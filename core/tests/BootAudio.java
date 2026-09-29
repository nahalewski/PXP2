import org.pxp2.core.*;
import java.io.*;

/**
 * Development tool: boots the VSH for the given number of vblanks and writes everything the audio mixer outputs
 * (44.1 kHz stereo, emulated time) to a WAV file. Usage: BootAudio flashRoot storage out.wav [vblanks=300]
 */
public final class BootAudio {
    public static void main(String[] args) throws Exception {
        int last = args.length > 3 ? Integer.parseInt(args[3]) : 300;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        PspSystem s = new PspSystem(new File(args[0], "flash0"), new File(args[1]));
        s.media.mixer.sink = (stereo, frames) -> { for (int i = 0; i < frames * 2; i++) { pcm.write(stereo[i]); pcm.write(stereo[i] >> 8); } };
        int[] vblanks = {0};
        s.system.onVblank = () -> { if (++vblanks[0] >= last) s.kernel.stopRequested = true; };
        System.out.println(s.bootVsh(3_000_000_000L).split("\n")[0]);
        byte[] d = pcm.toByteArray(); long sum = 0; int peak = 0;
        for (int i = 0; i + 1 < d.length; i += 2) { int v = (short)((d[i] & 255) | d[i + 1] << 8); sum += (long)v * v; peak = Math.max(peak, Math.abs(v)); }
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(args[2]))) {
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + d.length)); o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short)1)); o.writeShort(Short.reverseBytes((short)2));
            o.writeInt(Integer.reverseBytes(44100)); o.writeInt(Integer.reverseBytes(44100 * 4)); o.writeShort(Short.reverseBytes((short)4));
            o.writeShort(Short.reverseBytes((short)16)); o.writeBytes("data"); o.writeInt(Integer.reverseBytes(d.length)); o.write(d);
        }
        System.out.printf("%.2f s of audio, RMS %.0f, peak %d%n", d.length / 4 / 44100.0, Math.sqrt(sum / Math.max(1.0, d.length / 2)), peak);
    }
}
