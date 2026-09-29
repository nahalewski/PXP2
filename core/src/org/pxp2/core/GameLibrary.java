package org.pxp2.core;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Virtual XMB entries for games that are not in the Memory Stick's PSP/GAME folder: local ISOs (PXP/ISO), ISOs on the
 * PC hub and homebrew on the hub. Each one appears in the Game column as {@code ms0:/PSP/GAME/PXPISO_<id>/EBOOT.PBP}
 * (homebrew: {@code PXPHB_<id>}), served from memory by {@link HleIo} and never written to the stick.
 *
 * <p><b>Virtual EBOOT.PBP.</b> Built from the game's own PSP_GAME/PARAM.SFO (CATEGORY rewritten to "MG" and BOOTABLE 1,
 * which vshmain requires of Memory Stick entries), ICON0.PNG, PIC0.PNG and PIC1.PNG, plus ICON1.PMF and SND0.AT3 for
 * local ISOs when they are small, and a 512-byte "~PSP" DATA.PSP stub. Launching the entry is intercepted in HleVsh.
 *
 * <p><b>Metadata cache.</b> The virtual PBP itself is the cache: {@code <cacheDir>/<sha1(key)>.pbp}, keyed by hub URL +
 * file name + size (server) or path + size + mtime (local). Entries without PSP_GAME/PARAM.SFO (UMD Video) or that cannot
 * be parsed get a {@code .none} marker so they are not fetched again. Uncached entries are read on background threads
 * and appear the next time the XMB lists the Memory Stick.
 *
 * <p><b>De-duplication</b> (one entry per game), applied every time the XMB lists ms0:/PSP/GAME:
 * <ul><li>ISOs are keyed by DISC_ID; the first one wins, and local ISOs come before server ISOs.</li>
 * <li>An ISO whose DISC_ID is the inserted UMD's is hidden (the UMD icon already shows it).</li>
 * <li>Server homebrew is hidden when a real PSP/GAME folder has the same folder name or the same TITLE + DISC_ID,
 * and duplicates among server homebrew show once.</li></ul>
 */
public final class GameLibrary {
    public static final String ISO_PREFIX = "PXPISO_", HOMEBREW_PREFIX = "PXPHB_";
    private static final GameLibrary SHARED = new GameLibrary();
    public static GameLibrary shared() { return SHARED; }

    public enum State { PENDING, READY, NONE }
    /** A library entry with its scanned metadata. */
    public static final class Item {
        public final LibraryEntry entry; public final String folder, key;
        public volatile State state = State.PENDING;
        public volatile String discId, title, reason;
        /** Homebrew: the PSP/GAME folder name it installs to; the EBOOT.PBP path inside a zip ("GAME/EBOOT.PBP"). */
        public volatile String installFolder, zipEboot;
        volatile File pbp; volatile long pbpLength;
        Item(LibraryEntry e, String key) {
            this.entry = e; this.key = key;
            folder = (e.kind == LibraryEntry.Kind.SERVER_HOMEBREW ? HOMEBREW_PREFIX : ISO_PREFIX) + sha1(key).substring(0, 8).toUpperCase(Locale.ROOT);
        }
        public boolean isIso() { return entry.kind != LibraryEntry.Kind.SERVER_HOMEBREW; }
        @Override public String toString() { return folder + " " + state + " " + discId + " \"" + title + "\" " + entry; }
    }

    private final List<Item> items = new ArrayList<>();
    private volatile File cacheDir;
    private final ExecutorService localScanner = Executors.newSingleThreadExecutor(r -> daemon(r, "PXP2 library (local)"));
    private final ExecutorService serverScanner = Executors.newFixedThreadPool(3, r -> daemon(r, "PXP2 library (hub)"));
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private volatile Map<String, Item> visible;
    /** Called (from a scanner thread) whenever an entry finishes scanning; may be null. */
    public volatile Runnable onChange;
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; }

    private GameLibrary() { }

    /** Replaces the library. Entries already scanned (same cache key) keep their metadata. */
    public void setEntries(List<LibraryEntry> entries) {
        List<Item> started = new ArrayList<>();
        synchronized (this) {
            Map<String, Item> old = new HashMap<>(); for (Item i : items) old.put(i.key, i);
            items.clear();
            // Local ISOs first so that they win over server copies of the same disc.
            List<LibraryEntry> ordered = new ArrayList<>(entries);
            ordered.sort(Comparator.comparingInt(e -> e.kind.ordinal()));
            Set<String> keys = new HashSet<>();
            for (LibraryEntry e : ordered) {
                String key = e.cacheKey(); if (!keys.add(key)) continue;
                Item i = old.get(key); if (i == null) { i = new Item(e, key); started.add(i); }
                items.add(i);
            }
            visible = null;
        }
        if (cacheDir != null) for (Item i : started) schedule(i);
    }
    /** Sets the metadata cache folder (once; later calls are ignored) and starts scanning. */
    public void attachCache(File dir) {
        List<Item> pending;
        synchronized (this) { if (cacheDir != null) return; cacheDir = dir; pending = new ArrayList<>(items); }
        for (Item i : pending) if (i.state == State.PENDING) schedule(i);
    }
    public synchronized List<Item> items() { return new ArrayList<>(items); }
    public List<String> log() { synchronized (log) { return new ArrayList<>(log); } }
    private void note(String s) { if (log.size() < 2000) log.add(s); }

    private void schedule(Item i) {
        File dir = cacheDir; String base = sha1(i.key);
        File pbp = new File(dir, base + ".pbp"), none = new File(dir, base + ".none");
        if (pbp.isFile() && ready(i, pbp)) return; // cached: available immediately
        if (none.isFile()) { i.state = State.NONE; try { i.reason = new String(Files.readAllBytes(none.toPath()), StandardCharsets.UTF_8); } catch (IOException ignored) { } return; }
        (i.entry.kind == LibraryEntry.Kind.LOCAL_ISO ? localScanner : serverScanner).execute(() -> scan(i, pbp, none));
    }
    /** Loads identity fields from a cached virtual PBP. */
    private boolean ready(Item i, File pbp) {
        try (RandomAccessFile f = new RandomAccessFile(pbp, "r")) {
            byte[] h = new byte[40]; f.readFully(h); ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int o0 = b.getInt(8), o1 = b.getInt(12); if (o1 <= o0 || o1 - o0 > 65536) return false;
            byte[] sfo = new byte[o1 - o0]; f.seek(o0); f.readFully(sfo);
            Map<String, Object> m = ParamSfo.parse(sfo);
            i.discId = ParamSfo.string(m, "DISC_ID"); i.title = ParamSfo.string(m, "TITLE");
            Object folder = m.get("PXP2_FOLDER"), zip = m.get("PXP2_ZIP_EBOOT");
            if (folder instanceof String) i.installFolder = (String)folder; if (zip instanceof String) i.zipEboot = (String)zip;
            i.pbp = pbp; i.pbpLength = pbp.length(); i.state = State.READY; return true;
        } catch (IOException | RuntimeException e) { return false; }
    }
    private void scan(Item i, File pbpFile, File noneFile) {
        long t0 = System.nanoTime();
        try {
            byte[] pbp = i.isIso() ? isoPbp(i) : homebrewPbp(i);
            pbpFile.getParentFile().mkdirs(); File tmp = new File(pbpFile.getPath() + ".tmp");
            Files.write(tmp.toPath(), pbp); Files.move(tmp.toPath(), pbpFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!ready(i, pbpFile)) throw new IOException("cached entry unreadable");
            note(String.format(Locale.ROOT, "library: %s ready in %d ms (%d bytes)", i, (System.nanoTime() - t0) / 1_000_000, pbp.length));
            synchronized (this) { visible = null; }
        } catch (NotAGame e) {
            i.reason = e.getMessage(); i.state = State.NONE; note("library: skipped " + i.entry + ": " + e.getMessage());
            try { noneFile.getParentFile().mkdirs(); Files.write(noneFile.toPath(), e.getMessage().getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        } catch (IOException | RuntimeException e) {
            // Network or read failure: not cached, so it is retried the next time the library is set.
            i.reason = String.valueOf(e.getMessage()); i.state = State.NONE; note("library: failed " + i.entry + ": " + e);
        }
        Runnable r = onChange; if (r != null) try { r.run(); } catch (RuntimeException ignored) { }
    }
    private static final class NotAGame extends IOException { NotAGame(String m) { super(m); } }

    // ---- metadata extraction --------------------------------------------------------------------------------------
    private static IsoSource metadataSource(LibraryEntry e) throws IOException {
        if (e.kind == LibraryEntry.Kind.LOCAL_ISO) return IsoSource.open(e.file);
        if (e.kind == LibraryEntry.Kind.SERVER_ISO) return IsoSource.wrap(new HttpIsoSource(e.downloadUrl(), e.size > 0 ? e.size : -1, 64 * 1024, 64, 0));
        return new HttpIsoSource(e.kind == LibraryEntry.Kind.SERVER_HOMEBREW && e.isDir ? e.downloadUrl("EBOOT.PBP") : e.downloadUrl(), e.isDir ? -1 : e.size > 0 ? e.size : -1, 64 * 1024, 64, 0);
    }
    private static byte[] isoFile(Iso9660 iso, String path, int limit) throws IOException {
        Iso9660.Entry e = iso.find(path); if (e == null || e.directory || e.size <= 0 || e.size > limit) return null;
        return iso.readFile(e);
    }
    private byte[] isoPbp(Item i) throws IOException {
        try (IsoSource src = metadataSource(i.entry)) {
            Iso9660 iso;
            try { iso = new Iso9660(src); } catch (IOException e) { if (e.getMessage() != null && e.getMessage().startsWith("Not an ISO")) throw new NotAGame(e.getMessage()); throw e; }
            byte[] sfo = isoFile(iso, "/PSP_GAME/PARAM.SFO", 65536);
            if (sfo == null) throw new NotAGame(iso.find("/UMD_VIDEO") != null ? "UMD Video disc" : "no PSP_GAME/PARAM.SFO");
            LinkedHashMap<String, Object> m;
            try { m = ParamSfo.parse(sfo); } catch (IOException e) { throw new NotAGame("bad PARAM.SFO"); }
            if (ParamSfo.string(m, "DISC_ID") == null) throw new NotAGame("PARAM.SFO has no DISC_ID");
            boolean local = i.entry.kind == LibraryEntry.Kind.LOCAL_ISO;
            byte[] icon0 = isoFile(iso, "/PSP_GAME/ICON0.PNG", 256 * 1024), pic0 = isoFile(iso, "/PSP_GAME/PIC0.PNG", 512 * 1024), pic1 = isoFile(iso, "/PSP_GAME/PIC1.PNG", 1024 * 1024);
            byte[] icon1 = local ? isoFile(iso, "/PSP_GAME/ICON1.PMF", 768 * 1024) : null, snd0 = local ? isoFile(iso, "/PSP_GAME/SND0.AT3", 768 * 1024) : null;
            m.put("CATEGORY", "MG"); m.put("BOOTABLE", 1); m.putIfAbsent("REGION", 0x8000); m.putIfAbsent("DISC_VERSION", "1.00"); m.putIfAbsent("PSP_SYSTEM_VER", "1.00");
            m.putIfAbsent("PARENTAL_LEVEL", 1); m.putIfAbsent("TITLE", i.entry.name);
            if (src instanceof HttpIsoSource) note("library: " + src);
            return ExitApp.pbp(ParamSfo.build(m), icon0, icon1, pic0, pic1, snd0);
        }
    }
    /** Reads the first bytes of a (possibly zipped) PBP: header, PARAM.SFO, ICON0 and, when close enough, PIC1. */
    private byte[] homebrewPbp(Item i) throws IOException {
        LibraryEntry e = i.entry; String lower = e.fileName.toLowerCase(Locale.ROOT);
        String folder; byte[] head;
        if (e.isDir || lower.endsWith(".pbp")) {
            folder = e.isDir ? e.fileName : LibraryEntry.stem(e.fileName);
            try (IsoSource src = metadataSource(e)) { head = pbpHead(new SourceStream(src, 0, src.length()), src.length()); }
        } else if (lower.endsWith(".zip")) {
            try (IsoSource src = metadataSource(e)) {
                ZipLocator z = locateEboot(src);
                i.zipEboot = z.name; String parent = z.name.contains("/") ? z.name.substring(0, z.name.lastIndexOf('/')) : "";
                folder = parent.isEmpty() ? LibraryEntry.stem(e.fileName) : parent.substring(parent.lastIndexOf('/') + 1);
                InputStream raw = new SourceStream(src, z.dataStart, z.compressedSize);
                InputStream in = z.method == 8 ? new InflaterInputStream(raw, new Inflater(true), 16384) : raw;
                head = pbpHead(in, z.size);
            }
        } else throw new NotAGame("unsupported homebrew package (" + e.fileName + ")");
        folder = safeFolder(folder);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN); int[] o = new int[9];
        for (int k = 0; k < 8; k++) o[k] = b.getInt(8 + 4 * k); o[8] = Integer.MAX_VALUE;
        byte[] sfo = section(head, o, 0), icon0 = section(head, o, 1), pic1 = section(head, o, 4), pic0 = section(head, o, 3);
        LinkedHashMap<String, Object> m;
        try { m = ParamSfo.parse(sfo); } catch (IOException ex) { throw new NotAGame("bad PARAM.SFO in EBOOT.PBP"); }
        m.putIfAbsent("TITLE", e.name); m.putIfAbsent("DISC_ID", "HOMEBREW0"); m.putIfAbsent("CATEGORY", "MG"); m.putIfAbsent("BOOTABLE", 1); m.putIfAbsent("REGION", 0x8000);
        m.putIfAbsent("DISC_VERSION", "1.00"); m.putIfAbsent("PARENTAL_LEVEL", 1); m.putIfAbsent("PSP_SYSTEM_VER", "1.00");
        m.put("PXP2_FOLDER", folder); if (i.zipEboot != null) m.put("PXP2_ZIP_EBOOT", i.zipEboot);
        return ExitApp.pbp(ParamSfo.build(m), icon0, null, pic0, pic1, null);
    }
    private static byte[] section(byte[] head, int[] o, int k) {
        if (o[k] < 40 || o[k + 1] <= o[k] || o[k + 1] > head.length || (k < 7 && o[k + 1] == Integer.MAX_VALUE)) return null;
        return Arrays.copyOfRange(head, o[k], o[k + 1]);
    }
    /** Reads the PBP header and the sections up to PIC1 (only up to ICON0 if PIC1 ends past 2 MiB). */
    private static byte[] pbpHead(InputStream in, long total) throws IOException {
        byte[] h = readN(in, 40);
        if (h[0] != 0 || h[1] != 'P' || h[2] != 'B' || h[3] != 'P') throw new NotAGame("not an EBOOT.PBP");
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int icon0End = b.getInt(16), pic1End = b.getInt(28);
        int want = pic1End > 0 && pic1End <= 2 * 1024 * 1024 ? pic1End : icon0End;
        if (want < 40 || want > 4 * 1024 * 1024 || (total > 0 && want > total)) throw new NotAGame("bad EBOOT.PBP header");
        byte[] out = Arrays.copyOf(h, want); byte[] rest = readN(in, want - 40); System.arraycopy(rest, 0, out, 40, rest.length);
        // Sections past what was read are dropped by section() (their end offset is beyond the buffer).
        return out;
    }
    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] out = new byte[n]; int got = 0;
        while (got < n) { int r = in.read(out, got, n - got); if (r < 0) throw new EOFException("short EBOOT.PBP"); got += r; }
        return out;
    }
    /** Sequential stream over an IsoSource window. */
    private static final class SourceStream extends InputStream {
        private final IsoSource src; private long pos; private final long end;
        SourceStream(IsoSource s, long start, long length) { src = s; pos = start; end = start + length; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) <= 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= end) return -1; int n = src.read(pos, b, off, (int)Math.min(len, end - pos)); if (n <= 0) return -1; pos += n; return n;
        }
    }
    private static final class ZipLocator { String name; int method; long dataStart, compressedSize, size; }
    /** Finds the shallowest EBOOT.PBP (any folder) in a zip through its central directory (random access, few requests). */
    private static ZipLocator locateEboot(IsoSource src) throws IOException {
        long len = src.length(); int tail = (int)Math.min(len, 65536 + 22); byte[] t = new byte[tail];
        if (src.read(len - tail, t, 0, tail) != tail) throw new IOException("short zip read");
        int eocd = -1; for (int p = tail - 22; p >= 0; p--) if (le32(t, p) == 0x06054b50) { eocd = p; break; }
        if (eocd < 0) throw new NotAGame("not a zip file");
        long cdSize = le32(t, eocd + 12) & 0xffffffffL, cdOff = le32(t, eocd + 16) & 0xffffffffL;
        if (cdSize > 8 * 1024 * 1024 || cdOff + cdSize > len) throw new NotAGame("zip64 or bad central directory");
        byte[] cd = new byte[(int)cdSize]; if (src.read(cdOff, cd, 0, cd.length) != cd.length) throw new IOException("short zip read");
        ZipLocator best = null; int bestDepth = Integer.MAX_VALUE;
        for (int p = 0; p + 46 <= cd.length && le32(cd, p) == 0x02014b50; ) {
            int method = le16(cd, p + 10), nameLen = le16(cd, p + 28), extra = le16(cd, p + 30), comment = le16(cd, p + 32);
            String name = new String(cd, p + 46, Math.min(nameLen, cd.length - p - 46), (le16(cd, p + 8) & 0x800) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1).replace('\\', '/');
            String upper = name.toUpperCase(Locale.ROOT); int depth = name.split("/").length;
            if ((upper.equals("EBOOT.PBP") || upper.endsWith("/EBOOT.PBP")) && !upper.contains("__MACOSX") && depth < bestDepth && (method == 0 || method == 8)) {
                best = new ZipLocator(); best.name = name; best.method = method; bestDepth = depth;
                best.compressedSize = le32(cd, p + 20) & 0xffffffffL; best.size = le32(cd, p + 24) & 0xffffffffL; best.dataStart = le32(cd, p + 42) & 0xffffffffL; // local header offset for now
            }
            p += 46 + nameLen + extra + comment;
        }
        if (best == null) throw new NotAGame("no EBOOT.PBP in the zip");
        byte[] lh = new byte[30]; if (src.read(best.dataStart, lh, 0, 30) != 30 || le32(lh, 0) != 0x04034b50) throw new IOException("bad zip local header");
        best.dataStart += 30 + le16(lh, 26) + le16(lh, 28);
        return best;
    }
    private static int le16(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8; }
    private static int le32(byte[] b, int p) { return le16(b, p) | le16(b, p + 2) << 16; }
    static String safeFolder(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "HOMEBREW" : s.length() > 64 ? s.substring(0, 64) : s;
    }

    // ---- the virtual PSP/GAME overlay -----------------------------------------------------------------------------
    private final Map<String, String> localSfoKeys = new HashMap<>(); // "path|mtime" -> "TITLE|DISC_ID"
    private String localKey(File eboot) {
        String id = eboot.getPath() + "|" + eboot.lastModified();
        synchronized (localSfoKeys) { if (localSfoKeys.containsKey(id)) return localSfoKeys.get(id); }
        String key = null;
        try (RandomAccessFile f = new RandomAccessFile(eboot, "r")) {
            byte[] h = new byte[40]; f.readFully(h); ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int o0 = b.getInt(8), o1 = b.getInt(12);
            if (h[1] == 'P' && h[2] == 'B' && o1 > o0 && o1 - o0 <= 65536) {
                byte[] sfo = new byte[o1 - o0]; f.seek(o0); f.readFully(sfo); Map<String, Object> m = ParamSfo.parse(sfo);
                key = ParamSfo.string(m, "TITLE") + "|" + ParamSfo.string(m, "DISC_ID");
            }
        } catch (IOException | RuntimeException ignored) { }
        synchronized (localSfoKeys) { localSfoKeys.put(id, key); }
        return key;
    }
    /**
     * The virtual folders to show in ms0:/PSP/GAME after de-duplication against the real folders in {@code gameDir} and
     * the inserted UMD's DISC_ID ({@code umdDiscId}, may be null).
     */
    public List<Item> gameDir(File gameDir, String umdDiscId) {
        Set<String> localNames = new HashSet<>(), localKeys = new HashSet<>();
        File[] dirs = gameDir == null ? null : gameDir.listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) {
            localNames.add(d.getName().toUpperCase(Locale.ROOT));
            File eboot = new File(d, "EBOOT.PBP"); if (eboot.isFile()) { String k = localKey(eboot); if (k != null) localKeys.add(k.toUpperCase(Locale.ROOT)); }
        }
        List<Item> out = new ArrayList<>(); Set<String> discIds = new HashSet<>(), homebrew = new HashSet<>();
        if (umdDiscId != null) discIds.add(umdDiscId.toUpperCase(Locale.ROOT));
        Map<String, Item> map = new LinkedHashMap<>();
        for (Item i : items()) {
            if (i.state != State.READY) continue;
            if (i.isIso()) {
                if (i.discId == null || !discIds.add(i.discId.toUpperCase(Locale.ROOT))) continue;
            } else {
                String name = i.installFolder == null ? "" : i.installFolder.toUpperCase(Locale.ROOT), key = (i.title + "|" + i.discId).toUpperCase(Locale.ROOT);
                if (localNames.contains(name) || localKeys.contains(key) || !homebrew.add(name) || !homebrew.add("KEY:" + key)) continue;
            }
            out.add(i); map.put(i.folder.toUpperCase(Locale.ROOT), i);
        }
        visible = map;
        return out;
    }
    /** Resolves "ms0:/PSP/GAME/PXPISO_x" or ".../EBOOT.PBP" (absolute, normalised) to a visible entry. */
    public Item find(String absPath, File gameDir, String umdDiscId) {
        String folder = folderOf(absPath); if (folder == null) return null;
        Map<String, Item> v = visible; if (v == null) { gameDir(gameDir, umdDiscId); v = visible; }
        return v == null ? null : v.get(folder);
    }
    /** "PXPISO_X" for paths inside a virtual folder, else null. */
    static String folderOf(String absPath) {
        if (absPath == null) return null;
        String u = absPath.toUpperCase(Locale.ROOT); String base = "MS0:/PSP/GAME/";
        if (!u.startsWith(base)) return null;
        String rest = u.substring(base.length()); int slash = rest.indexOf('/'); String folder = slash < 0 ? rest : rest.substring(0, slash);
        return folder.startsWith(ISO_PREFIX) || folder.startsWith(HOMEBREW_PREFIX) ? folder : null;
    }
    /** "EBOOT.PBP" or "" (the folder itself) for a virtual path; null for anything else inside it. */
    static String innerOf(String absPath) {
        String u = absPath.toUpperCase(Locale.ROOT); int at = u.indexOf('/', "MS0:/PSP/GAME/".length());
        String rest = at < 0 ? "" : u.substring(at + 1);
        return rest.isEmpty() ? "" : rest.equals("EBOOT.PBP") ? "EBOOT.PBP" : null;
    }
    /** The virtual EBOOT.PBP bytes. */
    public byte[] pbp(Item i) throws IOException { File f = i.pbp; if (f == null) throw new FileNotFoundException(i.folder); return Files.readAllBytes(f.toPath()); }
    public long pbpLength(Item i) { return i.pbpLength; }
    public long pbpTime(Item i) { File f = i.pbp; return f == null ? 0 : f.lastModified(); }

    // ---- launching ----------------------------------------------------------------------------------------------
    /**
     * Server homebrew cannot stream: this downloads it into {@code gameRoot}/{@code installFolder} (unpacking a zip's
     * EBOOT.PBP folder, or the hub's zip of a served folder) and returns that folder. Runs on the caller's thread.
     */
    public File installHomebrew(Item i, File gameRoot) throws IOException {
        LibraryEntry e = i.entry; String folder = i.installFolder != null ? i.installFolder : safeFolder(LibraryEntry.stem(e.fileName));
        File target = new File(gameRoot, folder); File cache = cacheDir != null ? cacheDir : gameRoot; cache.mkdirs();
        File tmp = File.createTempFile("pxp2-hb", ".part", cache);
        try {
            long n = download(e.downloadUrl(), tmp);
            note("library: downloaded " + e.fileName + " (" + n + " bytes)");
            String lower = e.fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pbp") && !e.isDir) {
                target.mkdirs(); Files.move(tmp.toPath(), new File(target, "EBOOT.PBP").toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                // A zip (from the hub's HOMEBREW folder, or the hub's zip of a folder entry): unpack the EBOOT.PBP's folder.
                String eboot = i.zipEboot;
                if (eboot == null) try (ZipFile z = new ZipFile(tmp)) {
                    for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                        String name = en.nextElement().getName().replace('\\', '/');
                        if (name.toUpperCase(Locale.ROOT).endsWith("EBOOT.PBP") && (eboot == null || name.split("/").length < eboot.split("/").length)) eboot = name;
                    }
                }
                if (eboot == null) throw new IOException("The package has no EBOOT.PBP");
                String prefix = eboot.contains("/") ? eboot.substring(0, eboot.lastIndexOf('/') + 1) : "";
                unzip(tmp, prefix, target);
            }
        } finally { tmp.delete(); }
        if (!new File(target, "EBOOT.PBP").isFile()) throw new IOException("EBOOT.PBP missing after install");
        synchronized (this) { visible = null; }
        return target;
    }
    private static long download(String url, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(20000); c.setRequestProperty("User-Agent", "PXP2");
        try {
            int code = c.getResponseCode(); if (code != 200) throw new IOException("HTTP " + code + " from the hub");
            long n = 0;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) { byte[] buf = new byte[65536]; int r; while ((r = in.read(buf)) > 0) { o.write(buf, 0, r); n += r; } }
            return n;
        } finally { c.disconnect(); }
    }
    /** Extracts the zip entries under {@code prefix} into {@code target}; entries escaping the target are refused. */
    static void unzip(File zip, String prefix, File target) throws IOException {
        String root = target.getCanonicalPath() + File.separator;
        try (ZipFile z = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry ze = en.nextElement(); String name = ze.getName().replace('\\', '/');
                if (!name.startsWith(prefix) || ze.isDirectory()) continue;
                File f = new File(target, name.substring(prefix.length()));
                if (!f.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe path in zip: " + name);
                f.getParentFile().mkdirs();
                try (InputStream in = z.getInputStream(ze); OutputStream o = new FileOutputStream(f)) { byte[] buf = new byte[65536]; int r; while ((r = in.read(buf)) > 0) o.write(buf, 0, r); }
            }
        }
    }

    // ---- host helpers ---------------------------------------------------------------------------------------------
    /** The *.iso files of a folder (e.g. PXP/ISO), sorted by name. */
    public static List<LibraryEntry> localIsos(File dir) {
        List<LibraryEntry> out = new ArrayList<>(); File[] f = dir == null ? null : dir.listFiles(x -> x.isFile() && x.getName().toLowerCase(Locale.ROOT).endsWith(".iso"));
        if (f != null) { Arrays.sort(f, (a, b) -> a.getName().compareToIgnoreCase(b.getName())); for (File x : f) out.add(LibraryEntry.localIso(x)); }
        return out;
    }
    /** Entries from a hub's /api/catalog JSON: ISO games (streamed) and homebrew (.zip/.pbp/folders). UMD Video is skipped. */
    public static List<LibraryEntry> parseCatalog(String hubUrl, String json) {
        List<LibraryEntry> out = new ArrayList<>();
        if (hubUrl == null || json == null || json.isEmpty()) return out;
        Object root; try { root = new Json(json).value(); } catch (RuntimeException e) { return out; }
        if (!(root instanceof Map)) return out;
        Map<?, ?> m = (Map<?, ?>)root;
        for (Object o : list(m.get("games"))) {
            if (!(o instanceof Map)) continue; Map<?, ?> g = (Map<?, ?>)o; String f = str(g.get("filename"));
            if (f != null && f.toLowerCase(Locale.ROOT).endsWith(".iso")) out.add(LibraryEntry.serverIso(hubUrl, "games", f, num(g.get("size")), str(g.get("name"))));
        }
        for (Object o : list(m.get("homebrew"))) {
            if (!(o instanceof Map)) continue; Map<?, ?> h = (Map<?, ?>)o; String f = str(h.get("filename")); if (f == null) continue;
            boolean dir = Boolean.TRUE.equals(h.get("is_dir")); String lower = f.toLowerCase(Locale.ROOT);
            if (dir || lower.endsWith(".zip") || lower.endsWith(".pbp")) out.add(LibraryEntry.serverHomebrew(hubUrl, f, num(h.get("size")), dir, str(h.get("name"))));
        }
        return out;
    }
    private static List<?> list(Object o) { return o instanceof List ? (List<?>)o : Collections.emptyList(); }
    private static String str(Object o) { return o instanceof String ? (String)o : null; }
    private static long num(Object o) { return o instanceof Number ? ((Number)o).longValue() : -1; }
    /** Minimal JSON reader (objects, arrays, strings, numbers, booleans, null) for the hub catalog. */
    static final class Json {
        private final String s; private int p;
        Json(String s) { this.s = s; }
        Object value() {
            ws(); if (p >= s.length()) throw new IllegalArgumentException("json: end");
            char c = s.charAt(p);
            if (c == '{') { p++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(p) == '}') { p++; return m; }
                while (true) { ws(); String k = string(); ws(); expect(':'); m.put(k, value()); ws(); if (s.charAt(p) == ',') { p++; continue; } expect('}'); return m; } }
            if (c == '[') { p++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(p) == ']') { p++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(p) == ',') { p++; continue; } expect(']'); return l; } }
            if (c == '"') return string();
            if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; } if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; } if (s.startsWith("null", p)) { p += 4; return null; }
            int st = p; while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
            if (st == p) throw new IllegalArgumentException("json: unexpected " + c);
            String n = s.substring(st, p); return n.contains(".") || n.contains("e") || n.contains("E") ? (Object)Double.parseDouble(n) : (Object)Long.parseLong(n);
        }
        private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
        private void expect(char c) { if (p >= s.length() || s.charAt(p) != c) throw new IllegalArgumentException("json: expected " + c); p++; }
        private String string() {
            expect('"'); StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(p++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(p++);
                switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break; case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char)Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; break; default: b.append(e); }
            }
        }
    }
    static String sha1(String s) {
        try { StringBuilder b = new StringBuilder(); for (byte x : MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))) b.append(String.format(Locale.ROOT, "%02x", x)); return b.toString(); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
