package org.pxp2.core;

/**
 * Original PXP2 software mixer for one sceSasCore instance (the game-side sound mixer that the firmware's
 * sc_sascore runs on the Media Engine). Up to 32 voices, each playing PSP VAG ADPCM, 16-bit PCM or noise at its own
 * pitch, shaped by an SPU-style ADSR envelope and mixed to 16-bit stereo one grain at a time, with a per-voice
 * effect send into a small reverb.
 *
 * Formats and conventions used (public format knowledge):
 * - VAG: 16-byte blocks: byte 0 = predictor (high nibble) | shift (low nibble), byte 1 = flags
 *   (bit 0 loop end, bit 1 repeat, bit 2 loop start; 7 = end marker), then 28 4-bit samples, low nibble first.
 *   s = (nibble << 12 >> shift) + ((s1 * f0 + s2 * f1 + 32) >> 6) with the five standard (f0, f1) pairs.
 * - Pitch: 0x1000 = 1.0 (one source sample per 44.1 kHz output sample).
 * - Volumes: 0x1000 = 1.0. Envelope height: 0 .. 0x40000000.
 * - Envelope rates: linear curves add or subtract the rate once per sample; exponential decrease subtracts
 *   height * rate / 2^30. SetSimpleADSR's SPU bitfields are converted to the same units (see {@link #spuRate}).
 *
 * Rendering is deterministic and allocates nothing per grain.
 */
final class SasCore {
    static final int MAX_VOICES = 32, MAX_GRAIN = 0x800, ENVELOPE_MAX = 0x40000000;
    /** Envelope curve modes (SetADSRmode). */
    static final int LINEAR_INCREASE = 0, LINEAR_DECREASE = 1, LINEAR_BENT = 2, EXPONENT_DECREASE = 3, EXPONENT_INCREASE = 4, DIRECT = 5;
    static final int ATTACK = 0, DECAY = 1, SUSTAIN = 2, RELEASE = 3, OFF = 4;
    static final int TYPE_NONE = 0, TYPE_VAG = 1, TYPE_PCM = 2, TYPE_NOISE = 3;
    private static final int[][] VAG_FILTER = {{0, 0}, {60, 0}, {115, -52}, {98, -55}, {122, -60}};

    final Memory mem;
    int grain, maxVoices, outputMode, sampleRate;
    final Voice[] voices = new Voice[MAX_VOICES];
    /** Reverb: type (0 off .. 9), RevParam delay/feedback, RevEVOL volumes, RevVON dry/wet switches. */
    int revType, revDelay, revFeedback, revVolL, revVolR, dryOn = 1, wetOn;
    private final int[] dryL = new int[MAX_GRAIN], dryR = new int[MAX_GRAIN], sendL = new int[MAX_GRAIN], sendR = new int[MAX_GRAIN];
    private final Reverb reverb = new Reverb();

    SasCore(Memory mem, int grain, int maxVoices, int outputMode, int sampleRate) {
        this.mem = mem; this.grain = grain; this.maxVoices = maxVoices; this.outputMode = outputMode; this.sampleRate = sampleRate;
        for (int i = 0; i < MAX_VOICES; i++) voices[i] = new Voice();
    }

    /** One voice: source, pitch, volumes, envelope and decoder state. */
    final class Voice {
        int type, addr, size, loop;          // VAG: bytes and loop switch; PCM: samples and loop position (-1 = none)
        int noiseFreq;
        int pitch = 0x1000, volL = 0x1000, volR = 0x1000, effL, effR;
        boolean playing, paused;
        // Envelope
        int phase = OFF, height;
        final int[] rate = {0x7fffffff, 0, 0, 0}, mode = {LINEAR_INCREASE, EXPONENT_DECREASE, LINEAR_INCREASE, EXPONENT_DECREASE};
        int sustainLevel = ENVELOPE_MAX;
        // Source position: previous/current sample for linear interpolation, 12-bit fraction.
        int frac, prev, cur;
        // VAG decoder
        int block, loopBlock, s1, s2, bufPos, bufLen; boolean lastBlock, dataEnd;
        final int[] buf = new int[28];
        // PCM
        int pcmPos;
        // Noise LFSR
        int noiseLevel = 1, noiseTimer;

        void keyOn() {
            playing = true; phase = ATTACK; height = 0; frac = 0; prev = 0; restart();
            cur = next();
        }
        void keyOff() { if (playing && phase != OFF) phase = RELEASE; }
        void stop() { playing = false; phase = OFF; height = 0; }
        void restart() {
            block = addr; loopBlock = addr; s1 = s2 = 0; bufPos = bufLen = 0; lastBlock = dataEnd = false; pcmPos = 0;
            noiseLevel = 1; noiseTimer = 0;
        }

        /** Next source sample at the source rate; sets dataEnd when a one-shot sample has run out. */
        int next() {
            switch (type) {
                case TYPE_VAG: {
                    if (bufPos >= bufLen && !decodeBlock()) { dataEnd = true; return 0; }
                    return buf[bufPos++];
                }
                case TYPE_PCM: {
                    if (pcmPos >= size) {
                        if (loop >= 0 && loop < size) pcmPos = loop; else { dataEnd = true; return 0; }
                    }
                    int a = addr + pcmPos++ * 2;
                    return (short)mem.read16(a);
                }
                default: return 0;
            }
        }

        /**
         * Decodes the next 16-byte VAG block into buf; false at the end of a one-shot sample. The data's own flags
         * decide: a block with flag bit 0 ends the sample, unless bit 1 is also set, which continues at the last
         * loop-start block (bit 2). The end marker (flags 7) ends it too. Only data that runs past the SetVoice size
         * without an end flag uses the SetVoice loop switch (DBZ passes loop 1 for one-shot effects whose data ends
         * with flags 1 then 7; they must play once).
         */
        private boolean decodeBlock() {
            for (int tries = 0; tries < 3; tries++) {
                if (lastBlock) return false;
                if (block - addr < 0 || block - addr >= size || !mem.valid(block, 16)) {
                    if (loop == 0 || block == loopBlock) return false;
                    block = loopBlock; continue;
                }
                int header = mem.read8(block), flags = mem.read8(block + 1);
                if (flags == 7) return false; // end marker: nothing to decode
                if ((flags & 4) != 0) loopBlock = block;
                int shift = header & 15, predictor = header >> 4;
                if (shift > 12) shift = 9;
                if (predictor > 4) predictor = 0;
                int f0 = VAG_FILTER[predictor][0], f1 = VAG_FILTER[predictor][1];
                for (int i = 0; i < 28; i++) {
                    int b = mem.read8(block + 2 + (i >> 1));
                    int nibble = (i & 1) == 0 ? b & 15 : b >> 4;
                    int s = ((nibble << 28) >> 16) >> shift; // sign-extend the nibble into bits 15..12
                    s += (s1 * f0 + s2 * f1 + 32) >> 6;
                    if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
                    s2 = s1; s1 = s; buf[i] = s;
                }
                bufPos = 0; bufLen = 28; block += 16;
                if ((flags & 1) != 0) { if ((flags & 2) != 0) block = loopBlock; else lastBlock = true; }
                return true;
            }
            return false;
        }

        /** Advances the envelope by one sample; ends the voice when the release reaches zero. */
        void envelope() {
            switch (phase) {
                case ATTACK:
                    height = step(height, rate[ATTACK], mode[ATTACK]);
                    if (height >= ENVELOPE_MAX || mode[ATTACK] == DIRECT) { if (height >= ENVELOPE_MAX) height = ENVELOPE_MAX; phase = DECAY; }
                    break;
                case DECAY:
                    height = step(height, rate[DECAY], mode[DECAY]);
                    if (height <= sustainLevel || mode[DECAY] == DIRECT) { if (height < 0) height = 0; phase = SUSTAIN; }
                    break;
                case SUSTAIN:
                    height = step(height, rate[SUSTAIN], mode[SUSTAIN]);
                    break;
                case RELEASE:
                    height = step(height, rate[RELEASE], mode[RELEASE]);
                    if (height <= 0) stop();
                    break;
                default: break;
            }
        }

        /** Renders count samples of this voice and adds them to the dry and send buses. */
        void render(int count) {
            boolean noise = type == TYPE_NOISE;
            int noiseStep = 4 + (noiseFreq & 3), noiseShift = (noiseFreq >> 2) & 15;
            for (int i = 0; i < count && playing; i++) {
                int s; boolean finished = false;
                if (noise) {
                    // SPU-style noise: a 16-bit LFSR clocked at a rate chosen by freq (0..63).
                    noiseTimer -= noiseStep;
                    int parity = ((noiseLevel >> 15) ^ (noiseLevel >> 12) ^ (noiseLevel >> 11) ^ (noiseLevel >> 10) ^ 1) & 1;
                    if (noiseTimer < 0) { noiseLevel = (noiseLevel << 1 | parity) & 0xffff; noiseTimer += 0x20000 >> noiseShift; if (noiseTimer < 0) noiseTimer += 0x20000 >> noiseShift; }
                    s = (short)noiseLevel;
                } else {
                    s = prev + (((cur - prev) * frac) >> 12);
                    frac += pitch;
                    while (frac >= 0x1000) {
                        if (dataEnd) { finished = true; break; } // interpolated from the last sample down to 0: done
                        frac -= 0x1000; prev = cur; cur = next();
                    }
                }
                envelope();
                int v = (int)(((long)s * (height >> 15)) >> 15);
                dryL[i] += (v * volL) >> 12; dryR[i] += (v * volR) >> 12;
                sendL[i] += (v * effL) >> 12; sendR[i] += (v * effR) >> 12;
                if (finished) stop(); // a one-shot source has played out: the voice ends (end flag set)
            }
        }
    }

    /** Envelope curve step. */
    private static int step(int h, int rate, int mode) {
        long x;
        switch (mode) {
            case LINEAR_INCREASE: x = (long)h + rate; break;
            case LINEAR_DECREASE: x = (long)h - rate; break;
            case LINEAR_BENT: x = (long)h + (h < ENVELOPE_MAX / 4 * 3 ? rate : rate >> 2); break;
            case EXPONENT_DECREASE: x = h - (((long)h * rate) >> 30) - (rate != 0 && h > 0 && h < 64 ? 1 : 0); break;
            case EXPONENT_INCREASE: x = (long)h + Math.max(1L, ((long)(ENVELOPE_MAX - h) * rate) >> 30) * (rate != 0 ? 1 : 0); break;
            case DIRECT: x = rate; break;
            default: x = h;
        }
        return x < 0 ? 0 : x > ENVELOPE_MAX ? ENVELOPE_MAX : (int)x;
    }

    /**
     * Converts an SPU envelope rate (7-bit rate field, as in SetSimpleADSR) to a SAS per-sample rate. SPU: every
     * 2^max(0, shift-11) samples the 15-bit level moves by step << max(0, 11-shift), step 7-(r&3) (increase) or
     * 8-(r&3) (decrease), with shift = r >> 2. Rate 0x7f means "never" for increases.
     */
    static int spuRate(int r, boolean decrease) {
        if (r >= 0x7f && !decrease) return 0;
        int shift = r >> 2, step = decrease ? 8 - (r & 3) : 7 - (r & 3);
        long perSample15 = (long)step << Math.max(0, 11 - shift); // in 1/32768 of full scale ...
        long cycles = 1L << Math.max(0, shift - 11);                // ... every `cycles` samples
        long rate = (perSample15 << 15) / cycles;                   // full scale 0x8000 << 15 = 2^30
        return (int)Math.min(rate, 0x7fffffff);
    }

    /** __sceSasSetSimpleADSR: SPU ADSR1/ADSR2 bitfields. */
    void simpleAdsr(Voice v, int env1, int env2) {
        v.mode[ATTACK] = (env1 & 0x8000) != 0 ? LINEAR_BENT : LINEAR_INCREASE;
        v.rate[ATTACK] = spuRate((env1 >> 8) & 0x7f, false);
        v.mode[DECAY] = EXPONENT_DECREASE;
        v.rate[DECAY] = spuRate(((env1 >> 4) & 15) << 2, true);
        v.sustainLevel = Math.min(ENVELOPE_MAX, ((env1 & 15) + 1) << 26);
        boolean sustainDown = (env2 & 0x4000) != 0, sustainExp = (env2 & 0x8000) != 0;
        v.mode[SUSTAIN] = sustainDown ? (sustainExp ? EXPONENT_DECREASE : LINEAR_DECREASE) : (sustainExp ? LINEAR_BENT : LINEAR_INCREASE);
        v.rate[SUSTAIN] = spuRate((env2 >> 6) & 0x7f, sustainDown);
        v.mode[RELEASE] = (env2 & 0x20) != 0 ? EXPONENT_DECREASE : LINEAR_DECREASE;
        v.rate[RELEASE] = spuRate((env2 & 0x1f) << 2, true);
    }

    /** Mixes one grain. Mode 0 writes interleaved 16-bit stereo; other modes write 4 channels (dry L/R, wet L/R). */
    void mix(int out, int inOut, int inVolL, int inVolR) {
        int n = Math.min(grain, MAX_GRAIN);
        java.util.Arrays.fill(dryL, 0, n, 0); java.util.Arrays.fill(dryR, 0, n, 0);
        java.util.Arrays.fill(sendL, 0, n, 0); java.util.Arrays.fill(sendR, 0, n, 0);
        for (int i = 0; i < maxVoices && i < MAX_VOICES; i++) {
            Voice v = voices[i];
            if (v.playing && !v.paused && v.type != TYPE_NONE) v.render(n);
        }
        boolean wet = wetOn != 0 && revType != 0;
        if (wet) reverb.process(this, n); // replaces send[] with the wet signal
        int channels = outputMode == 0 ? 2 : 4;
        for (int i = 0; i < n; i++) {
            int l = dryOn != 0 ? dryL[i] : 0, r = dryOn != 0 ? dryR[i] : 0;
            if (wet && channels == 2) { l += (sendL[i] * revVolL) >> 12; r += (sendR[i] * revVolR) >> 12; }
            int a = out + i * channels * 2;
            if (inOut != 0) { // CoreWithMix: the existing buffer, scaled by the given volumes, plus the SAS output
                l += ((short)mem.read16(a) * inVolL) >> 12; r += ((short)mem.read16(a + 2) * inVolR) >> 12;
            }
            mem.write16(a, clamp(l)); mem.write16(a + 2, clamp(r));
            if (channels == 4) {
                int wl = wet ? (sendL[i] * revVolL) >> 12 : 0, wr = wet ? (sendR[i] * revVolR) >> 12 : 0;
                mem.write16(a + 4, clamp(wl)); mem.write16(a + 6, clamp(wr));
            }
        }
    }
    private static int clamp(int x) { return x > 32767 ? 32767 : x < -32768 ? -32768 : x; }

    /**
     * Small original reverb: four damped feedback combs and two allpasses per channel (Schroeder layout) sized by the
     * reverb type; the echo/delay types (6..8) use a single feedback delay set by RevParam. The state is float so the
     * tail decays to exact silence (integer feedback loops keep a small DC limit cycle). Buffers are allocated once.
     */
    private static final class Reverb {
        private static final int[] COMB = {1116, 1188, 1277, 1356}, ALLPASS = {556, 441};
        /** Room size per type (percent of the base lengths) and comb feedback: 0 off, 1 room, 2..4 studio S/M/L, 5 hall, 6 space echo, 7 echo, 8 delay, 9 pipe. */
        private static final int[] SIZE = {0, 45, 55, 75, 95, 120, 100, 100, 100, 20};
        private static final float[] FEEDBACK = {0, 0.67f, 0.70f, 0.76f, 0.81f, 0.85f, 0, 0, 0, 0.82f};
        private final float[][] comb = new float[8][1700], allpass = new float[4][700];
        private final float[] combLow = new float[8], delayL = new float[1 << 15], delayR = new float[1 << 15];
        private final int[] combPos = new int[8], combLen = new int[8], apPos = new int[4], apLen = new int[4];
        private int delayPos, type = -1;

        void process(SasCore c, int n) {
            if (c.revType != type) setup(c.revType);
            if (type >= 6 && type <= 8) { echo(c, n); return; }
            float fb = FEEDBACK[type];
            for (int i = 0; i < n; i++) {
                c.sendL[i] = round(channel(0, c.sendL[i] * 0.25f, fb));
                c.sendR[i] = round(channel(1, c.sendR[i] * 0.25f, fb));
            }
        }
        private float channel(int ch, float in, float fb) {
            float acc = 0;
            for (int k = 0; k < 4; k++) {
                int j = ch * 4 + k, p = combPos[j]; float y = comb[j][p];
                float lp = y * 0.7f + combLow[j] * 0.3f; combLow[j] = lp > -1e-4f && lp < 1e-4f ? 0 : lp; // damping low-pass
                float w = in + combLow[j] * fb; comb[j][p] = w > -1e-4f && w < 1e-4f ? 0 : w; // flush tiny values (no denormals)
                combPos[j] = p + 1 == combLen[j] ? 0 : p + 1;
                acc += y;
            }
            acc *= 0.25f;
            for (int k = 0; k < 2; k++) {
                int j = ch * 2 + k, p = apPos[j]; float b = allpass[j][p];
                float w = acc + b * 0.5f; allpass[j][p] = w > -1e-4f && w < 1e-4f ? 0 : w; apPos[j] = p + 1 == apLen[j] ? 0 : p + 1; acc = b - acc;
            }
            return acc;
        }
        private void echo(SasCore c, int n) {
            int len = Math.max(1, Math.min(delayL.length - 1, (c.revDelay & 0xff) * 256));
            float fb = Math.max(0, Math.min(127, c.revFeedback)) / 128f;
            for (int i = 0; i < n; i++) {
                int rp = (delayPos - len) & (delayL.length - 1);
                float yl = delayL[rp], yr = delayR[rp];
                float wl = c.sendL[i] + yl * fb, wr = c.sendR[i] + yr * fb; delayL[delayPos] = wl > -1e-4f && wl < 1e-4f ? 0 : wl; delayR[delayPos] = wr > -1e-4f && wr < 1e-4f ? 0 : wr;
                delayPos = (delayPos + 1) & (delayL.length - 1);
                c.sendL[i] = round(yl); c.sendR[i] = round(yr);
            }
        }
        private static int round(float x) { return x >= 32767f ? 32767 : x <= -32768f ? -32768 : Math.round(x); }
        private void setup(int t) {
            type = t < 0 || t > 9 ? 0 : t;
            for (int j = 0; j < 8; j++) { combLen[j] = Math.max(1, Math.min(1700, (COMB[j & 3] + (j >= 4 ? 23 : 0)) * SIZE[type] / 100)); combPos[j] = 0; combLow[j] = 0; java.util.Arrays.fill(comb[j], 0); }
            for (int j = 0; j < 4; j++) { apLen[j] = Math.max(1, Math.min(700, (ALLPASS[j & 1] + (j >= 2 ? 23 : 0)) * Math.max(SIZE[type], 30) / 100)); apPos[j] = 0; java.util.Arrays.fill(allpass[j], 0); }
            java.util.Arrays.fill(delayL, 0); java.util.Arrays.fill(delayR, 0); delayPos = 0;
        }
    }
}
