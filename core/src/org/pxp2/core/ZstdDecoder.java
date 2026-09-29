package org.pxp2.core;

import java.io.IOException;

/**
 * Original Zstandard decoder (RFC 8878) for frames whose decompressed size is known and fits one buffer, as in CHD
 * "zstd" hunks. Supports raw, RLE and compressed blocks, Huffman literals (1 or 4 streams, treeless reuse), predefined,
 * RLE, FSE and repeat sequence tables, repeat offsets and skippable frames. Dictionaries are not supported (CHD does not
 * use them) and the optional content checksum is skipped. The output buffer is the window, so offsets may reach back to
 * the start of the frame. Corrupt input ends in an IOException.
 */
final class ZstdDecoder {
    private static final int MAGIC = 0xFD2FB528, MAX_BLOCK = 128 * 1024;
    private static final int[] LL_BASE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};
    private static final int[] LL_BITS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final int[] ML_BASE = new int[53], ML_BITS = new int[53];
    static {
        for (int i = 0; i < 32; i++) ML_BASE[i] = i + 3;
        int[] base = {35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539};
        int[] bits = {1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < base.length; i++) { ML_BASE[32 + i] = base[i]; ML_BITS[32 + i] = bits[i]; }
    }
    private static final short[] LL_DEFAULT = {4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1};
    private static final short[] ML_DEFAULT = {1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1};
    private static final short[] OF_DEFAULT = {1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1};
    private static final Fse LL_PREDEF = Fse.build(LL_DEFAULT, 6), ML_PREDEF = Fse.build(ML_DEFAULT, 6), OF_PREDEF = Fse.build(OF_DEFAULT, 5);

    // Per-frame state
    private byte[] src; private byte[] out; private int outStart, outPos, outEnd;
    private final int[] rep = new int[3];
    private Fse llTable, ofTable, mlTable;
    private byte[] huffSymbol; private byte[] huffBits; private int huffMaxBits; // current Huffman literal table
    private byte[] literals = new byte[MAX_BLOCK];

    /** Decodes the zstd frame(s) in {@code src[off, off+len)} into exactly {@code outLen} bytes of {@code dst}. */
    void decode(byte[] src, int off, int len, byte[] dst, int dstOff, int outLen) throws IOException {
        try {
            this.src = src; out = dst; outStart = outPos = dstOff; outEnd = dstOff + outLen;
            int p = off, end = off + len;
            while (p < end && outPos < outEnd) {
                if (end - p < 4) throw new IOException("zstd: truncated frame header");
                int magic = le32(src, p);
                if ((magic & 0xFFFFFFF0) == 0x184D2A50) { // skippable frame
                    if (end - p < 8) throw new IOException("zstd: truncated skippable frame");
                    long skip = le32(src, p + 4) & 0xffffffffL; if (skip > end - p - 8) throw new IOException("zstd: bad skippable frame");
                    p += 8 + (int)skip; continue;
                }
                if (magic != MAGIC) throw new IOException("zstd: bad magic");
                p = frame(p + 4, end);
            }
            if (outPos != outEnd) throw new IOException("zstd: decoded " + (outPos - outStart) + " of " + outLen + " bytes");
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("zstd: corrupt data (" + e + ")");
        } finally { this.src = null; out = null; }
    }

    private int frame(int p, int end) throws IOException {
        int fhd = src[p++] & 255;
        int fcsFlag = fhd >>> 6; boolean single = (fhd & 0x20) != 0, checksum = (fhd & 4) != 0; int dictFlag = fhd & 3;
        if ((fhd & 8) != 0) throw new IOException("zstd: reserved bit set");
        if (!single) p++; // window descriptor: the output buffer is the window
        int dictBytes = dictFlag == 0 ? 0 : dictFlag == 1 ? 1 : dictFlag == 2 ? 2 : 4;
        if (dictBytes > 0) { long id = 0; for (int i = 0; i < dictBytes; i++) id |= (long)(src[p + i] & 255) << (8 * i); if (id != 0) throw new IOException("zstd: dictionaries are not supported"); p += dictBytes; }
        int fcsBytes = fcsFlag == 0 ? (single ? 1 : 0) : fcsFlag == 1 ? 2 : fcsFlag == 2 ? 4 : 8;
        p += fcsBytes;
        rep[0] = 1; rep[1] = 4; rep[2] = 8; llTable = ofTable = mlTable = null; huffSymbol = null;
        while (true) {
            if (end - p < 3) throw new IOException("zstd: truncated block header");
            int h = (src[p] & 255) | (src[p + 1] & 255) << 8 | (src[p + 2] & 255) << 16; p += 3;
            boolean last = (h & 1) != 0; int type = (h >>> 1) & 3, size = h >>> 3;
            switch (type) {
                case 0:
                    if (size > end - p || size > outEnd - outPos) throw new IOException("zstd: raw block overflow");
                    System.arraycopy(src, p, out, outPos, size); outPos += size; p += size; break;
                case 1:
                    if (p >= end || size > outEnd - outPos) throw new IOException("zstd: RLE block overflow");
                    java.util.Arrays.fill(out, outPos, outPos + size, src[p]); outPos += size; p += 1; break;
                case 2:
                    if (size > end - p || size > MAX_BLOCK) throw new IOException("zstd: bad compressed block size");
                    compressedBlock(p, p + size); p += size; break;
                default: throw new IOException("zstd: reserved block type");
            }
            if (last) break;
        }
        if (checksum) p += 4;
        return p;
    }

    // ---- compressed block -----------------------------------------------------------------------------------------
    private void compressedBlock(int p, int end) throws IOException {
        int b0 = src[p] & 255, litType = b0 & 3, sizeFormat = (b0 >>> 2) & 3;
        int regen, litEnd; byte[] lit = literals; int litStart = 0;
        if (litType == 0 || litType == 1) { // raw / RLE
            int hdr;
            if ((sizeFormat & 1) == 0) { regen = b0 >>> 3; hdr = 1; }
            else if (sizeFormat == 1) { regen = (b0 >>> 4) | (src[p + 1] & 255) << 4; hdr = 2; }
            else { regen = (b0 >>> 4) | (src[p + 1] & 255) << 4 | (src[p + 2] & 255) << 12; hdr = 3; }
            p += hdr;
            if (regen > MAX_BLOCK) throw new IOException("zstd: literals too long");
            if (litType == 0) { if (regen > end - p) throw new IOException("zstd: raw literals overflow"); lit = src; litStart = p; p += regen; }
            else { java.util.Arrays.fill(literals, 0, regen, src[p]); p += 1; }
        } else { // Huffman compressed / treeless
            int comp, hdr; boolean four = sizeFormat != 0;
            if (sizeFormat <= 1) { int h = (b0) | (src[p + 1] & 255) << 8 | (src[p + 2] & 255) << 16; regen = (h >>> 4) & 0x3FF; comp = (h >>> 14) & 0x3FF; hdr = 3; }
            else if (sizeFormat == 2) { long h = (le32(src, p) & 0xffffffffL); regen = (int)((h >>> 4) & 0x3FFF); comp = (int)((h >>> 18) & 0x3FFF); hdr = 4; }
            else { long h = (le32(src, p) & 0xffffffffL) | (long)(src[p + 4] & 255) << 32; regen = (int)((h >>> 4) & 0x3FFFF); comp = (int)((h >>> 22) & 0x3FFFF); hdr = 5; }
            p += hdr;
            if (regen > MAX_BLOCK || comp > end - p) throw new IOException("zstd: bad literals size");
            int q = p, qEnd = p + comp;
            if (litType == 2) q = readHuffmanTable(q, qEnd);
            else if (huffSymbol == null) throw new IOException("zstd: treeless literals without a table");
            if (four) {
                if (qEnd - q < 6) throw new IOException("zstd: truncated jump table");
                int s1 = le16(src, q), s2 = le16(src, q + 2), s3 = le16(src, q + 4); q += 6;
                int s4 = qEnd - q - s1 - s2 - s3; if (s4 < 1) throw new IOException("zstd: bad jump table");
                int seg = (regen + 3) / 4;
                if (seg * 3 > regen) throw new IOException("zstd: bad 4-stream literal size");
                huffStream(q, s1, 0, seg); q += s1;
                huffStream(q, s2, seg, seg); q += s2;
                huffStream(q, s3, 2 * seg, seg); q += s3;
                huffStream(q, s4, 3 * seg, regen - 3 * seg);
            } else huffStream(q, qEnd - q, 0, regen);
            p = qEnd;
        }
        litEnd = litStart + regen;
        sequences(p, end, lit, litStart, litEnd);
    }

    private int readHuffmanTable(int p, int end) throws IOException {
        int hb = src[p++] & 255; int[] w = new int[256]; int n;
        if (hb >= 128) {
            n = hb - 127; int bytes = (n + 1) / 2; if (bytes > end - p) throw new IOException("zstd: truncated Huffman weights");
            for (int i = 0; i < n; i++) { int b = src[p + i / 2] & 255; w[i] = (i & 1) == 0 ? b >>> 4 : b & 15; }
            p += bytes;
        } else {
            if (hb > end - p || hb == 0) throw new IOException("zstd: bad Huffman header");
            int[] used = new int[1];
            short[] norm = new short[256];
            int al = readFseTable(src, p, p + hb, norm, 255, 6, used);
            Fse t = Fse.build(norm, al);
            BackBits br = new BackBits(src, p + used[0], p + hb);
            int s1 = br.read(al), s2 = br.read(al); n = 0;
            while (true) {
                if (n > 254) throw new IOException("zstd: too many Huffman weights");
                w[n++] = t.symbol[s1]; s1 = t.base[s1] + br.read(t.bits[s1]);
                if (br.overflow()) { if (n > 254) throw new IOException("zstd: too many Huffman weights"); w[n++] = t.symbol[s2]; break; }
                if (n > 254) throw new IOException("zstd: too many Huffman weights");
                w[n++] = t.symbol[s2]; s2 = t.base[s2] + br.read(t.bits[s2]);
                if (br.overflow()) { if (n > 254) throw new IOException("zstd: too many Huffman weights"); w[n++] = t.symbol[s1]; break; }
            }
            p += hb;
        }
        // The last symbol's weight is implied: it completes the total to the next power of two.
        long total = 0; for (int i = 0; i < n; i++) { if (w[i] > 11) throw new IOException("zstd: bad Huffman weight"); if (w[i] > 0) total += 1L << (w[i] - 1); }
        if (total == 0) throw new IOException("zstd: empty Huffman table");
        int maxBits = 64 - Long.numberOfLeadingZeros(total); // highbit + 1
        long rest = (1L << maxBits) - total;
        if (rest == 0 || Long.bitCount(rest) != 1 || maxBits > 11) throw new IOException("zstd: bad Huffman weights");
        w[n] = Long.numberOfTrailingZeros(rest) + 1; int symbols = n + 1;
        int size = 1 << maxBits; byte[] sym = new byte[size], bits = new byte[size]; int pos = 0;
        for (int weight = 1; weight <= maxBits; weight++)
            for (int s = 0; s < symbols; s++) if (w[s] == weight) {
                int span = 1 << (weight - 1);
                if (pos + span > size) throw new IOException("zstd: Huffman table overflow");
                for (int k = 0; k < span; k++) { sym[pos + k] = (byte)s; bits[pos + k] = (byte)(maxBits + 1 - weight); }
                pos += span;
            }
        if (pos != size) throw new IOException("zstd: incomplete Huffman table");
        huffSymbol = sym; huffBits = bits; huffMaxBits = maxBits;
        return p;
    }

    private void huffStream(int p, int len, int at, int count) throws IOException {
        if (len <= 0) throw new IOException("zstd: empty Huffman stream");
        BackBits br = new BackBits(src, p, p + len); byte[] sym = huffSymbol, bits = huffBits; int mb = huffMaxBits;
        for (int i = 0; i < count; i++) {
            int idx = br.peek(mb);
            literals[at + i] = sym[idx]; br.skip(bits[idx]);
        }
        if (!br.finished()) throw new IOException("zstd: Huffman stream not fully consumed");
    }

    // ---- sequences --------------------------------------------------------------------------------------------------
    private void sequences(int p, int end, byte[] lit, int litPos, int litEnd) throws IOException {
        int nb;
        if (p >= end) throw new IOException("zstd: missing sequences header");
        int b0 = src[p++] & 255;
        if (b0 == 0) nb = 0;
        else if (b0 < 128) nb = b0;
        else if (b0 < 255) { nb = ((b0 - 128) << 8) + (src[p++] & 255); }
        else { nb = (src[p] & 255) + ((src[p + 1] & 255) << 8) + 0x7F00; p += 2; }
        if (nb > 0) {
            int modes = src[p++] & 255;
            if ((modes & 3) != 0) throw new IOException("zstd: reserved sequence mode bits");
            int[] next = {p};
            llTable = table(modes >>> 6, next, end, LL_PREDEF, llTable, 35, 9);
            ofTable = table((modes >>> 4) & 3, next, end, OF_PREDEF, ofTable, 31, 8);
            mlTable = table((modes >>> 2) & 3, next, end, ML_PREDEF, mlTable, 52, 9);
            p = next[0];
            BackBits br = new BackBits(src, p, end);
            int ll = br.read(llTable.log), of = br.read(ofTable.log), ml = br.read(mlTable.log);
            for (int s = 0; s < nb; s++) {
                int ofCode = ofTable.symbol[of], mlCode = mlTable.symbol[ml], llCode = llTable.symbol[ll];
                if (ofCode > 31 || mlCode > 52 || llCode > 35) throw new IOException("zstd: bad sequence code");
                int ofValue = (1 << ofCode) + br.read(ofCode);
                int matchLen = ML_BASE[mlCode] + br.read(ML_BITS[mlCode]);
                int litLen = LL_BASE[llCode] + br.read(LL_BITS[llCode]);
                int offset;
                if (ofCode > 1 && ofValue > 3) { offset = ofValue - 3; rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = offset; }
                else {
                    int idx = ofValue + (litLen == 0 ? 1 : 0); // 1..4
                    if (idx == 1) offset = rep[0];
                    else if (idx == 2) { offset = rep[1]; rep[1] = rep[0]; rep[0] = offset; }
                    else if (idx == 3) { offset = rep[2]; rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = offset; }
                    else { offset = rep[0] - 1; if (offset == 0) offset = 1; rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = offset; }
                }
                // execute
                if (litLen > litEnd - litPos || litLen > outEnd - outPos) throw new IOException("zstd: literal run overflow");
                System.arraycopy(lit, litPos, out, outPos, litLen); litPos += litLen; outPos += litLen;
                if (offset <= 0 || offset > outPos - outStart) throw new IOException("zstd: offset past the start");
                if (matchLen > outEnd - outPos) throw new IOException("zstd: match overflow");
                int from = outPos - offset;
                if (offset >= matchLen) System.arraycopy(out, from, out, outPos, matchLen);
                else for (int k = 0; k < matchLen; k++) out[outPos + k] = out[from + k];
                outPos += matchLen;
                if (s + 1 < nb) { // state updates: literal length, match length, offset
                    ll = llTable.base[ll] + br.read(llTable.bits[ll]);
                    ml = mlTable.base[ml] + br.read(mlTable.bits[ml]);
                    of = ofTable.base[of] + br.read(ofTable.bits[of]);
                }
            }
            if (!br.finished()) throw new IOException("zstd: sequence stream not fully consumed");
        }
        int rest = litEnd - litPos;
        if (rest > outEnd - outPos) throw new IOException("zstd: trailing literals overflow");
        System.arraycopy(lit, litPos, out, outPos, rest); outPos += rest;
    }

    private Fse table(int mode, int[] p, int end, Fse predefined, Fse previous, int maxSymbol, int maxLog) throws IOException {
        switch (mode) {
            case 0: return predefined;
            case 1: { if (p[0] >= end) throw new IOException("zstd: truncated RLE table"); int s = src[p[0]++] & 255; if (s > maxSymbol) throw new IOException("zstd: bad RLE symbol"); return Fse.rle(s); }
            case 2: { short[] norm = new short[maxSymbol + 1]; int[] used = new int[1]; int al = readFseTable(src, p[0], end, norm, maxSymbol, maxLog, used); p[0] += used[0]; return Fse.build(norm, al); }
            default: if (previous == null) throw new IOException("zstd: repeat table without a previous one"); return previous;
        }
    }

    /** Reads an FSE table description (forward little-endian bits); returns the accuracy log, {@code used[0]} = bytes. */
    static int readFseTable(byte[] b, int p, int end, short[] norm, int maxSymbol, int maxLog, int[] used) throws IOException {
        long bitPos = 0; long limit = (long)(end - p) * 8;
        int al = (int)fwd(b, p, end, bitPos, 4) + 5; bitPos += 4;
        if (al > maxLog) throw new IOException("zstd: FSE accuracy too high");
        int remaining = (1 << al) + 1, threshold = 1 << al, nbBits = al + 1, symbol = 0;
        java.util.Arrays.fill(norm, (short)0);
        while (remaining > 1 && symbol <= maxSymbol) {
            int max = (2 * threshold - 1) - remaining;
            int low = (int)fwd(b, p, end, bitPos, nbBits - 1), count;
            if (low < max) { count = low; bitPos += nbBits - 1; }
            else { count = (int)fwd(b, p, end, bitPos, nbBits); if (count >= threshold) count -= max; bitPos += nbBits; }
            count--;
            remaining -= count < 0 ? -count : count;
            norm[symbol++] = (short)count;
            if (count == 0) { // repeat flags: 2 bits each, 3 means "3 more zeros and another flag"
                while (true) {
                    int r = (int)fwd(b, p, end, bitPos, 2); bitPos += 2;
                    symbol += r; if (r != 3) break;
                    if (bitPos > limit) throw new IOException("zstd: truncated FSE table");
                }
            }
            while (remaining < threshold && threshold > 1) { nbBits--; threshold >>= 1; }
            if (bitPos > limit) throw new IOException("zstd: truncated FSE table");
        }
        if (remaining != 1 || symbol > maxSymbol + 1) throw new IOException("zstd: bad FSE table");
        used[0] = (int)((bitPos + 7) >>> 3);
        return al;
    }
    /** {@code n} bits (≤ 32) at forward bit position {@code pos} of {@code b[p, end)}; zeros past the end. */
    private static long fwd(byte[] b, int p, int end, long pos, int n) {
        if (n <= 0) return 0;
        long v = 0; int first = (int)(pos >>> 3), shift = (int)(pos & 7);
        for (int i = 0; i < 6; i++) { int at = p + first + i; if (at < end) v |= (long)(b[at] & 255) << (8 * i); }
        return (v >>> shift) & ((1L << n) - 1);
    }

    /** An FSE decoding table. */
    static final class Fse {
        final int log; final int[] symbol, bits, base;
        private Fse(int log) { this.log = log; int n = 1 << log; symbol = new int[n]; bits = new int[n]; base = new int[n]; }
        static Fse rle(int s) { Fse t = new Fse(0); t.symbol[0] = s; return t; }
        static Fse build(short[] norm, int log) {
            int size = 1 << log, high = size - 1; Fse t = new Fse(log); int[] next = new int[norm.length];
            for (int s = 0; s < norm.length; s++) {
                if (norm[s] == -1) { t.symbol[high--] = s; next[s] = 1; } else next[s] = Math.max(0, norm[s]);
            }
            int step = (size >>> 1) + (size >>> 3) + 3, mask = size - 1, pos = 0;
            for (int s = 0; s < norm.length; s++)
                for (int i = 0; i < norm[s]; i++) { t.symbol[pos] = s; do pos = (pos + step) & mask; while (pos > high); }
            if (pos != 0) throw new IllegalArgumentException("FSE table spread failed");
            for (int u = 0; u < size; u++) {
                int s = t.symbol[u], ns = next[s]++;
                int nb = log - (31 - Integer.numberOfLeadingZeros(ns));
                t.bits[u] = nb; t.base[u] = (ns << nb) - size;
            }
            return t;
        }
    }

    /** Backward bit reader: the stream is read from its last byte (whose highest set bit is a start marker) down. */
    static final class BackBits {
        private final byte[] b; private final int start, end; private long pos; // pos = number of unread bits
        BackBits(byte[] b, int start, int end) throws IOException {
            this.b = b; this.start = start; this.end = end;
            if (end <= start) throw new IOException("zstd: empty bitstream");
            int last = b[end - 1] & 255; if (last == 0) throw new IOException("zstd: bitstream without end marker");
            pos = (long)(end - start - 1) * 8 + (31 - Integer.numberOfLeadingZeros(last));
        }
        int peek(int n) {
            if (n == 0) return 0;
            long p = pos - n; // bits [p, p+n)
            if (p >= 0) {
                int first = (int)(p >>> 3), shift = (int)(p & 7); long v = 0;
                for (int i = 0; i < 5; i++) { int at = start + first + i; if (at < end) v |= (long)(b[at] & 255) << (8 * i); }
                return (int)((v >>> shift) & ((1L << n) - 1));
            }
            if (pos <= 0) return 0;
            // fewer than n bits left: the missing low bits read as zero
            int have = (int)pos; long v = 0;
            for (int i = 0; i < 5; i++) { int at = start + i; if (at < end) v |= (long)(b[at] & 255) << (8 * i); }
            return (int)((v & ((1L << have) - 1)) << (n - have));
        }
        void skip(int n) { pos -= n; }
        int read(int n) { int v = peek(n); pos -= n; return v; }
        boolean overflow() { return pos < 0; }
        boolean finished() { return pos == 0; }
    }

    private static int le16(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8; }
    private static int le32(byte[] b, int p) { return le16(b, p) | le16(b, p + 2) << 16; }
}
