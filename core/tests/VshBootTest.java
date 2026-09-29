import org.pxp2.core.*;
import java.io.File;
public final class VshBootTest {
    public static void main(String[] args) throws Exception {
        long budget = args.length > 2 ? Long.parseLong(args[2]) : 50_000_000L;
        long t0 = System.nanoTime();
        String report = PspSystem.diagnose(new File(args[0]), new File(args[1]), budget, args.length > 3 ? args[3] : "");
        System.out.println(report.length() > 12000 ? report.substring(0, 12000) + "\n..." : report);
        System.out.printf("Host time %.2f s%n", (System.nanoTime() - t0) / 1e9);
    }
}
