package org.pxp2.core;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/**
 * A disc image streamed from the PC hub ({@code pxp_server.py /download}) with HTTP Range requests.
 *
 * <p>The image is read in 256 KiB chunks kept in a small LRU cache (16 chunks, 4 MiB). When the guest reads chunks in
 * order, the next two are fetched ahead on a background thread, so sequential loads (the game executable, movies, music
 * streams) rarely wait on the network. A failed request is retried twice; after that the read throws an IOException,
 * which HleIo turns into an I/O error for the guest (the emulator keeps running).
 */
public final class HttpIsoSource implements IsoSource {
    public static final int CHUNK = 256 * 1024, CACHE_CHUNKS = 16, READ_AHEAD = 2;
    private static final ExecutorService prefetcher = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "PXP2 ISO read-ahead"); t.setDaemon(true); return t; });
    private final String url; private final long length; private final int chunkSize, cacheChunks, readAhead;
    private final Map<Long, byte[]> cache = new LinkedHashMap<Long, byte[]>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) { return size() > cacheChunks; }
    };
    private final Map<Long, CompletableFuture<byte[]>> inflight = new HashMap<>();
    private long lastChunk = -2; private volatile boolean closed;
    /** After a read has failed all its retries the hub counts as offline for a moment: reads fail at once, not after 15 s each. */
    private volatile long offlineUntil; static final long OFFLINE_NANOS = 3_000_000_000L;
    /** Statistics for logs and tests: HTTP requests made, bytes fetched, chunk cache hits and misses. */
    public volatile long requests, bytesFetched, hits, misses;

    /** @param length the image size when known (from the hub catalog), or -1 to ask the hub with a one-byte Range request. */
    public HttpIsoSource(String url, long length) throws IOException { this(url, length, CHUNK, CACHE_CHUNKS, READ_AHEAD); }
    /** Custom chunking: the metadata scanner uses small chunks and no read-ahead to fetch only what it parses. */
    public HttpIsoSource(String url, long length, int chunkSize, int cacheChunks, int readAhead) throws IOException {
        this.url = url; this.chunkSize = chunkSize; this.cacheChunks = cacheChunks; this.readAhead = readAhead;
        this.length = length >= 0 ? length : probeLength(url);
    }
    @Override public long length() { return length; }
    @Override public String describe() { return url; }

    private static HttpURLConnection connect(String url, long first, long last) throws IOException {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(15000); c.setRequestProperty("User-Agent", "PXP2");
        c.setRequestProperty("Range", "bytes=" + first + "-" + last);
        return c;
    }
    private static long probeLength(String url) throws IOException {
        HttpURLConnection c = connect(url, 0, 0);
        try {
            int code = c.getResponseCode(); String range = c.getHeaderField("Content-Range");
            drain(c);
            if (code == 206 && range != null && range.contains("/")) return Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
            if (code == 200 && c.getContentLengthLong() > 0) throw new IOException("The hub does not support Range requests (HTTP 200): " + url);
            throw new IOException("HTTP " + code + " for " + url);
        } finally { c.disconnect(); }
    }
    private static void drain(HttpURLConnection c) { try (InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) { if (in != null) while (in.read(new byte[4096]) > 0) { } } catch (IOException ignored) { } }

    /** One HTTP Range request for a whole chunk, with two retries. */
    private byte[] fetch(long index) throws IOException {
        long first = index * chunkSize, last = Math.min(length, first + chunkSize) - 1;
        IOException error = null;
        for (int attempt = 0; attempt < 3 && !closed; attempt++) {
            if (attempt > 0) try { Thread.sleep(200L * attempt); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            HttpURLConnection c = null;
            try {
                c = connect(url, first, last); requests++;
                int code = c.getResponseCode();
                if (code != 206) { drain(c); throw new IOException("HTTP " + code + " for bytes " + first + "-" + last); }
                byte[] out = new byte[(int)(last - first + 1)]; int n = 0;
                try (InputStream in = c.getInputStream()) { while (n < out.length) { int r = in.read(out, n, out.length - n); if (r < 0) break; n += r; } }
                if (n != out.length) throw new IOException("Short read " + n + "/" + out.length + " at " + first);
                bytesFetched += n; return out;
            } catch (IOException e) { error = e; if (c != null) c.disconnect(); }
        }
        throw error != null ? new IOException("Stream read failed (" + url + "): " + error.getMessage(), error) : new IOException("Stream closed");
    }
    /** Returns a chunk from the cache, joining an in-flight fetch of it or fetching it on this thread. */
    private byte[] chunk(long index) throws IOException {
        CompletableFuture<byte[]> future; boolean mine = false;
        synchronized (this) {
            byte[] hit = cache.get(index); if (hit != null) { hits++; return hit; }
            future = inflight.get(index);
            if (future == null) { future = new CompletableFuture<>(); inflight.put(index, future); mine = true; misses++; }
        }
        if (mine) {
            try {
                long offline = offlineUntil;
                if (offline != 0 && System.nanoTime() - offline < 0) throw new IOException("Stream offline, retrying shortly (" + url + ")");
                byte[] data;
                try { data = fetch(index); } catch (IOException e) { offlineUntil = System.nanoTime() + OFFLINE_NANOS; throw e; }
                synchronized (this) { cache.put(index, data); inflight.remove(index); }
                future.complete(data); return data;
            }
            catch (IOException | RuntimeException e) { synchronized (this) { inflight.remove(index); } future.completeExceptionally(e); throw e instanceof IOException ? (IOException)e : new IOException(e); }
        }
        try { return future.get(30, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw e.getCause() instanceof IOException ? (IOException)e.getCause() : new IOException(e.getCause()); }
        catch (TimeoutException e) { throw new IOException("Stream read timed out at chunk " + index); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    private void readAhead(long index) {
        long chunks = (length + chunkSize - 1) / chunkSize;
        for (long i = index + 1; i <= index + readAhead && i < chunks; i++) {
            synchronized (this) { if (closed || cache.containsKey(i) || inflight.containsKey(i)) continue; }
            final long next = i;
            try { prefetcher.execute(() -> { try { chunk(next); } catch (IOException ignored) { } }); } catch (RejectedExecutionException ignored) { }
        }
    }
    @Override public int read(long offset, byte[] out, int at, int n) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (offset < 0 || offset >= length || n <= 0) return 0;
        n = (int)Math.min(n, length - offset); int done = 0;
        while (done < n) {
            long pos = offset + done, index = pos / chunkSize; int within = (int)(pos % chunkSize);
            byte[] c = chunk(index); int take = Math.min(n - done, c.length - within);
            if (take <= 0) throw new IOException("Stream chunk " + index + " is short");
            System.arraycopy(c, within, out, at + done, take); done += take;
            boolean sequential; synchronized (this) { sequential = index == lastChunk + 1 || index == lastChunk; lastChunk = index; }
            if (sequential && readAhead > 0) readAhead(index);
        }
        return n;
    }
    @Override public void close() { closed = true; synchronized (this) { cache.clear(); } }
    @Override public String toString() { return String.format(Locale.ROOT, "%s: %d requests, %d KiB, %d hits, %d misses", url, requests, bytesFetched / 1024, hits, misses); }
}
