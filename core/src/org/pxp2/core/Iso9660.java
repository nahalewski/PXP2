package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Original read-only ISO 9660 reader for UMD images (2048-byte sectors). Paths are resolved case-insensitively
 * from the root directory of the primary volume descriptor. All reads are bounds-checked against the image. The bytes
 * come from an {@link IsoSource}: a local file or an image streamed from the PC hub over HTTP.
 */
public final class Iso9660 implements Closeable {
    public static final int SECTOR = 2048;
    public static final class Entry {
        public final String name; public final long sector, size; public final boolean directory;
        Entry(String name, long sector, long size, boolean directory) { this.name = name; this.sector = sector; this.size = size; this.directory = directory; }
    }
    private final IsoSource file; public final long sectors; private final Entry root;
    public final String volumeId;

    /** Opens a local .iso, .chd, .cso or .zso image. */
    public Iso9660(File image) throws IOException { this(IsoSource.open(image)); }
    public Iso9660(IsoSource source) throws IOException {
        file = source; sectors = file.length() / SECTOR;
        byte[] pvd;
        try { pvd = readSectors(16, 1); } catch (IOException e) { file.close(); throw e; }
        if (pvd[0] != 1 || pvd[1] != 'C' || pvd[2] != 'D' || pvd[3] != '0' || pvd[4] != '0' || pvd[5] != '1') { file.close(); throw new IOException("Not an ISO 9660 image"); }
        volumeId = new String(pvd, 40, 32, StandardCharsets.US_ASCII).trim();
        root = record(pvd, 156);
    }
    private static long le32(byte[] b, int at) { return (b[at] & 255L) | ((b[at + 1] & 255L) << 8) | ((b[at + 2] & 255L) << 16) | ((b[at + 3] & 255L) << 24); }
    private static Entry record(byte[] b, int at) {
        int nameLength = b[at + 32] & 255; String name = new String(b, at + 33, nameLength, StandardCharsets.ISO_8859_1);
        int semicolon = name.indexOf(';'); if (semicolon >= 0) name = name.substring(0, semicolon);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return new Entry(name, le32(b, at + 2), le32(b, at + 10), (b[at + 25] & 2) != 0);
    }
    public synchronized byte[] readSectors(long first, int count) throws IOException {
        if (first < 0 || count < 0 || first + count > sectors) throw new IOException("UMD read outside the image");
        byte[] out = new byte[count * SECTOR];
        if (file.read(first * SECTOR, out, 0, out.length) != out.length) throw new IOException("Short UMD read");
        return out;
    }
    /** Reads {@code length} bytes at byte {@code offset} of the image (fewer only at the end of the image). */
    public int read(long offset, byte[] out, int at, int length) throws IOException { return file.read(offset, out, at, length); }
    /** The byte source (local file or HTTP stream). */
    public IsoSource source() { return file; }
    private String discId; private boolean discIdRead;
    /** DISC_ID from PSP_GAME/PARAM.SFO (e.g. "ULUS10081"), or null for discs without one (UMD Video, unreadable). */
    public synchronized String discId() {
        if (!discIdRead) {
            discIdRead = true;
            try { Entry e = find("/PSP_GAME/PARAM.SFO"); if (e != null && e.size <= 65536) discId = ParamSfo.string(ParamSfo.parse(readFile(e)), "DISC_ID"); }
            catch (IOException | RuntimeException ignored) { }
        }
        return discId;
    }
    public List<Entry> list(Entry directory) throws IOException {
        if (!directory.directory) throw new IOException("Not a directory");
        List<Entry> out = new ArrayList<>(); int sectorsInDir = (int)((directory.size + SECTOR - 1) / SECTOR);
        byte[] d = readSectors(directory.sector, sectorsInDir);
        for (int i = 0; i < d.length; ) {
            int length = d[i] & 255;
            if (length == 0) { i = (i / SECTOR + 1) * SECTOR; continue; } // records never cross sector boundaries
            if (i + 33 > d.length || i + length > d.length) break;
            int nameLength = d[i + 32] & 255;
            if (!(nameLength == 1 && (d[i + 33] == 0 || d[i + 33] == 1))) out.add(record(d, i)); // skip "." and ".."
            i += length;
        }
        return out;
    }
    public Entry root() { return root; }
    /** Resolves "/PSP_GAME/SYSDIR/EBOOT.BIN" style paths; returns null when absent. */
    public Entry find(String path) throws IOException {
        Entry e = root;
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            Entry next = null; if (!e.directory) return null;
            for (Entry c : list(e)) if (c.name.equalsIgnoreCase(part)) { next = c; break; }
            if (next == null) return null; e = next;
        }
        return e;
    }
    public byte[] readFile(Entry e) throws IOException {
        if (e.size > 64L * 1024 * 1024) throw new IOException("File too large to read whole: " + e.name);
        byte[] out = new byte[(int)e.size]; read(e.sector * SECTOR, out, 0, out.length); return out;
    }
    @Override public void close() throws IOException { file.close(); }
}
