package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * SEPLUGINS loader: reads the CFW plugin lists from the Memory Stick and loads/starts the enabled plugin PRXs at the
 * boot phase a CFW uses. See psp.md "SEPLUGINS" for the design and the boot order.
 *
 * <p><b>Configuration</b> (paths case-insensitive, on ms0:; ef0: paths are read from ms0:):
 * <ul>
 * <li>{@code SEPLUGINS/plugins.txt} (ARK style): {@code mode, path, on|off}. mode is vsh, game, pops, always/all,
 *     umd (disc games), homebrew (Memory Stick games) or a game ID such as ULUS10081.</li>
 * <li>{@code SEPLUGINS/vsh.txt}, {@code game.txt}, {@code pops.txt} (PRO/ME style): {@code path [1|0]}. A line without
 *     a flag counts as enabled.</li>
 * </ul>
 * '#' and ';' start comments. plugins.txt entries come first, then the legacy file of the mode; duplicates load once.
 *
 * <p><b>Boot phase.</b> VSH: after the VSH system libraries (paf, common_gui, common_util, ...) have run module_start
 * and immediately before vshmain's module_start. GAME: after the GAME-mode system modules (HLE) and before the game
 * executable is loaded, so a plugin's start-module handler sees the game module start (the order PRO/ARK use: plugins
 * are started before init loads the game). POPS mode does not exist in PXP2, so pops entries are never loaded.
 *
 * <p><b>Memory.</b> Kernel plugins (module attribute 0x1000) load into kernel partition 1; user plugins into user
 * partition 2, allocated from the top so the game's link address (0x08804000 or a static ELF's range) stays free.
 *
 * <p><b>Failure isolation.</b> A plugin that is missing, cannot be loaded, lacks imports, faults or does not return
 * from module_start is logged and skipped; boot always continues.
 */
public final class Plugins {
    /** One configured plugin line. */
    public static final class Entry {
        public final String mode, path; public final boolean enabled; public final File source; public final int line;
        Entry(String mode, String path, boolean enabled, File source, int line) { this.mode = mode; this.path = path; this.enabled = enabled; this.source = source; this.line = line; }
        public String toString() { return mode + ", " + path + ", " + (enabled ? "on" : "off") + " (" + (source == null ? "?" : source.getName()) + ":" + line + ")"; }
    }
    /** What happened to one plugin at boot. */
    public static final class Result {
        public final String path; public String status; public int uid, base; public boolean kernel, started; public int exitStatus;
        Result(String path) { this.path = path; }
        public String toString() { return path + ": " + status; }
    }

    /** Instructions a plugin's module_start may run before boot continues without waiting for it. */
    public static long startBudget = 200_000_000L;
    private final Kernel k; private final HleSystemCtrl sctrl; private final File ms0;
    public final List<Result> results = new ArrayList<>();

    public Plugins(Kernel k, HleSystemCtrl sctrl, File ms0) {
        this.k = k; this.sctrl = sctrl; this.ms0 = ms0;
        k.faultHandler = this::onFault;
    }

    // ---- configuration files -----------------------------------------------------------------------------
    /** Finds a file under {@code dir} ignoring case in every path component (PSP FAT semantics); null when absent. */
    public static File findIgnoreCase(File dir, String relative) {
        File f = dir;
        for (String part : relative.replace('\\', '/').split("/")) {
            if (part.isEmpty()) continue;
            File exact = new File(f, part);
            if (!exact.exists()) { String[] names = f.list(); exact = null; if (names != null) for (String n : names) if (n.equalsIgnoreCase(part)) { exact = new File(f, n); break; } }
            if (exact == null) return null; f = exact;
        }
        return f;
    }
    private static List<String> lines(File f) {
        if (f == null || !f.isFile()) return new ArrayList<>();
        try { return new ArrayList<>(Arrays.asList(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\r?\n", -1))); }
        catch (IOException e) { return new ArrayList<>(); }
    }
    private static String stripComment(String line) {
        String s = line.replace(String.valueOf((char)0xfeff), ""); int c = s.indexOf('#'); if (c >= 0) s = s.substring(0, c);
        s = s.trim(); return s.startsWith(";") || s.startsWith("//") ? "" : s;
    }
    private static boolean flag(String v) {
        v = v.trim().toLowerCase(Locale.ROOT); return v.equals("on") || v.equals("1") || v.equals("enabled") || v.equals("true") || v.equals("yes");
    }
    /** All configured plugins, plugins.txt first, then vsh.txt, game.txt and pops.txt. */
    public static List<Entry> readConfig(File ms0) {
        List<Entry> out = new ArrayList<>();
        File ark = findIgnoreCase(ms0, "SEPLUGINS/plugins.txt"); List<String> l = lines(ark);
        for (int i = 0; i < l.size(); i++) {
            String s = stripComment(l.get(i)); if (s.isEmpty()) continue;
            String[] f = s.split(",");
            if (f.length < 2) continue;
            String path = f[1].trim(); boolean on = f.length < 3 || flag(f[2]);
            out.add(new Entry(f[0].trim().toLowerCase(Locale.ROOT), path, on, ark, i + 1));
        }
        for (String mode : new String[]{"vsh", "game", "pops"}) {
            File legacy = findIgnoreCase(ms0, "SEPLUGINS/" + mode + ".txt"); l = lines(legacy);
            for (int i = 0; i < l.size(); i++) {
                String s = stripComment(l.get(i)); if (s.isEmpty()) continue;
                // "path flag": the path itself may contain spaces, so the flag is the last token when it is one.
                int sp = s.lastIndexOf(' '), tab = s.lastIndexOf('\t'); int cut = Math.max(sp, tab);
                String path = s, flag = null;
                if (cut > 0) { String last = s.substring(cut + 1).trim().toLowerCase(Locale.ROOT); if (last.matches("[01]|on|off|enabled|disabled|true|false")) { path = s.substring(0, cut).trim(); flag = last; } }
                out.add(new Entry(mode, path, flag == null || flag(flag), legacy, i + 1));
            }
        }
        return out;
    }
    /** Whether an entry applies to the given boot: VSH, GAME (disc or Memory Stick, with its disc ID) or POPS. */
    static boolean applies(String entryMode, HleSystemCtrl.Mode mode, boolean fromDisc, String discId) {
        switch (entryMode) {
            case "always": case "all": return true;
            case "vsh": return mode == HleSystemCtrl.Mode.VSH;
            case "game": return mode == HleSystemCtrl.Mode.GAME;
            case "pops": return mode == HleSystemCtrl.Mode.POPS;
            case "umd": return mode == HleSystemCtrl.Mode.GAME && fromDisc;
            case "homebrew": return mode == HleSystemCtrl.Mode.GAME && !fromDisc;
            default: // a comma-free game ID list such as "ULUS10081" or "ULUS10081 ULES00001"
                if (mode != HleSystemCtrl.Mode.GAME || discId == null) return false;
                for (String id : entryMode.split("[\\s|]+")) if (id.equalsIgnoreCase(discId)) return true;
                return false;
        }
    }
    private static String normalise(String path) {
        String p = path.trim().replace('\\', '/');
        if (p.toLowerCase(Locale.ROOT).startsWith("ef0:")) p = "ms0:" + p.substring(4);
        if (!p.contains(":")) p = "ms0:" + (p.startsWith("/") ? "" : "/") + p;
        return p;
    }

    // ---- editing (Android "Plugins" list) ----------------------------------------------------------------
    /** PRX files found in ms0:/SEPLUGINS (two levels deep), as guest paths "ms0:/SEPLUGINS/...". */
    public static List<String> discover(File ms0) {
        List<String> out = new ArrayList<>(); File dir = findIgnoreCase(ms0, "SEPLUGINS");
        if (dir == null) return out;
        walk(dir, "ms0:/" + dir.getName(), 0, out); Collections.sort(out, String.CASE_INSENSITIVE_ORDER); return out;
    }
    private static void walk(File dir, String guest, int depth, List<String> out) {
        File[] files = dir.listFiles(); if (files == null) return;
        for (File f : files) {
            if (f.isDirectory() && depth < 2) walk(f, guest + "/" + f.getName(), depth + 1, out);
            else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".prx")) out.add(guest + "/" + f.getName());
        }
    }
    /** True when the plugin is enabled for {@code mode} ("vsh" or "game") in any configuration file. */
    public static boolean isEnabled(File ms0, String mode, String path) {
        for (Entry e : readConfig(ms0)) if (e.enabled && e.mode.equals(mode) && samePath(e.path, path)) return true;
        return false;
    }
    private static boolean samePath(String a, String b) { return normalise(a).equalsIgnoreCase(normalise(b)); }
    /**
     * Enables or disables a plugin for {@code mode} ("vsh", "game" or "pops"). An existing line for it (in plugins.txt
     * or the mode's legacy file) has its flag rewritten in place, keeping that file's format; otherwise a line
     * "path 1" is appended to SEPLUGINS/&lt;mode&gt;.txt. Other lines are left untouched.
     */
    public static void setEnabled(File ms0, String mode, String path, boolean enabled) throws IOException {
        boolean found = false;
        for (Entry e : readConfig(ms0)) {
            if (!e.mode.equals(mode) || !samePath(e.path, path)) continue;
            List<String> l = lines(e.source); String old = l.get(e.line - 1), line;
            if (e.source.getName().equalsIgnoreCase("plugins.txt")) {
                String[] f = old.split(",", -1);
                line = f.length >= 3 ? f[0] + "," + f[1] + ", " + (enabled ? "on" : "off") : old + ", " + (enabled ? "on" : "off");
            } else line = e.path + " " + (enabled ? "1" : "0");
            l.set(e.line - 1, line); write(e.source, l); found = true;
        }
        if (found || !enabled) return;
        File dir = findIgnoreCase(ms0, "SEPLUGINS"); if (dir == null) { dir = new File(ms0, "SEPLUGINS"); dir.mkdirs(); }
        File legacy = findIgnoreCase(dir, mode + ".txt"); if (legacy == null) legacy = new File(dir, mode + ".txt");
        List<String> l = lines(legacy); while (!l.isEmpty() && l.get(l.size() - 1).trim().isEmpty()) l.remove(l.size() - 1);
        l.add(path + " 1"); l.add(""); write(legacy, l);
    }
    private static void write(File f, List<String> lines) throws IOException {
        String nl = "\r\n"; // CFW tools write CRLF; the parsers accept both
        Files.write(f.toPath(), String.join(nl, lines).getBytes(StandardCharsets.UTF_8));
    }

    // ---- loading ---------------------------------------------------------------------------------------------
    /**
     * Loads and starts the plugins enabled for this boot. Runs the machine while each module_start executes (at most
     * {@link #startBudget} instructions each). Never throws: problems are logged per plugin in {@link #results}.
     */
    public void loadAndStart(HleSystemCtrl.Mode mode, String gamePath, String discId) {
        sctrl.mode = mode; sctrl.gamePath = gamePath;
        boolean fromDisc = gamePath != null && gamePath.toLowerCase(Locale.ROOT).startsWith("disc0:");
        List<Entry> config;
        try { config = readConfig(ms0); } catch (RuntimeException e) { k.log("SEPLUGINS: configuration unreadable: " + e); return; }
        Set<String> seen = new HashSet<>(); int n = 0;
        for (Entry e : config) {
            if (!e.enabled || !applies(e.mode, mode, fromDisc, discId)) continue;
            String path = normalise(e.path); if (!seen.add(path.toLowerCase(Locale.ROOT))) continue;
            n++;
            Result r = new Result(path); results.add(r);
            try { load(r); } catch (RuntimeException ex) { r.status = "failed: " + ex; }
            k.log("SEPLUGINS " + mode + ": " + r);
        }
        if (n == 0) k.log("SEPLUGINS " + mode + ": no enabled plugins");
    }
    private void load(Result r) {
        byte[] data;
        try { data = k.io.readFile(r.path); }
        catch (FileNotFoundException e) { r.status = "not loaded: file not found"; return; }
        catch (IOException e) { r.status = "not loaded: " + e.getMessage(); return; }
        int attributes = PrxLoader.moduleAttributes(data);
        r.kernel = attributes >= 0 && (attributes & 0x1000) != 0;
        HleModules.LoadedModule mod;
        sctrl.linkingPlugin = true;
        try { mod = k.modules.load(k.io.vfs.absolute(r.path), data, r.kernel ? 1 : 2, r.kernel ? SysMem.LOW : SysMem.HIGH); }
        catch (IOException | RuntimeException e) { r.status = "not loaded: " + e.getMessage(); return; }
        finally { sctrl.linkingPlugin = false; }
        sctrl.pluginModules.add(mod.uid); r.uid = mod.uid; r.base = mod.prx.base;
        String where = String.format(Locale.ROOT, "%s at %08x (%s, %d imports)", mod.prx.name, mod.prx.base, r.kernel ? "kernel" : "user", mod.prx.imports.size());
        byte[] arg = (r.path + "\0").getBytes(StandardCharsets.UTF_8);
        int argp = k.sysmem.allocate(r.kernel ? 1 : 2, "plugin args", SysMem.HIGH, arg.length, 0);
        if (argp != 0) k.memory.write(argp, arg);
        Kernel.GuestThread t;
        try { t = k.modules.start(mod, argp == 0 ? 0 : arg.length, argp); }
        catch (Kernel.Stop e) { r.status = "loaded " + where + "; not started: " + e.getMessage(); if (argp != 0) k.sysmem.free(argp); return; }
        if (t == null) { r.status = "loaded " + where + "; no module_start"; if (argp != 0) k.sysmem.free(argp); return; }
        String result = k.run(startBudget, () -> t.state == Kernel.State.DORMANT || t.state == Kernel.State.DEAD);
        if (argp != 0) k.sysmem.free(argp);
        if (t.state == Kernel.State.DEAD && failed.contains(t.uid)) { r.status = "loaded " + where + "; module_start faulted: " + faults.get(t.uid); return; }
        if (t.state == Kernel.State.DORMANT || t.state == Kernel.State.DEAD) {
            r.started = true; r.exitStatus = t.exitStatus;
            if (t.state == Kernel.State.DORMANT) k.deleteThread(t);
            if (r.exitStatus == 1) { unload(mod); r.status = "started " + where + "; module_start returned 1 (no resident), unloaded"; return; }
            r.status = "started " + where + String.format(Locale.ROOT, "; module_start returned %08x", r.exitStatus);
            return;
        }
        r.status = "loaded " + where + "; module_start still running (" + result.split("\n")[0] + "), boot continues";
    }
    private void unload(HleModules.LoadedModule mod) {
        k.modules.modules.remove(mod); k.sysmem.free(mod.block); k.deleteUid(mod.uid); sctrl.pluginModules.remove(mod.uid);
    }

    // ---- fault isolation -------------------------------------------------------------------------------------
    private final Set<Integer> failed = new HashSet<>(); private final Map<Integer, String> faults = new HashMap<>();
    /** A thread belongs to a plugin when it is a plugin's module_start thread or its entry lies in plugin code. */
    private boolean pluginThread(Kernel.GuestThread t) {
        if (sctrl.pluginModules.contains(t.module)) return true;
        HleModules.LoadedModule owner = k.modules.byAddress(t.entry);
        return owner != null && sctrl.pluginModules.contains(owner.uid);
    }
    private boolean onFault(Kernel.GuestThread t, String message) {
        if (!pluginThread(t)) {
            HleModules.LoadedModule at = k.modules.byAddress(k.cpu.pc());
            if (at != null && sctrl.pluginModules.contains(at.uid)) k.log("SEPLUGINS: fault inside plugin " + at.prx.name + " code on system thread " + t.name + " (cannot be isolated): " + message);
            return false;
        }
        k.log(String.format(Locale.ROOT, "SEPLUGINS: plugin thread %s faulted, thread ended, emulation continues: %s (PC=%08x)", t.name, message.split("\n")[0], k.cpu.pc()));
        failed.add(t.uid); faults.put(t.uid, message.split("\n")[0]);
        k.exitThread(t, 0x80020001, true);
        return true;
    }
}
