import org.pxp2.core.*;
import java.io.File;
/** Development tool: pixels, triangles and sprites drawn per frame at steady state. */
public final class PixelStats {
    public static void main(String[] a) throws Exception {
        PspSystem psp = new PspSystem(new File(a[0], "flash0"), new File(a[1])); int[] n = {0}; long[] base = new long[3];
        psp.system.onVblank = () -> {
            n[0]++;
            if (n[0] >= 900 && n[0] < 905) System.out.printf("frame %d: %d pixels, %d triangles, %d sprites, %d textures decoded%n", n[0], psp.renderer.pixels - base[0], psp.renderer.triangles - base[1], psp.renderer.sprites - base[2], psp.renderer.texturesDecoded);
            base[0] = psp.renderer.pixels; base[1] = psp.renderer.triangles; base[2] = psp.renderer.sprites;
            if (n[0] == 905) psp.kernel.stopRequested = true;
        };
        psp.bootVsh(5_000_000_000L);
    }
}
