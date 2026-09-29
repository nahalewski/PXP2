package org.pxp2.fresh;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import java.nio.ByteBuffer;
import java.util.List;
import org.pxp2.core.HleVideo;
import org.pxp2.core.PsmfDemuxer;

/**
 * A game's animated icon (ICON1.PMF, 144x80 H.264 in PSMF), looping like the XMB's focused game.
 *
 * <p>The access units come from {@link PsmfDemuxer}; a platform MediaCodec ("video/avc") decodes them straight to this
 * view's Surface, so no pixel conversion happens in Java. Frames are paced by count at the stream's 29.97 fps. At the end
 * of the stream the decoder is drained (EOS), flushed and fed from the first access unit again (an IDR with SPS/PPS).
 * The decoder thread starts when the surface exists and is stopped and released when the surface goes away (the row is
 * detached) or {@link #release()} is called. Decoder problems only log; the static ICON0 underneath stays visible.
 */
final class PmfIconView extends TextureView implements TextureView.SurfaceTextureListener {
    private static final String TAG = "PXP2Preview";
    private static final long FRAME_NS = 1_001_000_000L / 30; // 29.97 fps
    private final List<PsmfDemuxer.AccessUnit> units;
    private final String label;
    private Thread thread; private volatile boolean running; private Surface surface;

    PmfIconView(Context context, List<PsmfDemuxer.AccessUnit> units, String label) {
        super(context);
        this.units = units; this.label = label;
        setOpaque(false); setAlpha(0f); // shown with a fade once the first frame is on screen
        setSurfaceTextureListener(this);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        running = true;
        thread = new Thread(this::decodeLoop, "PXP2-icon1");
        thread.start();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { stop(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
        if (getAlpha() == 0f && getTag() == null) { setTag(Boolean.TRUE); animate().alpha(1f).setDuration(220).start(); }
    }

    /** Stops decoding and frees the codec and surface (safe to call more than once). */
    void release() { stop(); }

    private void stop() {
        running = false;
        Thread t = thread; thread = null;
        if (t != null) { t.interrupt(); try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        if (surface != null) { surface.release(); surface = null; }
    }

    private void decodeLoop() {
        MediaCodec codec = null; Surface out = surface;
        try {
            if (units.isEmpty() || out == null) return;
            int[] size = HleVideo.pictureSize(units.get(0).data);
            int w = size != null ? size[0] : 144, h = size != null ? size[1] : 80;
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, out, null, 0);
            codec.start();
            Log.i(TAG, "icon: " + label + " ICON1.PMF " + w + "x" + h + ", " + units.size() + " access units, decoder " + codec.getName());
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int next = 0, loops = 0; boolean inputDone = false; long shown = 0, start = System.nanoTime();
            while (running) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(5_000);
                    if (in >= 0) {
                        if (next < units.size()) {
                            byte[] au = units.get(next).data; ByteBuffer b = codec.getInputBuffer(in);
                            if (b == null || au.length > b.capacity()) { codec.queueInputBuffer(in, 0, 0, next * 33_367L, 0); next++; continue; }
                            b.clear(); b.put(au);
                            codec.queueInputBuffer(in, 0, au.length, next * 33_367L, 0); next++;
                        } else { codec.queueInputBuffer(in, 0, 0, next * 33_367L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 5_000);
                if (o < 0) continue;
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0) {
                    long due = start + shown * FRAME_NS, now = System.nanoTime();
                    if (now - due > 200_000_000L) { start = now - shown * FRAME_NS; due = now; } // fell behind (e.g. paused): resync
                    while (running && (now = System.nanoTime()) < due) Thread.sleep(Math.max(1, (due - now) / 1_000_000));
                    if (!running) { codec.releaseOutputBuffer(o, false); break; }
                    codec.releaseOutputBuffer(o, true); shown++;
                    if (shown == 1) Log.i(TAG, "icon: " + label + " first frame rendered");
                } else codec.releaseOutputBuffer(o, false);
                if (eos) { // loop: restart from the first access unit
                    codec.flush(); next = 0; inputDone = false; loops++;
                    if (loops <= 2) Log.i(TAG, "icon: " + label + " loop " + loops + " (" + shown + " frames shown)");
                }
            }
        } catch (InterruptedException e) {
            // stopping
        } catch (Exception e) {
            Log.w(TAG, "icon: " + label + " ICON1 playback failed: " + e);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
        }
    }
}
