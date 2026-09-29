package org.pxp2.core;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Geometry motion vectors for frame generation (psp.md "Frame generation"). Original implementation.
 * <p>
 * PXP2 rasterises every primitive itself, so motion does not have to be estimated from images (optical flow): it is
 * known from the geometry. While frame generation is on, {@link GeRenderer} reports
 * <ul>
 * <li>each draw (PRIM or patch) with the screen position of every vertex, in guest pixels after the transform;</li>
 * <li>each rasterised triangle/sprite ("job") with the draw's vertex indices it was built from, and</li>
 * <li>which job last wrote each pixel of each colour buffer ({@link Target}, one id per guest pixel).</li>
 * </ul>
 * Draws are grouped into frames at the game's display flips ({@link #flip}) and matched to the draw with the same
 * signature (primitive type, vertex count and format, texture, through mode) and the same occurrence number in the
 * previous frame. A matched vertex has moved from the previous frame's screen position to the current one, whatever
 * produced the motion (matrices, skinning, morphing, CPU-animated vertices or moving 2D sprites). A pixel's motion is
 * the barycentric (triangles) or bilinear (sprites) blend of its job's vertex motions.
 * <p>
 * A {@link Snapshot} of a buffer's ids is taken when a real frame is shown; {@link #field} turns two snapshots into
 * per-pixel displacement fields from the older frame A to the newer frame B, both "backward" (for B's pixels, where they
 * were in A) and "forward" (for A's pixels, where they go in B), with a state per pixel: STATIC (not redrawn, a clear, or
 * unchanged geometry), MOVING (matched geometry) or UNKNOWN (no match, framebuffer used as a texture, implausible
 * motion). Unknown pixels are not warped by the interpolator.
 * <p>
 * Cost when frame generation is off: none (GeRenderer holds no MotionVectors and checks one null reference per draw and
 * per shaded span).
 */
public final class MotionVectors {
    /** Guest screen size: fields and snapshots are one entry per PSP pixel, independent of the render scale. */
    public static final int W = 480, H = 272;
    public static final byte UNKNOWN = 0, MOVING = 1, STATIC = 2;
    static final byte TRIANGLE = 0, SPRITE = 1, CLEAR = 2;
    /** Frames apart that draws are matched over (1 = consecutive frames; the desktop drop-frame test uses 2). */
    public int matchDistance = 1;
    /** Motion larger than this many guest pixels between the matched frames is treated as a mismatch (unknown). */
    public float maxMotion = 128;

    /** One PRIM/patch command: its signature and the guest screen positions of its vertices (NaN when clipped). */
    static final class Draw {
        final long sig; final float[] xy; final boolean through, fbTexture; Draw prev, next;
        Draw(long sig, float[] xy, boolean through, boolean fbTexture) { this.sig = sig; this.xy = xy; this.through = through; this.fbTexture = fbTexture; }
    }
    /** The draws and jobs between two display flips. Job serials [firstSerial, firstSerial + jobs) belong to it. */
    static final class Frame {
        final int index, firstSerial; int jobs, draws3d, drawsFbTexture;
        Draw[] draw = new Draw[256]; long[] verts = new long[256]; byte[] kind = new byte[256];
        final HashMap<Long, ArrayList<Draw>> bySig = new HashMap<>();
        Frame(int index, int firstSerial) { this.index = index; this.firstSerial = firstSerial; }
        boolean has(int serial) { return serial >= firstSerial && serial < firstSerial + jobs; }
    }
    private final ArrayList<Frame> frames = new ArrayList<>(); // oldest first; the last one is being recorded
    private int serial = 1, frameIndex; private Draw draw;
    /** Display flips seen (frame boundaries). */
    public int flips;
    /** Jobs recorded (diagnostics). */
    public long jobsTotal;

    public MotionVectors() { frames.add(new Frame(0, serial)); }

    // ---- recording (GE thread) --------------------------------------------------------------------------------------
    /** Normalises a VRAM address (mirrors, uncached) to 0x04000000 + offset; other addresses are returned masked. */
    private static int vram(int a) { a &= 0x0fffffff; return (a & 0x0f000000) == 0x04000000 ? 0x04000000 | (a & 0x1fffff) : a; }

    /**
     * A new draw: {@code prim} is the PRIM type (0-7) or 8/9 for Bezier/spline patches; {@code texAddr} is 0 when not
     * textured; {@code xy} holds x, y per vertex in guest screen pixels (NaN for vertices behind the eye). The array is
     * kept.
     */
    void draw(int prim, int vtype, int texAddr, boolean through, float[] xy) {
        Frame f = frames.get(frames.size() - 1);
        long sig = (((long)vtype * 31 + prim) * 1_000_003L + xy.length) * 1_000_033L + (texAddr & 0xffffffffL) + (through ? 0x5bd1e995L : 0);
        boolean fbTex = texAddr != 0 && isTarget(vram(texAddr));
        Draw d = new Draw(sig, xy, through, fbTex);
        ArrayList<Draw> same = f.bySig.computeIfAbsent(sig, k -> new ArrayList<>());
        int occurrence = same.size(); same.add(d);
        Frame old = frameBack(matchDistance);
        if (old != null) {
            ArrayList<Draw> was = old.bySig.get(sig);
            if (was != null && occurrence < was.size()) { Draw p = was.get(occurrence); d.prev = p; p.next = d; }
        }
        if (!through) f.draws3d++; if (fbTex) f.drawsFbTexture++;
        draw = d;
    }
    /** A rasterised primitive of the current draw, recorded in the same order as GeRenderer's jobs (see {@link #serial}). */
    void job(byte kind, int a, int b, int c) {
        Frame f = frames.get(frames.size() - 1);
        if (f.jobs == f.draw.length) { int n = f.jobs * 2; f.draw = java.util.Arrays.copyOf(f.draw, n); f.verts = java.util.Arrays.copyOf(f.verts, n); f.kind = java.util.Arrays.copyOf(f.kind, n); }
        f.draw[f.jobs] = draw; f.verts[f.jobs] = (a & 0x1fffffL) | (b & 0x1fffffL) << 21 | (c & 0x1fffffL) << 42; f.kind[f.jobs] = draw == null ? CLEAR : kind;
        f.jobs++; serial++; jobsTotal++;
    }
    /** Serial number the next job will get; the renderer's pending job i has serial {@code serial() - pending + i}. */
    int serial() { return serial; }

    /** A display flip (sceDisplaySetFrameBuf): the frame being recorded is complete. */
    public void flip() {
        flips++;
        Frame f = frames.get(frames.size() - 1);
        if (f.jobs == 0 && f.bySig.isEmpty()) return; // nothing drawn since the last flip: keep recording into it
        if (serial > 0x3fff0000) reset(); // serial wrap (after about a billion primitives): forget everything
        frames.add(new Frame(++frameIndex, serial));
        while (frames.size() > matchDistance + 5) frames.remove(0);
        draw = null;
    }
    private void reset() {
        frames.clear(); serial = 1; for (Target t : targets) { java.util.Arrays.fill(t.ids, 0); t.last = 0; }
        frames.add(new Frame(++frameIndex, serial)); // (flip adds the next one)
    }
    private Frame frameBack(int n) { int i = frames.size() - 1 - n; return i >= 0 ? frames.get(i) : null; }
    private Frame frameOf(int serial) {
        for (int i = frames.size() - 1; i >= 0; i--) { Frame f = frames.get(i); if (f.has(serial)) return f; if (serial >= f.firstSerial) return null; }
        return null;
    }

    // ---- per-pixel ownership -------------------------------------------------------------------------------------------
    /** Which job last wrote each guest pixel of one colour buffer (0 = never since frame generation started). */
    public static final class Target {
        final byte[] array; final int base, stride, address, bytes; final int[] ids = new int[W * H]; int last;
        Target(byte[] array, int base, int stride, int address, int bytes) { this.array = array; this.base = base; this.stride = stride; this.address = address; this.bytes = bytes; }

        /** Pixels the pipeline just wrote (span s, first n entries); called from the raster workers (distinct rows). */
        void written(GeRenderer.Raster r, GeRenderer.Span s, int n) {
            boolean alphaOwn = r.blend && r.blendSrc == 2 && r.blendDst == 3; // src·a + dst·(1-a): the source owns it when mostly opaque
            if (r.blend && !alphaOwn) return; // additive and other blends: the layer below keeps the pixel
            int bpp = r.fbFormat == 3 ? 4 : 2, stride = r.fbStride, k = r.scale, base = r.fbBase, id = s.job; int[] P = s.p, A = s.a, ids = this.ids;
            for (int i = 0; i < n; i++) {
                if (alphaOwn && A[i] < 128) continue;
                int idx = (P[i] - base) / bpp, hy = idx / stride, gx = (idx - hy * stride) / k, gy = hy / k;
                if (gx < W && gy < H && gx >= 0 && gy >= 0) ids[gy * W + gx] = id;
            }
            if (id > last) last = id;
        }
        /** One pixel written by the strictly per-pixel pipeline. */
        void written(GeRenderer.Raster r, int p, int id) {
            if (r.blend && !(r.blendSrc == 2 && r.blendDst == 3)) return;
            int bpp = r.fbFormat == 3 ? 4 : 2, idx = (p - r.fbBase) / bpp, hy = idx / r.fbStride, gx = (idx - hy * r.fbStride) / r.scale, gy = hy / r.scale;
            if (gx < W && gy < H && gx >= 0 && gy >= 0) ids[gy * W + gx] = id;
            if (id > last) last = id;
        }
        /** A filled rectangle of render pixels [x0, x1) × [y0, y1) (the clear fast path). */
        void filled(int k, int x0, int x1, int y0, int y1, int id) {
            int gx0 = Math.max(0, x0 / k), gx1 = Math.min(W, (x1 + k - 1) / k), gy0 = Math.max(0, y0 / k), gy1 = Math.min(H, (y1 + k - 1) / k);
            for (int y = gy0; y < gy1; y++) java.util.Arrays.fill(ids, y * W + gx0, y * W + Math.max(gx0, gx1), id);
            if (id > last) last = id;
        }
    }
    private final ArrayList<Target> targets = new ArrayList<>();
    /** The ownership map of the colour buffer at guest address {@code address} (backing array/offset, pixels per row). */
    Target target(int address, byte[] array, int base, int stride, int bpp) {
        for (int i = 0; i < targets.size(); i++) { Target t = targets.get(i); if (t.base == base && t.array == array && t.stride == stride) return t; }
        Target t = new Target(array, base, stride, vram(address), stride * bpp * H);
        if (targets.size() >= 8) targets.remove(0); // e.g. render-to-texture buffers: keep the most recent few
        targets.add(t); return t;
    }
    private boolean isTarget(int address) {
        for (Target t : targets) if (address >= t.address && address < t.address + t.bytes) return true;
        return false;
    }
    /** The ownership map of the buffer at this backing array/offset, or null if the GE has not drawn it. */
    public Target targetAt(byte[] array, int base) {
        for (Target t : targets) if (t.base == base && t.array == array) return t;
        return null;
    }

    // ---- snapshots and fields --------------------------------------------------------------------------------------------
    /** The owners of a displayed buffer's pixels when it was shown as a real frame. */
    public static final class Snapshot {
        public final int[] ids = new int[W * H]; int frame = -1; public int last; public boolean has3d, fbTexture;
    }
    /** Copies the owner map of the displayed buffer (null target: the GE never drew it; every pixel is static). */
    public Snapshot capture(Target t, Snapshot reuse) {
        Snapshot s = reuse != null ? reuse : new Snapshot();
        if (t == null) { java.util.Arrays.fill(s.ids, 0); s.frame = -1; s.last = 0; s.has3d = false; s.fbTexture = false; return s; }
        System.arraycopy(t.ids, 0, s.ids, 0, W * H); s.last = t.last;
        Frame f = frameOf(t.last); s.frame = f != null ? f.index : -1;
        s.has3d = f != null && f.draws3d > 0; s.fbTexture = f != null && f.drawsFbTexture > 0;
        return s;
    }

    /** Result of {@link #field}: counts of pixels per state. */
    public static final class Stats { public int moving, stat, unknown, fbTexture; public float maxMotion; }

    /**
     * Displacement field (guest pixels, from frame A to frame B) for the pixels of snapshot {@code s}: {@code forward}
     * false means s is B and motion comes from each job's match in the previous frame; true means s is A and it comes
     * from the match in the next frame. Pixels drawn before the snapshot's frame, never drawn or cleared are static.
     */
    public Stats field(Snapshot s, boolean forward, float[] mx, float[] my, byte[] state) {
        Stats st = new Stats(); int[] ids = s.ids;
        int cachedId = -1; byte cs = STATIC; float[] plane = new float[6]; // per-job affine motion: mx = a·x + b·y + c
        Frame frame = null; for (Frame f : frames) if (f.index == s.frame) frame = f;
        for (int y = 0, i = 0; y < H; y++) for (int x = 0; x < W; x++, i++) {
            int id = ids[i];
            if (id != cachedId) { cachedId = id; cs = frame == null || !frame.has(id) ? STATIC : motionPlane(frame, id - frame.firstSerial, forward, plane); if (cs == UNKNOWN && frame != null && frame.draw[id - frame.firstSerial] != null && frame.draw[id - frame.firstSerial].fbTexture) st.fbTexture++; }
            if (cs == MOVING) {
                float px = x + 0.5f, py = y + 0.5f, vx = plane[0] * px + plane[1] * py + plane[2], vy = plane[3] * px + plane[4] * py + plane[5];
                if (Math.abs(vx) > maxMotion || Math.abs(vy) > maxMotion || vx != vx || vy != vy) { mx[i] = my[i] = 0; state[i] = UNKNOWN; st.unknown++; continue; }
                mx[i] = vx; my[i] = vy; state[i] = MOVING; st.moving++; st.maxMotion = Math.max(st.maxMotion, Math.abs(vx) + Math.abs(vy));
            } else { mx[i] = my[i] = 0; state[i] = cs; if (cs == STATIC) st.stat++; else st.unknown++; }
        }
        return st;
    }
    /** Fits the job's motion as an affine function of the guest pixel position; returns its state. */
    private byte motionPlane(Frame f, int j, boolean forward, float[] p) {
        byte kind = f.kind[j]; Draw d = f.draw[j];
        if (kind == CLEAR || d == null) return STATIC;
        Draw o = forward ? d.next : d.prev;
        if (o == null || d.fbTexture || o.xy.length != d.xy.length) return UNKNOWN;
        long v = f.verts[j]; int a = (int)(v & 0x1fffff), b = (int)((v >>> 21) & 0x1fffff), c = (int)((v >>> 42) & 0x1fffff);
        float[] q = d.xy, r = o.xy; float sign = forward ? 1 : -1; // forward: next - now; backward: now - prev
        if (2 * Math.max(a, Math.max(b, kind == SPRITE ? 0 : c)) + 1 >= q.length) return UNKNOWN;
        float ax = q[2 * a], ay = q[2 * a + 1], bx = q[2 * b], by = q[2 * b + 1];
        float dax = (r[2 * a] - ax) * sign, day = (r[2 * a + 1] - ay) * sign, dbx = (r[2 * b] - bx) * sign, dby = (r[2 * b + 1] - by) * sign;
        if (kind == SPRITE) { // bilinear between the corners, separable: x motion varies with x, y motion with y
            float w = bx - ax, h = by - ay;
            if (dax != dax || day != day || dbx != dbx || dby != dby) return UNKNOWN;
            if (Math.abs(w) < 1e-3f) { p[0] = 0; p[2] = (dax + dbx) / 2; } else { p[0] = (dbx - dax) / w; p[2] = dax - p[0] * ax; }
            if (Math.abs(h) < 1e-3f) { p[4] = 0; p[5] = (day + dby) / 2; } else { p[4] = (dby - day) / h; p[5] = day - p[4] * ay; }
            p[1] = 0; p[3] = 0;
            return dax == 0 && day == 0 && dbx == 0 && dby == 0 ? movingZero(p) : MOVING;
        }
        float cx = q[2 * c], cy = q[2 * c + 1], dcx = (r[2 * c] - cx) * sign, dcy = (r[2 * c + 1] - cy) * sign;
        if (dax != dax || day != day || dbx != dbx || dby != dby || dcx != dcx || dcy != dcy) return UNKNOWN;
        float det = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
        if (Math.abs(det) < 1e-4f) { p[0] = p[1] = p[3] = p[4] = 0; p[2] = (dax + dbx + dcx) / 3; p[5] = (day + dby + dcy) / 3; return MOVING; }
        // Solve m(x, y) = α·x + β·y + γ through the three vertices, for each component.
        for (int comp = 0; comp < 2; comp++) {
            float ma = comp == 0 ? dax : day, mb = comp == 0 ? dbx : dby, mc = comp == 0 ? dcx : dcy;
            float al = ((mb - ma) * (cy - ay) - (mc - ma) * (by - ay)) / det, be = ((mc - ma) * (bx - ax) - (mb - ma) * (cx - ax)) / det;
            p[comp * 3] = al; p[comp * 3 + 1] = be; p[comp * 3 + 2] = ma - al * ax - be * ay;
        }
        return MOVING;
    }
    /** Diagnostics: draws, matched draws and jobs of the retained frames (oldest first). */
    public String describe() {
        StringBuilder b = new StringBuilder();
        for (Frame f : frames) {
            int draws = 0, matched = 0, through = 0; for (java.util.List<Draw> l : f.bySig.values()) for (Draw d : l) { draws++; if (d.prev != null) matched++; if (d.through) through++; }
            b.append(String.format(java.util.Locale.ROOT, "[f%d draws %d (2D %d) matched %d jobs %d fbtex %d] ", f.index, draws, through, matched, f.jobs, f.drawsFbTexture));
        }
        return b.toString();
    }
    private static byte movingZero(float[] p) { java.util.Arrays.fill(p, 0); return MOVING; }
}
