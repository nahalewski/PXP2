package org.pxp2.core;

import java.io.IOException;

/**
 * A RIFF WAVE file holding ATRAC audio, as used by a game's SND0.AT3 (the XMB preview music) and by games' own .at3 files.
 *
 * <p>Chunks read (PXP2's own reading of the files; see psp.md "SND0.AT3"):
 * <ul>
 * <li>{@code fmt }: format tag 0xFFFE (WAVE_FORMAT_EXTENSIBLE) whose SubFormat GUID is
 *     E923AABF-CB58-4471-A119-FFFA01E4CE62 means ATRAC3plus; the two bytes after the GUID and a 16-bit version word are the
 *     big-endian codec configuration (frame size and channel layout, as in {@link Atrac3plus#frameBytesFromConfig}).
 *     Format tag 0x0270 means ATRAC3. The block align is the frame size.</li>
 * <li>{@code fact}: the total sample count, then the number of leading samples the encoder added (skipped at the start).</li>
 * <li>{@code smpl}: an optional loop (start and end sample, inclusive). These count from the first coded sample, i.e.
 *     they include the encoder delay: SND0 files loop to {@code delay + total - 1}.</li>
 * <li>{@code data}: the frames back to back.</li>
 * </ul>
 */
public final class At3File {
    public static final int ATRAC3PLUS = 1, ATRAC3 = 2, UNKNOWN = 0;
    private static final byte[] ATRAC3PLUS_GUID = {(byte)0xBF, (byte)0xAA, 0x23, (byte)0xE9, 0x58, (byte)0xCB, 0x71, 0x44,
        (byte)0xA1, 0x19, (byte)0xFF, (byte)0xFA, 0x01, (byte)0xE4, (byte)0xCE, 0x62};

    public final byte[] file;
    public final int codec, channels, sampleRate, blockAlign, config16, dataOffset, dataSize;
    /** Total samples per channel from {@code fact} (or computed from the data size), leading samples to skip. */
    public final long totalSamples; public final int delaySamples;
    /** Loop points in samples (inclusive), or -1 when the file has no {@code smpl} loop. */
    public final long loopStart, loopEnd;

    private At3File(byte[] file, int codec, int channels, int rate, int align, int config16, int dataOffset, int dataSize,
                    long total, int delay, long loopStart, long loopEnd) {
        this.file = file; this.codec = codec; this.channels = channels; this.sampleRate = rate; this.blockAlign = align;
        this.config16 = config16; this.dataOffset = dataOffset; this.dataSize = dataSize;
        this.totalSamples = total; this.delaySamples = delay; this.loopStart = loopStart; this.loopEnd = loopEnd;
    }

    public static At3File parse(byte[] b) throws IOException {
        if (b == null || b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) throw new IOException("not a RIFF WAVE file");
        int codec = UNKNOWN, channels = 0, rate = 0, align = 0, config = 0, dataOff = -1, dataSize = 0, delay = 0;
        long total = -1, loopStart = -1, loopEnd = -1;
        int p = 12;
        while (p + 8 <= b.length) {
            int size = le32(b, p + 4), body = p + 8;
            if (size < 0 || body + (long)size > b.length) size = b.length - body; // truncated file: take what is there
            if (tag(b, p, "fmt ") && size >= 16) {
                int format = le16(b, body); channels = le16(b, body + 2); rate = le32(b, body + 4); align = le16(b, body + 12);
                if (format == 0xFFFE && size >= 44 && regionEquals(b, body + 24, ATRAC3PLUS_GUID)) {
                    codec = ATRAC3PLUS; config = (b[body + 42] & 255) << 8 | (b[body + 43] & 255);
                } else if (format == 0x0270) codec = ATRAC3;
            } else if (tag(b, p, "fact") && size >= 4) {
                total = le32(b, body) & 0xFFFFFFFFL;
                if (size >= 8) delay = le32(b, body + 4);
            } else if (tag(b, p, "smpl") && size >= 36) {
                int loops = le32(b, body + 28);
                if (loops > 0 && size >= 36 + 24) { loopStart = le32(b, body + 36 + 8) & 0xFFFFFFFFL; loopEnd = le32(b, body + 36 + 12) & 0xFFFFFFFFL; }
            } else if (tag(b, p, "data")) {
                dataOff = body; dataSize = size;
            }
            p = body + size + (size & 1);
        }
        if (dataOff < 0 || align <= 0 || channels <= 0) throw new IOException("RIFF WAVE without fmt/data");
        int frameSamples = codec == ATRAC3 ? 1024 : Atrac3plus.FRAME_SAMPLES;
        long coded = (long)(dataSize / align) * frameSamples;
        if (delay < 0 || delay >= coded) delay = 0;
        if (total <= 0 || total > coded - delay) total = coded - delay;
        if (loopStart >= 0) { loopStart -= delay; loopEnd -= delay; } // smpl counts the encoder delay (end = delay + total - 1)
        if (loopStart < 0 || loopEnd < loopStart || loopEnd >= total) { loopStart = -1; loopEnd = -1; }
        return new At3File(b, codec, channels, rate, align, config, dataOff, dataSize, total, delay, loopStart, loopEnd);
    }

    public int frames() { return dataSize / blockAlign; }
    public String codecName() { return codec == ATRAC3PLUS ? "ATRAC3plus" : codec == ATRAC3 ? "ATRAC3" : "unknown"; }
    /** True when PXP2 can decode this file (ATRAC3plus; ATRAC3 is not implemented yet). */
    public boolean decodable() { return codec == ATRAC3PLUS && Atrac3plus.frameBytesFromConfig(config16) == blockAlign; }

    /**
     * Endless 16-bit interleaved stereo PCM for preview playback: skips the encoder delay, plays to the loop end (or the
     * last sample) and continues at the loop start (or the first sample). Mono is duplicated to both channels.
     */
    public final class LoopingPcm {
        private final Atrac3plus dec = new Atrac3plus(Atrac3plus.channelsFromConfig(config16));
        private final short[] frame = new short[Atrac3plus.FRAME_SAMPLES * 2];
        private int decodedFrame = -1; // index of the frame now in {@code frame}
        private long pos; // next sample to output, in file sample numbering (0 = first sample after the delay)

        public LoopingPcm() {
            if (!decodable()) throw new IllegalStateException(codecName() + " is not supported");
            seek(0);
        }
        /** Fills {@code out} with up to {@code maxFrames} stereo sample frames; returns the count written (always > 0). */
        public int read(short[] out, int maxFrames) {
            long end = loopEnd >= 0 ? loopEnd + 1 : totalSamples, restart = loopStart >= 0 ? loopStart : 0;
            int n = 0;
            while (n < maxFrames) {
                if (pos >= end) seek(restart);
                long coded = pos + delaySamples; int f = (int)(coded / Atrac3plus.FRAME_SAMPLES), at = (int)(coded % Atrac3plus.FRAME_SAMPLES);
                if (f != decodedFrame) decodeFrame(f);
                int take = (int)Math.min(Math.min(maxFrames - n, Atrac3plus.FRAME_SAMPLES - at), end - pos);
                System.arraycopy(frame, at * 2, out, n * 2, take * 2);
                n += take; pos += take;
            }
            return n;
        }
        private void seek(long sample) {
            pos = sample; int f = (int)((sample + delaySamples) / Atrac3plus.FRAME_SAMPLES);
            if (f == decodedFrame) return;
            dec.reset(); decodedFrame = -1;
            for (int prime = Math.max(0, f - 2); prime < f; prime++) decodeFrame(prime); // rebuild the overlap state
        }
        private void decodeFrame(int f) {
            if (f != decodedFrame + 1 && decodedFrame >= 0) { dec.reset(); } // non-sequential: start fresh
            if (f < frames()) dec.decode(file, dataOffset + f * blockAlign, blockAlign, frame);
            else java.util.Arrays.fill(frame, (short)0);
            decodedFrame = f;
        }
    }

    private static boolean tag(byte[] b, int o, String t) {
        if (o + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) if (b[o + i] != t.charAt(i)) return false;
        return true;
    }
    private static boolean regionEquals(byte[] b, int o, byte[] v) {
        if (o + v.length > b.length) return false;
        for (int i = 0; i < v.length; i++) if (b[o + i] != v[i]) return false;
        return true;
    }
    private static int le16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static int le32(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (b[o + 3] & 255) << 24; }
}
