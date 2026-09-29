package org.pxp2.fresh;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.pxp2.fresh.R;

/**
 * Interactive 3D PSP UMD View.
 * Renders an authentic 3D UMD cartridge in real-time perspective:
 * - Front shell with clear read opening and PSP hub
 * - Back shell with metal retaining ring and laser read window
 * - Inner optical disc continuously spinning with rainbow iridescent sheen
 * - Interactive 3D touch rotation (pitch & yaw drag, flick inertia, double-tap reset)
 * - Dynamic custom disc art (supports remote fetch, custom bitmaps, or synthesized game disc art)
 */
public class Umd3DView extends View {
    private static final String TAG = "Umd3DView";
    private static final ExecutorService HTTP_EXECUTOR = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 3D Perspective & Camera
    private final Camera camera = new Camera();
    private final Matrix matrixFront = new Matrix();
    private final Matrix matrixDisc = new Matrix();
    private final Matrix matrixBack = new Matrix();
    private final Matrix matrixShadow = new Matrix();

    // Textures
    private Bitmap caddyFront;
    private Bitmap caddyBack;
    private Bitmap discDefault;
    private Bitmap discCustom;

    // Paints
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plasticGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Iridescent colors for optical disc rainbow reflection
    private static final int[] IRIS_COLORS = new int[] {
        0x00ffffff,
        0x50ff2266, // magenta / pink
        0x65ff9900, // orange-gold
        0x65ffff22, // bright yellow
        0x6522ff88, // emerald green
        0x6500e5ff, // vivid cyan
        0x653377ff, // sapphire blue
        0x65b822ff, // royal purple
        0x50ff2266, // magenta / pink
        0x00ffffff
    };
    private static final float[] IRIS_POS = new float[] {
        0.00f, 0.12f, 0.25f, 0.38f, 0.50f, 0.62f, 0.75f, 0.88f, 0.95f, 1.00f
    };

    // 3D Angles (degrees)
    private float pitch = -12f; // Tilt around X-axis
    private float yaw = 18f;    // Rotation around Y-axis
    private float roll = 0f;    // Tilt around Z-axis

    // Spin animation
    private boolean isSpinning = true;
    private float discAngle = 0f;
    private float spinRpm = 45f; // Authentic PSP UMD read speed
    private long lastFrameTimeNanos = 0;

    // Touch & Inertia
    private final GestureDetector gestureDetector;
    private VelocityTracker velocityTracker;
    private float inertiaVx = 0f;
    private float inertiaVy = 0f;
    private boolean isDragging = false;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private ValueAnimator snapAnimator;

    // Idle subtle float animation
    private boolean idleFloatEnabled = true;
    private float idleTimer = 0f;

    // Dimensions & Geometry
    private final RectF boundsFront = new RectF();
    private final RectF boundsDisc = new RectF();
    private final RectF boundsBack = new RectF();
    private final Path discClipPath = new Path();
    private float cartridgeThicknessDp = 8.5f;

    // Current Game info
    private String currentGameTitle;
    private Bitmap currentGameIcon;

    public Umd3DView(Context context) {
        this(context, null);
    }

    public Umd3DView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Umd3DView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        gestureDetector = new GestureDetector(context, new GestureListener());
        init();
    }

    private void init() {
        setFocusable(true);
        setClickable(true);

        shadowPaint.setColor(0x40000000);
        shadowPaint.setMaskFilter(new BlurMaskFilter(dp(14), BlurMaskFilter.Blur.NORMAL));

        edgePaint.setColor(0xffc8c8cc);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(dp(2));

        loadTextures();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void loadTextures() {
        try {
            caddyFront = BitmapFactory.decodeResource(getResources(), R.drawable.umd_caddy_front);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load umd_caddy_front: " + t.getMessage());
        }
        try {
            caddyBack = BitmapFactory.decodeResource(getResources(), R.drawable.umd_caddy_back);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load umd_caddy_back: " + t.getMessage());
        }
        try {
            discDefault = BitmapFactory.decodeResource(getResources(), R.drawable.umd_disc_default);
            if (discDefault == null) {
                discDefault = BitmapFactory.decodeResource(getResources(), R.drawable.umd_disc_red);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load umd_disc_default: " + t.getMessage());
        }

        if (caddyFront == null) caddyFront = createFallbackCaddy(true);
        if (caddyBack == null) caddyBack = createFallbackCaddy(false);
        if (discDefault == null) discDefault = createFallbackDisc();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameTimeNanos = System.nanoTime();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (snapAnimator != null) {
            snapAnimator.cancel();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int modeW = MeasureSpec.getMode(widthMeasureSpec);
        int modeH = MeasureSpec.getMode(heightMeasureSpec);

        int defSize = Math.round(dp(240));
        if (modeW != MeasureSpec.EXACTLY && modeH != MeasureSpec.EXACTLY) {
            w = defSize;
            h = defSize;
        } else if (modeW != MeasureSpec.EXACTLY) {
            w = h;
        } else if (modeH != MeasureSpec.EXACTLY) {
            h = w;
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = dp(24);
        float size = Math.min(w, h) - padding * 2;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;

        boundsFront.set(left, top, left + size, top + size);
        boundsBack.set(boundsFront);

        // Disc is concentric with the caddy window
        float discInset = size * 0.045f;
        boundsDisc.set(left + discInset, top + discInset, left + size - discInset, top + size - discInset);

        // Prepare disc ring clip path for iridescent shader
        discClipPath.reset();
        float cx = boundsDisc.centerX();
        float cy = boundsDisc.centerY();
        float outerR = boundsDisc.width() / 2f;
        float innerR = outerR * 0.20f;
        discClipPath.addCircle(cx, cy, outerR, Path.Direction.CW);
        Path hole = new Path();
        hole.addCircle(cx, cy, innerR, Path.Direction.CW);
        discClipPath.op(hole, Path.Op.DIFFERENCE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        if (lastFrameTimeNanos == 0) lastFrameTimeNanos = now;
        float dt = (now - lastFrameTimeNanos) / 1_000_000_000f;
        lastFrameTimeNanos = now;
        if (dt > 0.1f) dt = 0.016f; // prevent giant jump after backgrounding

        // 1. Update spinning disc angle
        if (isSpinning) {
            float deltaAngle = (spinRpm * 360f / 60f) * dt;
            discAngle = (discAngle + deltaAngle) % 360f;
        }

        // 2. Update inertia physics when not dragging
        if (!isDragging && (Math.abs(inertiaVx) > 0.02f || Math.abs(inertiaVy) > 0.02f)) {
            yaw += inertiaVx * dt * 45f;
            pitch -= inertiaVy * dt * 45f;
            pitch = Math.max(-80f, Math.min(80f, pitch));
            float friction = (float) Math.pow(0.12f, dt);
            inertiaVx *= friction;
            inertiaVy *= friction;
        } else if (!isDragging && idleFloatEnabled && (snapAnimator == null || !snapAnimator.isRunning())) {
            // Subtle idle breathing float
            idleTimer += dt;
            float idlePitch = (float) Math.sin(idleTimer * 0.8f) * 1.8f;
            float idleYaw = (float) Math.cos(idleTimer * 0.6f) * 2.2f;
            // Apply slight wobble
            pitch += idlePitch * dt * 0.5f;
            yaw += idleYaw * dt * 0.5f;
        }

        // Keep yaw in standard -180..180 or 0..360 range
        while (yaw > 180f) yaw -= 360f;
        while (yaw < -180f) yaw += 360f;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float thicknessPx = dp(cartridgeThicknessDp);

        // Compute 3D viewing normal (Z component) to determine front vs back facing
        // normal = R * (0, 0, 1) -> normal.z = cos(pitch) * cos(yaw)
        double radPitch = Math.toRadians(pitch);
        double radYaw = Math.toRadians(yaw);
        float normalZ = (float) (Math.cos(radPitch) * Math.cos(radYaw));
        boolean isFrontFacing = normalZ >= 0;

        // 3. Draw grounded dynamic contact shadow
        drawCartridgeShadow(canvas, cx, cy);

        // 4. Determine layer depth ordering
        // Front shell is at +thickness/2, Disc at 0, Back shell at -thickness/2
        float frontZ = thicknessPx * 0.5f;
        float discZ = 0f;
        float backZ = -thicknessPx * 0.5f;

        // Draw 3 layers in back-to-front painter's order
        if (isFrontFacing) {
            // Looking at the front: Back shell -> Disc -> Front shell
            drawLayer(canvas, caddyBack, boundsBack, backZ, pitch, yaw, roll, 0f, cx, cy, 0.85f);
            drawDiscLayer(canvas, discZ, pitch, yaw, roll, discAngle, cx, cy);
            drawLayer(canvas, caddyFront, boundsFront, frontZ, pitch, yaw, roll, 0f, cx, cy, 1.0f);
            drawGameLabel(canvas, frontZ, pitch, yaw, roll, discAngle, cx, cy);
            drawFrontGlassSheen(canvas, frontZ, pitch, yaw, cx, cy);
        } else {
            // Looking at the back: Front shell -> Disc -> Back shell
            drawLayer(canvas, caddyFront, boundsFront, frontZ, pitch, yaw, roll, 0f, cx, cy, 0.85f);
            drawDiscLayer(canvas, discZ, pitch, yaw, roll, discAngle, cx, cy);
            drawLayer(canvas, caddyBack, boundsBack, backZ, pitch, yaw, roll, 0f, cx, cy, 1.0f);
            drawBackWindowGlow(canvas, backZ, pitch, yaw, cx, cy);
        }

        // Draw 3D cartridge side edge / depth bevel
        drawCartridgeEdgeRim(canvas, cx, cy, thicknessPx, isFrontFacing);

        // Request next frame for buttery 60/120fps animation
        postInvalidateOnAnimation();
    }

    private void drawCartridgeShadow(Canvas canvas, float cx, float cy) {
        canvas.save();
        float shadowY = cy + boundsFront.height() * 0.48f;
        float shadowScaleX = (float) (0.85f + 0.15f * Math.abs(Math.cos(Math.toRadians(yaw))));
        float shadowScaleY = (float) (0.28f + 0.08f * Math.sin(Math.toRadians(Math.abs(pitch))));
        float shadowOffsetX = (float) (Math.sin(Math.toRadians(yaw)) * dp(10));
        float shadowOffsetY = (float) (Math.sin(Math.toRadians(pitch)) * dp(8));

        canvas.translate(cx + shadowOffsetX, shadowY + shadowOffsetY);
        canvas.scale(shadowScaleX, shadowScaleY);
        canvas.drawOval(-boundsFront.width() * 0.42f, -boundsFront.height() * 0.18f,
                        boundsFront.width() * 0.42f, boundsFront.height() * 0.18f, shadowPaint);
        canvas.restore();
    }

    /**
     * The per-game label: the game's disc art masked into the disc already painted on the caddy front image (circle
     * centre ≈ (251.5, 252), radius ≈ 216 of 512), spinning about that centre. Nothing is drawn without a game.
     */
    private void drawGameLabel(Canvas canvas, float zOffset, float rotX, float rotY, float rotZ, float angle, float cx, float cy) {
        Bitmap art = discCustom; if (art == null || art.isRecycled()) return;
        float k = boundsFront.width() / 512f;
        float dcx = boundsFront.left + 251.5f * k, dcy = boundsFront.top + 252f * k, r = 216f * k;
        canvas.save();
        setup3dMatrix(matrixFront, zOffset, rotX, rotY, rotZ, 0f, cx, cy);
        canvas.concat(matrixFront);
        canvas.rotate(angle, dcx, dcy);
        // discCustom is 512² with the disc at radius 216 around its centre: map that circle onto the painted one.
        float s = r / 216f * 512f / 2f;
        bitmapPaint.setAlpha(255);
        canvas.drawBitmap(art, null, new RectF(dcx - s, dcy - s, dcx + s, dcy + s), bitmapPaint);
        drawIridescentSheen(canvas, new RectF(dcx - r, dcy - r, dcx + r, dcy + r), angle);
        canvas.restore();
    }

    private void drawLayer(Canvas canvas, Bitmap bmp, RectF bounds, float zOffset,
                           float rotX, float rotY, float rotZ, float inPlaneAngle,
                           float cx, float cy, float lightingMultiplier) {
        if (bmp == null || bmp.isRecycled()) return;

        canvas.save();
        setup3dMatrix(matrixFront, zOffset, rotX, rotY, rotZ, inPlaneAngle, cx, cy);
        canvas.concat(matrixFront);

        // Lighting shading: subtle darkening when tilted away from virtual light source
        double radPitch = Math.toRadians(rotX);
        double radYaw = Math.toRadians(rotY);
        float lightIntensity = (float) (0.80f + 0.20f * (Math.cos(radPitch) * Math.cos(radYaw)));
        int alpha = Math.round(255 * Math.max(0.6f, Math.min(1.0f, lightIntensity * lightingMultiplier)));
        bitmapPaint.setAlpha(alpha);

        canvas.drawBitmap(bmp, null, bounds, bitmapPaint);
        canvas.restore();
    }

    private void drawDiscLayer(Canvas canvas, float zOffset, float rotX, float rotY, float rotZ,
                               float inPlaneAngle, float cx, float cy) {
        Bitmap discBmp = discCustom != null ? discCustom : discDefault;
        if (discBmp == null || discBmp.isRecycled()) return;

        canvas.save();
        setup3dMatrix(matrixDisc, zOffset, rotX, rotY, rotZ, inPlaneAngle, cx, cy);
        canvas.concat(matrixDisc);

        // Draw disc art
        bitmapPaint.setAlpha(255);
        canvas.drawBitmap(discBmp, null, boundsDisc, bitmapPaint);

        // Draw iridescent optical disc rainbow sheen
        drawIridescentSheen(canvas, boundsDisc, inPlaneAngle);

        canvas.restore();
    }

    /**
     * Renders authentic rainbow diffraction highlight fanning across the optical disc tracks.
     */
    private void drawIridescentSheen(Canvas canvas, RectF bounds, float currentAngle) {
        canvas.save();
        canvas.clipPath(discClipPath);

        float discCx = bounds.centerX();
        float discCy = bounds.centerY();

        // Rotate the iridescent sweep shader with the disc + orientation
        float sheenAngle = (currentAngle * 0.7f + yaw * 1.2f) % 360f;
        canvas.rotate(sheenAngle, discCx, discCy);

        SweepGradient sweep = new SweepGradient(discCx, discCy, IRIS_COLORS, IRIS_POS);
        sheenPaint.setShader(sweep);
        sheenPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));

        canvas.drawCircle(discCx, discCy, bounds.width() / 2f, sheenPaint);
        sheenPaint.setXfermode(null);
        sheenPaint.setShader(null);

        // Specular secondary reflection slice
        Paint specularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        specularPaint.setShader(new LinearGradient(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            new int[] { 0x00ffffff, 0x44ffffff, 0x00ffffff },
            new float[] { 0.35f, 0.50f, 0.65f },
            Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(discCx, discCy, bounds.width() / 2f, specularPaint);

        canvas.restore();
    }

    /**
     * Renders clear polycarbonate glass specular highlight across front UMD window.
     */
    private void drawFrontGlassSheen(Canvas canvas, float zOffset, float rotX, float rotY, float cx, float cy) {
        canvas.save();
        setup3dMatrix(matrixFront, zOffset + dp(0.5f), rotX, rotY, 0f, 0f, cx, cy);
        canvas.concat(matrixFront);

        float shift = (rotY * 2.5f) - (rotX * 2.0f);
        float w = boundsFront.width();
        LinearGradient glassGrad = new LinearGradient(
            boundsFront.left + shift, boundsFront.top,
            boundsFront.left + shift + w * 0.4f, boundsFront.bottom,
            new int[] { 0x00ffffff, 0x22ffffff, 0x00ffffff },
            new float[] { 0.0f, 0.5f, 1.0f },
            Shader.TileMode.CLAMP
        );
        plasticGlassPaint.setShader(glassGrad);
        canvas.drawOval(boundsFront.left + w * 0.1f, boundsFront.top + w * 0.1f,
                        boundsFront.right - w * 0.1f, boundsFront.bottom - w * 0.1f, plasticGlassPaint);
        plasticGlassPaint.setShader(null);
        canvas.restore();
    }

    /**
     * Renders subtle metallic sheen over back window retaining ring.
     */
    private void drawBackWindowGlow(Canvas canvas, float zOffset, float rotX, float rotY, float cx, float cy) {
        canvas.save();
        setup3dMatrix(matrixBack, zOffset - dp(0.5f), rotX, rotY, 0f, 0f, cx, cy);
        canvas.concat(matrixBack);

        float w = boundsBack.width();
        float ringRadius = w * 0.38f;
        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        ringPaint.setColor(0x33ffffff);
        canvas.drawCircle(boundsBack.centerX(), boundsBack.centerY(), ringRadius, ringPaint);
        canvas.restore();
    }

    /**
     * Draws side plastic rim between front and back shells to create physical 3D cartridge volume.
     */
    private void drawCartridgeEdgeRim(Canvas canvas, float cx, float cy, float thickness, boolean isFrontFacing) {
        if (Math.abs(yaw) < 4f && Math.abs(pitch) < 4f) return;

        // Render subtle extruded rim shadow connecting the layers
        canvas.save();
        setup3dMatrix(matrixFront, 0f, pitch, yaw, roll, 0f, cx, cy);
        canvas.concat(matrixFront);

        float inset = dp(3);
        RectF edgeRect = new RectF(boundsFront.left + inset, boundsFront.top + inset,
                                   boundsFront.right - inset, boundsFront.bottom - inset);
        edgePaint.setColor(isFrontFacing ? 0x22000000 : 0x28000000);
        canvas.drawRoundRect(edgeRect, dp(16), dp(16), edgePaint);
        canvas.restore();
    }

    private void setup3dMatrix(Matrix outMatrix, float zOffset, float rotX, float rotY, float rotZ,
                               float inPlaneAngle, float cx, float cy) {
        camera.save();
        // Camera distance scaled to screen density for realistic perspective without fish-eye
        float dist = -14f * getResources().getDisplayMetrics().density;
        camera.setLocation(0f, 0f, dist);

        // In-plane rotation (for spinning disc)
        if (inPlaneAngle != 0f) {
            camera.rotateZ(-inPlaneAngle);
        }

        // Apply 3D orientation
        camera.rotateX(rotX);
        camera.rotateY(rotY);
        camera.rotateZ(rotZ);

        // Extrude along normal axis
        camera.translate(0f, 0f, zOffset);

        camera.getMatrix(outMatrix);
        camera.restore();

        // Center on view pivot
        outMatrix.preTranslate(-cx, -cy);
        outMatrix.postTranslate(cx, cy);
    }

    // =============================================================================================
    // Interactive Touch & Gesture Handling
    // =============================================================================================
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);

        // Give priority to double tap and fling detection
        if (gestureDetector.onTouchEvent(event)) {
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (snapAnimator != null && snapAnimator.isRunning()) {
                    snapAnimator.cancel();
                }
                isDragging = true;
                inertiaVx = 0f;
                inertiaVy = 0f;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastTouchX;
                float dy = event.getY() - lastTouchY;
                lastTouchX = event.getX();
                lastTouchY = event.getY();

                // Sensitivity factor: comfortable direct-manipulation tracking
                float sensitivity = 0.42f;
                yaw += dx * sensitivity;
                pitch -= dy * sensitivity;
                pitch = Math.max(-80f, Math.min(80f, pitch));
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                velocityTracker.computeCurrentVelocity(1000);
                float vx = velocityTracker.getXVelocity();
                float vy = velocityTracker.getYVelocity();

                // Flick inertia if released with speed
                if (Math.abs(vx) > 180f || Math.abs(vy) > 180f) {
                    inertiaVx = Math.max(-12f, Math.min(12f, vx * 0.016f));
                    inertiaVy = Math.max(-12f, Math.min(12f, vy * 0.016f));
                }
                velocityTracker.recycle();
                velocityTracker = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }

        return super.onTouchEvent(event);
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // Double tap smoothly snaps UMD back to default front-facing orientation
            resetOrientation(true);
            return true;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }
    }

    /**
     * Resets 3D orientation back to default perspective angle.
     */
    public void resetOrientation(boolean animate) {
        if (!animate) {
            pitch = -12f;
            yaw = 18f;
            roll = 0f;
            inertiaVx = 0f;
            inertiaVy = 0f;
            invalidate();
            return;
        }

        if (snapAnimator != null) snapAnimator.cancel();

        final float startPitch = pitch;
        final float startYaw = yaw;
        final float targetPitch = -12f;
        // Snap to nearest 360 wrap of 18 degrees
        float targetYaw = 18f;
        while (startYaw - targetYaw > 180f) targetYaw += 360f;
        while (startYaw - targetYaw < -180f) targetYaw -= 360f;
        final float finalTargetYaw = targetYaw;

        snapAnimator = ValueAnimator.ofFloat(0f, 1f);
        snapAnimator.setDuration(400);
        snapAnimator.setInterpolator(new DecelerateInterpolator(1.8f));
        snapAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            pitch = startPitch + (targetPitch - startPitch) * f;
            yaw = startYaw + (finalTargetYaw - startYaw) * f;
            invalidate();
        });
        snapAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                inertiaVx = 0f;
                inertiaVy = 0f;
            }
        });
        snapAnimator.start();
    }

    // =============================================================================================
    // Disc Art Management & Dynamic Synthesis
    // =============================================================================================

    /**
     * Directly sets a custom disc art bitmap.
     */
    public void setDiscBitmap(Bitmap bitmap) {
        if (bitmap != null) {
            this.discCustom = cropAndMaskDisc(bitmap);
        } else {
            this.discCustom = null;
        }
        invalidate();
    }

    /**
     * Loads disc art asynchronously from a URL (e.g. GameTDB or Libretro PSP Snaps).
     */
    public void setDiscUrl(String url) {
        HTTP_EXECUTOR.execute(() -> {
            Bitmap downloaded = downloadAndCacheDisc(getContext(), url);
            if (downloaded != null) {
                mainHandler.post(() -> setDiscBitmap(downloaded));
            }
        });
    }

    /**
     * Sets game info: attempts to fetch remote disc art by title/id, and automatically
     * falls back to rendering a custom branded UMD disc from the game's title and ICON0.PNG!
     */
    public void setGame(String title, Bitmap iconBitmap) { setGame(title, iconBitmap, null); }

    /**
     * Per-game disc: the label is printed from the game's own artwork (PIC1/PIC0 from its ISO or EBOOT), with its
     * ICON0 and title on top, and spins with the disc. Nothing is fetched from the network (a title lookup could only
     * find screenshots, which replaced the game's real art).
     */
    public void setGame(String title, Bitmap iconBitmap, Bitmap art) {
        this.currentGameTitle = title;
        this.currentGameIcon = iconBitmap;
        artToken++; // a hub fetch for the previous game must not replace this label

        if (title == null && iconBitmap == null) {
            setDiscBitmap(null);
            return;
        }

        // 1. Immediately synthesize high-quality custom game disc art so the user sees it without delay
        setDiscBitmap(renderCustomGameDisc(title, iconBitmap, art));
    }

    private int artToken;

    /**
     * Real UMD label art from the PC hub: /api/art/umd?id=ULUS10567 is a disc scan (LaunchBox "Disc" image) cut to
     * the disc circle, and replaces the synthesized label. Without one, a front cover (/api/art/box) becomes the
     * label artwork when the game has no PIC1/PIC0 of its own. Fetched on a background thread and cached in
     * cache/umd_labels (misses are remembered for a day); the synthesized label stays until something arrives.
     */
    public void loadHubArt(String hubUrl, String discId, boolean hasOwnArt) {
        if (hubUrl == null || hubUrl.isEmpty() || discId == null || !discId.matches("[A-Z]{4}\\d{5}")) return;
        final String base = hubUrl.replaceAll("/+$", "");
        final String title = currentGameTitle;
        final Bitmap icon = currentGameIcon;
        final int token = artToken;
        final File dir = new File(getContext().getCacheDir(), "umd_labels");
        HTTP_EXECUTOR.execute(() -> {
            Bitmap label = cachedOrFetch(dir, discId + "_umd.png", base + "/api/art/umd?id=" + discId);
            if (label != null) {
                mainHandler.post(() -> { if (token == artToken) setDiscBitmap(label); });
                return;
            }
            if (hasOwnArt) return;
            Bitmap box = cachedOrFetch(dir, discId + "_box.png", base + "/api/art/box?sys=psp&id=" + discId);
            if (box == null) return;
            Bitmap disc = renderCustomGameDisc(title, icon, box);
            mainHandler.post(() -> { if (token == artToken) setDiscBitmap(disc); });
        });
    }

    /** A cached image, or a fresh download of it (HTTP 200 only); a miss is remembered for a day in "<name>.none". */
    private static Bitmap cachedOrFetch(File dir, String name, String url) {
        File f = new File(dir, name), miss = new File(dir, name + ".none");
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
        }
        if (miss.isFile() && System.currentTimeMillis() - miss.lastModified() < 24L * 3600 * 1000) return null;
        try {
            dir.mkdirs();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(40000); // the hub may be cutting the label from a scan on first request
            int code = c.getResponseCode();
            if (code == 200) {
                File tmp = new File(dir, name + ".part");
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                Bitmap b = BitmapFactory.decodeFile(tmp.getAbsolutePath());
                if (b != null && tmp.renameTo(f)) { miss.delete(); return b; }
                tmp.delete();
                return b;
            }
            if (code == 404) new FileOutputStream(miss).close();
            c.disconnect();
        } catch (Throwable ignored) {}
        return null;
    }

    private Bitmap tryFetchRemoteDiscArt(String title) {
        try {
            String encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20");
            // Candidate 1: Libretro PSP Named_Snaps
            String url1 = "https://raw.githubusercontent.com/libretro-thumbnails/Sony_-_PlayStation_Portable/master/Named_Snaps/" + encoded + ".png";
            Bitmap b = downloadAndCacheDisc(getContext(), url1);
            if (b != null) return b;

            // Candidate 2: Libretro PSP Named_Titles
            String url2 = "https://raw.githubusercontent.com/libretro-thumbnails/Sony_-_PlayStation_Portable/master/Named_Titles/" + encoded + ".png";
            b = downloadAndCacheDisc(getContext(), url2);
            if (b != null) return b;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Bitmap downloadAndCacheDisc(Context context, String urlString) {
        if (context == null || urlString == null) return null;
        try {
            File cacheDir = new File(context.getCacheDir(), "umd_discs");
            if (!cacheDir.exists()) cacheDir.mkdirs();

            String cacheName = Math.abs(urlString.hashCode()) + ".png";
            File cachedFile = new File(cacheDir, cacheName);
            if (cachedFile.isFile() && cachedFile.length() > 1024) {
                Bitmap b = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
                if (b != null) return b;
            }

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "PXP2-UMD/1.0");

            int code = conn.getResponseCode();
            if (code == 200) {
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(cachedFile)) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.flush();
                }
                Bitmap b = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
                if (b != null) return b;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Synthesizes an authentic custom UMD disc art on the fly using game title and ICON0.PNG.
     */
    private Bitmap renderCustomGameDisc(String title, Bitmap icon, Bitmap art) {
        int size = 512;
        Bitmap disc = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(disc);

        // Drawn edge to edge: setDiscBitmap() maps this whole square onto the disc and masks it once. (It used to be
        // drawn at r=216 and masked twice, which shrank the label and left an empty ring.)
        float cx = size / 2f;
        float cy = size / 2f;
        float rOut = size / 2f;
        float rIn = rOut * 0.17f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 1. Dark metallic / titanium disc substrate background
        int baseColor = 0xff1e1e24;
        if (icon != null) {
            // Sample accent color from icon for disc color theme
            int sample = icon.getPixel(Math.min(10, icon.getWidth() - 1), Math.min(10, icon.getHeight() - 1));
            if (sample != 0) {
                int r = Color.red(sample);
                int g = Color.green(sample);
                int b = Color.blue(sample);
                baseColor = Color.rgb(Math.max(20, r / 3), Math.max(20, g / 3), Math.max(30, b / 3));
            }
        }
        p.setColor(baseColor);
        c.drawCircle(cx, cy, rOut, p);

        // 1b. The game's own artwork printed across the whole label (centre-cropped), darkened toward the hub and rim
        if (art != null && !art.isRecycled()) {
            float s = Math.max(size / (float)art.getWidth(), size / (float)art.getHeight());
            float w = art.getWidth() * s, h = art.getHeight() * s;
            c.drawBitmap(art, null, new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
            shade.setShader(new RadialGradient(cx, cy, rOut, new int[]{0x99000000, 0x00000000, 0x00000000, 0x88000000},
                new float[]{0.18f, 0.32f, 0.82f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rOut, shade);
        }

        // 2. Optical concentric data track micro-grooves
        p.setStyle(Paint.Style.STROKE);
        for (float r = rIn + 10f; r < rOut - 6f; r += 7f) {
            p.setColor(((int)(r * 13) % 2 == 0) ? 0x18ffffff : 0x0effffff);
            p.setStrokeWidth(1.2f);
            c.drawCircle(cx, cy, r, p);
        }
        p.setStyle(Paint.Style.FILL);

        // 3. Render Game ICON0.PNG centered on the upper disc quadrant
        if (icon != null && !icon.isRecycled()) {
            float iconMaxW = rOut * 1.05f;
            float iconMaxH = rOut * 0.65f;
            float scale = Math.min(iconMaxW / icon.getWidth(), iconMaxH / icon.getHeight());
            float scaledW = icon.getWidth() * scale;
            float scaledH = icon.getHeight() * scale;

            float iconX = cx - scaledW / 2f;
            float iconY = cy - rOut * 0.62f;

            // Icon soft shadow
            Paint iconShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconShadow.setColor(0x66000000);
            c.drawRoundRect(iconX - 2, iconY - 2, iconX + scaledW + 2, iconY + scaledH + 2, 8, 8, iconShadow);

            c.drawBitmap(icon, null, new RectF(iconX, iconY, iconX + scaledW, iconY + scaledH), new Paint(Paint.FILTER_BITMAP_FLAG));
        }

        // 4. Render Game Title curved or formatted below center
        if (title != null && !title.trim().isEmpty()) {
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(20f);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setShadowLayer(4f, 0f, 2f, 0xbb000000);

            String displayTitle = title.trim();
            if (displayTitle.length() > 22) {
                displayTitle = displayTitle.substring(0, 20) + "…";
            }
            c.drawText(displayTitle, cx, cy + rOut * 0.46f, textPaint);

            // Subtle PSP / UMD branding mark
            Paint subText = new Paint(Paint.ANTI_ALIAS_FLAG);
            subText.setColor(0x88ffffff);
            subText.setTextSize(11f);
            subText.setTypeface(Typeface.MONOSPACE);
            subText.setTextAlign(Paint.Align.CENTER);
            c.drawText("UNIVERSAL MEDIA DISC", cx, cy + rOut * 0.66f, subText);
        }

        // 5. Punch out center spindle hole with smooth anti-aliased edge
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawCircle(cx, cy, rIn, p);
        p.setXfermode(null);

        // Returned edge to edge: setDiscBitmap() maps it onto the disc and masks it exactly once (masking here as well
        // shrank the label to r = 216 * 216 / 256 and left an empty ring).
        return disc;
    }

    private static Bitmap cropAndMaskDisc(Bitmap src) {
        if (src == null) return null;
        int target = 512;
        Bitmap out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        float cx = target / 2f;
        float cy = target / 2f;
        float rOut = 216f;
        float rIn = rOut * 0.17f;

        // Draw source scaled
        c.drawBitmap(src, null, new RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut), new Paint(Paint.FILTER_BITMAP_FLAG));

        // Mask outer circle and punch inner hole
        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

        Bitmap maskBmp = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(maskBmp);
        Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(Color.WHITE);
        maskCanvas.drawCircle(cx, cy, rOut, mp);
        mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        maskCanvas.drawCircle(cx, cy, rIn, mp);

        c.drawBitmap(maskBmp, 0, 0, maskPaint);
        maskBmp.recycle();

        return out;
    }

    // =============================================================================================
    // Fallback Texture Generators (in case resources fail to load)
    // =============================================================================================
    private Bitmap createFallbackCaddy(boolean isFront) {
        int s = 512;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // UMD shell shape
        p.setColor(0xffe8e8ec);
        RectF r = new RectF(24, 24, s - 24, s - 24);
        c.drawRoundRect(r, 48, 48, p);

        p.setColor(isFront ? 0xffdcdce0 : 0xffcccccc);
        p.setStrokeWidth(dp(3));
        p.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(r, 48, 48, p);
        p.setStyle(Paint.Style.FILL);

        // Cutout window
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawCircle(s / 2f, s / 2f, 216f, p);
        p.setXfermode(null);

        if (isFront) {
            // Front center hub
            p.setColor(0xffe0e0e4);
            c.drawCircle(s / 2f, s / 2f, 44f, p);
            p.setColor(0xff555555);
            p.setTextSize(13f);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("PSP", s / 2f, s / 2f + 5f, p);
        }
        return b;
    }

    private Bitmap createFallbackDisc() {
        int s = 512;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = s / 2f;
        float cy = s / 2f;
        float rOut = 216f;
        float rIn = 36f;

        p.setColor(0xffcc1122); // Red disc
        c.drawCircle(cx, cy, rOut, p);

        p.setColor(0x33ffffff);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        c.drawCircle(cx, cy, rOut * 0.7f, p);
        c.drawCircle(cx, cy, rOut * 0.45f, p);
        p.setStyle(Paint.Style.FILL);

        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawCircle(cx, cy, rIn, p);
        return b;
    }

    // =============================================================================================
    // Public Getters / Setters & Configuration
    // =============================================================================================
    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { this.pitch = pitch; invalidate(); }

    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; invalidate(); }

    public void setPitchAndYaw(float pitch, float yaw) {
        this.pitch = pitch;
        this.yaw = yaw;
        invalidate();
    }

    public boolean isSpinning() { return isSpinning; }
    public void setSpinning(boolean spinning) {
        this.isSpinning = spinning;
        invalidate();
    }

    public void toggleSpin() {
        setSpinning(!isSpinning);
    }

    public float getSpinRpm() { return spinRpm; }
    public void setSpinRpm(float spinRpm) {
        this.spinRpm = Math.max(0f, Math.min(300f, spinRpm));
    }

    public boolean isIdleFloatEnabled() { return idleFloatEnabled; }
    public void setIdleFloatEnabled(boolean enabled) {
        this.idleFloatEnabled = enabled;
    }
}
