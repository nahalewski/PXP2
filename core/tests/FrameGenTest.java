import org.pxp2.core.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * Development tool: frame generation quality on real emulator output (psp.md "Frame generation").
 * <p>
 * Usage like FrameDump: {@code FrameGenTest <install> <storage> <outdir> <firstFrame> <lastFrame>} with -Dumd, -Dgame,
 * -Dpress, -Dscale as there.
 * <ul>
 * <li>Drop test (default): the game runs normally; every real frame R(i+1) is predicted from R(i) and R(i+2) at t = 0.5
 * (motion matched over two frames) and compared with the real one it replaces (PSNR, SSIM on luma). Baselines: repeating
 * R(i) (what the screen shows without frame generation) and a plain 50% blend of R(i) and R(i+2).</li>
 * <li>-Dsave=n,n,...: also writes side-by-side PNGs for those real-frame numbers: R(i) | generated | real R(i+1) |
 * R(i+2) | motion (hue = direction, brightness = speed; red = unknown).</li>
 * </ul>
 */
public final class FrameGenTest {
    static final class Shot { int[] px; int w, h; MotionVectors.Snapshot snap; int vblank; }

    public static void main(String[] args) throws Exception {
        File out = new File(args[2]); out.mkdirs();
        int first = Integer.parseInt(args[3]), last = Integer.parseInt(args[4]);
        Map<Integer, Integer> presses = new HashMap<>();
        for (String p : System.getProperty("press", "").split(",")) if (p.contains(":")) presses.put(Integer.parseInt(p.split(":")[0]), Integer.parseInt(p.split(":")[1], 16));
        Set<Integer> save = new HashSet<>(); for (String x : System.getProperty("save", "").split(",")) if (!x.isEmpty()) save.add(Integer.parseInt(x));
        int scale = Integer.getInteger("scale", 1), distance = Integer.getInteger("distance", 2);
        PspSystem psp = new PspSystem(new File(args[0], "flash0"), new File(args[1]), new File(args[1], "ms0"), PspSystem.DEFAULT_RAM_MB);
        if (System.getProperty("umd") != null) psp.insertUmd(IsoSource.open(System.getProperty("umd")));
        if (System.getProperty("game") != null) psp.gamePath = System.getProperty("game");
        int live = Integer.getInteger("live", -1); // -Dlive=1|2: FrameGen.offer with that mode (decision statistics only)
        FrameGen fg = new FrameGen(); fg.motion.matchDistance = live >= 0 ? 1 : distance; if (live >= 0) { fg.mode = live; fg.allowXmb = Boolean.getBoolean("xmb"); }
        Map<String, Integer> reasons = new TreeMap<>();
        List<Shot> shots = new ArrayList<>();
        double[] sum = new double[6]; int[] n = {0}; long[] interpNanos = {0}; int[] frame = {0}; int[] lastHash = {0};
        long[] counts = new long[5]; int[] badSaved = {0}; long[] fieldNanos = {0};
        StringBuilder csv = new StringBuilder("real,vblank,psnr_repeat,psnr_blend,psnr_fg,ssim_repeat,ssim_blend,ssim_fg,moving,unknown\n");
        while (psp != null) {
            PspSystem s = psp; s.renderer.setScale(scale);
            s.renderer.setMotion(fg.motion); s.system.onFrameBuf = fg.motion::flip;
            s.system.onVblank = () -> {
                int v = ++frame[0]; s.system.buttons = 0;
                for (Map.Entry<Integer, Integer> e : presses.entrySet()) if (v >= e.getKey() && v < e.getKey() + 10) s.system.buttons = e.getValue();
                if (v >= last) s.kernel.stopRequested = true;
                if (v < first) return;
                int[] px; int w = 480, h = 272;
                if (scale > 1) { px = s.renderer.frame(s.system.frameBuffer, s.system.frameWidth, s.system.pixelFormat, 1, false); w = s.renderer.frameWidth; h = s.renderer.frameHeight; }
                else px = s.system.snapshot();
                if (px == null) return;
                if (live >= 0) { // the frontend's path: FrameGen.offer decides per real frame
                    FrameGen.Plan plan = fg.offer(s, px, w, h);
                    if (plan != null) { reasons.merge(plan.generates() ? "generate k=" + plan.k : plan.reason, 1, Integer::sum); fieldNanos[0] += plan.fieldNanos; n[0]++; }
                    return;
                }
                int hash = Arrays.hashCode(Arrays.copyOf(px, w * h)); if (hash == lastHash[0] && !shots.isEmpty()) return; // repeated frame (30 fps games)
                lastHash[0] = hash;
                Shot sh = new Shot(); sh.px = Arrays.copyOf(px, w * h); sh.w = w; sh.h = h; sh.vblank = v;
                Memory m = s.kernel.memory; int[] off = new int[1];
                MotionVectors.Target t = m.valid(s.system.frameBuffer, 4) ? fg.motion.targetAt(m.backing(s.system.frameBuffer, off), off[0]) : null;
                sh.snap = fg.motion.capture(t, null);
                shots.add(sh); if (shots.size() > 3) shots.remove(0);
                if (shots.size() < 3) return;
                Shot A = shots.get(0), M = shots.get(1), B = shots.get(2);
                FrameGen.Plan p = new FrameGen.Plan(); p.a = real(A); p.b = real(B);
                long f0 = System.nanoTime();
                MotionVectors.Stats bs = fg.motion.field(B.snap, false, p.b.bx, p.b.by, p.b.bs);
                fg.motion.field(A.snap, true, p.a.fx, p.a.fy, p.a.fs);
                fieldNanos[0] += System.nanoTime() - f0;
                int[] gen = new int[w * h]; long t0 = System.nanoTime();
                fg.interpolate(p, 0.5f, gen, 1); interpNanos[0] += System.nanoTime() - t0;
                counts[0] += fg.both; counts[1] += fg.onlyA; counts[2] += fg.onlyB; counts[3] += fg.nearest; counts[4] += fg.occluded;
                int[] blend = new int[w * h]; for (int i = 0; i < w * h; i++) blend[i] = mix(A.px[i], B.px[i]);
                double pr = psnr(A.px, M.px), pb = psnr(blend, M.px), pf = psnr(gen, M.px);
                double sr = ssim(A.px, M.px, w, h), sb = ssim(blend, M.px, w, h), sf = ssim(gen, M.px, w, h);
                sum[0] += Math.min(pr, 60); sum[1] += Math.min(pb, 60); sum[2] += Math.min(pf, 60); sum[3] += sr; sum[4] += sb; sum[5] += sf; n[0]++;
                csv.append(String.format(Locale.ROOT, "%d,%d,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%d,%d%n", n[0], M.vblank, pr, pb, pf, sr, sb, sf, bs.moving, bs.unknown));
                if (Boolean.getBoolean("describe") && n[0] % 50 == 0) System.out.println("vblank " + v + " flips " + fg.motion.flips + " " + fg.motion.describe() + " unknown " + bs.unknown + " moving " + bs.moving);
                boolean bad = Boolean.getBoolean("savebad") && pf < Math.max(pr, pb) - 2 && badSaved[0]++ < 6;
                if (bad || save.contains(n[0]) || save.contains(-M.vblank)) write(out, String.format("fg%05d.png", M.vblank), w, h, A.px, gen, M.px, B.px, motionImage(p.b, w, h));
            };
            String r = s.bootVsh(3_000_000_000L);
            System.out.println("boot: " + r.split("\n")[0]);
            psp = frame[0] >= last ? null : s.restartIfRequested();
        }
        if (live >= 0) { System.out.printf(Locale.ROOT, "live mode %d: %d real frames, period %d vblanks, decisions %s, fields %.2f ms/frame%n", live, n[0], fg.period(), reasons, fieldNanos[0] / 1e6 / Math.max(1, n[0])); return; }
        java.nio.file.Files.writeString(new File(out, "quality.csv").toPath(), csv.toString());
        int k = Math.max(1, n[0]);
        System.out.printf(Locale.ROOT, "real frames compared: %d (motion over %d frames, scale %d)%n", n[0], distance, scale);
        System.out.printf(Locale.ROOT, "PSNR dB  repeat %.2f  blend %.2f  framegen %.2f%n", sum[0] / k, sum[1] / k, sum[2] / k);
        System.out.printf(Locale.ROOT, "SSIM     repeat %.4f  blend %.4f  framegen %.4f%n", sum[3] / k, sum[4] / k, sum[5] / k);
        long tot = Math.max(1, counts[0] + counts[1] + counts[2] + counts[3] + counts[4]);
        System.out.printf(Locale.ROOT, "pixels: both %.1f%%  A only %.1f%%  B only %.1f%%  occlusion pick %.1f%%  nearest %.1f%%%n",
            100.0 * counts[0] / tot, 100.0 * counts[1] / tot, 100.0 * counts[2] / tot, 100.0 * counts[4] / tot, 100.0 * counts[3] / tot);
        System.out.printf(Locale.ROOT, "motion fields (backward + forward) %.2f ms/frame%n", fieldNanos[0] / 1e6 / k);
        System.out.printf(Locale.ROOT, "CPU reference interpolation %.2f ms/frame (1 thread)%n", interpNanos[0] / 1e6 / k);
    }
    static FrameGen.Real real(Shot s) { FrameGen.Real r = new FrameGen.Real(); r.px = s.px; r.w = s.w; r.h = s.h; return r; }
    static int mix(int a, int b) { return 0xff000000 | (((a & 0xfefefe) >>> 1) + ((b & 0xfefefe) >>> 1) + (a & b & 0x010101)); }
    static double psnr(int[] a, int[] b) {
        double se = 0; int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) for (int sh = 0; sh < 24; sh += 8) { int d = ((a[i] >>> sh) & 255) - ((b[i] >>> sh) & 255); se += d * d; }
        double mse = se / (n * 3.0); return mse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
    }
    /** Mean SSIM on luma over 8×8 windows (stride 4), the standard constants. */
    static double ssim(int[] a, int[] b, int w, int h) {
        double c1 = 6.5025, c2 = 58.5225, sum = 0; int n = 0;
        for (int y = 0; y + 8 <= h; y += 4) for (int x = 0; x + 8 <= w; x += 4) {
            double ma = 0, mb = 0, va = 0, vb = 0, cov = 0;
            for (int j = 0; j < 8; j++) for (int i = 0; i < 8; i++) { double la = luma(a[(y + j) * w + x + i]), lb = luma(b[(y + j) * w + x + i]); ma += la; mb += lb; va += la * la; vb += lb * lb; cov += la * lb; }
            ma /= 64; mb /= 64; va = va / 64 - ma * ma; vb = vb / 64 - mb * mb; cov = cov / 64 - ma * mb;
            sum += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2)); n++;
        }
        return sum / Math.max(1, n);
    }
    static double luma(int c) { return 0.299 * ((c >>> 16) & 255) + 0.587 * ((c >>> 8) & 255) + 0.114 * (c & 255); }
    /** Motion field of B (backward) as an image at w×h: hue = direction, value = speed (8 px = full), red = unknown. */
    static int[] motionImage(FrameGen.Real b, int w, int h) {
        int[] o = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int gx = x * MotionVectors.W / w, gy = y * MotionVectors.H / h, i = gy * MotionVectors.W + gx;
            if (b.bs[i] == MotionVectors.UNKNOWN) { o[y * w + x] = 0xffc00000; continue; }
            float mx = b.bx[i], my = b.by[i], sp = (float)Math.min(1, Math.hypot(mx, my) / 8);
            o[y * w + x] = b.bs[i] == MotionVectors.STATIC ? 0xff202020 : sp < 0.01f ? 0xff404040 : java.awt.Color.HSBtoRGB((float)(Math.atan2(my, mx) / (2 * Math.PI)), 0.8f, 0.25f + 0.75f * sp);
        }
        return o;
    }
    static void write(File dir, String name, int w, int h, int[]... panels) {
        BufferedImage img = new BufferedImage(w * panels.length, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < panels.length; i++) img.setRGB(i * w, 0, w, h, panels[i], 0, w);
        try { javax.imageio.ImageIO.write(img, "png", new File(dir, name)); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
