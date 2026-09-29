package org.pxp2.core;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Original in-memory PSP system registry (normally flash1:/registry). Seeded with first-boot
 * settings for an English-language PSP with X as the confirm button. Missing keys are reported
 * to the guest as not found and logged by the caller.
 */
public final class Registry {
    public static final int DIRECTORY = 1, INT = 2, STRING = 3, BINARY = 4; // PSP registry key types
    public static final int NOT_FOUND = 0x80082718;
    public static final class Value {
        public final int type; public final byte[] data;
        Value(int type, byte[] data) { this.type = type; this.data = data; }
        public int size() { return data.length; }
    }
    private final Map<String, Map<String, Value>> categories = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public Registry() {
        category("/CONFIG/SYSTEM").putAll(values("owner_name", "PXP2", "umd_autoboot", 0, "usb_auto_connect", 1, "usb_charge", 0, "exh_mode", 0, "slide_welcome", 0, "slide_count", 0, "backlight_brightness", 3));
        category("/CONFIG/SYSTEM/XMB").putAll(values("language", 1, "button_assign", 1, "theme_type", 0, "check_boot_update", 0));
        category("/CONFIG/SYSTEM/XMB/THEME").putAll(values("color_mode", 0, "wallpaper_mode", 0, "custom_theme_mode", 0, "system_color", 0));
        category("/CONFIG/SYSTEM/POWER_SAVING").putAll(values("suspend_interval", 0, "backlight_off_interval", 0, "wlan_mode", 0, "active_backlight_mode", 1));
        category("/CONFIG/SYSTEM/SOUND").putAll(values("dynamic_normalizer", 0, "operation_sound_mode", 1, "avls", 0, "equalizer_mode", 0, "main_volume", 15, "mute", 0));
        category("/CONFIG/SYSTEM/CHARACTER_SET").putAll(values("oem", 5, "ansi", 0x13));
        category("/CONFIG/SYSTEM/LOCK").putAll(values("parental_level", 0, "browser_start", 0, "password", "0000"));
        category("/CONFIG/DATE").putAll(values("date_format", 2, "time_format", 0, "time_zone_offset", 0, "time_zone_area", "united_kingdom", "summer_time", 0));
        category("/CONFIG/DISPLAY").putAll(values("color_space_mode", 0, "screensaver_start_time", 0, "brightness", 3));
        category("/CONFIG/NETWORK/GO_MESSENGER").putAll(values("auth_name", "", "auth_key", "", "gm_mode", 0));
        category("/CONFIG/NETWORK/ADHOC").putAll(values("ssid_prefix", "PSP", "channel", 0));
        category("/CONFIG/NETWORK/INFRASTRUCTURE").putAll(values("latest_id", 0, "eap_md5", 0, "auto_setting", 0, "wifisvc_setting", 0));
        category("/CONFIG/NP").putAll(values("env", "np", "account_id", new byte[16], "login_id", "", "password", "", "auto_sign_in_enable", 0, "nav_only", 0));
        category("/CONFIG/PREMO").putAll(values("ps3_mac", new byte[6], "ps3_name", "", "guide_page", 0, "response", 0, "custom_video_buffer", 0, "custom_video_bitrate", 0, "setting_internet", 0, "button_assign", 0));
        category("/CONFIG/MUSIC").putAll(values("visualizer_mode", 0, "track_info_mode", 0, "wma_play", 0));
        category("/CONFIG/PHOTO").putAll(values("slideshow_speed", 0));
        category("/CONFIG/VIDEO").putAll(values("lr_button_enable", 1, "list_play_mode", 0, "title_display_mode", 0, "output_ext_menu", 0, "output_ext_func", 0, "sound_language", new byte[]{'e', 'n'}, "subtitle_language", new byte[]{'e', 'n'}, "menu_language", new byte[]{'e', 'n'}, "appended_volume", 0));
        category("/CONFIG/BROWSER").putAll(values("home_uri", "", "cookie_mode", 0, "proxy_mode", 0, "proxy_address", "", "proxy_port", 0, "picture", 1, "animation", 0, "javascript", 1, "cache_size", 512, "char_size", 1, "disp_mode", 0, "connect_mode", 0, "flash_activated", 0, "flash_play", 0));
        for (int i = 0; i < 10; i++) category("/CONFIG/ALARM").putAll(values("alarm_" + i + "_time", -1, "alarm_" + i + "_property", 0));
        category("/CONFIG/CAMERA").putAll(values("still_size", 0, "movie_size", 0, "still_quality", 0, "movie_quality", 0, "movie_fps", 0, "white_balance", 0, "exposure_bias", 0, "shutter_sound_mode", 0, "file_folder", 0, "file_number", 0, "msid", ""));
        category("/CONFIG/GAME").putAll(values("adhoc_matching_port", 0));
        category("/CONFIG/RSS").putAll(values("download_items", 5));
        category("/CONFIG/ONESEG");
        category("/CONFIG/LFTV");
        category("/DATA/COUNT").putAll(values("boot_count", 1, "game_exec_count", 0, "slide_count", 0, "usb_connect_count", 0, "wifi_connect_count", 0, "psn_access_count", 0));
        category("/DATA/FONT").putAll(values("path_name", "flash0:/font", "num_fonts", 18));
        category("/SYSPROFILE").putAll(values("sound_reduction", 0));
        category("/SYSPROFILE/RESOLUTION").putAll(values("horizontal", 8210, "vertical", 8210));
        category("/REGISTRY").putAll(values("category_version", 0x66));
    }
    /**
     * Fills /DATA/FONT/PROPERTY/INFOn from the installed PGF fonts (jpn0, ltn0-15, kr0), reading sizes,
     * resolutions and names from each font header as the first-boot font registration does.
     */
    public void addFonts(java.io.File directory) {
        List<String> names = new ArrayList<>(); names.add("jpn0.pgf"); for (int i = 0; i < 16; i++) names.add("ltn" + i + ".pgf"); names.add("kr0.pgf");
        int index = 0;
        for (String n : names) {
            java.io.File f = new java.io.File(directory, n); if (!f.isFile()) continue;
            byte[] h = new byte[256];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { if (in.read(h) != 256) continue; } catch (java.io.IOException e) { continue; }
            if (h[4] != 'P' || h[5] != 'G' || h[6] != 'F') continue;
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            String fontName = cString(h, 53, 64), fontType = cString(h, 117, 64);
            int family = fontName.contains("Matisse") || fontName.contains("Serif") && !fontName.contains("Sans") ? 2 : 1;
            int style = fontType.equalsIgnoreCase("Bold Italic") ? 6 : fontType.equalsIgnoreCase("Bold") ? 5 : fontType.equalsIgnoreCase("Italic") ? 2 : 1;
            int language = n.startsWith("jpn") ? 1 : n.startsWith("kr") ? 3 : 2;
            category("/DATA/FONT/PROPERTY/INFO" + index).putAll(values("h_size", b.getInt(36), "v_size", b.getInt(40), "h_resolution", b.getInt(44),
                "v_resolution", b.getInt(48), "extra_attributes", 0, "weight", 0, "family_code", family, "style", style, "sub_style", 0,
                "language_code", language, "region_code", 0, "country_code", 0, "file_name", n, "font_name", fontName, "expire_date", 0, "shadow_option", 0));
            index++;
        }
        category("/DATA/FONT").putAll(values("path_name", "flash0:/font", "num_fonts", index));
    }
    private static String cString(byte[] b, int at, int max) { int end = at; while (end < at + max && b[end] != 0) end++; return new String(b, at, end - at, StandardCharsets.ISO_8859_1); }
    private static Map<String, Value> values(Object... pairs) {
        Map<String, Value> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            if (v instanceof Integer) { int x = (Integer)v; out.put((String)pairs[i], new Value(INT, new byte[]{(byte)x, (byte)(x >>> 8), (byte)(x >>> 16), (byte)(x >>> 24)})); }
            else if (v instanceof String) { byte[] s = ((String)v).getBytes(StandardCharsets.UTF_8); out.put((String)pairs[i], new Value(STRING, Arrays.copyOf(s, s.length + 1))); }
            else out.put((String)pairs[i], new Value(BINARY, (byte[])v));
        }
        return out;
    }
    private Map<String, Value> category(String path) {
        path = normalise(path);
        // Create parents so every prefix category can be opened.
        for (int i = path.indexOf('/', 1); i > 0; i = path.indexOf('/', i + 1)) categories.computeIfAbsent(path.substring(0, i), x -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        return categories.computeIfAbsent(path, x -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
    }
    public static String normalise(String path) {
        if (path == null) return "/";
        String p = path.replace('\\', '/'); if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
    public boolean dirty;
    /** Saves the registry (PXP2 format, not Sony's .dreg) atomically. */
    public void save(java.io.File file) throws java.io.IOException {
        java.io.File temp = new java.io.File(file.getPath() + ".tmp"); file.getParentFile().mkdirs();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(temp)))) {
            out.writeInt(0x50585252); out.writeInt(categories.size());
            for (Map.Entry<String, Map<String, Value>> c : categories.entrySet()) {
                out.writeUTF(c.getKey()); out.writeInt(c.getValue().size());
                for (Map.Entry<String, Value> k : c.getValue().entrySet()) { out.writeUTF(k.getKey()); out.writeInt(k.getValue().type); out.writeInt(k.getValue().data.length); out.write(k.getValue().data); }
            }
        }
        if (!temp.renameTo(file)) { file.delete(); if (!temp.renameTo(file)) throw new java.io.IOException("Cannot save registry"); }
        dirty = false;
    }
    /** Overlays values saved by {@link #save} on top of the defaults. */
    public void load(java.io.File file) throws java.io.IOException {
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(file)))) {
            if (in.readInt() != 0x50585252) throw new java.io.IOException("Not a PXP2 registry");
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String cat = in.readUTF(); int keys = in.readInt(); Map<String, Value> c = category(cat);
                for (int j = 0; j < keys; j++) { String name = in.readUTF(); int type = in.readInt(), len = in.readInt(); if (len < 0 || len > 65536) throw new java.io.IOException("Corrupt registry"); byte[] d = new byte[len]; in.readFully(d);
                    Value def = c.get(name); if (def != null && def.type != type) continue; // corrected default types win over stale saves
                    c.put(name, new Value(type, d)); }
            }
        }
    }
    public interface ChangeListener {
        void onRegistryChange(String category, String key, Value value);
    }
    private final List<ChangeListener> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void addChangeListener(ChangeListener listener) { if (listener != null) changeListeners.add(listener); }
    public void removeChangeListener(ChangeListener listener) { changeListeners.remove(listener); }

    public boolean hasCategory(String path) { return categories.containsKey(normalise(path)); }
    public void createCategory(String path) { category(path); }
    public Value get(String category, String key) { Map<String, Value> c = categories.get(normalise(category)); return c == null ? null : c.get(key); }
    public void set(String category, String key, int type, byte[] data) {
        Value val = new Value(type, data);
        category(category).put(key, val);
        dirty = true;
        for (ChangeListener l : changeListeners) {
            try { l.onRegistryChange(normalise(category), key, val); } catch (Throwable ignored) {}
        }
    }
    public List<String> keys(String category) {
        String cat = normalise(category); List<String> out = new ArrayList<>();
        Map<String, Value> c = categories.get(cat); if (c != null) out.addAll(c.keySet());
        for (String other : categories.keySet()) if (other.length() > cat.length() + 1 && other.regionMatches(true, 0, cat.equals("/") ? "" : cat, 0, cat.equals("/") ? 0 : cat.length())
            && other.charAt(cat.equals("/") ? 0 : cat.length()) == '/' && other.indexOf('/', cat.equals("/") ? 1 : cat.length() + 1) < 0) out.add(other.substring(other.lastIndexOf('/') + 1));
        return out;
    }
}
