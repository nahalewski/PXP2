package org.pxp2.core;

import java.util.*;

/**
 * Original HLE implementations of PSP system services: SysMemUserForUser, UtilsForUser, sceRtc,
 * scePower, sceSuspendForUser, sceDisplay, sceCtrl and sceReg. Display timing uses a 59.94 Hz
 * virtual vblank that raises interrupt 30 and wakes vblank waiters.
 */
public final class HleSystem {
    public static final int VBLANK_INTERRUPT = 30;
    private static final long EPOCH_TICKS = 62135596800000000L; // microseconds from 0001-01-01 to 1970-01-01
    private final Kernel k; private final Memory m;
    private final long bootWallMicros = System.currentTimeMillis() * 1000L;
    private int compiledSdk, compilerVersion, rtcCallback; private long alarmTick;
    // display state
    public int frameBuffer, frameWidth = 512, pixelFormat = 3, displayMode, displayWidth = 480, displayHeight = 272, vcount;
    public final List<Integer> vblankFrames = new ArrayList<>();
    public Runnable onVblank;
    /** Called after each sceDisplaySetFrameBuf (frame generation marks the end of a frame); null when unused. */
    public Runnable onFrameBuf;
    // controller state
    public volatile int buttons; public volatile int analogX = 128, analogY = 128;
    /** Buttons visible to sceCtrl reads. PspSystem.startGame clears HOME: in GAME mode the HOME menu owns it. */
    public volatile int ctrlMask = ~0;
    private int samplingCycle, samplingMode;
    public Registry registry = new Registry();
    /** Diagnostic: log every registry key lookup with the returned type and size (-Dpxp2.traceRegistry=true). */
    public boolean traceRegistry = Boolean.getBoolean("pxp2.traceRegistry");

    public HleSystem(Kernel k) {
        this.k = k; this.m = k.memory;
        registerSysMem(); registerUtils(); registerRtc(); registerPower(); registerDisplay(); registerCtrl(); registerHprm(); registerRegistry();
        scheduleVblank(k.now() + 16683);
    }
    public long wallMicros() { return bootWallMicros + k.now(); }

    // ---- SysMemUserForUser ------------------------------------------------------------------
    private void registerSysMem() {
        String lib = "SysMemUserForUser";
        k.register(lib, 0x237dbd4f, "sceKernelAllocPartitionMemory", c -> {
            int partition = c.arg(0), type = c.arg(2), size = c.arg(3);
            if (!k.sysmem.validPartition(partition)) return 0x800200d6;
            if (type < 0 || type > 4) return 0x800200d8; // ILLEGAL_MEMBLOCK_ALLOC_TYPE
            int a = k.sysmem.allocate(partition, k.string(c.arg(1)), type, size, c.arg(4));
            if (a == 0) return 0x800200d9; // FAILED_ALLOC_MEMBLOCK
            return k.sysmem.block(a).uid;
        });
        k.register(lib, 0xb6d61d02, "sceKernelFreePartitionMemory", c -> {
            SysMem.Block b = k.object(c.arg(0), SysMem.Block.class); if (b == null) return 0x800200cb; k.sysmem.free(b.address); return 0;
        });
        k.register(lib, 0x9d9a5ba1, "sceKernelGetBlockHeadAddr", c -> { SysMem.Block b = k.object(c.arg(0), SysMem.Block.class); return b == null ? 0x800200cb : b.address; });
        k.register(lib, 0xf919f628, "sceKernelTotalFreeMemSize", c -> k.sysmem.totalFree(2));
        k.register(lib, 0xa291f107, "sceKernelMaxFreeMemSize", c -> k.sysmem.maxFree(2));
        // Previously observed: returns the user partition size (24 MiB) on this model.
        k.register(lib, 0xacbd88ca, "SysMemUserForUser_ACBD88CA", c -> k.sysmem.partitionSize(2));
        k.register(lib, 0x2a3e5280, "sceKernelQueryMemoryInfo", c -> {
            int partition = k.sysmem.partitionOf(c.arg(0) & 0x1fffffff);
            if (partition == 0) return 0x800200d3; // ILLEGAL_ADDR
            SysMem.Block b = k.sysmem.blockContaining(c.arg(0) & 0x1fffffff);
            if (c.arg(1) != 0) m.write32(c.arg(1), partition); if (c.arg(2) != 0) m.write32(c.arg(2), b == null ? 0 : b.uid);
            return 0;
        });
        k.register(lib, 0x13a5abef, "sceKernelPrintf", c -> { k.log("printf: " + k.string(c.arg(0))); return 0; });
        k.register(lib, 0x3fc9ae6a, "sceKernelDevkitVersion", c -> 0x06060110);
        k.register(lib, 0xfc114573, "sceKernelGetCompiledSdkVersion", c -> compiledSdk);
        k.register(lib, 0xf77d77cb, "sceKernelSetCompilerVersion", c -> { compilerVersion = c.arg(0); return 0; });
        k.register(lib, 0xa6848df8, "sceKernelSetUsersystemLibWork", c -> 0);
        int[] sdkNids = {0x7591c7db, 0x342061e5, 0x315ad3a0, 0xebd5c3e6, 0x057e7380, 0x91de343c, 0x7893f79a, 0x35669d4c, 0x1b4217bc, 0x358ca1bb};
        for (int nid : sdkNids) k.register(lib, nid, String.format(Locale.ROOT, "sceKernelSetCompiledSdkVersion_%08x", nid), c -> { compiledSdk = c.arg(0); return 0; });
    }

    // ---- UtilsForUser ---------------------------------------------------------------------------
    private void registerUtils() {
        String lib = "UtilsForUser";
        for (int nid : new int[]{0x3ee30821, 0x79d1c3fa, 0xbfa98062, 0x34b9fa9e, 0xb435dec5, 0x920f104a, 0xc2df770e})
            k.register(lib, nid, String.format(Locale.ROOT, "sceKernelDcache/Icache_%08x", nid), c -> 0);
        k.register(lib, 0x27cc57f0, "sceKernelLibcTime", c -> { int t = (int)(wallMicros() / 1000000); if (c.arg(0) != 0) m.write32(c.arg(0), t); return t; });
        k.register(lib, 0x71ec4271, "sceKernelLibcGettimeofday", c -> {
            long w = wallMicros(); if (c.arg(0) != 0) { m.write32(c.arg(0), (int)(w / 1000000)); m.write32(c.arg(0) + 4, (int)(w % 1000000)); }
            if (c.arg(1) != 0) { m.write32(c.arg(1), 0); m.write32(c.arg(1) + 4, 0); } return 0;
        });
        k.register(lib, 0x91e4f6a7, "sceKernelLibcClock", c -> (int)k.now());
        k.register("sceDmac", 0x617f3fe6, "sceDmacMemcpy", c -> { if (c.arg(2) > 0) m.copy(c.arg(0), c.arg(1), c.arg(2)); return 0; });
        k.register("sceDmac", 0xd97f94d8, "sceDmacTryMemcpy", c -> { if (c.arg(2) > 0) m.copy(c.arg(0), c.arg(1), c.arg(2)); return 0; });
    }

    // ---- sceRtc ---------------------------------------------------------------------------------
    private void writeDate(int at, long ticks) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")); long unixMicros = ticks - EPOCH_TICKS;
        cal.setTimeInMillis(Math.floorDiv(unixMicros, 1000));
        m.write16(at, cal.get(Calendar.YEAR)); m.write16(at + 2, cal.get(Calendar.MONTH) + 1); m.write16(at + 4, cal.get(Calendar.DAY_OF_MONTH));
        m.write16(at + 6, cal.get(Calendar.HOUR_OF_DAY)); m.write16(at + 8, cal.get(Calendar.MINUTE)); m.write16(at + 10, cal.get(Calendar.SECOND));
        m.write32(at + 12, (int)Math.floorMod(unixMicros, 1000000L));
    }
    private long readDate(int at) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")); cal.clear();
        cal.set(m.read16(at), m.read16(at + 2) - 1, m.read16(at + 4), m.read16(at + 6), m.read16(at + 8), m.read16(at + 10));
        return cal.getTimeInMillis() * 1000L + Integer.toUnsignedLong(m.read32(at + 12)) + EPOCH_TICKS;
    }
    private static long addCalendar(long ticks, int field, int amount) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")); long unix = ticks - EPOCH_TICKS;
        cal.setTimeInMillis(Math.floorDiv(unix, 1000)); cal.add(field, amount);
        return cal.getTimeInMillis() * 1000L + Math.floorMod(unix, 1000L) + EPOCH_TICKS;
    }
    /** Stores the wake alarm tick (0 pointer or 0 tick cancels). The alarm is recorded but never fires a power-on event. */
    public int setAlarmTick(int pointer) { alarmTick = pointer == 0 ? 0 : m.read64(pointer); return 0; }
    private long localOffsetMicros() { return TimeZone.getDefault().getOffset(wallMicros() / 1000) * 1000L; }
    private void registerRtc() {
        String lib = "sceRtc";
        k.register(lib, 0xc41c2853, "sceRtcGetTickResolution", c -> 1000000);
        k.register(lib, 0x3f7ad767, "sceRtcGetCurrentTick", c -> { m.write64(c.arg(0), wallMicros() + EPOCH_TICKS); return 0; });
        k.register(lib, 0x4cfa57b0, "sceRtcGetCurrentClock", c -> { writeDate(c.arg(0), wallMicros() + EPOCH_TICKS + c.arg(1) * 60_000_000L); return 0; });
        k.register(lib, 0xe7c27d1b, "sceRtcGetCurrentClockLocalTime", c -> { writeDate(c.arg(0), wallMicros() + EPOCH_TICKS + localOffsetMicros()); return 0; });
        k.register(lib, 0x34885e0d, "sceRtcConvertUtcToLocalTime", c -> { m.write64(c.arg(1), m.read64(c.arg(0)) + localOffsetMicros()); return 0; });
        k.register(lib, 0x779242a2, "sceRtcConvertLocalTimeToUTC", c -> { m.write64(c.arg(1), m.read64(c.arg(0)) - localOffsetMicros()); return 0; });
        k.register(lib, 0x6ff40acc, "sceRtcGetTick", c -> { m.write64(c.arg(1), readDate(c.arg(0))); return 0; });
        k.register(lib, 0x7ed29e40, "sceRtcSetTick", c -> { writeDate(c.arg(0), m.read64(c.arg(1))); return 0; });
        k.register(lib, 0x57726bc1, "sceRtcGetDayOfWeek", c -> {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")); cal.clear(); cal.set(c.arg(0), c.arg(1) - 1, c.arg(2));
            return cal.get(Calendar.DAY_OF_WEEK) - 1;
        });
        k.register(lib, 0x05ef322c, "sceRtcGetDaysInMonth", c -> {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")); cal.clear(); cal.set(c.arg(0), c.arg(1) - 1, 1);
            return cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        });
        k.register(lib, 0x42307a17, "sceRtcIsLeapYear", c -> { int y = c.arg(0); return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 ? 1 : 0; });
        k.register(lib, 0x44f45e05, "sceRtcTickAddTicks", c -> { m.write64(c.arg(0), m.read64(c.arg(1)) + c.arg64(2)); return 0; });
        k.register(lib, 0x26d25a5d, "sceRtcTickAddMicroseconds", c -> { m.write64(c.arg(0), m.read64(c.arg(1)) + c.arg64(2)); return 0; });
        k.register(lib, 0xf2a4afe5, "sceRtcTickAddSeconds", c -> { m.write64(c.arg(0), m.read64(c.arg(1)) + c.arg64(2) * 1000000L); return 0; });
        k.register(lib, 0xe6605bca, "sceRtcTickAddMinutes", c -> { m.write64(c.arg(0), m.read64(c.arg(1)) + c.arg64(2) * 60_000_000L); return 0; });
        k.register(lib, 0x26d7a24a, "sceRtcTickAddHours", c -> { m.write64(c.arg(0), m.read64(c.arg(1)) + c.arg(2) * 3_600_000_000L); return 0; });
        k.register(lib, 0xe51b4b7a, "sceRtcTickAddDays", c -> { m.write64(c.arg(0), m.read64(c.arg(1)) + c.arg(2) * 86_400_000_000L); return 0; });
        k.register(lib, 0xcf3a2ca8, "sceRtcTickAddWeeks", c -> { m.write64(c.arg(0), m.read64(c.arg(1)) + c.arg(2) * 604_800_000_000L); return 0; });
        k.register(lib, 0xdbf74f1b, "sceRtcTickAddMonths", c -> { m.write64(c.arg(0), addCalendar(m.read64(c.arg(1)), Calendar.MONTH, c.arg(2))); return 0; });
        k.register(lib, 0x42842c77, "sceRtcTickAddYears", c -> { m.write64(c.arg(0), addCalendar(m.read64(c.arg(1)), Calendar.YEAR, c.arg(2))); return 0; });
        k.register(lib, 0x9ed0ae87, "sceRtcCompareTick", c -> Long.compare(m.read64(c.arg(0)), m.read64(c.arg(1))));
        k.register(lib, 0x27c4594c, "sceRtcGetTime_t", c -> { m.write32(c.arg(1), (int)((readDate(c.arg(0)) - EPOCH_TICKS) / 1000000)); return 0; });
        k.register(lib, 0x3a807cc8, "sceRtcSetTime_t", c -> { writeDate(c.arg(0), Integer.toUnsignedLong(c.arg(1)) * 1000000L + EPOCH_TICKS); return 0; });
        k.register(lib, 0xe1c93e47, "sceRtcGetTime64_t", c -> { m.write64(c.arg(1), (readDate(c.arg(0)) - EPOCH_TICKS) / 1000000); return 0; });
        k.register(lib, 0x1909c99b, "sceRtcSetTime64_t", c -> { writeDate(c.arg(0), c.arg64(2) * 1000000L + EPOCH_TICKS); return 0; });
        k.register(lib, 0x4b1b5e82, "sceRtcCheckValid", c -> 0);
        k.register(lib, 0xc2ddbeb5, "sceRtcGetAlarmTick", c -> { if (c.arg(0) == 0) return 0x80000103; m.write64(c.arg(0), alarmTick); return 0; });
        k.register(lib, 0x7d1fbed3, "sceRtcSetAlarmTick", c -> setAlarmTick(c.arg(0)));
        k.register(lib, 0xfb3b18cd, "sceRtcRegisterCallback", c -> { rtcCallback = c.arg(0); return 0; });
        k.register(lib, 0x6a676d2d, "sceRtcUnregisterCallback", c -> { rtcCallback = 0; return 0; });
    }

    // ---- scePower / sceSuspendForUser -----------------------------------------------------------
    public volatile int batteryPercent = 100;
    public volatile boolean batteryCharging = false;
    public volatile boolean powerOnline = true;
    public volatile int batteryLifeTimeMinutes = 300;
    public volatile int batteryTempC = 28;
    public volatile int batteryVoltMv = 4135;
    public volatile boolean batteryChanged;

    public void updateBattery(int percent, boolean charging, boolean plugged, int tempC, int voltMv) {
        this.batteryPercent = Math.max(0, Math.min(100, percent));
        this.batteryCharging = charging;
        this.powerOnline = plugged;
        this.batteryTempC = tempC;
        this.batteryVoltMv = voltMv;
        this.batteryLifeTimeMinutes = charging ? 0 : Math.max(1, this.batteryPercent * 3);
        this.batteryChanged = true;
        if (vcount == 0) {
            batteryChanged = false;
            notifyPowerCallbacks();
        }
    }

    public int powerInfo() {
        int info = (batteryPercent & 0x7f) | 0x80;
        if (powerOnline || batteryCharging) info |= 0x1000;
        if (batteryPercent <= 15) info |= 0x100;
        return info;
    }

    public void notifyPowerCallbacks() {
        int info = powerInfo();
        for (int cb : powerCallbacks) {
            if (cb != 0) k.notifyCallback(cb, info);
        }
    }

    private final int[] powerCallbacks = new int[16];
    private int powerRequest; private boolean volatileLocked;
    private int volatileLock(int addressOut, int sizeOut, boolean block) {
        if (volatileLocked) { if (!block) return 0x802b0200; throw new Kernel.Stop("sceKernelVolatileMemLock would block (already locked)"); }
        volatileLocked = true;
        if (addressOut != 0) m.write32(addressOut, 0x08400000); if (sizeOut != 0) m.write32(sizeOut, 0x400000); return 0;
    }
    private void registerPower() {
        String lib = "scePower";
        k.register(lib, 0x04b7766e, "scePowerRegisterCallback", c -> {
            int slot = c.arg(0); if (slot == -1) { for (int i = 0; i < 16; i++) if (powerCallbacks[i] == 0) { slot = i; break; } }
            if (slot < 0 || slot >= 16) return 0x80000102; if (powerCallbacks[slot] != 0) return 0x80000020;
            powerCallbacks[slot] = c.arg(1); k.notifyCallback(c.arg(1), powerInfo());
            return slot;
        });
        k.register(lib, 0xdfa8baf8, "scePowerUnregisterCallback", c -> { if (c.arg(0) >= 0 && c.arg(0) < 16) powerCallbacks[c.arg(0)] = 0; return 0; });
        k.register(lib, 0xdb9d28dd, "scePowerUnregitserCallback", c -> { if (c.arg(0) >= 0 && c.arg(0) < 16) powerCallbacks[c.arg(0)] = 0; return 0; });
        k.register(lib, 0x2085d15d, "scePowerGetBatteryLifePercent", c -> batteryPercent);
        k.register(lib, 0x8efb3fa2, "scePowerGetBatteryLifeTime", c -> batteryLifeTimeMinutes);
        k.register(lib, 0x87440f5e, "scePowerIsPowerOnline", c -> powerOnline ? 1 : 0);
        k.register(lib, 0x0afd0d8b, "scePowerIsBatteryExist", c -> 1);
        k.register(lib, 0x1e490401, "scePowerIsBatteryCharging", c -> batteryCharging ? 1 : 0);
        k.register(lib, 0xb4432bc8, "scePowerGetBatteryChargingStatus", c -> batteryCharging ? 1 : 0);
        k.register(lib, 0xd3075926, "scePowerIsLowBattery", c -> (batteryPercent <= 15 && !batteryCharging) ? 1 : 0);
        k.register(lib, 0x28e12023, "scePowerGetBatteryTemp", c -> batteryTempC);
        k.register(lib, 0x483ce86b, "scePowerGetBatteryVolt", c -> batteryVoltMv);
        k.register(lib, 0xfee03a2f, "scePowerGetCpuClockFrequency", c -> 222);
        k.register(lib, 0xfdb5bfe9, "scePowerGetCpuClockFrequencyInt", c -> 222);
        k.register(lib, 0x478fe6f5, "scePowerGetBusClockFrequency", c -> 111);
        k.register(lib, 0xbd681969, "scePowerGetBusClockFrequencyInt", c -> 111);
        k.register(lib, 0x737486f2, "scePowerSetClockFrequency", c -> 0);
        k.register(lib, 0x469989ad, "scePower_469989AD (set clock frequency)", c -> 0);
        k.register(lib, 0x843fbf43, "scePowerSetCpuClockFrequency", c -> 0);
        k.register(lib, 0xb8d7b3fb, "scePowerSetBusClockFrequency", c -> 0);
        k.register(lib, 0x34f9c463, "scePowerGetPllClockFrequencyInt", c -> 222);
        k.register(lib, 0xea382a27, "scePowerGetPllClockFrequencyFloat", c -> { c.cpu.setFpr(0, 222f); return Float.floatToRawIntBits(222f); });
        k.register(lib, 0xa85880d0, "scePower_A85880D0 (games call it with the CPU clock; accepted)", c -> 0);
        k.register(lib, 0xefd3c963, "scePowerTick", c -> 0);
        k.register(lib, 0x78a1a796, "scePowerIsSuspendRequired", c -> 0);
        // Firmware power_01g.prx: atomically returns and clears the pending power-switch request word.
        k.register(lib, 0xdb62c9cf, "scePowerCancelRequest", c -> { int r = powerRequest; powerRequest = 0; return r; });
        k.register(lib, 0xac32c9cc, "scePowerRequestSuspend", c -> { k.log("Guest requested suspend"); return 0; });
        k.register(lib, 0x2b7c7cf4, "scePowerRequestStandby", c -> { k.log("Guest requested standby"); return 0; });
        k.register(lib, 0xd6d016ef, "scePowerLock", c -> 0);
        k.register(lib, 0xca3d34c1, "scePowerUnlock", c -> 0);
        String sus = "sceSuspendForUser";
        k.register(sus, 0xeadb1bd7, "sceKernelPowerLock", c -> 0);
        k.register(sus, 0x3aee7261, "sceKernelPowerUnlock", c -> 0);
        k.register(sus, 0x090ccb3f, "sceKernelPowerTick", c -> 0);
        // Volatile memory: the 4 MiB region at 0x08400000 lent to the VSH/games while locked (PXP2 partition 5 area).
        k.register(sus, 0x3e0271d3, "sceKernelVolatileMemLock", c -> volatileLock(c.arg(1), c.arg(2), true));
        k.register(sus, 0xa14f40b2, "sceKernelVolatileMemTryLock", c -> volatileLock(c.arg(1), c.arg(2), false));
        k.register(sus, 0xa569e425, "sceKernelVolatileMemUnlock", c -> { if (!volatileLocked) return 0x80000030; volatileLocked = false; return 0; });
    }

    // ---- sceDisplay -----------------------------------------------------------------------------
    private void scheduleVblank(long at) {
        k.schedule(at, () -> {
            vcount++;
            if (batteryChanged) {
                batteryChanged = false;
                notifyPowerCallbacks();
            }
            for (Kernel.GuestThread t : k.threads()) if (t.wait != null && t.wait.type.equals("VBlank")) k.wake(t, t.wait.data instanceof Integer ? (Integer)t.wait.data : 0);
            if (k.threadman != null) k.threadman.raise(VBLANK_INTERRUPT);
            if (onVblank != null) onVblank.run();
            scheduleVblank(at + 16683);
        });
    }
    private int waitVblank(boolean cb) { k.waitCurrent(new Kernel.Wait("VBlank", 0), 0, cb); return 0; }
    private void registerDisplay() {
        String lib = "sceDisplay";
        k.register(lib, 0x0e20f177, "sceDisplaySetMode", c -> { displayMode = c.arg(0); displayWidth = c.arg(1); displayHeight = c.arg(2); return 0; });
        k.register(lib, 0xdea197d4, "sceDisplayGetMode", c -> { // null pointers are skipped (display.prx 08801784)
            if (c.arg(0) != 0) m.write32(c.arg(0), displayMode); if (c.arg(1) != 0) m.write32(c.arg(1), displayWidth); if (c.arg(2) != 0) m.write32(c.arg(2), displayHeight); return 0; });
        k.register(lib, 0x289d82fe, "sceDisplaySetFrameBuf", c -> {
            if (c.arg(0) != 0 && (c.arg(2) < 0 || c.arg(2) > 3)) return 0x80000107;
            frameBuffer = c.arg(0); if (c.arg(1) > 0) frameWidth = c.arg(1); pixelFormat = c.arg(2);
            Runnable fb = onFrameBuf; if (fb != null) fb.run();
            return 0;
        });
        k.register(lib, 0xeeda2e54, "sceDisplayGetFrameBuf", c -> {
            if (c.arg(0) != 0) m.write32(c.arg(0), frameBuffer); if (c.arg(1) != 0) m.write32(c.arg(1), frameWidth); if (c.arg(2) != 0) m.write32(c.arg(2), pixelFormat); return 0;
        });
        k.register(lib, 0x984c27e7, "sceDisplayWaitVblankStart", c -> waitVblank(false));
        k.register(lib, 0x46f186c3, "sceDisplayWaitVblankStartCB", c -> waitVblank(true));
        k.register(lib, 0x36cdfade, "sceDisplayWaitVblank", c -> waitVblank(false));
        k.register(lib, 0x8eb9ec49, "sceDisplayWaitVblankCB", c -> waitVblank(true));
        k.register(lib, 0x9c6eaad7, "sceDisplayGetVcount", c -> vcount);
        k.register(lib, 0x4d4e10ec, "sceDisplayIsVblank", c -> 0);
        k.register(lib, 0x773dd3a3, "sceDisplayGetCurrentHcount", c -> (int)((k.now() % 16683) * 286 / 16683));
        k.register(lib, 0x210eab3a, "sceDisplayGetAccumulatedHcount", c -> (int)(k.now() * 286 / 16683));
        k.register(lib, 0xdba6c4c4, "sceDisplayGetFramePerSec", c -> { c.cpu.setFpr(0, 59.94f); return Float.floatToRawIntBits(59.94f); });
        k.register(lib, 0x7ed59bc4, "sceDisplaySetHoldMode", c -> 0);
        k.register(lib, 0xa544c486, "sceDisplaySetResumeMode", c -> 0);
        k.register(lib, 0xbf79f646, "sceDisplayGetResumeMode", c -> { if (c.arg(0) != 0) m.write32(c.arg(0), 0); return 0; });
        k.register(lib, 0xb4f378fa, "sceDisplayIsForeground", c -> 1);
    }
    /** Converts the current framebuffer to ARGB pixels (480x272), or null when none is set. */
    public int[] snapshot() {
        if (frameBuffer == 0 || !m.valid(frameBuffer, 4)) return null;
        int[] out = new int[480 * 272]; int bpp = pixelFormat == 3 ? 4 : 2;
        for (int y = 0; y < 272; y++) for (int x = 0; x < 480; x++) {
            int a = frameBuffer + (y * frameWidth + x) * bpp; if (!m.valid(a, bpp)) return out;
            int argb;
            if (bpp == 4) { int p = m.read32(a); argb = 0xff000000 | ((p & 0xff) << 16) | (p & 0xff00) | ((p >>> 16) & 0xff); }
            else {
                int p = m.read16(a), r, g, b;
                switch (pixelFormat) {
                    case 0: r = (p & 31) << 3; g = ((p >>> 5) & 63) << 2; b = ((p >>> 11) & 31) << 3; break;
                    case 1: r = (p & 31) << 3; g = ((p >>> 5) & 31) << 3; b = ((p >>> 10) & 31) << 3; break;
                    default: r = (p & 15) << 4; g = ((p >>> 4) & 15) << 4; b = ((p >>> 8) & 15) << 4; break;
                }
                argb = 0xff000000 | (r << 16) | (g << 8) | b;
            }
            out[y * 480 + x] = argb;
        }
        return out;
    }

    // ---- sceCtrl --------------------------------------------------------------------------------
    private void writeSample(int at, boolean negative) {
        int b = buttons & ctrlMask;
        m.write32(at, (int)k.now()); m.write32(at + 4, negative ? ~b : b);
        m.write8(at + 8, analogX); m.write8(at + 9, analogY); for (int i = 10; i < 16; i++) m.write8(at + i, 0);
    }
    public int readBuffer(int at, int count, boolean negative, boolean block) {
        if (count < 1) return 0x80000104; int n = Math.min(count, 64);
        for (int i = 0; i < n; i++) writeSample(at + i * 16, negative);
        if (block) { Kernel.Wait w = new Kernel.Wait("VBlank", 0); w.data = n; k.waitCurrent(w, 0, false); } // resumes with n at the next sample
        return n;
    }
    private void registerCtrl() {
        String lib = "sceCtrl";
        k.register(lib, 0x6a2774f3, "sceCtrlSetSamplingCycle", c -> { int old = samplingCycle; samplingCycle = c.arg(0); return old; });
        k.register(lib, 0x02baad91, "sceCtrlGetSamplingCycle", c -> { m.write32(c.arg(0), samplingCycle); return 0; });
        k.register(lib, 0x1f4011e6, "sceCtrlSetSamplingMode", c -> { int old = samplingMode; samplingMode = c.arg(0); return old; });
        k.register(lib, 0xda6b76a1, "sceCtrlGetSamplingMode", c -> { m.write32(c.arg(0), samplingMode); return 0; });
        k.register(lib, 0x3a622550, "sceCtrlPeekBufferPositive", c -> readBuffer(c.arg(0), c.arg(1), false, false));
        k.register(lib, 0xc152080a, "sceCtrlPeekBufferNegative", c -> readBuffer(c.arg(0), c.arg(1), true, false));
        k.register(lib, 0x1f803938, "sceCtrlReadBufferPositive", c -> readBuffer(c.arg(0), c.arg(1), false, true));
        k.register(lib, 0x60b81f86, "sceCtrlReadBufferNegative", c -> readBuffer(c.arg(0), c.arg(1), true, true));
        k.register(lib, 0xb1d0e5cd, "sceCtrlPeekLatch", c -> { m.write32(c.arg(0), 0); m.write32(c.arg(0) + 4, 0); m.write32(c.arg(0) + 8, buttons & ctrlMask); m.write32(c.arg(0) + 12, ~(buttons & ctrlMask)); return 0; });
        k.register(lib, 0x0b588501, "sceCtrlReadLatch", c -> { m.write32(c.arg(0), 0); m.write32(c.arg(0) + 4, 0); m.write32(c.arg(0) + 8, buttons & ctrlMask); m.write32(c.arg(0) + 12, ~(buttons & ctrlMask)); return 0; });
        k.register(lib, 0xa7144800, "sceCtrlSetIdleCancelThreshold", c -> 0);
        k.register(lib, 0x687660fa, "sceCtrlGetIdleCancelThreshold", c -> { if (c.arg(0) != 0) m.write32(c.arg(0), -1); if (c.arg(1) != 0) m.write32(c.arg(1), -1); return 0; });
    }

    // ---- sceHprm (headphone remote) -----------------------------------------------------------------
    /** No remote, headphones or microphone are plugged in: every key reads as released. */
    private void registerHprm() {
        String lib = "sceHprm";
        k.register(lib, 0x1910b327, "sceHprmPeekCurrentKey", c -> { m.write32(c.arg(0), 0); return 0; });
        k.register(lib, 0x2bcec83e, "sceHprmPeekLatch", c -> { for (int i = 0; i < 16; i += 4) m.write32(c.arg(0) + i, 0); return 0; });
        k.register(lib, 0x40d2f9f0, "sceHprmReadLatch", c -> { for (int i = 0; i < 16; i += 4) m.write32(c.arg(0) + i, 0); return 0; });
        k.register(lib, 0x208db1bd, "sceHprmIsRemoteExist", c -> 0);
        k.register(lib, 0x7e69eda4, "sceHprmIsHeadphoneExist", c -> 0);
        k.register(lib, 0x219c58f1, "sceHprmIsMicrophoneExist", c -> 0);
    }

    // ---- sceReg -----------------------------------------------------------------------------------
    private final Map<Integer, String> categoryHandles = new HashMap<>();
    private final Map<Integer, String[]> keyHandles = new HashMap<>();
    private int nextHandle = 0x100;
    private void registerRegistry() {
        String lib = "sceReg";
        k.register(lib, 0x92e41280, "sceRegOpenRegistry", c -> { m.write32(c.arg(2), 1); return 0; });
        k.register(lib, 0xfa8a5739, "sceRegCloseRegistry", c -> 0);
        k.register(lib, 0xdeda92bf, "sceRegRemoveRegistry", c -> 0);
        k.register(lib, 0x39461b4d, "sceRegFlushRegistry", c -> flushRegistry());
        k.register(lib, 0x0d69bf40, "sceRegFlushCategory", c -> flushRegistry());
        k.register(lib, 0x1d8a762e, "sceRegOpenCategory", c -> {
            String path = Registry.normalise(k.string(c.arg(1)));
            if (!registry.hasCategory(path)) {
                if ((c.arg(2) & 2) == 0) { k.log("sceReg: missing category " + path); return Registry.NOT_FOUND; }
                registry.createCategory(path);
            }
            int h = nextHandle++; categoryHandles.put(h, path); m.write32(c.arg(3), h); return 0;
        });
        k.register(lib, 0x0cae832b, "sceRegCloseCategory", c -> { categoryHandles.remove(c.arg(0)); return flushRegistry(); });
        k.register(lib, 0x4ca16893, "sceRegRemoveCategory", c -> 0);
        k.register(lib, 0xd4475aa8, "sceRegGetKeyInfo", c -> {
            String cat = categoryHandles.get(c.arg(0)); if (cat == null) return Registry.NOT_FOUND;
            String name = k.string(c.arg(1)); Registry.Value v = registry.get(cat, name);
            if (v == null) { k.log("sceReg: missing key " + cat + "/" + name); return Registry.NOT_FOUND; }
            int h = nextHandle++; keyHandles.put(h, new String[]{cat, name});
            m.write32(c.arg(2), h); m.write32(c.arg(3), v.type); m.write32(c.arg(4), v.size()); return 0;
        });
        k.register(lib, 0xc5768d02, "sceRegGetKeyInfoByName", c -> {
            String cat = categoryHandles.get(c.arg(0)); if (cat == null) return Registry.NOT_FOUND;
            String name = k.string(c.arg(1)); Registry.Value v = registry.get(cat, name);
            if (v == null) { k.log("sceReg: missing key " + cat + "/" + name); return Registry.NOT_FOUND; }
            if (traceRegistry) k.log("sceReg: info " + cat + "/" + name + " type=" + v.type + " size=" + v.size());
            m.write32(c.arg(2), v.type); m.write32(c.arg(3), v.size()); return 0;
        });
        k.register(lib, 0x28a8e98a, "sceRegGetKeyValue", c -> {
            String[] key = keyHandles.get(c.arg(1)); if (key == null) return Registry.NOT_FOUND;
            return copyOut(registry.get(key[0], key[1]), c.arg(2), c.arg(3));
        });
        k.register(lib, 0x30be0259, "sceRegGetKeyValueByName", c -> {
            String cat = categoryHandles.get(c.arg(0)); if (cat == null) return Registry.NOT_FOUND;
            String name = k.string(c.arg(1)); Registry.Value v = registry.get(cat, name);
            if (v == null) { k.log("sceReg: missing key " + cat + "/" + name); return Registry.NOT_FOUND; }
            return copyOut(v, c.arg(2), c.arg(3));
        });
        k.register(lib, 0x17768e14, "sceRegSetKeyValue", c -> {
            String cat = categoryHandles.get(c.arg(0)); if (cat == null) return Registry.NOT_FOUND;
            String name = k.string(c.arg(1)); Registry.Value old = registry.get(cat, name);
            int type = old == null ? (c.arg(3) == 4 ? Registry.INT : Registry.BINARY) : old.type;
            registry.set(cat, name, type, m.read(c.arg(2), c.arg(3))); return 0;
        });
        k.register(lib, 0x57641a81, "sceRegCreateKey", c -> {
            String cat = categoryHandles.get(c.arg(0)); if (cat == null) return Registry.NOT_FOUND;
            String name = k.string(c.arg(1)); if (registry.get(cat, name) == null) registry.set(cat, name, c.arg(2), new byte[Math.max(0, c.arg(3))]); return 0;
        });
        k.register(lib, 0x2c0db9dd, "sceRegGetKeysNum", c -> {
            String cat = categoryHandles.get(c.arg(0)); if (cat == null) return Registry.NOT_FOUND;
            m.write32(c.arg(1), registry.keys(cat).size()); return 0;
        });
        k.register(lib, 0x2d211135, "sceRegGetKeys", c -> {
            String cat = categoryHandles.get(c.arg(0)); if (cat == null) return Registry.NOT_FOUND;
            List<String> keys = registry.keys(cat);
            for (int i = 0; i < Math.min(keys.size(), c.arg(2)); i++) m.writeString(c.arg(1) + i * 27, keys.get(i), 27);
            return 0;
        });
    }
    /** Where the registry is persisted (flash1/registry), or null to keep it in memory only. */
    public java.io.File registryFile;
    private int flushRegistry() {
        if (registryFile != null && registry.dirty) { try { registry.save(registryFile); } catch (java.io.IOException e) { k.log("Registry save failed: " + e.getMessage()); } }
        return 0;
    }
    private int copyOut(Registry.Value v, int buffer, int size) {
        if (v == null) return Registry.NOT_FOUND;
        byte[] data = v.data; m.fill(buffer, 0, size); m.write(buffer, data, 0, Math.min(size, data.length)); return 0;
    }
}
