package org.pxp2.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Original HLE "SystemControl" for SEPLUGINS support: the CFW plugin API (SystemCtrlForKernel / SystemCtrlForUser,
 * KUBridge) plus the kernel-mode libraries plugins import (LoadCoreForKernel, KDebugForKernel, SysclibForKernel,
 * InitForKernel, and kernel names of libraries PXP2 already serves in user mode). See psp.md "SEPLUGINS".
 *
 * <p><b>NIDs.</b> CFW SystemControl exports use hash NIDs: the first four bytes of SHA-1(function name), little endian
 * (psp-build-exports PSP_EXPORT_FUNC). They are computed here from the names. The few non-hash aliases, and every
 * kernel-library NID below, come from the stub archives of the public PSPSDK (pspdev: libpspsystemctrl_kernel.a,
 * libpspsystemctrl_user.a, libpspkubridge.a, libpspkernel.a and the *_kernel_660.a libraries), which is what
 * plugins are linked against. No CFW code is used.
 *
 * <p><b>Module control blocks.</b> Functions that return a SceModule2* (sceKernelFindModuleByName, the start-module
 * handler argument) get a 256-byte block in kernel memory laid out as the 6.xx SceModule2 of the public CFW headers:
 * +0x04 attribute, +0x06 version, +0x08 modname[27], +0x2c modid, +0x50 module_start, +0x54 module_stop,
 * +0x64 entry_addr, +0x68 gp_value, +0x6c text_addr, +0x70 text_size, +0x74 data_size, +0x78 bss_size,
 * +0x7c nsegment, +0x80 segmentaddr[4], +0x90 segmentsize[4].
 */
public final class HleSystemCtrl {
    /** Boot mode seen by plugins (sctrl/InitForKernel queries). */
    public enum Mode { VSH, GAME, POPS }

    private final Kernel k; private final Memory m;
    public Mode mode = Mode.VSH;
    /** Executable path of the running game (GAME mode), for sceKernelInitFileName / sctrlGetInitPARAM. */
    public String gamePath;
    /** Modules loaded from SEPLUGINS (uids); imports they lack return an error instead of stopping emulation. */
    public final Set<Integer> pluginModules = new HashSet<>();
    /** Set by {@link Plugins} while a plugin is linked, so its missing imports get "soft" stubs. */
    boolean linkingPlugin;
    /** Imports plugins have that PXP2 does not implement, "library:nid" → count of calls so far. */
    public final Map<String, Integer> missingImports = new TreeMap<>();

    /** Version values reported to plugins. PXP2's own choice (not a real CFW's); documented in psp.md. */
    public static final int HEN_VERSION = 0x00001000, HEN_MINOR = 0, SE_VERSION = 0x00060000;
    private int userLevel = 4, devkitOverride, startHandler, seConfigSize;
    private final byte[] seConfig = new byte[36]; // SEConfig as in the public ARK headers: 34 bytes (+2 padding)
    public java.io.File seConfigFile;

    public HleSystemCtrl(Kernel k) {
        this.k = k; this.m = k.memory;
        registerSystemCtrl(); registerKuBridge(); registerKernelLibraries();
        k.importResolver = this::resolveImport;
        k.modules.onLinked = this::onLinked;
        k.modules.onStart = this::onStart;
    }

    /** Registers an HLE function unless another part of PXP2 already serves that NID (theirs is kept). */
    private void reg(String library, int nid, String name, Kernel.Hle h) { if (k.function(library, nid) == null) k.register(library, nid, name, h); }
    /** First four bytes of SHA-1(name), little endian: the NID psp-build-exports gives a PSP_EXPORT_FUNC entry. */
    public static int hashNid(String name) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(name.getBytes(StandardCharsets.US_ASCII));
            return (d[0] & 255) | (d[1] & 255) << 8 | (d[2] & 255) << 16 | (d[3] & 255) << 24;
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ---- SystemCtrlForKernel / SystemCtrlForUser ----------------------------------------------------------
    /** Names implemented (both libraries), and explicit extra NIDs from the PSPSDK stub archives. */
    private void sctrl(String name, Kernel.Hle h, int... extraNids) {
        for (String lib : new String[]{"SystemCtrlForKernel", "SystemCtrlForUser"}) {
            reg(lib, hashNid(name), name, h);
            for (int nid : extraNids) if (nid != hashNid(name)) reg(lib, nid, name + String.format(Locale.ROOT, "_%08x", nid), h);
        }
    }
    private void registerSystemCtrl() {
        sctrl("sctrlHENGetVersion", c -> HEN_VERSION);
        sctrl("sctrlHENGetMinorVersion", c -> HEN_MINOR);
        sctrl("sctrlSEGetVersion", c -> SE_VERSION);
        sctrl("sctrlHENIsSE", c -> 1);
        sctrl("sctrlHENIsDevhook", c -> 0);
        sctrl("sctrlHENIsToolKit", c -> 0, 0x22a45db7);
        sctrl("sctrlKernelMsIsEf", c -> 0);
        sctrl("sctrlHENIsSystemBooted", c -> 1);
        sctrl("sctrlIsLoadingPlugins", c -> 0);
        sctrl("sctrlHENFindFunction", c -> findFunction(c.string(c.arg(0)), c.arg(1) == 0 ? null : c.string(c.arg(1)), c.arg(2)));
        sctrl("sctrlHENFindFunctionInMod", c -> { LoadedModuleRef r = moduleOfBlock(c.arg(0)); return r == null ? 0 : findFunction(r.m.prx.name, c.arg(1) == 0 ? null : c.string(c.arg(1)), c.arg(2)); });
        sctrl("sctrlHENSetStartModuleHandler", c -> { int old = startHandler; startHandler = c.arg(0); k.log(String.format(Locale.ROOT, "sctrlHENSetStartModuleHandler(%08x), previous %08x", startHandler, old)); return old; });
        sctrl("sctrlHENPatchSyscall", c -> { patchSyscall(c.arg(0), c.arg(1)); return 0; }, 0x826668e9, 0x02bfcb5f);
        sctrl("sctrlKernelQuerySystemCall", c -> querySystemCall(c.arg(0)));
        // sctrlHENSetMemory(p2 MiB, p9 MiB): PXP2 can only switch to its fixed high-memory layout, and only before
        // anything is allocated in the user partition (normally it is chosen at boot from PARAM.SFO MEMSIZE; see SysMem).
        sctrl("sctrlHENSetMemory", c -> {
            boolean high = c.arg(0) > 24 && k.sysmem.useHighMemory();
            k.log(String.format(Locale.ROOT, "sctrlHENSetMemory(%d, %d): %s (RAM %d MiB, user partition %d MiB)", c.arg(0), c.arg(1),
                high ? "high-memory layout" : "layout unchanged", k.memory.size >> 20, k.sysmem.partitionSize(2) >> 20));
            return 0;
        });
        sctrl("sctrlHENApplyMemory", c -> 0);
        sctrl("sctrlHENSetSpeed", c -> { k.log(String.format(Locale.ROOT, "sctrlHENSetSpeed(%d, %d) ignored", c.arg(0), c.arg(1))); return 0; });
        sctrl("sctrlKernelSetUserLevel", c -> { int old = userLevel; userLevel = c.arg(0); return old; });
        sctrl("sctrlKernelSetDevkitVersion", c -> { int old = devkitOverride; devkitOverride = c.arg(0); return old; });
        sctrl("sctrlHENFakeDevkitVersion", c -> 0);
        sctrl("sctrlSEGetConfig", c -> getConfig(c.arg(0), 34));
        sctrl("sctrlSEGetConfigEx", c -> getConfig(c.arg(0), c.arg(1)));
        sctrl("sctrlSESetConfig", c -> setConfig(c.arg(0), 34));
        sctrl("sctrlSESetConfigEx", c -> setConfig(c.arg(0), c.arg(1)));
        sctrl("sctrlSEApplyConfig", c -> setConfig(c.arg(0), 34), 0x2f157baf);
        sctrl("sctrlModuleTextAddr", c -> { HleModules.LoadedModule mod = module(c.string(c.arg(0))); return mod == null ? 0 : mod.prx.base; });
        sctrl("sctrlPatchModule", c -> {
            HleModules.LoadedModule mod = module(c.string(c.arg(0))); if (mod == null || mod.prx.size == 0) return 0x8002012e;
            if (Integer.compareUnsigned(c.arg(2), mod.prx.size) >= 0) return Kernel.ILLEGAL_ADDR;
            m.write32(mod.prx.base + c.arg(2), c.arg(1)); return 0;
        });
        sctrl("sctrlFindImportByNID", c -> { LoadedModuleRef r = moduleOfBlock(c.arg(0)); return r == null ? 0 : importStub(r.m, c.string(c.arg(1)), c.arg(2)); });
        sctrl("sctrlHookImportByNID", c -> {
            LoadedModuleRef r = moduleOfBlock(c.arg(0)); if (r == null) return -1;
            int stub = importStub(r.m, c.string(c.arg(1)), c.arg(2)); if (stub == 0) return -1;
            if (c.arg(3) == 0) { m.write32(stub, 0x03e00008); m.write32(stub + 4, 0x24020000); } // jr ra; li v0, 0
            else { m.write32(stub, jump(c.arg(3))); m.write32(stub + 4, 0); }
            return 0;
        });
        sctrl("sctrlFlushCache", c -> 0);
        sctrl("sctrlKernelRand", c -> random.nextInt());
        sctrl("sctrlGetThreadUIDByName", c -> { String n = c.string(c.arg(0)); for (Kernel.GuestThread t : k.threads()) if (t.name.equals(n)) return t.uid; return 0x80020198; });
        sctrl("sctrlGetInitPARAM", c -> initParam(c.string(c.arg(0)), c.arg(1), c.arg(2), c.arg(3)));
        sctrl("sctrlGetSfoPARAM", c -> sfoParam(sfo(c.string(c.arg(0))), c.string(c.arg(1)), c.arg(2), c.arg(3), c.arg(4)));
        sctrl("sctrlKernelBootFrom", c -> bootFrom());
        sctrl("sctrlSEGetUmdFile", c -> umdFileString(), 0xac56b90b);
        sctrl("sctrlSESetUmdFile", c -> 0, 0xb64186d0);
        sctrl("sctrlSEGetDiscType", c -> 0x10);
        sctrl("sctrlKernelLoadExecVSHDisc", c -> forward("sceVshBridge", 0x63e69956, "sctrlKernelLoadExecVSHDisc"));
        sctrl("sctrlKernelLoadExecVSHMs2", c -> forward("sceVshBridge", 0x21d4d038, "sctrlKernelLoadExecVSHMs2"));
        sctrl("sctrlKernelExitVSH", c -> mode == Mode.GAME ? forward("LoadExecForUser", 0x05572a5f, "sctrlKernelExitVSH") : 0);
        sctrl("sctrlKernelLoadExecVSHWithApitype", c -> {
            int apitype = c.arg(0); c.cpu.setRegister(4, c.arg(1)); c.cpu.setRegister(5, c.arg(2)); // shift (apitype, file, param) to (file, param)
            if (apitype >= 0x120 && apitype < 0x130) return forward("sceVshBridge", 0x63e69956, "sctrlKernelLoadExecVSHWithApitype(disc)");
            if (apitype >= 0x140 && apitype < 0x160) return forward("sceVshBridge", 0x21d4d038, "sctrlKernelLoadExecVSHWithApitype(ms)");
            k.log(String.format(Locale.ROOT, "sctrlKernelLoadExecVSHWithApitype: apitype %x not supported", apitype)); return Kernel.ERROR;
        });
    }
    private final Random random = new Random(0x50585032L);

    /** Calls another HLE function with the current arguments (LoadExec requests reuse HleVsh's reboot path). */
    private int forward(String library, int nid, String what) {
        Kernel.Function f = k.function(library, nid);
        if (f == null) { k.log(what + ": target " + Kernel.key(library, nid) + " not available"); return Kernel.ERROR; }
        k.log(what + " -> " + f.name); return f.handler.call(k);
    }

    private int getConfig(int out, int size) {
        if (out == 0) return 0; loadConfig();
        int n = Math.max(0, Math.min(size, seConfig.length)); m.write(out, seConfig, 0, n); return out; // returns the pointer, as the API does
    }
    private int setConfig(int in, int size) {
        if (in == 0) return Kernel.ILLEGAL_ADDR; loadConfig();
        int n = Math.max(0, Math.min(size, seConfig.length));
        for (int i = 0; i < n; i++) seConfig[i] = (byte)m.read8(in + i);
        seConfigSize = Math.max(seConfigSize, n);
        if (seConfigFile != null) try { seConfigFile.getParentFile().mkdirs(); java.nio.file.Files.write(seConfigFile.toPath(), seConfig); } catch (java.io.IOException e) { k.log("SEConfig not saved: " + e.getMessage()); }
        return 0;
    }
    private boolean configLoaded;
    private void loadConfig() {
        if (configLoaded) return; configLoaded = true;
        if (seConfigFile != null && seConfigFile.isFile()) try { byte[] b = java.nio.file.Files.readAllBytes(seConfigFile.toPath()); System.arraycopy(b, 0, seConfig, 0, Math.min(b.length, seConfig.length)); } catch (java.io.IOException ignored) { }
    }

    private int umdFileBuffer;
    private int umdFileString() {
        if (umdFileBuffer == 0) { umdFileBuffer = k.sysmem.allocate(1, "sctrl umd file", SysMem.LOW, 0x100, 0); m.fill(umdFileBuffer, 0, 0x100); }
        return umdFileBuffer;
    }

    // ---- KUBridge (user-mode helper library of the CFW) ----------------------------------------------------
    private void registerKuBridge() {
        String lib = "KUBridge"; // NIDs = SHA-1 hashes of the names (checked against pspsdk libpspkubridge.a)
        ku(lib, "kuKernelGetModel", c -> k.model);
        ku(lib, "kuKernelGetUserLevel", c -> userLevel);
        ku(lib, "kuKernelBootFrom", c -> bootFrom());
        ku(lib, "kuKernelInitApitype", c -> apitype());
        ku(lib, "kuKernelInitKeyConfig", c -> keyConfig());
        ku(lib, "kuKernelInitFileName", c -> fileName());
        ku(lib, "kuKernelSetDdrMemoryProtection", c -> 0);
        ku(lib, "kuKernelPeekw", c -> m.read32(c.arg(0)));
        ku(lib, "kuKernelPokew", c -> { m.write32(c.arg(0), c.arg(1)); return 0; });
        ku(lib, "kuKernelMemcpy", c -> { if (c.arg(2) > 0) m.copy(c.arg(0), c.arg(1), c.arg(2)); return c.arg(0); });
        ku(lib, "kuKernelIcacheInvalidateAll", c -> 0);
        ku(lib, "kuKernelLoadModule", c -> k.modules.loadModule(c.string(c.arg(0)), c.arg(2)));
        ku(lib, "kuKernelFindModuleByName", c -> {
            HleModules.LoadedModule mod = module(c.string(c.arg(0))); if (mod == null) return 0x8002012e;
            if (c.arg(1) != 0) m.copy(c.arg(1), block(mod), 0x100); return 0; // copies the SceModule2 to the caller's buffer
        });
    }
    private void ku(String lib, String name, Kernel.Hle h) { reg(lib, hashNid(name), name, h); }

    // ---- kernel-mode libraries ------------------------------------------------------------------------------
    private void registerKernelLibraries() {
        // LoadCoreForKernel: old PSPSDK NIDs (libpspkernel.a) and 6.60 NIDs (libpsploadcore_kernel_660.a).
        String lib = "LoadCoreForKernel";
        for (int nid : new int[]{0xcf8a41b1, 0xf6b1bf0f}) reg(lib, nid, "sceKernelFindModuleByName_" + Integer.toHexString(nid), c -> { HleModules.LoadedModule mod = module(c.string(c.arg(0))); return mod == null ? 0 : block(mod); });
        for (int nid : new int[]{0xfb8ae27d, 0xbc99c625}) reg(lib, nid, "sceKernelFindModuleByAddress_" + Integer.toHexString(nid), c -> { HleModules.LoadedModule mod = k.modules.byAddress(c.arg(0)); return mod == null ? 0 : block(mod); });
        for (int nid : new int[]{0xcce4a157, 0x40972e6e}) reg(lib, nid, "sceKernelFindModuleByUID_" + Integer.toHexString(nid), c -> { HleModules.LoadedModule mod = k.object(c.arg(0), HleModules.LoadedModule.class); return mod == null ? 0 : block(mod); });
        // KDebugForKernel (libpspkernel.a)
        reg("KDebugForKernel", 0x84f370bc, "Kprintf", c -> { String s = format(c.arg(0), 1); print("Kprintf", s); return s.length(); });
        reg("KDebugForKernel", 0x7ceb2c09, "sceKernelRegisterKprintfHandler", c -> 0);
        reg("KDebugForKernel", 0xe146606d, "sceKernelRegisterDebugPutchar", c -> 0);
        // SysMemForKernel: sceKernelGetModel (0x6373995d old; 0x07c586a1 6.60, also what vshKernelGetModel wraps; see psp.md §3)
        for (int nid : new int[]{0x6373995d, 0x07c586a1}) reg("SysMemForKernel", nid, "sceKernelGetModel_" + Integer.toHexString(nid), c -> k.model);
        for (int nid : new int[]{0xb2c7aa36, 0x83b5226d}) reg("SysMemForKernel", nid, "sceKernelSetDdrMemoryProtection_" + Integer.toHexString(nid), c -> 0);
        reg("ThreadManForKernel", 0xf6427665, "sceKernelGetUserLevel", c -> userLevel);
        for (int nid : new int[]{0x8b61808b, 0xf153b371}) reg("InterruptManagerForKernel", nid, "sceKernelQuerySystemCall_" + Integer.toHexString(nid), c -> querySystemCall(c.arg(0)));
        // InitForKernel (same NIDs in libpspkernel.a and libpspinit_kernel_660.a)
        reg("InitForKernel", 0x27932388, "sceKernelBootFrom", c -> bootFrom());
        reg("InitForKernel", 0x7233b5bc, "sceKernelApplicationType", c -> keyConfig());
        reg("InitForKernel", 0x7a2333ad, "sceKernelInitApitype", c -> apitype());
        reg("InitForKernel", 0xa6e71b93, "sceKernelInitFileName", c -> fileName());
        registerSysclib();
    }
    private void print(String channel, String text) {
        k.io.stdout.append(text);
        for (String line : text.split("\n")) if (!line.isEmpty()) k.log(channel + ": " + line);
    }
    /** Boot device (InitForKernel sceKernelBootFrom): 0x20 UMD disc, 0x40 Memory Stick, 0 flash (VSH). */
    private int bootFrom() {
        if (mode != Mode.GAME || gamePath == null) return 0;
        return gamePath.toLowerCase(Locale.ROOT).startsWith("disc0:") || gamePath.toLowerCase(Locale.ROOT).startsWith("umd") ? 0x20 : 0x40;
    }
    /** Key config (sceKernelApplicationType): 0x100 VSH, 0x200 game, 0x300 POPS (PSPSDK pspinit.h). */
    private int keyConfig() { return mode == Mode.VSH ? 0x100 : mode == Mode.POPS ? 0x300 : 0x200; }
    /** API type: 0x120 UMD game, 0x141 Memory Stick game, 0x210 VSH. The VSH value is unverified. */
    private int apitype() { return mode == Mode.VSH ? 0x210 : bootFrom() == 0x20 ? 0x120 : 0x141; }
    private int fileNameBuffer;
    private int fileName() {
        if (mode != Mode.GAME || gamePath == null) return 0;
        if (fileNameBuffer == 0) { fileNameBuffer = k.sysmem.allocate(1, "init file name", SysMem.LOW, 0x100, 0); m.writeString(fileNameBuffer, gamePath, 0x100); }
        return fileNameBuffer;
    }

    // ---- SysclibForKernel ----------------------------------------------------------------------------------
    private void registerSysclib() {
        String lib = "SysclibForKernel"; // NIDs identical in libpspkernel.a and libpspsysclib_kernel_660.a
        reg(lib, 0x10f3bb61, "memset", c -> { if (c.arg(2) > 0) m.fill(c.arg(0), c.arg(1), c.arg(2)); return c.arg(0); });
        reg(lib, 0xab7592ff, "memcpy", c -> { if (c.arg(2) > 0) m.copy(c.arg(0), c.arg(1), c.arg(2)); return c.arg(0); });
        reg(lib, 0xa48d2592, "memmove", c -> { if (c.arg(2) > 0) { byte[] t = new byte[c.arg(2)]; for (int i = 0; i < t.length; i++) t[i] = (byte)m.read8(c.arg(1) + i); m.write(c.arg(0), t, 0, t.length); } return c.arg(0); });
        reg(lib, 0x81d0d1f7, "memcmp", c -> { for (int i = 0; i < c.arg(2); i++) { int d = m.read8(c.arg(0) + i) - m.read8(c.arg(1) + i); if (d != 0) return d; } return 0; });
        reg(lib, 0x52df196c, "strlen", c -> strlen(c.arg(0)));
        reg(lib, 0xc0ab8932, "strcmp", c -> strncmp(c.arg(0), c.arg(1), Integer.MAX_VALUE));
        reg(lib, 0x7ab35214, "strncmp", c -> strncmp(c.arg(0), c.arg(1), c.arg(2)));
        reg(lib, 0xec6f1cf2, "strcpy", c -> { int n = strlen(c.arg(1)); m.copy(c.arg(0), c.arg(1), n + 1); return c.arg(0); });
        reg(lib, 0xb49a7697, "strncpy", c -> { int n = c.arg(2), i = 0; for (; i < n; i++) { int ch = m.read8(c.arg(1) + i); m.write8(c.arg(0) + i, ch); if (ch == 0) break; } for (; i < n; i++) m.write8(c.arg(0) + i, 0); return c.arg(0); });
        reg(lib, 0x476fd94a, "strcat", c -> { int d = c.arg(0) + strlen(c.arg(0)); m.copy(d, c.arg(1), strlen(c.arg(1)) + 1); return c.arg(0); });
        reg(lib, 0xb1dc2ae8, "strchr", c -> { int ch = c.arg(1) & 255; for (int a = c.arg(0); ; a++) { int v = m.read8(a); if (v == ch) return a; if (v == 0) return 0; } });
        reg(lib, 0x4c0e0274, "strrchr", c -> { int ch = c.arg(1) & 255, last = 0; for (int a = c.arg(0); ; a++) { int v = m.read8(a); if (v == ch) last = a; if (v == 0) return last; } });
        reg(lib, 0xce2f7487, "toupper", c -> Character.toUpperCase((char)(c.arg(0) & 255)));
        reg(lib, 0x3ec5bbf6, "tolower", c -> Character.toLowerCase((char)(c.arg(0) & 255)));
        reg(lib, 0x7661e728, "sprintf", c -> { String s = format(c.arg(1), 2); m.writeString(c.arg(0), s, Integer.MAX_VALUE); return s.getBytes(StandardCharsets.UTF_8).length; });
        reg(lib, 0xc2145e80, "snprintf", c -> { String s = format(c.arg(2), 3); if (c.arg(1) > 0) m.writeString(c.arg(0), s, c.arg(1)); return s.getBytes(StandardCharsets.UTF_8).length; });
        reg(lib, 0x47dd934d, "strtol", c -> (int)strtol(c.arg(0), c.arg(1), c.arg(2)));
        reg(lib, 0x6a7900e1, "strtoul", c -> (int)strtol(c.arg(0), c.arg(1), c.arg(2)));
    }
    private int strlen(int a) { int n = 0; while (m.read8(a + n) != 0) n++; return n; }
    private int strncmp(int a, int b, int n) { for (int i = 0; i < n; i++) { int x = m.read8(a + i), y = m.read8(b + i); if (x != y) return x - y; if (x == 0) return 0; } return 0; }
    private long strtol(int s, int end, int base) {
        int p = s; while (m.read8(p) == ' ' || m.read8(p) == '\t') p++;
        boolean neg = false; if (m.read8(p) == '-' || m.read8(p) == '+') { neg = m.read8(p) == '-'; p++; }
        if ((base == 0 || base == 16) && m.read8(p) == '0' && (m.read8(p + 1) | 32) == 'x') { p += 2; base = 16; }
        else if (base == 0) base = m.read8(p) == '0' ? 8 : 10;
        long v = 0; for (;; p++) { int d = Character.digit((char)m.read8(p), base); if (d < 0) break; v = v * base + d; }
        if (end != 0) m.write32(end, p); return neg ? -v : v;
    }
    /**
     * printf-style formatting of a guest format string with guest arguments starting at argument index {@code first}:
     * %d %i %u %x %X %o %c %s %p %%, flags '-' '0' ' ' '+' '#', width, precision, and h/l/ll/z length modifiers
     * (64-bit values take an aligned register pair, as in the PSP EABI).
     */
    public String format(int fmtAddress, int first) {
        String f = m.string(fmtAddress, 4096); if (f == null) return "(null)";
        StringBuilder out = new StringBuilder(); int arg = first;
        for (int i = 0; i < f.length(); i++) {
            char ch = f.charAt(i); if (ch != '%') { out.append(ch); continue; }
            if (++i >= f.length()) break;
            String flags = ""; while (i < f.length() && "-0 +#".indexOf(f.charAt(i)) >= 0) flags += f.charAt(i++);
            int width = -1; if (i < f.length() && f.charAt(i) == '*') { width = k.arg(arg++); i++; } else { while (i < f.length() && Character.isDigit(f.charAt(i))) width = Math.max(0, width) * 10 + f.charAt(i++) - '0'; }
            int precision = -1; if (i < f.length() && f.charAt(i) == '.') { i++; precision = 0; if (i < f.length() && f.charAt(i) == '*') { precision = k.arg(arg++); i++; } else while (i < f.length() && Character.isDigit(f.charAt(i))) precision = precision * 10 + f.charAt(i++) - '0'; }
            int longs = 0; while (i < f.length() && "hlzjt".indexOf(f.charAt(i)) >= 0) { if (f.charAt(i) == 'l') longs++; i++; }
            if (i >= f.length()) break;
            char conv = f.charAt(i); String body;
            long v;
            switch (conv) {
                case '%': out.append('%'); continue;
                case 'c': body = String.valueOf((char)(k.arg(arg++) & 255)); break;
                case 's': { int a = k.arg(arg++); body = a == 0 ? "(null)" : m.string(a, 4096); if (precision >= 0 && body.length() > precision) body = body.substring(0, precision); break; }
                case 'd': case 'i': case 'u': case 'x': case 'X': case 'o': case 'p': {
                    if (longs >= 2) { if ((arg & 1) != 0) arg++; v = k.arg64(arg); arg += 2; }
                    else { int w = k.arg(arg++); v = conv == 'd' || conv == 'i' ? w : Integer.toUnsignedLong(w); }
                    if (conv == 'p') { body = "0x" + Long.toHexString(v); break; }
                    int radix = conv == 'x' || conv == 'X' ? 16 : conv == 'o' ? 8 : 10;
                    boolean neg = (conv == 'd' || conv == 'i') && v < 0;
                    String digits = conv == 'u' && longs >= 2 ? Long.toUnsignedString(v, radix) : Long.toString(Math.abs(v), radix);
                    if (neg && v == Long.MIN_VALUE) digits = "9223372036854775808";
                    if (conv == 'X') digits = digits.toUpperCase(Locale.ROOT);
                    if (precision >= 0) while (digits.length() < precision) digits = "0" + digits;
                    String sign = neg ? "-" : flags.contains("+") && (conv == 'd' || conv == 'i') ? "+" : flags.contains(" ") && (conv == 'd' || conv == 'i') ? " " : "";
                    if (flags.contains("#") && v != 0) sign += conv == 'x' ? "0x" : conv == 'X' ? "0X" : conv == 'o' ? "0" : "";
                    if (flags.contains("0") && !flags.contains("-") && precision < 0) while (sign.length() + digits.length() < width) digits = "0" + digits;
                    body = sign + digits; break;
                }
                default: out.append('%').append(conv); continue;
            }
            StringBuilder pad = new StringBuilder(); while (pad.length() + body.length() < width) pad.append(' ');
            if (flags.contains("-")) out.append(body).append(pad); else out.append(pad).append(body);
        }
        return out.toString();
    }

    // ---- modules and SceModule2 blocks --------------------------------------------------------------------
    private static final class LoadedModuleRef { final HleModules.LoadedModule m; LoadedModuleRef(HleModules.LoadedModule m) { this.m = m; } }
    private final Map<Integer, Integer> blocks = new HashMap<>(); private final Map<Integer, HleModules.LoadedModule> blockOwners = new HashMap<>();
    /** Loaded module by its module-info name (e.g. "vsh_module", "scePaf_Module"), newest first; null when absent. */
    public HleModules.LoadedModule module(String name) {
        if (name == null) return null;
        List<HleModules.LoadedModule> list = k.modules.modules;
        for (int i = list.size() - 1; i >= 0; i--) if (name.equals(list.get(i).prx.name)) return list.get(i);
        return null;
    }
    private LoadedModuleRef moduleOfBlock(int address) { HleModules.LoadedModule mod = blockOwners.get(address); return mod == null ? null : new LoadedModuleRef(mod); }
    /** The module's SceModule2 control block (kernel memory), refreshed from the loader's data. */
    public int block(HleModules.LoadedModule mod) {
        Integer b = blocks.get(mod.uid);
        if (b == null) { b = k.sysmem.allocate(1, "SceModule2 " + mod.prx.name, SysMem.LOW, 0x100, 0); if (b == 0) return 0; blocks.put(mod.uid, b); blockOwners.put(b, mod); }
        PrxLoader.Module p = mod.prx; int a = b;
        m.fill(a, 0, 0x100);
        m.write16(a + 0x04, p.attributes); m.write16(a + 0x06, p.version); m.writeString(a + 0x08, p.name == null ? "" : p.name, 28);
        m.write16(a + 0x24, mod.started ? 5 : 3); m.write32(a + 0x2c, mod.uid);
        m.write32(a + 0x50, p.moduleStart); m.write32(a + 0x54, p.moduleStop); m.write32(a + 0x64, p.entry); m.write32(a + 0x68, p.gp);
        m.write32(a + 0x6c, p.base); m.write32(a + 0x70, p.textSize); m.write32(a + 0x74, Math.max(0, p.size - p.textSize));
        m.write8(a + 0x7c, 1); m.write32(a + 0x80, p.base); m.write32(a + 0x90, p.size);
        return a;
    }

    // ---- start-module handler ----------------------------------------------------------------------------
    /**
     * sctrlHENSetStartModuleHandler: the handler runs on the module's start thread, as a guest call made before
     * module_start's first instruction (the same frame mechanism as thread callbacks), with a0 = SceModule2* and the
     * handler's own module gp. Its return value is ignored.
     */
    private void onStart(HleModules.LoadedModule mod, Kernel.GuestThread t) {
        if (startHandler == 0) return;
        HleModules.LoadedModule owner = k.modules.byAddress(startHandler);
        int blockAddress = block(mod);
        Kernel.Frame frame = new Kernel.Frame(); copy(t.context, frame.context); frame.done = r -> { };
        t.frames.push(frame);
        Cpu.Context c = t.context; c.r[4] = blockAddress; c.r[28] = owner != null ? owner.prx.gp : c.r[28]; c.r[31] = k.trampolines + 8;
        c.r[29] = (c.r[29] - 0x40) & ~15; c.pc = startHandler; c.next = startHandler + 4; c.delay = false;
        k.log(String.format(Locale.ROOT, "Start-module handler %08x called for %s", startHandler, mod.prx.name));
    }
    private static void copy(Cpu.Context from, Cpu.Context to) {
        System.arraycopy(from.r, 0, to.r, 0, 32); System.arraycopy(from.f, 0, to.f, 0, 32);
        to.pc = from.pc; to.next = from.next; to.hi = from.hi; to.lo = from.lo; to.fcr31 = from.fcr31; to.delay = from.delay; to.vfpu.copyFrom(from.vfpu);
    }

    // ---- FindFunction / PatchSyscall ------------------------------------------------------------------------
    /** Kernel-memory entry points made for HLE functions: "library:nid" → address of {@code jr ra; syscall n}. */
    private final Map<String, Integer> hleEntries = new HashMap<>(); private final Map<Integer, String> hleEntryKeys = new HashMap<>();
    /** sctrlHENPatchSyscall redirections of user-mode imports: original target → replacement. */
    private final Map<Integer, Integer> redirects = new LinkedHashMap<>();
    private int entryArea, entryUsed;
    private static int jump(int target) { return 0x08000000 | ((target >>> 2) & 0x03ffffff); }

    /**
     * sctrlHENFindFunction(modname, libname, nid). A module PXP2 loaded (real guest code) answers with its export
     * address. For a library served by HLE (the module itself is not emulated as code, e.g. sceDisplay_Service), the
     * answer is a two-instruction entry point {@code jr ra; syscall n} in kernel memory; from then on every import of
     * that function (existing and future modules) is routed through it, so patching its instructions hooks all
     * callers, as patching the real function's text would on hardware.
     */
    public int findFunction(String modname, String libname, int nid) {
        HleModules.LoadedModule mod = module(modname);
        if (mod != null) for (PrxLoader.Symbol s : mod.prx.exports) if (s.nid == nid && (libname == null || libname.equals(s.library))) {
            k.log(String.format(Locale.ROOT, "sctrlHENFindFunction(%s, %s, %08x) = %08x (guest export)", modname, libname, nid, s.address)); return s.address;
        }
        if (libname != null && (k.function(libname, nid) != null || resolveAlias(libname, nid) != null)) {
            int entry = hleEntry(libname, nid);
            k.log(String.format(Locale.ROOT, "sctrlHENFindFunction(%s, %s, %08x) = %08x (HLE entry for %s)", modname, libname, nid, entry, k.describe(libname, nid)));
            return entry;
        }
        k.log(String.format(Locale.ROOT, "sctrlHENFindFunction(%s, %s, %08x) = 0 (not found)", modname, libname, nid));
        return 0;
    }
    private int hleEntry(String library, int nid) {
        String key = Kernel.key(library, nid); Integer e = hleEntries.get(key);
        if (e != null) return e;
        if (entryArea == 0 || entryUsed + 8 > 0x1000) { entryArea = k.sysmem.allocate(1, "sctrl HLE entry points", SysMem.LOW, 0x1000, 0); entryUsed = 0; if (entryArea == 0) return 0; }
        int a = entryArea + entryUsed; entryUsed += 8;
        m.write32(a, 0x03e00008); m.write32(a + 4, (k.syscallCode(library, nid) << 6) | 12);
        hleEntries.put(key, a); hleEntryKeys.put(a, key);
        for (HleModules.LoadedModule mod : k.modules.modules) route(mod);
        return a;
    }
    /** sctrlHENPatchSyscall(addr, newaddr): user-mode imports that reach {@code addr} call {@code newaddr} instead. */
    private void patchSyscall(int address, int replacement) {
        redirects.put(address, replacement); int n = 0;
        for (HleModules.LoadedModule mod : k.modules.modules) n += route(mod);
        k.log(String.format(Locale.ROOT, "sctrlHENPatchSyscall(%08x, %08x): %d user import stubs redirected", address, replacement, n));
    }
    private int querySystemCall(int address) {
        String key = hleEntryKeys.get(address); if (key == null) return -1;
        int colon = key.indexOf(':'); return k.syscallCode(key.substring(0, colon), (int)Long.parseLong(key.substring(colon + 1), 16));
    }
    /** Re-applies HLE entry routing and syscall patches to one module's import stubs; returns the patches applied. */
    private int route(HleModules.LoadedModule mod) {
        int n = 0; boolean user = (mod.prx.attributes & 0x1000) == 0;
        for (PrxLoader.Symbol s : mod.prx.imports) {
            int w0 = m.read32(s.address), w1 = m.read32(s.address + 4);
            Integer entry = hleEntries.get(Kernel.key(s.library, s.nid));
            if (entry != null && w0 == 0x03e00008 && w1 == ((k.syscallCode(s.library, s.nid) << 6) | 12)) { m.write32(s.address, jump(entry)); m.write32(s.address + 4, 0); w0 = jump(entry); }
            if (!user || (w0 & 0xfc000000) != 0x08000000) continue;
            int target = (s.address & 0xf0000000) | ((w0 & 0x03ffffff) << 2);
            Integer to = redirects.get(target);
            if (to != null && to != target) { m.write32(s.address, jump(to)); m.write32(s.address + 4, 0); n++; }
        }
        return n;
    }
    private void onLinked(HleModules.LoadedModule mod) { if (!hleEntries.isEmpty() || !redirects.isEmpty()) route(mod); }
    private int importStub(HleModules.LoadedModule mod, String library, int nid) {
        for (PrxLoader.Symbol s : mod.prx.imports) if (s.nid == nid && s.library.equals(library)) return s.address;
        return 0;
    }

    // ---- PARAM.SFO queries ---------------------------------------------------------------------------------
    private Map<String, Object> sfo(String path) {
        if (path == null) return null;
        try {
            byte[] b = k.io.readFile(path);
            if (b.length >= 40 && FirmwareCrypto.word(b, 0) == 0x50425000) { int s = FirmwareCrypto.word(b, 8), e = FirmwareCrypto.word(b, 12); b = Arrays.copyOfRange(b, s, e); }
            return ParamSfo.parse(b);
        } catch (Exception e) { return null; }
    }
    private int initParam(String name, int typeOut, int lengthOut, int buffer) {
        Map<String, Object> sfo = null;
        if (mode == Mode.GAME && gamePath != null) sfo = gamePath.toUpperCase(Locale.ROOT).endsWith(".PBP") ? sfo(gamePath) : sfo("disc0:/PSP_GAME/PARAM.SFO");
        return sfoParam(sfo, name, typeOut, lengthOut, buffer);
    }
    private int sfoParam(Map<String, Object> sfo, String name, int typeOut, int lengthOut, int buffer) {
        if (sfo == null || name == null || !sfo.containsKey(name)) return 0x80010002;
        Object v = sfo.get(name);
        if (v instanceof Integer) { if (typeOut != 0) m.write16(typeOut, 0x0404); if (lengthOut != 0) m.write32(lengthOut, 4); if (buffer != 0) m.write32(buffer, (Integer)v); }
        else { byte[] s = ((String)v).getBytes(StandardCharsets.UTF_8); if (typeOut != 0) m.write16(typeOut, 0x0204); if (lengthOut != 0) m.write32(lengthOut, s.length + 1); if (buffer != 0) { m.write(buffer, s, 0, s.length); m.write8(buffer + s.length, 0); } }
        return 0;
    }

    // ---- import resolution for plugins -------------------------------------------------------------------
    /** Kernel-mode library names and the user-mode HLE libraries that serve the same functions with the same NIDs. */
    private static final Map<String, String[]> ALIASES = new HashMap<>();
    /** Kernel NIDs that differ from the user NID of the same function (PSPSDK *_kernel_660.a vs user stubs). */
    private static final Map<String, String> RENUMBERED = new HashMap<>();
    static {
        String[][] a = {{"ThreadManForKernel", "ThreadManForUser", "Kernel_Library"}, {"SysMemForKernel", "SysMemUserForUser"}, {"IoFileMgrForKernel", "IoFileMgrForUser"},
            {"ModuleMgrForKernel", "ModuleMgrForUser"}, {"LoadExecForKernel", "LoadExecForUser"}, {"UtilsForKernel", "UtilsForUser"},
            {"InterruptManagerForKernel", "InterruptManager", "Kernel_Library"}, {"StdioForKernel", "StdioForUser"}, {"sceCtrl_driver", "sceCtrl"},
            {"sceDisplay_driver", "sceDisplay"}, {"sceGe_driver", "sceGe_user"}, {"scePower_driver", "scePower"}, {"sceRtc_driver", "sceRtc"},
            {"sceReg_driver", "sceReg"}, {"sceAudio_driver", "sceAudio"}, {"sceHprm_driver", "sceHprm"}, {"sceUsb_driver", "sceUsb"}};
        for (String[] r : a) ALIASES.put(r[0], Arrays.copyOfRange(r, 1, r.length));
        String[] r = { // kernel library, kernel NID, user library, user NID (function name)
            "InterruptManagerForKernel 4023e1a7 InterruptManager 8a389411", // sceKernelDisableSubIntr
            "InterruptManagerForKernel ffa8b183 InterruptManager ca04a2b9", // sceKernelRegisterSubIntrHandler
            "InterruptManagerForKernel 2980ae03 InterruptManager 7860e0dc", // sceKernelResumeSubIntr
            "InterruptManagerForKernel c495f536 InterruptManager 5cb5a78b", // sceKernelSuspendSubIntr
            "ModuleMgrForKernel 433d5287 ModuleMgrForUser d8b73127", // sceKernelGetModuleIdByAddress
            "ModuleMgrForKernel 303fab7f ModuleMgrForUser 644395e2", // sceKernelGetModuleIdList
            "ModuleMgrForKernel eec2a745 ModuleMgrForUser b7f46618", // sceKernelLoadModuleByID
            "ModuleMgrForKernel 939e4270 ModuleMgrForUser 977de386", // sceKernelLoadModule
            "ModuleMgrForKernel 22bdbeff ModuleMgrForUser 748cbed9", // sceKernelQueryModuleInfo
            "ModuleMgrForKernel 5805c1ca ModuleMgrForUser d675ebb8", // sceKernelSelfStopUnloadModule
            "ModuleMgrForKernel 3ff74df1 ModuleMgrForUser 50f0c1ec", // sceKernelStartModule
            "ModuleMgrForKernel e5d6087b ModuleMgrForUser d1ff982a", // sceKernelStopModule
            "ModuleMgrForKernel e97e0db7 ModuleMgrForUser cc1d3699", // sceKernelStopUnloadSelfModule
            "ModuleMgrForKernel 387e3ca9 ModuleMgrForUser 2e0911aa", // sceKernelUnloadModule
            "SysMemForKernel 7158ce7e SysMemUserForUser 237dbd4f", // sceKernelAllocPartitionMemory
            "SysMemForKernel c886b169 SysMemUserForUser 3fc9ae6a", // sceKernelDevkitVersion
            "SysMemForKernel c1a26c6f SysMemUserForUser b6d61d02", // sceKernelFreePartitionMemory
            "SysMemForKernel f12a62f7 SysMemUserForUser 9d9a5ba1", // sceKernelGetBlockHeadAddr
            "SysMemForKernel b4f00cb5 SysMemUserForUser fc114573", // sceKernelGetCompiledSdkVersion
        };
        for (String line : r) { String[] f = line.split(" "); RENUMBERED.put(f[0] + ":" + f[1], f[2] + ":" + f[3]); }
    }
    private Kernel.Function resolveAlias(String library, int nid) {
        String to = RENUMBERED.get(library + ":" + String.format(Locale.ROOT, "%08x", nid));
        if (to != null) { int colon = to.indexOf(':'); Kernel.Function f = k.function(to.substring(0, colon), (int)Long.parseLong(to.substring(colon + 1), 16)); if (f != null) return f; }
        String[] targets = ALIASES.get(library);
        if (targets != null) for (String t : targets) { Kernel.Function f = k.function(t, nid); if (f != null) return f; }
        return null;
    }
    private Kernel.Function resolveImport(String library, int nid) {
        Kernel.Function f = resolveAlias(library, nid);
        if (f != null || !linkingPlugin) return f;
        // A plugin import PXP2 does not implement: calls from plugin code log and fail with
        // SCE_KERNEL_ERROR_LIBRARY_NOT_YET_LINKED (0x8002013a); calls from any other module still stop emulation.
        String key = Kernel.key(library, nid); missingImports.putIfAbsent(key, 0);
        k.log("Plugin import not implemented: " + key + " (returns 0x8002013a)");
        return Kernel.function(library, nid, key + " (missing; plugin)", c -> {
            int ra = c.cpu.register(31); HleModules.LoadedModule caller = k.modules.byAddress(ra - 8);
            if (caller == null || !pluginModules.contains(caller.uid)) throw new Kernel.Stop(String.format(Locale.ROOT, "Unimplemented import %s called from %08x", key, ra - 8));
            int n = missingImports.merge(key, 1, Integer::sum);
            if (n <= 3) k.log(String.format(Locale.ROOT, "Plugin %s called unimplemented %s from %08x -> 0x8002013a", caller.prx.name, key, ra - 8));
            return 0x8002013a;
        });
    }
}
