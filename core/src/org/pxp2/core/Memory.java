package org.pxp2.core;

import java.nio.charset.StandardCharsets;

/**
 * Original PXP2 PSP memory bus: main RAM at 0x08000000 (32 MiB PSP-1000, 64 MiB PSP-2000/3000/Go, or a
 * non-standard 128 MiB reaching 0x0fffffff; chosen at construction), 2 MiB VRAM and 16 KiB scratchpad.
 * Kernel (0x8), uncached (0x4/0xC) and kernel-uncached (0xA) segment bits are stripped with the 0x1fffffff mask so
 * every mirror reaches the same storage; RAM ends at 0x0fffffff at most, so no size overlaps another segment.
 * RAM is not mirrored past its end (an access beyond it faults). MMIO and cache behaviour are not emulated.
 */
public final class Memory {
    public static final int BASE = 0x08000000;
    /** PSP-1000 RAM size (the default of {@link #Memory()}, used by the desktop analysis tools). */
    public static final int SIZE = 32 * 1024 * 1024;
    /** Largest supported RAM (128 MiB: 0x08000000-0x0fffffff). */
    public static final int MAX_SIZE = 128 * 1024 * 1024;
    public static final int VRAM = 0x04000000, VRAM_SIZE = 2 * 1024 * 1024;
    public static final int SCRATCH = 0x00010000, SCRATCH_SIZE = 16 * 1024;
    public static final int ME_EDRAM_SIZE = 2 * 1024 * 1024;
    /** RAM size in bytes (32, 64 or 128 MiB). */
    public final int size;
    /** ~(size - 1): an offset o into RAM is in range when (o & ramMask) == 0 (negative o has high bits set). */
    final int ramMask;
    final byte[] ram; // package-visible for the CPU's inline fast paths
    private final byte[] vram = new byte[VRAM_SIZE];
    private final byte[] scratch = new byte[SCRATCH_SIZE];
    private final byte[] meEdram = new byte[ME_EDRAM_SIZE];
    private byte[] block;
    /**
     * VRAM byte range [vramWriteLo, vramWriteHi) written through this bus since the renderer last took it
     * ({@link #takeVramWrites}). The high-resolution renderer uses it to find CPU/DMA writes into framebuffers it shadows.
     */
    private int vramWriteLo = Integer.MAX_VALUE, vramWriteHi = Integer.MIN_VALUE;
    private void wrote(byte[] b, int offset, int length) {
        if (b == vram) { if (offset < vramWriteLo) vramWriteLo = offset; if (offset + length > vramWriteHi) vramWriteHi = offset + length; }
    }
    /** Stores {lo, hi} of the VRAM bytes written since the last call in {@code range}, resets it, and returns whether there were any. */
    public boolean takeVramWrites(int[] range) {
        if (vramWriteLo >= vramWriteHi) return false;
        range[0] = vramWriteLo; range[1] = vramWriteHi; vramWriteLo = Integer.MAX_VALUE; vramWriteHi = Integer.MIN_VALUE; return true;
    }
    /** Records a VRAM write made outside the bus (renderers that write through {@link #backing}). */
    public void markVramWritten(int offset, int length) { wrote(vram, offset, length); }

    /** PSP-1000 memory (32 MiB RAM). */
    public Memory() { this(SIZE); }
    /** @param ramBytes 32, 64 or 128 MiB */
    public Memory(int ramBytes) {
        if (ramBytes != 32 << 20 && ramBytes != 64 << 20 && ramBytes != MAX_SIZE) throw new IllegalArgumentException("RAM must be 32, 64 or 128 MiB: " + ramBytes);
        size = ramBytes; ramMask = ~(ramBytes - 1); ram = new byte[ramBytes];
    }

    /** Returns the offset into {@link #block} or throws for unmapped/unaligned access. */
    private int index(int address, int bytes, int alignment) {
        if ((address & (alignment - 1)) != 0) throw fault(address);
        int a = address & 0x1fffffff;
        if (a >= BASE && a + bytes <= BASE + size) { block = ram; return a - BASE; }
        // VRAM is mirrored four times across 0x04000000-0x047fffff.
        if (a >= VRAM && a < VRAM + 0x800000) { int o = (a - VRAM) & (VRAM_SIZE - 1); if (o + bytes <= VRAM_SIZE) { block = vram; return o; } }
        if (a >= SCRATCH && a + bytes <= SCRATCH + SCRATCH_SIZE) { block = scratch; return a - SCRATCH; }
        if (a > 0 && a + bytes <= ME_EDRAM_SIZE) { block = meEdram; return a; }
        throw fault(address);
    }
    private static IllegalArgumentException fault(int address) {
        return new IllegalArgumentException(String.format("Unmapped or unaligned address %08x", address));
    }
    public boolean valid(int address, int length) {
        if (length < 0) return false;
        if (length == 0) return true;
        int a = address & 0x1fffffff; long end = (long)a + length;
        return (a >= BASE && end <= (long)BASE + size) || (a >= VRAM && end <= (long)VRAM + VRAM_SIZE)
            || (a >= SCRATCH && end <= SCRATCH + SCRATCH_SIZE)
            || (a > 0 && end <= ME_EDRAM_SIZE);
    }
    // Fast path: o = offset into main RAM; (o & mask) == 0 means in range and aligned (negative o has high bits set).
    public int read8(int address) {
        int o = (address & 0x1fffffff) - BASE; if ((o & ramMask) == 0) return ram[o] & 255;
        int i = index(address, 1, 1); return block[i] & 255;
    }
    public void write8(int address, int value) {
        int o = (address & 0x1fffffff) - BASE; if ((o & ramMask) == 0) { ram[o] = (byte)value; return; }
        int i = index(address, 1, 1); block[i] = (byte)value; wrote(block, i, 1);
    }
    public int read16(int address) {
        int o = (address & 0x1fffffff) - BASE; byte[] b = ram;
        if ((o & (ramMask | 1)) != 0) { o = index(address, 2, 2); b = block; }
        return (b[o] & 255) | ((b[o+1] & 255) << 8);
    }
    public void write16(int address, int value) {
        int o = (address & 0x1fffffff) - BASE; byte[] b = ram;
        if ((o & (ramMask | 1)) != 0) { o = index(address, 2, 2); b = block; wrote(b, o, 2); }
        b[o] = (byte)value; b[o+1] = (byte)(value >>> 8);
    }
    public int read32(int address) {
        int o = (address & 0x1fffffff) - BASE; byte[] b = ram;
        if ((o & (ramMask | 3)) != 0) { o = index(address, 4, 4); b = block; }
        return (b[o] & 255) | ((b[o+1] & 255) << 8) | ((b[o+2] & 255) << 16) | (b[o+3] << 24);
    }
    public void write32(int address, int value) {
        int o = (address & 0x1fffffff) - BASE; byte[] b = ram;
        if ((o & (ramMask | 3)) != 0) { o = index(address, 4, 4); b = block; wrote(b, o, 4); }
        b[o] = (byte)value; b[o+1] = (byte)(value >>> 8); b[o+2] = (byte)(value >>> 16); b[o+3] = (byte)(value >>> 24);
    }
    public long read64(int address) { return Integer.toUnsignedLong(read32(address)) | ((long)read32(address + 4) << 32); }
    public void write64(int address, long value) { write32(address, (int)value); write32(address + 4, (int)(value >>> 32)); }
    public float readFloat(int address) { return Float.intBitsToFloat(read32(address)); }
    public void writeFloat(int address, float value) { write32(address, Float.floatToRawIntBits(value)); }

    /** Validates a whole range and returns its backing array; offset is stored in {@code at[0]}. */
    private byte[] span(int address, int length, int[] at) {
        if (!valid(address, length)) throw new IllegalArgumentException(String.format("Invalid memory range %08x+%x", address, length));
        if (length == 0) { at[0] = 0; return ram; }
        at[0] = index(address, 1, 1); return block;
    }
    /** Returns the backing array of the region containing {@code address}; the array offset is stored in {@code at[0]}. For renderers. */
    public byte[] backing(int address, int[] at) { at[0] = index(address, 1, 1); return block; }
    public void read(int address, byte[] target, int offset, int length) {
        int[] at = new int[1]; byte[] b = span(address, length, at); System.arraycopy(b, at[0], target, offset, length);
    }
    public byte[] read(int address, int length) { byte[] out = new byte[length]; read(address, out, 0, length); return out; }
    public void write(int address, byte[] source, int offset, int length) {
        int[] at = new int[1]; byte[] b = span(address, length, at); System.arraycopy(source, offset, b, at[0], length); wrote(b, at[0], length);
    }
    public void write(int address, byte[] source) { write(address, source, 0, source.length); }
    public void fill(int address, int value, int length) {
        int[] at = new int[1]; byte[] b = span(address, length, at); java.util.Arrays.fill(b, at[0], at[0] + length, (byte)value); wrote(b, at[0], length);
    }
    public void copy(int destination, int source, int length) {
        int[] s = new int[1], d = new int[1]; byte[] from = span(source, length, s), to = span(destination, length, d);
        System.arraycopy(from, s[0], to, d[0], length); wrote(to, d[0], length);
    }
    /** Reads a NUL-terminated guest string of at most {@code max} bytes (ISO-8859-1 / UTF-8 bytes preserved). */
    public String string(int address, int max) {
        if (address == 0) return null;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < max; i++) { int c = read8(address + i); if (c == 0) break; text.append((char)c); }
        return new String(text.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }
    /** Writes a NUL-terminated string, truncated to fit {@code capacity} bytes including the terminator. */
    public void writeString(int address, String value, int capacity) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); int n = Math.min(bytes.length, capacity - 1);
        write(address, bytes, 0, n); write8(address + n, 0);
    }
}
