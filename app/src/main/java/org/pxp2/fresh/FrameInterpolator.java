package org.pxp2.fresh;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;
import android.annotation.TargetApi;
import org.pxp2.core.FrameGen;
import org.pxp2.core.MotionVectors;
import org.pxp2.core.PspSystem;

/**
 * Frame generation on the GPU (psp.md "Frame generation"). Original implementation.
 * <p>
 * The emulation thread hands each displayed frame to {@link #offer}; {@link FrameGen} decides whether it is a new real
 * frame and how many frames to present for it, and computes PXP2's geometry motion fields (MotionVectors). The fields are
 * packed into small bitmaps and a presenter thread shows k frames per real frame, evenly spaced over the real frame
 * period: k−1 generated at t = i/k, then the real frame. Every frame, real or generated, is drawn by the same AGSL
 * shader (RuntimeShader, Android 13+), which runs on the GPU inside the normal hardware Canvas of the SurfaceView, so the
 * rest of the present path (layout, overlay text) is unchanged. The shader is the CPU reference algorithm of
 * {@link FrameGen} (bidirectional fixed-point warping, blend or nearest-frame fallback) plus the chosen screen filter
 * (nearest, bilinear or sharp bilinear), so real and generated frames look the same.
 */
@TargetApi(33)
final class FrameInterpolator {
    static final String TAG = "PXP2-FG";
    /** Frame generation needs AGSL (RuntimeShader), Android 13 / API 33. */
    static boolean supported() { return Build.VERSION.SDK_INT >= 33; }

    // Same algorithm as FrameGen.rows/solve (the CPU reference): keep them in step.
    private static final String AGSL =
        "uniform shader imgA; uniform shader imgB; uniform shader motA; uniform shader motB;\n" +
        "uniform float t; uniform float gen; uniform float s; uniform float sharp;\n" +
        "float3 decode(half4 v) {\n" + // 24 bits: dx (11, 1/8 px, +1024) | dy (11) | state (2)
        "  float bits = floor(float(v.r) * 255.0 + 0.5) * 65536.0 + floor(float(v.g) * 255.0 + 0.5) * 256.0 + floor(float(v.b) * 255.0 + 0.5);\n" +
        "  float st = mod(bits, 4.0), uy = mod(floor(bits / 4.0), 2048.0), ux = floor(bits / 8192.0);\n" +
        "  return float3((ux - 1024.0) / 8.0, (uy - 1024.0) / 8.0, st);\n" +
        "}\n" +
        "float2 cell(float2 g) { return clamp(floor(g), float2(0.0), float2(479.0, 271.0)) + 0.5; }\n" +
        "float3 fieldA(float2 g) { return decode(motA.eval(cell(g))); }\n" +
        "float3 fieldB(float2 g) { return decode(motB.eval(cell(g))); }\n" +
        "float2 sharpen(float2 p) { float2 c = floor(p - 0.5) + 0.5; float2 f = clamp((p - c - 0.5) * sharp + 0.5, 0.0, 1.0); return c + f; }\n" +
        "half4 sampleA(float2 p) { return imgA.eval(sharpen(p)); }\n" +
        "half4 sampleB(float2 p) { return imgB.eval(sharpen(p)); }\n" +
        // Fixed-point iteration q = g + f*M(q); returns (qx, qy, residual, valid)
        "float4 solveB(float2 g, float f) {\n" +
        "  float3 m = fieldB(g); float ok = m.z == 0.0 ? 0.0 : 1.0; float res = 0.0; float2 q = g;\n" +
        "  for (int i = 0; i < 3; i++) {\n" +
        "    q = g + f * m.xy;\n" +
        "    if (q.x < -1.0 || q.y < -1.0 || q.x > 481.0 || q.y > 273.0) ok = 0.0;\n" +
        "    float3 n = fieldB(q); if (n.z == 0.0) ok = 0.0;\n" +
        "    res = abs(n.x - m.x) + abs(n.y - m.y); m = n;\n" +
        "  }\n" +
        "  q = g + f * m.xy; if (res >= 1.0) ok = 0.0;\n" +
        "  return float4(q, res, ok);\n" +
        "}\n" +
        "float4 solveA(float2 g, float f) {\n" +
        "  float3 m = fieldA(g); float ok = m.z == 0.0 ? 0.0 : 1.0; float res = 0.0; float2 q = g;\n" +
        "  for (int i = 0; i < 3; i++) {\n" +
        "    q = g + f * m.xy;\n" +
        "    if (q.x < -1.0 || q.y < -1.0 || q.x > 481.0 || q.y > 273.0) ok = 0.0;\n" +
        "    float3 n = fieldA(q); if (n.z == 0.0) ok = 0.0;\n" +
        "    res = abs(n.x - m.x) + abs(n.y - m.y); m = n;\n" +
        "  }\n" +
        "  q = g + f * m.xy; if (res >= 1.0) ok = 0.0;\n" +
        "  return float4(q, res, ok);\n" +
        "}\n" +
        "half4 main(float2 p) {\n" +
        "  if (gen < 0.5) return sampleB(p);\n" +
        "  float2 g = p / s;\n" +
        "  float4 b = solveB(g, 1.0 - t); float4 a = solveA(g, -t);\n" +
        "  if (b.w > 0.5 && a.w > 0.5) {\n" +
        "    half4 cb = sampleB(b.xy * s), ca = sampleA(a.xy * s);\n" +
        "    float2 mb = (b.xy - g) / (1.0 - t), ma = (g - a.xy) / t;\n" +
        "    float d = dot(abs(float3(ca.rgb) - float3(cb.rgb)), float3(1.0));\n" +
        "    if (abs(mb.x - ma.x) + abs(mb.y - ma.y) < 1.0 || d <= 64.0 / 255.0) return mix(ca, cb, half(t));\n" +
        "    return b.z <= a.z ? cb : ca;\n" +
        "  }\n" +
        "  if (b.w > 0.5) return sampleB(b.xy * s);\n" +
        "  if (a.w > 0.5) return sampleA(a.xy * s);\n" +
        "  return t < 0.5 ? sampleA(p) : sampleB(p);\n" +
        "}\n";

    interface Presenter { void presentFrame(); }

    final FrameGen gen = new FrameGen();
    private final Presenter presenter;
    private final RuntimeShader shader = new RuntimeShader(AGSL);
    private final Paint paint = new Paint();
    private final Matrix local = new Matrix();
    private final Bitmap[] images = new Bitmap[4];
    private final Bitmap[] fieldsA = new Bitmap[3], fieldsB = new Bitmap[3];
    private final int[] packed = new int[MotionVectors.W * MotionVectors.H];
    private int nextImage, nextField; private Bitmap lastReal;

    /** One real frame to present: k frames (k−1 generated between a and b, then b). */
    static final class Job { Bitmap a, b, motA, motB; int k = 1; float periodMs; int w, h; }
    private Job pending; private volatile Job current; private volatile float currentT = 1f;
    private volatile boolean running = true; private final Thread thread;

    // statistics (presenter/emulation threads; read for the overlay)
    volatile float genMs, fieldMs, latencyMs; volatile int k = 1; volatile String reason = "";
    private long presented, generated, statsTime = System.nanoTime();
    volatile float presentedFps, generatedFps;

    FrameInterpolator(Presenter presenter) {
        this.presenter = presenter;
        thread = new Thread(this::presentLoop, "PXP2 frame generation"); thread.setDaemon(true); thread.start();
    }
    void release() { running = false; thread.interrupt(); }

    // ---- emulation thread ------------------------------------------------------------------------------------------------
    /** The displayed picture of this vblank (w×h ARGB, copied). Returns true if it was a new real frame. */
    boolean offer(PspSystem psp, int[] px, int w, int h) {
        FrameGen.Plan p = gen.offer(psp, px, w, h);
        if (p == null) return false;
        Bitmap img = images[nextImage]; nextImage = (nextImage + 1) % images.length;
        if (img == null || img.getWidth() != w || img.getHeight() != h) images[(nextImage + images.length - 1) % images.length] = img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        img.setPixels(px, 0, w, 0, 0, w, h);
        Job j = new Job(); j.b = img; j.a = lastReal; j.w = w; j.h = h; j.periodMs = p.periodMs; lastReal = img;
        reason = p.reason;
        if (p.generates() && j.a != null && j.a.getWidth() == w && j.a.getHeight() == h) {
            int f = nextField; nextField = (nextField + 1) % fieldsA.length;
            if (fieldsA[f] == null) { fieldsA[f] = field(); fieldsB[f] = field(); }
            FrameGen.pack(p.a.fx, p.a.fy, p.a.fs, packed); fieldsA[f].setPixels(packed, 0, MotionVectors.W, 0, 0, MotionVectors.W, MotionVectors.H);
            FrameGen.pack(p.b.bx, p.b.by, p.b.bs, packed); fieldsB[f].setPixels(packed, 0, MotionVectors.W, 0, 0, MotionVectors.W, MotionVectors.H);
            j.motA = fieldsA[f]; j.motB = fieldsB[f]; j.k = p.k;
            fieldMs = p.fieldNanos / 1e6f;
        }
        k = j.k; latencyMs = FrameGen.addedLatencyMs(j.periodMs, j.k) + (j.k > 1 ? genMs : 0);
        synchronized (this) { pending = j; notifyAll(); }
        return true;
    }
    private static Bitmap field() { return Bitmap.createBitmap(MotionVectors.W, MotionVectors.H, Bitmap.Config.ARGB_8888); }

    // ---- presenter thread --------------------------------------------------------------------------------------------------
    private void presentLoop() {
        try {
            while (running) {
                Job j;
                synchronized (this) { while (pending == null) wait(); j = pending; pending = null; }
                long step = (long)(j.periodMs * 1e6 / j.k), t0 = System.nanoTime();
                for (int i = 1; i <= j.k; i++) {
                    if (i < j.k && hasPending()) continue; // a newer real frame arrived: skip to showing this one's real frame
                    current = j; currentT = i == j.k ? 1f : i / (float)j.k;
                    long g0 = System.nanoTime(); presenter.presentFrame();
                    if (i < j.k) { genMs = genMs * 0.9f + (System.nanoTime() - g0) / 1e6f * 0.1f; generated++; }
                    presented++;
                    if (i < j.k) sleepUntil(t0 + i * step);
                }
                long now = System.nanoTime();
                if (now - statsTime > 1_000_000_000L) {
                    float sec = (now - statsTime) / 1e9f; presentedFps = presented / sec; generatedFps = generated / sec; presented = generated = 0; statsTime = now;
                    Log.i(TAG, String.format(java.util.Locale.ROOT, "presented %.1f fps (generated %.1f) k=%d reason=%s fields %.2f ms, present cpu %.2f ms, added latency %.1f ms",
                        presentedFps, generatedFps, k, reason.isEmpty() ? "generating" : reason, fieldMs, genMs, latencyMs));
                }
            }
        } catch (InterruptedException ignored) { }
    }
    private synchronized boolean hasPending() { return pending != null; }
    private static void sleepUntil(long deadline) throws InterruptedException {
        long wait = deadline - System.nanoTime(); if (wait > 0) Thread.sleep(wait / 1_000_000L, (int)(wait % 1_000_000L));
    }

    /** True once a frame can be drawn by {@link #draw}. */
    boolean ready() { return current != null; }
    int frameWidth() { Job j = current; return j != null ? j.w : 480; }
    int frameHeight() { Job j = current; return j != null ? j.h : 272; }

    /**
     * Draws the current frame (generated at the current t, or real) into dst with the screen filter: 0 pixel (nearest),
     * 1 smooth (bilinear), 2 sharp (sharp bilinear, like the integer prescale + smooth path).
     */
    void draw(Canvas c, Rect dst, int filter) {
        Job j = current; if (j == null) return; float t = currentT;
        boolean generate = t < 1f && j.motA != null && j.a != null;
        float perPixel = Math.min(dst.width() / (float)j.w, dst.height() / (float)j.h);
        float sharp = filter == 0 ? 1e4f : filter == 1 ? 1f : Math.max(1f, (float)Math.floor(perPixel));
        shader.setFloatUniform("t", t); shader.setFloatUniform("gen", generate ? 1f : 0f);
        shader.setFloatUniform("s", j.w / (float)MotionVectors.W); shader.setFloatUniform("sharp", sharp);
        shader.setInputShader("imgB", image(j.b));
        shader.setInputShader("imgA", image(generate ? j.a : j.b));
        shader.setInputShader("motA", field(generate ? j.motA : j.b));
        shader.setInputShader("motB", field(generate ? j.motB : j.b));
        local.setRectToRect(new RectF(0, 0, j.w, j.h), new RectF(dst), Matrix.ScaleToFit.FILL); shader.setLocalMatrix(local);
        paint.setShader(shader); c.drawRect(dst, paint);
    }
    private static Shader image(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR); return s;
    }
    private static Shader field(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); s.setFilterMode(BitmapShader.FILTER_MODE_NEAREST); return s;
    }

    /** One line for the performance overlay. */
    String status() {
        return String.format(java.util.Locale.ROOT, "  fg %s %.0f/%.0f fps +%.0f ms", k > 1 ? k + "x" : "1x", presentedFps, generatedFps, latencyMs) + (reason.isEmpty() ? "" : " (" + reason + ")");
    }

    // ---- GPU cost benchmark (API 34: HardwareBufferRenderer) -----------------------------------------------------------------
    /**
     * Renders the current job's generated frame (and, for comparison, the plain real frame) offscreen at w×h, n times each,
     * waiting for the GPU fence every time; logs and returns the median ms {generated, real}.
     */
    float[] benchmark(int w, int h, int n) {
        if (Build.VERSION.SDK_INT < 34) return null;
        Job j = current; if (j == null || j.motA == null) return null;
        android.hardware.HardwareBuffer buf = android.hardware.HardwareBuffer.create(w, h, android.hardware.HardwareBuffer.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        android.graphics.HardwareBufferRenderer r = new android.graphics.HardwareBufferRenderer(buf);
        android.graphics.RenderNode node = new android.graphics.RenderNode("framegen"); node.setPosition(0, 0, w, h); r.setContentRoot(node);
        float[] out = new float[2]; Job saved = current; float savedT = currentT;
        try {
            for (int mode = 0; mode < 2; mode++) {
                long[] ms = new long[n];
                for (int i = 0; i < n; i++) {
                    Job copy = new Job(); copy.a = j.a; copy.b = j.b; copy.motA = j.motA; copy.motB = j.motB; copy.w = j.w; copy.h = j.h; copy.k = j.k; copy.periodMs = j.periodMs;
                    current = copy; currentT = mode == 0 ? 0.5f : 1f;
                    android.graphics.RecordingCanvas c = node.beginRecording(); draw(c, new Rect(0, 0, w, h), 2); node.endRecording();
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1); long t0 = System.nanoTime();
                    r.obtainRenderRequest().draw(Runnable::run, result -> {
                        try { result.getFence().await(java.time.Duration.ofSeconds(2)); } finally { done.countDown(); }
                    });
                    try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
                    ms[i] = System.nanoTime() - t0;
                }
                java.util.Arrays.sort(ms); out[mode] = ms[n / 2] / 1e6f;
            }
        } finally { current = saved; currentT = savedT; r.close(); buf.close(); }
        Log.i(TAG, String.format(java.util.Locale.ROOT, "GPU benchmark %dx%d from %dx%d: generated frame %.2f ms, real frame %.2f ms (median of %d)", w, h, j.w, j.h, out[0], out[1], n));
        return out;
    }
}
