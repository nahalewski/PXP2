package org.pxp2.core;

import java.util.*;

/**
 * Original HLE for audio output (sceAudio), the audio codec service (sceAudiocodec), the UMD
 * drive (no disc inserted) and USB. Audio output is paced in emulated time and mixed by
 * {@link AudioMixer} for the host. MP3 frames are decoded by an optional host {@link FrameDecoder}
 * (Android MediaCodec); other codecs, and MP3 without a host decoder, produce silence of the correct length.
 */
public final class HleMedia {
    private final Kernel k; private final Memory m;
    static final class Channel { boolean reserved; int samples, format, volumeLeft, volumeRight; long busyUntil; }
    private final Channel[] channels = new Channel[8];
    private final Channel output2 = new Channel(), src = new Channel();
    public long samplesQueued, silentFrames; public int alcMode;
    private final List<Integer> umdCallbacks = new ArrayList<>();
    private boolean umdActivated;

    public HleMedia(Kernel k) {
        this.k = k; this.m = k.memory;
        for (int i = 0; i < 8; i++) channels[i] = new Channel();
        registerAudio(); registerCodec(); scheduleDrain(k.now() + 10_000);
        registerVaudio("sceVshBridge", 0xaf135135, 0x7816d515, 0xeb7b7992, 0xca223904); // -> sceVaudio_driver 03b6807d/67585dfd/8986295e/27acc20b
        registerVaudio("sceVaudio", 0x03b6807d, 0x67585dfd, 0x8986295e, 0x346fbe94); registerUmd(); registerUsb();
        // vaudio.prx: e8e78dc8 (samples, frequency, channels) reserves through the same routine as sceVaudioChReserve
        // (08800ac8: samples 256-2048 else 0x80000104, channels 2 or 4 else 0x80000108). 504e4745 (n) only stores a
        // halfword of the reserved channel (1024-4096, else 0x800001fe; 0x80000001 while none is reserved) that the output
        // routine passes to the ME; the video player calls it before every output with 2048. It does not affect timing here.
        k.register("sceVaudio", 0xe8e78dc8, "sceVaudio_E8E78DC8 (reserve)", c -> {
            if (c.arg(0) < 256 || c.arg(0) > 2048) return 0x80000104; if (c.arg(2) != 2 && c.arg(2) != 4) return 0x80000108;
            if (vaudio.reserved) return 0x80260002;
            vaudio.reserved = true; vaudio.samples = c.arg(0); vaudioRate = c.arg(1); vaudio.format = c.arg(2); vaudio.busyUntil = 0; return 0;
        });
        k.register("sceVaudio", 0x504e4745, "sceVaudio_504E4745 (set channel parameter)", c -> {
            if (!vaudio.reserved) return 0x80000001; if (c.arg(0) < 1024 || c.arg(0) > 4096) return 0x800001fe; vaudioParameter = c.arg(0); return 0;
        });
    }

    public final AudioMixer mixer = new AudioMixer();
    /** Optional host decoder for compressed frames (Android supplies MediaCodec for MP3). */
    public volatile FrameDecoder decoder; private short[] pcm = new short[4608];
    private void scheduleDrain(long at) { k.schedule(at, () -> { mixer.drain(AudioMixer.frameAt(k.now())); scheduleDrain(at + 10_000); }); }
    private int output(Channel c, int samples, boolean blocking) { return output(c, samples, blocking, 0, 44100, false); }
    /** Queues one buffer: plays after the channel's previous buffer, in emulated time, mixed into {@link #mixer}. */
    private int output(Channel c, int samples, boolean blocking, int buffer, int rate, boolean mono) {
        long duration = samples * 1_000_000L / Math.max(8000, rate), now = k.now();
        if (c.busyUntil > now && !blocking) return 0x80260009; // SCE_AUDIO_ERROR_OUTPUT_BUSY
        long start = Math.max(now, c.busyUntil); c.busyUntil = start + duration; samplesQueued += samples;
        mixer.add(AudioMixer.frameAt(start), m, buffer, samples, mono, rate, c.volumeLeft, c.volumeRight);
        if (blocking && start > now) {
            Kernel.GuestThread t = k.current; Kernel.Wait w = new Kernel.Wait("Audio", 0); k.waitCurrent(w, 0, false);
            k.schedule(start, () -> { if (t.wait == w) k.wake(t, samples); });
        }
        return samples;
    }
    private Channel channel(int ch) { return ch >= 0 && ch < 8 && channels[ch].reserved ? channels[ch] : null; }
    private void registerAudio() {
        String lib = "sceAudio";
        k.register(lib, 0x5ec81c55, "sceAudioChReserve", c -> {
            int ch = c.arg(0), samples = c.arg(1);
            if (samples <= 0 || samples > 0xffc0 || (samples & 63) != 0) return 0x80260005;
            if (ch < 0) { for (int i = 7; i >= 0; i--) if (!channels[i].reserved) { ch = i; break; } if (ch < 0) return 0x80260002; }
            if (ch >= 8) return 0x80260001; if (channels[ch].reserved) return 0x80260002;
            Channel x = channels[ch]; x.reserved = true; x.samples = samples; x.format = c.arg(2); x.busyUntil = 0; return ch;
        });
        k.register(lib, 0x6fc46853, "sceAudioChRelease", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; x.reserved = false; return 0; });
        k.register(lib, 0x136caf51, "sceAudioOutputBlocking", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; x.volumeLeft = x.volumeRight = c.arg(1); return output(x, x.samples, true, c.arg(2), 44100, (x.format & 0x10) != 0); });
        k.register(lib, 0x8c1009b2, "sceAudioOutput", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; x.volumeLeft = x.volumeRight = c.arg(1); return output(x, x.samples, false, c.arg(2), 44100, (x.format & 0x10) != 0); });
        k.register(lib, 0x13f592bc, "sceAudioOutputPannedBlocking", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; x.volumeLeft = c.arg(1); x.volumeRight = c.arg(2); return output(x, x.samples, true, c.arg(3), 44100, (x.format & 0x10) != 0); });
        k.register(lib, 0xe2d56b2d, "sceAudioOutputPanned", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; x.volumeLeft = c.arg(1); x.volumeRight = c.arg(2); return output(x, x.samples, false, c.arg(3), 44100, (x.format & 0x10) != 0); });
        k.register(lib, 0xb011922f, "sceAudioGetChannelRestLength", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; return (int)Math.max(0, (x.busyUntil - k.now()) * 44100 / 1_000_000L); });
        k.register(lib, 0xe9d97901, "sceAudioGetChannelRestLen", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; return (int)Math.max(0, (x.busyUntil - k.now()) * 44100 / 1_000_000L); });
        k.register(lib, 0xcb2e439e, "sceAudioSetChannelDataLen", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; x.samples = c.arg(1); return 0; });
        k.register(lib, 0x95fd0c2d, "sceAudioChangeChannelConfig", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; x.format = c.arg(1); return 0; });
        k.register(lib, 0xb7e1d8e7, "sceAudioChangeChannelVolume", c -> { Channel x = channel(c.arg(0)); if (x == null) return 0x80260008; x.volumeLeft = c.arg(1); x.volumeRight = c.arg(2); return 0; });
        k.register(lib, 0x01562ba3, "sceAudioOutput2Reserve", c -> { if (output2.reserved) return 0x80260002; output2.reserved = true; output2.samples = c.arg(0); return 0; });
        k.register(lib, 0x43196845, "sceAudioOutput2Release", c -> { output2.reserved = false; return 0; });
        k.register(lib, 0x2d53f36e, "sceAudioOutput2OutputBlocking", c -> { if (!output2.reserved) return 0x80260008; output2.volumeLeft = output2.volumeRight = c.arg(0); return output(output2, output2.samples, true, c.arg(1), 44100, false); });
        k.register(lib, 0x647cef33, "sceAudioOutput2GetRestSample", c -> (int)Math.max(0, (output2.busyUntil - k.now()) * 44100 / 1_000_000L));
        k.register(lib, 0x38553111, "sceAudioSRCChReserve", c -> { if (src.reserved) return 0x80260002; src.reserved = true; src.samples = c.arg(0); srcRate = c.arg(1) == 0 ? 44100 : c.arg(1); return 0; });
        k.register(lib, 0x5c37c0ae, "sceAudioSRCChRelease", c -> { src.reserved = false; return 0; });
        k.register(lib, 0xe0727056, "sceAudioSRCOutputBlocking", c -> { if (!src.reserved) return 0x80260008; src.volumeLeft = src.volumeRight = c.arg(0); return output(src, src.samples, true, c.arg(1), srcRate, false); });
        k.register("sceVaudio", 0xcbd4ac51, "sceVaudioSetAlcMode", c -> { alcMode = c.arg(0); return 0; });
    }

    // ---- sceVaudio (virtual audio channel used by the music player), also reached through sceVshBridge ----
    private final Channel vaudio = new Channel(); private int vaudioRate = 44100, srcRate = 44100, vaudioParameter; public int vaudioEffect;
    private int vaudioOutput(int samples, int volume, int buffer) {
        long duration = samples * 1_000_000L / Math.max(8000, vaudioRate), now = k.now();
        long start = Math.max(now, vaudio.busyUntil); vaudio.busyUntil = start + duration; samplesQueued += samples;
        mixer.add(AudioMixer.frameAt(start), m, buffer, samples, vaudio.format == 1, vaudioRate, volume, volume);
        if (start > now) { Kernel.GuestThread t = k.current; Kernel.Wait w = new Kernel.Wait("Vaudio", 0); k.waitCurrent(w, 0, false); k.schedule(start, () -> { if (t.wait == w) k.wake(t, samples); }); }
        return samples;
    }
    void registerVaudio(String lib, int reserve, int release, int output, int setting) {
        k.register(lib, reserve, lib + ".sceVaudioChReserve", c -> {
            if (vaudio.reserved) return 0x80260002; if (c.arg(0) <= 0 || c.arg(0) > 0xffc0) return 0x80260005;
            vaudio.reserved = true; vaudio.samples = c.arg(0); vaudioRate = c.arg(1); vaudio.format = c.arg(2); vaudio.busyUntil = 0; return 0;
        });
        k.register(lib, release, lib + ".sceVaudioChRelease", c -> { vaudio.reserved = false; return 0; });
        k.register(lib, output, lib + ".sceVaudioOutputBlocking", c -> vaudio.reserved ? vaudioOutput(vaudio.samples, c.arg(0), c.arg(1)) : 0x80260008);
        k.register(lib, setting, lib + ".sceVaudioSetting", c -> { vaudioEffect = c.arg(0); return 0; });
    }

    private void registerCodec() {
        String lib = "sceAudiocodec";
        k.register(lib, 0x9d3f790c, "sceAudiocodecCheckNeedMem", c -> {
            int ctx = c.arg(0), codec = c.arg(1); if (codec < 0x1000 || codec > 0x1005) return 0x80000004;
            // ME EDRAM needed per decoder instance. libatrac3plus requires 2x ATRAC3plus + 2x ATRAC3 to fit in 0x19000.
            m.write32(ctx + 16, codec == 0x1000 ? 0x7bc0 : codec == 0x1001 ? 0x3de0 : 0x2000); m.write32(ctx + 8, 0); return 0; // neededMem, err
        });
        // The caller stores its own buffer in ctx+16 (where CheckNeedMem reported the size); EDRAM = that buffer aligned to 64.
        k.register(lib, 0x3a20a200, "sceAudiocodecGetEDRAM", c -> { int ctx = c.arg(0); m.write32(ctx + 12, (m.read32(ctx + 16) + 0x3f) & ~0x3f); return 0; });
        k.register(lib, 0x29681260, "sceAudiocodecReleaseEDRAM", c -> { m.write32(c.arg(0) + 12, 0); return 0; });
        // Decode consumes exactly one frame and produces one frame of PCM with correct sizes and timing,
        // ATRAC3plus is decoded in core (Atrac3plus), MP3 by the host FrameDecoder; other codecs produce SILENCE.
        k.register(lib, 0x70a703f8, "sceAudiocodecDecode", c -> {
            int ctx = c.arg(0), codec = c.arg(1), out = m.read32(ctx + 32);
            int frameBytes, samples;
            switch (codec) {
                case 0x1000: frameBytes = (((m.read8(ctx + 40) & 3) << 8) | m.read8(ctx + 41)) * 8 + 8; samples = 2048; break; // ATRAC3plus
                case 0x1001: frameBytes = m.read8(ctx + 40) * 8; samples = 1024; break;                                       // ATRAC3 (approximate)
                case 0x1002: { // MP3: frame size and sample count come from the frame header at the input pointer
                    int in = m.read32(ctx + 24); int[] f = mp3Frame(in);
                    if (f == null) { m.write32(ctx + 28, 0); m.write32(ctx + 36, 0); m.write32(ctx + 8, 0x807f00fd); return 0x807f00fd; }
                    frameBytes = f[0]; samples = f[1]; break;
                }
                case 0x1003: // AAC (raw frames, as in MP4): the caller passes only the frame address, the ME finds the frame end
                    // itself. PXP2 has no AAC bitstream parser, so the length is unknown (reported as 0 consumed; the video player
                    // does not read it) and the frame is not given to the host decoder. Output size per GetOutputBytes.
                    frameBytes = 0; samples = m.read8(ctx + 45) != 0 ? 2048 : 1024; break;
                default: throw new Kernel.Stop(String.format(java.util.Locale.ROOT, "sceAudiocodecDecode codec %04x not implemented", codec));
            }
            if (DUMP != null && (codec == 0x1000 || codec == 0x1001)) dumpFrame(ctx, codec, frameBytes);
            FrameDecoder d = decoder;
            if (codec == 0x1000 && psmfHeader(m.read32(ctx + 24))) frameBytes += 8; // consumed includes the PSMF frame header
            if (codec == 0x1000 && out != 0 && decodeAtrac3plus(ctx, out, frameBytes)) {
                // decoded in core (Atrac3plus)
            } else if (d != null && codec == 0x1002 && out != 0 && m.valid(m.read32(ctx + 24), frameBytes)) {
                byte[] frame = m.read(m.read32(ctx + 24), frameBytes);
                if (pcm.length != samples * 2) pcm = new short[samples * 2]; // exactly one frame, so the decoder keeps any surplus queued
                int got = Math.max(0, Math.min(samples, d.decode(codec, frame, frameBytes, pcm)));
                byte[] bytes = new byte[samples * 4];
                for (int i = 0; i < got * 2; i++) { bytes[i * 2] = (byte)pcm[i]; bytes[i * 2 + 1] = (byte)(pcm[i] >> 8); }
                m.write(out, bytes); // any shortfall (decoder start-up delay) stays silent
            } else {
                if (silentFrames++ == 0) k.log("sceAudiocodecDecode: producing silence (codec " + Integer.toHexString(codec) + ", " + frameBytes + "-byte frames)");
                if (out != 0) m.fill(out, 0, samples * 4);
            }
            m.write32(ctx + 28, frameBytes); m.write32(ctx + 36, samples * 4); m.write32(ctx + 8, 0); return 0;
        });
        k.register(lib, 0x5b37eb1d, "sceAudiocodecInit", c -> { atrac3plus.remove(c.arg(0)); m.write32(c.arg(0) + 8, 0); return 0; });
        // 59176a0f (ctx, codec, &bytes): PCM bytes one decode produces, from avcodec's own table (08800a40): ATRAC3plus
        // channels(ctx+72) x 0x1000, ATRAC3 0x1000, MP3 0x1200 (MPEG-1, ctx+56 == 1) or 0x900, AAC 0x1000 or 0x2000 when
        // ctx byte 45 is set, 0x1004 0x1200, 0x1005 0x2f00.
        k.register(lib, 0x59176a0f, "sceAudiocodecGetOutputBytes", c -> {
            int ctx = c.arg(0), codec = c.arg(1), out = c.arg(2); if (codec < 0x1000 || codec > 0x1005) return 0x80000004;
            if (!m.valid(ctx, 108) || !m.valid(out, 4)) return 0x80000023;
            int bytes;
            switch (codec) {
                case 0x1000: bytes = m.read32(ctx + 56) == 1 && m.read32(ctx + 72) == 1 ? 0x1000 : m.read32(ctx + 56) == 1 ? 0x2000 : m.read32(ctx + 72) << 12; break;
                case 0x1001: bytes = 0x1000; break;
                case 0x1002: if (m.read32(ctx + 56) == 9999) throw new Kernel.Stop("sceAudiocodecGetOutputBytes: MP3 stream info not yet known (ctx+56 = 9999)");
                    bytes = m.read32(ctx + 56) == 1 ? 0x1200 : 0x900; break;
                case 0x1003: bytes = m.read8(ctx + 45) != 0 ? 0x2000 : 0x1000; break;
                case 0x1004: bytes = 0x1200; break;
                default: bytes = 0x2f00;
            }
            m.write32(out, bytes); return 0;
        });
    }

    /** ATRAC3plus decoders keyed by codec context address (state carries across frames; reset by sceAudiocodecInit). */
    private final Map<Integer, Atrac3plus> atrac3plus = new HashMap<>();
    private final byte[] atracFrame = new byte[8192], atracPcm = new byte[2048 * 4]; private final short[] atracSamples = new short[2048 * 2];
    /** Decodes the ATRAC3plus frame at ctx+24 to 2048 stereo 16-bit samples at {@code out}; false if the frame cannot be read. */
    private boolean decodeAtrac3plus(int ctx, int out, int frameBytes) {
        int in = m.read32(ctx + 24), config = (m.read8(ctx + 40) << 8) | m.read8(ctx + 41);
        // Movie streams (PSMF, e.g. the XMB's gameboot.pmf) prefix every ATRAC3plus frame with an 8-byte header
        // 0F D0 <config hi> <config lo> 00 00 00 00 and pass its address; the frame data starts after it.
        if (psmfHeader(in)) { in += 8; frameBytes -= 8; }
        if (frameBytes > atracFrame.length || !m.valid(in, frameBytes) || !m.valid(out, atracPcm.length)) return false;
        int channels = Atrac3plus.channelsFromConfig(config);
        Atrac3plus a = atrac3plus.get(ctx);
        if (a == null || a.channels() != channels) atrac3plus.put(ctx, a = new Atrac3plus(channels));
        m.read(in, atracFrame, 0, frameBytes);
        if (a.decode(atracFrame, 0, frameBytes, atracSamples) < 0 && atracErrors++ == 0) k.log("sceAudiocodecDecode: invalid ATRAC3plus frame (silence)");
        for (int i = 0; i < atracSamples.length; i++) { atracPcm[i * 2] = (byte)atracSamples[i]; atracPcm[i * 2 + 1] = (byte)(atracSamples[i] >> 8); }
        m.write(out, atracPcm, 0, atracPcm.length);
        return true;
    }
    private int atracErrors;
    private boolean psmfHeader(int in) { return m.valid(in, 8) && m.read8(in) == 0x0f && m.read8(in + 1) == 0xd0; }

    /** Debug: -Dpxp2.atracDump=dir appends every ATRAC frame to dir/codec-ctx.raw (ctx bytes 0..63 go to .ctx once). */
    private static final String DUMP = System.getProperty("pxp2.atracDump");
    private void dumpFrame(int ctx, int codec, int frameBytes) {
        try {
            java.io.File dir = new java.io.File(DUMP); dir.mkdirs();
            String name = String.format(java.util.Locale.ROOT, "%04x-%08x", codec, ctx);
            java.io.File c = new java.io.File(dir, name + ".ctx");
            if (!c.exists()) java.nio.file.Files.write(c.toPath(), m.read(ctx, 64));
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, name + ".raw"), true)) { o.write(m.read(m.read32(ctx + 24), frameBytes)); }
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static final int[][] MP3_BITRATES = { // kbit/s: [0] MPEG-1 layer III, [1] MPEG-2/2.5 layer III
        {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}, {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}};
    private static final int[][] MP3_RATES = {{44100, 48000, 32000}, {22050, 24000, 16000}, {11025, 12000, 8000}};
    /** Parses an MPEG layer III frame header: returns {frame bytes, samples per channel}, or null if it is not a valid frame. */
    int[] mp3Frame(int at) {
        if (!m.valid(at, 4)) return null;
        int h = (m.read8(at) << 24) | (m.read8(at + 1) << 16) | (m.read8(at + 2) << 8) | m.read8(at + 3);
        if ((h >>> 21) != 0x7ff || ((h >>> 17) & 3) != 1) return null; // sync, layer III
        int version = (h >>> 19) & 3, bitrate = (h >>> 12) & 15, rate = (h >>> 10) & 3, padding = (h >>> 9) & 1;
        if (version == 1 || bitrate == 0 || bitrate == 15 || rate == 3) return null;
        boolean mpeg1 = version == 3; int hz = MP3_RATES[version == 3 ? 0 : version == 2 ? 1 : 2][rate];
        int kbps = MP3_BITRATES[mpeg1 ? 0 : 1][bitrate], samples = mpeg1 ? 1152 : 576;
        return new int[]{samples / 8 * kbps * 1000 / hz + padding, samples};
    }

    private boolean disc() { return k.io != null && k.io.umd != null; }
    /** PSP_UMD_* bits: 0x01 not present, 0x02 present, 0x10 initialised, 0x20 ready (activated). */
    public int driveStat() { return !disc() ? 0x01 : umdActivated && !spinning ? 0x32 : 0x12; }
    /** Called after a disc is inserted or removed, and on (de)activation: every UMD callback gets the new state. */
    /** A disc was inserted, swapped or ejected: the drive is no longer activated until the system activates it again. */
    public void discChanged() {
        // Activation is a drive setting and survives disc changes: a new disc reports "present" (0x12) first and, if the
        // drive is activated, "ready" (0x32) after a short spin-up, which is what the XMB's UMD callback waits for.
        if (!disc() || !umdActivated) { spinning = false; notifyUmd(); return; }
        spinning = true; notifyUmd();
        k.schedule(k.now() + 300_000, () -> { spinning = false; notifyUmd(); });
    }
    private boolean spinning;
    public void notifyUmd() { for (int cb : umdCallbacks) k.notifyCallback(cb, driveStat()); }
    private void registerUmd() {
        String lib = "sceUmdUser";
        k.register(lib, 0x46ebb729, "sceUmdCheckMedium", c -> disc() ? 1 : 0);
        k.register(lib, 0xc6183d47, "sceUmdActivate", c -> {
            if (!disc()) return 0x80210003; // SCE_UMD_ERROR_NO_MEDIUM (approximate)
            umdActivated = true; notifyUmd(); return 0;
        });
        k.register(lib, 0xe83742ba, "sceUmdDeactivate", c -> { umdActivated = false; notifyUmd(); return 0; });
        k.register(lib, 0x6b4a146c, "sceUmdGetDriveStat", c -> driveStat());
        k.register(lib, 0xaee7404d, "sceUmdRegisterUMDCallBack", c -> { if (k.object(c.arg(0), Kernel.Callback.class) == null) return 0x80010016; umdCallbacks.add(c.arg(0)); k.notifyCallback(c.arg(0), driveStat()); return 0; });
        k.register(lib, 0xbd2bde07, "sceUmdUnRegisterUMDCallBack", c -> umdCallbacks.remove((Integer)c.arg(0)) ? 0 : 0x80010016);
        k.register(lib, 0x87533940, "sceUmdReplaceProhibit", c -> 0);
        k.register(lib, 0xcbe9f02a, "sceUmdReplacePermit", c -> 0);
        k.register(lib, 0x20628e6f, "sceUmdGetErrorStat", c -> 0);
        k.register(lib, 0x340b7686, "sceUmdGetDiscInfo", c -> {
            if (!disc()) return 0x80210003;
            if (c.arg(0) != 0) { m.write32(c.arg(0), 8); m.write32(c.arg(0) + 4, k.io.discType()); } // size, type
            return 0;
        });
        // The drive never has to spin up here, so every wait is satisfied once the requested state is reached;
        // waits for a state that cannot occur return immediately too (a real drive would time out or block).
        for (int nid : new int[]{0x8ef08fce, 0x56202973, 0x4a9e5e29})
            k.register(lib, nid, nid == 0x8ef08fce ? "sceUmdWaitDriveStat" : nid == 0x56202973 ? "sceUmdWaitDriveStatWithTimer" : "sceUmdWaitDriveStatCB",
                c -> (driveStat() & c.arg(0)) != 0 ? 0 : (nid == 0x8ef08fce ? 0 : 0x800201a8));
        k.register(lib, 0x6af9b50a, "sceUmdCancelWaitDriveStat", c -> 0);
    }
    private void registerUsb() {
        String lib = "sceUsb";
        k.register(lib, 0xae5de6af, "sceUsbStart", c -> 0);
        k.register(lib, 0xc2464fa0, "sceUsbStop", c -> 0);
        // usb.prx: register/unregister a USB state callback (validated as a callback uid). USB is never connected.
        k.register(lib, 0x8bfc3de8, "sceUsbRegisterCallback", c -> k.object(c.arg(0), Kernel.Callback.class) == null ? 0x80243002 : 0);
        k.register(lib, 0x89de0dc5, "sceUsbUnregisterCallback", c -> c.arg(0) < 0 ? 0x80243002 : 0);
        // USB mass storage (usbstorboot.prx): homebrew file managers set the exported capacity and a notify callback
        // before offering USB mode. There is no USB host, so these only succeed.
        k.register("sceUsbstorBoot", 0xe58818a8, "sceUsbstorBootSetCapacity", c -> 0);
        k.register("sceUsbstorBoot", 0x1f080078, "sceUsbstorBootRegisterNotify", c -> 0);
        k.register("sceUsbstorBoot", 0xa55c9e16, "sceUsbstorBootUnregisterNotify", c -> 0);
    }
}
