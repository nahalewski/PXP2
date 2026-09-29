import org.pxp2.core.*;
import java.nio.file.*;
/** Development tool: prints NUL-terminated strings of a module loaded at a base. args: prx base addr... */
public final class Str {
    public static void main(String[] a) throws Exception {
        Memory m = new Memory(); PrxLoader.load(Files.readAllBytes(Paths.get(a[0])), m, (int)Long.parseLong(a[1], 16));
        for (int i = 2; i < a.length; i++) { int p = (int)Long.parseLong(a[i], 16); System.out.println(a[i] + " " + m.string(p, 128)); }
    }
}
