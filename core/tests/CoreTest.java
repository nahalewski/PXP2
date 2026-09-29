import org.pxp2.core.*;

public final class CoreTest {
    private static void equal(int a, int b) { if(a != b) throw new AssertionError(a + " != " + b); }
    private static void rejects(Runnable op) {
        try { op.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) {
        System.out.println(Diagnostics.run());
        Memory m = new Memory(); int b = 0x08800000;
        m.write32(b,0x89abcdef); equal(m.read8(b),239); equal(m.read8(b+3),137); equal(m.read32(b),0x89abcdef);
        rejects(() -> m.read32(b+1)); rejects(() -> m.write32(0x0a000000,0)); rejects(() -> m.read8(0x07ffffff));
        // Zero register stays zero; signed and unsigned loads differ.
        int[] words={0x24000001,0x3c080880,0x2409ffff,0xa1090100,0x810a0100,0x910b0100,0xad090104,0x8d0c0104};
        for(int i=0;i<words.length;i++)m.write32(b+i*4,words[i]);
        Cpu c=new Cpu(m,b);for(int i=0;i<words.length;i++)c.step();
        equal(c.register(0),0); equal(c.register(10),-1); equal(c.register(11),255); equal(c.register(12),-1);
        // JAL records PC+8 and executes its delay slot before reaching the target.
        m.write32(b,0x0e200004);m.write32(b+4,0x24080007);m.write32(b+16,0x03e00008);m.write32(b+20,0x25080001);
        c=new Cpu(m,b);c.step();equal(c.pc(),b+4);equal(c.register(31),b+8);c.step();equal(c.pc(),b+16);
        c.step();c.step();equal(c.pc(),b+8);equal(c.register(8),8);
        m.write32(b,0xffffffff); Cpu invalid=new Cpu(m,b); rejects(invalid::step);equal(invalid.pc(),b);

        // RAM sizes: 64 MiB ends at 0x0bffffff, 128 MiB at 0x0fffffff; segment mirrors reach the same bytes, and the
        // CPU fast paths (lw/sw) agree with the Memory bus above 32 MiB.
        Memory m64 = new Memory(64 << 20), m128 = new Memory(128 << 20);
        m64.write32(0x0a000000, 0x12345678); equal(m64.read32(0x8a000000), 0x12345678); equal(m64.read32(0x4a000000), 0x12345678);
        m64.write32(0x0bfffffc, 7); equal(m64.read32(0xcbfffffc), 7); rejects(() -> m64.read32(0x0c000000)); rejects(() -> m64.read8(0x0bfffffc + 4));
        m128.write32(0x0ffffffc, 9); equal(m128.read32(0x8ffffffc), 9); rejects(() -> m128.read8(0x10000000)); equal(m128.read16(0x0c000000), 0);
        rejects(() -> new Memory(48 << 20)); equal(new Memory().size, 32 << 20);
        for (Memory mm : new Memory[]{m64, m128}) { // lui t0,0x0b00; ori t0,0x0010; lui t1,0xcafe; sw t1,0(t0); lw t2,0(t0); lbu t3,3(t0)
            int[] prog = {0x3c080b00, 0x35080010, 0x3c09cafe, 0xad090000, 0x8d0a0000, 0x910b0003};
            for (int i = 0; i < prog.length; i++) mm.write32(b + i * 4, prog[i]);
            Cpu cc = new Cpu(mm, b); for (int i = 0; i < prog.length; i++) cc.step();
            equal(cc.register(10), 0xcafe0000); equal(cc.register(11), 0xca); equal(mm.read32(0x8b000010), 0xcafe0000);
        }
        // Partition layouts per RAM size (psp.md "System memory size").
        Kernel k32 = new Kernel(), k64 = new Kernel(64 << 20), k128 = new Kernel(128 << 20);
        equal(k32.sysmem.partitionSize(2), 0x1800000); equal(k32.sysmem.validPartition(8) ? 1 : 0, 0); equal(k32.sysmem.useHighMemory() ? 1 : 0, 0);
        equal(k64.sysmem.partitionSize(2), 0x1800000); equal(k64.sysmem.partitionBase(8), 0x0a000000); equal(k64.sysmem.partitionSize(8), 0x1c00000);
        equal(k64.sysmem.partitionBase(10), 0x0bc00000); equal(k64.sysmem.partitionOf(0x0a100000), 8);
        equal(k128.sysmem.partitionSize(8), 0x5c00000); equal(k128.sysmem.partitionBase(10), 0x0fc00000);
        equal(k64.sysmem.useHighMemory() ? 1 : 0, 1); equal(k64.sysmem.partitionSize(2), 0x3400000); equal(k64.sysmem.validPartition(8) ? 1 : 0, 0);
        equal(k64.sysmem.totalFree(2), 0x3400000); equal(k64.sysmem.allocate(2, "top", SysMem.HIGH, 0x100, 0), 0x0bbfff00);
        equal(k128.sysmem.useHighMemory() ? 1 : 0, 1); equal(k128.sysmem.partitionSize(2), 0x7400000);
        equal(k64.model, 0); equal(k128.model, 0);

        // Battery and scePower tests
        Kernel k = new Kernel();
        HleSystem sys = new HleSystem(k);
        equal(sys.batteryPercent, 100);
        equal(sys.batteryCharging ? 1 : 0, 0);
        equal(sys.powerOnline ? 1 : 0, 1);
        equal(sys.batteryLifeTimeMinutes, 300);
        equal(k.function("scePower", 0x2085d15d).handler.call(k), 100); // scePowerGetBatteryLifePercent
        equal(k.function("scePower", 0x8efb3fa2).handler.call(k), 300); // scePowerGetBatteryLifeTime
        equal(k.function("scePower", 0x87440f5e).handler.call(k), 1);   // scePowerIsPowerOnline
        equal(k.function("scePower", 0x0afd0d8b).handler.call(k), 1);   // scePowerIsBatteryExist
        equal(k.function("scePower", 0x1e490401).handler.call(k), 0);   // scePowerIsBatteryCharging
        equal(k.function("scePower", 0xb4432bc8).handler.call(k), 0);   // scePowerGetBatteryChargingStatus
        equal(k.function("scePower", 0xd3075926).handler.call(k), 0);   // scePowerIsLowBattery
        equal(k.function("scePower", 0x28e12023).handler.call(k), 28);  // scePowerGetBatteryTemp
        equal(k.function("scePower", 0x483ce86b).handler.call(k), 4135);// scePowerGetBatteryVolt

        // Update battery discharging at 42%
        sys.updateBattery(42, false, false, 32, 3850);
        equal(sys.batteryPercent, 42);
        equal(sys.batteryCharging ? 1 : 0, 0);
        equal(sys.powerOnline ? 1 : 0, 0);
        equal(sys.batteryLifeTimeMinutes, 126);
        equal(k.function("scePower", 0x2085d15d).handler.call(k), 42);
        equal(k.function("scePower", 0x8efb3fa2).handler.call(k), 126);
        equal(k.function("scePower", 0x87440f5e).handler.call(k), 0);
        equal(k.function("scePower", 0x1e490401).handler.call(k), 0);
        equal(k.function("scePower", 0xd3075926).handler.call(k), 0);
        equal(k.function("scePower", 0x28e12023).handler.call(k), 32);
        equal(k.function("scePower", 0x483ce86b).handler.call(k), 3850);

        // Update battery low (8% discharging)
        sys.updateBattery(8, false, false, 35, 3600);
        equal(k.function("scePower", 0xd3075926).handler.call(k), 1);   // scePowerIsLowBattery == 1
        equal(sys.powerInfo() & 0x100, 0x100); // PSP_POWER_CB_BATTERY_LOW set

        // Update battery charging (8% charging on AC)
        sys.updateBattery(8, true, true, 36, 3900);
        equal(k.function("scePower", 0xd3075926).handler.call(k), 0);   // not low when charging
        equal(k.function("scePower", 0x1e490401).handler.call(k), 1);   // scePowerIsBatteryCharging == 1
        equal(k.function("scePower", 0x87440f5e).handler.call(k), 1);   // scePowerIsPowerOnline == 1
        equal(sys.batteryLifeTimeMinutes, 0);                            // 0 when charging
        equal(sys.powerInfo() & 0x1000, 0x1000); // PSP_POWER_CB_AC_POWER set

        // Power callback notification test
        Kernel.GuestThread t = k.createThread("test", 0x08900000, 32, 0x1000, 0, 0);
        Kernel.Callback cb = k.createCallback("powerCb", 0x08900100, 0);
        k.cpu.setRegister(4, 0); // slot 0
        k.cpu.setRegister(5, cb.uid); // callback uid
        k.function("scePower", 0x04b7766e).handler.call(k); // scePowerRegisterCallback
        equal(cb.notifyCount, 1);
        equal(cb.notifyArg, sys.powerInfo());

        // Changing battery notifies registered callback
        sys.updateBattery(85, true, true, 29, 4120);
        equal(cb.notifyCount, 2);
        equal(cb.notifyArg, sys.powerInfo());
        equal(cb.notifyArg & 0x7f, 85);

        System.out.println("PASS: loop/delay slots, little-endian RAM, bounds/alignment, zero register, byte extension, load/store, call/return, unsupported opcode, battery & scePower.");
    }
}
