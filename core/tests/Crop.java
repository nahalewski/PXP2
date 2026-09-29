import java.awt.image.BufferedImage; import java.io.File; import javax.imageio.ImageIO;
/** Development tool: crops a PNG. args: in out x y w h */
public final class Crop { public static void main(String[] a) throws Exception { BufferedImage i = ImageIO.read(new File(a[0])); ImageIO.write(i.getSubimage(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5])), "png", new File(a[1])); } }
