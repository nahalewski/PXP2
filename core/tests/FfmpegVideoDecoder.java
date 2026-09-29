import org.pxp2.core.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Desktop test decoder: an ffmpeg subprocess turns the Annex-B H.264 access units into raw I420 frames. Each decode
 * waits (bounded) until ffmpeg has produced all but the last few pictures fed so far, so runs stay nearly deterministic.
 */
public final class FfmpegVideoDecoder implements VideoDecoder {
    private static final int LAG = 6; // ffmpeg pipeline lag measured at 4-5 access units, plus margin
    private Process process; private OutputStream in; private Thread reader;
    private final BlockingQueue<Picture> out = new LinkedBlockingQueue<>();
    private int width, height; private long fed, received; private volatile boolean eof;

    private void start(int w, int h) throws IOException {
        width = w; height = h; fed = received = 0; eof = false;
        process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-threads", "1", "-framerate", "30", "-probesize", "32", "-analyzeduration", "0",
            "-f", "h264", "-i", "pipe:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "pipe:1").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        in = process.getOutputStream(); InputStream o = process.getInputStream(); int size = w * h * 3 / 2;
        reader = new Thread(() -> {
            try { while (true) { byte[] f = o.readNBytes(size); if (f.length < size) break; out.add(new Picture(w, h, f)); } } catch (IOException e) { }
            eof = true;
        }, "ffmpeg-video"); reader.setDaemon(true); reader.start();
    }
    @Override public List<Picture> decode(byte[] au, int length) {
        try {
            if (process == null) { int[] size = HleVideo.pictureSize(au); if (size == null) return List.of(); start(size[0], size[1]); }
            in.write(au, 0, length); in.flush(); fed++;
            return collect(fed - LAG, 500);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    @Override public List<Picture> flush() {
        if (process == null) return List.of();
        try { in.close(); reader.join(5000); } catch (Exception e) { }
        List<Picture> all = collect(Long.MAX_VALUE, 0); process.destroy(); process = null; return all;
    }
    @Override public void reset() { if (process != null) { process.destroyForcibly(); process = null; } out.clear(); }
    /** Waits until at least {@code target} pictures have arrived (or ffmpeg ended / timeout), then returns the new ones. */
    private List<Picture> collect(long target, long timeoutMs) {
        List<Picture> got = new ArrayList<>(); long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Picture p = out.poll(); if (p != null) { got.add(p); received++; continue; }
            if (received >= target || eof || System.currentTimeMillis() >= deadline) return got;
            try { p = out.poll(5, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return got; }
            if (p != null) { got.add(p); received++; }
        }
    }
}
