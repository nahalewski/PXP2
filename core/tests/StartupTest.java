import org.pxp2.core.*;
import java.io.File;
public final class StartupTest {
    public static void main(String[] args)throws Exception { System.out.println(FirmwareExecution.diagnose(new File(args[0]))); }
}
