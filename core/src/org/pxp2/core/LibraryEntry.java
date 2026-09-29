package org.pxp2.core;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * One game the host offers to the XMB besides the Memory Stick's own PSP/GAME folders: a local ISO (PXP/ISO), an ISO
 * on the PC hub (streamed with HTTP Range requests) or a homebrew package on the hub (downloaded when launched).
 * The host hands the list to {@link PspSystem#setLibrary}; {@link GameLibrary} turns each into a virtual XMB entry.
 */
public final class LibraryEntry {
    public enum Kind { LOCAL_ISO, SERVER_ISO, SERVER_HOMEBREW }
    public final Kind kind;
    /** Display name from the host (file name without extension); the XMB shows the game's own TITLE. */
    public final String name;
    /** LOCAL_ISO: the image file. */
    public final File file;
    /** Server entries: hub base URL ("http://192.168.0.2:8080"), catalog category ("games", "homebrew"), file name, size. */
    public final String hubUrl, category, fileName; public final long size;
    /** SERVER_HOMEBREW served as a folder (the hub zips it on download). */
    public final boolean isDir;

    private LibraryEntry(Kind kind, String name, File file, String hubUrl, String category, String fileName, long size, boolean isDir) {
        this.kind = kind; this.name = name; this.file = file; this.hubUrl = hubUrl == null ? null : hubUrl.replaceAll("/+$", "");
        this.category = category; this.fileName = fileName; this.size = size; this.isDir = isDir;
    }
    public static LibraryEntry localIso(File image) { return new LibraryEntry(Kind.LOCAL_ISO, stem(image.getName()), image, null, null, image.getName(), image.length(), false); }
    public static LibraryEntry serverIso(String hubUrl, String category, String fileName, long size, String name) {
        return new LibraryEntry(Kind.SERVER_ISO, name != null ? name : stem(fileName), null, hubUrl, category, fileName, size, false);
    }
    public static LibraryEntry serverHomebrew(String hubUrl, String fileName, long size, boolean isDir, String name) {
        return new LibraryEntry(Kind.SERVER_HOMEBREW, name != null ? name : stem(fileName), null, hubUrl, "homebrew", fileName, size, isDir);
    }
    static String stem(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }

    /** The hub's /download URL for this entry, or for a path inside a folder entry ("DIR/EBOOT.PBP"). */
    public String downloadUrl(String inner) {
        try {
            String f = inner == null ? fileName : fileName + "/" + inner;
            return hubUrl + "/download?cat=" + URLEncoder.encode(category.toLowerCase(java.util.Locale.ROOT), "UTF-8") + "&file=" + URLEncoder.encode(f, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
    public String downloadUrl() { return downloadUrl(null); }
    /** Identity for the metadata cache: server file name + size + hub URL, or local path + size + modification time. */
    public String cacheKey() {
        return kind == Kind.LOCAL_ISO ? "local|" + file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified()
            : kind + "|" + hubUrl + "|" + category + "|" + fileName + "|" + size;
    }
    /** Opens the disc image: the local file, or an HTTP Range stream from the hub. */
    public IsoSource openIso() throws IOException {
        if (kind == Kind.LOCAL_ISO) return IsoSource.open(file);
        if (kind == Kind.SERVER_ISO) return IsoSource.wrap(new HttpIsoSource(downloadUrl(), size > 0 ? size : -1));
        throw new IOException("Not a disc image: " + fileName);
    }
    @Override public String toString() { return kind + " " + (file != null ? file.getPath() : hubUrl + " " + fileName); }
}
