package org.pxp2.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The XMB Settings column as registry edits, so a host UI can change the emulated PSP's system settings while the
 * XMB is not running. Every entry names the registry key(s) the 6.61 firmware reads and writes for it, with the
 * values the XMB itself writes; they were traced by changing each setting in the XMB under PXP2 (see psp.md,
 * "XMB settings \u2192 registry map"). Entries marked {@code verified = false} are inferred from firmware strings or
 * value patterns because the XMB path cannot be exercised under PXP2 yet.
 *
 * Edits go through {@link Registry}'s own serializer: {@link #load} the saved file, change it, {@link #save} it. The
 * XMB reads these keys when it starts, so changes take effect on the next XMB start.
 */
public final class XmbSettings {
    private XmbSettings() { }

    public static final String SYSTEM = "/CONFIG/SYSTEM", XMB = "/CONFIG/SYSTEM/XMB", THEME = "/CONFIG/SYSTEM/XMB/THEME",
        DATE = "/CONFIG/DATE", POWER = "/CONFIG/SYSTEM/POWER_SAVING", SOUND = "/CONFIG/SYSTEM/SOUND", LOCK = "/CONFIG/SYSTEM/LOCK",
        VIDEO = "/CONFIG/VIDEO", CHARSET = "/CONFIG/SYSTEM/CHARACTER_SET";

    /** One registry write: an int, a string (NUL-terminated) or a binary value. */
    public static final class Write {
        public final String category, key; public final int type; public final byte[] data;
        Write(String category, String key, int type, byte[] data) { this.category = category; this.key = key; this.type = type; this.data = data; }
        boolean matches(Registry r) { Registry.Value v = r.get(category, key); return v != null && Arrays.equals(trim(v), trimmed(data, type)); }
        public void apply(Registry r) { r.set(category, key, type, data.clone()); }
    }
    public static Write intValue(String category, String key, int v) { return new Write(category, key, Registry.INT, new byte[]{(byte)v, (byte)(v >>> 8), (byte)(v >>> 16), (byte)(v >>> 24)}); }
    public static Write stringValue(String category, String key, String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); return new Write(category, key, Registry.STRING, Arrays.copyOf(b, b.length + 1)); }
    public static Write binaryValue(String category, String key, byte[] b) { return new Write(category, key, Registry.BINARY, b.clone()); }
    private static byte[] trim(Registry.Value v) { return trimmed(v.data, v.type); }
    private static byte[] trimmed(byte[] d, int type) { if (type != Registry.STRING) return d; int n = 0; while (n < d.length && d[n] != 0) n++; return Arrays.copyOf(d, n); }

    /** A selectable value: its label and the writes that select it. The first {@code matchCount} writes identify it. */
    public static final class Choice {
        public final String label; public final Write[] writes; final int matchCount;
        Choice(String label, int matchCount, Write... writes) { this.label = label; this.writes = writes; this.matchCount = matchCount; }
        public boolean selected(Registry r) { for (int i = 0; i < matchCount; i++) if (!writes[i].matches(r)) return false; return true; }
        public void apply(Registry r) { for (Write w : writes) w.apply(r); }
    }
    public enum Kind { CHOICE, TEXT, PASSCODE, RANGE, INFO }
    /** A row of the XMB Settings column. */
    public static final class Setting {
        public final String label, category, key, note; public final Kind kind; public final List<Choice> choices; public final boolean verified;
        public final int min, max;
        Setting(String label, Kind kind, String category, String key, List<Choice> choices, int min, int max, boolean verified, String note) {
            this.label = label; this.kind = kind; this.category = category; this.key = key; this.choices = choices; this.min = min; this.max = max; this.verified = verified; this.note = note;
        }
        /** The choice the registry currently holds, or null (unknown value). */
        public Choice current(Registry r) { if (choices != null) for (Choice c : choices) if (c.selected(r)) return c; return null; }
        /** The current value as the XMB would show it. */
        public String display(Registry r) {
            switch (kind) {
                case CHOICE: { Choice c = current(r); if (c != null) return c.label; Registry.Value v = r.get(category, key); return v == null ? "Not set" : "Other (" + raw(v) + ")"; }
                case TEXT: return string(r, category, key, "");
                case PASSCODE: return "\u2022\u2022\u2022\u2022";
                case RANGE: return String.valueOf(intOf(r, category, key, min));
                default: return note == null ? "" : note;
            }
        }
    }
    /** A group of settings, as one item of the XMB Settings column. */
    public static final class Section {
        public final String title; public final List<Setting> settings;
        Section(String title, Setting... settings) { this.title = title; this.settings = new ArrayList<>(Arrays.asList(settings)); }
    }

    private static Setting choice(String label, String category, String key, boolean verified, Choice... choices) {
        return new Setting(label, Kind.CHOICE, category, key, Arrays.asList(choices), 0, 0, verified, null);
    }
    private static Setting ints(String label, String category, String key, boolean verified, Object... labelValue) {
        Choice[] c = new Choice[labelValue.length / 2];
        for (int i = 0; i < c.length; i++) c[i] = new Choice((String)labelValue[2 * i], 1, intValue(category, key, (Integer)labelValue[2 * i + 1]));
        return choice(label, category, key, verified, c);
    }
    private static Choice code(String label, String category, String key, String code) { return new Choice(label, 1, binaryValue(category, key, code.getBytes(StandardCharsets.ISO_8859_1))); }
    private static Setting videoLanguage(String label, String key, boolean original) {
        List<Choice> c = new ArrayList<>();
        if (original) c.add(code("Original Language", VIDEO, key, "00"));
        String[][] langs = {{"Danish", "da"}, {"German", "de"}, {"English", "en"}, {"Spanish", "es"}, {"Finnish", "fi"}, {"French", "fr"}, {"Italian", "it"},
            {"Japanese", "ja"}, {"Korean", "ko"}, {"Dutch", "nl"}, {"Norwegian", "no"}, {"Portuguese", "pt"}, {"Russian", "ru"}, {"Swedish", "sv"}, {"Chinese", "zh"}};
        for (String[] l : langs) c.add(code(l[0], VIDEO, key, l[1]));
        return new Setting(label, Kind.CHOICE, VIDEO, key, c, 0, 0, true, null);
    }

    /**
     * Time zones in the order the XMB lists them: registry area id, offset in minutes and the city the XMB shows.
     * Each selection writes both /CONFIG/DATE time_zone_area and time_zone_offset.
     */
    public static final Object[][] TIME_ZONES = {
        {"samoa", -660, "Samoa Islands"}, {"midway_islands", -660, "Midway Islands"}, {"united_states_hawaii", -600, "Hawaii"},
        {"united_states_alaska", -540, "Alaska"}, {"pacific_us_canada", -480, "Pacific Time (US, Canada)"}, {"mexico_tijuana", -480, "Tijuana"},
        {"mountain_us_canada", -420, "Mountain Time (US, Canada)"}, {"mexico_chihuahua", -420, "Chihuahua"}, {"sanjose_costa_rica", -360, "San Jose"},
        {"central_us_canada", -360, "Central Time (US, Canada)"}, {"mexico_mexico_city", -360, "Mexico City"}, {"quito_ecuador", -300, "Quito"},
        {"eastern_us_canada", -300, "Eastern Time (US, Canada)"}, {"panama", -300, "Panama City"}, {"bogota_colombia", -300, "Bogota"},
        {"lima_peru", -300, "Lima"}, {"venezuela", -240, "Caracas"}, {"chile_santiago", -240, "Santiago"}, {"canada_atlantic", -240, "Atlantic (Canada)"},
        {"puerto_rico", -240, "Puerto Rico"}, {"la_paz_bolivia", -240, "La Paz"}, {"canada_newfoundland", -210, "Newfoundland"},
        {"sao_paulo_brazil", -180, "Sao Paulo"}, {"buenos_aires_argentine", -180, "Buenos Aires"}, {"portugal_azores", -60, "Azores"},
        {"morocco", 0, "Casablanca"}, {"ireland", 0, "Dublin"}, {"portugal_lisbon", 0, "Lisbon"}, {"iceland", 0, "Reykjavik"},
        {"united_kingdom", 0, "London"}, {"netherlands", 60, "Amsterdam"}, {"algeria", 60, "Algiers"}, {"andorra", 60, "Andorra la Vella"},
        {"austria", 60, "Vienna"}, {"namibia", 60, "Windhoek"}, {"norway", 60, "Oslo"}, {"denmark", 60, "Copenhagen"}, {"croatia", 60, "Zagreb"},
        {"bosnia_and_herzegovina", 60, "Sarajevo"}, {"san_marino", 60, "San Marino"}, {"macedonia", 60, "Skopje"}, {"sweden", 60, "Stockholm"},
        {"tunisia", 60, "Tunis"}, {"albania", 60, "Tirana"}, {"france", 60, "Paris"}, {"malta", 60, "Valletta"}, {"liechtenstein", 60, "Vaduz"},
        {"hungary", 60, "Budapest"}, {"slovakia", 60, "Bratislava"}, {"czech_republic", 60, "Prague"}, {"belgium", 60, "Brussels"},
        {"yugoslavia", 60, "Belgrade"}, {"germany", 60, "Berlin"}, {"switzerland", 60, "Bern"}, {"spain_madrid", 60, "Madrid"}, {"monaco", 60, "Monaco"},
        {"slovenia", 60, "Ljubljana"}, {"luxembourg", 60, "Luxembourg"}, {"italy", 60, "Rome"}, {"poland", 60, "Warsaw"}, {"greece", 120, "Athens"},
        {"jordan", 120, "Amman"}, {"turkey", 120, "Istanbul"}, {"israel", 120, "Jerusalem"}, {"egypt", 120, "Cairo"}, {"ukraine", 120, "Kiev"},
        {"bulgaria", 120, "Sofia"}, {"syrian_arab_republic", 120, "Damascus"}, {"estonia", 120, "Tallinn"}, {"cyprus", 120, "Nicosia"},
        {"lithuania", 120, "Vilnius"}, {"romania", 120, "Bucharest"}, {"lebanon", 120, "Beirut"}, {"finland", 120, "Helsinki"}, {"belarus", 120, "Minsk"},
        {"south_africa", 120, "Johannesburg"}, {"latvia", 120, "Riga"}, {"kuwait", 180, "Kuwait City"}, {"iraq", 180, "Baghdad"}, {"bahrain", 180, "Manama"},
        {"russian_federation_moscow", 180, "Moscow"}, {"saudi_arabia", 180, "Riyadh"}, {"iran", 210, "Tehran"}, {"united_arab_emirates", 240, "Abu Dhabi"},
        {"armenia", 240, "Yerevan"}, {"georgia", 240, "Tbilisi"}, {"azerbaijan", 240, "Baku"}, {"oman", 240, "Muscat"}, {"afghanistan", 270, "Kabul"},
        {"pakistan", 300, "Karachi"}, {"uzbekistan", 300, "Tashkent"}, {"kyrgyzstan", 300, "Bishkek"}, {"india", 330, "Calcutta"}, {"nepal", 345, "Kathmandu"},
        {"astana_kazakstan", 360, "Astana"}, {"bangladesh", 360, "Dhaka"}, {"thailand", 420, "Bangkok"}, {"malaysia", 480, "Kuala Lumpur"},
        {"singapore", 480, "Singapore"}, {"taiwan_province_of_china", 480, "Taipei"}, {"australia_perth", 480, "Perth"}, {"china", 480, "Beijing"},
        {"hong_kong", 480, "Hong Kong"}, {"korea", 540, "Seoul"}, {"japan", 540, "Tokyo"}, {"australia_adelaide", 570, "Adelaide"},
        {"australia_sydney", 600, "Sydney"}, {"new_zealand", 720, "Wellington"}, {"fiji", 720, "Suva"},
    };
    private static String gmt(int minutes) { int a = Math.abs(minutes); return String.format(java.util.Locale.ROOT, "GMT%s%02d:%02d", minutes < 0 ? "-" : "+", a / 60, a % 60); }
    private static Setting timeZone() {
        List<Choice> c = new ArrayList<>();
        for (Object[] z : TIME_ZONES) c.add(new Choice(gmt((Integer)z[1]) + " " + z[2], 1, stringValue(DATE, "time_zone_area", (String)z[0]), intValue(DATE, "time_zone_offset", (Integer)z[1])));
        return new Setting("Time Zone", Kind.CHOICE, DATE, "time_zone_area", c, 0, 0, true, null);
    }
    /** The 12 colours of Theme Settings > Color (system_color 0..11, the month colours January..December). */
    public static final String[] COLOR_NAMES = {"Silver", "Gold", "Lime", "Pink", "Green", "Lavender", "Turquoise", "Blue", "Purple", "Orange", "Brown", "Red"};
    /** Swatch colours of the 12 theme colours, sampled from the XMB wave backdrop drawn by the firmware. */
    public static final int[] COLOR_SWATCHES = {0xffc3c3c3, 0xffeed340, 0xfface215, 0xffedafbb, 0xff0acd0a, 0xffc982f0, 0xff03dacd, 0xff0468b2, 0xff9d39a9, 0xffeeab14, 0xff7b4f19, 0xfffd1915};
    private static Setting color() {
        List<Choice> c = new ArrayList<>();
        c.add(new Choice("By Month", 1, intValue(THEME, "color_mode", 0)));
        for (int i = 0; i < 12; i++) c.add(new Choice(COLOR_NAMES[i], 2, intValue(THEME, "color_mode", 1), intValue(THEME, "system_color", i)));
        return new Setting("Color", Kind.CHOICE, THEME, "system_color", c, 0, 0, true, null);
    }
    /** Theme Settings > Theme. Applying a theme writes these three keys (and resets the colour to By Month), as the XMB does. */
    public static final Choice THEME_ORIGINAL = new Choice("Original", 1, intValue(THEME, "custom_theme_mode", 0), intValue(THEME, "wallpaper_mode", 0), intValue(THEME, "color_mode", 0)),
        THEME_CLASSIC = new Choice("Classic", 1, intValue(THEME, "custom_theme_mode", 3), intValue(THEME, "wallpaper_mode", 4), intValue(THEME, "color_mode", 0)),
        THEME_CUSTOM = new Choice("Custom Theme", 1, intValue(THEME, "custom_theme_mode", 2), intValue(THEME, "wallpaper_mode", 3), intValue(THEME, "color_mode", 0));
    /** The installed custom theme (a copy of the applied .ptf/.ctf), and the wallpaper set from Photo. */
    public static File customThemeFile(File flash1) { return new File(flash1, "vsh/theme/custom_theme.dat"); }
    public static File wallpaperFile(File flash1) { return new File(flash1, "vsh/theme/wallpaper.bmp"); }
    /** Theme choices available with this flash1: Original, Classic and, when one is installed, Custom Theme. */
    public static List<Choice> themeChoices(File flash1) {
        List<Choice> c = new ArrayList<>(Arrays.asList(THEME_ORIGINAL, THEME_CLASSIC));
        if (flash1 != null && customThemeFile(flash1).isFile()) c.add(THEME_CUSTOM);
        return c;
    }
    /** Background choices: Original, Classic, Wallpaper (only with a saved wallpaper) and the custom theme's own background. */
    public static List<Choice> backgroundChoices(File flash1, Registry r) {
        List<Choice> c = new ArrayList<>();
        c.add(new Choice("Original", 1, intValue(THEME, "wallpaper_mode", 0))); c.add(new Choice("Classic", 1, intValue(THEME, "wallpaper_mode", 4)));
        if (flash1 != null && wallpaperFile(flash1).isFile()) c.add(new Choice("Wallpaper", 1, intValue(THEME, "wallpaper_mode", 1)));
        if (intOf(r, THEME, "custom_theme_mode", 0) == 2 && flash1 != null && customThemeFile(flash1).isFile()) c.add(new Choice("Custom Theme", 1, intValue(THEME, "wallpaper_mode", 3)));
        return c;
    }
    /**
     * Installs a .ptf/.ctf theme as the XMB's Theme > Apply does: copies it to flash1:/vsh/theme/custom_theme.dat and
     * selects Custom Theme. The caller saves the registry afterwards.
     */
    public static void installCustomTheme(File theme, File flash1, Registry r) throws IOException {
        File dest = customThemeFile(flash1), tmp = new File(dest.getPath() + ".tmp"); dest.getParentFile().mkdirs();
        java.nio.file.Files.copy(theme.toPath(), tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        THEME_CUSTOM.apply(r);
    }

    /** The settings, grouped and ordered like the XMB Settings column of a PSP-1000 on 6.61. */
    public static List<Section> sections() {
        List<Section> s = new ArrayList<>();
        s.add(new Section("Video Settings",
            videoLanguage("UMD\u2122 Menu Language", "menu_language", false),
            ints("UMD\u2122 Volume", VIDEO, "appended_volume", true, "Normal", 0, "+1", 1, "+2", 2),
            videoLanguage("Audio Language", "sound_language", true),
            videoLanguage("Subtitle Language", "subtitle_language", false),
            ints("L/R Button", VIDEO, "lr_button_enable", true, "Do Not Use", 0, "Skip", 1),
            ints("Sequential Playback", VIDEO, "list_play_mode", true, "Off", 0, "On", 1),
            ints("Title Display", VIDEO, "title_display_mode", true, "Title", 0, "File Name", 1)));
        s.add(new Section("Photo Settings", ints("Slideshow Speed", "/CONFIG/PHOTO", "slideshow_speed", true, "Fast", 0, "Normal", 1, "Slow", 2)));
        s.add(new Section("System Settings",
            new Setting("Nickname", Kind.TEXT, SYSTEM, "owner_name", null, 1, 20, false, null),
            ints("System Language", XMB, "language", true, "Deutsch", 4, "English", 1, "Espa\u00f1ol", 3, "Fran\u00e7ais", 2, "Italiano", 5, "Nederlands", 6,
                "Portugu\u00eas", 7, "\u0420\u0443\u0441\u0441\u043a\u0438\u0439", 8, "\u65e5\u672c\u8a9e", 0, "\ud55c\uad6d\uc5b4", 9, "\u7b80\u4f53\u4e2d\u6587", 11, "\u7e41\u9ad4\u4e2d\u6587", 10),
            choice("Character Set", CHARSET, "oem", true,
                new Choice("US (437) / Latin 1 (1252)", 2, intValue(CHARSET, "oem", 1), intValue(CHARSET, "ansi", 19)),
                new Choice("Multilingual Latin 1 (850) / Latin 1 (1252)", 2, intValue(CHARSET, "oem", 5), intValue(CHARSET, "ansi", 19)),
                new Choice("Russian (866) / Cyrillic (1251)", 2, intValue(CHARSET, "oem", 11), intValue(CHARSET, "ansi", 18)),
                new Choice("Japanese Shift-JIS (932)", 2, intValue(CHARSET, "oem", 13), intValue(CHARSET, "ansi", 13)),
                new Choice("Simplified Chinese GBK (936)", 2, intValue(CHARSET, "oem", 14), intValue(CHARSET, "ansi", 14)),
                new Choice("Korean (949)", 2, intValue(CHARSET, "oem", 15), intValue(CHARSET, "ansi", 15)),
                new Choice("Traditional Chinese Big5 (950)", 2, intValue(CHARSET, "oem", 16), intValue(CHARSET, "ansi", 16))),
            ints("UMD\u2122 Auto-Start", SYSTEM, "umd_autoboot", true, "Off", 0, "On", 1),
            ints("USB Auto Connect", SYSTEM, "usb_auto_connect", true, "Off", 0, "On", 1),
            ints("Enter Button", XMB, "button_assign", true, "\u2715 (Enter) / \u25cb (Back)", 1, "\u25cb (Enter) / \u2715 (Back)", 0)));
        s.add(new Section("Theme Settings",
            new Setting("Theme", Kind.CHOICE, THEME, "custom_theme_mode", Arrays.asList(THEME_ORIGINAL, THEME_CLASSIC, THEME_CUSTOM), 0, 0, true, null),
            color(),
            new Setting("Background", Kind.CHOICE, THEME, "wallpaper_mode", Arrays.asList(
                new Choice("Original", 1, intValue(THEME, "wallpaper_mode", 0)), new Choice("Wallpaper", 1, intValue(THEME, "wallpaper_mode", 1)),
                new Choice("Custom Theme", 1, intValue(THEME, "wallpaper_mode", 3)), new Choice("Classic", 1, intValue(THEME, "wallpaper_mode", 4))), 0, 0, true, null)));
        s.add(new Section("Date & Time Settings",
            ints("Date Format", DATE, "date_format", true, "YYYY/MM/DD", 0, "DD/MM/YYYY", 2, "MM/DD/YYYY", 1),
            ints("Time Format", DATE, "time_format", true, "12 Hour Clock", 1, "24 Hour Clock", 0),
            timeZone(),
            ints("Daylight Saving", DATE, "summer_time", true, "Standard", 0, "Daylight Saving", 1)));
        s.add(new Section("Power Save Settings",
            ints("Backlight Auto-Off", POWER, "backlight_off_interval", true, "Off", 0, "2 minutes", 120, "5 minutes", 300, "10 minutes", 600),
            ints("Auto Sleep", POWER, "suspend_interval", true, "Off", 0, "5 minutes", 300, "10 minutes", 600, "15 minutes", 900),
            ints("WLAN Power Save", POWER, "wlan_mode", true, "Off", 0, "On", 1)));
        s.add(new Section("Sound Settings",
            ints("AVLS", SOUND, "avls", true, "Off", 0, "On", 1),
            ints("Dynamic Normalizer", SOUND, "dynamic_normalizer", true, "Off", 0, "On", 1),
            ints("Key Tone", SOUND, "operation_sound_mode", true, "Off", 0, "On", 1)));
        Object[] levels = new Object[24]; levels[0] = "Off"; levels[1] = 0; for (int i = 1; i <= 11; i++) { levels[2 * i] = String.valueOf(i); levels[2 * i + 1] = i; }
        s.add(new Section("Security Settings",
            new Setting("Change Password", Kind.PASSCODE, LOCK, "password", null, 4, 4, false, null),
            ints("Parental Control Level", LOCK, "parental_level", false, levels),
            ints("Internet Browser Start Control", LOCK, "browser_start", false, "Off", 0, "On", 1)));
        s.add(new Section("RSS Channel Settings", ints("Item Save Options", "/CONFIG/RSS", "download_items", true,
            "Latest item", 1, "Last 2 items", 2, "Last 5 items", 5, "Last 10 items", 10, "All items", 0)));
        s.add(new Section("Network Settings", ints("Ad Hoc Mode channel", "/CONFIG/NETWORK/ADHOC", "channel", true, "Automatic", 0, "Ch 1", 1, "Ch 6", 6, "Ch 11", 11)));
        s.add(new Section("Volume and Brightness",
            new Setting("Volume", Kind.RANGE, SOUND, "main_volume", null, 0, 30, true, null),
            ints("Mute", SOUND, "mute", true, "Off", 0, "On", 1),
            ints("Screen Brightness", SYSTEM, "backlight_brightness", false, "Level 1", 1, "Level 2", 2, "Level 3", 3, "Level 4", 4)));
        return s;
    }

    /** Saved infrastructure connections (/CONFIG/NETWORK/INFRASTRUCTURE/&lt;n&gt;), as "n: name (SSID)" lines; read-only. */
    public static List<String> connections(Registry r) {
        List<String> out = new ArrayList<>(); String base = "/CONFIG/NETWORK/INFRASTRUCTURE";
        for (String k : r.keys(base)) {
            String cat = base + "/" + k; if (!r.hasCategory(cat)) continue;
            String name = string(r, cat, "cnf_name", ""), ssid = string(r, cat, "ssid", "");
            out.add(k + ": " + (name.isEmpty() ? "(unnamed)" : name) + (ssid.isEmpty() ? "" : " (" + ssid + ")"));
        }
        return out;
    }

    // ---- values -------------------------------------------------------------------------------------------------
    public static int intOf(Registry r, String category, String key, int fallback) {
        Registry.Value v = r.get(category, key);
        return v == null || v.data.length < 4 ? fallback : (v.data[0] & 255) | (v.data[1] & 255) << 8 | (v.data[2] & 255) << 16 | v.data[3] << 24;
    }
    public static String string(Registry r, String category, String key, String fallback) {
        Registry.Value v = r.get(category, key); if (v == null) return fallback;
        byte[] d = trim(v); return new String(d, v.type == Registry.STRING ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
    }
    private static String raw(Registry.Value v) {
        if (v.type == Registry.INT && v.data.length >= 4) return String.valueOf((v.data[0] & 255) | (v.data[1] & 255) << 8 | (v.data[2] & 255) << 16 | v.data[3] << 24);
        if (v.type == Registry.BINARY) { StringBuilder b = new StringBuilder(); for (byte x : v.data) b.append(x >= 32 && x < 127 ? (char)x : '?'); return b.toString(); }
        return new String(trim(v), StandardCharsets.UTF_8);
    }
    /** Loads the saved registry over the first-boot defaults (a missing file gives the defaults). */
    public static Registry load(File registryFile) throws IOException {
        Registry r = new Registry(); if (registryFile.isFile()) r.load(registryFile); return r;
    }
    /** Saves with Registry's own atomic serializer. */
    public static void save(Registry r, File registryFile) throws IOException { r.save(registryFile); }
    /** flash1/registry/pxp2-registry.bin under the emulator storage, as PspSystem uses it. */
    public static File registryFile(File storage) { return new File(storage, "flash1/registry/pxp2-registry.bin"); }
}
