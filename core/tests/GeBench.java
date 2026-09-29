import org.pxp2.core.*;
import java.io.File;
/** Development tool: GE flush (rasterisation) time per steady-state XMB frame. */
public final class GeBench {
    public static void main(String[] a) throws Exception {
        PspSystem psp = new PspSystem(new File(a[0], "flash0"), new File(a[1])); int frames = Integer.parseInt(a[2]); int[] n = {0}; long[] b = new long[5];
        psp.system.onVblank = () -> {
            n[0]++;
            if (n[0] == 400) { b[0] = psp.renderer.flushNanos; b[1] = psp.renderer.flushes; b[2] = System.nanoTime(); b[3] = psp.renderer.pixels; b[4] = psp.ge.nanos; }
            if (n[0] == 400 + frames) { psp.kernel.stopRequested = true;
                System.out.printf("ge lists %.3f ms/frame, flush %.3f ms/frame, %.1f flushes/frame, total %.2f ms/frame, %d pixels/frame%n", (psp.ge.nanos - b[4]) / 1e6 / frames, (psp.renderer.flushNanos - b[0]) / 1e6 / frames,
                    (psp.renderer.flushes - b[1]) / (double)frames, (System.nanoTime() - b[2]) / 1e6 / frames, (psp.renderer.pixels - b[3]) / frames); }
        };
        psp.bootVsh(5_000_000_000L);
    }
}
