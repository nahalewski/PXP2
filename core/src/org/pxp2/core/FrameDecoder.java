package org.pxp2.core;

/** Host-provided decoder for one compressed audio frame (for example Android MediaCodec for MP3). */
public interface FrameDecoder {
    /**
     * Decodes one frame of the given PSP codec id (0x1002 = MP3) into interleaved stereo 16-bit PCM.
     * @return frames written to {@code pcm} (per channel); fewer than a full frame is allowed (decoder delay)
     */
    int decode(int codec, byte[] frame, int length, short[] pcm);
}
