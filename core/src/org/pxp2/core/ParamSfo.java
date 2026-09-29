package org.pxp2.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** PARAM.SFO (PSF) reader and writer. Values are String (fmt 0x0204 UTF-8, 0x0004 raw) or Integer (0x0404). */
public final class ParamSfo {
    private ParamSfo() { }

    /** Parses a PSF file into key → value, in file order. */
    public static LinkedHashMap<String, Object> parse(byte[] b) throws IOException {
        if (b == null || b.length < 20 || b[0] != 0 || b[1] != 'P' || b[2] != 'S' || b[3] != 'F') throw new IOException("Not a PARAM.SFO");
        ByteBuffer in = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int keyOff = in.getInt(8), dataOff = in.getInt(12), count = in.getInt(16);
        if (count < 0 || count > 256 || 20 + count * 16 > b.length) throw new IOException("Bad PARAM.SFO index");
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int p = 20 + i * 16, kp = keyOff + (in.getShort(p) & 0xffff), fmt = in.getShort(p + 2) & 0xffff, used = in.getInt(p + 4), dp = dataOff + in.getInt(p + 12);
            if (kp < 0 || kp >= b.length || dp < 0 || used < 0 || dp + used > b.length) throw new IOException("Bad PARAM.SFO entry");
            int ke = kp; while (ke < b.length && b[ke] != 0) ke++;
            String key = new String(b, kp, ke - kp, StandardCharsets.US_ASCII);
            if (fmt == 0x0404 && used >= 4) out.put(key, in.getInt(dp));
            else { int n = used; while (n > 0 && b[dp + n - 1] == 0) n--; out.put(key, new String(b, dp, n, StandardCharsets.UTF_8)); }
        }
        return out;
    }
    public static String string(Map<String, Object> sfo, String key) { Object v = sfo.get(key); return v instanceof String ? (String)v : null; }

    /** Writes a PSF 1.1 file; keys are sorted as the firmware's tools do. */
    public static byte[] build(Map<String, Object> values) {
        TreeMap<String, Object> sorted = new TreeMap<>(values);
        ByteArrayOutputStream keys = new ByteArrayOutputStream(), data = new ByteArrayOutputStream();
        ByteBuffer index = ByteBuffer.allocate(16 * sorted.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            byte[] raw; int fmt, used;
            if (e.getValue() instanceof Integer) { raw = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((Integer)e.getValue()).array(); fmt = 0x0404; used = 4; }
            else {
                byte[] t = String.valueOf(e.getValue()).getBytes(StandardCharsets.UTF_8); used = t.length + 1;
                int max = e.getKey().equals("TITLE") ? 128 : e.getKey().equals("DISC_ID") ? 16 : (used + 3) & ~3; // conventional field sizes
                raw = Arrays.copyOf(t, Math.max(max, (used + 3) & ~3)); fmt = 0x0204;
            }
            index.putShort((short)keys.size()).putShort((short)fmt).putInt(used).putInt(raw.length).putInt(data.size());
            byte[] k = e.getKey().getBytes(StandardCharsets.US_ASCII); keys.write(k, 0, k.length); keys.write(0);
            data.write(raw, 0, raw.length);
        }
        while ((keys.size() & 3) != 0) keys.write(0);
        int keyOff = 20 + index.capacity(), dataOff = keyOff + keys.size();
        ByteBuffer out = ByteBuffer.allocate(dataOff + data.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{0, 'P', 'S', 'F'}).putInt(0x0101).putInt(keyOff).putInt(dataOff).putInt(sorted.size());
        out.put(index.array()).put(keys.toByteArray()).put(data.toByteArray());
        return out.array();
    }
}
