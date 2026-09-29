package org.pxp2.core;

/**
 * Frame generation policy and the CPU reference interpolator (psp.md "Frame generation"). Original implementation.
 * <p>
 * The frontend hands every displayed frame to {@link #offer}. A frame is a new <em>real</em> frame when the GE drew
 * into the displayed buffer, the displayed buffer changed, or its pixels changed; the previous real frame A and the new
 * one B are then compared and a {@link Plan} says how many frames to present for B: 1 (just B) or k, the first k-1
 * generated at t = i/k between A and B. Generation is skipped, with a reason, when it is off, in the XMB (unless allowed),
 * for static screens, scene cuts, 2D menus without motion, frames that use a framebuffer as a texture, and when the real
 * frame rate does not fit the mode.
 * <p>
 * Interpolation (the same algorithm as the app's GPU shader): for an output pixel p at time t, B's surface there is
 * found by fixed-point iteration q = p + (1−t)·M_B(q) on B's backward field, and A's by q = p − t·F_A(q) on A's forward
 * field (both are A→B displacements; Bowles et al., "Iterative Image Warping", 2012). A side is valid when the iteration
 * converges on known motion. Both valid and similar colours: blend by t; both valid but different (an occlusion): the
 * side with the smaller residual; one valid: that side; none (unknown motion: UI without a match, text, CPU drawing):
 * the nearest real frame, so it never smears.
 */
public final class FrameGen {
    public static final int OFF = 0, HALF_RATE = 1, MATCH_DISPLAY = 2;
    public final MotionVectors motion = new MotionVectors();
    /** Mode (OFF, HALF_RATE: 30 fps games to 60, MATCH_DISPLAY: up to {@link #outputHz}). */
    public volatile int mode = OFF;
    /** Presentation rate for MATCH_DISPLAY (e.g. 120 on a 120/144 Hz panel); HALF_RATE always outputs 60. */
    public volatile float outputHz = 120;
    /** Generate in the XMB too (off by default: its menus gain little and it is where people navigate). */
    public volatile boolean allowXmb;
    /** Mean absolute colour difference (0-255, per channel) above which A → B is a scene cut when little is moving. */
    public int cutThreshold = 48;
    public static final int FIELD_ITERATIONS = 3;

    /** A real frame: its pixels (ARGB, w×h at render resolution) and the owner snapshot of the buffer it came from. */
    public static final class Real {
        public int[] px = new int[0]; public int w, h; public long vblank; public final MotionVectors.Snapshot snap = new MotionVectors.Snapshot(); int hash;
        public final float[] fx = new float[MotionVectors.W * MotionVectors.H], fy = new float[MotionVectors.W * MotionVectors.H];
        public final byte[] fs = new byte[MotionVectors.W * MotionVectors.H]; // forward field (this frame as A)
        public final float[] bx = new float[MotionVectors.W * MotionVectors.H], by = new float[MotionVectors.W * MotionVectors.H];
        public final byte[] bs = new byte[MotionVectors.W * MotionVectors.H]; // backward field (this frame as B)
    }
    /** What to present for a new real frame B. */
    public static final class Plan {
        public Real a, b; public int k = 1, diff; public String reason = ""; public float periodMs; public long fieldNanos;
        public MotionVectors.Stats backward, forward;
        public boolean generates() { return k > 1; }
    }
    private Real a, b, spare = new Real(), spare2 = new Real();
    private long vblanks, lastRealVblank; private final int[] intervals = new int[8]; private int intervalCount;
    private int lastAddress, lastFlips, lastLast = -1;
    public long realFrames, generatedPlans;
    public String lastReason = "";

    /** Real frame period in vblanks (1 = 60 fps, 2 = 30 fps, ...): the median of the last intervals. */
    public int period() {
        int n = Math.min(intervalCount, intervals.length); if (n == 0) return 1;
        int[] s = java.util.Arrays.copyOf(intervals, n); java.util.Arrays.sort(s); return Math.max(1, s[n / 2]);
    }

    /**
     * Called once per vblank with the displayed picture ({@code px} is copied when it is a new real frame). Returns a plan
     * for a new real frame, or null when the frame repeats the last real one.
     */
    public Plan offer(PspSystem psp, int[] px, int w, int h) {
        vblanks++;
        int address = psp.system.frameBuffer; Memory m = psp.kernel.memory; int[] off = new int[1];
        MotionVectors.Target t = null;
        if (address != 0 && m.valid(address, 4)) { byte[] arr = m.backing(address, off); t = motion.targetAt(arr, off[0]); }
        int last = t != null ? t.last : 0, hash = hash(px, w, h);
        boolean fresh = b == null || address != lastAddress || last != lastLast || hash != b.hash || w != b.w || h != b.h;
        lastAddress = address; lastLast = last;
        if (!fresh) return null;
        if (b != null) { int iv = (int)Math.min(8, vblanks - lastRealVblank); intervals[intervalCount++ % intervals.length] = iv; }
        lastRealVblank = vblanks; realFrames++;
        // Rotate: the new frame goes into a spare Real (a Plan's frames stay intact until two more real frames arrive).
        Real n = spare; spare = spare2; spare2 = a; if (spare2 == null) spare2 = new Real();
        if (n.px.length < w * h) n.px = new int[w * h];
        System.arraycopy(px, 0, n.px, 0, w * h); n.w = w; n.h = h; n.vblank = vblanks; n.hash = hash;
        motion.capture(t, n.snap);
        a = b; b = n;
        Plan p = new Plan(); p.a = a; p.b = b; p.periodMs = period() * 1000f / 59.94f;
        p.reason = decide(psp, p);
        if (p.reason.isEmpty()) {
            long t0 = System.nanoTime();
            p.backward = motion.field(b.snap, false, b.bx, b.by, b.bs);
            p.forward = motion.field(a.snap, true, a.fx, a.fy, a.fs);
            p.fieldNanos = System.nanoTime() - t0;
            String late = decideAfterFields(p);
            if (!late.isEmpty()) p.reason = late; else { p.k = frames(); generatedPlans++; }
        }
        lastReason = p.reason;
        return p;
    }
    /** Presented frames per real frame for the current mode and cadence (1 = no generation). */
    private int frames() {
        int per = period();
        if (mode == HALF_RATE) return per == 2 ? 2 : 1;
        if (mode == MATCH_DISPLAY) return Math.max(1, Math.min(8, Math.round(outputHz * per / 59.94f)));
        return 1;
    }
    private String decide(PspSystem psp, Plan p) {
        if (mode == OFF) return "off";
        if (!psp.inGame() && !allowXmb) return "xmb";
        if (p.a == null || p.a.w != p.b.w || p.a.h != p.b.h) return "first frame";
        if (frames() < 2) return mode == HALF_RATE ? "not 30 fps" : "display rate";
        int diff = meanDifference(p.a, p.b);
        if (diff == 0) return "static";
        p.diff = diff;
        return "";
    }
    private String decideAfterFields(Plan p) {
        MotionVectors.Stats s = p.backward; int total = MotionVectors.W * MotionVectors.H;
        if (s.fbTexture > total / 3) return "readback";
        if (p.diff > cutThreshold && s.moving < total / 4) return "cut";
        if (s.unknown > total * 3 / 4 && s.moving < total / 20) return "cut";
        if (!p.b.snap.has3d && s.moving < total / 200 && (p.forward == null || p.forward.moving < total / 200)) return "menu";
        return "";
    }
    /** Mean absolute per-channel difference (0-255) on a sparse grid. */
    static int meanDifference(Real a, Real b) {
        long sum = 0; int n = 0, w = a.w, h = a.h, step = Math.max(1, w / 160);
        for (int y = step / 2; y < h; y += step) for (int x = step / 2; x < w; x += step) {
            int p = a.px[y * w + x], q = b.px[y * w + x];
            sum += Math.abs(((p >>> 16) & 255) - ((q >>> 16) & 255)) + Math.abs(((p >>> 8) & 255) - ((q >>> 8) & 255)) + Math.abs((p & 255) - (q & 255)); n += 3;
        }
        return n == 0 ? 0 : (int)((sum + n - 1) / n);
    }
    private static int hash(int[] px, int w, int h) {
        int x = w * 31 + h; for (int i = 0, n = w * h; i < n; i += 7) x = x * 0x01000193 ^ px[i];
        return x;
    }

    // ---- CPU reference interpolator ----------------------------------------------------------------------------------
    /** Per-pixel outcome counts of the last {@link #interpolate} (both, A only, B only, nearest, occlusion picks). */
    public long both, onlyA, onlyB, nearest, occluded;
    /**
     * Writes the generated frame at time t (0 = A, 1 = B) of plan p into out (p.b.w × p.b.h). Rows are split over the
     * given number of threads.
     */
    public void interpolate(Plan p, float t, int[] out, int threads) {
        both = onlyA = onlyB = nearest = occluded = 0;
        int h = p.b.h; threads = Math.max(1, threads);
        if (threads == 1) { long[] c = rows(p, t, out, 0, h); add(c); return; }
        java.util.List<Thread> list = new java.util.ArrayList<>(); long[][] counts = new long[threads][];
        for (int i = 0; i < threads; i++) {
            int y0 = h * i / threads, y1 = h * (i + 1) / threads, idx = i;
            Thread th = new Thread(() -> counts[idx] = rows(p, t, out, y0, y1)); th.start(); list.add(th);
        }
        for (Thread th : list) try { th.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (long[] c : counts) if (c != null) add(c);
    }
    private void add(long[] c) { both += c[0]; onlyA += c[1]; onlyB += c[2]; nearest += c[3]; occluded += c[4]; }
    private static long[] rows(Plan p, float t, int[] out, int y0, int y1) {
        Real A = p.a, B = p.b; int w = B.w; float s = w / (float)MotionVectors.W; long[] c = new long[5];
        float[] q = new float[5];
        for (int y = y0; y < y1; y++) for (int x = 0; x < w; x++) {
            float gx = (x + 0.5f) / s, gy = (y + 0.5f) / s; // guest coordinates of this output pixel's centre
            boolean vb = solve(B.bx, B.by, B.bs, gx, gy, 1 - t, q); float qbx = q[0], qby = q[1], rb = q[2], mbx = q[3], mby = q[4];
            boolean va = solve(A.fx, A.fy, A.fs, gx, gy, -t, q); float qax = q[0], qay = q[1], ra = q[2];
            int color;
            if (vb && va) {
                int cb = bilinear(B.px, w, B.h, qbx * s, qby * s), ca = bilinear(A.px, w, A.h, qax * s, qay * s);
                // Same motion on both sides (e.g. a static pixel under a changing translucent layer): blend. Different
                // motions whose colours disagree are an occlusion: take the side that converged better.
                boolean sameMotion = Math.abs(mbx - q[3]) + Math.abs(mby - q[4]) < 1f;
                if (sameMotion || difference(ca, cb) <= 64) { color = mix(ca, cb, t); c[0]++; }
                else { color = rb <= ra ? cb : ca; c[4]++; }
            } else if (vb) { color = bilinear(B.px, w, B.h, qbx * s, qby * s); c[2]++; }
            else if (va) { color = bilinear(A.px, w, A.h, qax * s, qay * s); c[1]++; }
            else { color = t < 0.5f ? A.px[y * w + x] : B.px[y * w + x]; c[3]++; }
            out[y * w + x] = color;
        }
        return c;
    }
    /**
     * Fixed-point iteration q = g + f·M(q) on a field (nearest guest pixel), from q = g, FIELD_ITERATIONS steps. Writes q (guest coordinates) and
     * the final residual |M(q) − m| and the motion m to r; returns true if every visited pixel had known motion and it converged.
     */
    static boolean solve(float[] mx, float[] my, byte[] st, float gx, float gy, float f, float[] r) {
        int W = MotionVectors.W, H = MotionVectors.H;
        int i = idx(gx, gy); if (st[i] == MotionVectors.UNKNOWN) { r[0] = gx; r[1] = gy; r[2] = 1e9f; return false; }
        float m0 = mx[i], m1 = my[i], qx = gx, qy = gy, res = 0;
        for (int it = 0; it < FIELD_ITERATIONS; it++) {
            qx = gx + f * m0; qy = gy + f * m1;
            if (qx < -1 || qy < -1 || qx > W + 1 || qy > H + 1) { r[0] = qx; r[1] = qy; r[2] = 1e9f; return false; }
            int j = idx(qx, qy); if (st[j] == MotionVectors.UNKNOWN) { r[0] = qx; r[1] = qy; r[2] = 1e9f; return false; }
            float n0 = mx[j], n1 = my[j]; res = Math.abs(n0 - m0) + Math.abs(n1 - m1); m0 = n0; m1 = n1; // a fixed number of steps, like the shader
        }
        qx = gx + f * m0; qy = gy + f * m1; r[0] = qx; r[1] = qy; r[2] = res; r[3] = m0; r[4] = m1;
        return res < 1.0f;
    }
    private static int idx(float gx, float gy) {
        int x = (int)Math.floor(gx), y = (int)Math.floor(gy);
        x = x < 0 ? 0 : x >= MotionVectors.W ? MotionVectors.W - 1 : x; y = y < 0 ? 0 : y >= MotionVectors.H ? MotionVectors.H - 1 : y;
        return y * MotionVectors.W + x;
    }
    /** Bilinear sample at continuous pixel coordinates (centres at +0.5), clamped at the edges. */
    static int bilinear(int[] px, int w, int h, float x, float y) {
        x -= 0.5f; y -= 0.5f;
        int x0 = (int)Math.floor(x), y0 = (int)Math.floor(y); int ax = (int)((x - x0) * 256), ay = (int)((y - y0) * 256);
        int xa = x0 < 0 ? 0 : x0 >= w ? w - 1 : x0, xb = x0 + 1 < 0 ? 0 : x0 + 1 >= w ? w - 1 : x0 + 1;
        int ya = y0 < 0 ? 0 : y0 >= h ? h - 1 : y0, yb = y0 + 1 < 0 ? 0 : y0 + 1 >= h ? h - 1 : y0 + 1;
        return GeRenderer.bilerp(px[ya * w + xa], px[ya * w + xb], px[yb * w + xa], px[yb * w + xb], ax, ay) | 0xff000000;
    }
    static int difference(int a, int b) {
        return Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255)) + Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)) + Math.abs((a & 255) - (b & 255));
    }
    static int mix(int a, int b, float t) {
        int wb = (int)(t * 256 + 0.5f), wa = 256 - wb, out = 0xff000000;
        for (int sh = 0; sh < 24; sh += 8) out |= (((((a >>> sh) & 255) * wa + ((b >>> sh) & 255) * wb) + 128) >> 8) << sh;
        return out;
    }

    // ---- GPU field packing -----------------------------------------------------------------------------------------------
    /**
     * Packs a field into opaque ARGB for the GPU shader: 24 bits = dx (11 bits, 1/8 pixel, offset 1024) · dy (11 bits) ·
     * state (2 bits). Alpha stays 255 so premultiplication cannot alter the bits.
     */
    public static void pack(float[] mx, float[] my, byte[] st, int[] out) {
        for (int i = 0, n = MotionVectors.W * MotionVectors.H; i < n; i++) {
            int ux = Math.max(0, Math.min(2047, Math.round(mx[i] * 8) + 1024)), uy = Math.max(0, Math.min(2047, Math.round(my[i] * 8) + 1024));
            int v = ux << 13 | uy << 2 | st[i];
            out[i] = 0xff000000 | v;
        }
    }
    /** Latency the mode adds for a real frame period (ms) and k presented frames: B waits (k−1)/k of a period. */
    public static float addedLatencyMs(float periodMs, int k) { return k <= 1 ? 0 : periodMs * (k - 1) / k; }
}
