package org.pxp2.core;

import java.io.*;
import java.util.*;

/**
 * Maps PSP device paths ("flash0:/vsh/module/paf.prx", "ms0:/PSP/GAME") onto host directories.
 * Lookups are case-insensitive like the PSP's FAT devices. Paths cannot escape a device root.
 */
public final class Vfs {
    private final Map<String, File> devices = new LinkedHashMap<>();
    private final Set<String> readOnly = new HashSet<>();
    public String cwd = "ms0:/";

    public void mount(String device, File directory, boolean writable) {
        devices.put(device.toLowerCase(Locale.ROOT), directory);
        if (!writable) readOnly.add(device.toLowerCase(Locale.ROOT));
    }
    public boolean hasDevice(String device) { return devices.containsKey(device.toLowerCase(Locale.ROOT)); }
    public static final class Location { public final String device, path; public final File file; public final boolean writable; Location(String d, String p, File f, boolean w) { device = d; path = p; file = f; writable = w; } }

    /** Normalises a guest path to "device:/a/b" form, resolving relative paths against {@link #cwd}. */
    public String absolute(String path) {
        if (path == null) return null;
        int colon = path.indexOf(':');
        String device, rest;
        if (colon > 0 && path.lastIndexOf('/', colon) < 0) { device = path.substring(0, colon); rest = path.substring(colon + 1); }
        else { int c = cwd.indexOf(':'); device = cwd.substring(0, c); rest = cwd.substring(c + 1) + "/" + path; }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : rest.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); continue; }
            parts.addLast(part);
        }
        // Device names like "flash0" or "disc0"; numeric suffixes are part of the name. Strip "flashfat0" style aliases.
        String d = device.toLowerCase(Locale.ROOT);
        if (d.equals("flashfat0")) d = "flash0"; if (d.equals("flashfat1")) d = "flash1"; if (d.equals("fatms0") || d.equals("fatms")) d = "ms0";
        if (d.equals("umd0") || d.equals("umd1") || d.equals("isofs0")) d = "disc0";
        return d + ":/" + String.join("/", parts);
    }
    public Location resolve(String guestPath) {
        String abs = absolute(guestPath); if (abs == null) return null;
        int colon = abs.indexOf(':'); String device = abs.substring(0, colon);
        File root = devices.get(device); if (root == null) return null;
        File file = root;
        for (String part : abs.substring(colon + 2).split("/")) {
            if (part.isEmpty()) continue;
            File exact = new File(file, part);
            if (!exact.exists()) {
                String[] names = file.list();
                if (names != null) for (String n : names) if (n.equalsIgnoreCase(part)) { exact = new File(file, n); break; }
            }
            file = exact;
        }
        return new Location(device, abs, file, !readOnly.contains(device));
    }
    public byte[] read(String guestPath) throws IOException {
        // CTF in-memory overlay: checked before the host filesystem so CTF themes shadow flash0.
        String abs = absolute(guestPath);
        if (abs != null) { byte[] ctf = ctfOverrides.get(abs.toLowerCase(Locale.ROOT)); if (ctf != null) return ctf; }
        Location l = resolve(guestPath);
        if (l == null || !l.file.isFile()) throw new FileNotFoundException(guestPath);
        if (l.file.length() > 64L * 1024 * 1024) throw new IOException("File too large: " + guestPath);
        return java.nio.file.Files.readAllBytes(l.file.toPath());
    }

    // ---- CTF in-memory overlay ----------------------------------------------------------------
    /** Absolute lower-case guest path → raw bytes from the CTF payload. */
    private final Map<String, byte[]> ctfOverrides = new HashMap<>();
    /**
     * Registers an in-memory file override for a CTF theme entry. The path must be an
     * absolute guest path (e.g. {@code "flash0:/vsh/resource/topmenu_icon.rco"}); subsequent
     * {@link #read} calls for that path return this data instead of the real flash0 file.
     */
    public void mountCtfEntry(String absGuestPath, byte[] data) {
        ctfOverrides.put(absGuestPath.toLowerCase(Locale.ROOT), data);
    }
    /** The CTF overlay bytes for a guest path (any spelling or case), or null when the path is not overridden. */
    public byte[] ctfOverride(String guestPath) {
        if (ctfOverrides.isEmpty()) return null;
        String abs = absolute(guestPath); return abs == null ? null : ctfOverrides.get(abs.toLowerCase(Locale.ROOT));
    }
    /** Removes all CTF overrides (e.g. on restart or theme change). */
    public void clearCtfOverrides() { ctfOverrides.clear(); }
    /** Returns the number of active CTF overrides. */
    public int ctfOverrideCount() { return ctfOverrides.size(); }
}
