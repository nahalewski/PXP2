package org.pxp2.core;

public final class Diagnostics {
    public static String run() {
        Memory m = new Memory(); int base = 0x08800000;
        // Sum 5+4+3+2+1, executing a counter increment in each branch delay slot.
        int[] program = {0x24080005, 0x24090000, 0x01284821, 0x2508ffff, 0x1500fffd, 0x254a0001};
        for (int i = 0; i < program.length; i++) m.write32(base + i*4, program[i]);
        Cpu cpu = new Cpu(m, base);
        for (int step = 0; step < 22; step++) cpu.step();
        if (cpu.register(9) != 15 || cpu.register(10) != 5 || cpu.pc() != base + 24)
            throw new IllegalStateException("CPU loop diagnostic failed");
        return "Original CPU executed 22 instructions.\nSum: 15; delay slots: 5. PASS.";
    }
}
