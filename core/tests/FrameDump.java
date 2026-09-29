import org.pxp2.core.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * Development tool: boots the VSH (following VSH restarts) and writes the displayed framebuffer to PNG at the given
 * global vblank counts. -Dpress=frame:mask,... holds a PSP button mask for 10 frames (O=2000, X=4000, UP=10,
 * RIGHT=20, DOWN=40, LEFT=80, START=8, SELECT=1, L=100, R=200, TRIANGLE=1000, SQUARE=8000). -Dvideo=ffmpeg decodes
 * video with an ffmpeg subprocess (FfmpegVideoDecoder).
 * Library (virtual XMB entries): -Disodirs=dir;dir adds the local ISOs of those folders, -Dhub=http://host:port adds the
 * hub's /api/catalog, -Dlibcache=dir sets the metadata cache, -Dlibwait=seconds waits for the scan first (default 120).
 * -Dumd also accepts an http:// URL (streamed with Range requests). -Dram=32|64|128 sets the RAM size (default 64 MiB).
 * Enhancements: -Dscale=N renders at Nx internal resolution and writes the high-resolution frame (480N x 272N);
 * -Dssaa=2 supersamples (renders at scale·2, writes scale); -Dsmooth=true applies edge smoothing; -Dtexfilter=1 forces
 * bilinear texture filtering, 2 the enhanced (Scale2x-style) texture upscale. -Dfrom=frame starts a timing window that
 * is reported (host and GE ms per frame) at the last frame. -Dhomeat=frame:yes|no,... simulates the HOME menu at that
 * frame (as if HOME was pressed and the choice made): yes quits the game the way the PSP does (PspSystem.homeQuit).
 */
public final class FrameDump {
    public static void main(String[] args) throws Exception {
        File out = new File(args[2]); out.mkdirs();
        Set<Integer> wanted = new TreeSet<>(); for (int i = 3; i < args.length; i++) wanted.add(Integer.parseInt(args[i]));
        int last = Collections.max(wanted);
        Map<Integer, Integer> presses = new HashMap<>();
        for (String p : System.getProperty("press", "").split(",")) if (p.contains(":")) presses.put(Integer.parseInt(p.split(":")[0]), Integer.parseInt(p.split(":")[1], 16));
        long t0 = System.nanoTime(); int[] frame = {0};
        final float wide = Float.parseFloat(System.getProperty("wide", "1")); // -Dwide=1.27 renders true widescreen
        final int scale = Integer.getInteger("scale", 1), ssaa = Integer.getInteger("ssaa", 1), texfilter = Integer.getInteger("texfilter", 0);
        final boolean smooth = Boolean.getBoolean("smooth"), enhanced = scale > 1 || ssaa > 1 || smooth;
        final long[] timing = {0, 0, 0}; final int from = Integer.getInteger("from", 300);
        final java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        final org.pxp2.core.AudioMixer.Sink wav = (stereo, frames) -> { for (int i = 0; i < frames * 2; i++) { pcm.write(stereo[i] & 255); pcm.write(stereo[i] >> 8 & 255); } };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (System.getProperty("wav") == null) return;
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(System.getProperty("wav"), "rw")) {
                byte[] d = pcm.toByteArray(); java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                h.put("RIFF".getBytes()).putInt(36 + d.length).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)2)
                 .putInt(44100).putInt(44100 * 4).putShort((short)4).putShort((short)16).put("data".getBytes()).putInt(d.length);
                f.setLength(0); f.write(h.array()); f.write(d); System.out.println("wav: " + d.length / 4 + " frames");
            } catch (Exception e) { System.out.println("wav failed: " + e); }
        }));
        if (System.getProperty("libcache") != null) PspSystem.libraryCache = new File(System.getProperty("libcache"));
        List<LibraryEntry> library = new ArrayList<>();
        for (String d : System.getProperty("isodirs", "").split(";")) if (!d.isEmpty()) library.addAll(GameLibrary.localIsos(new File(d)));
        String hub = System.getProperty("hub");
        if (hub != null) {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)new java.net.URL(hub + "/api/catalog").openConnection();
            String json = new String(c.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            List<LibraryEntry> fromHub = GameLibrary.parseCatalog(hub, json); library.addAll(fromHub);
            System.out.println("hub catalog: " + fromHub.size() + " library entries");
        }
        if (System.getProperty("libfilter") != null) library.removeIf(e -> !e.fileName.matches("(?i).*(" + System.getProperty("libfilter") + ").*")); // -Dlibfilter=regex on file names
        if (!library.isEmpty()) PspSystem.setLibrary(library);
        // -Dram=32|64|128 main RAM in MiB (default 64, PSP-2000/3000/Go); -Dmodel=n overrides the reported hardware model
        // (experiments only: the 01g firmware is a PSP-1000 image); -Dmem=true prints the partition report after each boot.
        PspSystem psp = new PspSystem(new File(args[0], "flash0"), new File(args[1]), new File(args[1], "ms0"), Integer.getInteger("ram", PspSystem.DEFAULT_RAM_MB));
        System.out.println("RAM " + psp.ramMegabytes + " MiB");
        if (!library.isEmpty()) { // wait for the metadata scan so the Game column is deterministic
            long until = System.nanoTime() + Long.getLong("libwait", 120) * 1_000_000_000L; long t1 = System.nanoTime();
            while (System.nanoTime() < until && GameLibrary.shared().items().stream().anyMatch(i -> i.state == GameLibrary.State.PENDING)) Thread.sleep(50);
            System.out.printf("library scan %.1fs:%n", (System.nanoTime() - t1) / 1e9);
            for (GameLibrary.Item i : GameLibrary.shared().items()) System.out.println("  " + i + (i.reason != null ? " (" + i.reason + ")" : ""));
        }
        if (System.getProperty("umd") != null) psp.insertUmd(IsoSource.open(System.getProperty("umd"))); // -Dumd=image.iso (or http URL) inserts a disc
        if (System.getProperty("game") != null) psp.gamePath = System.getProperty("game"); // -Dgame=disc0:/PSP_GAME/SYSDIR/BOOT.BIN boots the disc directly (no XMB)
        while (psp != null) {
            PspSystem s = psp; s.renderer.widescreen = wide; s.renderer.setScale(scale * ssaa); s.renderer.textureFilter = texfilter; s.kernel.model = Integer.getInteger("model", s.kernel.model);
            if ("ffmpeg".equals(System.getProperty("video"))) s.video.decoder = new FfmpegVideoDecoder(); // -Dvideo=ffmpeg decodes H.264 with ffmpeg
            if (System.getProperty("wav") != null) s.media.mixer.sink = wav; // -Dwav=out.wav records the mixed audio (44.1 kHz stereo)
            if (System.getProperty("trace") != null) s.kernel.liveTrace = java.util.regex.Pattern.compile(System.getProperty("trace")); // -Dtrace=regex prints matching HLE calls live
            for (String w : System.getProperty("watch", "").split(",")) if (!w.isEmpty()) s.kernel.watch.add((int)Long.parseLong(w, 16)); // -Dwatch=pc,pc
            s.system.onVblank = () -> {
                int v = ++frame[0]; s.system.buttons = 0;
                if (Integer.getInteger("getrace", -1) == v) GeRenderer.trace = new java.util.HashSet<>(); else if (Integer.getInteger("getrace", -1) == v - 3) GeRenderer.trace = null; // -Dgetrace=frame
                for (String sw : System.getProperty("swap", "").split(";")) { // -Dswap=frame:image.iso;frame:none hot-swaps discs
                    int colon = sw.indexOf(':'); if (colon <= 0 || Integer.parseInt(sw.substring(0, colon)) != v) continue;
                    String img = sw.substring(colon + 1); System.out.println("frame " + v + ": swap UMD -> " + img);
                    s.swapUmd(img.equals("none") ? null : new File(img));
                }
                for (Map.Entry<Integer, Integer> e : presses.entrySet()) if (v >= e.getKey() && v < e.getKey() + 10) s.system.buttons = e.getValue();
                String mark = System.getProperty("mark"); // -Dmark=frame:file creates the file at that frame (lets a test script act, e.g. stop the hub)
                if (mark != null && Integer.parseInt(mark.substring(0, mark.indexOf(':'))) == v) {
                    try { new File(mark.substring(mark.indexOf(':') + 1)).createNewFile(); Thread.sleep(1500); } catch (Exception ignored) { }
                }
                for (String h : System.getProperty("homeat", "").split(",")) { // -Dhomeat=frame:yes|no simulates the HOME menu choice
                    int colon = h.indexOf(':'); if (colon <= 0 || Integer.parseInt(h.substring(0, colon)) != v) continue;
                    boolean yes = h.substring(colon + 1).equalsIgnoreCase("yes");
                    if (!s.inGame()) System.out.println("frame " + v + ": HOME menu: in the XMB, HOME goes to the VSH (no menu)");
                    else if (!yes) System.out.println("frame " + v + ": HOME menu: No, game resumes");
                    else System.out.println("frame " + v + ": HOME menu: Yes -> " + s.homeQuit() + " (exit callback " + Integer.toHexString(s.gameLib.exitCallback) + ", t=" + s.kernel.now() / 1000 + " ms)");
                }
                if (v == from) { timing[0] = s.ge.nanos; timing[1] = System.nanoTime(); timing[2] = s.renderer.resolveNanos; }
                if (v == last && v > from && timing[1] != 0) System.out.printf("timing frames %d-%d: host %.2f ms/frame, ge %.2f ms/frame, resolve %.2f ms/frame (render scale %d)%n", from, v,
                    (System.nanoTime() - timing[1]) / 1e6 / (v - from), (s.ge.nanos - timing[0]) / 1e6 / (v - from), (s.renderer.resolveNanos - timing[2]) / 1e6 / (v - from), s.renderer.scale());
                if (v >= last) s.kernel.stopRequested = true;
                if (!wanted.contains(v)) return;
                int[] px; int fw = 480, fh = 272;
                if (enhanced) { // the frontend's path: the displayed buffer at render resolution
                    px = s.renderer.frame(s.system.frameBuffer, s.system.frameWidth, s.system.pixelFormat, ssaa, smooth);
                    fw = s.renderer.frameWidth; fh = s.renderer.frameHeight;
                } else px = s.system.snapshot();
                if (px == null) { System.out.println("frame " + v + ": no framebuffer"); return; }
                BufferedImage img = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_RGB); img.setRGB(0, 0, fw, fh, px, 0, fw);
                if (wide > 1) { // present anamorphic widescreen frames stretched, like the Android frontend
                    BufferedImage w = new BufferedImage(Math.round(fw * wide), fh, BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = w.createGraphics(); g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(img, 0, 0, w.getWidth(), fh, null); g.dispose(); img = w;
                }
                try { javax.imageio.ImageIO.write(img, "png", new File(out, String.format("frame%05d.png", v))); } catch (Exception e) { throw new RuntimeException(e); }
                System.out.printf("frame %d (boot %d) tri=%d spr=%d%s%n", v, s.restarts, s.renderer.triangles, s.renderer.sprites,
                    s.video.accessUnits > 0 ? " video au=" + s.video.accessUnits + " pictures=" + s.video.pictures : "");
            };
            String r = s.bootVsh(3_000_000_000L);
            System.out.println("boot " + s.restarts + ": " + r.split("\n")[0] + (s.gameLib.exitRequested ? " (game exit to the XMB at t=" + s.kernel.now() / 1000 + " ms)" : ""));
            if (!r.startsWith("Stopped by host")) { System.out.println(r); System.out.println(s.kernel.sysmem.report() + s.kernel.sysmem.blocks(2, 15) + s.kernel.sysmem.blocks(5, 8)); } // full stop report
            for (String d : System.getProperty("dis", "").split(",")) if (d.contains(":")) // -Ddis=addr:count disassembles guest memory at the stop
                System.out.print(Disasm.range(s.kernel.memory, (int)Long.parseLong(d.split(":")[0], 16), Integer.parseInt(d.split(":")[1])));
            String filter = System.getProperty("log"); // -Dlog=regex prints matching kernel log lines and recent HLE calls
            if (filter != null) {
                for (String l : s.kernel.log) if (l.matches(".*(" + filter + ").*")) System.out.println("  " + l);
                for (String l : s.kernel.callTrace) if (l.matches(".*(" + filter + ").*")) System.out.println("  call " + l);
            }
            if (Boolean.getBoolean("mem")) System.out.printf("memory (boot %d): TotalFreeMemSize %d KiB, MaxFreeMemSize %d KiB%n%s", s.restarts,
                s.kernel.sysmem.totalFree(2) / 1024, s.kernel.sysmem.maxFree(2) / 1024, s.kernel.sysmem.report());
            if (s.video.accessUnits > 0) System.out.printf("video: %d access units, %d pictures (%d black)%n", s.video.accessUnits, s.video.pictures, s.video.blackPictures);
            if (s.io.umd != null && s.io.umd.source() instanceof HttpIsoSource) System.out.println("stream: " + s.io.umd.source());
            if (s.vsh.hostMessage != null) System.out.println("host message: " + s.vsh.hostMessage);
            if (Boolean.getBoolean("stdout") && s.io.stdout.length() > 0) System.out.println("guest stdout:\n" + s.io.stdout); // -Dstdout=true prints what the guest wrote to fds 1/2
            psp = frame[0] >= last ? null : s.restartIfRequested();
        }
        for (String l : GameLibrary.shared().log()) System.out.println(l);
        System.out.printf("host %.1fs%n", (System.nanoTime() - t0) / 1e9);
    }
}
