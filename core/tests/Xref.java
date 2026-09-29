import org.pxp2.core.*;
import java.nio.file.*;
/** Development tool: finds lui/addiu pairs that build a given address. args: prx base (hex) string-or-hexaddr */
public final class Xref {
    public static void main(String[] a) throws Exception {
        byte[] file = Files.readAllBytes(Paths.get(a[0])); Memory m = new Memory(); int base = (int)Long.parseLong(a[1], 16);
        PrxLoader.Module mod = PrxLoader.load(file, m, base);
        int target;
        if (a[2].startsWith("0x")) target = (int)Long.parseLong(a[2].substring(2), 16);
        else { target = -1; byte[] s = a[2].getBytes(); outer: for (int p = base; p < base + mod.size - s.length; p++) { for (int i = 0; i < s.length; i++) if (m.read8(p + i) != (s[i] & 255)) continue outer; if (m.read8(p + s.length) == 0 && (p == base || m.read8(p - 1) == 0)) { target = p; break; } } }
        System.out.printf("target %08x%n", target);
        int hi = (target + 0x8000) >>> 16, lo = (short)target;
        for (int p = base; p < base + mod.size; p += 4) {
            int w = m.read32(p);
            if ((w >>> 26) == 15 && (w & 0xffff) == hi) {
                int reg = (w >>> 16) & 31;
                for (int q = p + 4; q < p + 64 && q < base + mod.size; q += 4) {
                    int x = m.read32(q);
                    if (((x >>> 26) == 9) && ((x >>> 21) & 31) == reg && (short)x == lo) System.out.printf("%08x (lui at %08x)%n", q, p);
                }
            }
        }
    }
}
