import java.io.File;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Random;

/**
 * Development tool: differential fuzzer for the GE renderer. Loads two builds of org.pxp2.core (e.g. a reference and a
 * modified renderer) in separate class loaders, feeds both the same random register states and primitives, and compares
 * VRAM after every flush. Primitives are flushed in groups of at most 7 so the reference renders them in a single band.
 * Usage: GeFuzz referenceClassDir candidateClassDir [cases] [seed]
 * Robustness mode: -Dscale=N and/or -Dtexfilter=1|2 set the candidate's internal resolution and texture filter. Its VRAM
 * then legitimately differs (downsampled high-resolution drawing, filtered texels); the run checks that nothing throws
 * and reports how many bytes differ instead of failing.
 */
public final class GeFuzz {
    static final class R {
        final Object mem, ge, ren; final int[] regs; final Method cmd, flush, w8, w32; final byte[] vram;
        R(String dir) throws Exception {
            ClassLoader l = new URLClassLoader(new URL[]{new File(dir).toURI().toURL()}, ClassLoader.getPlatformClassLoader());
            Class<?> mc = l.loadClass("org.pxp2.core.Memory"), gc = l.loadClass("org.pxp2.core.Ge"), rc = l.loadClass("org.pxp2.core.GeRenderer");
            mem = mc.getConstructor().newInstance();
            Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); uf.setAccessible(true); Object u = uf.get(null);
            ge = u.getClass().getMethod("allocateInstance", Class.class).invoke(u, gc);
            Field rf = gc.getDeclaredField("registers"); rf.setAccessible(true); regs = new int[256]; rf.set(ge, regs);
            ren = rc.getConstructor(mc).newInstance(mem);
            cmd = rc.getMethod("command", gc, int.class, int.class, int.class); flush = rc.getMethod("flush");
            w8 = mc.getMethod("write8", int.class, int.class); w32 = mc.getMethod("write32", int.class, int.class);
            Field vf = mc.getDeclaredField("vram"); vf.setAccessible(true); vram = (byte[])vf.get(mem);
        }
        void c(int c, int data) throws Exception { data &= 0xffffff; regs[c] = c << 24 | data; cmd.invoke(ren, ge, c, data, 0); }
    }
    static R a, b; static Random rnd; static String desc; static final boolean SYNC = Boolean.getBoolean("sync");
    static final boolean RELAXED = Integer.getInteger("scale", 1) > 1 || Integer.getInteger("texfilter", 0) != 0; static long diffBytes, compares;
    static void c(int cmd, int data) throws Exception { a.c(cmd, data); b.c(cmd, data); }
    static void w32(int addr, int v) throws Exception { a.w32.invoke(a.mem, addr, v); b.w32.invoke(b.mem, addr, v); }
    static void wf(int addr, float f) throws Exception { w32(addr, Float.floatToRawIntBits(f)); }
    static int f24(float f) { return Float.floatToRawIntBits(f) >>> 8; }
    static int pick(int... v) { return v[rnd.nextInt(v.length)]; }
    static float fr(float lo, float hi) { return lo + rnd.nextFloat() * (hi - lo); }

    public static void main(String[] args) throws Exception {
        a = new R(args[0]); b = new R(args[1]);
        if (RELAXED) {
            b.ren.getClass().getMethod("setScale", int.class).invoke(b.ren, Integer.getInteger("scale", 1));
            b.ren.getClass().getField("textureFilter").setInt(b.ren, Integer.getInteger("texfilter", 0));
        }
        int cases = args.length > 2 ? Integer.parseInt(args[2]) : 2000; rnd = new Random(args.length > 3 ? Long.parseLong(args[3]) : 1);
        // Random VRAM contents (framebuffers, depth, textures) and some RAM textures.
        for (int i = 0; i < 2 * 1024 * 1024; i += 4) w32(0x04000000 + i, rnd.nextInt());
        for (int i = 0; i < 256 * 1024; i += 4) w32(0x08200000 + i, rnd.nextInt());
        // Projection with w = z for perspective; viewport maps [-1,1] to the screen.
        c(0x3e, 0); float[] p = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, -0.5f, 0}; for (float v : p) c(0x3f, f24(v));
        c(0x42, f24(240)); c(0x43, f24(-136)); c(0x44, f24(30000)); c(0x45, f24(2048)); c(0x46, f24(2048)); c(0x47, f24(30000));
        c(0x4c, (2048 - 240) * 16); c(0x4d, (2048 - 136) * 16); c(0x48, f24(1)); c(0x49, f24(1)); c(0x4a, f24(0)); c(0x4b, f24(0));
        c(0x10, 0x080000); // vertex/index base high bits -> RAM
        int pending = 0, bad = 0, vbuf = 0x08100000;
        for (int t = 0; t < cases; t++) {
            // ---- random state ----
            int fbFmt = rnd.nextInt(4), fbOff = pick(0, 0x44000, 0x88000, 0x100000), zOff = pick(0x110000, 0x154000, fbOff, 0x1a0000);
            c(0x9c, fbOff); c(0x9d, pick(512, 512, 512, 256, 64)); c(0xd2, fbFmt);
            c(0x9e, zOff); c(0x9f, pick(512, 512, 512, 0, 16));
            int sx0 = rnd.nextInt(3) == 0 ? rnd.nextInt(200) : 0, sy0 = rnd.nextInt(3) == 0 ? rnd.nextInt(150) : 0;
            int sx1 = rnd.nextInt(3) == 0 ? 200 + rnd.nextInt(400) : 479, sy1 = rnd.nextInt(3) == 0 ? 150 + rnd.nextInt(400) : 271;
            c(0xd4, sx0 | sy0 << 10); c(0xd5, sx1 | sy1 << 10);
            boolean clear = rnd.nextInt(8) == 0; c(0xd3, clear ? 1 | rnd.nextInt(8) << 8 : 0);
            c(0x23, rnd.nextInt(2)); c(0xde, rnd.nextInt(8)); c(0xe7, rnd.nextInt(2));
            c(0x22, rnd.nextInt(3) == 0 ? 1 : 0); c(0xdb, rnd.nextInt(8) | rnd.nextInt(256) << 8 | pick(255, 255, rnd.nextInt(256)) << 16);
            c(0x21, rnd.nextInt(4) == 0 ? 0 : 1);
            c(0xdf, pick(2, 2, rnd.nextInt(11)) | pick(10, 3, rnd.nextInt(11)) << 4 | pick(0, 0, rnd.nextInt(6)) << 8);
            c(0xe0, rnd.nextInt(1 << 24)); c(0xe1, pick(0xffffff, rnd.nextInt(1 << 24)));
            boolean tex = rnd.nextInt(4) != 0; c(0x1e, tex ? 1 : 0);
            int tw = rnd.nextInt(8), th = rnd.nextInt(8), tfmt = pick(3, 3, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10);
            int texAddr = pick(0x04000000 + 0x180000, 0x04000000 + fbOff, 0x08200000);
            c(0xa0, texAddr & 0xffffff); c(0xa8, (texAddr >>> 8) & 0x0f0000 | pick(1 << tw, 1 << tw, 64, 512) & 0xffff);
            c(0xb8, tw | th << 8); c(0xc3, tfmt); c(0xc2, rnd.nextInt(3) == 0 ? 1 : 0);
            c(0xc9, rnd.nextInt(5) | (rnd.nextBoolean() ? 0x100 : 0) | (rnd.nextInt(4) == 0 ? 0x10000 : 0)); c(0xca, rnd.nextInt(1 << 24));
            c(0xc7, rnd.nextInt(2) | rnd.nextInt(2) << 8); c(0xc0, 0);
            if (tfmt >= 4 && tfmt <= 7) { c(0xb0, 0x08200000 & 0xffffff); c(0xb1, 0x080000); c(0xc5, rnd.nextInt(4) | rnd.nextInt(3) << 2 | 0xff << 8); c(0xc4, 16); }
            c(0x50, rnd.nextInt(3) == 0 ? 0 : 1); c(0x1d, rnd.nextInt(4) == 0 ? 1 : 0); c(0x9b, rnd.nextInt(2));
            boolean through = rnd.nextInt(3) != 0, light = !through && rnd.nextInt(4) == 0;
            c(0x17, light ? 1 : 0);
            if (light) {
                c(0x53, rnd.nextInt(8)); c(0x54, rnd.nextInt(1 << 24)); c(0x55, rnd.nextInt(1 << 24)); c(0x56, rnd.nextInt(1 << 24)); c(0x58, rnd.nextInt(256)); c(0x5c, rnd.nextInt(1 << 24)); c(0x5d, 255);
                for (int l = 0; l < 4; l++) { c(0x18 + l, rnd.nextInt(2)); c(0x5f + l, rnd.nextInt(3) << 8); c(0x63 + l * 3, f24(fr(-2, 2))); c(0x64 + l * 3, f24(fr(-2, 2))); c(0x65 + l * 3, f24(fr(-2, 2)));
                    c(0x7b + l * 3, f24(1)); c(0x7c + l * 3, f24(0.1f)); c(0x7d + l * 3, f24(0)); c(0x8f + l * 3, rnd.nextInt(1 << 24)); c(0x90 + l * 3, rnd.nextInt(1 << 24)); }
            }
            // Vertex format: float uv, 8888 colour, optional float normal, float position.
            int vtype = 3 | 7 << 2 | (light ? 3 << 5 : 0) | 3 << 7 | (through ? 0x800000 : 0), stride = light ? 36 : 24;
            c(0x12, vtype);
            // ---- primitive ----
            int kind = rnd.nextInt(10), count, jobs;
            boolean patch = !through && kind >= 8;
            int uc = 4, vc = 4, du = 1, dv = 1, ptype = 0;
            if (patch) { boolean sp = kind == 9; uc = sp ? pick(4, 5) : 4; du = uc == 5 ? 1 : 1 + rnd.nextInt(3); count = uc * vc; jobs = 2 * du * (sp ? uc - 3 : 1); ptype = sp ? 6 : 5; }
            else { ptype = pick(3, 4, 5, 6, 6, 0); count = ptype == 3 ? 3 * (1 + rnd.nextInt(2)) : ptype == 6 ? 2 * (1 + rnd.nextInt(3)) : ptype == 0 ? 1 + rnd.nextInt(5) : 3 + rnd.nextInt(5); jobs = count; }
            if (pending + jobs > 7) { flushBoth(); pending = 0; if (!same()) { bad++; report(t); if (bad > 3 && !RELAXED) break; } }
            float cx = fr(0, 480), cy = fr(0, 272), sz = rnd.nextInt(4) == 0 ? 300 : 60;
            int base = vbuf;
            for (int i = 0; i < count; i++) {
                int o = base + i * stride;
                float u = fr(-0.5f, 1.5f), v = fr(-0.5f, 1.5f);
                if (through) { u *= (1 << tw); v *= (1 << th); }
                wf(o, u); wf(o + 4, v);
                int col = rnd.nextInt(); if (rnd.nextInt(3) == 0 && i > 0) col = rnd.nextInt(2) == 0 ? 0xff808080 : 0x40ffffff; w32(o + 8, col);
                int po = o + 12;
                if (light) { wf(po, fr(-1, 1)); wf(po + 4, fr(-1, 1)); wf(po + 8, fr(-1, 1)); po += 12; }
                if (through) { wf(po, cx + fr(-sz, sz)); wf(po + 4, cy + fr(-sz, sz)); wf(po + 8, fr(0, 65535)); }
                else if (patch) { wf(po, (i % uc) / (float)(uc - 1) * 1.6f - 0.8f + fr(-0.2f, 0.2f)); wf(po + 4, (i / uc) / 3f * 1.6f - 0.8f + fr(-0.2f, 0.2f)); wf(po + 8, fr(0.6f, 3)); }
                else { wf(po, fr(-1.2f, 1.2f)); wf(po + 4, fr(-1.2f, 1.2f)); wf(po + 8, rnd.nextInt(10) == 0 ? fr(-0.5f, 0.3f) : fr(0.6f, 4)); }
            }
            c(0x01, base & 0xffffff); vbuf = base + count * stride; if (vbuf > 0x08180000) vbuf = 0x08100000;
            int[] q = a.regs; desc = String.format("fmt=%d stride=%d z=%x/%d clear=%x dt=%d/%d zmask=%d at=%d/%06x blend=%d/%03x fix=%06x/%06x tex=%d fmt=%d sw=%d tw=%dx%d func=%05x clamp=%x shade=%d cull=%d through=%b light=%b prim=%d count=%d scissor=%x-%x",
                q[0xd2] & 3, q[0x9d] & 0x7fc, q[0x9e] & 0xffffff, q[0x9f] & 0x7fc, q[0xd3] & 0xfff, q[0x23] & 1, q[0xde] & 7, q[0xe7] & 1, q[0x22] & 1, q[0xdb] & 0xffffff, q[0x21] & 1, q[0xdf] & 0xfff, q[0xe0] & 0xffffff, q[0xe1] & 0xffffff,
                q[0x1e] & 1, q[0xc3] & 15, q[0xc2] & 1, 1 << tw, 1 << th, q[0xc9] & 0xfffff, q[0xc7] & 0xffff, q[0x50] & 1, q[0x1d] & 1, through, light, ptype, count, q[0xd4] & 0xfffff, q[0xd5] & 0xfffff);
            if (patch) { c(0x36, du | dv << 8); if (ptype == 5) c(0x05, uc | vc << 8); else c(0x06, uc | vc << 8 | rnd.nextInt(4) << 16 | rnd.nextInt(4) << 18); }
            else c(0x04, ptype << 16 | count);
            pending += jobs;
            if (rnd.nextInt(3) == 0 || SYNC) { flushBoth(); pending = 0; if (!same()) { bad++; report(t); if (bad > 3 && !RELAXED) break; } }
        }
        flushBoth(); if (!same()) { bad++; report(cases); }
        if (RELAXED) { System.out.printf("OK (relaxed): %d cases ran without errors; %d of %d comparisons differed, %.4f%% of VRAM bytes on average%n", cases, bad, compares, 100.0 * diffBytes / Math.max(1, compares) / a.vram.length); return; }
        System.out.println(bad == 0 ? "OK: " + cases + " cases identical" : "MISMATCHES: " + bad);
    }
    static void flushBoth() throws Exception { a.flush.invoke(a.ren); b.flush.invoke(b.ren); }
    static boolean same() {
        if (!RELAXED) return java.util.Arrays.equals(a.vram, b.vram);
        compares++; int n = 0; for (int i = 0; i < a.vram.length; i++) if (a.vram[i] != b.vram[i]) n++;
        diffBytes += n; return n == 0 || compares < 0; // differences are expected: count them, keep going
    }
    static void report(int t) {
        if (RELAXED) return; // (bad counts differing comparisons; VRAM is not resynced so the candidate's shadows stay coherent)
        int n = 0, first = -1; for (int i = 0; i < a.vram.length; i++) if (a.vram[i] != b.vram[i]) { n++; if (first < 0) first = i; }
        System.out.printf("case %d: %d bytes differ, first at vram+%x (%02x vs %02x)%n", t, n, first, a.vram[first] & 255, b.vram[first] & 255);
        System.out.println("  last primitive: " + desc);
        System.arraycopy(a.vram, 0, b.vram, 0, a.vram.length); // resync and continue
    }
}
