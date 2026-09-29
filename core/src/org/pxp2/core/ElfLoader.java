package org.pxp2.core;

import java.io.IOException;
import java.nio.*;
import java.util.*;

/** Original loader for fixed-address ELF32 little-endian MIPS executables only. */
public final class ElfLoader {
    private static long u32(ByteBuffer b, int at) { return Integer.toUnsignedLong(b.getInt(at)); }
    private static int u16(ByteBuffer b, int at) { return Short.toUnsignedInt(b.getShort(at)); }
    private static void require(boolean condition, String reason) throws IOException {
        if (!condition) throw new IOException(reason);
    }
    private static void range(long offset, long size, long length) throws IOException {
        require(offset >= 0 && size >= 0 && offset <= length && size <= length-offset, "ELF range outside file");
    }
    private static final class Segment {
        long offset, address, fileSize, memorySize; boolean execute;
    }
    public static final class Loaded {
        public final Memory memory;
        public final int entry;
        private final List<Segment> segments;
        private Loaded(Memory memory, int entry, List<Segment> segments) { this.memory = memory; this.entry = entry; this.segments = segments; }
        public boolean executable(int pc) {
            long address = Integer.toUnsignedLong(pc);
            for (Segment s : segments) if (s.execute && address >= s.address && address + 4 <= s.address+s.fileSize) return true;
            return false;
        }
    }
    public static Loaded load(byte[] bytes) throws IOException {
        require(bytes.length >= 52 && bytes.length <= 32*1024*1024, "Invalid ELF size");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(b.getInt(0) == 0x464c457f, "Not an ELF executable (encrypted PSP modules require decryption)");
        require(b.get(4) == 1 && b.get(5) == 1 && b.get(6) == 1 && b.getInt(20) == 1, "Only ELF32 little-endian version 1 is supported");
        require(u16(b, 16) == 2 && u16(b, 18) == 8, "Only fixed-address MIPS ET_EXEC is supported; PRX relocation is not implemented");
        require(u16(b, 40) == 52 && u16(b, 42) == 32, "Invalid ELF header sizes");
        int count = u16(b, 44); long ph = u32(b, 28);
        require(count > 0 && count <= 128 && ph >= 52, "Invalid ELF program table"); range(ph, count*32L, bytes.length);
        // Reject required dynamic linking or relocation rather than ignoring it.
        long sh = u32(b, 32); int sc = u16(b, 48);
        if (sh != 0 || sc != 0) {
            require(sh >= 52 && sc > 0 && sc <= 2048 && u16(b, 46) == 40, "Invalid ELF section table");
            range(sh, sc*40L, bytes.length);
            for (int i = 0; i < sc; i++) {
                int p = (int)sh + i*40; long type = u32(b,p+4);
                require(type != 4 && type != 9 && type != 6 && type != 0x700000a0L && type != 0x700000a1L,
                    "ELF relocation/dynamic linking is not implemented");
                if (type != 8) range(u32(b,p+16), u32(b,p+20), bytes.length);
            }
        }
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int p = (int)ph + i*32; long type = u32(b,p);
            require(type != 2 && type != 3 && type != 0x700000a0L && type != 0x700000a1L,
                "ELF dynamic/relocation program headers are not supported");
            if (type != 1) continue;
            Segment s = new Segment(); s.offset=u32(b,p+4); s.address=u32(b,p+8);
            s.fileSize=u32(b,p+16); s.memorySize=u32(b,p+20); s.execute=(b.getInt(p+24)&1)!=0;
            require(s.fileSize <= s.memorySize, "ELF filesz exceeds memsz");
            range(s.offset,s.fileSize,bytes.length);
            require(s.address >= Memory.BASE && s.address+s.memorySize <= (long)Memory.BASE+Memory.SIZE, "ELF segment outside PSP RAM");
            long alignment=u32(b,p+28);
            require(alignment <= 1 || ((alignment & (alignment-1)) == 0 && (s.address % alignment) == (s.offset % alignment)), "Invalid ELF segment alignment");
            for (Segment other : segments) require(s.memorySize == 0 || other.memorySize == 0 || s.address+s.memorySize <= other.address || other.address+other.memorySize <= s.address, "Overlapping ELF segments");
            segments.add(s);
        }
        Memory memory = new Memory(); Loaded loaded = new Loaded(memory,b.getInt(24),segments);
        require((loaded.entry & 3)==0 && loaded.executable(loaded.entry), "ELF entry is not in initialized executable memory");
        for (Segment s : segments) {
            for (int i=0;i<s.fileSize;i++) memory.write8((int)s.address+i,bytes[(int)s.offset+i]);
            // Newly allocated RAM already provides zero-filled BSS.
        }
        return loaded;
    }
}
