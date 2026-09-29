package org.pxp2.fresh;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The PXP2 quick menu: a PSP-styled list drawn over the paused emulator (in games and in the XMB). Rows are built by
 * XmbActivity: choices (◀ ▶ or ✕ cycle), sliders (◀ ▶ step, touch drag), actions (✕) and read-only info lines.
 * Navigation: D-pad / stick up-down selects, left-right changes, the confirm button activates, the back button or
 * HOME closes; touch taps and drags. Original drawing code, tinted with the XMB theme colour.
 */
final class QuickMenuView extends View {
    interface Listener { void onQuickMenuClosed(); }

    private static final int CHOICE = 0, SLIDER = 1, ACTION = 2, INFO = 3, HEADER = 4;
    private static final class Row {
        int type; String label; String[] options; IntSupplier get; IntConsumer set; int min, max, step; Supplier<String> text; Runnable action; String note;
        java.util.function.IntFunction<String> format;
    }
    private final List<Row> rows = new ArrayList<>();
    private final Listener listener;
    private final Paint dim = new Paint(), panel = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
        value = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelRect = new RectF();
    private int selected, scroll; private float rowHeight = 1, listTop, sliderLeft, sliderRight;
    private int confirm = XmbActivity.CROSS, back = XmbActivity.CIRCLE;
    private int dragRow = -1; private float downY; private boolean moved;
    private String title = "PXP2", subtitle = "";

    QuickMenuView(Context c, Listener listener) {
        super(c); this.listener = listener;
        dim.setColor(0xa0000000); label.setColor(Color.WHITE); value.setColor(0xddffffff); value.setTextAlign(Paint.Align.RIGHT);
        setVisibility(GONE);
    }

    // ---- building ---------------------------------------------------------------------------------
    void clear() { rows.clear(); selected = 0; scroll = 0; }
    void header(String text) { Row r = new Row(); r.type = HEADER; r.label = text; rows.add(r); }
    void choice(String text, String[] options, IntSupplier get, IntConsumer set, String note) { Row r = new Row(); r.type = CHOICE; r.label = text; r.options = options; r.get = get; r.set = set; r.note = note; rows.add(r); }
    void slider(String text, int min, int max, int step, IntSupplier get, IntConsumer set, java.util.function.IntFunction<String> format) {
        Row r = new Row(); r.type = SLIDER; r.label = text; r.min = min; r.max = max; r.step = step; r.get = get; r.set = set; r.format = format; rows.add(r);
    }
    void action(String text, Runnable action) { Row r = new Row(); r.type = ACTION; r.label = text; r.action = action; rows.add(r); }
    void info(String text, Supplier<String> value) { Row r = new Row(); r.type = INFO; r.label = text; r.text = value; rows.add(r); }

    void open(String title, String subtitle, boolean circleConfirms) {
        this.title = title; this.subtitle = subtitle;
        confirm = circleConfirms ? XmbActivity.CIRCLE : XmbActivity.CROSS; back = circleConfirms ? XmbActivity.CROSS : XmbActivity.CIRCLE;
        selected = 0; while (selected < rows.size() - 1 && !selectable(rows.get(selected))) selected++;
        scroll = 0; setVisibility(VISIBLE); bringToFront(); invalidate();
    }
    boolean isOpen() { return getVisibility() == VISIBLE; }
    void close() { if (!isOpen()) return; setVisibility(GONE); listener.onQuickMenuClosed(); }
    /** Redraws, e.g. after the "last key" readout changed. */
    void refresh() { if (isOpen()) postInvalidate(); }
    private static boolean selectable(Row r) { return r.type != HEADER && r.type != INFO; }

    // ---- controller -------------------------------------------------------------------------------
    void onButton(int bit) {
        if (rows.isEmpty()) return;
        Row r = rows.get(selected);
        if (bit == XmbActivity.UP || bit == XmbActivity.DOWN) move(bit == XmbActivity.UP ? -1 : 1);
        else if (bit == XmbActivity.LEFT || bit == XmbActivity.RIGHT) change(r, bit == XmbActivity.LEFT ? -1 : 1);
        else if (bit == confirm) activate(r);
        else if (bit == back || bit == XmbActivity.HOME) close();
        invalidate();
    }
    private void move(int d) {
        int i = selected;
        do { i += d; } while (i >= 0 && i < rows.size() && !selectable(rows.get(i)));
        if (i >= 0 && i < rows.size()) selected = i;
        else if (d < 0) scroll = 0; // above the first selectable row: show the headers too
    }
    private void change(Row r, int d) {
        if (r.type == CHOICE) { int n = r.options.length; r.set.accept(((r.get.getAsInt() + d) % n + n) % n); }
        else if (r.type == SLIDER) r.set.accept(Math.max(r.min, Math.min(r.max, r.get.getAsInt() + d * r.step)));
    }
    private void activate(Row r) {
        if (r.type == CHOICE) change(r, 1);
        else if (r.type == ACTION) r.action.run();
    }

    // ---- drawing ----------------------------------------------------------------------------------
    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int accent = XmbTheme.accent(getContext());
        c.drawRect(0, 0, w, h, dim);
        float u = Math.min(h / 22f, w / 30f);
        float pw = Math.min(w - u * 2, u * 30), left = (w - pw) / 2;
        panelRect.set(left, u * 0.8f, left + pw, h - u * 0.8f);
        panel.setShader(new LinearGradient(0, panelRect.top, 0, panelRect.bottom, (accent & 0x00ffffff) | 0x66000000, 0xe0101014, Shader.TileMode.CLAMP));
        c.drawRoundRect(panelRect, u * 0.8f, u * 0.8f, panel); panel.setShader(null);
        label.setTextSize(u * 1.25f); label.setFakeBoldText(true); label.setTextAlign(Paint.Align.LEFT);
        c.drawText(title, left + u, panelRect.top + u * 1.8f, label);
        label.setFakeBoldText(false); value.setTextSize(u * 0.8f);
        c.drawText(subtitle, panelRect.right - u, panelRect.top + u * 1.8f, value);
        String enter = confirm == XmbActivity.CROSS ? "✕" : "○", cancel = confirm == XmbActivity.CROSS ? "○" : "✕";
        value.setTextAlign(Paint.Align.CENTER);
        c.drawText("▲▼ Select    ◀▶ Change    " + enter + " Enter    " + cancel + " / HOME Resume", w / 2f, panelRect.bottom - u * 0.6f, value);
        value.setTextAlign(Paint.Align.RIGHT);
        rowHeight = u * 1.75f; listTop = panelRect.top + u * 2.6f;
        int visible = Math.max(1, (int)((panelRect.bottom - u * 1.6f - listTop) / rowHeight));
        if (selected < scroll) scroll = selected; if (selected >= scroll + visible) scroll = selected - visible + 1;
        while (scroll > 0 && rows.get(scroll - 1).type == HEADER && selected - (scroll - 1) < visible) scroll--;
        sliderRight = panelRect.right - u; sliderLeft = sliderRight - pw * 0.34f;
        for (int i = scroll; i < rows.size() && i < scroll + visible; i++) {
            Row r = rows.get(i); float top = listTop + (i - scroll) * rowHeight, base = top + rowHeight * 0.66f;
            if (r.type == HEADER) {
                label.setTextSize(u * 0.8f); label.setColor(0xaaffffff); c.drawText(r.label.toUpperCase(java.util.Locale.ROOT), left + u, base + u * 0.1f, label);
                fill.setColor(0x33ffffff); c.drawRect(left + u, top + rowHeight * 0.82f, panelRect.right - u, top + rowHeight * 0.82f + 2, fill);
                label.setColor(Color.WHITE); continue;
            }
            if (i == selected) {
                fill.setShader(new LinearGradient(left, 0, panelRect.right, 0, (accent & 0x00ffffff) | 0xbb000000, (accent & 0x00ffffff) | 0x33000000, Shader.TileMode.CLAMP));
                c.drawRoundRect(left + u * 0.4f, top + 2, panelRect.right - u * 0.4f, top + rowHeight - 2, u * 0.5f, u * 0.5f, fill); fill.setShader(null);
            }
            label.setTextSize(u * 0.95f); label.setColor(r.type == INFO ? 0xccffffff : Color.WHITE);
            c.drawText(r.label, left + u, base, label); label.setColor(Color.WHITE);
            value.setTextSize(u * 0.85f);
            if (r.type == CHOICE) {
                int v = r.get.getAsInt(); String s = r.options[Math.max(0, Math.min(r.options.length - 1, v))];
                c.drawText((i == selected ? "◀  " : "") + s + (i == selected ? "  ▶" : "") + (r.note != null ? "   " + r.note : ""), panelRect.right - u, base, value);
            } else if (r.type == SLIDER) {
                int v = r.get.getAsInt(); float f = (v - r.min) / (float)Math.max(1, r.max - r.min), cy = top + rowHeight / 2;
                fill.setColor(0x44ffffff); c.drawRoundRect(sliderLeft, cy - u * 0.14f, sliderRight, cy + u * 0.14f, u * 0.14f, u * 0.14f, fill);
                fill.setColor(0xffffffff); c.drawRoundRect(sliderLeft, cy - u * 0.14f, sliderLeft + (sliderRight - sliderLeft) * f, cy + u * 0.14f, u * 0.14f, u * 0.14f, fill);
                c.drawCircle(sliderLeft + (sliderRight - sliderLeft) * f, cy, u * 0.36f, fill);
                c.drawText(r.format.apply(v), sliderLeft - u * 0.6f, base, value);
            } else if (r.type == INFO) {
                String s = r.text.get(); if (s != null) c.drawText(s, panelRect.right - u, base, value);
            }
        }
    }

    // ---- touch ------------------------------------------------------------------------------------
    private int rowAt(float y) { int i = scroll + (int)Math.floor((y - listTop) / rowHeight); return y >= listTop && i >= 0 && i < rows.size() ? i : -1; }
    private void dragTo(Row r, float x) {
        float f = Math.max(0, Math.min(1, (x - sliderLeft) / (sliderRight - sliderLeft)));
        int v = r.min + Math.round(f * (r.max - r.min) / r.step) * r.step; r.set.accept(Math.max(r.min, Math.min(r.max, v)));
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downY = y; moved = false; dragRow = -1;
                if (!panelRect.contains(x, y)) return true;
                int i = rowAt(y);
                if (i >= 0 && rows.get(i).type == SLIDER && x >= sliderLeft - rowHeight * 0.5f) { dragRow = i; selected = i; dragTo(rows.get(i), x); invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragRow >= 0) { dragTo(rows.get(dragRow), x); invalidate(); }
                else if (Math.abs(y - downY) > rowHeight) { // drag the list
                    int d = y < downY ? 1 : -1; downY = y; moved = true;
                    scroll = Math.max(0, Math.min(Math.max(0, rows.size() - 1), scroll + d)); selected = Math.max(selected, scroll);
                    while (selected < rows.size() - 1 && !selectable(rows.get(selected))) selected++;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP: {
                if (dragRow >= 0) { dragRow = -1; return true; }
                if (moved) return true;
                if (!panelRect.contains(x, y)) { close(); return true; } // tap outside the panel resumes
                int i = rowAt(y); if (i < 0 || !selectable(rows.get(i))) return true;
                selected = i; Row r = rows.get(i);
                activate(r); // a tap cycles a choice or runs an action
                invalidate(); return true;
            }
            default: return true;
        }
    }
}
