import org.pxp2.core.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Streaming and library checks without the firmware: HttpIsoSource Range reads and chunk cache against a local HTTP
 * server, Iso9660 over HTTP, the hub catalog parser, and that a dead hub turns into IOExceptions (not a crash).
 * Usage: java LibraryTest local-test/test-umd.iso
 */
public final class LibraryTest {
    static int failures;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; }

    public static void main(String[] args) throws Exception {
        byte[] iso = Files.readAllBytes(new File(args.length > 0 ? args[0] : "local-test/test-umd.iso").toPath());
        byte[] big = new byte[3 * 1024 * 1024 + 12345]; new Random(1).nextBytes(big);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] rangeRequests = {0};
        server.createContext("/download", ex -> {
            String q = ex.getRequestURI().getQuery(); byte[] body = q.contains("big") ? big : iso;
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range == null) { ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close(); return; }
            rangeRequests[0]++;
            String[] r = range.substring(6).split("-"); int a = Integer.parseInt(r[0]), b = Math.min(body.length - 1, Integer.parseInt(r[1]));
            ex.getResponseHeaders().add("Content-Range", "bytes " + a + "-" + b + "/" + body.length);
            ex.sendResponseHeaders(206, b - a + 1); ex.getResponseBody().write(body, a, b - a + 1); ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        HttpIsoSource s = new HttpIsoSource(base + "/download?cat=games&file=big", -1);
        check(s.length() == big.length, "length from Content-Range: " + s.length());
        Random rnd = new Random(2); boolean same = true;
        for (int i = 0; i < 200; i++) {
            int off = rnd.nextInt(big.length), n = 1 + rnd.nextInt(300000); byte[] out = new byte[n];
            int got = s.read(off, out, 0, n); int want = Math.min(n, big.length - off);
            if (got != want || !Arrays.equals(Arrays.copyOfRange(out, 0, got), Arrays.copyOfRange(big, off, off + got))) same = false;
        }
        check(same, "200 random reads match the file (" + s + ")");
        check(s.requests <= 200 && s.hits > 0, "chunk cache serves repeated reads");
        check(s.read(big.length, new byte[10], 0, 10) == 0, "read at the end returns 0");

        Iso9660 disc = new Iso9660(new HttpIsoSource(base + "/download?cat=games&file=test.iso", iso.length));
        check("PXPT00001".equals(disc.discId()), "Iso9660 over HTTP: DISC_ID " + disc.discId());
        check(disc.find("/PSP_GAME/ICON0.PNG") != null, "Iso9660 over HTTP: finds PSP_GAME/ICON0.PNG");

        String json = "{\"games\": [{\"name\": \"A\", \"filename\": \"A \\\"x\\\".iso\", \"category\": \"GAMES\", \"size\": 81920},"
            + " {\"name\": \"C\", \"filename\": \"C.cso\", \"size\": 5}], \"videos\": [{\"filename\": \"V.iso\", \"size\": 1}],"
            + " \"homebrew\": [{\"name\": \"H\", \"filename\": \"H.zip\", \"size\": 9}, {\"name\": \"D\", \"filename\": \"D\", \"size\": 9, \"is_dir\": true}, {\"filename\": \"x.7z\", \"size\": 1}]}";
        List<LibraryEntry> cat = GameLibrary.parseCatalog("http://hub:8080/", json);
        check(cat.size() == 3 && cat.get(0).fileName.equals("A \"x\".iso") && cat.get(2).isDir, "catalog: ISO games + zip/folder homebrew (" + cat + ")");
        check(cat.get(0).downloadUrl().equals("http://hub:8080/download?cat=games&file=A%20%22x%22.iso"), "download URL: " + cat.get(0).downloadUrl());

        // A dead hub: reads of chunks not in the cache fail with IOException after the retries, and nothing hangs.
        HttpIsoSource dead = new HttpIsoSource(base + "/download?cat=games&file=big", big.length);
        dead.read(0, new byte[16], 0, 16);
        server.stop(0);
        long t0 = System.nanoTime(); boolean threw = false;
        try { dead.read(2 * 1024 * 1024, new byte[16], 0, 16); } catch (IOException e) { threw = true; System.out.println("     " + e.getMessage()); }
        check(threw, String.format(Locale.ROOT, "dead hub -> IOException after %.1f s", (System.nanoTime() - t0) / 1e9));
        byte[] first = new byte[16]; dead.read(0, first, 0, 16);
        check(Arrays.equals(first, Arrays.copyOf(big, 16)), "cached chunks still readable after the hub went away");
        System.out.println(failures == 0 ? "LibraryTest passed" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
