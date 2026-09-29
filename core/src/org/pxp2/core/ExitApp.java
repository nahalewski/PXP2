package org.pxp2.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The "Exit to PXP2" entry in the XMB Game column: a tiny homebrew EBOOT.PBP that PXP2 (re)writes to
 * ms0:/PSP/GAME/PXP2_EXIT at every start. It is never executed — launching it is intercepted in HleVsh, which asks the
 * host to leave the emulator. The icon is supplied by the host app (its launcher icon); a generated one is the fallback.
 */
public final class ExitApp {
    public static final String FOLDER = "PXP2_EXIT", TITLE = "Exit to PXP2";
    private ExitApp() { }

    /** True for any path that refers to the exit app's executable (the XMB may pass ms0: or fatms0: forms). */
    public static boolean isExitPath(String path) { return path != null && path.toUpperCase(java.util.Locale.ROOT).contains("/PSP/GAME/" + FOLDER + "/"); }

    /** Writes PSP/GAME/PXP2_EXIT/EBOOT.PBP under the memory stick root when missing or different. */
    public static void install(File ms0, byte[] icon0Png) {
        try {
            byte[] pbp = pbp(sfo(), icon0Png != null ? icon0Png : defaultIcon());
            File dir = new File(ms0, "PSP/GAME/" + FOLDER), file = new File(dir, "EBOOT.PBP");
            if (file.isFile() && file.length() == pbp.length && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(file.toPath()), pbp)) return;
            dir.mkdirs();
            try (OutputStream o = new FileOutputStream(file)) { o.write(pbp); }
        } catch (IOException ignored) { } // a read-only stick just means no exit entry
    }

    private static byte[] sfo() {
        Object[][] entries = {{"BOOTABLE", 1}, {"CATEGORY", "MG"}, {"DISC_ID", "PXP200001"}, {"DISC_VERSION", "1.00"}, {"PARENTAL_LEVEL", 1}, {"PSP_SYSTEM_VER", "1.00"},
            {"REGION", 0x8000}, {"TITLE", TITLE}};
        ByteArrayOutputStream keys = new ByteArrayOutputStream(), data = new ByteArrayOutputStream();
        ByteBuffer index = ByteBuffer.allocate(16 * entries.length).order(ByteOrder.LITTLE_ENDIAN);
        for (Object[] e : entries) {
            byte[] raw; int fmt, used;
            if (e[1] instanceof Integer) { raw = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((Integer)e[1]).array(); fmt = 0x0404; used = 4; }
            else { byte[] t = ((String)e[1]).getBytes(StandardCharsets.UTF_8); used = t.length + 1; raw = java.util.Arrays.copyOf(t, (used + 3) & ~3); fmt = 0x0204; }
            index.putShort((short)keys.size()).putShort((short)fmt).putInt(used).putInt(raw.length).putInt(data.size());
            byte[] k = ((String)e[0]).getBytes(StandardCharsets.US_ASCII); keys.write(k, 0, k.length); keys.write(0);
            data.write(raw, 0, raw.length);
        }
        while ((keys.size() & 3) != 0) keys.write(0);
        int keyOff = 20 + index.capacity(), dataOff = keyOff + keys.size();
        ByteBuffer out = ByteBuffer.allocate(dataOff + data.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{0, 'P', 'S', 'F'}).putInt(0x0101).putInt(keyOff).putInt(dataOff).putInt(entries.length);
        out.put(index.array()).put(keys.toByteArray()).put(data.toByteArray());
        return out.array();
    }

    private static byte[] pbp(byte[] sfo, byte[] icon0) { return pbp(sfo, icon0, null, null, null, null); }
    /**
     * Builds an EBOOT.PBP whose DATA.PSP is a 512-byte "~PSP" stub. vshmain reads 512 bytes of DATA.PSP and requires a
     * "~PSP" (or "~SCE") header before it will launch; the body is never executed because the launch is intercepted.
     * Null sections are left empty.
     */
    static byte[] pbp(byte[] sfo, byte[] icon0, byte[] icon1, byte[] pic0, byte[] pic1, byte[] snd0) {
        byte[] dataPsp = new byte[512]; System.arraycopy(new byte[]{'~', 'P', 'S', 'P'}, 0, dataPsp, 0, 4);
        byte[] none = new byte[0];
        byte[][] sections = {sfo, icon0 == null ? none : icon0, icon1 == null ? none : icon1, pic0 == null ? none : pic0, pic1 == null ? none : pic1, snd0 == null ? none : snd0, dataPsp, none};
        int total = 40; for (byte[] s : sections) total += s.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{0, 'P', 'B', 'P'}).putInt(0x00010000);
        int at = 40; for (byte[] s : sections) { b.putInt(at); at += s.length; }
        for (byte[] s : sections) b.put(s);
        return b.array();
    }

    /** 144×80 fallback icon: a dark gradient with a white "power/exit" ring (no font needed). */
    static byte[] defaultIcon() {
        int w = 144, h = 80; byte[] raw = new byte[h * (1 + w * 4)];
        for (int y = 0, p = 0; y < h; y++) {
            raw[p++] = 0;
            for (int x = 0; x < w; x++) {
                double dx = x - 72, dy = y - 40, r = Math.sqrt(dx * dx + dy * dy);
                boolean ring = r > 20 && r < 26 && !(dy < 0 && Math.abs(dx) < 7), bar = Math.abs(dx) < 3 && dy > -30 && dy < -6;
                int g = 40 + y / 2;
                raw[p++] = (byte)(ring || bar ? 255 : 90 + x / 3); raw[p++] = (byte)(ring || bar ? 255 : g); raw[p++] = (byte)(ring || bar ? 255 : 160); raw[p++] = (byte)255;
            }
        }
        Deflater d = new Deflater(); d.setInput(raw); d.finish(); byte[] z = new byte[raw.length + 64]; int n = d.deflate(z); d.end();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, 8);
        chunk(png, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(new byte[]{8, 6, 0, 0, 0}).array());
        chunk(png, "IDAT", java.util.Arrays.copyOf(z, n)); chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream o, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII); CRC32 crc = new CRC32(); crc.update(t); crc.update(data);
        o.write(ByteBuffer.allocate(4).putInt(data.length).array(), 0, 4); o.write(t, 0, 4); o.write(data, 0, data.length);
        o.write(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array(), 0, 4);
    }
}
