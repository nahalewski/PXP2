package org.pxp2.core;

import java.io.IOException;

/**
 * Original LZMA (LZMA1) decoder for one independently compressed block whose decompressed size is known, as stored in
 * CHD hunks (MAME's "lzma" codec: raw range-coded data, no header, no end marker, lc=3 lp=0 pb=2). The output buffer
 * doubles as the dictionary, so no separate window is needed. Written from the published algorithm description; any
 * malformed input ends in an IOException, never in an out-of-bounds access.
 */
final class LzmaDecoder {
    private static final int STATES = 12, POS_STATES_MAX = 16, ALIGN_BITS = 4, END_POS_MODEL = 14, FULL_DISTANCES = 128;
    private final int lc, lp, pb;
    private final int[] isMatch = new int[STATES * POS_STATES_MAX], isRep = new int[STATES], isRepG0 = new int[STATES],
        isRepG1 = new int[STATES], isRepG2 = new int[STATES], isRep0Long = new int[STATES * POS_STATES_MAX],
        posSlot = new int[4 * 64], specPos = new int[FULL_DISTANCES - END_POS_MODEL], align = new int[1 << ALIGN_BITS];
    private final int[] literal;
    private final LengthDecoder matchLen = new LengthDecoder(), repLen = new LengthDecoder();
    // range decoder
    private byte[] in; private int inPos, inEnd, range, code;

    LzmaDecoder(int lc, int lp, int pb) {
        if (lc < 0 || lc > 8 || lp < 0 || lp > 4 || pb < 0 || pb > 4) throw new IllegalArgumentException("bad LZMA properties");
        this.lc = lc; this.lp = lp; this.pb = pb; literal = new int[0x300 << (lc + lp)];
    }

    private static final class LengthDecoder {
        final int[] choice = new int[2], low = new int[POS_STATES_MAX << 3], mid = new int[POS_STATES_MAX << 3], high = new int[256];
        void reset() { fill(choice); fill(low); fill(mid); fill(high); }
    }
    private static void fill(int[] a) { java.util.Arrays.fill(a, 1024); }

    /** Decodes exactly {@code outLen} bytes. Throws IOException on corrupt or truncated input. */
    void decode(byte[] src, int srcOff, int srcLen, byte[] out, int outOff, int outLen) throws IOException {
        fill(isMatch); fill(isRep); fill(isRepG0); fill(isRepG1); fill(isRepG2); fill(isRep0Long); fill(posSlot); fill(specPos); fill(align); fill(literal);
        matchLen.reset(); repLen.reset();
        in = src; inPos = srcOff; inEnd = srcOff + srcLen;
        if (srcLen < 5 || (src[srcOff] & 255) != 0) throw new IOException("LZMA: bad stream start");
        inPos++; code = 0; range = -1;
        for (int i = 0; i < 4; i++) code = (code << 8) | (src[inPos++] & 255);
        int state = 0, rep0 = 0, rep1 = 0, rep2 = 0, rep3 = 0;
        final int pbMask = (1 << pb) - 1, lpMask = (1 << lp) - 1, end = outOff + outLen;
        int pos = outOff;
        while (pos < end) {
            int total = pos - outOff, posState = total & pbMask;
            if (bit(isMatch, (state << 4) + posState) == 0) {
                int prev = total > 0 ? out[pos - 1] & 255 : 0;
                int base = 0x300 * (((total & lpMask) << lc) + (prev >>> (8 - lc)));
                int symbol = 1;
                if (state < 7) {
                    do symbol = (symbol << 1) | bit(literal, base + symbol); while (symbol < 0x100);
                } else {
                    if (rep0 >= total) throw new IOException("LZMA: distance past start");
                    int matchByte = out[pos - rep0 - 1] & 255;
                    do {
                        int matchBit = (matchByte >>> 7) & 1; matchByte <<= 1;
                        int b = bit(literal, base + ((1 + matchBit) << 8) + symbol);
                        symbol = (symbol << 1) | b;
                        if (matchBit != b) { while (symbol < 0x100) symbol = (symbol << 1) | bit(literal, base + symbol); break; }
                    } while (symbol < 0x100);
                }
                out[pos++] = (byte)symbol;
                state = state < 4 ? 0 : state < 10 ? state - 3 : state - 6;
                continue;
            }
            int len;
            if (bit(isRep, state) != 0) {
                if (total == 0) throw new IOException("LZMA: repeat at start");
                if (bit(isRepG0, state) == 0) {
                    if (bit(isRep0Long, (state << 4) + posState) == 0) { // short rep: one byte at rep0
                        if (rep0 >= total) throw new IOException("LZMA: distance past start");
                        state = state < 7 ? 9 : 11;
                        out[pos] = out[pos - rep0 - 1]; pos++;
                        continue;
                    }
                } else {
                    int dist;
                    if (bit(isRepG1, state) == 0) dist = rep1;
                    else {
                        if (bit(isRepG2, state) == 0) dist = rep2; else { dist = rep3; rep3 = rep2; }
                        rep2 = rep1;
                    }
                    rep1 = rep0; rep0 = dist;
                }
                len = length(repLen, posState);
                state = state < 7 ? 8 : 11;
            } else {
                rep3 = rep2; rep2 = rep1; rep1 = rep0;
                len = length(matchLen, posState);
                state = state < 7 ? 7 : 10;
                rep0 = distance(len);
                if (rep0 == -1) break; // end marker
            }
            len += 2;
            if ((rep0 & 0xffffffffL) >= total) throw new IOException("LZMA: distance past start");
            if (len > end - pos) throw new IOException("LZMA: match past the end of the block");
            int from = pos - rep0 - 1;
            for (int k = 0; k < len; k++) out[pos + k] = out[from + k];
            pos += len;
        }
        if (pos != end) throw new IOException("LZMA: stream ended early (" + (pos - outOff) + "/" + outLen + ")");
        in = null;
    }

    private int length(LengthDecoder d, int posState) throws IOException {
        if (bit(d.choice, 0) == 0) return tree(d.low, posState << 3, 3);
        if (bit(d.choice, 1) == 0) return 8 + tree(d.mid, posState << 3, 3);
        return 16 + tree(d.high, 0, 8);
    }
    private int distance(int len) throws IOException {
        int lenState = len < 3 ? len : 3;
        int slot = tree(posSlot, lenState << 6, 6);
        if (slot < 4) return slot;
        int direct = (slot >>> 1) - 1, dist = (2 | (slot & 1)) << direct;
        if (slot < END_POS_MODEL) return dist + reverseTree(specPos, dist - slot - 1, direct);
        dist += directBits(direct - ALIGN_BITS) << ALIGN_BITS;
        return dist + reverseTree(align, 0, ALIGN_BITS);
    }
    private int tree(int[] probs, int off, int bits) throws IOException {
        int m = 1; for (int i = 0; i < bits; i++) m = (m << 1) | bit(probs, off + m);
        return m - (1 << bits);
    }
    private int reverseTree(int[] probs, int off, int bits) throws IOException {
        int m = 1, sym = 0;
        for (int i = 0; i < bits; i++) { int b = bit(probs, off + m); m = (m << 1) | b; sym |= b << i; }
        return sym;
    }
    private int directBits(int count) throws IOException {
        int result = 0;
        for (int i = 0; i < count; i++) {
            range >>>= 1;
            int b = Integer.compareUnsigned(code, range) >= 0 ? 1 : 0;
            if (b != 0) code -= range;
            result = (result << 1) | b;
            normalize();
        }
        return result;
    }
    private int bit(int[] probs, int i) throws IOException {
        int p = probs[i], bound = (range >>> 11) * p, b;
        if (Integer.compareUnsigned(code, bound) < 0) { range = bound; probs[i] = p + ((2048 - p) >>> 5); b = 0; }
        else { range -= bound; code -= bound; probs[i] = p - (p >>> 5); b = 1; }
        normalize();
        return b;
    }
    private void normalize() throws IOException {
        if (Integer.compareUnsigned(range, 1 << 24) < 0) {
            // Up to a few bytes past the end are read as zero (the encoder's flush); more than that is truncation.
            int next;
            if (inPos < inEnd) next = in[inPos] & 255; else if (inPos < inEnd + 4) next = 0; else throw new IOException("LZMA: truncated input");
            inPos++;
            range <<= 8; code = (code << 8) | next;
        }
    }
}
