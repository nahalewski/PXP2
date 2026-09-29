package org.pxp2.fresh;

import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.pxp2.core.HleVideo;
import org.pxp2.core.VideoDecoder;

/**
 * H.264 decoding for the emulated Media Engine ({@link HleVideo}) through the platform MediaCodec ("video/avc",
 * ByteBuffer mode). Access units arrive as Annex-B with SPS/PPS in band. Each decoded image is copied into an I420
 * picture of exactly the coded size the core expects ({@link HleVideo#pictureSize}): the decoder's crop rectangle is
 * placed at its own offset, and whatever the decoder does not cover stays black. The copy goes through the
 * {@code Image} planes (row and pixel strides, so planar and semi-planar layouts both work); when a codec gives no
 * {@code Image}, the output format's stride, slice height and colour format describe the raw buffer instead.
 * Decoder problems yield no pictures (the guest then shows nothing new), never stop emulation.
 */
final class AndroidVideo implements VideoDecoder {
    private static final String TAG = "PXP2";
    private MediaCodec codec; private String codecName = "?"; private int width, height; private long pts;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    /** Raw output layout from the latest output format (used only when {@code getOutputImage} returns null). */
    private int colorFormat, stride, sliceHeight; private final Rect formatCrop = new Rect();
    private byte[] row = new byte[0]; // scratch for one source row (deinterleaving semi-planar chroma)
    private boolean describedImage; private int failedWidth, failedHeight;

    @Override public synchronized List<Picture> decode(byte[] au, int length) {
        List<Picture> out = new ArrayList<>(1);
        try {
            int[] size = HleVideo.pictureSize(au);
            if (size != null && (codec == null || size[0] != width || size[1] != height) && (size[0] != failedWidth || size[1] != failedHeight)) {
                release(); open(size[0], size[1], length);
                if (codec == null) { failedWidth = size[0]; failedHeight = size[1]; } // do not retry on every access unit
            }
            if (codec == null) return out; // no SPS seen yet
            // Never drop an access unit: later pictures reference it. While the decoder holds all input buffers, drain.
            for (int tries = 0; ; tries++) {
                int in = codec.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer b = codec.getInputBuffer(in);
                    if (b == null || b.capacity() < length) { // too small: restart with room for it (from the next SPS on)
                        Log.w(TAG, "video: input buffer " + (b == null ? "null" : b.capacity()) + " < access unit " + length);
                        release(); return out;
                    }
                    b.clear(); b.put(au, 0, length); codec.queueInputBuffer(in, 0, length, pts, 0); pts += 33_367; break;
                }
                drain(out, 0, false);
                if (tries >= 20) { Log.w(TAG, "video: decoder accepts no input, access unit dropped"); break; }
            }
            drain(out, 5_000, false);
        } catch (Exception e) { Log.w(TAG, "video: " + codecName + " failed", e); release(); }
        return out;
    }
    @Override public synchronized List<Picture> flush() {
        List<Picture> out = new ArrayList<>();
        if (codec == null) return out;
        try {
            int in = codec.dequeueInputBuffer(20_000);
            if (in >= 0) { codec.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); drain(out, 20_000, true); }
        } catch (Exception e) { Log.w(TAG, "video: flush failed", e); }
        release(); return out;
    }
    @Override public synchronized void reset() { release(); failedWidth = failedHeight = 0; }
    synchronized void release() { if (codec != null) { try { codec.stop(); } catch (Exception e) { } codec.release(); codec = null; } }

    /**
     * Opens the first H.264 decoder that accepts a w x h stream: hardware decoders first, then software ones. Hardware
     * decoders often have a minimum size (Qualcomm c2.qti.avc.decoder: 96x96), which game icon movies (ICON1.PMF,
     * 144x80) are below; configuring such a codec fails with BAD_VALUE, so it is skipped.
     */
    private void open(int w, int h, int auSize) {
        for (String name : decoders(w, h)) {
            MediaCodec c = null;
            try {
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(auSize * 2, Math.max(w * h * 3 / 2, 64 * 1024)));
                if (android.os.Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                c = MediaCodec.createByCodecName(name);
                c.configure(f, null, null, 0); c.start();
                codec = c; codecName = name;
                width = w; height = h; pts = 0; describedImage = false;
                colorFormat = 0; stride = w; sliceHeight = h; formatCrop.set(0, 0, w, h);
                Log.i(TAG, "video: " + name + " decodes " + w + "x" + h);
                return;
            } catch (Exception e) {
                Log.w(TAG, "video: " + name + " rejects " + w + "x" + h + ": " + e);
                if (c != null) c.release();
            }
        }
        Log.w(TAG, "video: no H.264 decoder for " + w + "x" + h);
    }
    private int listedWidth, listedHeight; private List<String> listed = new ArrayList<>();
    /** H.264 decoder names that report support for w x h, hardware first (listed once per size). */
    private List<String> decoders(int w, int h) {
        if (w == listedWidth && h == listedHeight && !listed.isEmpty()) return listed;
        List<String> hw = new ArrayList<>(), sw = new ArrayList<>();
        for (MediaCodecInfo i : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (i.isEncoder()) continue;
            boolean avc = false; for (String t : i.getSupportedTypes()) avc |= t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC);
            if (!avc) continue;
            try {
                MediaCodecInfo.VideoCapabilities v = i.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
                if (v == null || !v.isSizeSupported(w, h)) continue;
            } catch (RuntimeException e) { continue; }
            String n = i.getName(); boolean software = android.os.Build.VERSION.SDK_INT >= 29 ? i.isSoftwareOnly()
                : n.startsWith("OMX.google.") || n.startsWith("c2.android.");
            if (n.toLowerCase(java.util.Locale.ROOT).contains("secure")) continue;
            (software ? sw : hw).add(n);
        }
        hw.addAll(sw); listed = hw; listedWidth = w; listedHeight = h;
        return listed;
    }
    /** Collects the decoded pictures now available (waiting up to {@code timeoutUs} for the first; until EOS when draining). */
    private void drain(List<Picture> out, long timeoutUs, boolean untilEos) {
        for (int tries = 0; tries < (untilEos ? 64 : 8); tries++) {
            int i = codec.dequeueOutputBuffer(info, tries == 0 || untilEos ? timeoutUs : 0);
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outputFormat(codec.getOutputFormat()); continue; }
            if (i == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
            if (i < 0) { if (untilEos) continue; break; }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (info.size > 0) {
                    Picture p = null;
                    Image img = codec.getOutputImage(i);
                    if (img != null) { try { p = fromImage(img); } finally { img.close(); } }
                    else p = fromBuffer(codec.getOutputBuffer(i));
                    if (p != null) out.add(p);
                }
            } finally { codec.releaseOutputBuffer(i, false); }
            if (eos) break;
        }
    }
    /** Records the raw layout of a new output format (stride, slice height, crop, colour format). */
    private void outputFormat(MediaFormat f) {
        int w = get(f, MediaFormat.KEY_WIDTH, width), h = get(f, MediaFormat.KEY_HEIGHT, height);
        colorFormat = get(f, MediaFormat.KEY_COLOR_FORMAT, 0);
        stride = Math.max(w, get(f, MediaFormat.KEY_STRIDE, w)); sliceHeight = Math.max(h, get(f, MediaFormat.KEY_SLICE_HEIGHT, h));
        int l = get(f, "crop-left", 0), t = get(f, "crop-top", 0);
        formatCrop.set(l, t, get(f, "crop-right", w - 1) + 1, get(f, "crop-bottom", h - 1) + 1);
        Log.i(TAG, "video: " + codecName + " output " + w + "x" + h + " stride " + stride + " slice " + sliceHeight
            + " crop " + formatCrop.toShortString() + " colour 0x" + Integer.toHexString(colorFormat) + " for coded " + width + "x" + height);
    }
    private static int get(MediaFormat f, String key, int def) { try { return f.containsKey(key) ? f.getInteger(key) : def; } catch (RuntimeException e) { return def; } }

    /** Copies a YUV_420_888 image (any row/pixel strides, planar or semi-planar) into an I420 picture of the coded size. */
    private Picture fromImage(Image img) {
        Image.Plane[] p = img.getPlanes(); Rect c = img.getCropRect();
        if (!describedImage) {
            describedImage = true;
            Log.i(TAG, "video: " + codecName + " image " + img.getWidth() + "x" + img.getHeight() + " format 0x" + Integer.toHexString(img.getFormat())
                + " crop " + c.toShortString() + " Y " + p[0].getRowStride() + "/" + p[0].getPixelStride()
                + " U " + p[1].getRowStride() + "/" + p[1].getPixelStride() + " V " + p[2].getRowStride() + "/" + p[2].getPixelStride()
                + " for coded " + width + "x" + height);
        }
        if (p.length < 3) return null;
        Rect crop = new Rect(0, 0, img.getWidth(), img.getHeight());
        if (!crop.intersect(c)) return null;
        byte[] yuv = allocate(crop);
        copy(yuv, p[0].getBuffer(), 0, p[0].getRowStride(), p[0].getPixelStride(), crop, 0);
        copy(yuv, p[1].getBuffer(), 0, p[1].getRowStride(), p[1].getPixelStride(), crop, 1);
        copy(yuv, p[2].getBuffer(), 0, p[2].getRowStride(), p[2].getPixelStride(), crop, 2);
        return dump(new Picture(width, height, yuv));
    }
    /** Fallback for codecs without {@code Image} output: I420 (planar) or NV12 (semi-planar) described by the output format. */
    private Picture fromBuffer(ByteBuffer b) {
        if (b == null) return null;
        boolean planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
        boolean semi = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        if (!planar && !semi) return null; // vendor/tiled layouts are only readable through Image
        b.position(info.offset); b.limit(info.offset + info.size); ByteBuffer s = b.slice();
        Rect crop = new Rect(formatCrop); byte[] yuv = allocate(crop);
        int ySize = stride * sliceHeight, cs = planar ? stride / 2 : stride;
        copy(yuv, s, 0, stride, 1, crop, 0);
        copy(yuv, s, ySize, cs, planar ? 1 : 2, crop, 1);
        copy(yuv, s, planar ? ySize + cs * (sliceHeight / 2) : ySize + 1, cs, planar ? 1 : 2, crop, 2);
        return dump(new Picture(width, height, yuv));
    }
    /**
     * An I420 buffer of the coded size. The crop rectangle keeps its offset but is clipped to the coded picture;
     * the buffer is pre-filled with black only when the crop leaves part of it uncovered.
     */
    private byte[] allocate(Rect crop) {
        int w = width, h = height; byte[] yuv = new byte[w * h * 3 / 2];
        if (!crop.intersect(0, 0, w, h)) crop.setEmpty();
        if (crop.left != 0 || crop.top != 0 || crop.width() < w || crop.height() < h) {
            Arrays.fill(yuv, 0, w * h, (byte)16); Arrays.fill(yuv, w * h, yuv.length, (byte)128);
        }
        return yuv;
    }
    /**
     * Copies the crop rectangle of one plane ({@code plane} 0 = Y, 1 = U/Cb, 2 = V/Cr) from {@code src} (plane data at
     * {@code base}, {@code rowStride} bytes per row, {@code pixelStride} bytes per sample) into the I420 picture.
     */
    private void copy(byte[] yuv, ByteBuffer src, int base, int rowStride, int pixelStride, Rect crop, int plane) {
        if (crop.isEmpty()) return;
        int sub = plane == 0 ? 1 : 2, w = width, h = height, dstW = w / sub;
        int dstBase = plane == 0 ? 0 : plane == 1 ? w * h : w * h + (w / 2) * (h / 2);
        int x0 = crop.left / sub, y0 = crop.top / sub, pw = Math.min((crop.right + sub - 1) / sub, dstW) - x0;
        int ph = Math.min((crop.bottom + sub - 1) / sub, h / sub) - y0, span = (pw - 1) * pixelStride + 1, limit = src.limit();
        if (pw <= 0 || ph <= 0) return;
        if (pixelStride != 1 && row.length < span) row = new byte[span];
        for (int y = 0; y < ph; y++) {
            int at = base + (y0 + y) * rowStride + x0 * pixelStride, dst = dstBase + (y0 + y) * dstW + x0;
            int n = Math.min(span, limit - at); if (n <= 0) break;
            src.position(at);
            if (pixelStride == 1) src.get(yuv, dst, n);
            else { src.get(row, 0, n); for (int x = 0, s = 0; s < n; x++, s += pixelStride) yuv[dst + x] = row[s]; }
        }
    }

    // ---- TEMPORARY diagnostics: dumps the first pictures of each size (removed before release) ----
    private int dumped;
    private Picture dump(Picture p) {
        try {
            if (dumped < 40) {
                android.app.Application app = (android.app.Application)Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                java.io.File d = new java.io.File(app.getExternalFilesDir(null), "vdump"); d.mkdirs();
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(d, String.format(java.util.Locale.ROOT, "%dx%d_%03d.yuv", p.width, p.height, dumped)))) { o.write(p.yuv); }
                dumped++;
            }
        } catch (Exception e) { Log.w(TAG, "dump", e); }
        return p;
    }
}
