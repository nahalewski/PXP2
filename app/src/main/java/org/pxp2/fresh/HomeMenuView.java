package org.pxp2.fresh;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.Date;

/**
 * PXP2's own HOME screen, drawn over the paused game like the PSP's: the game frame dimmed behind, the clock and
 * battery at the top, and "Quit the game?" with Yes / No. Navigation: D-pad / stick left-right, the confirm button
 * (✕ or ○ per the PSP's button-assign setting) chooses, the other one or HOME again answers No; touch taps an answer.
 * Original drawing code; it does not use any Sony artwork.
 */
final class HomeMenuView extends View {
    interface Listener { void onHomeAnswer(boolean quit); }

    private final Listener listener;
    private final Paint dim = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG),
        box = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF yesBox = new RectF(), noBox = new RectF();
    private boolean yesSelected; // PXP2 starts on "No", the harmless answer
    private int confirm = XmbActivity.CROSS, back = XmbActivity.CIRCLE;
    private int batteryPercent = 100; private boolean charging;
    private final Runnable tick = new Runnable() { @Override public void run() { if (getVisibility() == VISIBLE) { invalidate(); postDelayed(this, 1000); } } };

    HomeMenuView(Context c, Listener listener) {
        super(c); this.listener = listener;
        dim.setColor(0xb8000000); text.setColor(Color.WHITE); small.setColor(0xddffffff);
        text.setTextAlign(Paint.Align.CENTER); line.setStyle(Paint.Style.STROKE);
        setVisibility(GONE); setFocusable(false);
    }

    /** Opens the screen. {@code circleConfirms}: the PSP registry's button_assign is 0 (○ = enter, Japanese layout). */
    void open(boolean circleConfirms, int batteryPercent, boolean charging) {
        confirm = circleConfirms ? XmbActivity.CIRCLE : XmbActivity.CROSS; back = circleConfirms ? XmbActivity.CROSS : XmbActivity.CIRCLE;
        this.batteryPercent = batteryPercent; this.charging = charging; yesSelected = false;
        setVisibility(VISIBLE); bringToFront(); removeCallbacks(tick); post(tick); invalidate();
    }
    void setBattery(int percent, boolean charging) { batteryPercent = percent; this.charging = charging; if (getVisibility() == VISIBLE) invalidate(); }
    boolean isOpen() { return getVisibility() == VISIBLE; }
    private void answer(boolean quit) { setVisibility(GONE); removeCallbacks(tick); listener.onHomeAnswer(quit); }

    /** A PSP button press while the screen is up (already mapped from the key, hat or stick). Always consumed. */
    void onButton(int bit) {
        if ((bit & (XmbActivity.LEFT | XmbActivity.RIGHT | XmbActivity.UP | XmbActivity.DOWN)) != 0) { yesSelected = !yesSelected; invalidate(); }
        else if (bit == confirm) answer(yesSelected);
        else if (bit == back || bit == XmbActivity.HOME) answer(false);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight(); float u = Math.min(w, h * 16f / 9f) / 40f; // layout unit
        int accent = XmbTheme.accent(getContext());
        c.drawRect(0, 0, w, h, dim);
        // Top bar: HOME on the left, date/time and battery on the right.
        Paint bar = new Paint(); bar.setShader(new LinearGradient(0, 0, 0, u * 3.2f, 0xcc000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, u * 3.2f, bar);
        small.setTextSize(u * 1.05f); small.setTextAlign(Paint.Align.LEFT);
        drawHouse(c, u * 1.6f, u * 1.55f, u * 0.75f, Color.WHITE);
        c.drawText("HOME", u * 2.8f, u * 1.95f, small);
        float bx = w - u * 3.6f, by = u * 1.0f; drawBattery(c, bx, by, u * 2.2f, u * 1.05f);
        small.setTextAlign(Paint.Align.RIGHT);
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(getContext());
        java.text.DateFormat date = android.text.format.DateFormat.getDateFormat(getContext());
        Date now = new Date();
        c.drawText(date.format(now) + "  " + time.format(now) + "   " + batteryPercent + "%", bx - u * 0.6f, u * 1.95f, small);
        // Centre: the question and the two answers.
        float cy = h * 0.46f;
        text.setTextSize(u * 1.7f); text.setFakeBoldText(false);
        c.drawText("Do you want to quit the game?", w / 2f, cy - u * 2.2f, text);
        float bw = u * 7f, bh = u * 2.6f, gap = u * 1.4f;
        yesBox.set(w / 2f - gap / 2 - bw, cy, w / 2f - gap / 2, cy + bh);
        noBox.set(w / 2f + gap / 2, cy, w / 2f + gap / 2 + bw, cy + bh);
        drawAnswer(c, yesBox, "Yes", yesSelected, accent, u);
        drawAnswer(c, noBox, "No", !yesSelected, accent, u);
        // Button hints, following the PSP's enter-button setting.
        small.setTextAlign(Paint.Align.CENTER); small.setTextSize(u * 0.95f);
        String enter = confirm == XmbActivity.CROSS ? "✕" : "○", cancel = confirm == XmbActivity.CROSS ? "○" : "✕";
        c.drawText(enter + " Enter      " + cancel + " Back      HOME Close", w / 2f, h - u * 1.6f, small);
    }
    private void drawAnswer(Canvas c, RectF r, String label, boolean selected, int accent, float u) {
        float radius = r.height() / 2;
        if (selected) {
            box.setShader(new RadialGradient(r.centerX(), r.centerY(), r.width() * 0.75f, (accent & 0x00ffffff) | 0xaa000000, (accent & 0x00ffffff) | 0x22000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, box); box.setShader(null);
            line.setColor(0xeeffffff); line.setStrokeWidth(u * 0.14f); c.drawRoundRect(r, radius, radius, line);
        } else {
            box.setColor(0x33ffffff); c.drawRoundRect(r, radius, radius, box);
        }
        text.setTextSize(u * 1.35f); text.setFakeBoldText(selected); text.setColor(selected ? Color.WHITE : 0xbbffffff);
        c.drawText(label, r.centerX(), r.centerY() + text.getTextSize() * 0.36f, text);
        text.setFakeBoldText(false); text.setColor(Color.WHITE);
    }
    private void drawBattery(Canvas c, float x, float y, float bw, float bh) {
        line.setColor(Color.WHITE); line.setStrokeWidth(bh * 0.1f);
        c.drawRect(x, y, x + bw, y + bh, line);
        box.setColor(Color.WHITE); c.drawRect(x + bw, y + bh * 0.3f, x + bw + bh * 0.18f, y + bh * 0.7f, box);
        float inset = bh * 0.18f, full = bw - inset * 2;
        box.setColor(batteryPercent <= 15 && !charging ? 0xffff5040 : charging ? 0xff80ff80 : Color.WHITE);
        c.drawRect(x + inset, y + inset, x + inset + full * Math.max(0, Math.min(100, batteryPercent)) / 100f, y + bh - inset, box);
        box.setColor(Color.WHITE);
    }
    private void drawHouse(Canvas c, float cx, float cy, float r, int color) {
        Path p = new Path();
        p.moveTo(cx, cy - r); p.lineTo(cx + r, cy - r * 0.05f); p.lineTo(cx + r * 0.7f, cy - r * 0.05f); p.lineTo(cx + r * 0.7f, cy + r * 0.8f);
        p.lineTo(cx - r * 0.7f, cy + r * 0.8f); p.lineTo(cx - r * 0.7f, cy - r * 0.05f); p.lineTo(cx - r, cy - r * 0.05f); p.close();
        box.setColor(color); c.drawPath(p, box);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            if (yesBox.contains(x, y)) { yesSelected = true; invalidate(); answer(true); }
            else if (noBox.contains(x, y)) { yesSelected = false; invalidate(); answer(false); }
        }
        return true; // the game below gets no touches while the screen is up
    }
}
