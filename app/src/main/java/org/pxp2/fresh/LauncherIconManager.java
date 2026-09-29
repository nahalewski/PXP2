package org.pxp2.fresh;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;
import java.io.File;
import java.util.Calendar;
import org.pxp2.core.Registry;

/**
 * Manages the Android app launcher icon dynamically using <activity-alias> components.
 * Corresponds to the 12 PSP theme colors / months.
 */
public final class LauncherIconManager {
    private static final String TAG = "LauncherIconManager";
    public static final int ICON_COUNT = 12;
    public static final String PREF_LAST_COLOR = "launcher_icon_color_index";

    public static final String[] ALIAS_NAMES = {
        "org.pxp2.fresh.LauncherAliasColor0",
        "org.pxp2.fresh.LauncherAliasColor1",
        "org.pxp2.fresh.LauncherAliasColor2",
        "org.pxp2.fresh.LauncherAliasColor3",
        "org.pxp2.fresh.LauncherAliasColor4",
        "org.pxp2.fresh.LauncherAliasColor5",
        "org.pxp2.fresh.LauncherAliasColor6",
        "org.pxp2.fresh.LauncherAliasColor7",
        "org.pxp2.fresh.LauncherAliasColor8",
        "org.pxp2.fresh.LauncherAliasColor9",
        "org.pxp2.fresh.LauncherAliasColor10",
        "org.pxp2.fresh.LauncherAliasColor11"
    };

    public static final String[] COLOR_NAMES = {
        "Black",
        "White",
        "Red",
        "Blue",
        "Pink",
        "Light Blue",
        "Green",
        "Yellow",
        "Purple",
        "Silver",
        "Orange / Bronze",
        "Teal"
    };

    /**
     * Switches the active launcher icon alias using PackageManager.DONT_KILL_APP.
     * Enables the target alias first, then disables the others.
     */
    public static synchronized void setIconColor(Context context, int colorIndex) {
        if (context == null || colorIndex < 0 || colorIndex >= ICON_COUNT) {
            Log.w(TAG, "Invalid setIconColor request: " + colorIndex);
            return;
        }

        PackageManager pm = context.getPackageManager();
        String targetAlias = ALIAS_NAMES[colorIndex];
        ComponentName targetComponent = new ComponentName(context, targetAlias);

        try {
            int currentState = pm.getComponentEnabledSetting(targetComponent);
            if (currentState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                // 1. Enable new target alias first
                pm.setComponentEnabledSetting(
                    targetComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                );
                Log.i(TAG, "Switched active launcher icon to " + COLOR_NAMES[colorIndex] + " (" + targetAlias + ")");
            }

            // 2. Disable all other aliases
            for (int i = 0; i < ICON_COUNT; i++) {
                if (i == colorIndex) continue;
                ComponentName other = new ComponentName(context, ALIAS_NAMES[i]);
                int state = pm.getComponentEnabledSetting(other);
                if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        other,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    );
                }
            }

            SharedPreferences prefs = context.getSharedPreferences("pxp2", Context.MODE_PRIVATE);
            prefs.edit().putInt(PREF_LAST_COLOR, colorIndex).apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to switch launcher icon alias: " + t.getMessage(), t);
        }
    }

    /**
     * Resolves the icon index (0..11) from the PSP system registry and storage.
     */
    public static int resolveColorIndex(Registry registry, File flash1) {
        if (registry == null) return 3; // Default to Blue (classic PXP2 icon)

        // 1. Check custom theme file
        if (flash1 != null) {
            File ctfDat = new File(flash1, "vsh/theme/custom_theme.dat");
            if (ctfDat.isFile() && ctfDat.length() > 0) {
                Registry.Value ctm = registry.get("/CONFIG/SYSTEM/XMB/THEME", "custom_theme_mode");
                if (ctm != null && readInt(ctm) == 2) { // 2 = Custom Theme (0 Original, 3 Classic; traced, see psp.md)
                    return 0; // Black icon for custom theme
                }
            }
        }

        // 2. Check explicit theme_color key if present
        Registry.Value tc = registry.get("/CONFIG/SYSTEM/XMB", "theme_color");
        if (tc == null) tc = registry.get("/CONFIG/SYSTEM/XMB/THEME", "theme_color");
        if (tc != null) {
            int val = readInt(tc);
            if (val >= 0 && val < ICON_COUNT) return val;
        }

        // 3. Check color_mode and system_color
        Registry.Value cm = registry.get("/CONFIG/SYSTEM/XMB/THEME", "color_mode");
        Registry.Value sc = registry.get("/CONFIG/SYSTEM/XMB/THEME", "system_color");
        int colorMode = cm != null ? readInt(cm) : 0;
        int systemColor = sc != null ? readInt(sc) : 0;

        if (colorMode == 0) {
            // Month mode: 1 = Jan .. 12 = Dec
            int month = Calendar.getInstance().get(Calendar.MONTH) + 1;
            return mapMonthToIconIndex(month);
        } else {
            // Theme Settings > Color writes color_mode 1 and system_color 0..11, the January..December colours (traced).
            return systemColor >= 0 && systemColor < 12 ? mapMonthToIconIndex(systemColor + 1) : mapSystemColorToIconIndex(systemColor);
        }
    }

    /**
     * Maps PSP 12-month calendar colors (1..12) to our 12 icon variants:
     * 1: White (1)
     * 2: Yellow (7)
     * 3: Green (6)
     * 4: Pink (4)
     * 5: Green (6)
     * 6: Light Blue (5)
     * 7: Teal (11)
     * 8: Dark Blue -> Blue (3)
     * 9: Purple (8)
     * 10: Orange/Bronze (10)
     * 11: Bronze/Orange (10)
     * 12: Red (2)
     */
    public static int mapMonthToIconIndex(int month) {
        switch (month) {
            case 1: return 1;  // Jan: White/Silver
            case 2: return 7;  // Feb: Yellow
            case 3: return 6;  // Mar: Pale Green
            case 4: return 4;  // Apr: Pink
            case 5: return 6;  // May: Green
            case 6: return 5;  // Jun: Light Blue
            case 7: return 11; // Jul: Teal
            case 8: return 3;  // Aug: Dark Blue
            case 9: return 8;  // Sep: Purple
            case 10: return 10;// Oct: Gold/Orange
            case 11: return 10;// Nov: Brown/Bronze
            case 12: return 2; // Dec: Red
            default: return 3; // Fallback: Blue
        }
    }

    /**
     * Maps system_color integer to our 12 icon variants.
     */
    public static int mapSystemColorToIconIndex(int color) {
        if (color >= 0 && color < ICON_COUNT) return color;
        // 1-based month index (1..12)
        if (color >= 1 && color <= 12) return mapMonthToIconIndex(color);
        // PSP-2000/3000 extended colors (13..17+)
        switch (color) {
            case 13: return 0; // Black
            case 14: return 1; // White
            case 15: return 2; // Red
            case 16: return 3; // Blue
            case 17: return 4; // Pink
            default: return (Math.abs(color) % ICON_COUNT);
        }
    }

    /**
     * Reads persistent registry on storage and updates the launcher icon alias.
     */
    public static void updateIconFromStorage(Context context) {
        try {
            File storage = new File(context.getExternalFilesDir(null), "psp");
            File flash1 = new File(storage, "flash1");
            File regFile = new File(flash1, "registry/pxp2-registry.bin");
            Registry reg = new Registry();
            if (regFile.isFile()) {
                try { reg.load(regFile); } catch (Throwable ignored) {}
            }
            int color = resolveColorIndex(reg, flash1);
            setIconColor(context, color);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to update icon from storage: " + t.getMessage(), t);
        }
    }

    public static int readInt(Registry.Value v) {
        if (v == null || v.data == null || v.data.length < 4) return 0;
        return (v.data[0] & 255) | ((v.data[1] & 255) << 8) | ((v.data[2] & 255) << 16) | ((v.data[3] & 255) << 24);
    }
}
