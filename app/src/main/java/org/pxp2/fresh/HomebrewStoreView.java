package org.pxp2.fresh;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PSP Homebrew Store tab view for PXP2.
 * Integrates VitaHomebrewDB PSP catalog, built-in pre-cached top homebrew list,
 * background live updates, category filtering, search, and automatic download & unpack engine.
 */
public final class HomebrewStoreView extends LinearLayout {
    private final int ACCENT = XmbTheme.accent(getContext()); // XMB theme colour
    private static final String CATALOG_URL = "https://raw.githubusercontent.com/DrDecki/VitaHomebrewDB/main/psp_apps.json";
    private static final String ICON_BASE_URL = "https://raw.githubusercontent.com/DrDecki/VitaHomebrewDB/main/icons/";

    private static final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private static final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);
    private static final ExecutorService iconExecutor = Executors.newFixedThreadPool(4);

    private static final LruCache<String, Bitmap> iconMemCache;
    static {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8; // 1/8th of memory
        iconMemCache = new LruCache<String, Bitmap>(Math.max(1024, cacheSize)) {
            @Override protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    private static final String PRECACHED_JSON = "[\n"
        + "  {\"name\":\"Apollo Save Tool\",\"version\":\"v2.3.2\",\"author\":\"bucanero\",\"type\":\"14\",\"size\":\"7362152\",\"url\":\"https://github.com/bucanero/apollo-psp/releases/download/v2.3.2/apollo-psp.zip\",\"icon\":\"6dfa4c6a101e35286b1682491881364b052ca4fc0339fb4e159fcb53773035d6.png\",\"description\":\"Savegame manager for PSP. Download, unlock, patch and resign save files directly on PSP.\"},\n"
        + "  {\"name\":\"Cave Story\",\"version\":\"v1.0\",\"author\":\"Studio Pixel / Roor\",\"type\":\"11\",\"size\":\"1832010\",\"url\":\"https://archive.org/download/cavestory-4end-psp.7z/cavestory-4end-psp.zip\",\"icon\":\"\",\"description\":\"The legendary 2D action-adventure platformer ported to PSP with original music and cutscenes.\"},\n"
        + "  {\"name\":\"Doom PSP\",\"version\":\"v1.4\",\"author\":\"kgsws\",\"type\":\"12\",\"size\":\"4945328\",\"url\":\"https://archive.org/download/doom.7z_202106/doom.zip\",\"icon\":\"3f9906d2d48bf5b2725d2b7042a9ae639a0fa886ef8e811aa0c18d0ba9622d50.png\",\"description\":\"Classic Doom engine ported to PSP with shareware WAD, stereo sound, and custom controls.\"},\n"
        + "  {\"name\":\"CSPSP\",\"version\":\"v1.92b\",\"author\":\"kevinbchen\",\"type\":\"11\",\"size\":\"12513410\",\"url\":\"https://github.com/kevinbchen/cspsp/releases/download/v1.92b/cspsp-1.92b.zip\",\"icon\":\"49405d4fe444ec3c2e428c0575d710fa532ae1fa7fe4516ffbb6374f1b80d0d8.png\",\"description\":\"2D top-down multiplayer Counter-Strike clone with bots, custom weapons, and map editor.\"},\n"
        + "  {\"name\":\"DaedalusX64\",\"version\":\"v1.1.8\",\"author\":\"DaedalusX64 Team\",\"type\":\"15\",\"size\":\"2434257\",\"url\":\"https://github.com/DaedalusX64/daedalus/releases/download/1.1.8/DaedalusX64_1_1_8.zip\",\"icon\":\"9559c5d10526e8389656fb154d868971fce81a8b981cf0ec7fb7bca06fcf1899.png\",\"description\":\"Nintendo 64 emulator for the PSP with DynaRec JIT compiler and high compatibility.\"},\n"
        + "  {\"name\":\"PSPident\",\"version\":\"v1.5\",\"author\":\"Yoti\",\"type\":\"14\",\"size\":\"142851\",\"url\":\"https://github.com/Yoti/psp_pspident/releases/download/v1.5/pspIdent_v1.5_20230922-030352.zip\",\"icon\":\"\",\"description\":\"Hardware and motherboard identification tool displaying motherboard model, tachyon, baryon revisions.\"},\n"
        + "  {\"name\":\"PKGi PSP\",\"version\":\"v1.1.0\",\"author\":\"bucanero\",\"type\":\"14\",\"size\":\"1048576\",\"url\":\"https://github.com/bucanero/pkgi-psp/releases/download/v1.1.0/pkgi-psp.zip\",\"icon\":\"04ee9d1b068305c48bbf9d0ef7b7ce7005471c26b801a2ec96f3cf0d1bb391e6.png\",\"description\":\"Open-source content downloader and package installer for PSP.\"},\n"
        + "  {\"name\":\"CMFileManager PSP\",\"version\":\"v4.12\",\"author\":\"joel16\",\"type\":\"14\",\"size\":\"1205862\",\"url\":\"https://github.com/joel16/CMFileManager-PSP/releases/download/v4.12/CMFileManager-PSP.zip\",\"icon\":\"71cb05f0a71be76c6c49870dcfb9dc906be896791e847779f225e34aa8a90da5.png\",\"description\":\"Comprehensive file manager and explorer for PSP Memory Stick with text and image viewer.\"},\n"
        + "  {\"name\":\"Chocolate Doom\",\"version\":\"v3.0.0\",\"author\":\"Chocolate Doom Team\",\"type\":\"12\",\"size\":\"2420000\",\"url\":\"https://archive.org/download/chocolate-doom-3.0.0/Chocolate%20Doom%203.0.0.zip\",\"icon\":\"f19f2a0df8cfcfd79ce7cf1e52dbfc8aefebca03df9b4f4fb8d8ef51ef4d0b0a.png\",\"description\":\"Historically accurate Doom source port maintaining original DOS Doom gameplay and behavior.\"},\n"
        + "  {\"name\":\"RetroArch\",\"version\":\"v1.9.14\",\"author\":\"Libretro Team\",\"type\":\"15\",\"size\":\"41943040\",\"url\":\"https://archive.org/download/retro-arch-1.9.0.7z/RetroArch-1.9.14.zip\",\"icon\":\"272e505230bf31c9e8fc5507be6cb50a6dbcbbeea9e8020e85f0c43efb6fbfa7.png\",\"description\":\"Multi-system frontend and modular emulation system for retro gaming.\"},\n"
        + "  {\"name\":\"Bookr\",\"version\":\"v8.1\",\"author\":\"Carlos / Nguyen\",\"type\":\"14\",\"size\":\"1850000\",\"url\":\"https://archive.org/download/bookr-mod-8.1/Bookr%20Mod%208.1.zip\",\"icon\":\"3a42ca4cb3827ec318c41490212f45ecf5fa80905e71887e583344605908ef13.png\",\"description\":\"Document and eBook reader for PSP supporting PDF, TXT, and comic book archives.\"}\n"
        + "]";

    public static final class HomebrewItem {
        public final String id;
        public final String name;
        public final String version;
        public final String author;
        public final String description;
        public final String category; // "Games", "Ports", "Emulators", "Utilities"
        public final long size;
        public final String sizeFormatted;
        public final String url;
        public final String iconUrl;

        // Dynamic status
        public volatile boolean isInstalled;
        public volatile File installedEboot;
        public volatile int downloadProgress = -1; // -1 = idle, 0..100 = downloading, 101 = installing
        public volatile String statusMessage = null;

        public HomebrewItem(String id, String name, String version, String author,
                            String description, String category, long size, String url, String iconUrl) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.author = author;
            this.description = description;
            this.category = category;
            this.size = size;
            this.sizeFormatted = GameCatalogView.formatSize(size);
            this.url = url;
            this.iconUrl = iconUrl;
        }

        public String cleanFolderName() {
            String clean = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            clean = clean.replaceAll("_+", "_");
            if (clean.startsWith("_")) clean = clean.substring(1);
            if (clean.endsWith("_")) clean = clean.substring(0, clean.length() - 1);
            return clean.isEmpty() ? "Homebrew_" + Math.abs(name.hashCode()) : clean;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Runnable onInstallComplete;

    private TextView headerTitle, headerCount, statusBanner;
    private ProgressBar loadingBar;
    private Button refreshBtn;
    private EditText searchInput;
    private LinearLayout categoryRow;
    private LinearLayout listContainer;
    private TextView emptyText;

    private final List<HomebrewItem> allItems = new ArrayList<>();
    private final List<HomebrewItem> displayedItems = new ArrayList<>();
    private final Map<String, View> activeCardViews = new ConcurrentHashMap<>();

    private static final String[] CATEGORIES = {"All", "Games", "Ports", "Emulators", "Utilities"};
    private String selectedCategory = "All";
    private String searchQuery = "";

    public HomebrewStoreView(Context context, SharedPreferences prefs, Runnable onInstallComplete) {
        super(context);
        this.prefs = prefs;
        this.onInstallComplete = onInstallComplete;
        initUi();
        loadInitialCatalog();
        fetchLiveCatalog();
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
        headerTitle.setText("PSP Homebrew Store");
        headerTitle.setTextSize(18);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextColor(Color.WHITE);
        titleCol.addView(headerTitle);

        TextView subTitle = new TextView(getContext());
        subTitle.setText("Online catalog & 1-tap Memory Stick installer");
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
        refreshBtn.setOnClickListener(v -> fetchLiveCatalog());
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

        // Search Bar
        searchInput = new EditText(getContext());
        searchInput.setHint("Search homebrew by name, author, or category...");
        searchInput.setHintTextColor(0x80ffffff);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(14);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0x26ffffff);
        searchBg.setCornerRadius(dp(12));
        searchBg.setStroke(dp(1), 0x33ffffff);
        searchInput.setBackground(searchBg);
        searchInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = dp(12);
        addView(searchInput, searchLp);

        // Category Pills (Horizontal Scroll)
        HorizontalScrollView catScroll = new HorizontalScrollView(getContext());
        catScroll.setHorizontalScrollBarEnabled(false);
        categoryRow = new LinearLayout(getContext());
        categoryRow.setOrientation(LinearLayout.HORIZONTAL);
        categoryRow.setPadding(0, dp(8), 0, dp(4));

        for (String cat : CATEGORIES) {
            Button catBtn = new Button(getContext());
            catBtn.setText(cat);
            catBtn.setAllCaps(false);
            catBtn.setTextSize(13);
            catBtn.setMinHeight(dp(32));
            catBtn.setPadding(dp(14), dp(2), dp(14), dp(2));
            catBtn.setStateListAnimator(null);
            updateCategoryPillStyle(catBtn, cat.equals(selectedCategory));
            catBtn.setOnClickListener(v -> {
                selectedCategory = cat;
                refreshCategoryPills();
                applyFilter();
            });
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            pLp.rightMargin = dp(8);
            categoryRow.addView(catBtn, pLp);
        }
        catScroll.addView(categoryRow);
        addView(catScroll);

        // Status Banner (for download/install notifications)
        statusBanner = new TextView(getContext());
        statusBanner.setTextSize(12);
        statusBanner.setTextColor(0xff99ff99);
        statusBanner.setVisibility(View.GONE);
        statusBanner.setPadding(dp(4), dp(4), dp(4), dp(4));
        addView(statusBanner);

        // List Container for cards
        listContainer = new LinearLayout(getContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(8);
        addView(listContainer, listLp);

        // Empty state message
        emptyText = new TextView(getContext());
        emptyText.setText("No homebrew matching your search or category filter.");
        emptyText.setTextSize(13);
        emptyText.setTextColor(0x99ffffff);
        emptyText.setPadding(dp(8), dp(16), dp(8), dp(16));
        emptyText.setVisibility(View.GONE);
        addView(emptyText);
    }

    private void updateCategoryPillStyle(Button btn, boolean active) {
        GradientDrawable pBg = new GradientDrawable();
        pBg.setCornerRadius(dp(16));
        if (active) {
            pBg.setColor(ACCENT);
            btn.setTextColor(Color.WHITE);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            pBg.setColor(0x1affffff);
            btn.setTextColor(0xccffffff);
            btn.setTypeface(Typeface.DEFAULT);
        }
        btn.setBackground(pBg);
    }

    private void refreshCategoryPills() {
        for (int i = 0; i < categoryRow.getChildCount(); i++) {
            View v = categoryRow.getChildAt(i);
            if (v instanceof Button) {
                Button b = (Button) v;
                updateCategoryPillStyle(b, b.getText().toString().equals(selectedCategory));
            }
        }
    }

    // ---- Catalog Loading & Caching --------------------------------------------------------------
    private void loadInitialCatalog() {
        File cacheFile = new File(getContext().getFilesDir(), "homebrew_catalog.json");
        String jsonContent = null;
        if (cacheFile.isFile()) {
            try {
                jsonContent = readFileToString(cacheFile);
            } catch (Exception ignored) {}
        }
        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            jsonContent = PRECACHED_JSON;
        }

        try {
            List<HomebrewItem> parsed = parseCatalogJson(jsonContent);
            if (!parsed.isEmpty()) {
                synchronized (allItems) {
                    allItems.clear();
                    allItems.addAll(parsed);
                    checkInstalledStatuses(allItems);
                }
                applyFilter();
            }
        } catch (Exception ignored) {}
    }

    public void refreshInstalled() {
        netExecutor.execute(() -> {
            List<HomebrewItem> snapshot;
            synchronized (allItems) {
                snapshot = new ArrayList<>(allItems);
            }
            checkInstalledStatuses(snapshot);
            mainHandler.post(this::applyFilter);
        });
    }

    private void fetchLiveCatalog() {
        loadingBar.setVisibility(View.VISIBLE);
        refreshBtn.setEnabled(false);

        netExecutor.execute(() -> {
            String downloadedJson = null;
            try {
                downloadedJson = fetchUrlAsString(CATALOG_URL);
            } catch (Exception ignored) {}

            final String liveJson = downloadedJson;
            mainHandler.post(() -> {
                if (liveJson != null && !liveJson.trim().isEmpty()) {
                    try {
                        List<HomebrewItem> liveItems = parseCatalogJson(liveJson);
                        if (!liveItems.isEmpty()) {
                            // Save to disk cache
                            saveCatalogToDisk(liveJson);

                            // Merge live items with built-in top list (ensures items like Cave Story/PSPident stay present)
                            Map<String, HomebrewItem> map = new LinkedHashMap<>();
                            for (HomebrewItem it : parseCatalogJson(PRECACHED_JSON)) {
                                map.put(it.name.toLowerCase(Locale.ROOT), it);
                            }
                            for (HomebrewItem it : liveItems) {
                                map.put(it.name.toLowerCase(Locale.ROOT), it);
                            }

                            synchronized (allItems) {
                                allItems.clear();
                                allItems.addAll(map.values());
                                allItems.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                                checkInstalledStatuses(allItems);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                applyFilter();
                loadingBar.setVisibility(View.GONE);
                refreshBtn.setEnabled(true);
            });
        });
    }

    private void saveCatalogToDisk(String json) {
        try {
            File dest = new File(getContext().getFilesDir(), "homebrew_catalog.json");
            File tmp = new File(getContext().getFilesDir(), "homebrew_catalog.json.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            tmp.renameTo(dest);
        } catch (Exception ignored) {}
    }

    private static List<HomebrewItem> parseCatalogJson(String json) {
        List<HomebrewItem> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String name = obj.optString("name", "").trim();
                if (name.isEmpty()) continue;

                String id = obj.optString("id", String.valueOf(i));
                String version = obj.optString("version", "").trim();
                String author = obj.optString("author", "Homebrew Community").trim();
                String desc = obj.optString("description", "").trim();
                if (desc.isEmpty()) desc = obj.optString("long_description", "").trim();

                String typeStr = obj.optString("type", "");
                String category = mapCategory(typeStr);

                long size = 0;
                String sizeStr = obj.optString("size", "0");
                try { size = Long.parseLong(sizeStr); } catch (Exception ignored) {}

                String url = obj.optString("url", "").trim();
                String iconField = obj.optString("icon", "").trim();
                String iconUrl = "";
                if (!iconField.isEmpty()) {
                    if (iconField.startsWith("http://") || iconField.startsWith("https://")) {
                        iconUrl = iconField;
                    } else {
                        iconUrl = ICON_BASE_URL + iconField;
                    }
                }

                result.add(new HomebrewItem(id, name, version, author, desc, category, size, url, iconUrl));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String mapCategory(String type) {
        if ("1".equals(type) || "11".equals(type)) return "Games";
        if ("4".equals(type) || "12".equals(type)) return "Ports";
        if ("15".equals(type)) return "Emulators";
        if ("14".equals(type)) return "Utilities";
        return "Games";
    }

    // ---- Installed Status Detection -------------------------------------------------------------
    private void checkInstalledStatuses(List<HomebrewItem> items) {
        File root = PxpStorage.memoryStick(getContext());
        String[] gameDirs = {"PSP/GAME", "PSP/GAMES", "PSP/GAME150"};
        Map<String, File> installedFolders = new HashMap<>();
        Map<String, File> installedTitles = new HashMap<>();

        for (String gd : gameDirs) {
            File base = new File(root, gd);
            if (!base.isDirectory()) continue;
            File[] subs = base.listFiles();
            if (subs == null) continue;
            for (File sub : subs) {
                if (!sub.isDirectory()) continue;
                File eboot = new File(sub, "EBOOT.PBP");
                if (eboot.isFile()) {
                    installedFolders.put(sub.getName().toLowerCase(Locale.ROOT), eboot);
                    String title = GameCatalogView.parsePbpTitle(eboot, "");
                    if (title != null && !title.trim().isEmpty()) {
                        installedTitles.put(title.trim().toLowerCase(Locale.ROOT), eboot);
                    }
                }
            }
        }

        for (HomebrewItem item : items) {
            File match = null;
            String cleanName = item.cleanFolderName().toLowerCase(Locale.ROOT);
            String nameLower = item.name.toLowerCase(Locale.ROOT);

            if (installedFolders.containsKey(cleanName)) {
                match = installedFolders.get(cleanName);
            } else if (installedTitles.containsKey(nameLower)) {
                match = installedTitles.get(nameLower);
            } else {
                // Partial check
                for (Map.Entry<String, File> entry : installedFolders.entrySet()) {
                    if (cleanName.contains(entry.getKey()) || entry.getKey().contains(cleanName)) {
                        match = entry.getValue();
                        break;
                    }
                }
                if (match == null) {
                    for (Map.Entry<String, File> entry : installedTitles.entrySet()) {
                        if (nameLower.contains(entry.getKey()) || entry.getKey().contains(nameLower)) {
                            match = entry.getValue();
                            break;
                        }
                    }
                }
            }

            item.isInstalled = (match != null);
            item.installedEboot = match;
        }
    }

    // ---- Filtering & List Population ------------------------------------------------------------
    private void applyFilter() {
        List<HomebrewItem> snapshot;
        synchronized (allItems) {
            snapshot = new ArrayList<>(allItems);
        }
        displayedItems.clear();
        for (HomebrewItem it : snapshot) {
            // Category check
            if (!selectedCategory.equals("All") && !it.category.equalsIgnoreCase(selectedCategory)) {
                continue;
            }
            // Search query check
            if (!searchQuery.isEmpty()) {
                boolean matchName = it.name.toLowerCase(Locale.ROOT).contains(searchQuery);
                boolean matchAuthor = it.author.toLowerCase(Locale.ROOT).contains(searchQuery);
                boolean matchDesc = it.description.toLowerCase(Locale.ROOT).contains(searchQuery);
                boolean matchCat = it.category.toLowerCase(Locale.ROOT).contains(searchQuery);
                if (!matchName && !matchAuthor && !matchDesc && !matchCat) {
                    continue;
                }
            }
            displayedItems.add(it);
        }

        headerCount.setText(displayedItems.size() + (displayedItems.size() == 1 ? " item" : " items"));
        populateList();
    }

    private void populateList() {
        listContainer.removeAllViews();
        activeCardViews.clear();

        if (displayedItems.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        emptyText.setVisibility(View.GONE);

        for (HomebrewItem item : displayedItems) {
            View card = createItemCard(item);
            activeCardViews.put(item.name, card);
            listContainer.addView(card);
        }
    }

    private View createItemCard(HomebrewItem item) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(14));
        cardBg.setColor(0x1affffff);
        cardBg.setStroke(dp(1), 0x22ffffff);
        card.setBackground(cardBg);

        // Main content row (Icon + Text columns)
        LinearLayout topBar = new LinearLayout(getContext());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        // Icon View
        ImageView iconView = new ImageView(getContext());
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        loadIcon(item, iconView);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        iconLp.rightMargin = dp(12);
        topBar.addView(iconView, iconLp);

        // Text metadata column
        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);

        // Title row
        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(getContext());
        titleView.setText(item.name);
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        if (!item.version.isEmpty()) {
            TextView verBadge = new TextView(getContext());
            verBadge.setText(item.version);
            verBadge.setTextSize(11);
            verBadge.setTextColor(0xccffffff);
            verBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable verBg = new GradientDrawable();
            verBg.setColor(0x26ffffff);
            verBg.setCornerRadius(dp(6));
            verBadge.setBackground(verBg);
            titleRow.addView(verBadge);
        }
        textCol.addView(titleRow);

        // Author and Badges row
        LinearLayout metaRow = new LinearLayout(getContext());
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(2);

        TextView catBadge = new TextView(getContext());
        catBadge.setText(item.category);
        catBadge.setTextSize(11);
        catBadge.setTypeface(Typeface.DEFAULT_BOLD);
        catBadge.setTextColor(ACCENT);
        catBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable catBg = new GradientDrawable();
        catBg.setColor(0x28b46bff);
        catBg.setCornerRadius(dp(6));
        catBadge.setBackground(catBg);
        metaRow.addView(catBadge);

        TextView authorView = new TextView(getContext());
        authorView.setText("  by " + item.author + (item.size > 0 ? "  •  " + item.sizeFormatted : ""));
        authorView.setTextSize(12);
        authorView.setTextColor(0xb3ffffff);
        authorView.setSingleLine(true);
        authorView.setEllipsize(TextUtils.TruncateAt.END);
        metaRow.addView(authorView);
        textCol.addView(metaRow, metaLp);

        // Description
        if (!item.description.isEmpty()) {
            TextView descView = new TextView(getContext());
            descView.setText(item.description);
            descView.setTextSize(12);
            descView.setTextColor(0xaaffffff);
            descView.setMaxLines(2);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            descLp.topMargin = dp(4);
            textCol.addView(descView, descLp);
        }

        topBar.addView(textCol, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));
        card.addView(topBar);

        // Action row: Status, Progress, and Action button
        LinearLayout actionsRow = new LinearLayout(getContext());
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(8);

        // Status text / progress indicator on the left
        LinearLayout statusCol = new LinearLayout(getContext());
        statusCol.setOrientation(LinearLayout.VERTICAL);
        statusCol.setGravity(Gravity.CENTER_VERTICAL);

        if (item.downloadProgress >= 0) {
            TextView progText = new TextView(getContext());
            progText.setText(item.statusMessage != null ? item.statusMessage : ("Downloading... " + item.downloadProgress + "%"));
            progText.setTextSize(12);
            progText.setTextColor(ACCENT);
            statusCol.addView(progText);

            ProgressBar pb = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(Math.min(100, item.downloadProgress));
            pb.setIndeterminate(item.downloadProgress > 100);
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(120), dp(4));
            pLp.topMargin = dp(2);
            statusCol.addView(pb, pLp);
        } else if (item.isInstalled) {
            TextView instText = new TextView(getContext());
            instText.setText("✓ Installed");
            instText.setTextSize(12);
            instText.setTypeface(Typeface.DEFAULT_BOLD);
            instText.setTextColor(0xff88ff88);
            statusCol.addView(instText);
        } else if (item.statusMessage != null) {
            TextView errText = new TextView(getContext());
            errText.setText(item.statusMessage);
            errText.setTextSize(11);
            errText.setTextColor(0xffff8888);
            errText.setSingleLine(true);
            errText.setEllipsize(TextUtils.TruncateAt.END);
            statusCol.addView(errText);
        }
        actionsRow.addView(statusCol, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        // Action Button
        Button actionBtn = new Button(getContext());
        actionBtn.setAllCaps(false);
        actionBtn.setTextSize(12);
        actionBtn.setMinHeight(dp(32));
        actionBtn.setPadding(dp(14), dp(2), dp(14), dp(2));
        actionBtn.setStateListAnimator(null);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(12));

        if (item.downloadProgress >= 0) {
            actionBtn.setText("Downloading...");
            actionBtn.setEnabled(false);
            btnBg.setColor(0x22ffffff);
            actionBtn.setTextColor(0x66ffffff);
        } else if (item.isInstalled) {
            actionBtn.setText("▶ Play");
            actionBtn.setEnabled(true);
            btnBg.setColor(ACCENT);
            actionBtn.setTextColor(Color.WHITE);
            actionBtn.setOnClickListener(v -> playHomebrew(item));
        } else {
            actionBtn.setText("⬇ Download");
            actionBtn.setEnabled(true);
            btnBg.setColor(0x33ffffff);
            actionBtn.setTextColor(Color.WHITE);
            actionBtn.setOnClickListener(v -> startDownload(item));
        }
        actionBtn.setBackground(btnBg);
        actionsRow.addView(actionBtn);

        card.addView(actionsRow, actLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);
        return card;
    }

    private void updateItemCardUi(HomebrewItem item) {
        mainHandler.post(() -> {
            View oldView = activeCardViews.get(item.name);
            if (oldView != null && listContainer.indexOfChild(oldView) >= 0) {
                int index = listContainer.indexOfChild(oldView);
                View newView = createItemCard(item);
                activeCardViews.put(item.name, newView);
                listContainer.removeViewAt(index);
                listContainer.addView(newView, index);
            }
        });
    }

    // ---- Icon Loading & Caching -----------------------------------------------------------------
    private void loadIcon(HomebrewItem item, ImageView iv) {
        String key = item.iconUrl.isEmpty() ? ("default_" + item.category) : item.iconUrl;
        Bitmap cached = iconMemCache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }

        // Set placeholder bitmap while loading
        Bitmap fallback = drawDefaultIcon(item.name, item.category);
        iv.setImageBitmap(fallback);
        if (item.iconUrl.isEmpty()) {
            iconMemCache.put(key, fallback);
            return;
        }

        iv.setTag(item.iconUrl);
        iconExecutor.execute(() -> {
            Bitmap bmp = loadIconFromDiskOrNet(item.iconUrl);
            if (bmp != null) {
                iconMemCache.put(key, bmp);
                mainHandler.post(() -> {
                    if (item.iconUrl.equals(iv.getTag())) {
                        iv.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private Bitmap loadIconFromDiskOrNet(String urlString) {
        try {
            File cacheDir = new File(getContext().getCacheDir(), "hb_icons");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            String hashName = "ic_" + Math.abs(urlString.hashCode()) + ".png";
            File diskFile = new File(cacheDir, hashName);
            if (diskFile.isFile() && diskFile.length() > 0) {
                Bitmap bmp = BitmapFactory.decodeFile(diskFile.getPath());
                if (bmp != null) return bmp;
            }

            // Fetch from network
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "PXP2");
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(diskFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) fos.write(buf, 0, len);
                }
                return BitmapFactory.decodeFile(diskFile.getPath());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Bitmap drawDefaultIcon(String title, String category) {
        int size = 96;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Rounded badge
        p.setColor(0x33ffffff);
        RectF r = new RectF(4, 4, size - 4, size - 4);
        c.drawRoundRect(r, 20, 20, p);

        // Letter or icon symbol
        p.setColor(Color.WHITE);
        p.setTextSize(36);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        String letter = (title != null && !title.isEmpty()) ? title.substring(0, 1).toUpperCase(Locale.ROOT) : "P";
        c.drawText(letter, size / 2f, size / 2f + 13, p);

        return b;
    }

    // ---- Download & Automatic Unpack/Install Engine ---------------------------------------------
    private void startDownload(HomebrewItem item) {
        if (item.downloadProgress >= 0) return;
        if (item.url == null || item.url.trim().isEmpty()) {
            Toast.makeText(getContext(), "Download URL not available for " + item.name, Toast.LENGTH_SHORT).show();
            return;
        }

        item.downloadProgress = 0;
        item.statusMessage = "Starting download...";
        updateItemCardUi(item);

        downloadExecutor.execute(() -> {
            File tempDl = new File(getContext().getCacheDir(), "dl_" + item.cleanFolderName() + "_" + System.currentTimeMillis() + ".tmp");
            try {
                downloadFileWithProgress(item.url, tempDl, (downloaded, total) -> {
                    if (total > 0) {
                        int pct = (int) ((downloaded * 100) / total);
                        item.downloadProgress = pct;
                        item.statusMessage = "Downloading... " + pct + "%";
                    } else {
                        item.downloadProgress = 50;
                        item.statusMessage = "Downloading... " + GameCatalogView.formatSize(downloaded);
                    }
                    updateItemCardUi(item);
                });

                item.downloadProgress = 101;
                item.statusMessage = "Extracting & Installing...";
                updateItemCardUi(item);

                File installedFile = installDownloadedFile(tempDl, item);

                item.downloadProgress = -1;
                item.isInstalled = true;
                item.installedEboot = installedFile;
                item.statusMessage = null;

                mainHandler.post(() -> {
                    updateItemCardUi(item);
                    statusBanner.setText("✓ Successfully installed " + item.name + " to Memory Stick");
                    statusBanner.setVisibility(View.VISIBLE);
                    Toast.makeText(getContext(), item.name + " installed to PSP/GAME", Toast.LENGTH_SHORT).show();
                    if (onInstallComplete != null) {
                        onInstallComplete.run();
                    }
                });
            } catch (Exception e) {
                item.downloadProgress = -1;
                item.statusMessage = "Failed: " + e.getMessage();
                mainHandler.post(() -> {
                    updateItemCardUi(item);
                    Toast.makeText(getContext(), "Install error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (tempDl.exists()) tempDl.delete();
            }
        });
    }

    private interface ProgressListener {
        void onProgress(long current, long total);
    }

    private static void downloadFileWithProgress(String urlString, File targetFile, ProgressListener listener) throws IOException {
        HttpURLConnection conn = openConnectionFollowingRedirects(urlString);
        long totalLength = conn.getContentLengthLong();
        long current = 0;
        long lastUpdate = 0;

        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buf = new byte[32768];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                current += len;
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 150) {
                    lastUpdate = now;
                    if (listener != null) listener.onProgress(current, totalLength);
                }
            }
            out.getFD().sync();
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnectionFollowingRedirects(String urlString) throws IOException {
        String currentUrl = urlString;
        int redirects = 0;
        while (redirects++ < 8) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 PXP2-PSP-Store");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Redirect missing Location header");
                currentUrl = loc;
                continue;
            }
            if (code != 200) {
                conn.disconnect();
                throw new IOException("HTTP error " + code + " downloading from server");
            }
            return conn;
        }
        throw new IOException("Too many redirects for " + urlString);
    }

    private File installDownloadedFile(File downloadedFile, HomebrewItem item) throws Exception {
        boolean isZip = isZipFile(downloadedFile, item.url);
        File memStick = PxpStorage.memoryStick(getContext());
        File gameDir = new File(memStick, "PSP/GAME");
        gameDir.mkdirs();

        if (isZip) {
            File stagingDir = new File(getContext().getCacheDir(), "unpack_" + item.cleanFolderName() + "_" + System.currentTimeMillis());
            stagingDir.mkdirs();
            try {
                unpackZip(downloadedFile, stagingDir);

                File eboot = findFileRecursive(stagingDir, "EBOOT.PBP");
                if (eboot == null) {
                    throw new IOException("No EBOOT.PBP found in downloaded zip archive");
                }

                File ebootParent = eboot.getParentFile();
                File targetGameDir;
                if (ebootParent.equals(stagingDir)) {
                    // EBOOT is in zip root
                    targetGameDir = new File(gameDir, item.cleanFolderName());
                    targetGameDir.mkdirs();
                    copyDirectory(stagingDir, targetGameDir);
                } else {
                    String folderName = ebootParent.getName();
                    if (folderName.equalsIgnoreCase("GAME") || folderName.equalsIgnoreCase("PSP")) {
                        folderName = item.cleanFolderName();
                    }
                    targetGameDir = new File(gameDir, folderName);
                    targetGameDir.mkdirs();
                    copyDirectory(ebootParent, targetGameDir);

                    // Check for extra root directories like seplugins
                    File[] topLevel = stagingDir.listFiles();
                    if (topLevel != null) {
                        for (File f : topLevel) {
                            if (f.isDirectory() && !f.getName().equalsIgnoreCase("PSP") && !f.getName().equalsIgnoreCase(folderName)) {
                                copyDirectory(f, new File(memStick, f.getName()));
                            }
                        }
                    }
                }
                return new File(targetGameDir, "EBOOT.PBP");
            } finally {
                deleteRecursive(stagingDir);
            }
        } else {
            // Standalone EBOOT.PBP or .pbp
            File targetGameDir = new File(gameDir, item.cleanFolderName());
            targetGameDir.mkdirs();
            File destEboot = new File(targetGameDir, "EBOOT.PBP");
            copyFile(downloadedFile, destEboot);
            return destEboot;
        }
    }

    private static boolean isZipFile(File file, String url) {
        if (url != null && url.toLowerCase(Locale.ROOT).endsWith(".zip")) return true;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[4];
            int n = fis.read(header);
            return n == 4 && header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void unpackZip(File zipFile, File targetDir) throws IOException {
        String canonicalTarget = targetDir.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = zis.getNextEntry()) != null) {
                String cleanName = entry.getName().replace('\\', '/');
                File outFile = new File(targetDir, cleanName);
                if (!outFile.getCanonicalPath().startsWith(canonicalTarget)) {
                    throw new SecurityException("Zip entry path traversal detected: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File p = outFile.getParentFile();
                    if (p != null && !p.exists()) p.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static File findFileRecursive(File dir, String filename) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile() && f.getName().equalsIgnoreCase(filename)) return f;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                File found = findFileRecursive(f, filename);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void copyDirectory(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
            File[] files = src.listFiles();
            if (files != null) {
                for (File f : files) {
                    copyDirectory(f, new File(dest, f.getName()));
                }
            }
        } else {
            File p = dest.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            copyFile(src, dest);
        }
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] subs = f.listFiles();
            if (subs != null) for (File s : subs) deleteRecursive(s);
        }
        f.delete();
    }

    private void playHomebrew(HomebrewItem item) {
        if (item.installedEboot != null && item.installedEboot.isFile()) {
            prefs.edit().putString(XmbActivity.UMD, item.installedEboot.getPath()).apply();
        }
        getContext().startActivity(new Intent(getContext(), XmbActivity.class));
    }

    private static String readFileToString(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String fetchUrlAsString(String urlStr) throws IOException {
        HttpURLConnection conn = openConnectionFollowingRedirects(urlStr);
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }
}
