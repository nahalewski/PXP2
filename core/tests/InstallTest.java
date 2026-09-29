import org.pxp2.core.*;
import java.io.File;
public final class InstallTest {
    public static void main(String[] args)throws Exception {
        System.out.println(FirmwareInstaller.install(new File(args[0]),new File(args[1]),System.out::println));
    }
}
