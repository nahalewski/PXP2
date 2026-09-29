package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Original bounded PBP container reader. This does not decrypt PSAR/PRX content. */
public final class PbpPackage {
    public static final long SONY_661_SIZE = 32580549;
    public static final String SONY_661_SHA256 = "dc23a6dabdaed40bbfaad811d1f170346b911ba9750478fe5928d9f10c94f552";
    private static final String[] NAMES = {"PARAM.SFO", "ICON0.PNG", "ICON1.PMF", "PIC0.PNG",
        "PIC1.PNG", "SND0.AT3", "DATA.PSP", "DATA.PSAR"};

    public static final class Section {
        public final String name;
        public final long offset, size;
        private Section(String name, long offset, long size) { this.name = name; this.offset = offset; this.size = size; }
    }
    public static List<Section> inspect(RandomAccessFile input) throws IOException {
        long length = input.length();
        if (length < 40 || length > 128L * 1024 * 1024) throw new IOException("PBP length outside supported range");
        input.seek(0);
        if (read32(input) != 0x50425000L) throw new IOException("Invalid PBP signature");
        if (read32(input) != 0x00010000L) throw new IOException("Unsupported PBP version");
        long[] offsets = new long[9]; offsets[8] = length;
        for (int i = 0; i < 8; i++) {
            offsets[i] = read32(input);
            if (offsets[i] < 40 || offsets[i] > length || (i > 0 && offsets[i] < offsets[i-1]))
                throw new IOException("Invalid PBP section offset: " + NAMES[i]);
        }
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < 8; i++) sections.add(new Section(NAMES[i], offsets[i], offsets[i+1] - offsets[i]));
        return Collections.unmodifiableList(sections);
    }
    private static long read32(RandomAccessFile input) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder();
        for (byte b : value) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    private static String hash(RandomAccessFile input, long start, long length, OutputStream output) throws IOException {
        input.seek(start); MessageDigest hash = digest(); byte[] buffer = new byte[65536];
        while (length > 0) {
            int count = input.read(buffer, 0, (int)Math.min(buffer.length, length));
            if (count <= 0) throw new EOFException("Truncated PBP data");
            hash.update(buffer, 0, count);
            if (output != null) output.write(buffer, 0, count);
            length -= count;
        }
        return hex(hash.digest());
    }
    public static final class Extraction {
        public final File directory;
        public final String report;
        private Extraction(File directory, String report) { this.directory = directory; this.report = report; }
    }
    public static Extraction extractSony661(File source, File parent) throws IOException {
        return extractVerified(source, parent, SONY_661_SIZE, SONY_661_SHA256);
    }
    /** Exact source identity is supplied by the caller; Android uses only the pinned Sony identity. */
    public static Extraction extractVerified(File source, File parent, long expectedSize, String expectedHash) throws IOException {
        if (!expectedHash.matches("[a-f0-9]{64}")) throw new IOException("Invalid expected digest");
        Path base = parent.toPath(); Files.createDirectories(base);
        Path destination = base.resolve("package-" + expectedHash);
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            verifySource(input, expectedSize, expectedHash);
            List<Section> sections = inspect(input);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                verifyExtracted(input, destination, sections);
                verifySource(input, expectedSize, expectedHash);
                return new Extraction(destination.toFile(), report(sections));
            }
            Path stage = Files.createTempDirectory(base, ".extract-");
            try {
                StringBuilder manifest = new StringBuilder("PXP2 PBP package extraction\nsource_sha256=" + expectedHash + "\n");
                for (Section section : sections) {
                    if (section.size == 0) continue;
                    File file = stage.resolve(section.name).toFile();
                    String checksum;
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        checksum = hash(input, section.offset, section.size, output); output.getFD().sync();
                    }
                    manifest.append(section.name).append(' ').append(section.size).append(' ').append(checksum).append('\n');
                }
                verifySource(input, expectedSize, expectedHash);
                verifyExtracted(input, stage, sections);
                manifest.append("state=CONTAINER_EXTRACTED\nfirmware_installed=false\n");
                try (FileOutputStream out = new FileOutputStream(stage.resolve("manifest.txt").toFile())) {
                    out.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
                }
                // Same-filesystem directory publication; no partially extracted package is selected.
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
                return new Extraction(destination.toFile(), report(sections));
            } finally {
                // Only the fixed-name files in this operation's private staging directory are removed.
                if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                    for (String name : NAMES) Files.deleteIfExists(stage.resolve(name));
                    Files.deleteIfExists(stage.resolve("manifest.txt")); Files.delete(stage);
                }
            }
        }
    }
    private static void verifySource(RandomAccessFile input, long size, String checksum) throws IOException {
        if (input.length() != size || !hash(input, 0, size, null).equals(checksum))
            throw new IOException("Update size/SHA-256 mismatch; extraction refused");
    }
    private static void verifyExtracted(RandomAccessFile input, Path directory, List<Section> sections) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid extraction directory");
        for (Section section : sections) {
            Path path = directory.resolve(section.name);
            if (section.size == 0) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected empty-section file");
                continue;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != section.size)
                throw new IOException("Incomplete extraction: " + section.name);
            try (RandomAccessFile extracted = new RandomAccessFile(path.toFile(), "r")) {
                if (!hash(input, section.offset, section.size, null).equals(hash(extracted, 0, section.size, null)))
                    throw new IOException("Extracted file changed: " + section.name);
            }
        }
    }
    private static String report(List<Section> sections) {
        StringBuilder result = new StringBuilder("PBP container extracted and verified:\n");
        for (Section s : sections) if (s.size > 0) result.append(s.name).append(": ").append(s.size).append(" bytes\n");
        return result.append("Encrypted contents preserved. PSP flash files are not yet decrypted or installed.").toString();
    }
}
