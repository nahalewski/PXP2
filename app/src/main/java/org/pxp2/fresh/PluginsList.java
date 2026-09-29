package org.pxp2.fresh;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.pxp2.core.Plugins;

/**
 * SEPLUGINS toggle list for the Settings screen: one row per plugin PRX in PXP/SEPLUGINS (and per plugin named in the
 * configuration files), with "XMB" and "Game" switches. Switching edits SEPLUGINS/vsh.txt and game.txt (or the
 * plugin's existing line in plugins.txt) through {@link Plugins#setEnabled}; changes apply at the next XMB or game start.
 */
final class PluginsList {
    /** The list's rows; add this to the Settings card. */
    final LinearLayout view;
    private final Context context;
    private final File memoryStick;

    PluginsList(Context context, File memoryStick) {
        this.context = context; this.memoryStick = memoryStick;
        view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL);
        refresh();
    }

    /** Rebuilds the rows from the files on the Memory Stick. */
    void refresh() {
        view.removeAllViews();
        List<String> paths = new ArrayList<>(Plugins.discover(memoryStick));
        for (Plugins.Entry e : Plugins.readConfig(memoryStick)) {
            if (!e.mode.equals("vsh") && !e.mode.equals("game")) continue;
            boolean known = false;
            for (String p : paths) if (p.equalsIgnoreCase(e.path)) { known = true; break; }
            if (!known) paths.add(e.path);
        }
        if (paths.isEmpty()) {
            view.addView(label("No plugins found. Copy .prx files to PXP/SEPLUGINS.", 13, 0xaaffffff));
            return;
        }
        for (String path : paths) view.addView(row(path));
    }

    private LinearLayout row(String path) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        String name = path.substring(path.lastIndexOf('/') + 1);
        boolean present = Plugins.findIgnoreCase(memoryStick, path.replaceFirst("(?i)^(ms0|ef0):/?", "")) != null;
        TextView title = label(present ? name : name + " (missing)", 14, present ? Color.WHITE : 0x99ffffff);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(toggle(path, "vsh", "XMB"));
        row.addView(toggle(path, "game", "Game"));
        return row;
    }

    private CheckBox toggle(String path, String mode, String text) {
        CheckBox box = new CheckBox(context);
        box.setText(text); box.setTextColor(Color.WHITE);
        box.setChecked(Plugins.isEnabled(memoryStick, mode, path));
        box.setOnCheckedChangeListener((b, checked) -> {
            try { Plugins.setEnabled(memoryStick, mode, path, checked); }
            catch (IOException e) {
                Toast.makeText(context, String.format(Locale.ROOT, "Could not update %s.txt: %s", mode, e.getMessage()), Toast.LENGTH_LONG).show();
                refresh();
            }
        });
        return box;
    }

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(context); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }
}
