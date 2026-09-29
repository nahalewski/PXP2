package org.pxp2.core;

import java.io.*;

/**
 * Random-access byte source behind a UMD image: a local file, or an HTTP URL read with Range requests
 * ({@link HttpIsoSource}). {@link Iso9660} reads through this interface only, so a disc can be streamed from the PC hub.
 */
public interface IsoSource extends Closeable {
    /** Total size of the image in bytes. */
    long length();
    /** Reads up to {@code length} bytes at {@code offset}; returns the count read (0 at or past the end). */
    int read(long offset, byte[] out, int at, int length) throws IOException;
    /** Short description for logs ("file:/x.iso", "http://hub/..."). */
    String describe();

    /** Opens a local path, or an http(s) URL as a streamed image; CHD, CSO and ZSO images are detected by their magic. */
    static IsoSource open(String pathOrUrl) throws IOException {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return wrap(new HttpIsoSource(pathOrUrl, -1));
        return wrap(new FileSource(new File(pathOrUrl)));
    }
    /** Opens a local image file (.iso, .chd, .cso or .zso: the content decides). */
    static IsoSource open(File image) throws IOException { return wrap(new FileSource(image)); }
    /**
     * Returns {@code raw} itself for a plain image, or a decoding view of it when it starts with the CHD magic
     * ("MComprHD", {@link ChdSource}) or "CISO"/"ZISO" ({@link CsoSource}). The view owns {@code raw}.
     */
    static IsoSource wrap(IsoSource raw) throws IOException {
        byte[] magic = new byte[8]; int n;
        try { n = raw.length() >= 8 ? raw.read(0, magic, 0, 8) : 0; }
        catch (IOException e) { try { raw.close(); } catch (IOException ignored) { } throw e; }
        if (n == 8 && magic[0] == 'M' && ChdSource.MAGIC.equals(new String(magic, java.nio.charset.StandardCharsets.ISO_8859_1))) return new ChdSource(raw);
        if (n >= 4 && (magic[0] == 'C' || magic[0] == 'Z') && magic[1] == 'I' && magic[2] == 'S' && magic[3] == 'O') return new CsoSource(raw);
        return raw;
    }
    /** File names PXP2 treats as UMD images: .iso, .chd, .cso and .zso. */
    static boolean isImageName(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".iso") || n.endsWith(".chd") || n.endsWith(".cso") || n.endsWith(".zso");
    }

    /** A local image file. */
    final class FileSource implements IsoSource {
        private final RandomAccessFile file; private final long length; private final String name;
        public FileSource(File image) throws IOException { file = new RandomAccessFile(image, "r"); length = file.length(); name = image.getPath(); }
        @Override public long length() { return length; }
        @Override public synchronized int read(long offset, byte[] out, int at, int n) throws IOException {
            if (offset < 0 || offset >= length || n <= 0) return 0;
            n = (int)Math.min(n, length - offset); file.seek(offset); file.readFully(out, at, n); return n;
        }
        @Override public String describe() { return name; }
        @Override public void close() throws IOException { file.close(); }
    }
}
