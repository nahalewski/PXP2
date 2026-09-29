package org.pxp2.fresh;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.*;
import android.hardware.input.InputManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.FrameLayout;
import java.io.File;
import org.pxp2.core.PspSystem;

/**
 * Runs the real Sony VSH on PXP2's core and presents it full screen. Emulation runs on its own thread in
 * vblank-sized slices paced to 59.94 Hz. Input comes from on-screen touch controls and physical gamepads.
 */
public final class XmbActivity extends Activity implements SurfaceHolder.Callback {
    // PSP controller bits
    static final int SELECT = 0x1, START = 0x8, UP = 0x10, RIGHT = 0x20, DOWN = 0x40, LEFT = 0x80, L = 0x100, R = 0x200,
        TRIANGLE = 0x1000, CIRCLE = 0x2000, CROSS = 0x4000, SQUARE = 0x8000, HOME = 0x10000,
        VOLUP = 0x100000, VOLDOWN = 0x200000, // PSP volume buttons (the XMB draws its own volume bar)
        DISC = 0x40000000, // not a PSP button: opens the UMD swap menu
        MENU = 0x20000000; // not a PSP button: opens the PXP2 quick menu
    public static final String FILTER = "filter", REFRESH = "refresh", ASPECT = "aspect", UMD = "umd";
    /**
     * Enhancements (SharedPreferences "pxp2"): RESOLUTION is the internal render scale (1 = native 480×272, 2, 3, ...;
     * 0 = Max, the largest scale the screen can show); TEXTURE_FILTER 0 off / 1 bilinear / 2 enhanced (Scale2x-style
     * upscale + bilinear); ANTIALIAS 0 off / 1 SSAA 2× (renders at twice the chosen scale and averages) / 2 edge smoothing.
     */
    public static final String RESOLUTION = "resolution", TEXTURE_FILTER = "texture_filter", ANTIALIAS = "antialias";
    /** Largest render scale used (memory: one 8888 frame at scale k is 512·272·4·k² bytes, plus depth and the back buffer). */
    static final int MAX_SCALE = 8;
    /** The largest useful scale on a w×h screen: the most PSP pixels per screen pixel along the limiting axis (at least 1). */
    static int maxScale(int w, int h) {
        int longSide = Math.max(w, h), shortSide = Math.min(w, h);
        return Math.max(1, Math.min(MAX_SCALE, Math.min(longSide / 480, shortSide / 272)));
    }
    /** Intent extra: an http(s) URL of an ISO on the PC hub to insert as the UMD for this session (streamed, not saved). */
    public static final String STREAM_UMD = "stream_umd";
    /** Optional: boot this executable directly instead of the XMB (e.g. "ms0:/PSP/GAME/X/EBOOT.PBP"); exiting returns to the XMB. */
    public static final String BOOT_PATH = "boot_path";

    private SurfaceView surface; private ControlsView controls;
    private volatile SurfaceHolder holder;
    private volatile boolean running;
    private Thread emulation;
    private volatile int padButtons, touchButtons, gestureButtons, analogX = 128, analogY = 128; private int axisTriggers, axisHat;
    private int filter, refresh, aspect, resolution, textureFilter, antialias;
    private final Bitmap frame = Bitmap.createBitmap(480, 272, Bitmap.Config.ARGB_8888);
    /** Frame at render resolution (internal resolution / anti-aliasing), recreated when its size changes; shown is what present draws. */
    private Bitmap hiFrame; private volatile Bitmap shown = frame;
    private volatile int screenMaxScale = 1, renderScale = 1;
    private volatile boolean framesShown;
    private Bitmap scaled; private volatile String status = "Starting PSP system...";
    private long frames, lastFpsTime; private float fps; private volatile float cpuMs, geMs, presentMs;
    private volatile int batteryPercent = 100, batteryTemp = 28, batteryVolt = 4135;
    private volatile boolean batteryCharging, batteryPlugged = true;

    private void updateBattery(Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = (level >= 0 && scale > 0) ? Math.min(100, Math.max(0, Math.round((level * 100f) / scale))) : 100;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean powerOnline = plugged > 0;
        int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) / 10;
        int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4135);

        batteryPercent = percent; batteryCharging = charging; batteryPlugged = powerOnline;
        batteryTemp = temp; batteryVolt = voltage;
        PspSystem p = live;
        if (p != null) p.system.updateBattery(percent, charging, powerOnline, temp, voltage);
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        filter = getPreferences().getInt(FILTER, 2); refresh = getPreferences().getInt(REFRESH, 60); aspect = getPreferences().getInt(ASPECT, 0);
        resolution = getPreferences().getInt(RESOLUTION, 1); textureFilter = getPreferences().getInt(TEXTURE_FILTER, 0); antialias = getPreferences().getInt(ANTIALIAS, 0);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics(); screenMaxScale = maxScale(dm.widthPixels, dm.heightPixels);
        Intent initialBattery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (initialBattery != null) updateBattery(initialBattery);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this); surface.getHolder().addCallback(this);
        controls = new ControlsView(this);
        padMode = getPreferences().getInt(PAD_MODE, 0);
        homeMenu = new HomeMenuView(this, this::onHomeAnswer); quickMenu = new QuickMenuView(this, this::onQuickMenuClosed);
        root.addView(surface); root.addView(controls); root.addView(homeMenu); root.addView(quickMenu); // overlays above the pad
        applyBrightness(getPreferences().getInt(BRIGHTNESS, 0));
        setContentView(root);
        hideSystemUi();
    }
    private android.content.SharedPreferences getPreferences() { return getSharedPreferences("pxp2", MODE_PRIVATE); }
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) hideSystemUi(); }

    @Override public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        if (Build.VERSION.SDK_INT >= 30) {
            // Clamp to the panel's best supported rate (e.g. 144 Hz on the Razer Edge, 120 Hz on many Snapdragon phones).
            float best = 60; for (Display.Mode mode : getDisplay().getSupportedModes()) best = Math.max(best, mode.getRefreshRate());
            h.getSurface().setFrameRate(Math.min(refresh, best), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        }
    }
    @Override public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        holder = h; scaled = null;
        // True widescreen factor: how much wider the screen is than the PSP's 480:272 (capped so 2D stays sensible).
        wideFactor = height > 0 ? Math.max(1f, Math.min(1.6f, (width / (float)height) / (480f / 272f))) : 1f;
        screenMaxScale = maxScale(width, height);
    }
    /** Internal render scale for the chosen resolution: fixed values are capped at what the screen can show (0 = Max). */
    private int chosenScale() { int m = screenMaxScale; return resolution <= 0 ? m : Math.max(1, Math.min(resolution, m)); }
    private volatile float wideFactor = 1f;
    private volatile Rect picture; private volatile float pictureWide = 1f;
    @Override public void surfaceDestroyed(SurfaceHolder h) { holder = null; }

    private InputManager inputManager;
    private InputManager.InputDeviceListener inputListener;

    private static boolean isGameController(InputDevice dev) {
        if (dev == null || dev.isVirtual()) return false;
        int sources = dev.getSources();
        return ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
            || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    private static boolean hasPhysicalController() {
        int[] ids = InputDevice.getDeviceIds();
        if (ids == null) return false;
        for (int id : ids) {
            InputDevice dev = InputDevice.getDevice(id);
            if (isGameController(dev)) return true;
        }
        return false;
    }

    private void updateControllerVisibility() {
        // Hide the on-screen pad whenever a physical controller is attached (Kishi, DualSense, the Razer Edge's built-in
        // controls...) and bring it back when the last one disconnects. Touching the screen still shows it again.
        runOnUiThread(() -> { if (controls != null) controls.setAutoHidden(hasPhysicalController()); });
    }

    @Override protected void onResume() {
        super.onResume(); running = true;
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, batteryFilter);
        }
        if (inputManager == null) inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null && inputListener == null) {
            inputListener = new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) { updateControllerVisibility(); }
                @Override public void onInputDeviceRemoved(int id) { updateControllerVisibility(); }
                @Override public void onInputDeviceChanged(int id) { updateControllerVisibility(); }
            };
            inputManager.registerInputDeviceListener(inputListener, null);
        }
        updateControllerVisibility();
        emulation = new Thread(this::emulate, "PXP2 emulation"); emulation.setPriority(Thread.MAX_PRIORITY); emulation.start();
    }
    @Override protected void onPause() {
        running = false;
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        if (inputManager != null && inputListener != null) {
            inputManager.unregisterInputDeviceListener(inputListener);
            inputListener = null;
        }
        padButtons = 0; axisTriggers = 0; axisHat = 0;
        if (anyMenuOpen()) closeMenus(); // HOME screen answers No, quick menu closes
        if (emulation != null) { emulation.interrupt(); try { emulation.join(2000); } catch (InterruptedException ignored) { } }
        if (framesShown) XmbTheme.saveWallpaper(this, shown); // the app's backdrop follows the XMB theme
        super.onPause();
    }
    @Override protected void onDestroy() {
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void hookRegistryListener(PspSystem systemInstance, File flash1) {
        if (systemInstance == null) return;
        systemInstance.system.registry.addChangeListener((cat, key, val) -> {
            if (cat.contains("THEME") || cat.contains("XMB")) {
                int color = LauncherIconManager.resolveColorIndex(systemInstance.system.registry, flash1);
                runOnUiThread(() -> LauncherIconManager.setIconColor(XmbActivity.this, color));
            }
        });
    }

    // ---- emulation ------------------------------------------------------------------------------
    private void emulate() {
        try {
            File install = new File(getFilesDir(), "firmware/flash-661-01g-v1");
            if (!new File(install, "flash0/vsh/module/vshmain.prx").isFile()) { status = "Firmware is not installed. Go back and install the Sony 6.61 virtual flash first."; present(false); return; }
            File storage = new File(getExternalFilesDir(null), "psp");
            File flash1 = new File(storage, "flash1");
            PspSystem.exitAppIcon = exitIcon(); // icon for the XMB's "Exit to PXP2" entry
            handOverLibrary();
            // System memory setting (Settings → System memory; "pxp2" prefs key "ram_mb"): 32, 64 (default) or 128 MiB.
            int ram = getPreferences().getInt(RecoveryActivity.RAM_MB, PspSystem.DEFAULT_RAM_MB);
            if (ram != 32 && ram != 64 && ram != 128) ram = PspSystem.DEFAULT_RAM_MB;
            PspSystem psp = new PspSystem(new File(install, "flash0"), storage, PxpStorage.memoryStick(this), ram);
            psp.system.updateBattery(batteryPercent, batteryCharging, batteryPlugged, batteryTemp, batteryVolt);
            hookRegistryListener(psp, flash1);
            int initialColor = LauncherIconManager.resolveColorIndex(psp.system.registry, flash1);
            runOnUiThread(() -> LauncherIconManager.setIconColor(XmbActivity.this, initialColor));
            String stream = getIntent().getStringExtra(STREAM_UMD), umd = stream != null ? stream : getPreferences().getString(UMD, "");
            if (!umd.isEmpty()) try { psp.insertUmd(org.pxp2.core.IsoSource.open(umd)); } catch (java.io.IOException e) { status = "Disc image could not be opened: " + e.getMessage(); }
            String bootPath = getIntent().getStringExtra(BOOT_PATH);
            if (bootPath != null && !bootPath.isEmpty()) psp.gamePath = bootPath; // direct homebrew launch from the app
            audio = new AndroidAudio(); video = new AndroidVideo();
            while (running && psp != null) {
                PspSystem current = psp; live = current;
                current.system.updateBattery(batteryPercent, batteryCharging, batteryPlugged, batteryTemp, batteryVolt);
                current.media.mixer.sink = audio; current.media.decoder = audio; // host audio output and MP3 decoding
                current.video.decoder = video; // H.264 for the video player (MediaCodec)
                String result = current.startVsh(400_000_000L);
                if (!result.equals("Completed")) { status = "Stopped: " + result; present(false); return; }
                current.system.onVblank = () -> current.kernel.stopRequested = true; // one run slice per vblank
                status = null;
                long next = System.nanoTime();
                if (carriedVolume >= 0) pendingVolume = carriedVolume; // keep the volume over game boot / exit reboots
                int savedBrightness = getPreferences().getInt(BRIGHTNESS, 0); if (savedBrightness > 0) pendingPspBrightness = pspBrightnessLevel(savedBrightness);
                while (running) {
                    applyMenuChanges(current);
                    if (menuPaused) { Thread.sleep(20); next = System.nanoTime(); continue; } // HOME / quick menu open: emulation paused
                    // DISC and MENU are PXP2's own; in a game HOME belongs to the HOME menu and never reaches the game.
                    current.system.buttons = (padButtons | touchButtons | gestureButtons) & ~(DISC | MENU | (current.inGame() ? HOME : 0)); current.system.analogX = analogX; current.system.analogY = analogY;
                    current.renderer.widescreen = aspect == 2 ? wideFactor : 1f;
                    int ssaa = antialias == 1 ? 2 : 1, scale = chosenScale(); renderScale = scale;
                    current.renderer.setScale(Math.min(MAX_SCALE, scale * ssaa)); current.renderer.textureFilter = textureFilter;
                    Integer vol = current.vsh.impose.get(org.pxp2.core.HleVsh.IMPOSE_VOLUME), mute = current.vsh.impose.get(org.pxp2.core.HleVsh.IMPOSE_MUTE); // 0..30, mute
                    current.media.mixer.gain = mute != null && mute != 0 ? 0f : Math.max(0, Math.min(30, vol == null ? 15 : vol)) / 30f;
                    long t0 = System.nanoTime(), g0 = current.ge.nanos;
                    result = current.run(50_000_000L);
                    long t1 = System.nanoTime(); geMs = (current.ge.nanos - g0) / 1e6f; cpuMs = (t1 - t0) / 1e6f - geMs;
                    if (current.vsh.exitToHostRequested) { if (framesShown) XmbTheme.saveWallpaper(this, shown); runOnUiThread(this::finish); return; } // "Exit to PXP2"
                    String message = current.vsh.hostMessage; // e.g. a library game that could not be streamed or downloaded
                    if (message != null) { current.vsh.hostMessage = null; runOnUiThread(() -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()); }
                    if (current.restartPending()) break; // VSH restart, disc boot or game exit
                    if (!result.startsWith("Stopped")) { status = "Stopped: " + result; present(false); return; }
                    if (current.renderer.scale() == 1 && antialias != 2) { // native: the 1× VRAM picture, exactly as before
                        int[] px = current.system.snapshot();
                        if (px != null) { frame.setPixels(px, 0, 480, 0, 0, 480, 272); shown = frame; present(true); framesShown = true; }
                    } else { // the high-resolution shadow of the displayed buffer, drawn as is (no 1× round trip)
                        int[] px = current.renderer.frame(current.system.frameBuffer, current.system.frameWidth, current.system.pixelFormat, ssaa, antialias == 2);
                        if (px != null) {
                            int fw = current.renderer.frameWidth, fh = current.renderer.frameHeight;
                            if (hiFrame == null || hiFrame.getWidth() != fw || hiFrame.getHeight() != fh) hiFrame = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                            hiFrame.setPixels(px, 0, fw, 0, 0, fw, fh); shown = hiFrame; present(true); framesShown = true;
                        }
                    }
                    presentMs = (System.nanoTime() - t1) / 1e6f;
                    next += 16_683_000L; long wait = next - System.nanoTime();
                    if (wait > 0) Thread.sleep(wait / 1_000_000L, (int)(wait % 1_000_000L)); else if (wait < -100_000_000L) next = System.nanoTime();
                }
                Integer volume = current.vsh.impose.get(org.pxp2.core.HleVsh.IMPOSE_VOLUME); carriedVolume = volume == null ? -1 : volume;
                psp = current.restartIfRequested();
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable e) { status = "Stopped: " + e; present(false); }
        finally { if (audio != null) { audio.release(); audio = null; } if (video != null) { video.release(); video = null; } }
    }
    private AndroidAudio audio; private AndroidVideo video;
    /**
     * Library hand-off: the ISOs in PXP/ISO and the last PC hub catalog (saved by the Server tab) become virtual entries
     * in the XMB Game column (org.pxp2.core.GameLibrary); their metadata is cached in the app cache.
     */
    private void handOverLibrary() {
        PspSystem.libraryCache = new File(getCacheDir(), "library");
        java.util.List<org.pxp2.core.LibraryEntry> library = new java.util.ArrayList<>(org.pxp2.core.GameLibrary.localIsos(new File(PxpStorage.memoryStick(this), "ISO")));
        android.content.SharedPreferences p = getPreferences();
        library.addAll(org.pxp2.core.GameLibrary.parseCatalog(p.getString(ServerTransferView.PREF_SERVER_URL, ""), p.getString(ServerTransferView.PREF_CATALOG_JSON, "")));
        PspSystem.setLibrary(library);
    }
    /**
     * Draws the current frame scaled to fit with the chosen filter: 0 pixel, 1 smooth, 2 sharp (integer prescale + smooth).
     * The frame is 480×272 or, at a higher internal resolution, that many times larger; the sharp filter's prescale only
     * fills the remaining gap to the screen (none when the frame already matches it).
     */
    private void present(boolean haveFrame) {
        SurfaceHolder h = holder; if (h == null) return;
        Canvas c = Build.VERSION.SDK_INT >= 26 ? h.lockHardwareCanvas() : h.lockCanvas();
        if (c == null) return;
        try {
            c.drawColor(Color.BLACK);
            int w = c.getWidth(), ht = c.getHeight();
            // Screen modes: 0 original, 1 stretch to fill, 2 true widescreen (wider rendered frame; until rendered, shown as original).
            float wide = aspect == 2 ? wideFactor : 1f; // widescreen frames are anamorphic: stretch them back out
            float scale = Math.min(w / (480f * wide), ht / 272f);
            int dw = aspect == 1 ? w : Math.round(480 * wide * scale), dh = aspect == 1 ? ht : Math.round(272 * scale);
            if (tall(w, ht)) dh = Math.round(272 * scale); // unfolded/portrait: picture on top, controls below it
            int top = tall(w, ht) ? 0 : (ht - dh) / 2;
            Rect dst = new Rect((w - dw) / 2, top, (w + dw) / 2, top + dh);
            picture = dst; pictureWide = wide; // for mapping touches back to PSP screen coordinates
            if (haveFrame) {
                Paint p = new Paint(); p.setFilterBitmap(filter != 0);
                Bitmap source = shown; int sw = source.getWidth(), sh = source.getHeight();
                if (filter == 2) {
                    int k = Math.max(1, (int)Math.floor(Math.min(dw / (float)sw, dh / (float)sh)));
                    if (k > 1) {
                        if (scaled == null || scaled.getWidth() != sw * k || scaled.getHeight() != sh * k) scaled = Bitmap.createBitmap(sw * k, sh * k, Bitmap.Config.ARGB_8888);
                        new Canvas(scaled).drawBitmap(source, null, new Rect(0, 0, sw * k, sh * k), new Paint());
                        source = scaled;
                    }
                }
                c.drawBitmap(source, null, dst, p);
                frames++; long now = System.nanoTime();
                if (now - lastFpsTime > 1_000_000_000L) {
                    fps = frames * 1e9f / (now - lastFpsTime); frames = 0; lastFpsTime = now;
                    android.util.Log.i("PXP2", String.format(java.util.Locale.ROOT, "perf %.1f fps cpu %.1f ge %.1f present %.1f ms scale %d frame %dx%d", fps, cpuMs, geMs, presentMs, renderScale, sw, sh));
                }
                Paint t = new Paint(); t.setColor(0x80ffffff); t.setTextSize(28); t.setAntiAlias(true);
                c.drawText(String.format(java.util.Locale.ROOT, "%.0f fps  cpu %.1f  ge %.1f  present %.1f ms  %dx%s", fps, cpuMs, geMs, presentMs, renderScale,
                    antialias == 1 ? " ssaa" : antialias == 2 ? " smooth" : ""), Math.max(12, dst.left + 12), Math.max(34, dst.top + 34), t); // on screen even when the picture overhangs it
            }
            String s = status;
            if (s != null) {
                Paint t = new Paint(); t.setColor(Color.WHITE); t.setTextSize(36); t.setAntiAlias(true);
                int y = ht / 3; for (String line : s.split("\n")) { c.drawText(line.length() > 90 ? line.substring(0, 90) : line, 40, y, t); y += 44; }
            }
        } finally { h.unlockCanvasAndPost(c); }
    }

    // ---- gamepad and keyboard -------------------------------------------------------------------
    private static int keyBit(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return UP; case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT; case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            // Cross: A / Enter / X / Button 1 (PS4/PS5)
            // (Face buttons are positional: DS4/DualSense with Android's Sony layouts, Kishi and Xbox pads report the
            // bottom button as BUTTON_A. Pads without a layout send raw HID BUTTON_1..4 = square, cross, circle, triangle.)
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_2:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_X: return CROSS;
            // Circle: B / Esc / O / raw HID Button 3
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_3:
            case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_O: return CIRCLE;
            // Square: X / S / raw HID Button 1 / Button C
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_1:
            case KeyEvent.KEYCODE_BUTTON_C: case KeyEvent.KEYCODE_S: return SQUARE;
            // Triangle: Y / T / Button 4 (PS4/PS5) / Button Z
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_BUTTON_4:
            case KeyEvent.KEYCODE_BUTTON_Z: case KeyEvent.KEYCODE_T: return TRIANGLE;
            // L1 / L2 / Button 5 / Button 7 / Q
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_5: case KeyEvent.KEYCODE_BUTTON_7:
            case KeyEvent.KEYCODE_Q: return L;
            // R1 / R2 / Button 6 / Button 8 / E
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_6: case KeyEvent.KEYCODE_BUTTON_8:
            case KeyEvent.KEYCODE_E: return R;
            // Start / Options / Menu / Button 10 / Space
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_BUTTON_10:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_SPACE: return START;
            // Select / Share / Create / Button 9 / Tab
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_BUTTON_9:
            case KeyEvent.KEYCODE_TAB: return SELECT;
            // Home / PS / Nexus / Mode / H / Home
            case KeyEvent.KEYCODE_BUTTON_MODE: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_H: return HOME;
            // Phone volume keys drive the PSP's volume; consuming them also keeps Android's volume panel from appearing.
            case KeyEvent.KEYCODE_VOLUME_UP: return VOLUP; case KeyEvent.KEYCODE_VOLUME_DOWN: return VOLDOWN;
            default: return 0;
        }
    }
    private final long[] keyDownAt = new long[32];
    // BACK is read as a key on purpose: a controller's Select/View button arrives as KEYCODE_BACK (Razer Kishi) and must
    // be the PSP's SELECT, not navigation. The app does not opt in to predictive back, so BACK keys still arrive here.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode(), bit = keyBit(code);
        boolean pad = fromController(e);
        // Razer Kishi (and other pads) may send Select/View as BACK: that is SELECT. A touch-screen or navigation-bar
        // BACK asks before leaving the emulator (or closes an open menu).
        if (code == KeyEvent.KEYCODE_BACK) {
            if (pad) bit = SELECT;
            else { if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) { if (anyMenuOpen()) closeMenus(); else confirmLeave(); } return true; }
        }
        if (pad && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) noteKey(e, bit);
        if (anyMenuOpen()) { // menus take the controller; the paused game sees nothing
            if (bit == 0) return super.dispatchKeyEvent(e);
            if (e.getAction() == KeyEvent.ACTION_DOWN && (e.getRepeatCount() == 0 || (bit & (UP | DOWN | LEFT | RIGHT)) != 0)) menuButton(bit);
            return true;
        }
        if (bit == HOME && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) homeDown();
        else if (bit == HOME && e.getAction() == KeyEvent.ACTION_UP) homeUp();
        if (bit == 0) return super.dispatchKeyEvent(e);
        // Presses are held for at least ~2 frames: the PSP samples buttons once per vblank, so an instant tap would be lost.
        if (e.getAction() == KeyEvent.ACTION_DOWN) { padButtons |= bit; keyDownAt[Integer.numberOfTrailingZeros(bit)] = e.getEventTime(); }
        else if (e.getAction() == KeyEvent.ACTION_UP) {
            long remaining = 40 - (e.getEventTime() - keyDownAt[Integer.numberOfTrailingZeros(bit)]);
            int released = bit;
            if (remaining > 0) controls.postDelayed(() -> padButtons &= ~released, remaining); else padButtons &= ~bit;
        }
        if (((e.getSource() & InputDevice.SOURCE_GAMEPAD) != 0 || (e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) && (bit & (VOLUP | VOLDOWN)) == 0) {
            controls.setAutoHidden(true);
        }
        return true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) == 0 || e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
        // Apply 12% radial deadzone to eliminate analog stick drift on Kishi and controllers
        float mag = (float)Math.sqrt(x * x + y * y);
        float deadzone = 0.12f;
        if (mag > deadzone) {
            float scale = (mag - deadzone) / (1f - deadzone);
            x = (x / mag) * scale;
            y = (y / mag) * scale;
        } else {
            x = 0; y = 0;
        }
        analogX = Math.max(0, Math.min(255, Math.round(128 + x * 127)));
        analogY = Math.max(0, Math.min(255, Math.round(128 + y * 127)));
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int hat = (hx < -0.5f ? LEFT : 0) | (hx > 0.5f ? RIGHT : 0) | (hy < -0.5f ? UP : 0) | (hy > 0.5f ? DOWN : 0);
        // Analog triggers report on LTRIGGER/BRAKE and RTRIGGER/GAS (Kishi, Razer Edge, DualShock 4, DualSense). AXIS_Z/RZ
        // are the RIGHT STICK on these pads, so they must not count as triggers (moving the stick used to press L/R).
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        int triggers = (lt > 0.5f ? L : 0) | (rt > 0.5f ? R : 0);
        if (anyMenuOpen()) { // hat and left stick navigate the menus (edge-triggered)
            int dirs = hat | (x < -0.6f ? LEFT : 0) | (x > 0.6f ? RIGHT : 0) | (y < -0.6f ? UP : 0) | (y > 0.6f ? DOWN : 0), pressed = dirs & ~menuDirs;
            menuDirs = dirs; analogX = analogY = 128;
            for (int b : new int[]{UP, DOWN, LEFT, RIGHT}) if ((pressed & b) != 0) menuButton(b);
            return true;
        }
        menuDirs = 0;
        // Only release what the axes pressed: D-pad buttons that arrive as key events (Kishi, DS4) stay held.
        padButtons = (padButtons & ~(axisTriggers | axisHat)) | hat | triggers;
        axisTriggers = triggers;
        axisHat = hat;
        controls.setAutoHidden(true);
        return true;
    }

    // ---- HOME menu and PXP2 quick menu ----------------------------------------------------------
    /**
     * HOME: in a game or homebrew a short press opens the HOME screen ("Do you want to quit the game?"); Yes quits the
     * way the PSP does (PspSystem.homeQuit: the game's exit callback, else straight back to the XMB). In the XMB a
     * short press goes to the VSH as before. A long press (either mode) opens the PXP2 quick menu. Both pause emulation.
     */
    static final String PAD_MODE = "pad_mode", BRIGHTNESS = "brightness";
    private static final long HOME_LONG_PRESS_MS = 600;
    private HomeMenuView homeMenu; private QuickMenuView quickMenu;
    private volatile boolean menuPaused;
    private volatile int pendingVolume = -1, pendingPspBrightness = -1, carriedVolume = -1;
    private int padMode, menuDirs; private boolean homeHeld, homeLongFired, leaveDialog;
    private volatile String lastKey = "none yet";
    private final Runnable homeLongPress = () -> { homeLongFired = true; padButtons &= ~HOME; touchButtons &= ~HOME; openQuickMenu(); };
    private void homeDown() { if (homeHeld) return; homeHeld = true; homeLongFired = false; controls.postDelayed(homeLongPress, HOME_LONG_PRESS_MS); }
    private void homeUp() {
        if (!homeHeld) return; homeHeld = false; controls.removeCallbacks(homeLongPress);
        PspSystem p = live;
        if (!homeLongFired && p != null && p.inGame() && !anyMenuOpen()) openHomeMenu();
    }
    private boolean anyMenuOpen() { return (homeMenu != null && homeMenu.isOpen()) || (quickMenu != null && quickMenu.isOpen()); }
    private void menuButton(int bit) { if (homeMenu.isOpen()) homeMenu.onButton(bit); else if (quickMenu.isOpen()) quickMenu.onButton(bit); }
    private void closeMenus() { if (homeMenu.isOpen()) homeMenu.onButton(HOME); if (quickMenu.isOpen()) quickMenu.close(); }
    private boolean circleConfirms() { PspSystem p = live; return p != null && p.circleConfirms(); }
    private void openHomeMenu() {
        padButtons = 0; menuDirs = 0; menuPaused = true;
        homeMenu.open(circleConfirms(), batteryPercent, batteryCharging);
    }
    private void onHomeAnswer(boolean quit) {
        PspSystem p = live;
        if (quit && p != null) p.requestHomeQuit(); // applied by the emulation thread at the start of the next slice
        menuPaused = false; hideSystemUi();
    }
    private void onQuickMenuClosed() { menuPaused = leaveDialog; hideSystemUi(); }
    /** Emulation thread: menu changes that touch the machine (impose parameters are not thread-safe). */
    private void applyMenuChanges(PspSystem p) {
        int v = pendingVolume; if (v >= 0) { pendingVolume = -1; p.vsh.impose.put(org.pxp2.core.HleVsh.IMPOSE_VOLUME, v); p.vsh.impose.put(org.pxp2.core.HleVsh.IMPOSE_MUTE, 0); }
        int b = pendingPspBrightness; if (b >= 0) { pendingPspBrightness = -1; p.vsh.impose.put(0x2, b); p.vsh.impose.put(0x80000002, b); }
    }
    /** Window brightness for PXP2 only (0 = follow Android's setting, else 5..100 %); no system setting is changed. */
    private void applyBrightness(int percent) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = percent <= 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.02f, percent / 100f);
        getWindow().setAttributes(lp);
    }
    /** PSP backlight level for the impose brightness parameter (ids 0x2 / 0x80000002; PXP2's default is 3). Levels 0..3 assumed. */
    static int pspBrightnessLevel(int percent) { return Math.max(0, Math.min(3, (percent - 1) / 25)); }
    private void applyFrameRate() {
        SurfaceHolder h = holder;
        if (h == null || Build.VERSION.SDK_INT < 30) return;
        float best = 60; for (Display.Mode mode : getDisplay().getSupportedModes()) best = Math.max(best, mode.getRefreshRate());
        h.getSurface().setFrameRate(Math.min(refresh, best), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
    }
    private void openQuickMenu() {
        if (anyMenuOpen()) return;
        PspSystem p = live; android.content.SharedPreferences prefs = getPreferences();
        padButtons = 0; menuDirs = 0; menuPaused = true;
        Integer vol = p == null ? null : p.vsh.impose.get(org.pxp2.core.HleVsh.IMPOSE_VOLUME);
        int[] volume = {vol == null ? 15 : vol}, brightness = {prefs.getInt(BRIGHTNESS, 0)};
        QuickMenuView m = quickMenu; m.clear();
        m.action("Resume", m::close);
        m.header("Display");
        m.choice("Picture", new String[]{"Pixel", "Smooth", "Sharp"}, () -> filter, v -> { filter = v; scaled = null; prefs.edit().putInt(FILTER, v).apply(); }, null);
        m.choice("Screen mode", new String[]{"Original", "Stretch", "True widescreen"}, () -> Math.min(2, aspect), v -> { aspect = v; prefs.edit().putInt(ASPECT, v).apply(); }, null);
        int[] rates = {60, 120, 144};
        m.choice("Refresh rate", new String[]{"60 Hz", "120 Hz", "144 Hz"}, () -> refresh >= 144 ? 2 : refresh >= 120 ? 1 : 0, v -> { refresh = rates[v]; prefs.edit().putInt(REFRESH, refresh).apply(); applyFrameRate(); }, null);
        int maxScale = screenMaxScale; String[] scales = new String[maxScale + 1];
        scales[0] = "Max (" + maxScale + "×)"; for (int k = 1; k <= maxScale; k++) scales[k] = k == 1 ? "1× (480×272)" : k + "× (" + 480 * k + "×" + 272 * k + ")";
        m.choice("Resolution", scales, () -> Math.max(0, Math.min(maxScale, resolution)), v -> { resolution = v; prefs.edit().putInt(RESOLUTION, v).apply(); }, null);
        m.choice("Texture filtering", new String[]{"Off", "Bilinear", "Enhanced"}, () -> Math.max(0, Math.min(2, textureFilter)), v -> { textureFilter = v; prefs.edit().putInt(TEXTURE_FILTER, v).apply(); }, null);
        m.choice("Anti-aliasing", new String[]{"Off", "SSAA 2×", "Edge smoothing"}, () -> Math.max(0, Math.min(2, antialias)), v -> { antialias = v; prefs.edit().putInt(ANTIALIAS, v).apply(); }, null);
        int ram = p == null ? PspSystem.DEFAULT_RAM_MB : p.ramMegabytes, ramSetting = prefs.getInt(RecoveryActivity.RAM_MB, PspSystem.DEFAULT_RAM_MB);
        m.info("System memory", () -> ram + " MB" + (ramSetting != ram ? " (" + ramSetting + " MB applies on restart)" : "  (change in Settings, applies on restart)"));
        m.header("System");
        m.slider("Brightness", 0, 100, 5, () -> brightness[0], v -> {
            brightness[0] = v == 0 ? 0 : Math.max(5, v); applyBrightness(brightness[0]); prefs.edit().putInt(BRIGHTNESS, brightness[0]).apply();
            pendingPspBrightness = pspBrightnessLevel(brightness[0] == 0 ? 100 : brightness[0]);
        }, v -> v == 0 ? "Android" : v + "%");
        m.slider("Volume", 0, 30, 1, () -> volume[0], v -> { volume[0] = v; pendingVolume = v; }, v -> String.valueOf(v));
        m.action("Swap disc…", () -> { m.close(); showDiscMenu(); });
        m.action("Return to PXP2", () -> { m.close(); finish(); });
        m.header("Controls");
        m.choice("On-screen gamepad", new String[]{"Auto (hide with a controller)", "Always shown", "Hidden"}, () -> padMode,
            v -> { padMode = v; prefs.edit().putInt(PAD_MODE, v).apply(); controls.invalidate(); }, null);
        m.info("Last controller key", () -> lastKey);
        m.info("Razer Nexus opens by itself?", () -> "Razer Nexus › Settings: turn off launch on connect");
        m.info("Kishi HOME / Nexus key", () -> "handled by Android; hold HOME/PS for this menu");
        m.open("PXP2", p == null ? "" : p.inGame() ? "Game paused" : "XMB paused", circleConfirms());
    }
    private void confirmLeave() {
        leaveDialog = true; menuPaused = true;
        new android.app.AlertDialog.Builder(this).setTitle("Leave the PSP?").setMessage("Return to the PXP2 app. Progress not saved in the game is lost.")
            .setPositiveButton("Leave", (d, w) -> finish()).setNegativeButton("Stay", null)
            .setOnDismissListener(d -> { leaveDialog = false; menuPaused = anyMenuOpen(); hideSystemUi(); }).show();
    }
    /** True for keys from a game controller: gamepad/joystick sources, and Razer (Kishi) devices incl. their consumer-control half. */
    private static boolean fromController(KeyEvent e) {
        if ((e.getSource() & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0) return true;
        InputDevice d = e.getDevice();
        return d != null && !d.isVirtual() && (d.getVendorId() == 0x1532 || d.getName().toLowerCase(java.util.Locale.ROOT).contains("kishi"));
    }
    /** Records the last controller key for the quick menu's readout; unmapped keys are logged (logcat tag PXP2Input). */
    private void noteKey(KeyEvent e, int bit) {
        InputDevice d = e.getDevice(); String device = d == null ? "?" : d.getName() + String.format(java.util.Locale.ROOT, " %04x:%04x", d.getVendorId(), d.getProductId());
        String name = KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "");
        lastKey = name + " (" + e.getKeyCode() + ", scan " + e.getScanCode() + ") → " + (bit == 0 ? "unmapped" : buttonName(bit));
        if (bit == 0) android.util.Log.i("PXP2Input", "unmapped controller key " + name + " code " + e.getKeyCode() + " scan " + e.getScanCode() + " from " + device);
        if (quickMenu != null) quickMenu.refresh();
    }
    private static String buttonName(int bit) {
        switch (bit) {
            case UP: return "▲"; case DOWN: return "▼"; case LEFT: return "◀"; case RIGHT: return "▶"; case CROSS: return "✕"; case CIRCLE: return "○";
            case SQUARE: return "□"; case TRIANGLE: return "△"; case L: return "L"; case R: return "R"; case START: return "START"; case SELECT: return "SELECT";
            case HOME: return "HOME"; case VOLUP: return "VOL+"; case VOLDOWN: return "VOL−"; default: return Integer.toHexString(bit);
        }
    }

    /** Screens at least 3:4 tall (the unfolded Fold is ~1:1) get the picture on top and the controls beneath it. */
    private volatile PspSystem live;
    /** Disc menu: pick an image from PXP/ISO or eject. The swap happens in the background like a real drive. */
    void showDiscMenu() {
        File[] isos = new File(PxpStorage.memoryStick(this), "ISO").listFiles(f -> f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".iso"));
        if (isos == null) isos = new File[0];
        java.util.Arrays.sort(isos, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String current = getPreferences().getString(UMD, "");
        String[] names = new String[isos.length + 1];
        for (int i = 0; i < isos.length; i++) names[i] = (isos[i].getPath().equals(current) ? "● " : "") + isos[i].getName();
        names[isos.length] = current.isEmpty() ? "● No disc" : "Eject";
        File[] choices = isos;
        new android.app.AlertDialog.Builder(this).setTitle(isos.length == 0 ? "UMD drive (put .iso files in PXP/ISO)" : "UMD drive")
            .setItems(names, (d, which) -> {
                File image = which < choices.length ? choices[which] : null;
                getPreferences().edit().putString(UMD, image == null ? "" : image.getPath()).apply();
                PspSystem p = live; if (p != null) p.requestUmdSwap(image);
            }).show();
    }

    /** The app's launcher icon drawn onto a 144×80 PSP ICON0 with the XMB theme colour behind it. */
    private byte[] exitIcon() {
        try {
            Bitmap icon = Bitmap.createBitmap(144, 80, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(icon);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setShader(new LinearGradient(0, 0, 144, 80, XmbTheme.statusBar(this), XmbTheme.accent(this), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(0, 0, 144, 80), 10, 10, bg);
            android.graphics.drawable.Drawable d = getPackageManager().getApplicationIcon(getPackageName());
            d.setBounds(42, 10, 102, 70); d.draw(c);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); icon.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Throwable e) { return null; } // core falls back to its generated icon
    }

    static boolean tall(int w, int h) { return h > w * 0.75f; }

    // ---- touch controls -------------------------------------------------------------------------
    /** Semi-transparent PSP controls; multi-touch, slide between buttons. Hidden while a gamepad is in use. */
    final class ControlsView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[][] buttons = new float[15][]; // x, y, radius, bit
        private final String[] labels = {"▲", "▼", "◀", "▶", "△", "○", "✕", "□", "L", "R", "SELECT", "START", "HOME", "DISC", "☰"};
        private boolean hidden;
        ControlsView(Context c) { super(c); text.setColor(0xccffffff); text.setTextAlign(Paint.Align.CENTER); }
        void setAutoHidden(boolean h) { if (hidden != h) { hidden = h; post(this::invalidate); } }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float u = Math.min(w, h) / 9f, cx = u * 2.2f, cy = h - u * 2.6f, rx = w - u * 2.2f, shoulder = u * 1.0f, home = u * 0.8f, bottom = h - u * 0.7f;
            if (tall(w, h)) {
                // Near-square (Fold inner screen) or portrait: the picture sits at the top, so lay the pad out underneath it.
                float wide = aspect == 2 ? wideFactor : 1f, pic = Math.min(w / (480f * wide), h / 272f) * 272f, area = h - pic;
                u = Math.min(w / 9f, area / 6.2f); cx = u * 2.4f; rx = w - u * 2.4f; cy = pic + area * 0.52f;
                shoulder = pic + u * 0.9f; home = pic + u * 0.9f; bottom = h - u * 0.95f;
            }
            int[] bits = {UP, DOWN, LEFT, RIGHT, TRIANGLE, CIRCLE, CROSS, SQUARE, L, R, SELECT, START, HOME, DISC, MENU};
            float[][] pos = {{cx, cy - u}, {cx, cy + u}, {cx - u, cy}, {cx + u, cy}, {rx, cy - u}, {rx + u, cy}, {rx, cy + u}, {rx - u, cy},
                {u * 1.6f, shoulder}, {w - u * 1.6f, shoulder}, {w / 2f - u * 1.4f, bottom}, {w / 2f + u * 1.4f, bottom}, {w / 2f, home}, tall(w, h) ? new float[]{w / 2f, cy} : new float[]{w - u * 0.8f, h / 2f}, {w / 2f + u * 1.5f, home}}; // ☰ quick menu beside HOME
            for (int i = 0; i < 15; i++) buttons[i] = new float[]{pos[i][0], pos[i][1], i == 14 ? u * 0.5f : i == 13 ? u * 0.6f : i >= 8 ? u * 0.75f : u * 0.62f, bits[i]};
            text.setTextSize(u * 0.45f);
        }
        @Override protected void onDraw(Canvas c) {
            // Quick-menu setting: 0 auto (hidden while a controller is in use), 1 always shown, 2 hidden. ☰ always stays.
            boolean hide = padMode == 2 || (padMode == 0 && hidden);
            for (int i = 0; i < 15; i++) {
                float[] b = buttons[i]; if (b == null || (hide && i != 14)) continue;
                boolean down = (touchButtons & (int)b[3]) != 0;
                fill.setColor(down ? 0x70ffffff : 0x30ffffff); c.drawCircle(b[0], b[1], b[2], fill);
                c.drawText(labels[i], b[0], b[1] + text.getTextSize() / 3, text);
            }
        }
        // Gesture layer on the picture itself: swipe = D-pad, tap = ✕, long-press = △, two-finger tap = ○.
        private int gesturePointer = -1; private float gx, gy; private long gt; private boolean gestureTwoFinger;
        private int buttonAt(float x, float y) { for (float[] b : buttons) if (b != null && (padMode != 2 || (int)b[3] == MENU) && Math.hypot(x - b[0], y - b[1]) < b[2] * 1.15f) return (int)b[3]; return 0; }
        private void pulse(int bit) { gestureButtons |= bit; postDelayed(() -> gestureButtons &= ~bit, 120); }
        /** Presses a sequence of buttons, one every 180 ms (each held for 120 ms, spanning several emulated frames). */
        private void sequence(int... bits) { for (int i = 0; i < bits.length; i++) { int bit = bits[i]; postDelayed(() -> pulse(bit), i * 180L); } }
        /**
         * Tap on the picture. The XMB keeps the selected category at a fixed spot (x≈108, icon row y≈40–110 in 480×272 PSP
         * coordinates, columns ≈76 px apart) and the selected item at y≈135 with rows ≈65 px apart; a tap on another icon
         * moves there, a tap on the selected item opens it. Elsewhere a tap is ✕.
         */
        private void tapAt(float sx, float sy) {
            Rect p = picture; if (p == null || p.width() == 0) { pulse(CROSS); return; }
            float x = (sx - p.left) / p.width() * 480f, y = (sy - p.top) / p.height() * 272f;
            if (pictureWide > 1) x = 240 + (x - 240) * pictureWide; // widescreen frames are drawn stretched
            if (y >= 30 && y < 112) {
                int columns = Math.round((x - 108) / 76f);
                if (columns != 0) { int[] seq = new int[Math.min(8, Math.abs(columns))]; java.util.Arrays.fill(seq, columns > 0 ? RIGHT : LEFT); sequence(seq); return; }
            } else if (y >= 112 && x >= 60) {
                int rows = Math.round((y - 135) / 65f);
                if (rows != 0) { int[] seq = new int[Math.min(8, Math.abs(rows))]; java.util.Arrays.fill(seq, rows > 0 ? DOWN : UP); sequence(seq); return; }
            }
            pulse(CROSS);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            setAutoHidden(false);
            int mask = 0, action = e.getActionMasked(), index = e.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN && buttonAt(e.getX(), e.getY()) == 0) { gesturePointer = e.getPointerId(0); gx = e.getX(); gy = e.getY(); gt = e.getEventTime(); gestureTwoFinger = false; }
            if (action == MotionEvent.ACTION_POINTER_DOWN && gesturePointer >= 0 && buttonAt(e.getX(index), e.getY(index)) == 0) gestureTwoFinger = true;
            for (int p = 0; p < e.getPointerCount(); p++) {
                if ((action == MotionEvent.ACTION_POINTER_UP && p == index) || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) continue;
                if (e.getPointerId(p) == gesturePointer) continue;
                mask |= buttonAt(e.getX(p), e.getY(p));
            }
            if ((action == MotionEvent.ACTION_UP || (action == MotionEvent.ACTION_POINTER_UP && e.getPointerId(index) == gesturePointer)) && gesturePointer >= 0) {
                int p = e.findPointerIndex(gesturePointer);
                float dx = e.getX(p) - gx, dy = e.getY(p) - gy, threshold = getWidth() / 20f; long held = e.getEventTime() - gt;
                if (gestureTwoFinger) pulse(CIRCLE);
                // Swipes drag the menu like a list: finger left = move to the item on the right, finger up = item below.
                else if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) pulse(Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? LEFT : RIGHT) : (dy > 0 ? UP : DOWN));
                else if (held > 500) pulse(TRIANGLE);
                else tapAt(gx, gy);
                gesturePointer = -1;
            }
            if (action == MotionEvent.ACTION_CANCEL) gesturePointer = -1;
            if ((mask & DISC) != 0 && (touchButtons & DISC) == 0) showDiscMenu();
            if ((mask & MENU) != 0 && (touchButtons & MENU) == 0) openQuickMenu();
            if ((mask & HOME) != 0 && (touchButtons & HOME) == 0) homeDown(); else if ((mask & HOME) == 0 && (touchButtons & HOME) != 0) homeUp();
            if (mask != touchButtons) { touchButtons = mask; invalidate(); }
            return true;
        }
    }

    /**
     * Switches the Android launcher icon alias to the specified color index (0..11)
     * using PackageManager.DONT_KILL_APP.
     */
    public void switchLauncherIcon(int colorIndex) {
        LauncherIconManager.setIconColor(this, colorIndex);
    }

    public static void switchLauncherIcon(Context context, int colorIndex) {
        LauncherIconManager.setIconColor(context, colorIndex);
    }
}
