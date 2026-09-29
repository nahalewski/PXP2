package org.pxp2.core;

import java.util.List;

/**
 * Host-provided H.264 decoder behind the emulated Media Engine ({@link HleVideo}); Android uses MediaCodec.
 * Access units are Annex-B byte streams (start-code prefixed NAL units, SPS/PPS in band).
 */
public interface VideoDecoder {
    /** One decoded picture: I420 planes (Y width x height, then Cb and Cr at half size) of the coded picture size. */
    final class Picture {
        public final int width, height; public final byte[] yuv;
        public Picture(int width, int height, byte[] yuv) { this.width = width; this.height = height; this.yuv = yuv; }
    }
    /** Queues one access unit; returns the pictures that became ready in display order (often none while the decoder fills its reorder delay). */
    List<Picture> decode(byte[] au, int length);
    /** End of stream: returns the pictures still held back for reordering. */
    List<Picture> flush();
    /** Discards all state before a new stream. */
    void reset();
}
