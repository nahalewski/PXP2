package org.pxp2.core;

/**
 * Mixes every PSP audio output onto one 44.1 kHz stereo timeline in emulated time. Outputs are added at the
 * sample position where the hardware would start playing them; {@link #drain} hands finished samples to the
 * host sink (Android AudioTrack) in order. Positions older than the ring (about 1.5 s) are dropped.
 */
public final class AudioMixer {
    public interface Sink { void write(short[] stereo, int frames); }
    public static final int RATE = 44100;
    private static final int SIZE = 1 << 16, MASK = SIZE - 1;
    private final int[] left = new int[SIZE], right = new int[SIZE];
    private long readHead = -1;
    public volatile Sink sink;
    /** Master output gain: the PSP's own volume setting (impose volume 0..30, mute) applied after mixing. */
    public volatile float gain = 1f;
    private short[] out = new short[8192];

    /** Adds {@code frames} input frames (interleaved stereo, or mono) resampled from {@code rate}, starting at {@code startFrame}. */
    public void add(long startFrame, Memory m, int address, int frames, boolean mono, int rate, int volumeLeft, int volumeRight) {
        if (sink == null || address == 0 || frames <= 0) return;
        int bytes = frames * (mono ? 2 : 4); if (!m.valid(address, bytes)) return;
        if (readHead < 0) readHead = startFrame;
        if (startFrame < readHead) startFrame = readHead;                // late: never rewrite already drained audio
        long outFrames = (long)frames * RATE / Math.max(1, rate);
        if (startFrame + outFrames - readHead > SIZE) return;             // too far ahead of the host; drop rather than wrap
        byte[] data = m.read(address, bytes);
        for (long i = 0; i < outFrames; i++) {
            int src = (int)(i * rate / RATE); if (src >= frames) src = frames - 1;
            int l, r;
            if (mono) { l = r = (short)((data[src * 2] & 255) | (data[src * 2 + 1] << 8)); }
            else { l = (short)((data[src * 4] & 255) | (data[src * 4 + 1] << 8)); r = (short)((data[src * 4 + 2] & 255) | (data[src * 4 + 3] << 8)); }
            int at = (int)((startFrame + i) & MASK);
            left[at] += l * volumeLeft >> 15; right[at] += r * volumeRight >> 15;
        }
    }
    /** Sends every mixed frame before {@code untilFrame} to the sink. */
    public void drain(long untilFrame) {
        Sink s = sink; if (s == null || readHead < 0 || untilFrame <= readHead) return;
        int n = (int)Math.min(untilFrame - readHead, SIZE);
        if (out.length < n * 2) out = new short[n * 2];
        for (int i = 0; i < n; i++) {
            int at = (int)((readHead + i) & MASK);
            float g = gain; out[i * 2] = (short)Math.max(-32768, Math.min(32767, Math.round(left[at] * g))); out[i * 2 + 1] = (short)Math.max(-32768, Math.min(32767, Math.round(right[at] * g)));
            left[at] = 0; right[at] = 0;
        }
        readHead += n; s.write(out, n);
    }
    public static long frameAt(long micros) { return micros * RATE / 1_000_000L; }
}
