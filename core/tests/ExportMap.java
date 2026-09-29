import org.pxp2.core.*;
import java.nio.file.*;
import java.util.*;

/** Development tool: for each export of a firmware module, lists the imported functions its code calls. */
public final class ExportMap {
    public static void main(String[] args) throws Exception {
        Memory m = new Memory(); PrxLoader.Module mod = PrxLoader.load(Files.readAllBytes(Paths.get(args[0])), m, 0x08800000);
        PspSystem psp = new PspSystem(Paths.get(args[0]).getParent().toFile(), Files.createTempDirectory("pxp2-map").toFile());
        Map<Integer, String> stubs = new HashMap<>();
        for (PrxLoader.Symbol s : mod.imports) stubs.put(s.address, psp.kernel.describe(s.library, s.nid));
        String filter = args.length > 1 && !args[1].equals("ALL") ? args[1] : null;
        for (PrxLoader.Symbol e : mod.exports) {
            if (filter != null && !e.library.equals(filter)) continue;
            List<String> calls = new ArrayList<>(); int pc = e.address;
            for (int i = 0; i < 400; i++, pc += 4) {
                int w = m.read32(pc);
                if ((w >>> 26) == 3) { int t = ((pc + 4) & 0xf0000000) | ((w & 0x03ffffff) << 2); calls.add(stubs.getOrDefault(t, String.format("local %08x", t))); }
                if ((w >>> 26) == 2) { int t = ((pc + 4) & 0xf0000000) | ((w & 0x03ffffff) << 2); if (stubs.containsKey(t)) { calls.add("tail " + stubs.get(t)); break; } }
                if (w == 0x03e00008) break;
            }
            System.out.printf("%s:%08x @%08x -> %s%n", e.library, e.nid, e.address, calls);
            if (args.length > 2) System.out.print(Disasm.range(m, e.address, Integer.parseInt(args[2])));
        }
    }
}
