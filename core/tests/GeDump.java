import org.pxp2.core.*;
import java.io.File;
import java.util.*;

/** Development tool: boots the VSH and prints a GE command histogram and the framebuffer state after N instructions. */
public final class GeDump {
    public static void main(String[] args) throws Exception {
        PspSystem psp = new PspSystem(new File(args[0], "flash0"), new File(args[1]));
        long[] counts = new long[256];
        psp.ge.renderer = (ge, cmd, data, at) -> counts[cmd]++;
        psp.bootVsh(Long.parseLong(args[2]));
        for (int i = 0; i < 256; i++) if (counts[i] > 0) System.out.printf("cmd %02x: %d%n", i, counts[i]);
        System.out.printf("fb=%08x width=%d format=%d%n", psp.system.frameBuffer, psp.system.frameWidth, psp.system.pixelFormat);
        StringBuilder regs = new StringBuilder();
        for (int i = 0; i < 256; i++) if (psp.ge.registers[i] != 0) regs.append(String.format("%02x=%06x ", i, psp.ge.registers[i] & 0xffffff));
        System.out.println(regs);
    }
}
