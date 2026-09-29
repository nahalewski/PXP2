import org.pxp2.core.*;
import java.io.File;
/** Development tool: measures host time per emulated frame with and without the software renderer. */
public final class Bench {
    public static void main(String[] a) throws Exception {
        for (boolean render : new boolean[]{false, true}) {
            PspSystem psp = new PspSystem(new File(a[0], "flash0"), new File(a[1]));
            if (!render) psp.ge.renderer = null;
            int frames = Integer.parseInt(a[2]); long[] t = new long[2]; int[] n = {0};
            psp.system.onVblank = () -> { n[0]++; if (n[0] == 400) t[0] = System.nanoTime(); if (n[0] == 400 + frames) psp.kernel.stopRequested = true; };
            psp.bootVsh(5_000_000_000L); t[1] = System.nanoTime();
            System.out.printf("renderer=%s: %.2f ms/frame (%.0f fps) over %d frames, %d instructions total%n", render, (t[1] - t[0]) / 1e6 / frames, frames * 1e9 / (t[1] - t[0]), frames, psp.kernel.instructions());
        }
    }
}
