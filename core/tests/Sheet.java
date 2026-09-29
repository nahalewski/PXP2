import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
/** Development tool: combines frame PNGs into one contact sheet (half size, 3 columns). */
public final class Sheet {
    public static void main(String[] a) throws Exception {
        File[] files = new File(a[0]).listFiles((d, n) -> n.startsWith("frame")); java.util.Arrays.sort(files);
        int cols = 3, w = 240, h = 136, rows = (files.length + cols - 1) / cols;
        BufferedImage out = new BufferedImage(cols * w, rows * h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < files.length; i++) out.getGraphics().drawImage(ImageIO.read(files[i]).getScaledInstance(w, h, 4), (i % cols) * w, (i / cols) * h, null);
        ImageIO.write(out, "png", new File(a[1]));
    }
}
