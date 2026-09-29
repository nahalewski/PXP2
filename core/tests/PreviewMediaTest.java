import org.pxp2.core.At3File;
import org.pxp2.core.Atrac3plus;
import org.pxp2.core.Iso9660;
import org.pxp2.core.PsmfDemuxer;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Tests the game-preview media readers: {@link PsmfDemuxer} (ICON1.PMF) and {@link At3File} (SND0.AT3).
 *
 * <p>Usage: {@code PreviewMediaTest [iso-folder scratch-folder]}
 * <ul>
 * <li>Without arguments: a synthetic PSMF (PES packets that split NAL units, MPEG-2 pack stuffing, padding packets and an
 *     audio packet) must demux to the exact elementary stream and access units. No files or tools needed.</li>
 * <li>With arguments: for each ISO in the folder, PSP_GAME/ICON1.PMF and SND0.AT3 are read through {@link Iso9660},
 *     written to the scratch folder, and compared with ffmpeg (on the PATH): the demuxed video must equal
 *     {@code ffmpeg -c:v copy -f h264} byte for byte with the same frame count as ffprobe, and the ATRAC3plus decode
 *     must match {@code ffmpeg -f f32le} (SNR over 60 dB).</li>
 * </ul>
 */
public final class PreviewMediaTest {
    public static void main(String[] args) throws Exception {
        synthetic();
        if (args.length >= 2) {
            int failures = 0;
            File[] isos = new File(args[0]).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".iso"));
            Arrays.sort(isos);
            File scratch = new File(args[1]); scratch.mkdirs();
            for (File f : isos) failures += checkIso(f, scratch);
            System.out.println(failures == 0 ? "PreviewMediaTest: all real files OK" : "PreviewMediaTest: " + failures + " FAILURES");
            if (failures > 0) System.exit(1);
        }
    }

    // ------------------------------------------------------------------------------------------------ synthetic stream
    private static void synthetic() throws IOException {
        ByteArrayOutputStream es = new ByteArrayOutputStream();
        int[][] frames = {{9, 7, 8, 6, 5, 5}, {9, 1}, {9, 1, 1}, {9, 1}};
        List<Integer> auStarts = new ArrayList<>();
        Random rnd = new Random(1);
        for (int[] frame : frames) {
            auStarts.add(es.size());
            boolean firstSlice = true;
            for (int type : frame) {
                es.write(new byte[]{0, 0, 0, 1, (byte)(0x60 | type)});
                if (type == 1 || type == 5) { es.write(firstSlice ? 0x88 : 0x48); firstSlice = false; } // first_mb 0 / not 0
                for (int i = 0; i < 40 + rnd.nextInt(300); i++) es.write(1 + rnd.nextInt(255)); // no emulated start codes
            }
        }
        byte[] ref = es.toByteArray();
        ByteArrayOutputStream ps = new ByteArrayOutputStream();
        for (int at = 0, n = 0; at < ref.length; n++) {
            int len = Math.min(ref.length - at, 97 + n * 13); // packets split NAL units and start codes at odd places
            ps.write(new byte[]{0, 0, 1, (byte)0xBA, 0x44, 0, 4, 0, 4, 1, 1, (byte)0x89, (byte)0xC3, (byte)0xFA, (byte)0xFF, (byte)0xFF}); // 2 stuffing
            if (n == 1) { ps.write(new byte[]{0, 0, 1, (byte)0xBD, 0, 6, (byte)0x81, (byte)0x80, 0, 9, 9, 9}); } // audio, ignored
            if (n == 2) { ps.write(new byte[]{0, 0, 1, (byte)0xBE, 0, 3, (byte)0xFF, (byte)0xFF, (byte)0xFF}); } // padding
            boolean pts = n % 2 == 0;
            int hdr = pts ? 5 : 0, pesLen = 3 + hdr + len;
            ps.write(new byte[]{0, 0, 1, (byte)0xE0, (byte)(pesLen >> 8), (byte)pesLen, (byte)0x81, (byte)(pts ? 0x80 : 0), (byte)hdr});
            if (pts) { long t = 90000 + n * 3003L; ps.write(new byte[]{(byte)(0x21 | (t >> 29) & 0x0E), (byte)(t >> 22), (byte)((t >> 14) | 1), (byte)(t >> 7), (byte)((t << 1) | 1)}); }
            ps.write(ref, at, len); at += len;
        }
        ps.write(new byte[]{0, 0, 1, (byte)0xB9});
        byte[] stream = ps.toByteArray(), pmf = new byte[0x800 + stream.length];
        System.arraycopy("PSMF0015".getBytes(), 0, pmf, 0, 8);
        ByteBuffer.wrap(pmf).putInt(8, 0x800).putInt(12, stream.length);
        System.arraycopy(stream, 0, pmf, 0x800, stream.length);

        check(PsmfDemuxer.version(pmf).equals("0015"), "version");
        check(Arrays.equals(PsmfDemuxer.videoStream(pmf), ref), "synthetic elementary stream differs");
        List<PsmfDemuxer.AccessUnit> aus = PsmfDemuxer.accessUnits(pmf);
        // frames with two slices (the second has first_mb_in_slice != 0) stay one access unit each
        check(aus.size() == frames.length, "expected " + frames.length + " access units, got " + aus.size());
        for (int i = 0; i < frames.length; i++) check(aus.get(i).data.length == (i + 1 < frames.length ? auStarts.get(i + 1) : ref.length) - auStarts.get(i), "access unit " + i + " bounds");
        int total = 0; for (PsmfDemuxer.AccessUnit au : aus) total += au.data.length;
        check(total == ref.length, "access units do not cover the stream");
        check(aus.get(0).pts90k == 90000, "first PTS " + aus.get(0).pts90k);
        System.out.println("PsmfDemuxer synthetic: OK (" + aus.size() + " access units, " + ref.length + " bytes)");
    }

    // ------------------------------------------------------------------------------------------------ real files
    private static int checkIso(File isoFile, File scratch) throws Exception {
        int failures = 0;
        String base = isoFile.getName().replaceAll("[^A-Za-z0-9]", "");
        try (Iso9660 iso = new Iso9660(isoFile)) {
            Iso9660.Entry icon1 = iso.find("/PSP_GAME/ICON1.PMF"), snd0 = iso.find("/PSP_GAME/SND0.AT3");
            if (icon1 != null) failures += checkPmf(isoFile.getName(), iso.readFile(icon1), new File(scratch, base + "_ICON1.PMF"));
            if (snd0 != null) failures += checkAt3(isoFile.getName(), iso.readFile(snd0), new File(scratch, base + "_SND0.AT3"));
        }
        return failures;
    }

    private static int checkPmf(String name, byte[] pmf, File copy) throws Exception {
        Files.write(copy.toPath(), pmf);
        long t0 = System.nanoTime();
        byte[] es = PsmfDemuxer.videoStream(pmf); List<PsmfDemuxer.AccessUnit> aus = PsmfDemuxer.accessUnits(pmf);
        double ms = (System.nanoTime() - t0) / 1e6;
        File ref = new File(copy.getPath() + ".h264");
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", copy.getPath(), "-map", "0:v:0", "-c:v", "copy", "-f", "h264", ref.getPath());
        byte[] want = Files.readAllBytes(ref.toPath());
        String frames = run("ffprobe", "-v", "error", "-count_packets", "-select_streams", "v:0", "-show_entries", "stream=nb_read_packets,width,height", "-of", "csv=p=0", copy.getPath()).trim();
        int[] size = org.pxp2.core.HleVideo.pictureSize(aus.isEmpty() ? new byte[0] : aus.get(0).data);
        boolean same = Arrays.equals(es, want);
        int ffFrames = Integer.parseInt(frames.substring(frames.lastIndexOf(',') + 1).trim());
        boolean ok = same && ffFrames == aus.size();
        System.out.printf("%-48s ICON1.PMF %s: %d bytes ES (%s ffmpeg), %d access units (ffprobe %d), SPS %s, %.1f ms%n",
            name, PsmfDemuxer.version(pmf), es.length, same ? "==" : "!=", aus.size(), ffFrames,
            size == null ? "?" : size[0] + "x" + size[1], ms);
        return ok ? 0 : 1;
    }

    private static int checkAt3(String name, byte[] riff, File copy) throws Exception {
        Files.write(copy.toPath(), riff);
        At3File at3 = At3File.parse(riff);
        String info = String.format("%s %d Hz %d ch, %d-byte frames, %d frames, %d samples, delay %d, loop %d..%d",
            at3.codecName(), at3.sampleRate, at3.channels, at3.blockAlign, at3.frames(), at3.totalSamples, at3.delaySamples, at3.loopStart, at3.loopEnd);
        if (!at3.decodable()) { System.out.printf("%-48s SND0.AT3 %s: not decodable (skipped)%n", name, info); return 0; }
        File ref = new File(copy.getPath() + ".f32");
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", copy.getPath(), "-f", "f32le", "-ac", "2", ref.getPath());
        FloatBuffer want = ByteBuffer.wrap(Files.readAllBytes(ref.toPath())).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        // raw decode of every frame, then the best alignment with ffmpeg's output (which may drop the encoder delay)
        int ch = Atrac3plus.channelsFromConfig(at3.config16);
        Atrac3plus dec = new Atrac3plus(ch);
        float[] ours = new float[at3.frames() * Atrac3plus.FRAME_SAMPLES * 2];
        long t0 = System.nanoTime(); int bad = 0;
        for (int f = 0; f < at3.frames(); f++) {
            if (dec.decodeFloat(riff, at3.dataOffset + f * at3.blockAlign, at3.blockAlign) < 0) bad++;
            for (int i = 0; i < Atrac3plus.FRAME_SAMPLES; i++) {
                ours[(f * Atrac3plus.FRAME_SAMPLES + i) * 2] = dec.output(0)[i];
                ours[(f * Atrac3plus.FRAME_SAMPLES + i) * 2 + 1] = dec.output(ch > 1 ? 1 : 0)[i];
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6 / at3.frames();
        int bestShift = 0; double bestSnr = -1e9;
        for (int shift : new int[]{0, at3.delaySamples, -at3.delaySamples}) {
            double snr = snr(ours, want, shift); if (snr > bestSnr) { bestSnr = snr; bestShift = shift; }
        }
        // the preview stream (delay skipped, looping) must reproduce the first seconds of the raw decode
        At3File.LoopingPcm loop = at3.new LoopingPcm(); short[] pcm = new short[4096 * 2]; double lerr = 0;
        for (int got = 0; got < 44100 * 2; ) {
            int n = loop.read(pcm, 4096);
            for (int i = 0; i < n * 2; i++) { int at = (got * 2 + i) + at3.delaySamples * 2; if (at < ours.length) lerr = Math.max(lerr, Math.abs(pcm[i] / 32768.0 - clip(ours[at]))); }
            got += n;
        }
        boolean ok = bad == 0 && bestSnr > 60 && lerr < 2.0 / 32768;
        System.out.printf("%-48s SND0.AT3 %s: SNR %.1f dB vs ffmpeg (shift %d, ffmpeg %d samples), %d bad frames, looping stream max error %.1f LSB, %.3f ms/frame%n",
            name, info, bestSnr, bestShift, want.limit() / 2, bad, lerr * 32768, ms);
        return ok ? 0 : 1;
    }
    private static double clip(double v) { return Math.round(Math.max(-1, Math.min(32767 / 32768.0, v)) * 32768) / 32768.0; }
    /** SNR of ours (interleaved stereo) against the reference, with ours[i + shift] aligned to reference[i]. */
    private static double snr(float[] ours, FloatBuffer ref, int shift) {
        double sig = 0, err = 0; int n = ref.limit() / 2;
        for (int i = 0; i < n; i++) {
            int j = i + shift; if (j < 0 || j * 2 + 1 >= ours.length) continue;
            for (int c = 0; c < 2; c++) { double a = ours[j * 2 + c], b = ref.get(i * 2 + c); sig += b * b; err += (a - b) * (a - b); }
        }
        return 10 * Math.log10(sig / Math.max(err, 1e-30));
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + " failed: " + out);
        return out;
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
