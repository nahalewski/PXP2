package org.pxp2.fresh;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import java.io.File;
import java.io.FileOutputStream;

/**
 * App chrome that follows the PSP's own XMB theme. The colour is the XMB theme colour index kept by
 * {@link LauncherIconManager} (updated from the emulated registry while the XMB runs), and the backdrop is a blurred
 * snapshot of the XMB taken when the emulator is left — so Original, Classic, custom PTF and CTF themes all carry over.
 */
final class XmbTheme {
    private XmbTheme() { }
    /** Per colour index (same order as LauncherIconManager.COLOR_NAMES): gradient top, middle, bottom, accent. */
    private static final int[][] PALETTE = {
        {0xff26262a, 0xff45454c, 0xff111114, 0xffc8c8d0}, // Black
        {0xff6f7482, 0xffa9aebb, 0xff3d414b, 0xffffffff}, // White
        {0xff5a0f14, 0xffa3242c, 0xff33080b, 0xffff7a82}, // Red
        {0xff0f2c5c, 0xff2456a8, 0xff081a38, 0xff7fb2ff}, // Blue
        {0xff5e1a45, 0xffb0457f, 0xff360d27, 0xffff9fd2}, // Pink
        {0xff1a4f6e, 0xff3f93c0, 0xff0d2e42, 0xff9fe0ff}, // Light blue
        {0xff1f4a1c, 0xff4c8a3a, 0xff102a0e, 0xffa8e58f}, // Green
        {0xff5e4d0c, 0xffab9020, 0xff352b05, 0xffffe070}, // Yellow
        {0xff3a0f55, 0xff7a2a9c, 0xff2a0a3d, 0xffb46bff}, // Purple (the original PXP2 look)
        {0xff4a4f58, 0xff858c98, 0xff2a2d33, 0xffe0e6f0}, // Silver
        {0xff5a2e0c, 0xffa65a1f, 0xff331905, 0xffffb070}, // Orange / bronze
        {0xff0c4a4a, 0xff1f8a86, 0xff052b2b, 0xff7fe8e0}, // Teal
    };
    private static int index(Context c) {
        int i = c.getSharedPreferences("pxp2", Context.MODE_PRIVATE).getInt(LauncherIconManager.PREF_LAST_COLOR, 8);
        return i >= 0 && i < PALETTE.length ? i : 8;
    }
    static int accent(Context c) { return PALETTE[index(c)][3]; }
    static int statusBar(Context c) { return PALETTE[index(c)][2]; }
    /** A key that changes whenever the theme the UI should show changes (colour or saved wallpaper). */
    static String signature(Context c) { File w = wallpaperFile(c); return index(c) + ":" + (w.isFile() ? w.lastModified() : 0); }

    /** The app background: the blurred XMB wallpaper under a colour-tinted scrim, or the theme gradient alone. */
    static Drawable background(Context c) {
        int[] p = PALETTE[index(c)];
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{p[0], p[1], p[2]});
        Bitmap wall = null; File f = wallpaperFile(c);
        if (f.isFile()) try { wall = BitmapFactory.decodeFile(f.getPath()); } catch (Throwable ignored) { }
        if (wall == null) return gradient;
        BitmapDrawable image = new BitmapDrawable(c.getResources(), wall); image.setFilterBitmap(true);
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{(p[0] & 0x00ffffff) | 0x66000000, (p[2] & 0x00ffffff) | 0xcc000000});
        return new LayerDrawable(new Drawable[]{gradient, new CoverDrawable(image), scrim});
    }

    static File wallpaperFile(Context c) { return new File(c.getFilesDir(), "xmb_wallpaper.png"); }
    /** Saves a soft-blurred copy of the current XMB frame (icons and text blur away, the theme's backdrop remains). */
    static void saveWallpaper(Context c, Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;
        try {
            Bitmap small = Bitmap.createScaledBitmap(frame, 30, 17, true); // heavy downscale = cheap wide blur
            Bitmap out = Bitmap.createScaledBitmap(small, 240, 136, true);
            File tmp = new File(c.getFilesDir(), "xmb_wallpaper.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { out.compress(Bitmap.CompressFormat.PNG, 100, o); }
            if (!tmp.renameTo(wallpaperFile(c))) { wallpaperFile(c).delete(); tmp.renameTo(wallpaperFile(c)); }
        } catch (Throwable ignored) { }
    }

    /** Draws a bitmap scaled to cover its bounds (centre-cropped), like CSS background-size: cover. */
    private static final class CoverDrawable extends Drawable {
        private final BitmapDrawable image;
        CoverDrawable(BitmapDrawable image) { this.image = image; }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds(); Bitmap bmp = image.getBitmap(); if (bmp == null || b.isEmpty()) return;
            float scale = Math.max(b.width() / (float)bmp.getWidth(), b.height() / (float)bmp.getHeight());
            float w = bmp.getWidth() * scale, h = bmp.getHeight() * scale;
            RectF dst = new RectF(b.centerX() - w / 2, b.centerY() - h / 2, b.centerX() + w / 2, b.centerY() + h / 2);
            canvas.save(); canvas.clipRect(b); canvas.drawBitmap(bmp, null, dst, image.getPaint()); canvas.restore();
        }
        @Override public void setAlpha(int alpha) { image.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { image.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
