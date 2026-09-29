package org.pxp2.core;

import java.io.IOException;

/**
 * Original minimal FLAC frame decoder for CHD "flac" hunks. chdman's default DVD codec list (lzma, zlib, huff, flac)
 * lets FLAC win the odd hunk even on data discs, so CHDs made with default settings need it. A CHD FLAC hunk is one byte
 * of sample byte order ('L' little-endian or 'B' big-endian 16-bit samples) followed by bare FLAC frames (no "fLaC"
 * stream header) of 2-channel 16-bit audio; the decoded samples, interleaved, are the hunk's bytes. Supports constant,
 * verbatim, fixed and LPC subframes, wasted bits, the stereo decorrelation modes and both Rice partition formats.
 * Written from the FLAC format specification. Corrupt data throws IOException.
 */
final class FlacDecoder {
    private final byte[] b; private final int end; private long pos; // bit position (MSB first)

    private FlacDecoder(byte[] b, int start, int end) { this.b = b; this.end = end; pos = (long)start * 8; }

    static void decodeChdHunk(byte[] comp, byte[] out) throws IOException {
        if (comp.length < 2) throw new IOException("flac: empty hunk");
        boolean little = comp[0] == 'L';
        if (!little && comp[0] != 'B') throw new IOException("flac: bad byte-order marker");
        if (out.length % 4 != 0) throw new IOException("flac: hunk is not whole stereo samples");
        FlacDecoder d = new FlacDecoder(comp, 1, comp.length);
        int total = out.length / 4, done = 0;
        int[][] ch = new int[2][];
        while (done < total) {
            int n = d.frame(ch);
            if (n > total - done) throw new IOException("flac: frame overruns the hunk");
            for (int i = 0; i < n; i++) {
                int o = (done + i) * 4;
                for (int c = 0; c < 2; c++) {
                    int s = ch[c][i];
                    if (little) { out[o + 2 * c] = (byte)s; out[o + 2 * c + 1] = (byte)(s >> 8); }
                    else { out[o + 2 * c] = (byte)(s >> 8); out[o + 2 * c + 1] = (byte)s; }
                }
            }
            done += n;
        }
    }

    private long bits(int n) throws IOException {
        if (n == 0) return 0;
        if (pos + n > (long)end * 8) throw new IOException("flac: truncated frame");
        long v = 0;
        for (int i = 0; i < n; ) {
            int byteIndex = (int)(pos >>> 3), bit = (int)(pos & 7), take = Math.min(8 - bit, n - i);
            v = (v << take) | (((b[byteIndex] & 255) >>> (8 - bit - take)) & ((1 << take) - 1));
            pos += take; i += take;
        }
        return v;
    }
    private int signed(int n) throws IOException { if (n == 0) return 0; long v = bits(n); return (int)((v << (64 - n)) >> (64 - n)); }
    private int unary() throws IOException { int q = 0; while (bits(1) == 0) { if (++q > 1 << 20) throw new IOException("flac: bad unary code"); } return q; }
    private void align() { pos = (pos + 7) & ~7L; }

    /** Decodes one frame into ch[0], ch[1]; returns its block size. */
    private int frame(int[][] ch) throws IOException {
        align();
        if (bits(14) != 0x3FFE) throw new IOException("flac: lost frame sync");
        bits(2); // reserved, blocking strategy
        int bsCode = (int)bits(4), srCode = (int)bits(4), chanAssign = (int)bits(4), ssCode = (int)bits(3); bits(1);
        // UTF-8 style frame/sample number
        int first = (int)bits(8), extra = 0;
        if ((first & 0x80) != 0) { if ((first & 0xE0) == 0xC0) extra = 1; else if ((first & 0xF0) == 0xE0) extra = 2; else if ((first & 0xF8) == 0xF0) extra = 3; else if ((first & 0xFC) == 0xF8) extra = 4; else if ((first & 0xFE) == 0xFC) extra = 5; else if (first == 0xFE) extra = 6; else throw new IOException("flac: bad frame number"); }
        bits(8 * extra);
        int blockSize;
        if (bsCode == 1) blockSize = 192;
        else if (bsCode >= 2 && bsCode <= 5) blockSize = 576 << (bsCode - 2);
        else if (bsCode == 6) blockSize = (int)bits(8) + 1;
        else if (bsCode == 7) blockSize = (int)bits(16) + 1;
        else if (bsCode >= 8) blockSize = 256 << (bsCode - 8);
        else throw new IOException("flac: reserved block size");
        if (srCode == 12) bits(8); else if (srCode == 13 || srCode == 14) bits(16); else if (srCode == 15) throw new IOException("flac: bad sample rate");
        int bps = ssCode == 0 ? 16 : ssCode == 1 ? 8 : ssCode == 2 ? 12 : ssCode == 4 ? 16 : ssCode == 5 ? 20 : ssCode == 6 ? 24 : -1;
        if (bps < 0) throw new IOException("flac: reserved sample size");
        bits(8); // header CRC-8
        int channels = chanAssign < 8 ? chanAssign + 1 : chanAssign <= 10 ? 2 : -1;
        if (channels != 2) throw new IOException("flac: expected 2 channels, got assignment " + chanAssign);
        for (int c = 0; c < 2; c++) {
            if (ch[c] == null || ch[c].length < blockSize) ch[c] = new int[blockSize];
            int sbps = bps + ((chanAssign == 8 && c == 1) || (chanAssign == 9 && c == 0) || (chanAssign == 10 && c == 1) ? 1 : 0);
            subframe(ch[c], blockSize, sbps);
        }
        int[] l = ch[0], r = ch[1];
        if (chanAssign == 8) for (int i = 0; i < blockSize; i++) r[i] = l[i] - r[i];            // left/side
        else if (chanAssign == 9) for (int i = 0; i < blockSize; i++) l[i] = l[i] + r[i];       // side/right
        else if (chanAssign == 10) for (int i = 0; i < blockSize; i++) {                        // mid/side
            int side = r[i], mid = (l[i] << 1) | (side & 1);
            l[i] = (mid + side) >> 1; r[i] = (mid - side) >> 1;
        }
        align(); bits(16); // frame CRC-16
        return blockSize;
    }

    private void subframe(int[] s, int n, int bps) throws IOException {
        if (bits(1) != 0) throw new IOException("flac: bad subframe padding");
        int type = (int)bits(6), wasted = 0;
        if (bits(1) != 0) wasted = unary() + 1;
        bps -= wasted;
        if (bps <= 0 || bps > 32) throw new IOException("flac: bad wasted bits");
        if (type == 0) { int v = signed(bps); for (int i = 0; i < n; i++) s[i] = v; }
        else if (type == 1) { for (int i = 0; i < n; i++) s[i] = signed(bps); }
        else if (type >= 8 && type <= 12) {
            int order = type - 8; if (order > n) throw new IOException("flac: bad predictor order");
            for (int i = 0; i < order; i++) s[i] = signed(bps);
            residual(s, n, order);
            for (int i = order; i < n; i++) {
                switch (order) {
                    case 1: s[i] += s[i - 1]; break;
                    case 2: s[i] += 2 * s[i - 1] - s[i - 2]; break;
                    case 3: s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3]; break;
                    case 4: s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4]; break;
                    default: break;
                }
            }
        } else if (type >= 32) {
            int order = type - 31; if (order > n) throw new IOException("flac: bad LPC order");
            for (int i = 0; i < order; i++) s[i] = signed(bps);
            int precision = (int)bits(4) + 1; if (precision == 16) throw new IOException("flac: bad LPC precision");
            int shift = signed(5); if (shift < 0) throw new IOException("flac: negative LPC shift");
            int[] coef = new int[order]; for (int i = 0; i < order; i++) coef[i] = signed(precision);
            residual(s, n, order);
            for (int i = order; i < n; i++) {
                long sum = 0; for (int j = 0; j < order; j++) sum += (long)coef[j] * s[i - j - 1];
                s[i] += (int)(sum >> shift);
            }
        } else throw new IOException("flac: reserved subframe type " + type);
        if (wasted > 0) for (int i = 0; i < n; i++) s[i] <<= wasted;
    }

    private void residual(int[] s, int n, int order) throws IOException {
        int method = (int)bits(2); if (method > 1) throw new IOException("flac: reserved residual method");
        int paramBits = method == 0 ? 4 : 5, escape = (1 << paramBits) - 1;
        int partOrder = (int)bits(4), parts = 1 << partOrder;
        if ((n >> partOrder) << partOrder != n || (n >> partOrder) < order) throw new IOException("flac: bad partition order");
        int i = order;
        for (int p = 0; p < parts; p++) {
            int count = (n >> partOrder) - (p == 0 ? order : 0);
            int k = (int)bits(paramBits);
            if (k == escape) { int raw = (int)bits(5); for (int j = 0; j < count; j++) s[i++] = signed(raw); }
            else for (int j = 0; j < count; j++) {
                long v = ((long)unary() << k) | bits(k);
                s[i++] = (int)((v >>> 1) ^ -(v & 1));
            }
        }
    }
}
