package org.pxp2.fresh;

import android.media.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import org.pxp2.core.AudioMixer;
import org.pxp2.core.FrameDecoder;

/**
 * Android audio for PXP2: an AudioTrack that plays the emulator's mixed 44.1 kHz stereo stream, and an MP3
 * frame decoder backed by the platform MediaCodec. Writes never block the emulation thread; if emulation runs
 * slower than real time the track underruns (audible gaps) rather than slowing emulation further.
 */
final class AndroidAudio implements AudioMixer.Sink, FrameDecoder {
    private final AudioTrack track;
    private MediaCodec mp3; private int mp3Rate, mp3Channels = 2;
    private short[] pending = new short[0]; private int pendingCount; // decoded interleaved stereo samples not yet handed out

    AndroidAudio() {
        int min = AudioTrack.getMinBufferSize(AudioMixer.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(AudioMixer.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(Math.max(min * 4, 44100)).setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
    }
    @Override public void write(short[] stereo, int frames) { track.write(stereo, 0, frames * 2, AudioTrack.WRITE_NON_BLOCKING); }
    void pause() { track.pause(); }
    void resume() { track.play(); }
    void release() { track.release(); if (mp3 != null) { mp3.release(); mp3 = null; } }

    private static int mp3Rate(byte[] f) {
        int version = (f[1] >>> 3) & 3, rate = (f[2] >>> 2) & 3;
        int[][] rates = {{11025, 12000, 8000}, {0, 0, 0}, {22050, 24000, 16000}, {44100, 48000, 32000}};
        return rate == 3 ? 44100 : rates[version][rate];
    }
    @Override public int decode(int codec, byte[] frame, int length, short[] pcm) {
        if (codec != 0x1002 || length < 4) return 0;
        try {
            int rate = mp3Rate(frame), channels = ((frame[3] >>> 6) & 3) == 3 ? 1 : 2;
            if (mp3 == null || rate != mp3Rate || channels != mp3Channels) {
                if (mp3 != null) mp3.release();
                mp3 = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG);
                mp3.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, rate, channels), null, null, 0);
                mp3.start(); mp3Rate = rate; mp3Channels = channels; pendingCount = 0;
            }
            int in = mp3.dequeueInputBuffer(20_000);
            if (in >= 0) { ByteBuffer b = mp3.getInputBuffer(in); b.clear(); b.put(frame, 0, length); mp3.queueInputBuffer(in, 0, length, 0, 0); }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int tries = 0; tries < 4; tries++) {
                int out = mp3.dequeueOutputBuffer(info, tries == 0 ? 5_000 : 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { mp3Channels = mp3.getOutputFormat().getInteger(MediaFormat.KEY_CHANNEL_COUNT); continue; }
                if (out < 0) break;
                ShortBuffer s = mp3.getOutputBuffer(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                int n = info.size / 2, stereo = mp3Channels == 1 ? n * 2 : n;
                if (pending.length < pendingCount + stereo) pending = java.util.Arrays.copyOf(pending, (pendingCount + stereo) * 2);
                for (int i = 0; i < n; i++) {
                    short v = s.get(i);
                    if (mp3Channels == 1) { pending[pendingCount++] = v; pending[pendingCount++] = v; } else pending[pendingCount++] = v;
                }
                mp3.releaseOutputBuffer(out, false);
            }
            int want = Math.min(pcm.length, pendingCount) & ~1;
            System.arraycopy(pending, 0, pcm, 0, want);
            System.arraycopy(pending, want, pending, 0, pendingCount - want); pendingCount -= want;
            return want / 2;
        } catch (Exception e) { return 0; } // decoder problems degrade to silence, never stop emulation
    }
}
