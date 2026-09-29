package org.pxp2.fresh;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Server Transfer & ISO Streaming View for PXP2.
 * Connects to the PC Python Content Hub (e.g. http://192.168.0.240:8080).
 * Features:
 * - High-speed Wi-Fi transfer of ISO games and Homebrew into device storage.
 * - Live ISO Streaming (the emulator reads the ISO from the hub with HTTP Range requests; nothing is copied first).
 * - Stream ISO only; Homebrew requires local transfer.
 */
public final class ServerTransferView extends LinearLayout {
    private final int ACCENT = XmbTheme.accent(getContext()); // XMB theme colour
    private static final String DEFAULT_SERVER = "http://192.168.0.240:8080";
    static final String PREF_SERVER_URL = "server_hub_url";
    /** The last /api/catalog JSON from the hub; XmbActivity turns it into XMB library entries. */
    static final String PREF_CATALOG_JSON = "server_catalog_json";
    private static final ExecutorService netExecutor = Executors.newFixedThreadPool(4);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Runnable onLibraryChanged;

    private TextView serverInput; // shows the auto-detected hub address (no manual entry)
    private Button connectBtn;
    private TextView statusText;
    private ProgressBar loadingBar;
    private LinearLayout listContainer;
    private TextView emptyText;

    private String activeFilter = "ALL"; // ALL, GAMES, VIDEOS, HOMEBREW
    private final List<ServerItem> allItems = new ArrayList<>();
    private final Map<String, DownloadTask> activeDownloads = new HashMap<>();

    public static class ServerItem {
        public final String name;
        public final String filename;
        public final String category; // GAMES, VIDEOS, HOMEBREW, PS1
        public final long size;
        public final String sizeFormatted;
        public final String ext;
        public final boolean isDir;
        public final String discId;
        public final String artworkIcon;
        public final String artworkBg;
        /** Name the file is saved under on the device: "<TITLE> [ULUS-10081].iso" for PSP discs (hub-supplied). */
        public String filenameForDevice = "";
        /** PS1: the PSP/GAME folder of the converted EBOOT.PBP (its DISC_ID). */
        public String deviceFolder = "";
        /** PS1: the converted EBOOT carries a real DATA.PSP (else it cannot boot on a PSP). */
        public boolean popsReady = true;

        public ServerItem(String name, String filename, String category, long size, String sizeFormatted, String ext, boolean isDir) {
            this(name, filename, category, size, sizeFormatted, ext, isDir, "", "", "");
        }

        /** The file name the device keeps: the hub's rename, else the name on the PC. */
        String deviceName() { return filenameForDevice != null && !filenameForDevice.isEmpty() ? filenameForDevice : filename; }

        public ServerItem(String name, String filename, String category, long size, String sizeFormatted, String ext, boolean isDir, String discId, String artworkIcon, String artworkBg) {
            this.name = name;
            this.filename = filename;
            this.category = category;
            this.size = size;
            this.sizeFormatted = sizeFormatted;
            this.ext = ext;
            this.isDir = isDir;
            this.discId = discId;
            this.artworkIcon = artworkIcon;
            this.artworkBg = artworkBg;
        }
    }

    private static class DownloadTask {
        public volatile int progress = 0;
        public volatile boolean isDownloading = false;
        public volatile boolean isFinished = false;
        public volatile String error = null;
        public ProgressBar bar;
        public TextView label;
        public Button actionBtn;
    }

    public ServerTransferView(Context context, SharedPreferences prefs, Runnable onLibraryChanged) {
        super(context);
        this.prefs = prefs;
        this.onLibraryChanged = onLibraryChanged;
        setOrientation(VERTICAL);
        buildUi();
        fetchCatalog();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        removeAllViews();

        // 1. Header Card with Server IP Input
        LinearLayout headerCard = new LinearLayout(getContext());
        headerCard.setOrientation(VERTICAL);
        headerCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable headerBg = new GradientDrawable();
        headerBg.setColor(0x2effffff);
        headerBg.setCornerRadius(dp(18));
        headerBg.setStroke(dp(1), 0x33ffffff);
        headerCard.setBackground(headerBg);
        addView(headerCard);

        TextView title = new TextView(getContext());
        title.setText("📡 PC Content Hub & Wireless Transfer");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        headerCard.addView(title);

        TextView subtitle = new TextView(getContext());
        subtitle.setText("Connect to your PC Python server to transfer games & homebrew or stream ISOs over Wi-Fi.");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xb3ffffff);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        headerCard.addView(subtitle);

        // Server URL input row
        LinearLayout connRow = new LinearLayout(getContext());
        connRow.setOrientation(HORIZONTAL);
        connRow.setGravity(Gravity.CENTER_VERTICAL);
        headerCard.addView(connRow);

        serverInput = new TextView(getContext());
        serverInput.setText("PC Hub: searching the network…");
        serverInput.setTextSize(13);
        serverInput.setTextColor(Color.WHITE);
        serverInput.setSingleLine(true);
        serverInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0x33000000);
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), 0x44ffffff);
        serverInput.setBackground(inputBg);
        connRow.addView(serverInput, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        connectBtn = new Button(getContext());
        connectBtn.setText("Search again");
        connectBtn.setAllCaps(false);
        connectBtn.setTextSize(13);
        connectBtn.setTextColor(Color.WHITE);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(ACCENT);
        btnBg.setCornerRadius(dp(8));
        connectBtn.setBackground(btnBg);
        connectBtn.setPadding(dp(14), dp(4), dp(14), dp(4));
        LayoutParams btnLp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(38));
        btnLp.leftMargin = dp(8);
        connectBtn.setOnClickListener(v -> {
            prefs.edit().remove(PREF_SERVER_URL).apply(); // forget the last hub and search from scratch
            fetchCatalog();
        });
        connRow.addView(connectBtn, btnLp);

        // Status row
        statusText = new TextView(getContext());
        statusText.setText("Connecting to server...");
        statusText.setTextSize(12);
        statusText.setTextColor(0xccffffff);
        statusText.setPadding(0, dp(8), 0, 0);
        headerCard.addView(statusText);

        loadingBar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(true);
        loadingBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        loadingBar.setVisibility(View.GONE);
        LayoutParams lbLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(4));
        lbLp.topMargin = dp(8);
        headerCard.addView(loadingBar, lbLp);

        // 2. Filter Pills (All, Games, Videos, Homebrew, PS1)
        HorizontalScrollView filterScroll = new HorizontalScrollView(getContext());
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setFillViewport(true);
        LinearLayout filterBar = new LinearLayout(getContext());
        filterBar.setOrientation(HORIZONTAL);
        filterBar.setPadding(0, dp(14), 0, dp(10));
        filterScroll.addView(filterBar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        addView(filterScroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        String[] filters = {"ALL", "GAMES", "VIDEOS", "HOMEBREW", "PS1"};
        String[] labels = {"All", "🎮 Games", "🎬 Videos", "🕹️ Homebrew", "💿 PS1"};
        for (int i = 0; i < filters.length; i++) {
            final String f = filters[i];
            Button pill = new Button(getContext());
            pill.setText(labels[i]);
            pill.setAllCaps(false);
            pill.setTextSize(12);
            pill.setMinHeight(dp(32));
            pill.setPadding(dp(10), dp(2), dp(10), dp(2));
            styleFilterPill(pill, activeFilter.equals(f));
            pill.setOnClickListener(v -> {
                activeFilter = f;
                buildUi();
                populateCards();
            });
            LayoutParams pLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) pLp.leftMargin = dp(6);
            filterBar.addView(pill, pLp);
        }

        // 3. Scrollable list of items
        listContainer = new LinearLayout(getContext());
        listContainer.setOrientation(VERTICAL);
        addView(listContainer);

        emptyText = new TextView(getContext());
        emptyText.setText("No items found. Ensure Python server is running (start-server.bat).");
        emptyText.setTextSize(13);
        emptyText.setTextColor(0x88ffffff);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, dp(24), 0, dp(24));
        emptyText.setVisibility(View.GONE);
        listContainer.addView(emptyText);
    }

    private void styleFilterPill(Button b, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (active) {
            bg.setColor(ACCENT);
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(0x22ffffff);
            b.setTextColor(0x99ffffff);
            b.setTypeface(Typeface.DEFAULT);
        }
        b.setBackground(bg);
    }

    /** True if a PXP2 hub answers /api/catalog at {@code base} within the timeout. */
    static boolean probe(String base, int timeoutMs) {
        // /api/ping identifies the hub; hubs that predate it answer /api/catalog instead.
        for (String path : new String[]{"/api/ping", "/api/catalog"}) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
                c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs * 3);
                int code = c.getResponseCode();
                String type = c.getContentType(); c.disconnect();
                if (code == 200 && type != null && type.contains("json")) return true;
                if (code != 404) return false;
            } catch (IOException e) { return false; }
        }
        return false;
    }

    public void fetchCatalog() {
        final String lastUrl = prefs.getString(PREF_SERVER_URL, "").replaceAll("/+$", "");
        loadingBar.setVisibility(View.VISIBLE);
        statusText.setText("Searching for the PXP2 PC Hub on your Wi-Fi...");
        serverInput.setText("PC Hub: searching the network…");

        netExecutor.execute(() -> {
            // Auto-detect: the last hub that worked, then a UDP broadcast the hub answers, then a scan of the local subnet.
            List<String> candidates = new ArrayList<>();
            if (!lastUrl.isEmpty() && probe(lastUrl, 1200)) candidates.add(lastUrl);
            if (candidates.isEmpty()) { String found = HubDiscovery.discover(); if (found != null) candidates.add(found); }
            if (candidates.isEmpty()) candidates.add(DEFAULT_SERVER); // last resort, reported as a failure if unreachable

            Exception lastError = null;
            String workingUrl = null, catalogJson = null;
            List<ServerItem> loaded = new ArrayList<>();

            for (String candidate : candidates) {
                try {
                    URL url = new URL(candidate + "/api/catalog");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(4000);
                    conn.setRequestProperty("User-Agent", "PXP2-Android");

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();

                        JSONObject root = new JSONObject(sb.toString());
                        catalogJson = sb.toString();

                        // Parse GAMES
                        if (root.has("games")) {
                            JSONArray gArr = root.getJSONArray("games");
                            for (int i = 0; i < gArr.length(); i++) {
                                JSONObject o = gArr.getJSONObject(i);
                                ServerItem it = new ServerItem(
                                    o.optString("title", "").isEmpty() ? o.getString("name") : o.getString("title"),
                                    o.getString("filename"),
                                    o.getString("category"),
                                    o.getLong("size"),
                                    o.getString("size_formatted"),
                                    o.getString("ext"),
                                    false,
                                    o.optString("disc_id", ""), "", ""
                                );
                                it.filenameForDevice = o.optString("filename_for_device", "");
                                loaded.add(it);
                            }
                        }

                        // Parse VIDEOS
                        if (root.has("videos")) {
                            JSONArray vArr = root.getJSONArray("videos");
                            for (int i = 0; i < vArr.length(); i++) {
                                JSONObject o = vArr.getJSONObject(i);
                                loaded.add(new ServerItem(
                                    o.getString("name"),
                                    o.getString("filename"),
                                    o.getString("category"),
                                    o.getLong("size"),
                                    o.getString("size_formatted"),
                                    o.getString("ext"),
                                    false
                                ));
                            }
                        }

                        // Parse HOMEBREW
                        if (root.has("homebrew")) {
                            JSONArray hArr = root.getJSONArray("homebrew");
                            for (int i = 0; i < hArr.length(); i++) {
                                JSONObject o = hArr.getJSONObject(i);
                                loaded.add(new ServerItem(
                                    o.getString("name"),
                                    o.getString("filename"),
                                    o.getString("category"),
                                    o.getLong("size"),
                                    o.getString("size_formatted"),
                                    o.getString("ext"),
                                    o.optBoolean("is_dir", false)
                                ));
                            }
                        }

                        // Parse PS1
                        if (root.has("ps1")) {
                            JSONArray pArr = root.getJSONArray("ps1");
                            for (int i = 0; i < pArr.length(); i++) {
                                JSONObject o = pArr.getJSONObject(i);
                                ServerItem it = new ServerItem(
                                    o.getString("name"),
                                    o.getString("filename"),
                                    o.optString("category", "PS1"),
                                    o.optLong("size", 0),
                                    o.optString("size_formatted", ""),
                                    o.optString("ext", "PS1"),
                                    false,
                                    o.optString("disc_id", ""),
                                    o.optString("artwork_icon", ""),
                                    o.optString("artwork_bg", "")
                                );
                                it.filenameForDevice = o.optString("filename_for_device", "EBOOT.PBP");
                                it.deviceFolder = o.optString("device_folder", it.discId);
                                it.popsReady = o.optBoolean("pops_ready", false);
                                loaded.add(it);
                            }
                        }

                        workingUrl = candidate;
                        break;
                    } else {
                        lastError = new IOException("HTTP " + code + " from " + candidate);
                    }
                } catch (Exception e) {
                    lastError = e;
                }
            }

            if (workingUrl != null) {
                final String finalWorkingUrl = workingUrl, finalCatalog = catalogJson;
                mainHandler.post(() -> {
                    prefs.edit().putString(PREF_SERVER_URL, finalWorkingUrl).putString(PREF_CATALOG_JSON, finalCatalog).apply();
                    if (serverInput != null) serverInput.setText("PC Hub found at " + finalWorkingUrl.replace("http://", ""));
                    synchronized (allItems) {
                        allItems.clear();
                        allItems.addAll(loaded);
                    }
                    loadingBar.setVisibility(View.GONE);
                    statusText.setText("● Connected to PC Hub (" + loaded.size() + " items available)");
                    statusText.setTextColor(0xff99ff99);
                    populateCards();
                });
            } else {
                final String errMsg = lastError != null ? lastError.getMessage() : "Connection failed";
                mainHandler.post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    statusText.setText("✕ No PXP2 PC Hub found on this Wi-Fi. Start pxp_server.py on your PC (same network), then tap Search again.");
                    if (serverInput != null) serverInput.setText("PC Hub: not found");
                    statusText.setTextColor(0xffff8888);
                    populateCards();
                });
            }
        });
    }

    private void populateCards() {
        listContainer.removeAllViews();

        List<ServerItem> filtered = new ArrayList<>();
        synchronized (allItems) {
            for (ServerItem it : allItems) {
                if ("ALL".equals(activeFilter) || it.category.equalsIgnoreCase(activeFilter)) {
                    filtered.add(it);
                }
            }
        }

        if (filtered.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            listContainer.addView(emptyText);
            return;
        }
        emptyText.setVisibility(View.GONE);

        for (ServerItem item : filtered) {
            listContainer.addView(createItemCard(item));
        }
    }

    private View createItemCard(ServerItem item) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x22ffffff);
        cardBg.setCornerRadius(dp(14));
        cardBg.setStroke(dp(1), 0x2affffff);
        card.setBackground(cardBg);

        LayoutParams cardLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);

        // Top info row
        LinearLayout topRow = new LinearLayout(getContext());
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow);

        boolean isIsoGameOrVideo = "GAMES".equals(item.category) || "VIDEOS".equals(item.category);
        boolean isPs1 = "PS1".equalsIgnoreCase(item.category);

        TextView iconBadge = new TextView(getContext());
        String iconChar = isIsoGameOrVideo ? ("GAMES".equals(item.category) ? "🎮" : "🎬") : (isPs1 ? "💿" : "🕹️");
        iconBadge.setText(iconChar);
        iconBadge.setTextSize(18);
        iconBadge.setPadding(0, 0, dp(10), 0);
        topRow.addView(iconBadge);
        if (isPs1 && item.artworkIcon != null && !item.artworkIcon.isEmpty()) {
            // The hub's ICON0 (144x80, cut from the game's title screen or cover) replaces the emoji once it arrives.
            ImageView art = new ImageView(getContext());
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setVisibility(View.GONE);
            LayoutParams artLp = new LayoutParams(dp(72), dp(40));
            artLp.rightMargin = dp(10);
            topRow.addView(art, 0, artLp);
            loadHubImage(item.artworkIcon, bmp -> {
                art.setImageBitmap(bmp);
                art.setVisibility(View.VISIBLE);
                iconBadge.setVisibility(View.GONE);
            });
        }

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(VERTICAL);
        topRow.addView(textCol, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        TextView titleView = new TextView(getContext());
        titleView.setText(item.name);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(titleView);

        TextView metaView = new TextView(getContext());
        if (isPs1) {
            metaView.setText("PS1  •  " + (item.discId.isEmpty() ? "" : item.discId + "  •  ") + item.sizeFormatted + "  •  PSP/GAME/" + item.deviceFolder);
        } else {
            metaView.setText(item.category + "  •  " + item.sizeFormatted + "  •  " + item.deviceName());
        }
        metaView.setTextSize(11);
        metaView.setTextColor(0xb3ffffff);
        metaView.setSingleLine(true);
        metaView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textCol.addView(metaView);
        if (isPs1) { // converted for real PSPs; PXP2 cannot run POPS yet, so this never claims the game runs here
            TextView note = new TextView(getContext());
            note.setText(GameCatalogView.PS1_NOTE + (item.popsReady ? "" : "  •  the hub has no DATA.PSP yet (see psp.md)"));
            note.setTextSize(11);
            note.setTextColor(0xffffd27a);
            textCol.addView(note);
        }

        // Action Buttons Row
        LinearLayout actionsRow = new LinearLayout(getContext());
        actionsRow.setOrientation(HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        actionsRow.setPadding(0, dp(10), 0, 0);
        card.addView(actionsRow);

        File localFile = localTarget(item);
        // a disc saved before the hub renamed discs still counts as transferred
        boolean alreadyInstalled = localFile.exists()
            || (isIsoGameOrVideo && new File(PxpStorage.memoryStick(getContext()), "ISO/" + item.filename).exists())
            || ("HOMEBREW".equals(item.category) && new File(PxpStorage.memoryStick(getContext()), "PSP/GAME/" + item.name + "/EBOOT.PBP").exists());

        // Progress row (hidden by default)
        ProgressBar dlProgress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        dlProgress.setMax(100);
        dlProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        dlProgress.setVisibility(View.GONE);
        card.addView(dlProgress, new LayoutParams(LayoutParams.MATCH_PARENT, dp(4)));

        TextView dlStatus = new TextView(getContext());
        dlStatus.setTextSize(11);
        dlStatus.setTextColor(0xccffffff);
        dlStatus.setVisibility(View.GONE);
        dlStatus.setPadding(0, dp(4), 0, 0);
        card.addView(dlStatus);

        DownloadTask dt = activeDownloads.get(item.filename);
        if (dt != null) {
            dt.bar = dlProgress;
            dt.label = dlStatus;
        }

        // 1. Transfer Button (available for all items: ISO, Video, Homebrew, PS1)
        String transferText = alreadyInstalled ? "✓ Transferred" : (isPs1 ? "⬇ Convert & Transfer" : "⬇ Transfer to Device");
        Button transferBtn = actionButton(transferText, alreadyInstalled ? 0x33ffffff : ACCENT);
        if (alreadyInstalled) {
            transferBtn.setTextColor(0xff99ff99);
        } else {
            transferBtn.setOnClickListener(v -> startTransfer(item, dlProgress, dlStatus, transferBtn));
        }
        actionsRow.addView(transferBtn);

        // 2. Stream ISO Button (strictly for ISO games/videos ONLY, NOT homebrew or PS1!)
        if (isIsoGameOrVideo) {
            Button streamBtn = actionButton("▶ Stream ISO", 0xff28a745);
            LayoutParams sLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            sLp.leftMargin = dp(8);
            streamBtn.setOnClickListener(v -> streamIsoGame(item, dlStatus));
            actionsRow.addView(streamBtn, sLp);
        }

        return card;
    }

    private Button actionButton(String text, int bgColor) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(34));
        b.setPadding(dp(12), dp(2), dp(12), dp(2));
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        return b;
    }

    private void startTransfer(ServerItem item, ProgressBar bar, TextView status, Button btn) {
        btn.setEnabled(false);
        btn.setText("Transferring...");
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        status.setVisibility(View.VISIBLE);
        boolean isPs1 = "PS1".equalsIgnoreCase(item.category);
        status.setText(isPs1 ? "Converting & connecting to server..." : "Connecting to server...");

        final String serverUrl = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER).replaceAll("/+$", "");
        final String downloadUrl = serverUrl + "/download?cat=" + item.category.toLowerCase(Locale.ROOT) + "&file=" + URLEncoder.encode(item.filename);

        netExecutor.execute(() -> {
            try {
                URL url = new URL(downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                // a PS1 disc is converted by the hub before the first byte arrives (about 10-60 s; allow 15 minutes)
                conn.setReadTimeout(isPs1 ? 900000 : 180000);
                conn.setRequestProperty("User-Agent", "PXP2-Android");
                if (isPs1) mainHandler.post(() -> status.setText("The PC Hub is converting the disc to a PSP EBOOT.PBP..."));

                int code = conn.getResponseCode();
                if (code != 200) throw new IOException("Server returned HTTP " + code);
                if ("GAMES".equals(item.category) && item.filenameForDevice.isEmpty()) {
                    String cd = attachmentName(conn.getHeaderField("Content-Disposition")); // older catalog: take the hub's name
                    if (cd != null && cd.toLowerCase(Locale.ROOT).matches(".*\\.(iso|cso)$")) item.filenameForDevice = cd;
                }

                long totalBytes = conn.getContentLengthLong();
                if (totalBytes <= 0) totalBytes = item.size;

                File destFolder = new File(PxpStorage.memoryStick(getContext()), "PSP/GAME");
                File targetFile = localTarget(item);
                if (item.isDir) targetFile = new File(destFolder, item.filename + ".zip"); // unpacked into PSP/GAME below
                targetFile.getParentFile().mkdirs();
                File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".part");

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[65536];
                    long downloaded = 0;
                    int read;
                    long lastUpdate = 0;

                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        downloaded += read;
                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 100 || downloaded == totalBytes) {
                            lastUpdate = now;
                            final int pct = totalBytes > 0 ? (int)(downloaded * 100 / totalBytes) : 0;
                            final long d = downloaded;
                            final long t = totalBytes;
                            mainHandler.post(() -> {
                                bar.setProgress(pct);
                                status.setText(String.format(Locale.ROOT, "Transferring... %d%% (%.1f of %.1f MB)", pct, d / 1e6, t / 1e6));
                            });
                        }
                    }
                    out.flush();
                }

                if (tempFile.renameTo(targetFile)) {
                    // If homebrew zip, unpack it
                    if (targetFile.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        unpackHomebrewZip(targetFile, destFolder);
                        targetFile.delete();
                    }

                    mainHandler.post(() -> {
                        bar.setVisibility(View.GONE);
                        status.setText("✓ Transfer complete! Saved to device.");
                        status.setTextColor(0xff99ff99);
                        btn.setEnabled(true);
                        btn.setText("✓ Transferred");
                        if (onLibraryChanged != null) onLibraryChanged.run();
                    });
                } else {
                    throw new IOException("Failed to finalize downloaded file");
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    bar.setVisibility(View.GONE);
                    status.setText("Transfer failed: " + e.getMessage());
                    status.setTextColor(0xffff8888);
                    btn.setEnabled(true);
                    btn.setText("Retry Transfer");
                });
            }
        });
    }

    /**
     * Where a hub item is saved on the Memory Stick:
     * PSP discs and videos: PXP/ISO/<filename_for_device> ("<TITLE> [ULUS-10081].iso", renamed by the hub);
     * PS1: PXP/PSP/GAME/<DISC_ID>/EBOOT.PBP (converted by the hub); homebrew .pbp: PSP/GAME/<name>/EBOOT.PBP;
     * other homebrew (zip): PSP/GAME/<file>, unpacked after the download.
     */
    private File localTarget(ServerItem item) {
        File ms = PxpStorage.memoryStick(getContext());
        if ("GAMES".equals(item.category) || "VIDEOS".equals(item.category)) return new File(ms, "ISO/" + safeName(item.deviceName()));
        if ("PS1".equalsIgnoreCase(item.category)) {
            String folder = safeName(item.deviceFolder.isEmpty() ? item.discId : item.deviceFolder);
            if (folder.isEmpty()) folder = safeName(item.name.replaceAll("[^A-Za-z0-9]", ""));
            return new File(ms, "PSP/GAME/" + (folder.isEmpty() ? "PS1GAME" : folder) + "/EBOOT.PBP");
        }
        if (!item.isDir && item.filename.toLowerCase(Locale.ROOT).endsWith(".pbp")) return new File(ms, "PSP/GAME/" + safeName(item.name) + "/EBOOT.PBP");
        return new File(ms, "PSP/GAME/" + safeName(item.filename));
    }

    /** A single path component: no separators or characters Android storage refuses. */
    private static String safeName(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", " ").replaceAll("\\s+", " ").trim();
        return t.equals(".") || t.equals("..") ? "" : t;
    }

    /** The file name in a Content-Disposition header (filename*=UTF-8'' preferred over filename=). */
    static String attachmentName(String cd) {
        if (cd == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*=UTF-8''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cd);
        try {
            if (m.find()) return java.net.URLDecoder.decode(m.group(1).trim().replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {}
        m = java.util.regex.Pattern.compile("filename=\"([^\"]*)\"").matcher(cd);
        return m.find() ? m.group(1) : null;
    }

    interface BitmapSink { void accept(Bitmap b); }

    /** Loads an image from the hub (path like /art/ps1/SLUS01411/icon0.png) in the background, cached in the app cache. */
    private void loadHubImage(String path, BitmapSink sink) {
        final String base = prefs.getString(PREF_SERVER_URL, "").replaceAll("/+$", "");
        if (base.isEmpty()) return;
        final File cache = new File(getContext().getCacheDir(), "hub_art/" + Integer.toHexString((base + path).hashCode()) + ".png");
        netExecutor.execute(() -> {
            Bitmap b = cache.isFile() ? BitmapFactory.decodeFile(cache.getAbsolutePath()) : null;
            if (b == null) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
                    c.setConnectTimeout(4000); c.setReadTimeout(30000);
                    if (c.getResponseCode() == 200) {
                        cache.getParentFile().mkdirs();
                        File tmp = new File(cache.getPath() + ".part");
                        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[16384]; int r;
                            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                        }
                        b = BitmapFactory.decodeFile(tmp.getAbsolutePath());
                        if (b != null && !tmp.renameTo(cache)) tmp.delete();
                    }
                    c.disconnect();
                } catch (IOException ignored) {}
            }
            final Bitmap fb = b;
            if (fb != null) mainHandler.post(() -> sink.accept(fb));
        });
    }

    private void unpackHomebrewZip(File zipFile, File gameRoot) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                File target = new File(gameRoot, name);
                if (!target.getCanonicalPath().startsWith(gameRoot.getCanonicalPath() + File.separator)) continue; // never write outside PSP/GAME
                target.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    int r;
                    while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                }
                zis.closeEntry();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Streams the ISO over Wi-Fi: the emulator reads the image from the hub with HTTP Range requests as the game needs
     * it (org.pxp2.core.HttpIsoSource, 256 KiB chunks with a small cache and read-ahead), so nothing is copied first.
     * The first sectors are checked here so that an unreachable hub or a non-ISO file is reported before launching.
     */
    private void streamIsoGame(ServerItem item, TextView status) {
        status.setVisibility(View.VISIBLE);
        status.setText("Connecting to the ISO stream...");
        status.setTextColor(ACCENT);

        final String serverUrl = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER).replaceAll("/+$", "");
        final String streamUrl = org.pxp2.core.LibraryEntry.serverIso(serverUrl, item.category.toLowerCase(Locale.ROOT), item.filename, item.size, item.name).downloadUrl();

        netExecutor.execute(() -> {
            try (org.pxp2.core.Iso9660 disc = new org.pxp2.core.Iso9660(new org.pxp2.core.HttpIsoSource(streamUrl, item.size > 0 ? item.size : -1))) {
                String id = disc.discId();
                mainHandler.post(() -> {
                    status.setText("▶ Streaming " + item.name + (id != null ? " (" + id + ")" : "") + " from the PC Hub");
                    status.setTextColor(0xff99ff99);
                    Intent intent = new Intent(getContext(), XmbActivity.class);
                    intent.putExtra(XmbActivity.STREAM_UMD, streamUrl);
                    getContext().startActivity(intent);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    status.setText("Stream error: " + e.getMessage());
                    status.setTextColor(0xffff8888);
                });
            }
        });
    }
}
