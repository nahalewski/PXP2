import org.pxp2.core.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Deflater;

/**
 * Compressed UMD images: every sector of each CHD (and a CSO and a ZSO made here) must decode byte for byte to its
 * reference ISO (the stripped ISO the CHD was made from), locally and streamed over HTTP; corrupt and truncated images
 * must fail with IOException (never another exception); decode throughput is printed.
 *
 * Usage: java ChdTest image.chd reference.iso [image2.chd reference2.iso ...]
 * Options: -Dhttp=N streams the first N MiB of each CHD over a local HTTP server (default 64; 0 = off),
 *          -Dcorrupt=0 skips the corruption tests, -Dcso=0 skips the CSO/ZSO round trip.
 */
public final class ChdTest {
    static int failures;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length % 2 != 0) { System.err.println("usage: ChdTest image.chd reference.iso [...]"); System.exit(2); }
        for (int i = 0; i < args.length; i += 2) {
            File chd = new File(args[i]), ref = new File(args[i + 1]);
            System.out.println("== " + chd.getName() + " vs " + ref.getName());
            fullCompare(chd, ref);
            randomCompare(chd, ref);
            int httpMiB = Integer.getInteger("http", 64);
            if (httpMiB > 0) httpCompare(chd, ref, httpMiB);
            if (!"0".equals(System.getProperty("corrupt"))) corruption(chd);
            if (!"0".equals(System.getProperty("cso")) && ref.length() <= 512L * 1024 * 1024) csoRoundTrip(ref);
        }
        System.out.println(failures == 0 ? "ChdTest: all passed" : "ChdTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void fullCompare(File chdFile, File ref) throws IOException {
        try (IsoSource src = IsoSource.open(chdFile); RandomAccessFile iso = new RandomAccessFile(ref, "r")) {
            check(src instanceof ChdSource, "detected as CHD by its magic: " + src.describe());
            ChdSource chd = (ChdSource)src;
            check(chd.length() == iso.length(), "logical size " + chd.length() + " = ISO size " + iso.length());
            System.out.println("     " + chd.codecNames() + ", hunk " + chd.hunkBytes + ", unit " + chd.unitBytes + ", " + chd.hunkCount + " hunks, metadata " + chd.metadataTags);
            byte[] a = new byte[1 << 20], b = new byte[1 << 20]; long decodeNanos = 0, badSector = -1;
            for (long pos = 0; pos < iso.length(); pos += a.length) {
                int n = (int)Math.min(a.length, iso.length() - pos);
                long t0 = System.nanoTime(); int got = chd.read(pos, a, 0, n); decodeNanos += System.nanoTime() - t0;
                iso.seek(pos); iso.readFully(b, 0, n);
                if (got != n) { badSector = pos / 2048; break; }
                if (!Arrays.equals(a, 0, n, b, 0, n)) { for (int k = 0; k < n; k++) if (a[k] != b[k]) { badSector = (pos + k) / 2048; break; } break; }
            }
            double mib = iso.length() / 1048576.0, s = decodeNanos / 1e9;
            check(badSector < 0, String.format(Locale.ROOT, "every sector matches (%d sectors)%s", iso.length() / 2048, badSector >= 0 ? ", first mismatch at sector " + badSector : ""));
            System.out.printf(Locale.ROOT, "     sequential decode: %.1f MiB in %.2f s = %.1f MiB/s (%s)%n", mib, s, mib / s, chd);
            try (Iso9660 fs = new Iso9660(chdFile)) { check(fs.discId() != null || fs.find("/UMD_VIDEO") != null, "Iso9660 over the CHD: DISC_ID " + fs.discId()); }
        }
    }

    static void randomCompare(File chdFile, File ref) throws IOException {
        try (IsoSource chd = IsoSource.open(chdFile); RandomAccessFile iso = new RandomAccessFile(ref, "r")) {
            Random r = new Random(7); boolean same = true; long t0 = System.nanoTime(); int reads = 3000;
            for (int i = 0; i < reads; i++) {
                long off = (long)(r.nextDouble() * iso.length()); int n = 1 + r.nextInt(64 * 1024);
                byte[] a = new byte[n], b = new byte[n]; int got = chd.read(off, a, 0, n);
                int want = (int)Math.min(n, iso.length() - off); iso.seek(off); iso.readFully(b, 0, want);
                if (got != want || !Arrays.equals(a, 0, got, b, 0, got)) { same = false; System.out.println("     mismatch at " + off + "+" + n); break; }
            }
            check(same, String.format(Locale.ROOT, "%d random reads match (%.0f reads/s)", reads, reads / ((System.nanoTime() - t0) / 1e9)));
            check(chd.read(chd.length(), new byte[16], 0, 16) == 0, "read at the end returns 0");
        }
    }

    static void httpCompare(File chdFile, File ref, int mib) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download", ex -> {
            try (RandomAccessFile f = new RandomAccessFile(chdFile, "r")) {
                String range = ex.getRequestHeaders().getFirst("Range"); long len = f.length();
                String[] r = range.substring(6).split("-"); long a = Long.parseLong(r[0]), b = Math.min(len - 1, Long.parseLong(r[1]));
                byte[] body = new byte[(int)(b - a + 1)]; f.seek(a); f.readFully(body);
                ex.getResponseHeaders().add("Content-Range", "bytes " + a + "-" + b + "/" + len);
                ex.sendResponseHeaders(206, body.length); ex.getResponseBody().write(body);
            } finally { ex.close(); }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4)); server.start();
        try (IsoSource src = IsoSource.open("http://127.0.0.1:" + server.getAddress().getPort() + "/download?cat=chd&file=x.chd");
             RandomAccessFile iso = new RandomAccessFile(ref, "r")) {
            check(src instanceof ChdSource, "streamed image detected as CHD: " + src.describe());
            long limit = Math.min(iso.length(), (long)mib << 20); byte[] a = new byte[256 * 1024], b = new byte[a.length]; boolean same = true;
            long t0 = System.nanoTime();
            for (long pos = 0; pos < limit && same; pos += a.length) {
                int n = (int)Math.min(a.length, limit - pos); src.read(pos, a, 0, n); iso.seek(pos); iso.readFully(b, 0, n);
                same = Arrays.equals(a, 0, n, b, 0, n);
            }
            double s = (System.nanoTime() - t0) / 1e9;
            HttpIsoSource http = (HttpIsoSource)((ChdSource)src).raw();
            check(same, String.format(Locale.ROOT, "first %.0f MiB streamed over HTTP match (%.1f MiB/s; %s)", limit / 1048576.0, limit / 1048576.0 / s, http));
            try (Iso9660 fs = new Iso9660(IsoSource.open("http://127.0.0.1:" + server.getAddress().getPort() + "/download?cat=chd&file=x.chd"))) {
                check(fs.discId() != null || fs.find("/UMD_VIDEO") != null, "Iso9660 over a streamed CHD: DISC_ID " + fs.discId());
            }
        } finally { server.stop(0); }
    }

    /** Flipped bytes in the map and in hunk data, and a truncated copy: only IOExceptions, never another exception. */
    static void corruption(File chdFile) throws IOException {
        byte[] whole = chdFile.length() <= 64L * 1024 * 1024 ? Files.readAllBytes(chdFile.toPath()) : null;
        File tmp = File.createTempFile("pxp2-chd", ".chd"); tmp.deleteOnExit();
        long mapOffset, firstData, length = chdFile.length();
        try (RandomAccessFile f = new RandomAccessFile(chdFile, "r")) {
            f.seek(40); mapOffset = f.readLong(); f.seek(mapOffset + 4); byte[] b6 = new byte[6]; f.readFully(b6);
            firstData = 0; for (byte x : b6) firstData = firstData << 8 | (x & 255);
        }
        Random r = new Random(11); int cases = 0, clean = 0, io = 0, other = 0;
        long[][] targets = {{mapOffset + 16, mapOffset + 48}, {firstData, Math.min(length, firstData + 4 * 1024 * 1024)}, {0, 124}};
        for (long[] t : targets) for (int k = 0; k < 6; k++) {
            Files.copy(chdFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            long at = t[0] + (long)(r.nextDouble() * Math.max(1, t[1] - t[0]));
            try (RandomAccessFile f = new RandomAccessFile(tmp, "rw")) { f.seek(at); int v = f.read(); f.seek(at); f.write(v ^ (1 << r.nextInt(8))); }
            cases++;
            switch (readAll(tmp, 8L * 1024 * 1024)) { case 0: clean++; break; case 1: io++; break; default: other++; }
        }
        // truncated in the middle of the hunk data
        Files.copy(chdFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile f = new RandomAccessFile(tmp, "rw")) { f.setLength(firstData + (length - firstData) / 3); }
        int trunc = readAll(tmp, Long.MAX_VALUE); cases++;
        if (trunc == 1) io++; else if (trunc == 0) clean++; else other++;
        check(other == 0, "corruption: " + cases + " damaged copies -> " + io + " IOException, " + clean + " unaffected reads, " + other + " other exceptions");
        check(trunc == 1, "truncated CHD fails with IOException");
        tmp.delete();
        if (whole != null) whole[0] = 0;
    }
    /** 0 = read without error, 1 = IOException, 2 = anything else. Reads the first {@code limit} bytes. */
    static int readAll(File f, long limit) {
        try (IsoSource s = IsoSource.open(f)) {
            byte[] buf = new byte[1 << 20]; long end = Math.min(s.length(), limit);
            for (long p = 0; p < end; p += buf.length) s.read(p, buf, 0, (int)Math.min(buf.length, end - p));
            if (limit == Long.MAX_VALUE) return 0;
            return 0;
        } catch (IOException e) { return 1; }
        catch (Throwable t) { System.out.println("     unexpected: " + t); return 2; }
    }

    /** Writes a CSO v1 (raw deflate) and a ZSO (LZ4) of the reference and checks both decode to it. */
    static void csoRoundTrip(File ref) throws IOException {
        byte[] iso = Files.readAllBytes(ref.toPath());
        for (String kind : new String[]{"CISO", "ZISO"}) {
            File out = File.createTempFile("pxp2-" + kind, kind.equals("CISO") ? ".cso" : ".zso"); out.deleteOnExit();
            int bs = 2048, blocks = (iso.length + bs - 1) / bs;
            ByteArrayOutputStream data = new ByteArrayOutputStream(); long[] index = new long[blocks + 1];
            int headerAndIndex = 24 + 4 * (blocks + 1);
            for (int i = 0; i < blocks; i++) {
                index[i] = headerAndIndex + data.size();
                byte[] blk = Arrays.copyOfRange(iso, i * bs, Math.min(iso.length, (i + 1) * bs));
                byte[] c = kind.equals("CISO") ? deflate(blk) : lz4(blk);
                if (c.length >= blk.length) { data.write(blk); index[i] |= 0x80000000L; } else data.write(c);
            }
            index[blocks] = headerAndIndex + data.size();
            try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
                o.writeBytes(kind); o.writeInt(Integer.reverseBytes(24)); o.writeLong(Long.reverseBytes(iso.length)); o.writeInt(Integer.reverseBytes(bs));
                o.writeByte(1); o.writeByte(0); o.writeShort(0);
                for (long e : index) o.writeInt(Integer.reverseBytes((int)e));
                data.writeTo(o);
            }
            try (IsoSource s = IsoSource.open(out)) {
                byte[] back = new byte[iso.length]; int got = s.read(0, back, 0, back.length);
                check(s instanceof CsoSource && got == iso.length && Arrays.equals(back, iso), kind + " round trip: " + iso.length + " -> " + out.length() + " bytes, decodes identically");
            }
            out.delete();
        }
    }
    static byte[] deflate(byte[] b) { Deflater d = new Deflater(9, true); d.setInput(b); d.finish(); byte[] o = new byte[b.length * 2 + 64]; int n = d.deflate(o); d.end(); return Arrays.copyOf(o, n); }
    /** Greedy LZ4 block compressor for the test (hash of 4 bytes, last 5 bytes literal as the format requires). */
    static byte[] lz4(byte[] in) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); int[] table = new int[4096]; Arrays.fill(table, -1);
        int anchor = 0, p = 0, limit = in.length - 12;
        while (p < limit) {
            int h = ((in[p] & 255) | (in[p + 1] & 255) << 8 | (in[p + 2] & 255) << 16 | (in[p + 3] & 255) << 24) * -1640531535 >>> 20;
            int cand = table[h]; table[h] = p;
            if (cand >= 0 && p - cand < 65536 && in[cand] == in[p] && in[cand + 1] == in[p + 1] && in[cand + 2] == in[p + 2] && in[cand + 3] == in[p + 3]) {
                int ml = 4; while (p + ml < in.length - 5 && in[cand + ml] == in[p + ml]) ml++;
                writeSeq(o, in, anchor, p - anchor, p - cand, ml); p += ml; anchor = p;
            } else p++;
        }
        writeSeq(o, in, anchor, in.length - anchor, 0, 0);
        return o.toByteArray();
    }
    static void writeSeq(ByteArrayOutputStream o, byte[] in, int litStart, int lit, int offset, int ml) {
        int mlc = ml == 0 ? 0 : ml - 4, token = (Math.min(lit, 15) << 4) | (ml == 0 ? 0 : Math.min(mlc, 15));
        o.write(token);
        if (lit >= 15) { int x = lit - 15; while (x >= 255) { o.write(255); x -= 255; } o.write(x); }
        o.write(in, litStart, lit);
        if (ml == 0) return;
        o.write(offset & 255); o.write(offset >>> 8);
        if (mlc >= 15) { int x = mlc - 15; while (x >= 255) { o.write(255); x -= 255; } o.write(x); }
    }
}
