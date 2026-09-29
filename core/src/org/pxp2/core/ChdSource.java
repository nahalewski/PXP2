package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A MAME CHD v5 image ("MComprHD") read as a plain UMD image: an {@link IsoSource} over another IsoSource, so it works
 * for a local file and for a CHD streamed from the PC hub ({@link HttpIsoSource}) alike. Original implementation written
 * from the format description (header, compressed hunk map, codecs); no emulator code was used.
 *
 * <p><b>Format.</b> The 124-byte big-endian header gives four codec tags, the logical size, the map and metadata offsets,
 * the hunk size (a multiple of the 2048-byte unit for DVD/UMD images, as {@code chdman createdvd} writes) and the SHA-1s.
 * The v5 map is compressed: a Huffman-coded (16 symbols, 8 bits max, RLE-coded tree) list of per-hunk compression types
 * with run lengths, followed by per-hunk lengths, CRC16s and self/parent references, all in one MSB-first bit stream,
 * verified by a CRC16 over the expanded 12-byte entries. Each hunk is decompressed on its own and checked against its
 * CRC16. Uncompressed v5 images (plain 4-byte map) are read too.
 *
 * <p><b>Codecs:</b> zlib (raw deflate, {@link Inflater}), zstd ({@link ZstdDecoder}), lzma ({@link LzmaDecoder}, LZMA1
 * with lc=3 lp=0 pb=2) and huff (CHD's 8-bit Huffman) and flac ({@link FlacDecoder}); all written for PXP2. Images that need a
 * parent CHD, CD images (2448-byte frames) and the CD codecs are refused with an IOException.
 *
 * <p><b>Cache and read-ahead.</b> Decoded hunks are kept in an LRU cache (about 4 MiB). When the reads are sequential,
 * the next hunks are decoded ahead on a background thread. Corrupt data surfaces as an IOException on the read that
 * needs it; the emulator turns that into an I/O error for the guest.
 */
public final class ChdSource implements IsoSource {
    public static final String MAGIC = "MComprHD";
    static final int HEADER_V5 = 124;
    static final int CODEC_ZLIB = tag("zlib"), CODEC_ZSTD = tag("zstd"), CODEC_LZMA = tag("lzma"), CODEC_HUFF = tag("huff"), CODEC_FLAC = tag("flac");
    private static final int TAG_CD_V2 = tag("CHT2"), TAG_CD_OLD = tag("CHTR"), TAG_CD_GD = tag("CHGD"), TAG_CD = tag("CHCD");
    private static final int READ_AHEAD = 4, CACHE_BYTES = 4 * 1024 * 1024;
    private static final ExecutorService decoder = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "PXP2 CHD read-ahead"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });

    private final IsoSource raw;
    public final int version, hunkBytes, unitBytes, hunkCount;
    public final long logicalBytes;
    private final int[] codecs = new int[4];
    /** Expanded map: per hunk the compression type (0-3 codec, 4 none, 5 self, 6 parent, -1 zero), length, offset, CRC16. */
    private final byte[] type; private final int[] length; private final long[] offset; private final short[] crc; private final boolean[] hasCrc;
    public final List<String> metadataTags = new ArrayList<>();
    private final Map<Integer, byte[]> cache; private final int cacheHunks;
    private final Map<Integer, CompletableFuture<byte[]>> inflight = new HashMap<>();
    private int lastHunk = -2; private volatile boolean closed;
    /** Statistics: hunks decoded, compressed bytes read, cache hits. */
    public volatile long decoded, compressedRead, hits;

    /** Opens the CHD in {@code raw}; the source is closed when this is (or when opening fails). */
    public ChdSource(IsoSource raw) throws IOException {
        this.raw = raw;
        try {
            byte[] h = readFully(0, HEADER_V5 > raw.length() ? (int)Math.max(0, raw.length()) : HEADER_V5);
            if (h.length < 16 || !MAGIC.equals(new String(h, 0, 8, StandardCharsets.ISO_8859_1))) throw new IOException("Not a CHD image");
            version = be32(h, 12);
            if (version != 5) throw new IOException("CHD version " + version + " is not supported (only v5; recreate it with a current chdman)");
            if (h.length < HEADER_V5 || be32(h, 8) < HEADER_V5) throw new IOException("CHD header is truncated");
            for (int i = 0; i < 4; i++) codecs[i] = be32(h, 16 + 4 * i);
            logicalBytes = be64(h, 32); long mapOffset = be64(h, 40), metaOffset = be64(h, 48);
            hunkBytes = be32(h, 56); unitBytes = be32(h, 60);
            boolean parent = false; for (int i = 104; i < 124; i++) if (h[i] != 0) parent = true;
            if (parent) throw new IOException("This CHD needs a parent image (delta CHDs are not supported)");
            if (hunkBytes <= 0 || hunkBytes > 16 * 1024 * 1024 || unitBytes <= 0 || hunkBytes % unitBytes != 0) throw new IOException("CHD has a bad hunk size (" + hunkBytes + "/" + unitBytes + ")");
            if (logicalBytes <= 0 || logicalBytes > (1L << 40)) throw new IOException("CHD has a bad logical size");
            long hunks = (logicalBytes + hunkBytes - 1) / hunkBytes;
            if (hunks > 50_000_000) throw new IOException("CHD has too many hunks");
            hunkCount = (int)hunks;
            readMetadata(metaOffset);
            for (String t : metadataTags) { int g = tag(t); if (g == TAG_CD_V2 || g == TAG_CD_OLD || g == TAG_CD_GD || g == TAG_CD) throw new IOException("This CHD is a CD image (" + t + "), not a UMD/DVD image"); }
            if (unitBytes != 2048) throw new IOException("CHD unit size is " + unitBytes + " bytes; a UMD image needs 2048-byte sectors (chdman createdvd)");
            type = new byte[hunkCount]; length = new int[hunkCount]; offset = new long[hunkCount]; crc = new short[hunkCount]; hasCrc = new boolean[hunkCount];
            if (codecs[0] == 0) readRawMap(mapOffset); else readCompressedMap(mapOffset);
            cacheHunks = Math.max(4, CACHE_BYTES / hunkBytes);
            cache = new LinkedHashMap<Integer, byte[]>(cacheHunks * 2, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) { return size() > cacheHunks; }
            };
        } catch (IOException | RuntimeException e) {
            try { raw.close(); } catch (IOException ignored) { }
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("Corrupt CHD: " + e, e);
        }
    }

    /** True when the source starts with the CHD magic. */
    public static boolean isChd(IsoSource s) throws IOException {
        byte[] m = new byte[8]; return s.length() >= 8 && s.read(0, m, 0, 8) == 8 && MAGIC.equals(new String(m, StandardCharsets.ISO_8859_1));
    }
    static int tag(String s) { return s.charAt(0) << 24 | s.charAt(1) << 16 | s.charAt(2) << 8 | s.charAt(3); }
    static String tagName(int t) { if (t == 0) return "none"; char[] c = {(char)(t >>> 24 & 255), (char)(t >>> 16 & 255), (char)(t >>> 8 & 255), (char)(t & 255)}; return new String(c).trim(); }
    /** The codec names ("lzma", "zstd", ...), for logs. */
    public String codecNames() { StringBuilder b = new StringBuilder(); for (int c : codecs) if (c != 0) b.append(b.length() > 0 ? "," : "").append(tagName(c)); return b.length() == 0 ? "none" : b.toString(); }

    private byte[] readFully(long at, int n) throws IOException {
        byte[] b = new byte[n]; int got = raw.read(at, b, 0, n);
        if (got != n) throw new IOException("CHD is truncated (read " + got + "/" + n + " at " + at + ")");
        return b;
    }
    private void readMetadata(long at) throws IOException {
        Set<Long> seen = new HashSet<>();
        while (at != 0 && metadataTags.size() < 64 && seen.add(at)) {
            if (at < 0 || at + 16 > raw.length()) throw new IOException("CHD metadata offset out of range");
            byte[] m = readFully(at, 16);
            metadataTags.add(tagName(be32(m, 0)));
            at = be64(m, 8);
        }
    }
    private void readRawMap(long mapOffset) throws IOException {
        long bytes = 4L * hunkCount; if (mapOffset < 0 || mapOffset + bytes > raw.length()) throw new IOException("CHD map out of range");
        byte[] m = readFully(mapOffset, (int)bytes);
        for (int i = 0; i < hunkCount; i++) {
            long o = (be32(m, 4 * i) & 0xffffffffL) * hunkBytes;
            if (o == 0) type[i] = -1; else { type[i] = 4; offset[i] = o; length[i] = hunkBytes; }
        }
    }

    private void readCompressedMap(long mapOffset) throws IOException {
        if (mapOffset < 0 || mapOffset + 16 > raw.length()) throw new IOException("CHD map out of range");
        byte[] mh = readFully(mapOffset, 16);
        long mapBytes = be32(mh, 0) & 0xffffffffL; long firstOffset = be48(mh, 4); int mapCrc = be16(mh, 10);
        int lengthBits = mh[12] & 255, selfBits = mh[13] & 255, parentBits = mh[14] & 255;
        if (mapBytes > 64L * 1024 * 1024 || mapOffset + 16 + mapBytes > raw.length()) throw new IOException("CHD map size out of range");
        if (lengthBits > 32 || selfBits > 32 || parentBits > 48) throw new IOException("CHD map has bad field widths");
        BitReader bits = new BitReader(readFully(mapOffset + 16, (int)mapBytes));
        Huffman types = Huffman.importRle(bits, 16, 8);
        int last = 0, repeat = 0;
        for (int i = 0; i < hunkCount; i++) {
            if (repeat > 0) { type[i] = (byte)last; repeat--; continue; }
            int v = types.decode(bits);
            if (v == 7) { type[i] = (byte)last; repeat = 2 + types.decode(bits); }          // RLE small
            else if (v == 8) { type[i] = (byte)last; repeat = 2 + 16 + (types.decode(bits) << 4); repeat += types.decode(bits); } // RLE large
            else type[i] = (byte)(last = v);
        }
        long cur = firstOffset, lastSelf = 0, lastParent = 0;
        byte[] check = new byte[12];
        Crc16 mapCheck = new Crc16();
        for (int i = 0; i < hunkCount; i++) {
            long off = cur; int len = 0, c = 0; int t = type[i];
            switch (t) {
                case 0: case 1: case 2: case 3:
                    len = (int)bits.read(lengthBits); cur += len; c = (int)bits.read(16); hasCrc[i] = true; break;
                case 4: len = hunkBytes; cur += len; c = (int)bits.read(16); hasCrc[i] = true; break;
                case 5: off = lastSelf = bits.read(selfBits); break;
                case 6: off = lastParent = bits.read(parentBits); break;
                case 10: lastSelf++; // fall through: SELF_1 = previous self reference + 1
                case 9: t = 5; off = lastSelf; break;
                case 11: t = 6; off = lastParent = (long)i * hunkBytes / unitBytes; break;
                case 13: lastParent += hunkBytes / unitBytes; // fall through: PARENT_1
                case 12: t = 6; off = lastParent; break;
                default: throw new IOException("CHD map has an unknown hunk type " + t);
            }
            type[i] = (byte)t; length[i] = len; offset[i] = off; crc[i] = (short)c;
            // The map CRC covers the expanded 12-byte entries: type, u24 length, u48 offset, u16 CRC.
            check[0] = (byte)t; check[1] = (byte)(len >>> 16); check[2] = (byte)(len >>> 8); check[3] = (byte)len;
            for (int k = 0; k < 6; k++) check[4 + k] = (byte)(off >>> (40 - 8 * k));
            check[10] = (byte)(c >>> 8); check[11] = (byte)c;
            mapCheck.update(check, 0, 12);
        }
        if (bits.overflow()) throw new IOException("CHD map is truncated");
        if (mapCheck.value() != mapCrc) throw new IOException("CHD map CRC mismatch (corrupt image)");
        for (int i = 0; i < hunkCount; i++) {
            if (type[i] == 6) throw new IOException("This CHD needs a parent image (hunk " + i + ")");
            if (type[i] <= 3 && codecs[type[i]] == 0) throw new IOException("CHD hunk " + i + " uses an empty codec slot");
            if (type[i] == 5 && (offset[i] < 0 || offset[i] >= hunkCount)) throw new IOException("CHD hunk " + i + " refers to a missing hunk");
            if ((type[i] <= 4) && (offset[i] < 0 || offset[i] + length[i] > raw.length())) throw new IOException("CHD hunk " + i + " lies outside the file");
        }
    }

    @Override public long length() { return logicalBytes; }
    @Override public String describe() { return "chd:" + raw.describe(); }
    /** The underlying (compressed) source. */
    public IsoSource raw() { return raw; }

    @Override public int read(long pos, byte[] out, int at, int n) throws IOException {
        if (closed) throw new IOException("CHD closed");
        if (pos < 0 || pos >= logicalBytes || n <= 0) return 0;
        n = (int)Math.min(n, logicalBytes - pos); int done = 0;
        while (done < n) {
            long p = pos + done; int h = (int)(p / hunkBytes), within = (int)(p % hunkBytes);
            byte[] data = hunk(h); int take = Math.min(n - done, hunkBytes - within);
            System.arraycopy(data, within, out, at + done, take); done += take;
            boolean sequential; synchronized (this) { sequential = h == lastHunk + 1 || h == lastHunk; lastHunk = h; }
            if (sequential) readAhead(h);
        }
        return n;
    }

    /** One decoded hunk, from the cache, an in-flight read-ahead, or decoded on this thread. */
    byte[] hunk(int h) throws IOException {
        CompletableFuture<byte[]> future; boolean mine = false;
        synchronized (this) {
            byte[] hit = cache.get(h); if (hit != null) { hits++; return hit; }
            future = inflight.get(h);
            if (future == null) { future = new CompletableFuture<>(); inflight.put(h, future); mine = true; }
        }
        if (mine) {
            try {
                byte[] data = decode(h, 0);
                synchronized (this) { cache.put(h, data); inflight.remove(h); }
                future.complete(data); return data;
            } catch (IOException | RuntimeException e) {
                synchronized (this) { inflight.remove(h); }
                IOException io = e instanceof IOException ? (IOException)e : new IOException("CHD hunk " + h + " is corrupt: " + e, e);
                future.completeExceptionally(io); throw io;
            }
        }
        try { return future.get(60, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw e.getCause() instanceof IOException ? (IOException)e.getCause() : new IOException(e.getCause()); }
        catch (TimeoutException e) { throw new IOException("CHD hunk " + h + " timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    private void readAhead(int h) {
        for (int i = h + 1; i <= h + READ_AHEAD && i < hunkCount; i++) {
            synchronized (this) { if (closed || cache.containsKey(i) || inflight.containsKey(i)) continue; }
            final int next = i;
            try { decoder.execute(() -> { try { hunk(next); } catch (IOException ignored) { } }); } catch (RejectedExecutionException ignored) { }
        }
    }

    private byte[] decode(int h, int depth) throws IOException {
        if (h < 0 || h >= hunkCount) throw new IOException("CHD hunk " + h + " out of range");
        int t = type[h]; byte[] out = new byte[hunkBytes];
        switch (t) {
            case -1: return out;
            case 5: {
                if (depth > 8) throw new IOException("CHD self-reference chain too long at hunk " + h);
                int target = (int)offset[h];
                byte[] cached; synchronized (this) { cached = cache.get(target); }
                return cached != null ? cached : decode(target, depth + 1);
            }
            case 4: {
                int got = raw.read(offset[h], out, 0, hunkBytes); compressedRead += got;
                if (got != hunkBytes) throw new IOException("CHD hunk " + h + " is truncated");
                break;
            }
            default: {
                if (length[h] <= 0 || length[h] > hunkBytes * 2 + 1024) throw new IOException("CHD hunk " + h + " has a bad length");
                byte[] comp = new byte[length[h]];
                int got = raw.read(offset[h], comp, 0, comp.length); compressedRead += got;
                if (got != comp.length) throw new IOException("CHD hunk " + h + " is truncated");
                decompress(codecs[t], comp, out, h);
            }
        }
        if (hasCrc[h]) { Crc16 c = new Crc16(); c.update(out, 0, hunkBytes); if (c.value() != (crc[h] & 0xffff)) throw new IOException("CHD hunk " + h + " CRC mismatch (corrupt image)"); }
        decoded++;
        return out;
    }

    private void decompress(int codec, byte[] comp, byte[] out, int h) throws IOException {
        try {
            if (codec == CODEC_LZMA) new LzmaDecoder(3, 0, 2).decode(comp, 0, comp.length, out, 0, out.length);
            else if (codec == CODEC_ZSTD) new ZstdDecoder().decode(comp, 0, comp.length, out, 0, out.length);
            else if (codec == CODEC_ZLIB) {
                Inflater inf = new Inflater(true);
                try {
                    inf.setInput(comp); int n = 0;
                    while (n < out.length) { int r = inf.inflate(out, n, out.length - n); if (r == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break; n += r; }
                    if (n != out.length) throw new IOException("zlib hunk is short (" + n + "/" + out.length + ")");
                } catch (DataFormatException e) { throw new IOException("zlib: " + e.getMessage()); } finally { inf.end(); }
            }
            else if (codec == CODEC_HUFF) {
                BitReader bits = new BitReader(comp); Huffman tree = Huffman.importHuffman(bits, 256, 16);
                for (int i = 0; i < out.length; i++) out[i] = (byte)tree.decode(bits);
                if (bits.overflow()) throw new IOException("huff hunk is truncated");
            }
            else if (codec == CODEC_FLAC) FlacDecoder.decodeChdHunk(comp, out);
            else throw new IOException("CHD codec '" + tagName(codec) + "' is not supported (convert with chdman createdvd -c zstd,lzma)");
        } catch (IOException e) {
            throw new IOException("CHD hunk " + h + " (" + tagName(codec) + "): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("CHD hunk " + h + " (" + tagName(codec) + ") is corrupt: " + e, e);
        }
    }

    @Override public void close() throws IOException { closed = true; synchronized (this) { cache.clear(); } raw.close(); }
    @Override public String toString() {
        return String.format(Locale.ROOT, "%s: CHD v%d %s, hunk %d, %d hunks, %d decoded, %d hits, %d KiB read", raw.describe(), version, codecNames(), hunkBytes, hunkCount, decoded, hits, compressedRead / 1024);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------
    static int be16(byte[] b, int p) { return (b[p] & 255) << 8 | (b[p + 1] & 255); }
    static int be32(byte[] b, int p) { return be16(b, p) << 16 | be16(b, p + 2); }
    static long be48(byte[] b, int p) { return (long)be16(b, p) << 32 | (be32(b, p + 2) & 0xffffffffL); }
    static long be64(byte[] b, int p) { return (long)be32(b, p) << 32 | (be32(b, p + 4) & 0xffffffffL); }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), as CHD uses for the map and hunks. */
    static final class Crc16 {
        private static final int[] TABLE = new int[256];
        static { for (int i = 0; i < 256; i++) { int c = i << 8; for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? (c << 1) ^ 0x1021 : c << 1; TABLE[i] = c & 0xffff; } }
        private int crc = 0xffff;
        void update(byte[] b, int off, int len) { int c = crc; for (int i = off; i < off + len; i++) c = ((c << 8) ^ TABLE[((c >>> 8) ^ b[i]) & 255]) & 0xffff; crc = c; }
        int value() { return crc; }
    }

    /** MSB-first bit reader; reads past the end return zero bits and set the overflow flag. */
    static final class BitReader {
        private final byte[] b; private long pos; private final long total;
        BitReader(byte[] b) { this.b = b; total = (long)b.length * 8; }
        long read(int n) { long v = peek(n); pos += n; return v; }
        long peek(int n) {
            if (n == 0) return 0;
            long v = 0;
            for (int i = 0; i < n; ) {
                long p = pos + i; int byteIndex = (int)(p >>> 3), bit = (int)(p & 7), take = Math.min(8 - bit, n - i);
                int by = byteIndex < b.length ? b[byteIndex] & 255 : 0;
                v = (v << take) | ((by >>> (8 - bit - take)) & ((1 << take) - 1));
                i += take;
            }
            return v;
        }
        void skip(int n) { pos += n; }
        boolean overflow() { return pos > total; }
    }

    /** CHD's canonical Huffman decoder (MAME huffman.cpp format): lengths → codes, longest codes numbered first. */
    static final class Huffman {
        final int numCodes, maxBits; final int[] lengths; int[] lookup;
        Huffman(int numCodes, int maxBits) { this.numCodes = numCodes; this.maxBits = maxBits; lengths = new int[numCodes]; }

        /** Tree stored as RLE-coded code lengths (the v5 map's compression-type tree). */
        static Huffman importRle(BitReader bits, int numCodes, int maxBits) throws IOException {
            Huffman h = new Huffman(numCodes, maxBits);
            int width = maxBits >= 16 ? 5 : maxBits >= 8 ? 4 : 3;
            for (int cur = 0; cur < numCodes; ) {
                int nb = (int)bits.read(width);
                if (nb != 1) { h.lengths[cur++] = nb; continue; }
                nb = (int)bits.read(width);
                if (nb == 1) { h.lengths[cur++] = nb; continue; }
                int rep = (int)bits.read(width) + 3;
                if (cur + rep > numCodes) throw new IOException("CHD Huffman tree is invalid");
                while (rep-- > 0) h.lengths[cur++] = nb;
            }
            h.build();
            if (bits.overflow()) throw new IOException("CHD Huffman tree is truncated");
            return h;
        }
        /** Tree stored Huffman-coded with a small 24-symbol tree (the "huff" codec). */
        static Huffman importHuffman(BitReader bits, int numCodes, int maxBits) throws IOException {
            Huffman small = new Huffman(24, 6);
            small.lengths[0] = (int)bits.read(3);
            int start = (int)bits.read(3) + 1, count = 0;
            for (int i = 1; i < 24; i++) {
                if (i < start || count == 7) small.lengths[i] = 0;
                else { count = (int)bits.read(3); small.lengths[i] = count == 7 ? 0 : count; }
            }
            small.build();
            int temp = numCodes - 9, rleBits = 0; while (temp != 0) { temp >>>= 1; rleBits++; }
            Huffman h = new Huffman(numCodes, maxBits); int last = 0, cur = 0;
            while (cur < numCodes) {
                int v = small.decode(bits);
                if (v != 0) h.lengths[cur++] = last = v - 1;
                else {
                    int c = (int)bits.read(3) + 2;
                    if (c == 7 + 2) c += (int)bits.read(rleBits);
                    for (; c != 0 && cur < numCodes; c--) h.lengths[cur++] = last;
                }
                if (bits.overflow()) throw new IOException("CHD Huffman tree is truncated");
            }
            h.build();
            return h;
        }
        private void build() throws IOException {
            int[] histo = new int[33];
            for (int l : lengths) { if (l > maxBits) throw new IOException("CHD Huffman code too long"); histo[l]++; }
            int start = 0;
            for (int len = 32; len > 0; len--) {
                int next = (start + histo[len]) >>> 1;
                if (len != 1 && next * 2 != start + histo[len]) throw new IOException("CHD Huffman tree is inconsistent");
                histo[len] = start; start = next;
            }
            int[] codes = new int[numCodes];
            for (int i = 0; i < numCodes; i++) if (lengths[i] > 0) codes[i] = histo[lengths[i]]++;
            lookup = new int[1 << maxBits];
            for (int i = 0; i < numCodes; i++) {
                int l = lengths[i]; if (l == 0) continue;
                int shift = maxBits - l, first = codes[i] << shift, last = ((codes[i] + 1) << shift) - 1;
                if (last >= lookup.length) throw new IOException("CHD Huffman code out of range");
                for (int k = first; k <= last; k++) lookup[k] = i << 5 | l;
            }
        }
        int decode(BitReader bits) {
            int e = lookup[(int)bits.peek(maxBits)];
            bits.skip(e & 31);
            return e >>> 5;
        }
    }
}
