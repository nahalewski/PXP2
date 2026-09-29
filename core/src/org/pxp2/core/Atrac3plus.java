package org.pxp2.core;

/**
 * Original pure-Java ATRAC3plus decoder (PSP codec 0x1000). One instance holds the inter-frame state of one decoder
 * context (window shapes, gain control and tone history, overlap buffers and the PQF delay line).
 *
 * Signal path per channel unit: bitstream parameters (word lengths, scale factors, code tables, Huffman-coded spectrum,
 * gain control points, tonal components, noise) → inverse quantisation with power compensation → per-subband 256-point
 * IMDCT with sine/steep windows → gain compensation and overlap-add → tone synthesis → 16-band inverse PQF → PCM.
 * The constant tables (Huffman code lengths/symbols, shapes, weights, filter coefficients) are published format
 * constants kept in {@link Atrac3plusTables}; see psp.md §5 for sources.
 *
 * Output: 2048 samples per channel per frame. Not thread-safe; allocation-free after construction.
 */
public final class Atrac3plus {
    public static final int FRAME_SAMPLES = 2048;
    private static final int SUBBANDS = 16, SB_SAMPLES = 128, POWER_COMP_OFF = 15;

    // ------------------------------------------------------------------------------------------------ tables
    private static int[] u8(String s) { int[] r = new int[s.length()]; for (int i = 0; i < r.length; i++) r[i] = s.charAt(i) & 0xff; return r; }
    private static int[] s8(String s) { int[] r = new int[s.length()]; for (int i = 0; i < r.length; i++) r[i] = (byte)s.charAt(i); return r; }

    private static final int[] WL_WEIGHTS = s8(Atrac3plusTables.WL_WEIGHTS), SF_WEIGHTS = s8(Atrac3plusTables.SF_WEIGHTS);
    private static final int[] QU_NUM_TO_SEG = u8(Atrac3plusTables.QU_NUM_TO_SEG), QU_TO_SUBBAND = u8(Atrac3plusTables.QU_TO_SUBBAND);
    private static final int[] SUBBAND_TO_NUM_POWGRPS = u8(Atrac3plusTables.SUBBAND_TO_NUM_POWGRPS);
    private static final int[] WL_SHAPES = s8(Atrac3plusTables.WL_SHAPES), SF_SHAPES = s8(Atrac3plusTables.SF_SHAPES);
    private static final int[] CT_RESTRICTED_TO_FULL = u8(Atrac3plusTables.CT_RESTRICTED_TO_FULL);
    private static final int[] SPECTRA_TABS = u8(Atrac3plusTables.SPECTRA_TABS); // {group size, coeffs per symbol, bits, signed} x 112
    private static final float[] SF_TAB = Atrac3plusTables.SF_TAB, MANT_TAB = Atrac3plusTables.MANT_TAB;
    private static final float[] NOISE_TAB = Atrac3plusTables.NOISE_TAB, PWC_LEVS = Atrac3plusTables.PWC_LEVS;
    private static final int[] QU_TO_SPEC_POS = {
        0, 16, 32, 48, 64, 80, 96, 112, 128, 160, 192, 224, 256, 288, 320, 352,
        384, 448, 512, 576, 640, 704, 768, 896, 1024, 1152, 1280, 1408, 1536, 1664, 1792, 1920, 2048};
    private static final int[] SUBBAND_TO_POWGRP = {0, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4};
    private static final int[] SUBBAND_TO_QU = {0, 8, 12, 16, 18, 20, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32};

    /** Canonical Huffman code as a single-level lookup: entry = symbol << 8 | code length (length 0 = invalid code). */
    static final class Vlc {
        final int[] table; final int bits;
        /** counts[0..11] = number of codes of length 1..12 in code order; symbols follow in the same order. */
        Vlc(int[] counts, int co, int[] symbols, int so) {
            int n = 0, max = 0; for (int l = 1; l <= 12; l++) if (counts[co + l - 1] > 0) { n += counts[co + l - 1]; max = l; }
            bits = max; table = new int[1 << max];
            java.util.Arrays.fill(table, -1 << 8);
            long code = 0; int s = so;
            for (int l = 1; l <= 12; l++)
                for (int c = counts[co + l - 1]; c > 0; c--) {
                    int first = (int)(code >>> (32 - l)) << (max - l), span = 1 << (max - l);
                    for (int i = 0; i < span; i++) table[first + i] = symbols[s] << 8 | l;
                    s++; code += 1L << (32 - l);
                }
            used = s - so;
        }
        final int used;
    }
    private static final Vlc[] WL = new Vlc[4], CT = new Vlc[4], SF = new Vlc[8], SPEC = new Vlc[112], GAIN = new Vlc[11], TONE = new Vlc[7];
    static {
        int[] wlCbs = u8(Atrac3plusTables.WL_CBS), ctCbs = u8(Atrac3plusTables.CT_CBS), x = u8(Atrac3plusTables.WL_CT_XLATS);
        int at = 0;
        for (int i = 0; i < 4; i++) { WL[i] = new Vlc(wlCbs, i * 12, x, at); at += WL[i].used; CT[i] = new Vlc(ctCbs, i * 12, x, at); at += CT[i].used; }
        int[] sfCbs = u8(Atrac3plusTables.SF_CBS); x = u8(Atrac3plusTables.SF_XLATS); at = 0;
        for (int i = 0; i < 8; i++) { SF[i] = new Vlc(sfCbs, i * 12, x, at); at += SF[i].used; }
        // spectrum code-length counts are unsigned (one reaches 172); a first entry above 127 means "reuse table 256-n"
        int[] spCbs = u8(Atrac3plusTables.SPECTRA_CBS); x = u8(Atrac3plusTables.SPECTRA_XLATS); at = 0;
        for (int i = 0; i < 112; i++) {
            if (spCbs[i * 12] > 127) SPEC[i] = SPEC[256 - spCbs[i * 12]]; // shares an earlier table
            else { SPEC[i] = new Vlc(spCbs, i * 12, x, at); at += SPEC[i].used; }
        }
        int[] gCbs = u8(Atrac3plusTables.GAIN_CBS); x = u8(Atrac3plusTables.GAIN_XLATS); at = 0;
        for (int i = 0; i < 11; i++) { GAIN[i] = new Vlc(gCbs, i * 12, x, at); at += GAIN[i].used; }
        int[] tCbs = u8(Atrac3plusTables.TONE_CBS); x = u8(Atrac3plusTables.TONE_XLATS); at = 0;
        for (int i = 0; i < 7; i++) { TONE[i] = new Vlc(tCbs, i * 12, x, at); at += TONE[i].used; }
    }

    // DSP constants computed from their definitions
    private static final float[] SINE_TABLE = new float[2048], HANN = new float[256], AMP_SF = new float[64];
    private static final float[] SINE128 = new float[128], SINE64 = new float[64];
    private static final float[] FIR1 = Atrac3plusTables.IPQF_COEFFS1, FIR2 = Atrac3plusTables.IPQF_COEFFS2; // [12][16]
    private static final float[] PQF_MATRIX = new float[16 * 16]; // DCT-IV of size 16, scaled, arranged for the PQF history
    static {
        for (int i = 0; i < 2048; i++) SINE_TABLE[i] = (float)Math.sin(2 * Math.PI * i / 2048);
        for (int i = 0; i < 256; i++) HANN[i] = (float)((1.0f - Math.cos(2 * Math.PI * i / 256.0f)) * 0.5f);
        for (int i = 0; i < 64; i++) AMP_SF[i] = (float)Math.pow(2, (i - 3) / 4.0f);
        for (int i = 0; i < 128; i++) SINE128[i] = (float)Math.sin((i + 0.5) * (Math.PI / 256));
        for (int i = 0; i < 64; i++) SINE64[i] = (float)Math.sin((i + 0.5) * (Math.PI / 128));
        // Row r of the matrix gives the value stored at history slot r (0..7 -> buf1[r], 8..15 -> buf2[r-8]):
        // buf1[i] = -s*u[7-i], buf2[i] = -s*u[8+i], u = DCT-IV(x), s = 32/32768.
        double s = 32.0 / 32768.0;
        for (int r = 0; r < 16; r++) {
            int n = r < 8 ? 7 - r : 8 + (r - 8);
            for (int k = 0; k < 16; k++) PQF_MATRIX[r * 16 + k] = (float)(-s * Math.cos(Math.PI / 16 * (n + 0.5) * (k + 0.5)));
        }
    }

    // ------------------------------------------------------------------------------------------------ state
    static final class Gain {
        int numPoints; final int[] lev = new int[7], loc = new int[7];
        void set(Gain o) { numPoints = o.numPoints; System.arraycopy(o.lev, 0, lev, 0, 7); System.arraycopy(o.loc, 0, loc, 0, 7); }
        void clear() { numPoints = 0; java.util.Arrays.fill(lev, 0); java.util.Arrays.fill(loc, 0); }
    }
    static final class Envelope {
        boolean hasStart, hasStop; int start, stop;
        void set(Envelope o) { hasStart = o.hasStart; hasStop = o.hasStop; start = o.start; stop = o.stop; }
        void clear() { hasStart = hasStop = false; start = stop = 0; }
    }
    static final class Waves {
        final Envelope pend = new Envelope(), curr = new Envelope(); int numWavs, startIndex;
        void set(Waves o) { pend.set(o.pend); curr.set(o.curr); numWavs = o.numWavs; startIndex = o.startIndex; }
        void clear() { pend.clear(); curr.clear(); numWavs = startIndex = 0; }
    }
    static final class Synth {
        boolean tonesPresent; int amplitudeMode, numToneBands, tonesIndex;
        final int[] toneSharing = new int[16], toneMaster = new int[16], invertPhase = new int[16];
        final int[] freq = new int[48], ampSf = new int[48], ampIndex = new int[48], phase = new int[48];
    }
    static final class Channel {
        final int chNum; int numCodedVals, fillMode, splitPoint, tableType, numGainSubbands;
        final int[] wordlen = new int[32], sfIdx = new int[32], tabIdx = new int[32], powerLevs = new int[5];
        final int[] spectrum = new int[2048 + 64];
        int[] wnd = new int[16], wndPrev = new int[16];
        Gain[] gain = gains(), gainPrev = gains();
        Waves[] tones = waves(), tonesPrev = waves();
        // inverse PQF history: 24 slots x (8 + 8) values
        final float[] pqf = new float[24 * 16]; int pqfPos;
        final float[] prev = new float[2048]; // IMDCT overlap
        Channel(int n) { chNum = n; }
        private static Gain[] gains() { Gain[] g = new Gain[16]; for (int i = 0; i < 16; i++) g[i] = new Gain(); return g; }
        private static Waves[] waves() { Waves[] w = new Waves[16]; for (int i = 0; i < 16; i++) w[i] = new Waves(); return w; }
    }
    static final class Unit {
        int type, numQuantUnits, numSubbands, usedQuantUnits, numCodedSubbands;
        boolean mute, useFullTable, noisePresent; int noiseLevel, noiseTable;
        final int[] swapChannels = new int[16], negateCoeffs = new int[16];
        final Channel[] ch = {new Channel(0), new Channel(1)};
        Synth waves = new Synth(), wavesPrev = new Synth();
    }

    private final Unit[] units;
    private final int channels;
    // scratch
    private final float[][] spec = new float[2][2048], time = new float[2][2048], pcm;
    private final float[] imdct = new float[256], wav1 = new float[128], wav2 = new float[128], pwc = new float[128];
    private final float[] fftRe = new float[64], fftIm = new float[64], dctOut = new float[128], pqfIn = new float[16];
    private final int[] refWaves = new int[48], bandHasTones = new int[16], rngIndex = new int[16];
    private final Bits br = new Bits();

    /** @param channels output channel count from the stream configuration (1 = mono, 2 = stereo; more channels decode the first units). */
    public Atrac3plus(int channels) {
        this.channels = Math.max(1, Math.min(8, channels));
        units = new Unit[5]; for (int i = 0; i < units.length; i++) units[i] = new Unit();
        pcm = new float[this.channels][2048];
    }
    public int channels() { return channels; }
    public void reset() { for (int i = 0; i < units.length; i++) units[i] = new Unit(); }

    /** Channel count for the ATRAC3plus configuration bytes (ctx+40/41, or the RIFF/OMA codec info). */
    public static int channelsFromConfig(int config16) {
        int id = (config16 >>> 10) & 7; return id == 0 ? 2 : new int[]{1, 1, 2, 3, 4, 6, 7, 8}[id];
    }
    /** Frame size in bytes for the configuration bytes. */
    public static int frameBytesFromConfig(int config16) { return (config16 & 0x3ff) * 8 + 8; }

    /**
     * Decodes one frame into {@code out} as interleaved stereo 16-bit PCM (2048 frames; mono is duplicated).
     * @return 0 on success, or a negative value if the frame is invalid (the output is then silence)
     */
    public int decode(byte[] frame, int off, int len, short[] out) {
        int r = decodeFloat(frame, off, len);
        float[] l = pcm[0], rt = pcm[channels > 1 ? 1 : 0];
        if (r < 0) { java.util.Arrays.fill(out, 0, 4096, (short)0); return r; }
        for (int i = 0; i < 2048; i++) { out[i * 2] = clip(l[i]); out[i * 2 + 1] = clip(rt[i]); }
        return 0;
    }
    private static short clip(float f) { int v = Math.round(f * 32768f); return (short)(v > 32767 ? 32767 : v < -32768 ? -32768 : v); }
    /** Decoded float output of the last frame, per channel, nominal range [-1, 1]. */
    public float[] output(int ch) { return pcm[ch]; }

    /** Decodes one frame to {@link #output}. */
    public int decodeFloat(byte[] frame, int off, int len) {
        Bits b = br; b.init(frame, off, len);
        if (b.bit() != 0) return -1; // start bit
        int block = 0, outCh = 0, id;
        while (b.left() >= 2 && (id = b.bits(2)) != 3) {
            if (id == 2 || block >= units.length) return -2; // extension units are not used by PSP streams
            Unit u = units[block]; u.type = id; int n = id + 1;
            int r = decodeUnit(b, u, n); if (r < 0) return r;
            residual(u, n);
            reconstruct(u, n);
            for (int c = 0; c < n; c++) if (outCh + c < channels) System.arraycopy(time[c], 0, pcm[outCh + c], 0, 2048);
            block++; outCh += n;
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ bit reader
    static final class Bits {
        private byte[] buf = new byte[1024 + 8]; private int pos, end;
        void init(byte[] src, int off, int len) {
            if (buf.length < len + 8) buf = new byte[len + 8];
            System.arraycopy(src, off, buf, 0, len); java.util.Arrays.fill(buf, len, len + 8, (byte)0);
            pos = 0; end = len * 8;
        }
        int left() { return end - pos; }
        private int window() { // 32 bits starting at the byte holding pos, left-aligned at bit (pos & 7)
            int p = Math.min(pos >> 3, buf.length - 4);
            return ((buf[p] & 255) << 24 | (buf[p + 1] & 255) << 16 | (buf[p + 2] & 255) << 8 | (buf[p + 3] & 255)) << (pos & 7);
        }
        int bits(int n) { if (n == 0) return 0; int v = window() >>> (32 - n); pos += n; return v; }
        int bit() { return bits(1); }
        int vlc(Vlc t) { int e = t.table[window() >>> (32 - t.bits)]; pos += e & 255; return e >> 8; }
    }
    private static int signExtend(int v, int bits) { return v << (32 - bits) >> (32 - bits); }
    private static int log2(int v) { return v == 0 ? 0 : 31 - Integer.numberOfLeadingZeros(v); } // floor(log2), 0 for 0 (bit counts rely on it)

    // ------------------------------------------------------------------------------------------------ bitstream
    private int decodeUnit(Bits b, Unit u, int n) {
        u.numQuantUnits = b.bits(5) + 1;
        if (u.numQuantUnits > 28 && u.numQuantUnits < 32) return -3;
        u.mute = b.bit() != 0;
        for (int c = 0; c < n; c++) { java.util.Arrays.fill(u.ch[c].wordlen, 0); if (wordlen(b, u, c) < 0) return -4; }
        int i = u.numQuantUnits - 1;
        for (; i >= 0; i--) if (u.ch[0].wordlen[i] != 0 || (n == 2 && u.ch[1].wordlen[i] != 0)) break;
        u.usedQuantUnits = i + 1;
        u.numSubbands = QU_TO_SUBBAND[u.numQuantUnits - 1] + 1;
        u.numCodedSubbands = u.usedQuantUnits > 0 ? QU_TO_SUBBAND[u.usedQuantUnits - 1] + 1 : 0;
        if (u.usedQuantUnits > 0) for (int c = 0; c < n; c++) { java.util.Arrays.fill(u.ch[c].sfIdx, 0); if (scaleFactors(b, u, c) < 0) return -5; }
        if (u.usedQuantUnits > 0) {
            u.useFullTable = b.bit() != 0;
            for (int c = 0; c < n; c++) { java.util.Arrays.fill(u.ch[c].tabIdx, 0); if (codeTables(b, u, c) < 0) return -6; }
        }
        spectrum(b, u, n);
        if (n == 2) { flags(b, u.swapChannels, u.numCodedSubbands); flags(b, u.negateCoeffs, u.numCodedSubbands); }
        for (int c = 0; c < n; c++) flags(b, u.ch[c].wnd, u.numSubbands);
        for (int c = 0; c < n; c++) if (gainData(b, u, c) < 0) return -7;
        if (tones(b, u, n) < 0) return -8;
        u.noisePresent = b.bit() != 0;
        if (u.noisePresent) { u.noiseLevel = b.bits(4); u.noiseTable = b.bits(4); }
        return 0;
    }

    /** Subband flags: 0 = all clear; 1,0 = all set; 1,1 = one bit each. */
    private static int flags(Bits b, int[] out, int n) {
        java.util.Arrays.fill(out, 0, n, 0);
        int any = b.bit();
        if (any != 0) { if (b.bit() != 0) for (int i = 0; i < n; i++) out[i] = b.bit(); else java.util.Arrays.fill(out, 0, n, 1); }
        return any;
    }

    private static int codedUnits(Bits b, Channel c, Unit u) {
        c.fillMode = b.bits(2);
        if (c.fillMode == 0) c.numCodedVals = u.numQuantUnits;
        else {
            c.numCodedVals = b.bits(5); if (c.numCodedVals > u.numQuantUnits) return -1;
            if (c.fillMode == 3) c.splitPoint = b.bits(2) + (c.chNum << 1) + 1;
        }
        return 0;
    }
    /** Fills dst[0..n) from a start value minus a segment shape (values 0..2 take the start value). */
    private static void vqShape(int start, int[] shapes, int shapeAt, int[] dst, int n) {
        if (n == 0) return;
        dst[0] = dst[1] = dst[2] = start;
        for (int i = 3; i < n; i++) dst[i] = start - shapes[shapeAt + QU_NUM_TO_SEG[i] - 1];
    }

    private static int wordlen(Bits b, Unit u, int chNum) {
        Channel c = u.ch[chNum], ref = u.ch[0]; int[] wl = c.wordlen; int weight = 0;
        c.fillMode = 0;
        switch (b.bits(2)) {
            case 0: for (int i = 0; i < u.numQuantUnits; i++) wl[i] = b.bits(3); break;
            case 1:
                if (chNum != 0) {
                    if (codedUnits(b, c, u) < 0) return -1;
                    if (c.numCodedVals > 0) { Vlc t = WL[b.bits(2)]; for (int i = 0; i < c.numCodedVals; i++) wl[i] = (ref.wordlen[i] + b.vlc(t)) & 7; }
                } else {
                    weight = b.bits(2);
                    if (codedUnits(b, c, u) < 0) return -1;
                    if (c.numCodedVals > 0) {
                        int pos = b.bits(5); if (pos > c.numCodedVals) return -1;
                        int deltaBits = b.bits(2), min = b.bits(3);
                        for (int i = 0; i < pos; i++) wl[i] = b.bits(3);
                        for (int i = pos; i < c.numCodedVals; i++) wl[i] = (min + b.bits(deltaBits)) & 7;
                    }
                }
                break;
            case 2:
                if (codedUnits(b, c, u) < 0) return -1;
                if (chNum != 0 && c.numCodedVals > 0) {
                    Vlc t = WL[b.bits(2)];
                    wl[0] = (ref.wordlen[0] + b.vlc(t)) & 7;
                    for (int i = 1; i < c.numCodedVals; i++) wl[i] = (wl[i - 1] + ref.wordlen[i] - ref.wordlen[i - 1] + b.vlc(t)) & 7;
                } else if (c.numCodedVals > 0) {
                    int flag = b.bit(); Vlc t = WL[b.bit()];
                    int start = b.bits(3);
                    vqShape(start, WL_SHAPES, (start * 16 + b.bits(4)) * 9, wl, c.numCodedVals);
                    if (flag == 0) for (int i = 0; i < c.numCodedVals; i++) wl[i] = (wl[i] + b.vlc(t)) & 7;
                    else {
                        int i = 0;
                        for (; i < (c.numCodedVals & -2); i += 2)
                            if (b.bit() == 0) { wl[i] = (wl[i] + b.vlc(t)) & 7; wl[i + 1] = (wl[i + 1] + b.vlc(t)) & 7; }
                        if ((c.numCodedVals & 1) != 0) wl[i] = (wl[i] + b.vlc(t)) & 7;
                    }
                }
                break;
            default:
                weight = b.bits(2);
                if (codedUnits(b, c, u) < 0) return -1;
                if (c.numCodedVals > 0) {
                    Vlc t = WL[b.bits(2)];
                    wl[0] = b.bits(3);
                    for (int i = 1; i < c.numCodedVals; i++) wl[i] = (wl[i - 1] + b.vlc(t)) & 7;
                }
        }
        if (c.fillMode == 2) for (int i = c.numCodedVals; i < u.numQuantUnits; i++) wl[i] = chNum != 0 ? b.bit() : 1;
        else if (c.fillMode == 3) {
            int pos = Math.min(32, chNum != 0 ? c.numCodedVals + c.splitPoint : u.numQuantUnits - c.splitPoint);
            for (int i = c.numCodedVals; i < pos; i++) wl[i] = 1;
        }
        if (weight != 0) {
            int at = (chNum * 3 + weight - 1) * 32;
            for (int i = 0; i < u.numQuantUnits; i++) { wl[i] += WL_WEIGHTS[at + i]; if (wl[i] < 0 || wl[i] > 7) return -1; }
        }
        return 0;
    }

    private static int scaleFactors(Bits b, Unit u, int chNum) {
        Channel c = u.ch[chNum], ref = u.ch[0]; int[] sf = c.sfIdx; int n = u.usedQuantUnits, weight = 0;
        switch (b.bits(2)) {
            case 0: for (int i = 0; i < n; i++) sf[i] = b.bits(6); break;
            case 1:
                if (chNum != 0) { Vlc t = SF[b.bits(2)]; for (int i = 0; i < n; i++) sf[i] = (ref.sfIdx[i] + b.vlc(t)) & 63; }
                else {
                    weight = b.bits(2);
                    if (weight == 3) {
                        int start = b.bits(6); vqShape(start, SF_SHAPES, b.bits(6) * 9, sf, n);
                        int longVals = b.bits(5), deltaBits = b.bits(2), min = b.bits(4) - 7;
                        for (int i = 0; i < longVals; i++) sf[i] = (sf[i] + b.bits(4) - 7) & 63;
                        for (int i = longVals; i < n; i++) sf[i] = (sf[i] + min + b.bits(deltaBits)) & 63;
                    } else {
                        int longVals = b.bits(5), deltaBits = b.bits(3), min = b.bits(6);
                        if (longVals > n || deltaBits == 7) return -1;
                        for (int i = 0; i < longVals; i++) sf[i] = b.bits(6);
                        for (int i = longVals; i < n; i++) sf[i] = (min + b.bits(deltaBits)) & 63;
                    }
                }
                break;
            case 2:
                if (chNum != 0) {
                    Vlc t = SF[b.bits(2)];
                    sf[0] = (ref.sfIdx[0] + b.vlc(t)) & 63;
                    for (int i = 1; i < n; i++) sf[i] = (sf[i - 1] + ref.sfIdx[i] - ref.sfIdx[i - 1] + b.vlc(t)) & 63;
                } else {
                    Vlc t = SF[b.bits(2) + 4];
                    int start = b.bits(6); vqShape(start, SF_SHAPES, b.bits(6) * 9, sf, n);
                    for (int i = 0; i < n; i++) sf[i] = (sf[i] + signExtend(b.vlc(t), 4)) & 63;
                }
                break;
            default:
                if (chNum != 0) System.arraycopy(ref.sfIdx, 0, sf, 0, n);
                else {
                    weight = b.bits(2); int sel = b.bits(2);
                    if (weight == 3) {
                        Vlc t = SF[sel + 4];
                        int start = b.bits(6); vqShape(start, SF_SHAPES, b.bits(6) * 9, sf, n);
                        int diff = (b.bits(4) + 56) & 63;
                        sf[0] = (sf[0] + diff) & 63;
                        for (int i = 1; i < n; i++) { diff = (diff + signExtend(b.vlc(t), 4)) & 63; sf[i] = (diff + sf[i]) & 63; }
                    } else {
                        Vlc t = SF[sel];
                        sf[0] = b.bits(6);
                        for (int i = 1; i < n; i++) sf[i] = (sf[i - 1] + b.vlc(t)) & 63;
                    }
                }
        }
        if (weight == 1 || weight == 2) {
            int at = (weight - 1) * 32;
            for (int i = 0; i < n; i++) { sf[i] -= SF_WEIGHTS[at + i]; if (sf[i] < 0 || sf[i] > 63) return -1; }
        }
        return 0;
    }

    private static int codeTables(Bits b, Unit u, int chNum) {
        Channel c = u.ch[chNum], ref = u.ch[0];
        int mask = u.useFullTable ? 7 : 3;
        c.tableType = b.bit();
        int mode = b.bits(2);
        if (mode == 3 && chNum == 0) return 0;
        int n = u.usedQuantUnits;
        if (b.bit() != 0) { n = b.bits(5); if (n > u.usedQuantUnits) return -1; }
        Vlc t = null, delta = null; int bitsDirect = u.useFullTable ? 3 : 2, pred = 0;
        if (mode == 1) t = u.useFullTable ? CT[1] : CT[0];
        else if (mode == 2) { t = u.useFullTable ? CT[1] : CT[0]; delta = u.useFullTable ? CT[2] : CT[0]; }
        else if (mode == 3) t = u.useFullTable ? CT[3] : CT[0];
        for (int i = 0; i < n; i++) {
            if (c.wordlen[i] != 0) {
                int v;
                switch (mode) {
                    case 0: v = b.bits(bitsDirect); break;
                    case 1: v = b.vlc(t); break;
                    case 2: v = i == 0 ? b.vlc(t) : (pred + b.vlc(delta)) & mask; pred = v; break;
                    default: v = (ref.tabIdx[i] + b.vlc(t)) & mask;
                }
                c.tabIdx[i] = v;
            } else if (chNum != 0 && ref.wordlen[i] != 0) c.tabIdx[i] = b.bit(); // clone-master flag
        }
        return 0;
    }

    private static void spectrum(Bits b, Unit u, int n) {
        for (int chNum = 0; chNum < n; chNum++) {
            Channel c = u.ch[chNum];
            java.util.Arrays.fill(c.spectrum, 0);
            java.util.Arrays.fill(c.powerLevs, POWER_COMP_OFF);
            for (int qu = 0; qu < u.usedQuantUnits; qu++) {
                int at = QU_TO_SPEC_POS[qu], num = QU_TO_SPEC_POS[qu + 1] - at, wl = c.wordlen[qu], ct = c.tabIdx[qu];
                if (wl != 0) {
                    if (!u.useFullTable) ct = CT_RESTRICTED_TO_FULL[(c.tableType * 7 + wl - 1) * 4 + ct];
                    int tab = (c.tableType * 8 + ct) * 7 + wl - 1;
                    quSpectrum(b, tab, c.spectrum, at, num);
                } else if (chNum != 0 && u.ch[0].wordlen[qu] != 0 && ct == 0) {
                    System.arraycopy(u.ch[0].spectrum, at, c.spectrum, at, num); // copy master
                    c.wordlen[qu] = u.ch[0].wordlen[qu];
                }
            }
            if (u.usedQuantUnits > 2) {
                int groups = SUBBAND_TO_NUM_POWGRPS[u.numCodedSubbands - 1];
                for (int i = 0; i < groups; i++) c.powerLevs[i] = b.bits(4);
            }
        }
    }
    private static void quSpectrum(Bits b, int tab, int[] out, int at, int num) {
        int group = SPECTRA_TABS[tab * 4], coeffs = SPECTRA_TABS[tab * 4 + 1], bits = SPECTRA_TABS[tab * 4 + 2];
        boolean signed = SPECTRA_TABS[tab * 4 + 3] != 0; Vlc t = SPEC[tab]; int mask = (1 << bits) - 1;
        int end = Math.min(at + num, out.length - group * coeffs);
        for (int pos = at; pos < end; ) {
            if (group == 1 || b.bit() != 0) {
                for (int j = 0; j < group; j++) {
                    int val = b.vlc(t);
                    for (int i = 0; i < coeffs; i++) {
                        int cf = val & mask;
                        if (signed) cf = signExtend(cf, bits); else if (cf != 0 && b.bit() != 0) cf = -cf;
                        out[pos++] = cf; val >>>= bits;
                    }
                }
            } else pos += group * coeffs;
        }
    }

    // gain control
    private static int gainData(Bits b, Unit u, int chNum) {
        Channel c = u.ch[chNum];
        for (int i = 0; i < 16; i++) c.gain[i].clear();
        if (b.bit() == 0) { c.numGainSubbands = 0; return 0; }
        int coded = b.bits(4) + 1;
        c.numGainSubbands = b.bit() != 0 ? b.bits(4) + 1 : coded;
        if (gainPoints(b, u, chNum, coded) < 0 || gainLevels(b, u, chNum, coded) < 0 || gainLocations(b, u, chNum, coded) < 0) return -1;
        for (int sb = coded; sb < c.numGainSubbands; sb++) c.gain[sb].set(c.gain[sb - 1]);
        return 0;
    }
    private static int gainPoints(Bits b, Unit u, int chNum, int coded) {
        Gain[] g = u.ch[chNum].gain, ref = u.ch[0].gain;
        switch (b.bits(2)) {
            case 0: for (int i = 0; i < coded; i++) g[i].numPoints = b.bits(3); break;
            case 1: for (int i = 0; i < coded; i++) g[i].numPoints = b.vlc(GAIN[0]); break;
            case 2:
                if (chNum != 0) for (int i = 0; i < coded; i++) g[i].numPoints = (ref[i].numPoints + b.vlc(GAIN[1])) & 7;
                else {
                    g[0].numPoints = b.vlc(GAIN[0]);
                    for (int i = 1; i < coded; i++) g[i].numPoints = (g[i - 1].numPoints + b.vlc(GAIN[1])) & 7;
                }
                break;
            default:
                if (chNum != 0) for (int i = 0; i < coded; i++) g[i].numPoints = ref[i].numPoints;
                else {
                    int deltaBits = b.bits(2), min = b.bits(3);
                    for (int i = 0; i < coded; i++) { g[i].numPoints = min + b.bits(deltaBits); if (g[i].numPoints > 7) return -1; }
                }
        }
        for (int i = 0; i < coded; i++) if (g[i].numPoints < 0 || g[i].numPoints > 7) return -1;
        return 0;
    }
    private static void levelsCloneMaster(Gain d, Gain ref) { for (int i = 0; i < d.numPoints; i++) d.lev[i] = i >= ref.numPoints ? 7 : ref.lev[i]; }
    private static void levelsDeltaPrevious(Bits b, Gain d) {
        if (d.numPoints > 0) d.lev[0] = b.vlc(GAIN[2]);
        for (int i = 1; i < d.numPoints; i++) d.lev[i] = (d.lev[i - 1] + b.vlc(GAIN[3])) & 15;
    }
    private static int gainLevels(Bits b, Unit u, int chNum, int coded) {
        Gain[] g = u.ch[chNum].gain, ref = u.ch[0].gain;
        switch (b.bits(2)) {
            case 0: for (int sb = 0; sb < coded; sb++) for (int i = 0; i < g[sb].numPoints; i++) g[sb].lev[i] = b.bits(4); break;
            case 1:
                if (chNum != 0) {
                    for (int sb = 0; sb < coded; sb++) for (int i = 0; i < g[sb].numPoints; i++) {
                        int d = b.vlc(GAIN[5]); int pred = i >= ref[sb].numPoints ? 7 : ref[sb].lev[i];
                        g[sb].lev[i] = (pred + d) & 15;
                    }
                } else for (int sb = 0; sb < coded; sb++) levelsDeltaPrevious(b, g[sb]);
                break;
            case 2:
                if (chNum != 0) {
                    for (int sb = 0; sb < coded; sb++) if (g[sb].numPoints > 0) {
                        if (b.bit() != 0) levelsDeltaPrevious(b, g[sb]); else levelsCloneMaster(g[sb], ref[sb]);
                    }
                } else {
                    if (g[0].numPoints > 0) levelsDeltaPrevious(b, g[0]);
                    for (int sb = 1; sb < coded; sb++) for (int i = 0; i < g[sb].numPoints; i++) {
                        int d = b.vlc(GAIN[4]); int pred = i >= g[sb - 1].numPoints ? 7 : g[sb - 1].lev[i];
                        g[sb].lev[i] = (pred + d) & 15;
                    }
                }
                break;
            default:
                if (chNum != 0) for (int sb = 0; sb < coded; sb++) levelsCloneMaster(g[sb], ref[sb]);
                else {
                    int deltaBits = b.bits(2), min = b.bits(4);
                    for (int sb = 0; sb < coded; sb++) for (int i = 0; i < g[sb].numPoints; i++) {
                        g[sb].lev[i] = min + b.bits(deltaBits); if (g[sb].lev[i] > 15) return -1;
                    }
                }
        }
        return 0;
    }
    /** Direct location coding: 5 bits, or a shorter increment once the previous location is past the middle. */
    private static void locDirect(Bits b, Gain d, int pos) {
        if (pos == 0 || d.loc[pos - 1] < 15) d.loc[pos] = b.bits(5);
        else if (d.loc[pos - 1] >= 30) d.loc[pos] = 31;
        else d.loc[pos] = d.loc[pos - 1] + b.bits(log2(30 - d.loc[pos - 1]) + 1) + 1;
    }
    private static void locDeltaPrevious(Bits b, Gain d) {
        if (d.numPoints <= 0) return;
        d.loc[0] = b.bits(5);
        for (int i = 1; i < d.numPoints; i++) d.loc[i] = d.loc[i - 1] + b.vlc(d.lev[i] <= d.lev[i - 1] ? GAIN[7] : GAIN[9]);
    }
    private static int gainLocations(Bits b, Unit u, int chNum, int coded) {
        Gain[] g = u.ch[chNum].gain, rg = u.ch[0].gain;
        switch (b.bits(2)) {
            case 0: for (int sb = 0; sb < coded; sb++) for (int i = 0; i < g[sb].numPoints; i++) locDirect(b, g[sb], i); break;
            case 1:
                if (chNum != 0) {
                    for (int sb = 0; sb < coded; sb++) {
                        Gain d = g[sb], ref = rg[sb]; if (d.numPoints <= 0) continue;
                        d.loc[0] = ((ref.numPoints > 0 ? ref.loc[0] : 0) + b.vlc(GAIN[10])) & 31;
                        for (int i = 1; i < d.numPoints; i++) {
                            boolean beyond = i >= ref.numPoints;
                            if (d.lev[i] > d.lev[i - 1]) { // rising
                                if (beyond) d.loc[i] = d.loc[i - 1] + b.vlc(GAIN[9]);
                                else if (b.bit() != 0) locDirect(b, d, i);
                                else d.loc[i] = ref.loc[i];
                            } else {
                                int delta = b.vlc(beyond ? GAIN[7] : GAIN[10]);
                                d.loc[i] = beyond ? d.loc[i - 1] + delta : (ref.loc[i] + delta) & 31;
                            }
                        }
                    }
                } else for (int sb = 0; sb < coded; sb++) locDeltaPrevious(b, g[sb]);
                break;
            case 2:
                if (chNum != 0) {
                    for (int sb = 0; sb < coded; sb++) {
                        Gain d = g[sb], ref = rg[sb]; if (d.numPoints <= 0) continue;
                        if (d.numPoints > ref.numPoints || b.bit() != 0) locDeltaPrevious(b, d);
                        else System.arraycopy(ref.loc, 0, d.loc, 0, d.numPoints);
                    }
                } else {
                    for (int i = 0; i < g[0].numPoints; i++) locDirect(b, g[0], i);
                    for (int sb = 1; sb < coded; sb++) {
                        Gain d = g[sb], p = g[sb - 1]; if (d.numPoints <= 0) continue;
                        d.loc[0] = ((p.numPoints > 0 ? p.loc[0] : 0) + b.vlc(GAIN[6])) & 31;
                        for (int i = 1; i < d.numPoints; i++) {
                            boolean beyond = i >= p.numPoints;
                            int delta = b.vlc(GAIN[(d.lev[i] > d.lev[i - 1] ? 2 : 0) + (beyond ? 1 : 0) + 6]);
                            d.loc[i] = beyond ? d.loc[i - 1] + delta : (p.loc[i] + delta) & 31;
                        }
                    }
                }
                break;
            default:
                if (chNum != 0) {
                    for (int sb = 0; sb < coded; sb++) for (int i = 0; i < g[sb].numPoints; i++) {
                        if (i >= rg[sb].numPoints) locDirect(b, g[sb], i); else g[sb].loc[i] = rg[sb].loc[i];
                    }
                } else {
                    int deltaBits = b.bits(2) + 1, min = b.bits(5);
                    for (int sb = 0; sb < coded; sb++) for (int i = 0; i < g[sb].numPoints; i++) g[sb].loc[i] = min + i + b.bits(deltaBits);
                }
        }
        for (int sb = 0; sb < coded; sb++) {
            Gain d = g[sb];
            for (int i = 0; i < d.numPoints; i++) if (d.loc[i] < 0 || d.loc[i] > 31 || (i > 0 && d.loc[i] <= d.loc[i - 1])) return -1;
        }
        return 0;
    }

    // tonal components
    private int tones(Bits b, Unit u, int n) {
        for (int c = 0; c < n; c++) for (int i = 0; i < 16; i++) u.ch[c].tones[i].clear();
        Synth w = u.waves;
        w.tonesPresent = b.bit() != 0;
        if (!w.tonesPresent) return 0;
        java.util.Arrays.fill(w.freq, 0); java.util.Arrays.fill(w.ampSf, 0); java.util.Arrays.fill(w.ampIndex, 0); java.util.Arrays.fill(w.phase, 0);
        w.amplitudeMode = b.bit();
        if (w.amplitudeMode == 0) return -1; // not used by known encoders
        w.numToneBands = b.vlc(TONE[0]) + 1;
        if (n == 2) { flags(b, w.toneSharing, w.numToneBands); flags(b, w.toneMaster, w.numToneBands); flags(b, w.invertPhase, w.numToneBands); }
        w.tonesIndex = 0;
        for (int c = 0; c < n; c++) {
            for (int i = 0; i < w.numToneBands; i++) bandHasTones[i] = c == 0 ? 1 : 1 - w.toneSharing[i];
            toneEnvelope(b, u, c);
            if (toneCount(b, u, c) < 0) return -1;
            toneFrequencies(b, u, c);
            toneAmplitudes(b, u, c);
            Waves[] d = u.ch[c].tones;
            for (int sb = 0; sb < w.numToneBands; sb++) if (bandHasTones[sb] != 0)
                for (int i = 0; i < d[sb].numWavs; i++) w.phase[d[sb].startIndex + i] = b.bits(5);
        }
        if (n == 2) for (int i = 0; i < w.numToneBands; i++) {
            if (w.toneSharing[i] != 0) u.ch[1].tones[i].set(u.ch[0].tones[i]);
            if (w.toneMaster[i] != 0) { Waves t = u.ch[0].tones[i]; u.ch[0].tones[i] = u.ch[1].tones[i]; u.ch[1].tones[i] = t; }
        }
        return 0;
    }
    private void toneEnvelope(Bits b, Unit u, int c) {
        Waves[] d = u.ch[c].tones, ref = u.ch[0].tones; int bands = u.waves.numToneBands;
        if (c == 0 || b.bit() == 0) {
            for (int sb = 0; sb < bands; sb++) {
                if (bandHasTones[sb] == 0) continue;
                Envelope e = d[sb].pend;
                e.hasStart = b.bit() != 0; e.start = e.hasStart ? b.bits(5) : -1;
                e.hasStop = b.bit() != 0; e.stop = e.hasStop ? b.bits(5) : 32;
            }
        } else for (int sb = 0; sb < bands; sb++) if (bandHasTones[sb] != 0) d[sb].pend.set(ref[sb].pend);
    }
    private int toneCount(Bits b, Unit u, int c) {
        Waves[] d = u.ch[c].tones, ref = u.ch[0].tones; Synth w = u.waves; int bands = w.numToneBands;
        switch (b.bits(c + 1)) {
            case 0: for (int sb = 0; sb < bands; sb++) if (bandHasTones[sb] != 0) d[sb].numWavs = b.bits(4); break;
            case 1: for (int sb = 0; sb < bands; sb++) if (bandHasTones[sb] != 0) d[sb].numWavs = b.vlc(TONE[1]); break;
            case 2: for (int sb = 0; sb < bands; sb++) if (bandHasTones[sb] != 0) d[sb].numWavs = (ref[sb].numWavs + signExtend(b.vlc(TONE[2]), 3)) & 15; break;
            default: for (int sb = 0; sb < bands; sb++) if (bandHasTones[sb] != 0) d[sb].numWavs = ref[sb].numWavs;
        }
        for (int sb = 0; sb < bands; sb++) if (bandHasTones[sb] != 0) {
            if (d[sb].numWavs < 0 || w.tonesIndex + d[sb].numWavs > 48) return -1;
            d[sb].startIndex = w.tonesIndex; w.tonesIndex += d[sb].numWavs;
        }
        return 0;
    }
    private void toneFrequencies(Bits b, Unit u, int c) {
        Waves[] d = u.ch[c].tones, ref = u.ch[0].tones; Synth w = u.waves; int[] f = w.freq; int bands = w.numToneBands;
        if (c == 0 || b.bit() == 0) {
            for (int sb = 0; sb < bands; sb++) {
                int nw = d[sb].numWavs, s = d[sb].startIndex; if (bandHasTones[sb] == 0 || nw == 0) continue;
                if (nw > 1 && b.bit() != 0) { // descending: each value bounded by the next one
                    f[s + nw - 1] = b.bits(10);
                    for (int i = nw - 2; i >= 0; i--) f[s + i] = b.bits(log2(f[s + i + 1]) + 1);
                } else {
                    for (int i = 0; i < nw; i++) {
                        if (i == 0 || f[s + i - 1] < 512) f[s + i] = b.bits(10);
                        else { int nb = log2(1023 - f[s + i - 1]) + 1; f[s + i] = b.bits(nb) + 1024 - (1 << nb); }
                    }
                }
            }
        } else {
            for (int sb = 0; sb < bands; sb++) {
                int nw = d[sb].numWavs; if (bandHasTones[sb] == 0 || nw == 0) continue;
                int rs = ref[sb].startIndex, rn = ref[sb].numWavs, s = d[sb].startIndex;
                for (int i = 0; i < nw; i++) {
                    int delta = signExtend(b.vlc(TONE[6]), 8);
                    int pred = i < rn ? f[rs + i] : rn > 0 ? f[rs + rn - 1] : 0;
                    f[s + i] = (pred + delta) & 0x3ff;
                }
            }
        }
    }
    private void toneAmplitudes(Bits b, Unit u, int c) {
        Waves[] d = u.ch[c].tones, ref = u.ch[0].tones; Synth w = u.waves; int bands = w.numToneBands;
        java.util.Arrays.fill(refWaves, 0);
        if (c != 0) {
            for (int sb = 0; sb < bands; sb++) {
                if (bandHasTones[sb] == 0 || d[sb].numWavs == 0) continue;
                for (int j = 0; j < d[sb].numWavs; j++) {
                    int fi = 0, maxDiff = 1024;
                    for (int i = 0; i < ref[sb].numWavs; i++) {
                        int diff = Math.abs(w.freq[d[sb].startIndex + j] - w.freq[ref[sb].startIndex + i]);
                        if (diff < maxDiff) { maxDiff = diff; fi = i; }
                    }
                    refWaves[d[sb].startIndex + j] = maxDiff < 8 ? fi + ref[sb].startIndex : j < ref[sb].numWavs ? j + ref[sb].startIndex : -1;
                }
            }
        }
        switch (b.bits(c + 1)) {
            case 0:
                for (int sb = 0; sb < bands; sb++) {
                    if (bandHasTones[sb] == 0 || d[sb].numWavs == 0) continue;
                    if (w.amplitudeMode != 0) for (int i = 0; i < d[sb].numWavs; i++) w.ampSf[d[sb].startIndex + i] = b.bits(6);
                    else w.ampSf[d[sb].startIndex] = b.bits(6);
                }
                break;
            case 1:
                for (int sb = 0; sb < bands; sb++) {
                    if (bandHasTones[sb] == 0 || d[sb].numWavs == 0) continue;
                    if (w.amplitudeMode != 0) for (int i = 0; i < d[sb].numWavs; i++) w.ampSf[d[sb].startIndex + i] = b.vlc(TONE[3]) + 20;
                    else w.ampSf[d[sb].startIndex] = b.vlc(TONE[4]) + 24;
                }
                break;
            case 2:
                for (int sb = 0; sb < bands; sb++) {
                    if (bandHasTones[sb] == 0 || d[sb].numWavs == 0) continue;
                    for (int i = 0; i < d[sb].numWavs; i++) {
                        int delta = signExtend(b.vlc(TONE[5]), 5), r = refWaves[d[sb].startIndex + i];
                        w.ampSf[d[sb].startIndex + i] = ((r >= 0 ? w.ampSf[r] : 34) + delta) & 63;
                    }
                }
                break;
            default:
                for (int sb = 0; sb < bands; sb++) {
                    if (bandHasTones[sb] == 0) continue;
                    for (int i = 0; i < d[sb].numWavs; i++) { int r = refWaves[d[sb].startIndex + i]; w.ampSf[d[sb].startIndex + i] = r >= 0 ? w.ampSf[r] : 32; }
                }
        }
    }

    // ------------------------------------------------------------------------------------------------ signal
    /** Inverse quantisation, power compensation (noise fill) and stereo swapping into {@link #spec}. */
    private void residual(Unit u, int n) {
        if (u.mute) { for (int c = 0; c < n; c++) java.util.Arrays.fill(spec[c], 0); return; }
        int rng = 0;
        for (int qu = 0; qu < u.usedQuantUnits; qu++) rng += u.ch[0].sfIdx[qu] + u.ch[1].sfIdx[qu];
        for (int sb = 0; sb < u.numCodedSubbands; sb++, rng += 128) rngIndex[sb] = rng & 0x3fc;
        for (int c = 0; c < n; c++) {
            float[] dst = spec[c]; Channel ch = u.ch[c];
            java.util.Arrays.fill(dst, 0);
            for (int qu = 0; qu < u.usedQuantUnits; qu++) {
                if (ch.wordlen[qu] <= 0) continue;
                float q = SF_TAB[ch.sfIdx[qu]] * MANT_TAB[ch.wordlen[qu]];
                for (int i = QU_TO_SPEC_POS[qu], e = QU_TO_SPEC_POS[qu + 1]; i < e; i++) dst[i] = ch.spectrum[i] * q;
            }
            for (int sb = 0; sb < u.numCodedSubbands; sb++) powerCompensation(u, c, dst, rngIndex[sb], sb);
        }
        if (u.type == 1) for (int sb = 0; sb < u.numCodedSubbands; sb++) {
            int at = sb * SB_SAMPLES;
            if (u.swapChannels[sb] != 0) for (int i = 0; i < SB_SAMPLES; i++) { float t = spec[0][at + i]; spec[0][at + i] = spec[1][at + i]; spec[1][at + i] = t; }
            if (u.negateCoeffs[sb] != 0) for (int i = 0; i < SB_SAMPLES; i++) spec[1][at + i] = -spec[1][at + i];
        }
    }
    private void powerCompensation(Unit u, int chIndex, float[] sp, int rng, int sb) {
        int swap = u.type == 1 && u.swapChannels[sb] != 0 ? 1 : 0;
        Channel src = u.ch[chIndex ^ swap], ch = u.ch[chIndex];
        int lev = src.powerLevs[SUBBAND_TO_POWGRP[sb]];
        if (lev == POWER_COMP_OFF) return;
        for (int i = 0; i < SB_SAMPLES; i++, rng++) pwc[i] = NOISE_TAB[rng & 0x3ff];
        Gain g1 = src.gain[sb], g2 = src.gainPrev[sb];
        int gainLev = g1.numPoints > 0 ? 6 - g1.lev[0] : 0, gcv = 0;
        for (int i = 0; i < g2.numPoints; i++) gcv = Math.max(gcv, gainLev - (g2.lev[i] - 6));
        for (int i = 0; i < g1.numPoints; i++) gcv = Math.max(gcv, 6 - g1.lev[i]);
        float grpLev = PWC_LEVS[lev] / (1 << gcv);
        for (int qu = SUBBAND_TO_QU[sb] + (sb == 0 ? 2 : 0); qu < SUBBAND_TO_QU[sb + 1]; qu++) {
            if (ch.wordlen[qu] <= 0) continue;
            float quLev = SF_TAB[ch.sfIdx[qu]] * MANT_TAB[ch.wordlen[qu]] / (1 << ch.wordlen[qu]) * grpLev;
            for (int i = QU_TO_SPEC_POS[qu], e = QU_TO_SPEC_POS[qu + 1], k = 0; i < e; i++, k++) sp[i] += pwc[k] * quLev;
        }
    }

    private void reconstruct(Unit u, int n) {
        for (int c = 0; c < n; c++) {
            Channel ch = u.ch[c]; float[] t = time[c], s = spec[c];
            for (int sb = 0; sb < u.numSubbands; sb++) {
                imdct(s, sb * SB_SAMPLES, (ch.wndPrev[sb] << 1) + ch.wnd[sb], (sb & 1) != 0);
                gainCompensation(ch.prev, sb * SB_SAMPLES, ch.gainPrev[sb], ch.gain[sb], t);
            }
            java.util.Arrays.fill(ch.prev, u.numSubbands * SB_SAMPLES, 2048, 0);
            java.util.Arrays.fill(t, u.numSubbands * SB_SAMPLES, 2048, 0);
            if (u.waves.tonesPresent || u.wavesPrev.tonesPresent)
                for (int sb = 0; sb < u.numSubbands; sb++)
                    if (ch.tones[sb].numWavs != 0 || ch.tonesPrev[sb].numWavs != 0) generateTones(u, c, sb, t, sb * SB_SAMPLES);
            pqf(ch, t);
        }
        for (int c = 0; c < n; c++) {
            Channel ch = u.ch[c];
            int[] w = ch.wnd; ch.wnd = ch.wndPrev; ch.wndPrev = w;
            Gain[] g = ch.gain; ch.gain = ch.gainPrev; ch.gainPrev = g;
            Waves[] tw = ch.tones; ch.tones = ch.tonesPrev; ch.tonesPrev = tw;
        }
        Synth sy = u.waves; u.waves = u.wavesPrev; u.wavesPrev = sy;
    }

    /** 256-point IMDCT of 128 coefficients (negated), windowed; odd subbands are spectrally reversed. Result in {@link #imdct}. */
    private void imdct(float[] in, int at, int windowId, boolean reverse) {
        dct4(in, at, reverse);
        float[] u = dctOut, o = imdct;
        // y = -IMDCT: y[n] = -u[n+64] (n<64), u[191-n] (64<=n<192), u[n-192] (n>=192)
        for (int n = 0; n < 64; n++) o[n] = -u[n + 64];
        for (int n = 64; n < 192; n++) o[n] = u[191 - n];
        for (int n = 192; n < 256; n++) o[n] = u[n - 192];
        if ((windowId & 2) != 0) { for (int i = 0; i < 32; i++) o[i] = 0; for (int i = 0; i < 64; i++) o[32 + i] *= SINE64[i]; }
        else for (int i = 0; i < 128; i++) o[i] *= SINE128[i];
        if ((windowId & 1) != 0) { for (int i = 0; i < 64; i++) o[160 + i] *= SINE64[63 - i]; for (int i = 224; i < 256; i++) o[i] = 0; }
        else for (int i = 0; i < 128; i++) o[128 + i] *= SINE128[127 - i];
    }

    // DCT-IV of size 128 through a 64-point complex FFT: u[2n] = Re W[n], u[127-2n] = -Im W[n],
    // W[n] = e^{-i pi (n + 1/4)/128} * FFT64( (x[2j] + i x[127-2j]) e^{-i pi j/128} )[n]
    private static final float[] PRE_C = new float[64], PRE_S = new float[64], POST_C = new float[64], POST_S = new float[64];
    private static final float[] TW_C = new float[32], TW_S = new float[32];
    private static final int[] REV = new int[64];
    static {
        for (int j = 0; j < 64; j++) {
            PRE_C[j] = (float)Math.cos(Math.PI * j / 128); PRE_S[j] = (float)-Math.sin(Math.PI * j / 128);
            POST_C[j] = (float)Math.cos(Math.PI * (j + 0.25) / 128); POST_S[j] = (float)-Math.sin(Math.PI * (j + 0.25) / 128);
            REV[j] = Integer.reverse(j) >>> 26;
        }
        for (int k = 0; k < 32; k++) { TW_C[k] = (float)Math.cos(2 * Math.PI * k / 64); TW_S[k] = (float)-Math.sin(2 * Math.PI * k / 64); }
    }
    /** Test hook: largest difference between the fast DCT-IV and its direct definition for a random input. */
    public static double dct4SelfTest() {
        Atrac3plus a = new Atrac3plus(1); java.util.Random r = new java.util.Random(1); float[] x = new float[128];
        for (int i = 0; i < 128; i++) x[i] = r.nextFloat() * 2 - 1;
        a.dct4(x, 0, false); double worst = 0;
        for (int n = 0; n < 128; n++) {
            double s = 0; for (int k = 0; k < 128; k++) s += x[k] * Math.cos(Math.PI / 128 * (n + 0.5) * (k + 0.5));
            worst = Math.max(worst, Math.abs(s - a.dctOut[n]));
        }
        return worst;
    }
    /** Writes DCT-IV(in[at..at+128)) (optionally of the reversed input) to {@link #dctOut}. */
    void dct4(float[] in, int at, boolean reverse) {
        float[] re = fftRe, im = fftIm;
        for (int j = 0; j < 64; j++) {
            float a, b;
            if (!reverse) { a = in[at + 2 * j]; b = in[at + 127 - 2 * j]; }
            else { a = in[at + 127 - 2 * j]; b = in[at + 2 * j]; }
            int r = REV[j];
            re[r] = a * PRE_C[j] - b * PRE_S[j]; im[r] = a * PRE_S[j] + b * PRE_C[j];
        }
        for (int size = 2; size <= 64; size <<= 1) {
            int half = size >> 1, step = 64 / size;
            for (int start = 0; start < 64; start += size)
                for (int k = 0; k < half; k++) {
                    int p = start + k, q = p + half; float wc = TW_C[k * step], ws = TW_S[k * step];
                    float tr = re[q] * wc - im[q] * ws, ti = re[q] * ws + im[q] * wc;
                    re[q] = re[p] - tr; im[q] = im[p] - ti; re[p] += tr; im[p] += ti;
                }
        }
        float[] o = dctOut;
        for (int n = 0; n < 64; n++) {
            float wr = re[n] * POST_C[n] - im[n] * POST_S[n], wi = re[n] * POST_S[n] + im[n] * POST_C[n];
            o[2 * n] = wr; o[127 - 2 * n] = -wi;
        }
    }

    private static final float[] GAIN_LEV = new float[16], GAIN_INC = new float[31];
    static {
        for (int i = 0; i < 16; i++) GAIN_LEV[i] = (float)Math.pow(2.0, 6 - i);
        for (int i = -15; i < 16; i++) GAIN_INC[i + 15] = (float)Math.pow(2.0, -1.0f / 4 * i);
    }
    /** Gain compensation of one subband and overlap-add with the previous frame (4-sample location units). */
    private void gainCompensation(float[] prev, int at, Gain now, Gain next, float[] out) {
        float[] in = imdct;
        float scale = next.numPoints > 0 ? GAIN_LEV[next.lev[0]] : 1.0f;
        int pos = 0;
        if (now.numPoints > 0) {
            for (int i = 0; i < now.numPoints; i++) {
                int last = now.loc[i] << 2;
                float lev = GAIN_LEV[now.lev[i]];
                float inc = GAIN_INC[(i + 1 < now.numPoints ? now.lev[i + 1] : 6) - now.lev[i] + 15];
                for (; pos < last; pos++) out[at + pos] = (in[pos] * scale + prev[at + pos]) * lev;
                for (; pos < last + 4; pos++) { out[at + pos] = (in[pos] * scale + prev[at + pos]) * lev; lev *= inc; }
            }
        }
        for (; pos < 128; pos++) out[at + pos] = in[pos] * scale + prev[at + pos];
        System.arraycopy(in, 128, prev, at, 128);
    }

    private void generateTones(Unit u, int c, int sb, float[] out, int at) {
        Waves now = u.ch[c].tonesPrev[sb], next = u.ch[c].tones[sb];
        java.util.Arrays.fill(wav1, 0); java.util.Arrays.fill(wav2, 0);
        Envelope ne = next.curr;
        if (next.pend.hasStart && next.pend.start < next.pend.stop) { ne.hasStart = true; ne.start = next.pend.start + 32; }
        else if (now.pend.hasStart) { ne.hasStart = true; ne.start = now.pend.start; }
        else { ne.hasStart = false; ne.start = 0; }
        if (now.pend.hasStop && now.pend.stop >= ne.start) { ne.hasStop = true; ne.stop = now.pend.stop; }
        else if (next.pend.hasStop) { ne.hasStop = true; ne.stop = next.pend.stop + 32; }
        else { ne.hasStop = false; ne.stop = 64; }
        boolean reg1 = now.curr.stop >= 32, reg2 = ne.start < 32;
        if (now.numWavs != 0 && reg1) synth(u.wavesPrev, now, now.curr, (u.wavesPrev.invertPhase[sb] & c) != 0, 128, wav1);
        if (next.numWavs != 0 && reg2) synth(u.waves, next, ne, (u.waves.invertPhase[sb] & c) != 0, 0, wav2);
        if (now.numWavs != 0 && next.numWavs != 0 && reg1 && reg2) {
            for (int i = 0; i < 128; i++) { wav1[i] *= HANN[128 + i]; wav2[i] *= HANN[i]; }
        } else {
            if (now.numWavs != 0 && !now.curr.hasStop) for (int i = 0; i < 128; i++) wav1[i] *= HANN[128 + i];
            if (next.numWavs != 0 && !ne.hasStart) for (int i = 0; i < 128; i++) wav2[i] *= HANN[i];
        }
        for (int i = 0; i < 128; i++) out[at + i] += wav1[i] + wav2[i];
    }
    private static void synth(Synth p, Waves w, Envelope env, boolean invert, int regOffset, float[] out) {
        for (int k = 0; k < w.numWavs; k++) {
            int idx = w.startIndex + k;
            double amp = AMP_SF[p.ampSf[idx]] * (p.amplitudeMode == 0 ? (p.ampIndex[idx] + 1) / 15.13f : 1.0f);
            int inc = p.freq[idx], pos = ((p.phase[idx] & 31) << 6) - (regOffset ^ 128) * inc & 2047;
            for (int i = 0; i < 128; i++) { out[i] = (float)(out[i] + SINE_TABLE[pos] * amp); pos = (pos + inc) & 2047; }
        }
        if (invert) for (int i = 0; i < 128; i++) out[i] = -out[i];
        if (env.hasStart) {
            int pos = (env.start << 2) - regOffset;
            if (pos > 0 && pos <= 128) {
                for (int i = 0; i < pos; i++) out[i] = 0;
                if (!env.hasStop || env.start != env.stop) { // pos + 3 may reach index 131 in malformed streams
                    if (pos < 128) out[pos] *= HANN[0]; if (pos + 1 < 128) out[pos + 1] *= HANN[32];
                    if (pos + 2 < 128) out[pos + 2] *= HANN[64]; if (pos + 3 < 128) out[pos + 3] *= HANN[96];
                }
            }
        }
        if (env.hasStop) {
            int pos = ((env.stop + 1) << 2) - regOffset;
            if (pos > 0 && pos <= 128) {
                if (pos >= 4) { out[pos - 4] *= HANN[96]; out[pos - 3] *= HANN[64]; out[pos - 2] *= HANN[32]; out[pos - 1] *= HANN[0]; }
                for (int i = pos; i < 128; i++) out[i] = 0;
            }
        }
    }

    /** 16-band inverse PQF (384-tap prototype): 128 samples per band in, 2048 samples out, into {@link #pcm}-sized {@code in}. */
    private void pqf(Channel ch, float[] io) {
        float[] out = pqfOut;
        float[] h = ch.pqf, x = pqfIn;
        int pos = ch.pqfPos;
        for (int s = 0; s < SB_SAMPLES; s++) {
            for (int sb = 0; sb < 16; sb++) x[sb] = io[sb * SB_SAMPLES + s];
            int base = pos * 16;
            for (int r = 0; r < 16; r++) {
                float acc = 0; int m = r * 16;
                for (int k = 0; k < 16; k++) acc += PQF_MATRIX[m + k] * x[k];
                h[base + r] = acc;
            }
            int now = pos, next = now + 1 == 24 ? 0 : now + 1, o = s * 16;
            for (int i = 0; i < 16; i++) out[o + i] = 0;
            for (int t = 0; t < 12; t++) {
                int b1 = now * 16, b2 = next * 16 + 8, c = t * 16;
                for (int i = 0; i < 8; i++) {
                    out[o + i] += h[b1 + i] * FIR1[c + i] + h[b2 + i] * FIR2[c + i];
                    out[o + i + 8] += h[b1 + 7 - i] * FIR1[c + i + 8] + h[b2 + 7 - i] * FIR2[c + i + 8];
                }
                now = next + 1 == 24 ? 0 : next + 1; next = now + 1 == 24 ? 0 : now + 1;
            }
            pos = pos == 0 ? 23 : pos - 1;
        }
        ch.pqfPos = pos;
        System.arraycopy(out, 0, io, 0, 2048);
    }
    private final float[] pqfOut = new float[2048];
}
