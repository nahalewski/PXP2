import java.awt.image.BufferedImage;
import java.io.File;
/** Development tool: pixel-exact comparison of same-named PNGs in two directories. */
public final class PngDiff {
    public static void main(String[] a) throws Exception {
        for (File f : new File(a[0]).listFiles()) {
            BufferedImage x = javax.imageio.ImageIO.read(f), y = javax.imageio.ImageIO.read(new File(a[1], f.getName()));
            int diff = 0, max = 0, x0 = 9999, y0 = 9999, x1 = -1, y1 = -1;
            for (int j = 0; j < x.getHeight(); j++) for (int i = 0; i < x.getWidth(); i++) {
                int p = x.getRGB(i, j), q = y.getRGB(i, j); if (p == q) continue; diff++;
                x0 = Math.min(x0, i); x1 = Math.max(x1, i); y0 = Math.min(y0, j); y1 = Math.max(y1, j);
                for (int s = 0; s < 24; s += 8) max = Math.max(max, Math.abs(((p >> s) & 255) - ((q >> s) & 255)));
            }
            System.out.printf("%s: %d differing pixels, max channel delta %d%s%n", f.getName(), diff, max, diff > 0 ? String.format(" in (%d,%d)-(%d,%d)", x0, y0, x1, y1) : "");
        }
    }
}
