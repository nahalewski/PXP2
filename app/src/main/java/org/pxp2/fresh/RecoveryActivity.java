package org.pxp2.fresh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pxp2.core.*;

/**
 * PXP2 setup and launcher. Guides the user through downloading Sony's official 6.61 update, verifying it,
 * decrypting it into a private virtual flash, choosing the Memory Stick folder, and starting the XMB.
 * Styled after the XMB (wave gradient, rounded translucent cards).
 */
public final class RecoveryActivity extends Activity {
    private static final long SIZE = 32580549;
    private static final String HASH = "dc23a6dabdaed40bbfaad811d1f170346b911ba9750478fe5928d9f10c94f552";
    private static final String URL_661 = "http://du01.psp.update.playstation.org/update/psp/image/us/2014_1212_6be8878f475ac5b1a499b95ab2f7d301/EBOOT.PBP";
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int ACCENT = 0xffb46bff; // follows the XMB theme colour (XmbTheme), set before each buildUi
    private String themeSignature;

    private final TextView[] stepTitle = new TextView[3], stepDetail = new TextView[3], stepMark = new TextView[3];
    private ProgressBar progress; private TextView progressText; private Button setup, start;
    private Button filterButton, refreshButton, aspectButton, themeButton, stickButton, umdButton; private TextView developerOutput, stickStatus;
    private SharedPreferences prefs;
    /** "pxp2" preference: main RAM in MiB (32, 64 or 128), applied when XmbActivity builds the PspSystem. */
    public static final String RAM_MB = "ram_mb";
    private TextView firmwareInfo; private Button memoryButton;

    private GameCatalogView catalogView;
    private PluginsList pluginList;
    private HomebrewStoreView homebrewStoreView;
    private ServerTransferView serverTransferView;
    private LinearLayout settingsContainer;
    private int activeTab = -1; // 0 = Games, 1 = Homebrew, 2 = Server, 3 = Settings
    private Button tabGamesBtn, tabHomebrewBtn, tabServerBtn, tabSetupBtn;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("pxp2", MODE_PRIVATE);
        LauncherIconManager.updateIconFromStorage(this);
        handleColorIntent(getIntent());
        buildUi();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildUi();
    }

    private void buildUi() {
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        boolean isFoldableWide = screenWidthDp >= 600;

        // The whole app follows the XMB theme: colour accent/bars from the XMB colour, backdrop from the XMB wallpaper.
        ACCENT = XmbTheme.accent(this); themeSignature = XmbTheme.signature(this);
        getWindow().setStatusBarColor(XmbTheme.statusBar(this)); getWindow().setNavigationBarColor(XmbTheme.statusBar(this));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackground(XmbTheme.background(this));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(24) + insets.getSystemWindowInsetTop(), dp(20) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout root = new LinearLayout(this);
        scroll.addView(root);

        catalogView = new GameCatalogView(this, prefs, () -> {
            refreshOptions();
        });

        homebrewStoreView = new HomebrewStoreView(this, prefs, () -> {
            if (catalogView != null) catalogView.refresh();
        });

        serverTransferView = new ServerTransferView(this, prefs, () -> {
            if (catalogView != null) catalogView.refresh();
            refreshOptions();
        });

        if (isFoldableWide) {
            // Foldable 2-Column mode (Pixel Fold / Pixel 9 Pro Fold unfolded or wide >= 600dp / Tablets)
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);

            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            root.addView(leftCol, leftLp);

            settingsContainer = null;
            populateSettingsColumn(leftCol);

            LinearLayout rightCol = new LinearLayout(this);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            rightLp.leftMargin = dp(20);
            root.addView(rightCol, rightLp);

            rightCol.addView(buildTabBar(false));
            rightCol.addView(catalogView, spaced(dp(12)));
            rightCol.addView(homebrewStoreView, spaced(dp(12)));
            rightCol.addView(serverTransferView, spaced(dp(12)));
            if (activeTab < 0 || activeTab > 2) activeTab = 0;
            setStoreTab(activeTab, false);
        } else {
            // Single screen / phone mode (< 600dp)
            // Games, Homebrew, Server, and Settings each have their OWN clean dedicated view!
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            root.addView(column, new LinearLayout.LayoutParams(Math.min(dp(640), getResources().getDisplayMetrics().widthPixels - dp(40)), LinearLayout.LayoutParams.WRAP_CONTENT));

            settingsContainer = new LinearLayout(this);
            settingsContainer.setOrientation(LinearLayout.VERTICAL);
            populateSettingsColumn(settingsContainer);

            column.addView(buildTabBar(true));
            column.addView(catalogView, spaced(dp(12)));
            column.addView(homebrewStoreView, spaced(dp(12)));
            column.addView(serverTransferView, spaced(dp(12)));
            column.addView(settingsContainer, spaced(dp(12)));

            if (activeTab < 0) {
                activeTab = installed() ? 0 : 3;
            }
            setStoreTab(activeTab, true);
        }

        setContentView(scroll);
        refreshOptions(); refreshSteps(null); refreshStick();
    }

    private LinearLayout buildTabBar(boolean includeSetup) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        tabGamesBtn = tabPill("Games", activeTab == 0);
        tabHomebrewBtn = tabPill("Homebrew", activeTab == 1);
        tabServerBtn = tabPill("Server", activeTab == 2);

        tabGamesBtn.setOnClickListener(v -> setStoreTab(0, includeSetup));
        tabHomebrewBtn.setOnClickListener(v -> setStoreTab(1, includeSetup));
        tabServerBtn.setOnClickListener(v -> setStoreTab(2, includeSetup));

        bar.addView(tabGamesBtn);
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hbLp.leftMargin = dp(8);
        bar.addView(tabHomebrewBtn, hbLp);

        LinearLayout.LayoutParams srvLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srvLp.leftMargin = dp(8);
        bar.addView(tabServerBtn, srvLp);

        if (includeSetup) {
            tabSetupBtn = tabPill("Settings", activeTab == 3);
            tabSetupBtn.setOnClickListener(v -> setStoreTab(3, true));
            LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            setupLp.leftMargin = dp(8);
            bar.addView(tabSetupBtn, setupLp);
        } else {
            tabSetupBtn = null;
        }

        return bar;
    }

    private Button tabPill(String title, boolean active) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(dp(36));
        b.setPadding(dp(16), dp(4), dp(16), dp(4));
        b.setStateListAnimator(null);
        updateTabPillStyle(b, active);
        return b;
    }

    private void updateTabPillStyle(Button b, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (active) {
            bg.setColor(ACCENT);
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(0x26ffffff);
            b.setTextColor(0xb3ffffff);
            b.setTypeface(Typeface.DEFAULT);
        }
        b.setBackground(bg);
    }

    private void setStoreTab(int tab, boolean singleScreen) {
        activeTab = tab;
        if (tabGamesBtn != null) updateTabPillStyle(tabGamesBtn, tab == 0);
        if (tabHomebrewBtn != null) updateTabPillStyle(tabHomebrewBtn, tab == 1);
        if (tabServerBtn != null) updateTabPillStyle(tabServerBtn, tab == 2);
        if (tabSetupBtn != null) updateTabPillStyle(tabSetupBtn, tab == 3);

        if (catalogView != null) catalogView.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        if (homebrewStoreView != null) homebrewStoreView.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        if (serverTransferView != null) serverTransferView.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        if (settingsContainer != null) settingsContainer.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);

        if (tab == 0 && catalogView != null) {
            catalogView.refresh();
        } else if (tab == 1 && homebrewStoreView != null) {
            homebrewStoreView.refreshInstalled();
        } else if (tab == 2 && serverTransferView != null) {
            serverTransferView.fetchCatalog();
        }
    }

    private void populateSettingsColumn(LinearLayout column) {
        TextView title = text("PXP2", 44, Typeface.create("sans-serif-light", Typeface.NORMAL), Color.WHITE); title.setLetterSpacing(0.3f);
        column.addView(title);
        column.addView(text("Recovery info", 16, Typeface.DEFAULT, 0xccffffff));
        firmwareInfo = text("", 13, Typeface.DEFAULT, 0xb3ffffff); firmwareInfo.setPadding(0, dp(6), 0, 0); column.addView(firmwareInfo);
        refreshHeader();
        TextView intro = text("PXP2 runs the real PSP system software. It downloads Sony's official 6.61 update directly from PlayStation servers, checks it, and prepares a private virtual flash on this device. Nothing from Sony is bundled with the app.", 14, Typeface.DEFAULT, 0xb3ffffff);
        intro.setPadding(0, dp(12), 0, dp(18)); column.addView(intro);

        LinearLayout steps = card(); column.addView(steps);
        String[] titles = {"Download system software", "Verify & extract", "Decrypt & install virtual flash"};
        for (int i = 0; i < 3; i++) steps.addView(stepRow(i, titles[i]));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT)); progress.setVisibility(View.GONE);
        steps.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
        progressText = text("", 13, Typeface.DEFAULT, 0xccffffff); progressText.setPadding(0, dp(6), 0, 0); steps.addView(progressText);

        setup = pill("Set up PSP system", false); column.addView(setup, spaced(dp(16)));
        setup.setOnClickListener(v -> confirmSetup());
        start = pill("Start XMB", true); column.addView(start, spaced(dp(10)));
        start.setOnClickListener(v -> startActivity(new Intent(this, XmbActivity.class)));
        Button pspSettings = pill("PSP Settings", false); column.addView(pspSettings, spaced(dp(10)));
        pspSettings.setOnClickListener(v -> startActivity(new Intent(this, XmbSettingsActivity.class)));

        LinearLayout stick = card(); column.addView(stick, spaced(dp(18)));
        stick.addView(text("Memory Stick", 18, Typeface.DEFAULT_BOLD, Color.WHITE));
        stickStatus = text("", 13, Typeface.DEFAULT, 0xccffffff); stick.addView(stickStatus);
        stickButton = option(); stick.addView(stickButton, spaced(dp(8)));
        stickButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 30 && !PxpStorage.sharedAccess())
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:" + getPackageName())));
            else refreshStick();
        });

        LinearLayout disc = card(); column.addView(disc, spaced(dp(18)));
        disc.addView(text("UMD drive", 18, Typeface.DEFAULT_BOLD, Color.WHITE));
        disc.addView(text("Put .iso images (UMD games and UMD Video) in PXP/ISO. Tap to change the inserted disc; the XMB shows it in the Game or Video column.", 13, Typeface.DEFAULT, 0xccffffff));
        umdButton = option(); disc.addView(umdButton, spaced(dp(8)));
        umdButton.setOnClickListener(v -> {
            File[] isos = isoImages(); String current = prefs.getString(XmbActivity.UMD, ""); int next = 0; // 0 = no disc
            for (int i = 0; i < isos.length; i++) if (isos[i].getPath().equals(current)) next = i + 1;
            next = isos.length == 0 ? 0 : (next + 1) % (isos.length + 1);
            prefs.edit().putString(XmbActivity.UMD, next == 0 ? "" : isos[next - 1].getPath()).apply();
            refreshOptions();
            if (catalogView != null) catalogView.refresh();
        });

        LinearLayout display = card(); column.addView(display, spaced(dp(18)));
        display.addView(text("Display", 18, Typeface.DEFAULT_BOLD, Color.WHITE));
        filterButton = option(); refreshButton = option(); aspectButton = option(); themeButton = option();
        resolutionButton = option(); textureButton = option(); antialiasButton = option();
        display.addView(filterButton); display.addView(aspectButton); display.addView(refreshButton);
        display.addView(resolutionButton); display.addView(textureButton); display.addView(antialiasButton); display.addView(themeButton);
        // Enhancements: internal resolution cycles 1×, 2×, ... up to what this screen can show, then Max (always the screen's
        // largest); texture filtering and anti-aliasing cycle through their modes. XmbActivity applies them on start.
        resolutionButton.setOnClickListener(v -> {
            int r = prefs.getInt(XmbActivity.RESOLUTION, 1), max = screenMaxScale();
            prefs.edit().putInt(XmbActivity.RESOLUTION, r <= 0 ? 1 : r >= max ? 0 : r + 1).apply(); refreshOptions();
        });
        textureButton.setOnClickListener(v -> { prefs.edit().putInt(XmbActivity.TEXTURE_FILTER, (prefs.getInt(XmbActivity.TEXTURE_FILTER, 0) + 1) % 3).apply(); refreshOptions(); });
        antialiasButton.setOnClickListener(v -> { prefs.edit().putInt(XmbActivity.ANTIALIAS, (prefs.getInt(XmbActivity.ANTIALIAS, 0) + 1) % 3).apply(); refreshOptions(); });
        aspectButton.setOnClickListener(v -> { prefs.edit().putInt(XmbActivity.ASPECT, (prefs.getInt(XmbActivity.ASPECT, 0) + 1) % 3).apply(); refreshOptions(); });
        filterButton.setOnClickListener(v -> { prefs.edit().putInt(XmbActivity.FILTER, (prefs.getInt(XmbActivity.FILTER, 2) + 1) % 3).apply(); refreshOptions(); });
        refreshButton.setOnClickListener(v -> { int r = prefs.getInt(XmbActivity.REFRESH, 60); prefs.edit().putInt(XmbActivity.REFRESH, r == 60 ? 120 : r == 120 ? 144 : 60).apply(); refreshOptions(); });
        themeButton.setOnClickListener(v -> {
            int current = prefs.getInt(LauncherIconManager.PREF_LAST_COLOR, 3);
            int next = (current + 1) % LauncherIconManager.ICON_COUNT;
            LauncherIconManager.setIconColor(this, next);
            try {
                File storage = new File(getExternalFilesDir(null), "psp");
                File regFile = new File(storage, "flash1/registry/pxp2-registry.bin");
                Registry reg = new Registry();
                if (regFile.isFile()) reg.load(regFile);
                reg.set("/CONFIG/SYSTEM/XMB/THEME", "color_mode", Registry.INT, new byte[]{1, 0, 0, 0});
                reg.set("/CONFIG/SYSTEM/XMB/THEME", "system_color", Registry.INT, new byte[]{(byte)next, 0, 0, 0});
                reg.save(regFile);
            } catch (Throwable ignored) {}
            refreshOptions();
        });
        display.addView(text("Controls: on-screen touch buttons, or connect a gamepad (A = ✕, B = ○, X = □, Y = △, L1/R1, Start, Select, Guide = Home). Touch controls hide while a gamepad is in use.", 13, Typeface.DEFAULT, 0xaaffffff));

        // System memory: 32 / 64 / 128 MiB main RAM (PspSystem; see psp.md "System memory size").
        LinearLayout memory = card(); column.addView(memory, spaced(dp(18)));
        memory.addView(text("System memory", 18, Typeface.DEFAULT_BOLD, Color.WHITE));
        memory.addView(text("Games get 24 MB at every size. Extra memory goes to homebrew built for large memory and to the kernel. 128 MB is not a real PSP size. Applies at the next XMB start.", 13, Typeface.DEFAULT, 0xccffffff));
        memoryButton = option(); memory.addView(memoryButton, spaced(dp(8)));
        memoryButton.setOnClickListener(v -> { int mb = ramSetting(); prefs.edit().putInt(RAM_MB, mb == 32 ? 64 : mb == 64 ? 128 : 32).apply(); refreshMemory(); });
        refreshMemory();

        // SEPLUGINS: plugin PRXs in PXP/SEPLUGINS, switched on per mode (edits vsh.txt / game.txt; see PluginsList).
        LinearLayout plugins = card(); column.addView(plugins, spaced(dp(18)));
        plugins.addView(text("Plugins", 18, Typeface.DEFAULT_BOLD, Color.WHITE));
        plugins.addView(text("CFW plugins (.prx) in PXP/SEPLUGINS. Ticked plugins load at the next XMB or game start.", 13, Typeface.DEFAULT, 0xccffffff));
        pluginList = new PluginsList(this, PxpStorage.memoryStick(this)); plugins.addView(pluginList.view, spaced(dp(8)));

        LinearLayout developer = card(); column.addView(developer, spaced(dp(18)));
        TextView devTitle = text("Developer diagnostics  ▸", 15, Typeface.DEFAULT_BOLD, 0xccffffff); developer.addView(devTitle);
        LinearLayout devBody = new LinearLayout(this); devBody.setOrientation(LinearLayout.VERTICAL); devBody.setVisibility(View.GONE); developer.addView(devBody);
        devTitle.setOnClickListener(v -> { boolean show = devBody.getVisibility() != View.VISIBLE; devBody.setVisibility(show ? View.VISIBLE : View.GONE); devTitle.setText(show ? "Developer diagnostics  ▾" : "Developer diagnostics  ▸"); });
        Button cpu = option(); cpu.setText("CPU self-test"); devBody.addView(cpu); cpu.setOnClickListener(v -> diagnostic(Diagnostics::run));
        Button elf = option(); elf.setText("ELF loader self-test"); devBody.addView(elf); elf.setOnClickListener(v -> diagnostic(ElfDiagnostic::run));
        Button boot = option(); boot.setText("Headless VSH boot report"); devBody.addView(boot);
        boot.setOnClickListener(v -> diagnostic(() -> PspSystem.diagnose(installDir(), new File(getExternalFilesDir(null), "psp"), 100_000_000L)));
        developerOutput = text("", 11, Typeface.MONOSPACE, 0xddffffff); devBody.addView(developerOutput);

        TextView legal = text("PXP2 is an independent emulator. PSP and PlayStation are trademarks of Sony Interactive Entertainment.", 11, Typeface.DEFAULT, 0x80ffffff);
        legal.setPadding(0, dp(18), 0, 0); column.addView(legal);
    }

    @Override protected void onResume() {
        super.onResume();
        if (themeSignature != null && !themeSignature.equals(XmbTheme.signature(this))) buildUi(); // XMB theme changed while away
        if (stickStatus != null) { refreshStick(); refreshOptions(); }
        if (catalogView != null) catalogView.refresh();
        if (homebrewStoreView != null) homebrewStoreView.refreshInstalled();
        if (pluginList != null) pluginList.refresh();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleColorIntent(intent);
    }
    private void handleColorIntent(Intent intent) {
        if (intent == null) return;
        int color = intent.getIntExtra("set_theme_color", -1);
        if (color >= 0 && color < LauncherIconManager.ICON_COUNT) {
            LauncherIconManager.setIconColor(this, color);
            refreshOptions();
        }
    }

    // ---- header and system memory -----------------------------------------------------------------
    private int ramSetting() { int mb = prefs.getInt(RAM_MB, PspSystem.DEFAULT_RAM_MB); return mb == 32 || mb == 64 || mb == 128 ? mb : PspSystem.DEFAULT_RAM_MB; }
    private void refreshMemory() {
        if (memoryButton == null) return;
        int mb = ramSetting();
        memoryButton.setText("System memory: " + mb + " MB" + (mb == 32 ? " (PSP-1000)" : mb == 64 ? " (PSP-2000/3000/Go, default)" : " (double, non-standard)"));
    }
    /** Installed system software (flash0:/vsh/etc/version.txt) and the app version, under the title. */
    private void refreshHeader() {
        if (firmwareInfo == null) return;
        String firmware = "not installed";
        File version = new File(installDir(), "flash0/vsh/etc/version.txt");
        if (version.isFile()) {
            String release = null, region = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(version), java.nio.charset.StandardCharsets.US_ASCII))) {
                for (String line; (line = r.readLine()) != null; ) { // "release:6.61:" and "target:1:WorldWide"
                    String[] f = line.split(":");
                    if (f.length >= 2 && f[0].equals("release")) release = f[1];
                    if (f.length >= 3 && f[0].equals("target")) region = f[2];
                }
            } catch (IOException ignored) { }
            // The flash image name carries the model generation: flash-661-01g-v1 is the PSP-1000 (01g) image.
            String model = installDir().getName().contains("01g") ? "PSP-1000 (01g)" : null;
            firmware = (release != null ? release : "unknown version") + (model != null ? " · " + model : "") + (region != null ? " · " + region : "");
        }
        String app;
        try { app = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { app = "unknown"; }
        firmwareInfo.setText("System software: " + firmware + "\nPXP2 app: " + app);
    }

    // ---- styling helpers ------------------------------------------------------------------------
    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
    private TextView text(String s, int sp, Typeface face, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTypeface(face); t.setTextColor(color); return t; }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = top; return p; }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0x2effffff); bg.setCornerRadius(dp(18)); bg.setStroke(dp(1), 0x33ffffff); c.setBackground(bg);
        return c;
    }
    private Button pill(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(17); b.setTextColor(primary ? XmbTheme.statusBar(this) : Color.WHITE);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(28));
        if (primary) bg.setColor(Color.WHITE); else { bg.setColor(0x33ffffff); bg.setStroke(dp(1), 0x66ffffff); }
        b.setBackground(bg); b.setMinHeight(dp(54)); b.setStateListAnimator(null); return b;
    }
    private Button option() {
        Button b = new Button(this); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(Color.WHITE); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(12)); bg.setColor(0x1affffff); b.setBackground(bg); b.setStateListAnimator(null);
        b.setPadding(dp(14), 0, dp(14), 0); return b;
    }
    private View stepRow(int i, String title) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, dp(6), 0, dp(10));
        stepMark[i] = text(String.valueOf(i + 1), 16, Typeface.DEFAULT_BOLD, Color.WHITE); stepMark[i].setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(0x33ffffff); stepMark[i].setBackground(dot);
        row.addView(stepMark[i], new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(14), 0, 0, 0);
        stepTitle[i] = text(title, 16, Typeface.DEFAULT_BOLD, Color.WHITE); stepDetail[i] = text("", 13, Typeface.DEFAULT, 0xb3ffffff);
        body.addView(stepTitle[i]); body.addView(stepDetail[i]); row.addView(body);
        return row;
    }
    private void refreshStick() {
        File root = PxpStorage.memoryStick(this);
        boolean shared = PxpStorage.sharedAccess();
        stickStatus.setText((shared ? "Folder: " : "Using a private folder until access is allowed: ") + root.getPath()
            + "\nPut PSP game folders in PXP/PSP/GAME and ISO images in PXP/ISO. Saves go to PXP/PSP/SAVEDATA. The folder is visible to your PC over USB.");
        stickButton.setText(shared ? "Folder ready ✓" : "Allow access to create /storage/emulated/0/PXP");
    }
    private File[] isoImages() {
        File[] list = new File(PxpStorage.memoryStick(this), "ISO").listFiles(f -> f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".iso"));
        if (list == null) return new File[0];
        java.util.Arrays.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName())); return list;
    }
    private Button resolutionButton, textureButton, antialiasButton; // Display card: enhancements
    private int screenMaxScale() { android.util.DisplayMetrics dm = getResources().getDisplayMetrics(); return XmbActivity.maxScale(dm.widthPixels, dm.heightPixels); }
    private void refreshOptions() {
        String umd = prefs.getString(XmbActivity.UMD, "");
        if (!umd.isEmpty() && !new File(umd).isFile()) { umd = ""; prefs.edit().putString(XmbActivity.UMD, "").apply(); }
        int count = isoImages().length;
        umdButton.setText("Disc: " + (umd.isEmpty() ? "No disc" + (count == 0 ? " (no images in PXP/ISO)" : " (" + count + " image" + (count == 1 ? "" : "s") + " available)") : new File(umd).getName()));
        String[] filters = {"Pixel (sharpest edges)", "Smooth (bilinear)", "Sharp upscale (integer prescale + smooth)"};
        filterButton.setText("Picture: " + filters[prefs.getInt(XmbActivity.FILTER, 2)]);
        String[] aspects = {"Original (PSP 16:9, no distortion)", "Stretch to fill (wider image)", "True widescreen (renders extra area at the sides)"};
        aspectButton.setText("Screen: " + aspects[Math.min(2, prefs.getInt(XmbActivity.ASPECT, 0))]);
        int r = prefs.getInt(XmbActivity.REFRESH, 60);
        refreshButton.setText("Display refresh: " + r + " Hz" + (r == 60 ? " (native PSP rate)" : " (XMB animates at 60; frames are paced to your display)"));
        int res = prefs.getInt(XmbActivity.RESOLUTION, 1), maxScale = screenMaxScale();
        resolutionButton.setText("Resolution: " + (res <= 0 ? "Max (" + maxScale + "×, " + 480 * maxScale + "×" + 272 * maxScale + ")"
            : res + "×" + (res == 1 ? " (native 480×272)" : " (" + 480 * res + "×" + 272 * res + ")")));
        String[] textures = {"Off (original texels)", "Bilinear (smooth)", "Enhanced (edge-directed 2×/4× upscale + bilinear)"};
        textureButton.setText("Texture upscaling: " + textures[Math.max(0, Math.min(2, prefs.getInt(XmbActivity.TEXTURE_FILTER, 0)))]);
        String[] aa = {"Off", "SSAA 2× (renders at twice the resolution)", "Edge smoothing (FXAA-style, cheap)"};
        antialiasButton.setText("Anti-aliasing: " + aa[Math.max(0, Math.min(2, prefs.getInt(XmbActivity.ANTIALIAS, 0)))]);
        int currentColor = prefs.getInt(LauncherIconManager.PREF_LAST_COLOR, 3);
        if (currentColor < 0 || currentColor >= LauncherIconManager.ICON_COUNT) currentColor = 3;
        themeButton.setText("Launcher icon: " + LauncherIconManager.COLOR_NAMES[currentColor]);
    }

    // ---- state ----------------------------------------------------------------------------------
    private File pbp() { return new File(getFilesDir(), "EBOOT.PBP"); }
    private File firmwareDir() { return new File(getFilesDir(), "firmware"); }
    private File installDir() { return new File(firmwareDir(), "flash-661-01g-v1"); }
    private boolean installed() { return new File(installDir(), "flash0/vsh/module/vshmain.prx").isFile(); }
    /** Updates the step cards; {@code active} (0-2) shows that step as in progress. */
    private void refreshSteps(Integer active) {
        boolean downloaded = pbp().isFile(), ready = installed();
        boolean[] done = {downloaded || ready, ready, ready};
        String[] details = {done[0] ? "Sony 6.61 update (32.6 MB) saved and verified" : "Official 6.61 update from PlayStation servers",
            done[1] ? "SHA-256 matched; container extracted" : "Checks size and SHA-256, unpacks the update",
            done[2] ? "Virtual flash ready (312 files, 215 modules)" : "Decrypts the system files into private storage"};
        for (int i = 0; i < 3; i++) {
            boolean running = active != null && active == i;
            stepMark[i].setText(done[i] ? "✓" : running ? "…" : String.valueOf(i + 1));
            ((GradientDrawable)stepMark[i].getBackground()).setColor(done[i] ? ACCENT : running ? 0x66ffffff : 0x33ffffff);
            if (!running) stepDetail[i].setText(details[i]);
            stepTitle[i].setAlpha(done[i] || running ? 1f : 0.7f);
        }
        start.setEnabled(ready); start.setAlpha(ready ? 1f : 0.4f);
        setup.setText(ready ? "Verify virtual flash" : "Set up PSP system");
        refreshHeader(); // firmware version appears once the install finishes
    }

    private void confirmSetup() {
        if (busy.get()) return;
        if (installed()) { runSetup(); return; }
        new AlertDialog.Builder(this).setTitle("Set up PSP system software")
            .setMessage("PXP2 will download Sony's official PSP 6.61 update (32.6 MB) from PlayStation servers, verify it and decrypt it into this app's private storage. Continue?")
            .setPositiveButton("Continue", (d, w) -> runSetup()).setNegativeButton("Cancel", null).show();
    }
    private void step(int index, String detail, int permille) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            refreshSteps(index); stepDetail[index].setText(detail);
            progress.setVisibility(View.VISIBLE); progress.setIndeterminate(permille < 0); if (permille >= 0) progress.setProgress(permille);
            progressText.setText(detail);
        });
    }
    private void runSetup() {
        if (!busy.compareAndSet(false, true)) return;
        setup.setEnabled(false);
        worker.execute(() -> {
            String error = null;
            try {
                if (!pbp().isFile() && !installed()) download();
                if (installed()) { step(2, "Verifying installed virtual flash...", -1); FirmwareInstaller.verify(installDir().toPath()); }
                else {
                    step(1, "Verifying the update and extracting its container...", -1);
                    FirmwareInstaller.install(pbp(), firmwareDir(), message -> step(message.startsWith("Verifying") ? 1 : 2, message, -1));
                }
            } catch (Exception e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            String failure = error; busy.set(false);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setup.setEnabled(true); progress.setVisibility(View.GONE); refreshSteps(null);
                progressText.setText(failure == null ? "Ready. Press Start XMB." : "Setup stopped: " + failure);
            });
        });
    }
    private void download() throws Exception {
        File pending = new File(getFilesDir(), "EBOOT.PBP.partial");
        HttpURLConnection connection = (HttpURLConnection)new URL(URL_661).openConnection();
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("PlayStation server returned HTTP " + connection.getResponseCode());
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(pending)) {
                byte[] buffer = new byte[65536]; int n, last = -1;
                while ((n = in.read(buffer)) != -1) {
                    count += n; if (count > SIZE) throw new IOException("Unexpected update size");
                    digest.update(buffer, 0, n); out.write(buffer, 0, n);
                    int permille = (int)(count * 1000 / SIZE);
                    if (permille / 10 != last) { last = permille / 10; step(0, String.format(java.util.Locale.ROOT, "Downloading... %.1f of 32.6 MB", count / 1e6), permille); }
                }
                out.getFD().sync();
            }
            StringBuilder actual = new StringBuilder(); for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (count != SIZE || !HASH.equals(actual.toString())) throw new IOException("Download verification failed (SHA-256 mismatch)");
            if (!pending.renameTo(pbp())) throw new IOException("Cannot save the verified update");
        } finally { connection.disconnect(); pending.delete(); }
    }
    private interface Job { String run() throws Exception; }
    private void diagnostic(Job job) {
        if (!busy.compareAndSet(false, true)) return;
        developerOutput.setText("Running...");
        worker.execute(() -> {
            String r; try { r = job.run(); } catch (Exception e) { r = "Stopped: " + e.getMessage(); }
            String out = r.length() > 6000 ? r.substring(0, 6000) + "\n..." : r; busy.set(false);
            runOnUiThread(() -> { if (!isDestroyed()) developerOutput.setText(out); });
        });
    }
}
