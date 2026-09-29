import org.pxp2.core.*;
import java.io.File;

/** Development tool: boots the VSH, presses a button mask at a frame, and prints the kernel log and registry/IO calls. */
public final class PressBoot {
    public static void main(String[] a) throws Exception {
        PspSystem psp = new PspSystem(new File(a[0], "flash0"), new File(a[1]));
        int frame = Integer.parseInt(a[2]), mask = Integer.parseInt(a[3], 16);
        psp.system.onVblank = () -> psp.system.buttons = psp.system.vcount >= frame && psp.system.vcount < frame + 10 ? mask : 0;
        System.out.println(psp.bootVsh(Long.parseLong(a[4])));
        for (String l : psp.kernel.log) if (!l.contains("alarm_")) System.out.println(l);
        if (psp.vsh.rebootArgs != null) { StringBuilder h = new StringBuilder(); for (byte b : psp.vsh.rebootArgs) h.append(String.format("%02x", b)); System.out.println("reboot args: " + h); }
        if (psp.vsh.rebootParam != null) { StringBuilder h = new StringBuilder(); for (byte b : psp.vsh.rebootParam) h.append(String.format("%02x", b)); System.out.println("reboot param: " + h); }
        for (String l : psp.kernel.callTrace) if (l.startsWith("sceReg") || l.startsWith("sceIo")) System.out.println(l);
    }
}
