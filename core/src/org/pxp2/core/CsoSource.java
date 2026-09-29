package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * CSO ("CISO", v1 raw deflate and v2 deflate/LZ4) and ZSO ("ZISO", LZ4) compressed UMD images as an {@link IsoSource},
 * over a local file or an HTTP stream. Original implementation from the published layout: a 24-byte little-endian header
 * (magic, header size, total bytes, block size, version, index shift) and one 32-bit index entry per block plus one;
 * an entry's low 31 bits shifted by the index shift give the block's file offset. Bit 31 marks a stored block (v1 and
 * ZSO) or an LZ4 block (v2, where a stored block is one whose size is at least the block size). Decoded blocks are kept
 * in a small LRU cache. Corrupt data throws IOException.
 */
public final class CsoSource implements IsoSource {
    private final IsoSource raw; private final boolean zso; public final int version, blockSize, align; public final long totalBytes;
    private final long[] index; private final int blocks;
    private final Map<Integer, byte[]> cache = new LinkedHashMap<Integer, byte[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) { return size() > Math.max(8, (2 * 1024 * 1024) / blockSize); }
    };

    public CsoSource(IsoSource raw) throws IOException {
        this.raw = raw;
        try {
            byte[] h = new byte[24];
            if (raw.read(0, h, 0, 24) != 24) throw new IOException("CSO header is truncated");
            String magic = new String(h, 0, 4, StandardCharsets.ISO_8859_1);
            if (!magic.equals("CISO") && !magic.equals("ZISO")) throw new IOException("Not a CSO/ZSO image");
            zso = magic.equals("ZISO");
            totalBytes = le64(h, 8); blockSize = le32(h, 16); version = h[20] & 255; align = h[21] & 255;
            if (blockSize < 2048 || blockSize > 1 << 20 || (blockSize & (blockSize - 1)) != 0) throw new IOException("CSO has a bad block size " + blockSize);
            if (totalBytes <= 0 || totalBytes > (1L << 36)) throw new IOException("CSO has a bad total size");
            if (align > 20) throw new IOException("CSO has a bad index alignment");
            long count = (totalBytes + blockSize - 1) / blockSize; blocks = (int)count;
            int headerSize = le32(h, 4); if (headerSize < 24 || headerSize > 4096) headerSize = 24;
            byte[] ix = new byte[(blocks + 1) * 4];
            if (raw.read(headerSize, ix, 0, ix.length) != ix.length) throw new IOException("CSO index is truncated");
            index = new long[blocks + 1];
            for (int i = 0; i <= blocks; i++) index[i] = le32(ix, 4 * i) & 0xffffffffL;
        } catch (IOException | RuntimeException e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw e instanceof IOException ? (IOException)e : new IOException("Corrupt CSO: " + e, e);
        }
    }
    public static boolean isCso(IsoSource s) throws IOException {
        byte[] m = new byte[4]; if (s.length() < 24 || s.read(0, m, 0, 4) != 4) return false;
        String t = new String(m, StandardCharsets.ISO_8859_1); return t.equals("CISO") || t.equals("ZISO");
    }

    @Override public long length() { return totalBytes; }
    @Override public String describe() { return (zso ? "zso:" : "cso:") + raw.describe(); }
    @Override public int read(long pos, byte[] out, int at, int n) throws IOException {
        if (pos < 0 || pos >= totalBytes || n <= 0) return 0;
        n = (int)Math.min(n, totalBytes - pos); int done = 0;
        while (done < n) {
            long p = pos + done; int b = (int)(p / blockSize), within = (int)(p % blockSize);
            byte[] data = block(b); int take = Math.min(n - done, blockSize - within);
            System.arraycopy(data, within, out, at + done, take); done += take;
        }
        return n;
    }
    private byte[] block(int b) throws IOException {
        synchronized (cache) { byte[] hit = cache.get(b); if (hit != null) return hit; }
        long e = index[b], e2 = index[b + 1];
        long start = (e & 0x7fffffffL) << align, stop = (e2 & 0x7fffffffL) << align;
        boolean flag = (e & 0x80000000L) != 0;
        if (stop < start || stop - start > blockSize * 2L + 64 || stop > raw.length() + (1L << align)) throw new IOException("CSO block " + b + " has a bad index entry");
        int len = (int)Math.min(stop - start, raw.length() - start);
        byte[] comp = new byte[len];
        if (raw.read(start, comp, 0, len) != len) throw new IOException("CSO block " + b + " is truncated");
        byte[] out = new byte[blockSize]; int need = (int)Math.min(blockSize, totalBytes - (long)b * blockSize);
        boolean stored = zso || version < 2 ? flag : !flag && len >= blockSize;
        boolean lz4 = zso ? !flag : version >= 2 && flag;
        if (stored) { if (len < need) throw new IOException("CSO stored block " + b + " is short"); System.arraycopy(comp, 0, out, 0, Math.min(len, blockSize)); }
        else if (lz4) lz4(comp, out, b, need);
        else {
            Inflater inf = new Inflater(true);
            try {
                inf.setInput(comp); int got = 0;
                while (got < blockSize) { int r = inf.inflate(out, got, blockSize - got); if (r == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break; got += r; }
                // the last block of an image may be shorter than a whole block
                if (got < need) throw new IOException("CSO block " + b + " inflated to " + got + " bytes");
            } catch (DataFormatException ex) { throw new IOException("CSO block " + b + ": " + ex.getMessage()); } finally { inf.end(); }
        }
        synchronized (cache) { cache.put(b, out); }
        return out;
    }
    /** LZ4 block format: token (literal length, match length), literals, 16-bit offset. Stops when the block is full. */
    static void lz4(byte[] in, byte[] out, int blockNo, int need) throws IOException {
        int ip = 0, op = 0, inEnd = in.length, outEnd = out.length;
        try {
            while (ip < inEnd && op < outEnd) {
                int token = in[ip++] & 255, lit = token >>> 4;
                if (lit == 15) { int x; do { x = in[ip++] & 255; lit += x; } while (x == 255); }
                if (lit > outEnd - op || lit > inEnd - ip) throw new IOException("ZSO block " + blockNo + ": literals overflow");
                System.arraycopy(in, ip, out, op, lit); ip += lit; op += lit;
                if (op >= outEnd || ip >= inEnd) break;
                int offset = (in[ip] & 255) | (in[ip + 1] & 255) << 8; ip += 2;
                int ml = (token & 15) + 4;
                if ((token & 15) == 15) { int x; do { x = in[ip++] & 255; ml += x; } while (x == 255); }
                if (offset == 0 || offset > op || ml > outEnd - op) throw new IOException("ZSO block " + blockNo + ": bad match");
                for (int k = 0; k < ml; k++) out[op + k] = out[op - offset + k];
                op += ml;
            }
        } catch (ArrayIndexOutOfBoundsException e) { throw new IOException("ZSO block " + blockNo + " is truncated"); }
        if (op < need) throw new IOException("ZSO block " + blockNo + " decoded to " + op + " bytes");
    }
    @Override public void close() throws IOException { synchronized (cache) { cache.clear(); } raw.close(); }

    private static int le32(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8 | (b[p + 2] & 255) << 16 | (b[p + 3] & 255) << 24; }
    private static long le64(byte[] b, int p) { return (le32(b, p) & 0xffffffffL) | (long)le32(b, p + 4) << 32; }
}
