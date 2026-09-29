package org.pxp2.fresh;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.pxp2.core.Iso9660;
import org.pxp2.core.PbpPackage;

/**
 * Rich PSP Game & Homebrew Catalog for PXP2 host UI.
 * Scans PXP/ISO, PXP/PSP/GAME, PXP/PSP/GAMES, and PXP/PSP/GAME150 for real PSP games,
 * extracts titles and ICON0.PNG, manages active disc selection, and provides one-tap launch.
 */
public final class GameCatalogView extends LinearLayout {
    private final int ACCENT = XmbTheme.accent(getContext()); // XMB theme colour
    private int tint(int alpha) { return (ACCENT & 0x00ffffff) | (alpha << 24); }
    /** Category of converted PS1 EBOOTs (PARAM.SFO CATEGORY "ME"): listed, never launched; PXP2 cannot run POPS yet. */
    static final String PS1 = "PS1";
    static final String PS1_NOTE = "PS1 (runs on PSP; PXP2 support coming)";
    private static final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Runnable onSelectionChanged;

    private TextView headerTitle, headerCount;
    private Button refreshBtn;
    private LinearLayout listContainer;
    private TextView emptyText;
    private ProgressBar loadingBar;
    private final List<GameItem> scannedGames = new ArrayList<>();

    public static final class GameItem {
        public final File file;
        public final String title;
        public final String category; // "ISO", "CSO", "Homebrew"
        public final long size;
        public final String sizeFormatted;
        public Bitmap icon;
        private Bitmap art; private boolean artLoaded; // PIC1/PIC0 artwork, loaded when the 3D disc is shown
        /** The game's own artwork (PIC1.PNG, else PIC0.PNG) from the ISO or EBOOT.PBP, used as the UMD disc label. */
        public Bitmap art() { if (!artLoaded) { artLoaded = true; art = loadArt(file); } return art; }
        private String discId; private boolean discIdLoaded;
        /** DISC_ID of a PSP disc image (e.g. ULUS10567), used to fetch its real UMD label from the PC hub; null if none. */
        public String discId() {
            if (!discIdLoaded) {
                discIdLoaded = true;
                if (file.getName().toLowerCase(Locale.ROOT).endsWith(".iso")) {
                    try (Iso9660 iso = new Iso9660(file)) { discId = iso.discId(); } catch (Throwable ignored) {}
                }
            }
            return discId;
        }

        public GameItem(File file, String title, String category, long size, Bitmap icon) {
            this.file = file;
            this.title = title;
            this.category = category;
            this.size = size;
            this.sizeFormatted = formatSize(size);
            this.icon = icon;
        }
    }

    public GameCatalogView(Context context, SharedPreferences prefs, Runnable onSelectionChanged) {
        super(context);
        this.prefs = prefs;
        this.onSelectionChanged = onSelectionChanged;
        initUi();
        refresh();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void initUi() {
        setOrientation(LinearLayout.VERTICAL);
        setPadding(dp(18), dp(16), dp(18), dp(16));

        // Translucent card background styled like XMB recovery
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x2effffff);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x33ffffff);
        setBackground(bg);

        // Header row: Title + Subtitle on left, Refresh button & count on right
        LinearLayout headerRow = new LinearLayout(getContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(getContext());
        titleCol.setOrientation(LinearLayout.VERTICAL);
        headerTitle = new TextView(getContext());
        headerTitle.setText("Game Catalog");
        headerTitle.setTextSize(18);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextColor(Color.WHITE);
        titleCol.addView(headerTitle);

        TextView subTitle = new TextView(getContext());
        subTitle.setText("ISO images & Homebrew on Memory Stick");
        subTitle.setTextSize(12);
        subTitle.setTextColor(0xb3ffffff);
        titleCol.addView(subTitle);
        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        headerCount = new TextView(getContext());
        headerCount.setTextSize(12);
        headerCount.setTextColor(0xccffffff);
        headerCount.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable countBg = new GradientDrawable();
        countBg.setColor(0x33ffffff);
        countBg.setCornerRadius(dp(10));
        headerCount.setBackground(countBg);
        headerRow.addView(headerCount);

        refreshBtn = new Button(getContext());
        refreshBtn.setText("↺ Refresh");
        refreshBtn.setTextSize(13);
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setAllCaps(false);
        GradientDrawable refBg = new GradientDrawable();
        refBg.setColor(0x26ffffff);
        refBg.setCornerRadius(dp(12));
        refreshBtn.setBackground(refBg);
        refreshBtn.setMinHeight(dp(36));
        refreshBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        refreshBtn.setStateListAnimator(null);
        refreshBtn.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        refLp.leftMargin = dp(8);
        headerRow.addView(refreshBtn, refLp);

        addView(headerRow);

        // Loading bar
        loadingBar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(true);
        loadingBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        loadingBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(4));
        barLp.topMargin = dp(10);
        addView(loadingBar, barLp);

        // List container for game cards
        listContainer = new LinearLayout(getContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(12);
        addView(listContainer, listLp);

        // Empty state message
        emptyText = new TextView(getContext());
        emptyText.setText("No PSP games or homebrew found on Memory Stick.\n\n"
            + "• Place .iso or .cso files in /sdcard/PXP/ISO/\n"
            + "• Place homebrew in /sdcard/PXP/PSP/GAME/<folder>/EBOOT.PBP\n\n"
            + "Tap Refresh once files are transferred.");
        emptyText.setTextSize(13);
        emptyText.setTextColor(0x99ffffff);
        emptyText.setPadding(dp(8), dp(16), dp(8), dp(16));
        emptyText.setLineSpacing(dp(2), 1.15f);
        addView(emptyText);
    }

    public void refresh() {
        loadingBar.setVisibility(View.VISIBLE);
        refreshBtn.setEnabled(false);

        scanExecutor.execute(() -> {
            List<GameItem> items = scanAllGames(getContext());
            mainHandler.post(() -> {
                scannedGames.clear();
                scannedGames.addAll(items);
                populateList();
                loadingBar.setVisibility(View.GONE);
                refreshBtn.setEnabled(true);
            });
        });
    }

    private void populateList() {
        listContainer.removeAllViews();
        String currentUmd = prefs.getString(XmbActivity.UMD, "");

        int count = scannedGames.size();
        headerCount.setText(count + (count == 1 ? " game" : " games"));

        if (count == 0) {
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        emptyText.setVisibility(View.GONE);

        for (GameItem item : scannedGames) {
            boolean isSelected = item.file.getPath().equals(currentUmd);
            View itemView = createGameItemView(item, isSelected);
            listContainer.addView(itemView);
        }
    }

    private View createGameItemView(GameItem item, boolean isSelected) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setCornerRadius(dp(12));
        if (isSelected) {
            rowBg.setColor(tint(0x3d));
            rowBg.setStroke(dp(1), ACCENT);
        } else {
            rowBg.setColor(0x14ffffff);
            rowBg.setStroke(dp(1), 0x22ffffff);
        }
        row.setBackground(rowBg);

        LinearLayout topBar = new LinearLayout(getContext());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        // Game Icon (either decoded ICON0.PNG or fallback badge)
        ImageView iconView = new ImageView(getContext());
        if (item.icon != null) {
            iconView.setImageBitmap(item.icon);
        } else {
            iconView.setImageBitmap(drawDefaultIcon(item.category));
        }
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconLp.rightMargin = dp(12);
        topBar.addView(iconView, iconLp);

        // Title and metadata column
        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(getContext());
        titleView.setText(item.title);
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(titleView);

        LinearLayout metaRow = new LinearLayout(getContext());
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        // Category Badge [ISO] or [Homebrew]
        TextView badge = new TextView(getContext());
        badge.setText(item.category);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(isSelected ? 0xffffffff : ACCENT);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(isSelected ? tint(0x88) : tint(0x28));
        badgeBg.setCornerRadius(dp(6));
        badge.setBackground(badgeBg);
        metaRow.addView(badge);

        // File size
        TextView sizeView = new TextView(getContext());
        sizeView.setText("  •  " + item.sizeFormatted + "  •  " + item.file.getName());
        sizeView.setTextSize(12);
        sizeView.setTextColor(0xb3ffffff);
        sizeView.setSingleLine(true);
        sizeView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metaRow.addView(sizeView);

        textCol.addView(metaRow);
        if (PS1.equals(item.category)) { // converted PS1 EBOOTs are listed but never launched (PXP2 has no POPS yet)
            TextView note = new TextView(getContext());
            note.setText(PS1_NOTE);
            note.setTextSize(11);
            note.setTextColor(0xffffd27a);
            note.setPadding(0, dp(3), 0, 0);
            textCol.addView(note);
        }
        topBar.addView(textCol, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        // Quick status or play button
        if (isSelected) {
            TextView activeBadge = new TextView(getContext());
            activeBadge.setText("● INSERTED");
            activeBadge.setTextSize(11);
            activeBadge.setTypeface(Typeface.DEFAULT_BOLD);
            activeBadge.setTextColor(0xff99ff99);
            activeBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
            topBar.addView(activeBadge);
        }

        row.addView(topBar);
        if (PS1.equals(item.category)) { // no UMD, no launch: a PS1 EBOOT is for a real PSP until PXP2 runs POPS
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = dp(8);
            row.setLayoutParams(itemLp);
            return row;
        }

        // Actions row: Show 3D UMD, Tap to select as disc or Play Now
        LinearLayout actionsRow = new LinearLayout(getContext());
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(6);

        Button umdBtn = new Button(getContext());
        umdBtn.setText("💿 3D UMD");
        umdBtn.setTextSize(12);
        umdBtn.setTextColor(Color.WHITE);
        umdBtn.setAllCaps(false);
        GradientDrawable umdBg = new GradientDrawable();
        umdBg.setColor(0x28ffffff);
        umdBg.setStroke(dp(1), 0x44ffffff);
        umdBg.setCornerRadius(dp(12));
        umdBtn.setBackground(umdBg);
        umdBtn.setMinHeight(dp(32));
        umdBtn.setPadding(dp(10), dp(2), dp(10), dp(2));
        umdBtn.setStateListAnimator(null);
        umdBtn.setOnClickListener(v -> showUmdDialog(getContext(), item));
        LinearLayout.LayoutParams umdLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        umdLp.rightMargin = dp(8);
        actionsRow.addView(umdBtn, umdLp);

        Button playBtn = new Button(getContext());
        playBtn.setText(isSelected ? "▶ Play Game in XMB" : "Insert & Play");
        playBtn.setTextSize(12);
        playBtn.setTextColor(Color.WHITE);
        playBtn.setAllCaps(false);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setColor(isSelected ? ACCENT : 0x33ffffff);
        playBg.setCornerRadius(dp(12));
        playBtn.setBackground(playBg);
        playBtn.setMinHeight(dp(32));
        playBtn.setPadding(dp(12), dp(2), dp(12), dp(2));
        playBtn.setStateListAnimator(null);
        playBtn.setOnClickListener(v -> {
            if ("Homebrew".equals(item.category)) { // boot the homebrew directly; quitting it lands in the XMB
                String root = PxpStorage.memoryStick(getContext()).getAbsolutePath(), path = item.file.getAbsolutePath();
                Intent i = new Intent(getContext(), XmbActivity.class);
                if (path.startsWith(root)) i.putExtra(XmbActivity.BOOT_PATH, "ms0:" + path.substring(root.length()).replace(File.separatorChar, '/'));
                getContext().startActivity(i); return;
            }
            selectGame(item);
            getContext().startActivity(new Intent(getContext(), XmbActivity.class));
        });
        actionsRow.addView(playBtn);
        row.addView(actionsRow, actLp);

        row.setOnClickListener(v -> selectGame(item));

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        itemLp.bottomMargin = dp(8);
        row.setLayoutParams(itemLp);
        return row;
    }

    private void selectGame(GameItem item) {
        String newPath = item.file.getPath();
        prefs.edit().putString(XmbActivity.UMD, newPath).apply();
        populateList();
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    private void showUmdDialog(Context context, GameItem item) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(16));

        GradientDrawable dialogBg = new GradientDrawable();
        dialogBg.setColor(0xf0161426);
        dialogBg.setCornerRadius(dp(20));
        dialogBg.setStroke(dp(1), tint(0x44));
        content.setBackground(dialogBg);

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(item != null ? item.title : "Universal Media Disc");
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.WHITE);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(titleView);

        // Subtitle / category
        TextView subView = new TextView(context);
        subView.setText((item != null ? item.category + " • " + item.sizeFormatted : "PSP Optical Media")
            + "  (Drag 3D • Flick • Double-Tap Reset)");
        subView.setTextSize(11);
        subView.setTextColor(0xbbffffff);
        subView.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
        subLp.bottomMargin = dp(10);
        content.addView(subView, subLp);

        // Interactive 3D UMD View
        Umd3DView umdView = new Umd3DView(context);
        if (item != null) {
            Bitmap ownArt = item.art();
            umdView.setGame(item.title, item.icon, ownArt);
            // real UMD label art (or a cover) from the PC hub, fetched in the background; the synthesized label shows meanwhile
            umdView.loadHubArt(prefs.getString(ServerTransferView.PREF_SERVER_URL, ""), item.discId(), ownArt != null);
        }
        int umdSize = dp(230);
        LinearLayout.LayoutParams umdLp = new LinearLayout.LayoutParams(umdSize, umdSize);
        content.addView(umdView, umdLp);

        // Controls Row: Toggle Spin, Reset 3D Angle, Insert / Play, Close
        LinearLayout controlsRow = new LinearLayout(context);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        ctrlLp.topMargin = dp(12);

        Button spinBtn = new Button(context);
        spinBtn.setText("⏸ Pause");
        spinBtn.setTextSize(11);
        spinBtn.setTextColor(Color.WHITE);
        spinBtn.setAllCaps(false);
        GradientDrawable btnBg1 = new GradientDrawable();
        btnBg1.setColor(0x28ffffff);
        btnBg1.setCornerRadius(dp(10));
        spinBtn.setBackground(btnBg1);
        spinBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        spinBtn.setStateListAnimator(null);
        spinBtn.setOnClickListener(v -> {
            umdView.toggleSpin();
            spinBtn.setText(umdView.isSpinning() ? "⏸ Pause" : "▶ Spin");
        });
        LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(34));
        btnLp1.rightMargin = dp(6);
        controlsRow.addView(spinBtn, btnLp1);

        Button resetBtn = new Button(context);
        resetBtn.setText("↺ Reset");
        resetBtn.setTextSize(11);
        resetBtn.setTextColor(Color.WHITE);
        resetBtn.setAllCaps(false);
        GradientDrawable btnBg2 = new GradientDrawable();
        btnBg2.setColor(0x28ffffff);
        btnBg2.setCornerRadius(dp(10));
        resetBtn.setBackground(btnBg2);
        resetBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        resetBtn.setStateListAnimator(null);
        resetBtn.setOnClickListener(v -> umdView.resetOrientation(true));
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(34));
        btnLp2.rightMargin = dp(6);
        controlsRow.addView(resetBtn, btnLp2);

        if (item != null && !PS1.equals(item.category)) {
            Button insertBtn = new Button(context);
            insertBtn.setText("▶ Insert & Play");
            insertBtn.setTextSize(11);
            insertBtn.setTextColor(Color.WHITE);
            insertBtn.setAllCaps(false);
            GradientDrawable btnBg3 = new GradientDrawable();
            btnBg3.setColor(ACCENT);
            btnBg3.setCornerRadius(dp(10));
            insertBtn.setBackground(btnBg3);
            insertBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
            insertBtn.setStateListAnimator(null);
            insertBtn.setOnClickListener(v -> {
                dialog.dismiss();
                selectGame(item);
                getContext().startActivity(new Intent(getContext(), XmbActivity.class));
            });
            LinearLayout.LayoutParams btnLp3 = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(34));
            btnLp3.rightMargin = dp(6);
            controlsRow.addView(insertBtn, btnLp3);
        }

        Button closeBtn = new Button(context);
        closeBtn.setText("Close");
        closeBtn.setTextSize(11);
        closeBtn.setTextColor(0xccffffff);
        closeBtn.setAllCaps(false);
        GradientDrawable btnBg4 = new GradientDrawable();
        btnBg4.setColor(0x1fffffff);
        btnBg4.setCornerRadius(dp(10));
        closeBtn.setBackground(btnBg4);
        closeBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        closeBtn.setStateListAnimator(null);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        controlsRow.addView(closeBtn, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)));

        content.addView(controlsRow, ctrlLp);

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static Bitmap drawDefaultIcon(String category) {
        int size = 96;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Round rect disc badge
        p.setColor(0x44ffffff);
        RectF r = new RectF(4, 4, size - 4, size - 4);
        c.drawRoundRect(r, 20, 20, p);

        p.setColor(0xffffffff);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        c.drawCircle(size / 2f, size / 2f, size / 3f, p);
        c.drawCircle(size / 2f, size / 2f, size / 8f, p);

        return b;
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024f * 1024f * 1024f));
    }

    // ---- Game Scanning Engine -------------------------------------------------------------------
    public static List<GameItem> scanAllGames(Context context) {
        List<GameItem> result = new ArrayList<>();
        File root = PxpStorage.memoryStick(context);

        // 1. Scan PXP/ISO
        File isoDir = new File(root, "ISO");
        if (isoDir.isDirectory()) {
            File[] files = isoDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".iso") || name.endsWith(".cso")) {
                        String category = name.endsWith(".cso") ? "CSO" : "ISO";
                        String title = parseIsoTitle(f);
                        Bitmap icon = parseIsoIcon(f);
                        result.add(new GameItem(f, title, category, f.length(), icon));
                    }
                }
            }
        }

        // 2. Scan PSP/GAME, PSP/GAMES, PSP/GAME150
        String[] gameDirs = {"PSP/GAME", "PSP/GAMES", "PSP/GAME150"};
        for (String gd : gameDirs) {
            File base = new File(root, gd);
            if (!base.isDirectory()) continue;
            File[] subs = base.listFiles();
            if (subs == null) continue;
            for (File sub : subs) {
                if (!sub.isDirectory()) continue;
                if (sub.getName().equalsIgnoreCase(org.pxp2.core.ExitApp.FOLDER)) continue; // XMB-only "Exit to PXP2" entry
                File eboot = new File(sub, "EBOOT.PBP");
                if (eboot.isFile()) {
                    String title = parsePbpTitle(eboot, sub.getName());
                    Bitmap icon = parsePbpIcon(eboot);
                    // Detect PS1 POPS EBOOTs: CATEGORY = "ME" in PARAM.SFO
                    String sfoCategory = parsePbpCategory(eboot);
                    String cat = "ME".equals(sfoCategory) ? "PS1" : "Homebrew";
                    result.add(new GameItem(eboot, title, cat, eboot.length(), icon));
                }
            }
        }

        // Sort alphabetically by title
        result.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        return result;
    }

    private static String parseIsoTitle(File isoFile) {
        try (Iso9660 iso = new Iso9660(isoFile)) {
            Iso9660.Entry sfoEntry = iso.find("/PSP_GAME/PARAM.SFO");
            if (sfoEntry == null) sfoEntry = iso.find("/UMD_VIDEO/PARAM.SFO");
            if (sfoEntry != null && sfoEntry.size > 0 && sfoEntry.size < 65536) {
                byte[] sfoData = new byte[(int)sfoEntry.size];
                iso.read(sfoEntry.sector * Iso9660.SECTOR, sfoData, 0, sfoData.length);
                String title = extractSfoTitle(sfoData);
                if (title != null && !title.trim().isEmpty()) return title.trim();
            }
        } catch (Throwable ignored) {}

        // Fallback: clean filename
        String name = isoFile.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ');
    }

    private static Bitmap parseIsoIcon(File isoFile) {
        try (Iso9660 iso = new Iso9660(isoFile)) {
            Iso9660.Entry iconEntry = iso.find("/PSP_GAME/ICON0.PNG");
            if (iconEntry == null) iconEntry = iso.find("/UMD_VIDEO/ICON0.PNG");
            if (iconEntry != null && iconEntry.size > 0 && iconEntry.size < 2_000_000) {
                byte[] data = new byte[(int)iconEntry.size];
                iso.read(iconEntry.sector * Iso9660.SECTOR, data, 0, data.length);
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** PIC1.PNG (background art) or PIC0.PNG from a disc image (PSP_GAME or UMD_VIDEO) or an EBOOT.PBP; null if none. */
    static Bitmap loadArt(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        String name = file.getName().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".iso")) {
                try (Iso9660 iso = new Iso9660(file)) {
                    for (String path : new String[]{"/PSP_GAME/PIC1.PNG", "/UMD_VIDEO/PIC1.PNG", "/PSP_GAME/PIC0.PNG", "/UMD_VIDEO/PIC0.PNG"}) {
                        Iso9660.Entry e = iso.find(path);
                        if (e == null || e.size <= 0 || e.size > 4_000_000) continue;
                        byte[] data = new byte[(int)e.size]; iso.read(e.sector * Iso9660.SECTOR, data, 0, data.length);
                        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o); if (b != null) return b;
                    }
                }
            } else if (name.endsWith(".pbp")) {
                // PIC1.PNG = section[4], PIC0.PNG = section[3] in PBP layout
                // Use readPbpOffsets for large file support (PS1 POPS = 200MB+)
                long[] offsets = readPbpOffsets(file);
                if (offsets != null) {
                    // Try PIC1.PNG (index 4) then PIC0.PNG (index 3)
                    for (int idx : new int[]{4, 3}) {
                        long secOff = offsets[idx], secSize = offsets[idx + 1] - offsets[idx];
                        if (secSize <= 0 || secSize > 4_000_000) continue;
                        byte[] data = new byte[(int)secSize];
                        try (RandomAccessFile raf2 = new RandomAccessFile(file, "r")) { raf2.seek(secOff); raf2.readFully(data); }
                        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o); if (b != null) return b;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Reads PBP section offsets directly from the file header (no size limit, unlike PbpPackage.inspect).
     *  Returns an array of 9 offsets [0..7 = section starts, 8 = file size], or null if not a valid PBP.
     *  This works for large PS1 POPS EBOOTs (200MB+) which PbpPackage.inspect() rejects. */
    private static long[] readPbpOffsets(File pbpFile) {
        try (RandomAccessFile raf = new RandomAccessFile(pbpFile, "r")) {
            if (raf.length() < 40) return null;
            raf.seek(0);
            byte[] hdr = new byte[40];
            raf.readFully(hdr);
            // Validate magic \x00PBP and version 0x00010000
            if (hdr[0] != 0 || hdr[1] != 'P' || hdr[2] != 'B' || hdr[3] != 'P') return null;
            long[] offsets = new long[9];
            for (int i = 0; i < 8; i++) {
                int base = 8 + i * 4;
                offsets[i] = ((hdr[base] & 0xFFL)) | ((hdr[base+1] & 0xFFL) << 8)
                           | ((hdr[base+2] & 0xFFL) << 16) | ((hdr[base+3] & 0xFFL) << 24);
            }
            offsets[8] = raf.length();
            return offsets;
        } catch (Throwable ignored) { return null; }
    }

    public static String parsePbpTitle(File pbpFile, String fallback) {
        try {
            long[] offsets = readPbpOffsets(pbpFile);
            if (offsets == null) return fallback.replace('_', ' ');
            long sfoOff = offsets[0], sfoSize = offsets[1] - offsets[0];
            if (sfoSize <= 0 || sfoSize > 65536) return fallback.replace('_', ' ');
            byte[] data = new byte[(int)sfoSize];
            try (RandomAccessFile raf = new RandomAccessFile(pbpFile, "r")) { raf.seek(sfoOff); raf.readFully(data); }
            String title = extractSfoTitle(data);
            if (title != null && !title.trim().isEmpty()) return title.trim();
        } catch (Throwable ignored) {}
        return fallback.replace('_', ' ');
    }

    public static Bitmap parsePbpIcon(File pbpFile) {
        try {
            long[] offsets = readPbpOffsets(pbpFile);
            if (offsets == null || offsets.length < 3) return null;
            long iconOff = offsets[1], iconSize = offsets[2] - offsets[1];
            if (iconSize <= 0 || iconSize > 2_000_000) return null;
            byte[] data = new byte[(int)iconSize];
            try (RandomAccessFile raf = new RandomAccessFile(pbpFile, "r")) { raf.seek(iconOff); raf.readFully(data); }
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Reads the CATEGORY field from PARAM.SFO embedded in an EBOOT.PBP.
     *  Returns "ME" for PS1 POPS EBOOTs, "MG"/"UG" for PSP homebrew, or null on failure. */
    public static String parsePbpCategory(File pbpFile) {
        try {
            long[] offsets = readPbpOffsets(pbpFile);
            if (offsets == null) return null;
            long sfoOff = offsets[0], sfoSize = offsets[1] - offsets[0];
            if (sfoSize <= 0 || sfoSize > 65536) return null;
            byte[] data = new byte[(int)sfoSize];
            try (RandomAccessFile raf = new RandomAccessFile(pbpFile, "r")) { raf.seek(sfoOff); raf.readFully(data); }
            return extractSfoCategory(data);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Extracts "TITLE" entry from standard PSP PARAM.SFO byte array. */
    public static String extractSfoTitle(byte[] b) {
        if (b == null || b.length < 20) return null;
        if (b[0] != 0x00 || b[1] != 'P' || b[2] != 'S' || b[3] != 'F') return null;

        int keyTableOffset = readLe32(b, 8);
        int dataTableOffset = readLe32(b, 12);
        int entryCount = readLe32(b, 16);

        int pos = 20;
        for (int i = 0; i < entryCount && pos + 16 <= b.length; i++, pos += 16) {
            int keyOffset = readLe16(b, pos);
            int dataLen = readLe32(b, pos + 4);
            int dataOffset = readLe32(b, pos + 12);

            int kStart = keyTableOffset + keyOffset;
            int kEnd = kStart;
            while (kEnd < b.length && b[kEnd] != 0) kEnd++;
            if (kEnd <= b.length) {
                String key = new String(b, kStart, kEnd - kStart, StandardCharsets.UTF_8);
                if ("TITLE".equals(key)) {
                    int dStart = dataTableOffset + dataOffset;
                    int dEnd = dStart;
                    while (dEnd < dStart + dataLen && dEnd < b.length && b[dEnd] != 0) dEnd++;
                    return new String(b, dStart, dEnd - dStart, StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** Extracts "CATEGORY" entry from standard PSP PARAM.SFO byte array.
     *  Returns "ME" for PS1 POPS EBOOTs, "MG"/"UG" for PSP homebrew/games, null if absent. */
    public static String extractSfoCategory(byte[] b) {
        if (b == null || b.length < 20) return null;
        if (b[0] != 0x00 || b[1] != 'P' || b[2] != 'S' || b[3] != 'F') return null;

        int keyTableOffset = readLe32(b, 8);
        int dataTableOffset = readLe32(b, 12);
        int entryCount = readLe32(b, 16);

        int pos = 20;
        for (int i = 0; i < entryCount && pos + 16 <= b.length; i++, pos += 16) {
            int keyOffset = readLe16(b, pos);
            int dataLen = readLe32(b, pos + 4);
            int dataOffset = readLe32(b, pos + 12);

            int kStart = keyTableOffset + keyOffset;
            int kEnd = kStart;
            while (kEnd < b.length && b[kEnd] != 0) kEnd++;
            if (kEnd <= b.length) {
                String key = new String(b, kStart, kEnd - kStart, StandardCharsets.UTF_8);
                if ("CATEGORY".equals(key)) {
                    int dStart = dataTableOffset + dataOffset;
                    int dEnd = dStart;
                    while (dEnd < dStart + dataLen && dEnd < b.length && b[dEnd] != 0) dEnd++;
                    return new String(b, dStart, dEnd - dStart, StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static int readLe16(byte[] b, int off) {
        return (b[off] & 255) | ((b[off + 1] & 255) << 8);
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 255) | ((b[off + 1] & 255) << 8) | ((b[off + 2] & 255) << 16) | ((b[off + 3] & 255) << 24);
    }
}
