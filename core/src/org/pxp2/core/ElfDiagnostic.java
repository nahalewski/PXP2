package org.pxp2.core;

import java.io.IOException;
import java.nio.*;

/** Original test executable, not Sony firmware and not a compatibility claim. */
public final class ElfDiagnostic {
    public static byte[] program() {
        ByteBuffer b=ByteBuffer.allocate(0x11c).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x464c457f); b.put(4,(byte)1); b.put(5,(byte)1); b.put(6,(byte)1);
        b.putShort(16,(short)2); b.putShort(18,(short)8); b.putInt(20,1);
        b.putInt(24,0x08800000); b.putInt(28,52); b.putShort(40,(short)52);
        b.putShort(42,(short)32); b.putShort(44,(short)1);
        b.putInt(52,1); b.putInt(56,0x100); b.putInt(60,0x08800000);
        b.putInt(68,28); b.putInt(72,64); b.putInt(76,5); b.putInt(80,0x100);
        int[] words={0x24080005,0x24090000,0x01284821,0x2508ffff,0x1500fffd,0x254a0001,0x0000000d};
        for(int i=0;i<words.length;i++) b.putInt(0x100+i*4,words[i]);
        return b.array();
    }
    public static String run() throws IOException {
        Execution.Result result=Execution.run(ElfLoader.load(program()),1000);
        if(result.instructions!=22 || result.sum!=15 || result.delayCount!=5 || !result.reason.equals("MIPS BREAK"))
            throw new IllegalStateException("ELF execution diagnostic failed: " + result);
        return "Original ELF loader/CPU test: PASS\n" + result + "\nSum: 15; delay slots: 5.\nThis test does not execute Sony firmware.";
    }
}
