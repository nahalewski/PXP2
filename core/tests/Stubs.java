import org.pxp2.core.*;
import java.nio.file.*;
/** Development tool: lists a module's import stub addresses with names. args: prx base(hex) */
public final class Stubs {
    public static void main(String[] a) throws Exception {
        Memory m = new Memory(); PrxLoader.Module mod = PrxLoader.load(Files.readAllBytes(Paths.get(a[0])), m, (int)Long.parseLong(a[1], 16));
        PspSystem psp = new PspSystem(Paths.get(a[0]).getParent().toFile(), Files.createTempDirectory("pxp2-stubs").toFile());
        for (PrxLoader.Symbol s : mod.imports) System.out.printf("%08x %s%n", s.address, psp.kernel.describe(s.library, s.nid));
    }
}
