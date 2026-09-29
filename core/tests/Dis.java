import org.pxp2.core.*;
import java.nio.file.*;
/** Development tool: disassemble a firmware module loaded at a given base. args: prx base start count */
public final class Dis {
    public static void main(String[] a) throws Exception {
        Memory m = new Memory(); PrxLoader.Module mod = PrxLoader.load(Files.readAllBytes(Paths.get(a[0])), m, (int)Long.parseLong(a[1], 16));
        System.out.print(Disasm.range(m, (int)Long.parseLong(a[2], 16), Integer.parseInt(a[3])));
    }
}
