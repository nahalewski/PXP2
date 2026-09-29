package org.pxp2.fresh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.pxp2.core.Registry;
import org.pxp2.core.XmbSettings;

/**
 * "PSP Settings": the XMB Settings column as a native screen. It edits the emulated PSP's saved registry
 * (flash1/registry/pxp2-registry.bin) with Registry's own serializer — load, change one setting, save — and only while
 * the XMB is not running, because a running XMB keeps its own copy in memory and writes it back. The XMB reads the
 * settings when it starts, so changes take effect on the next start. The registry keys and values come from
 * {@link XmbSettings} (traced from the firmware; see psp.md).
 */
public final class XmbSettingsActivity extends Activity {
    private File storage, flash1, registryFile;
    private Registry registry;
    private boolean xmbRunning, securityUnlocked;
    private LinearLayout content;
    private int accent;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        storage = new File(getExternalFilesDir(null), "psp"); flash1 = new File(storage, "flash1");
        registryFile = XmbSettings.registryFile(storage);
        buildUi();
    }
    @Override protected void onResume() { super.onResume(); reload(); }

    /** The XMB runs on XmbActivity's "PXP2 emulation" thread in this process; it stops when that activity pauses. */
    static boolean xmbRunning() {
        for (Thread t : Thread.getAllStackTraces().keySet()) if ("PXP2 emulation".equals(t.getName()) && t.isAlive()) return true;
        return false;
    }

    private void reload() {
        xmbRunning = xmbRunning();
        try { registry = loadRegistry(); } catch (IOException e) { registry = new Registry(); toast("Could not read the PSP settings: " + e.getMessage()); }
        render();
    }
    /** The saved registry over the first-boot defaults; before the first XMB start, the fonts are registered as PspSystem does. */
    private Registry loadRegistry() throws IOException {
        Registry r = XmbSettings.load(registryFile);
        if (!registryFile.isFile()) r.addFonts(new File(getFilesDir(), "firmware/flash-661-01g-v1/flash0/font"));
        return r;
    }

    private interface Change { void apply(Registry r) throws IOException; }
    /** Load the saved file fresh, apply one change, save it atomically. Refused while the XMB runs. */
    private void commit(Change change) {
        if (xmbRunning()) { xmbRunning = true; toast("Close the XMB to change settings"); render(); return; }
        try {
            Registry r = loadRegistry(); change.apply(r); XmbSettings.save(r, registryFile); registry = r;
        } catch (IOException e) { toast("Could not save: " + e.getMessage()); }
        // The app's own colours follow the XMB theme colour (launcher icon and accent).
        int color = LauncherIconManager.resolveColorIndex(registry, flash1);
        LauncherIconManager.setIconColor(this, color);
        if (XmbTheme.accent(this) != accent) buildUi();
        render();
    }

    // ---- layout -----------------------------------------------------------------------------------------------
    private void buildUi() {
        accent = XmbTheme.accent(this);
        getWindow().setStatusBarColor(XmbTheme.statusBar(this)); getWindow().setNavigationBarColor(XmbTheme.statusBar(this));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackground(XmbTheme.background(this));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(20) + insets.getSystemWindowInsetTop(), dp(16) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        FrameLayout frame = new FrameLayout(this); scroll.addView(frame);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(760), getResources().getDisplayMetrics().widthPixels), -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL; frame.addView(content, lp);
        setContentView(scroll);
        if (registry != null) render();
    }

    private void render() {
        if (content == null || registry == null) return;
        content.removeAllViews();
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this); back.setText("‹ Back"); back.setAllCaps(false); back.setTextColor(Color.WHITE); back.setTextSize(15);
        GradientDrawable bb = new GradientDrawable(); bb.setCornerRadius(dp(20)); bb.setColor(0x33ffffff); bb.setStroke(dp(1), 0x66ffffff); back.setBackground(bb);
        back.setStateListAnimator(null); back.setPadding(dp(16), 0, dp(16), 0); back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(-2, dp(40)));
        content.addView(top);
        TextView title = text("PSP Settings", 34, Typeface.create("sans-serif-light", Typeface.NORMAL), Color.WHITE);
        title.setPadding(0, dp(12), 0, 0); content.addView(title);
        content.addView(text("The emulated PSP's own system settings, as in the XMB Settings column.", 14, Typeface.DEFAULT, 0xccffffff));

        LinearLayout banner = card(); content.addView(banner, spaced(dp(14)));
        if (xmbRunning) {
            banner.addView(text("Close the XMB to change settings", 17, Typeface.DEFAULT_BOLD, accent));
            banner.addView(text("The XMB is running and keeps these settings in memory. Leave it (Exit to PXP2 or Back), then change them here.", 13, Typeface.DEFAULT, 0xccffffff));
        } else {
            banner.addView(text(registryFile.isFile() ? "Changes take effect the next time the XMB starts." : "The PSP has not been started yet: these are its first-boot settings. Changes are used from the first start.", 14, Typeface.DEFAULT, Color.WHITE));
        }

        for (XmbSettings.Section section : XmbSettings.sections()) {
            content.addView(sectionTitle(section.title));
            LinearLayout card = card(); card.setPadding(dp(6), dp(6), dp(6), dp(6)); content.addView(card, spaced(dp(6)));
            if (section.title.equals("Date & Time Settings")) card.addView(row("Date and Time", "Set from this device's clock", null, null));
            for (XmbSettings.Setting s : section.settings) card.addView(settingRow(s));
            if (section.title.equals("Theme Settings")) card.addView(row("Install Custom Theme", themeFiles().length + " in PSP/THEME", null, xmbRunning ? null : v -> pickThemeFile()));
            if (section.title.equals("Network Settings")) {
                List<String> connections = XmbSettings.connections(registry);
                card.addView(row("Infrastructure Mode connections", connections.isEmpty() ? "None saved" : String.valueOf(connections.size()), null, null));
                for (String c : connections) card.addView(row("  " + c, "Read-only", null, null));
            }
        }
        TextView foot = text("Not shown: USB Connection (an action, not a setting), Enable WMA Playback and Enable Flash® Player (online activations), "
            + "Restore Default Settings, and items this PSP-1000 model's XMB does not offer (UMD Cache, USB Charge, TV output, Remote Play).", 12, Typeface.DEFAULT, 0x99ffffff);
        foot.setPadding(dp(4), dp(16), dp(4), 0); content.addView(foot);
    }

    private View settingRow(XmbSettings.Setting s) {
        Integer swatch = null;
        if (s.label.equals("Color")) { int m = XmbSettings.intOf(registry, XmbSettings.THEME, "color_mode", 0), c = XmbSettings.intOf(registry, XmbSettings.THEME, "system_color", 0);
            if (m == 1 && c >= 0 && c < 12) swatch = XmbSettings.COLOR_SWATCHES[c]; }
        String value = s.display(registry);
        if (s.kind == XmbSettings.Kind.RANGE) value = value + " / " + s.max;
        return row(s.label, value, swatch, xmbRunning || s.kind == XmbSettings.Kind.INFO ? null : v -> edit(s));
    }

    private View row(String label, String value, Integer swatch, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setMinimumHeight(dp(52)); r.setPadding(dp(12), dp(6), dp(12), dp(6));
        TextView l = text(label, 15, Typeface.DEFAULT, Color.WHITE); r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (swatch != null) { View dot = new View(this); GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(swatch); d.setStroke(dp(1), 0x99ffffff); dot.setBackground(d);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(14), dp(14)); dl.rightMargin = dp(8); r.addView(dot, dl); }
        TextView v = text(value, 14, Typeface.DEFAULT, click == null && !xmbRunning ? 0x99ffffff : 0xddffffff); v.setGravity(Gravity.END); v.setMaxWidth(dp(300));
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        if (click != null) {
            GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(12));
            r.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, mask)); r.setOnClickListener(click);
        } else if (xmbRunning) r.setAlpha(0.55f);
        return r;
    }

    // ---- editing ----------------------------------------------------------------------------------------------
    private void edit(XmbSettings.Setting s) {
        if (s.category.equals(XmbSettings.LOCK) && !securityUnlocked) { askPasscode(() -> { securityUnlocked = true; edit(s); }); return; }
        switch (s.kind) {
            case CHOICE: choose(s); break;
            case TEXT: editText(s); break;
            case PASSCODE: newPasscode(); break;
            case RANGE: editRange(s); break;
            default: break;
        }
    }

    private void choose(XmbSettings.Setting s) {
        List<XmbSettings.Choice> choices = s.label.equals("Theme") ? XmbSettings.themeChoices(flash1)
            : s.label.equals("Background") ? XmbSettings.backgroundChoices(flash1, registry) : s.choices;
        CharSequence[] labels = new CharSequence[choices.size()]; int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            XmbSettings.Choice c = choices.get(i);
            if (s.label.equals("Color") && i > 0) {
                SpannableString span = new SpannableString("●  " + c.label);
                span.setSpan(new ForegroundColorSpan(XmbSettings.COLOR_SWATCHES[i - 1]), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); labels[i] = span;
            } else labels[i] = c.label;
            if (checked < 0 && c.selected(registry)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(s.label).setSingleChoiceItems(labels, checked, (d, which) -> {
            d.dismiss(); XmbSettings.Choice c = choices.get(which); commit(c::apply);
        }).setNegativeButton("Cancel", null).show();
    }

    private void editText(XmbSettings.Setting s) {
        EditText input = new EditText(this); input.setSingleLine(); input.setText(s.display(registry)); input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(s.max)});
        new AlertDialog.Builder(this).setTitle(s.label).setView(padded(input)).setPositiveButton("OK", (d, w) -> {
            String value = input.getText().toString().trim();
            if (value.length() < s.min) { toast(s.label + " cannot be empty"); return; }
            commit(r -> XmbSettings.stringValue(s.category, s.key, value).apply(r));
        }).setNegativeButton("Cancel", null).show();
    }

    private EditText passcodeField() {
        EditText input = new EditText(this); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)}); input.setGravity(Gravity.CENTER); input.setTextSize(24); return input;
    }
    /** Security Settings ask for the current 4-digit password first, as the XMB does. */
    private void askPasscode(Runnable then) {
        EditText input = passcodeField();
        new AlertDialog.Builder(this).setTitle("Enter the password").setMessage("Security Settings need the PSP's 4-digit password (0000 unless it was changed).")
            .setView(padded(input)).setPositiveButton("OK", (d, w) -> {
                if (input.getText().toString().equals(XmbSettings.string(registry, XmbSettings.LOCK, "password", "0000"))) then.run(); else toast("Wrong password");
            }).setNegativeButton("Cancel", null).show();
    }
    private void newPasscode() {
        EditText input = passcodeField();
        new AlertDialog.Builder(this).setTitle("Change Password").setMessage("Enter a new 4-digit password.").setView(padded(input)).setPositiveButton("OK", (d, w) -> {
            String p = input.getText().toString();
            if (!p.matches("[0-9]{4}")) { toast("The password must be 4 digits"); return; }
            commit(r -> XmbSettings.stringValue(XmbSettings.LOCK, "password", p).apply(r));
        }).setNegativeButton("Cancel", null).show();
    }

    private void editRange(XmbSettings.Setting s) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView label = text("", 18, Typeface.DEFAULT_BOLD, 0xff202020); label.setGravity(Gravity.CENTER);
        SeekBar bar = new SeekBar(this); bar.setMax(s.max - s.min);
        int current = XmbSettings.intOf(registry, s.category, s.key, s.min);
        bar.setProgress(Math.max(0, Math.min(s.max, current) - s.min)); label.setText(String.valueOf(current));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { label.setText(String.valueOf(p + s.min)); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        box.addView(label); box.addView(bar);
        new AlertDialog.Builder(this).setTitle(s.label + " (" + s.min + "–" + s.max + ")").setView(padded(box)).setPositiveButton("OK", (d, w) -> {
            int value = bar.getProgress() + s.min; commit(r -> XmbSettings.intValue(s.category, s.key, value).apply(r));
        }).setNegativeButton("Cancel", null).show();
    }

    private File[] themeFiles() {
        File[] files = new File(PxpStorage.memoryStick(this), "PSP/THEME").listFiles(f -> f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(ptf|ctf)"));
        if (files == null) return new File[0];
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName())); return files;
    }
    /** Theme Settings > Theme > (a .ptf/.ctf) > Apply: copies it to flash1:/vsh/theme/custom_theme.dat and selects it. */
    private void pickThemeFile() {
        File[] files = themeFiles();
        if (files.length == 0) { toast("Put .ptf or .ctf themes in PXP/PSP/THEME first"); return; }
        String[] names = new String[files.length]; for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        new AlertDialog.Builder(this).setTitle("Install Custom Theme").setItems(names, (d, which) -> {
            File theme = files[which];
            new AlertDialog.Builder(this).setTitle("Apply " + theme.getName() + "?").setMessage("It replaces the installed custom theme and is used from the next XMB start.")
                .setPositiveButton("Apply", (d2, w) -> commit(r -> XmbSettings.installCustomTheme(theme, flash1, r))).setNegativeButton("Cancel", null).show();
        }).setNegativeButton("Cancel", null).show();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------
    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
    private TextView text(String s, int sp, Typeface face, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTypeface(face); t.setTextColor(color); return t; }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = top; return p; }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0x2effffff); bg.setCornerRadius(dp(18)); bg.setStroke(dp(1), 0x33ffffff); c.setBackground(bg);
        return c;
    }
    private TextView sectionTitle(String s) {
        TextView t = text(s, 18, Typeface.DEFAULT_BOLD, Color.WHITE); t.setPadding(dp(4), dp(20), 0, 0);
        t.setShadowLayer(dp(3), 0, 0, 0x66000000); return t;
    }
    private View padded(View v) { FrameLayout f = new FrameLayout(this); f.setPadding(dp(22), dp(8), dp(22), 0); f.addView(v); return f; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
