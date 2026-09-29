import org.pxp2.core.Atrac3plus;
import java.nio.*;
import java.nio.file.*;

/**
 * Verifies the ATRAC3plus decoder against a reference decode.
 * Usage: Atrac3plusTest stream.oma reference.f32
 *   stream.oma    : EA3/OMA file (96-byte header, codec params at bytes 33..35) holding raw ATRAC3plus frames,
 *                   e.g. the boot sound dumped with -Dpxp2.atracDump and wrapped in an EA3 header;
 *   reference.f32 : the same stream decoded by ffmpeg ("ffmpeg -i stream.oma -f f32le reference.f32"), interleaved.
 * Prints SNR, maximum sample error and decode time per frame.
 */
public final class Atrac3plusTest {
    public static void main(String[] args) throws Exception {
        System.out.printf("fast DCT-IV max error vs direct: %.3g%n", Atrac3plus.dct4SelfTest());
        byte[] oma = Files.readAllBytes(Paths.get(args[0]));
        int params = (oma[34] & 255) << 8 | (oma[35] & 255);
        int frameBytes = Atrac3plus.frameBytesFromConfig(params), channels = Atrac3plus.channelsFromConfig(params);
        int frames = (oma.length - 96) / frameBytes;
        FloatBuffer ref = ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[1]))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        System.out.printf("%d frames of %d bytes, %d channel(s); reference %d samples%n", frames, frameBytes, channels, ref.limit() / channels);
        Atrac3plus d = new Atrac3plus(channels);
        double sig = 0, err = 0, maxErr = 0; int bad = 0, worstFrame = -1; double worstFrameErr = 0;
        for (int f = 0; f < frames; f++) {
            int r = d.decodeFloat(oma, 96 + f * frameBytes, frameBytes);
            if (r < 0) { bad++; System.out.println("frame " + f + ": error " + r); }
            double fe = 0;
            for (int i = 0; i < 2048; i++) for (int c = 0; c < channels; c++) {
                int at = (f * 2048 + i) * channels + c; if (at >= ref.limit()) continue;
                double a = d.output(c)[i], b = ref.get(at), e = a - b;
                sig += b * b; err += e * e; maxErr = Math.max(maxErr, Math.abs(e)); fe = Math.max(fe, Math.abs(e));
            }
            if (fe > worstFrameErr) { worstFrameErr = fe; worstFrame = f; }
        }
        System.out.printf("SNR %.1f dB, max error %.3g (%.2f LSB of 16-bit), worst frame %d, %d bad frames%n",
            10 * Math.log10(sig / Math.max(err, 1e-30)), maxErr, maxErr * 32768, worstFrame, bad);
        short[] pcm = new short[4096]; int reps = 20; long best = Long.MAX_VALUE;
        for (int rep = 0; rep < reps; rep++) {
            d.reset(); long t0 = System.nanoTime();
            for (int f = 0; f < frames; f++) d.decode(oma, 96 + f * frameBytes, frameBytes, pcm);
            best = Math.min(best, System.nanoTime() - t0);
        }
        System.out.printf("decode %.3f ms/frame (best of %d passes; a frame is %.1f ms of audio)%n", best / 1e6 / frames, reps, 2048 * 1000.0 / 44100);
        if (bad > 0 || 10 * Math.log10(sig / Math.max(err, 1e-30)) < 60) System.exit(1);
    }
}
