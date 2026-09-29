import org.pxp2.core.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** SEPLUGINS configuration parsing/editing and SystemControl NID checks; needs no firmware. */
public final class PluginsTest {
    static int failures;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; }

    public static void main(String[] args) throws Exception {
        // NIDs are SHA-1 hashes of the names; these values are the ones in the PSPSDK stub libraries.
        check(HleSystemCtrl.hashNid("sctrlHENFindFunction") == 0x159af5cc, "sctrlHENFindFunction NID 159af5cc");
        check(HleSystemCtrl.hashNid("sctrlHENSetStartModuleHandler") == 0x1c90becb, "sctrlHENSetStartModuleHandler NID 1c90becb");
        check(HleSystemCtrl.hashNid("sctrlSEGetConfig") == 0x16c3b7ee, "sctrlSEGetConfig NID 16c3b7ee");
        check(HleSystemCtrl.hashNid("kuKernelGetModel") == 0x24331850, "kuKernelGetModel NID 24331850");

        File ms0 = Files.createTempDirectory("pxp2-plugins").toFile();
        File dir = new File(ms0, "seplugins"); dir.mkdirs();
        Files.write(new File(dir, "VSH.TXT").toPath(), "# comment\r\nms0:/seplugins/a.prx 1\r\nms0:/SEPLUGINS/b.prx 0\r\nms0:/seplugins/with space.prx\r\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "plugins.txt").toPath(), "vsh, ms0:/SEPLUGINS/c.prx, on\ngame, ms0:/SEPLUGINS/c.prx, off\nULUS10081, ms0:/SEPLUGINS/d.prx, on\n; x\n".getBytes(StandardCharsets.UTF_8));
        List<Plugins.Entry> config = Plugins.readConfig(ms0);
        for (Plugins.Entry e : config) System.out.println("     " + e);
        check(config.size() == 6, "6 entries parsed (plugins.txt first, then vsh.txt)");
        check(config.get(0).path.equals("ms0:/SEPLUGINS/c.prx") && config.get(0).enabled && config.get(0).mode.equals("vsh"), "ARK line parsed");
        check(config.get(2).mode.equals("ulus10081"), "game-ID line parsed");
        check(Plugins.isEnabled(ms0, "vsh", "ms0:/SEPLUGINS/A.PRX"), "a.prx enabled (case-insensitive path)");
        check(!Plugins.isEnabled(ms0, "vsh", "ms0:/seplugins/b.prx"), "b.prx disabled");
        check(Plugins.isEnabled(ms0, "vsh", "ms0:/seplugins/with space.prx"), "line without flag counts as enabled");

        Plugins.setEnabled(ms0, "vsh", "ms0:/seplugins/b.prx", true);
        Plugins.setEnabled(ms0, "game", "ms0:/SEPLUGINS/c.prx", true);
        Plugins.setEnabled(ms0, "game", "ms0:/seplugins/e.prx", true);
        Plugins.setEnabled(ms0, "vsh", "ms0:/seplugins/a.prx", false);
        check(Plugins.isEnabled(ms0, "vsh", "ms0:/seplugins/b.prx"), "b.prx enabled in place");
        check(Plugins.isEnabled(ms0, "game", "ms0:/seplugins/c.prx"), "plugins.txt line switched on");
        check(Plugins.isEnabled(ms0, "game", "ms0:/seplugins/e.prx"), "new game.txt line added");
        check(!Plugins.isEnabled(ms0, "vsh", "ms0:/seplugins/a.prx"), "a.prx disabled");
        String vsh = new String(Files.readAllBytes(new File(dir, "VSH.TXT").toPath()), StandardCharsets.UTF_8);
        check(vsh.startsWith("# comment\r\n"), "comments kept");
        String ark = new String(Files.readAllBytes(new File(dir, "plugins.txt").toPath()), StandardCharsets.UTF_8);
        check(ark.contains("game, ms0:/SEPLUGINS/c.prx, on") && ark.contains("; x"), "plugins.txt keeps its format");
        check(new File(dir, "game.txt").isFile(), "game.txt created");
        Files.write(new File(dir, "e.prx").toPath(), new byte[0]);
        check(Plugins.discover(ms0).size() == 1, "discover finds the PRX");
        System.out.println(failures == 0 ? "PluginsTest passed" : "PluginsTest: " + failures + " failures");
        if (failures != 0) System.exit(1);
    }
}
