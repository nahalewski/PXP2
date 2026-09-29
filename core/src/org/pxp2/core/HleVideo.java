package org.pxp2.core;

import java.util.*;

/**
 * Original HLE of the Media Engine H.264 decoder interface sceVideocodec and the colour-space converter of sceMpegbase
 * (both exported by the firmware's kd/avcodec.prx, which forwards them to the ME and the DMAC+ CSC unit). The context
 * layout is taken from avcodec.prx's own argument checks and from how kd/mpeg_vsh.prx fills and reads it (psp.md §7).
 * Access units are decoded by an optional host {@link VideoDecoder}; without one every access unit yields a black
 * picture of the stream's size, so playback keeps its timing. Only H.264 (codec type 0) is implemented.
 *
 * Decoded pictures live in a PXP2 frame store in kernel memory. The eight plane pointers of a picture descriptor follow
 * the firmware's size checks (four luma parts of w*h/4 bytes, four chroma parts of w*h/8); how the real ME arranges
 * pixels inside them is not visible to guest code (only sceMpegbase reads them), so PXP2 stores a plain I420 image:
 * Y rows, then Cb, then Cr, and the parts are consecutive slices of it.
 */
public final class HleVideo {
    private final Kernel k; private final Memory m;
    /** Optional host decoder (Android: MediaCodec). */
    public volatile VideoDecoder decoder;
    public long accessUnits, pictures, blackPictures;
    private static final int BAD_ADDRESS = 0x80000023, BAD_PARAMETER = 0x806201fe;

    /** Per-context decoder state (keyed by the guest context address). */
    static final class Codec {
        int store, slotSize, slots, next, width, height, maxWidth, maxHeight;
        int cropLeft, cropRight, cropTop, cropBottom, command7;
        final ArrayDeque<VideoDecoder.Picture> ready = new ArrayDeque<>();
        boolean dumped;
    }
    private final Map<Integer, Codec> codecs = new HashMap<>();

    public HleVideo(Kernel k) { this.k = k; this.m = k.memory; setMatrix(DEFAULT_MATRIX); registerCodec(); registerCsc(); }

    private boolean ok(int address, int length) { return address != 0 && m.valid(address, length); }
    /** avcodec checks the 96-byte context and the codec-type-specific block at ctx+16 (40 bytes for H.264). */
    private int check(int ctx, int type) {
        if (!ok(ctx, 96)) return BAD_ADDRESS;
        if (type != 0) throw new Kernel.Stop(String.format(Locale.ROOT, "sceVideocodec codec type %d (not H.264) is not implemented", type));
        return ok(m.read32(ctx + 16), 40) ? 0 : BAD_ADDRESS;
    }
    private Codec codec(int ctx) { return codecs.computeIfAbsent(ctx, c -> new Codec()); }

    private void registerCodec() {
        String lib = "sceVideocodec";
        // avcodec writes the magic 0x05100601 at ctx+0, then the ME reports its working-memory size at ctx+24 (read by
        // mpeg_vsh right after Open and passed to GetEDRAM). PXP2's ME needs none of it for decoding; 64 KiB is reserved.
        k.register(lib, 0xc01ec829, "sceVideocodecOpen", c -> {
            int ctx = c.arg(0), type = c.arg(1), e = check(ctx, type); if (e != 0) return e;
            m.write32(ctx, 0x05100601); m.write32(ctx + 24, 0x10000); codec(ctx); return 0;
        });
        // Allocates ctx+24 bytes of ME memory (+63 for alignment): raw block at ctx+92, 64-byte aligned address at ctx+20.
        k.register(lib, 0x2d31f5b1, "sceVideocodecGetEDRAM", c -> {
            int ctx = c.arg(0); if (!ok(ctx, 96)) return BAD_ADDRESS; if (m.read32(ctx + 92) != 0) return BAD_PARAMETER;
            int a = k.sysmem.allocate(1, "ME video EDRAM", SysMem.LOW, m.read32(ctx + 24) + 63, 0); if (a == 0) return Kernel.NO_MEMORY;
            m.write32(ctx + 92, a); m.write32(ctx + 20, (a + 63) & ~63); return 0;
        });
        k.register(lib, 0x4f160bf4, "sceVideocodecReleaseEDRAM", c -> {
            int ctx = c.arg(0); if (!ok(ctx, 96)) return BAD_ADDRESS; int a = m.read32(ctx + 92); if (a == 0) return BAD_PARAMETER;
            k.sysmem.free(a); m.write32(ctx + 20, 0); m.write32(ctx + 92, 0); return 0;
        });
        // Init: the ME takes the EDRAM (ctx+20/24) and the caller's memory (ctx+28/32) and starts a stream.
        k.register(lib, 0x17099f0a, "sceVideocodecInit", c -> {
            int ctx = c.arg(0), e = check(ctx, c.arg(1)); if (e != 0) return e;
            Codec x = codec(ctx); x.ready.clear(); VideoDecoder d = decoder; if (d != null) d.reset(); return 0;
        });
        // ME command 3 (avcodec passes only the context; callers read no result field).
        k.register(lib, 0x26927d19, "sceVideocodec_26927D19 (ME command 3)", c -> c.arg(1) >= 3 ? BAD_PARAMETER : check(c.arg(0), c.arg(1)));
        // ME command 1: mpeg_vsh stores the stream limits at ctx+64..76 (max width, max height, then 2 or 3 and 0 or 1
        // depending on the player mode) just before it.
        k.register(lib, 0x745a7b7a, "sceVideocodec_745A7B7A (ME command 1, stream limits)", c -> {
            int ctx = c.arg(0), e = check(ctx, c.arg(1)); if (e != 0) return e;
            Codec x = codec(ctx); x.maxWidth = m.read32(ctx + 64); x.maxHeight = m.read32(ctx + 68); return 0;
        });
        k.register(lib, 0xdba273fa, "sceVideocodecDecode", c -> decode(c.arg(0), c.arg(1)));
        // ME commands 5 and 6 fill the SEI block at ctx+80 and the crop block at ctx+84 outside a decode (H.264 only).
        k.register(lib, 0x627b7d42, "sceVideocodec_627B7D42 (ME command 5, SEI)", c -> {
            int ctx = c.arg(0); if (c.arg(1) != 0) return BAD_PARAMETER; int e = check(ctx, 0); if (e != 0) return e;
            if (!ok(m.read32(ctx + 80), 164)) return BAD_ADDRESS; sei(m.read32(ctx + 80)); return 0;
        });
        k.register(lib, 0x17cf7d2c, "sceVideocodec_17CF7D2C (ME command 6, frame crop)", c -> {
            int ctx = c.arg(0); if (c.arg(1) != 0) return BAD_PARAMETER; int e = check(ctx, 0); if (e != 0) return e;
            if (!ok(m.read32(ctx + 84), 16)) return BAD_ADDRESS; crop(codec(ctx), m.read32(ctx + 84)); return 0;
        });
        // ME command 7 takes the word at ctx+88 (mpeg_vsh passes 1 in player modes 4-6, the modes that request SEI and crop).
        k.register(lib, 0x893b32b1, "sceVideocodec_893B32B1 (ME command 7)", c -> {
            int ctx = c.arg(0); if (c.arg(1) != 0) return BAD_PARAMETER; int e = check(ctx, 0); if (e != 0) return e;
            codec(ctx).command7 = m.read32(ctx + 88); return 0;
        });
        // Stop: end of stream. The ME returns the pictures it still holds for reordering, like a decode without input.
        k.register(lib, 0xa2f0564e, "sceVideocodecStop", c -> {
            int ctx = c.arg(0), e = check(ctx, c.arg(1)); if (e != 0) return e;
            Codec x = codec(ctx); VideoDecoder d = decoder; if (d != null) x.ready.addAll(d.flush());
            output(ctx, x, Math.max(1, m.read32(ctx + 60))); return 0;
        });
        k.register(lib, 0x307e6e1c, "sceVideocodecDelete", c -> {
            int ctx = c.arg(0); if (!ok(ctx, 96)) return BAD_ADDRESS;
            Codec x = codecs.remove(ctx); if (x != null && x.store != 0) k.sysmem.free(x.store); VideoDecoder d = decoder; if (d != null) d.reset(); return 0;
        });
    }

    /**
     * Decode (dba273fa). Inputs: ctx+36/40 access unit address/size, ctx+44 array of ctx+60 picture descriptors (44 bytes),
     * ctx+48 (100 bytes) and ctx+56 (ctx+60 x 328 bytes, optional) output blocks, ctx+52 bit 31/30 = also fill SEI (ctx+80)
     * / crop (ctx+84). Outputs read by mpeg_vsh: info (ctx+16) +8 width, +12 height, +32 number of pictures returned;
     * descriptor words 0-7 = plane pointers, passed unchanged to sceMpegBaseCscAvc.
     */
    private int decode(int ctx, int type) {
        int e = check(ctx, type); if (e != 0) return e;
        int au = m.read32(ctx + 36), size = m.read32(ctx + 40), descriptors = m.read32(ctx + 44), count = m.read32(ctx + 60);
        if (!ok(au, size) || size <= 0 || count <= 0 || count > 16 || !ok(descriptors, count * 44)) return BAD_ADDRESS;
        Codec x = codec(ctx); accessUnits++;
        byte[] data = annexB(m.read(au, size));
        if (!x.dumped) { x.dumped = true; k.log("sceVideocodecDecode: first access unit " + size + " bytes " + hex(m.read(au, Math.min(size, 24)))); }
        scanParameterSets(x, data);
        VideoDecoder d = decoder;
        if (d != null) x.ready.addAll(d.decode(data, data.length));
        else if (x.width > 0 && x.height > 0 && containsPicture(data)) { x.ready.add(new VideoDecoder.Picture(x.width, x.height, null)); blackPictures++; }
        output(ctx, x, 1); // one picture per access unit, as the ME does once its reorder delay is filled
        int flags = m.read32(ctx + 52);
        if (flags < 0) sei(m.read32(ctx + 80));
        if ((flags & 0x40000000) != 0) crop(x, m.read32(ctx + 84));
        return 0;
    }
    /**
     * SEI block (164 bytes): mpeg_vsh tests byte 0 bit 1 (then uses the words at +24/+28 as picture-timing delays) and
     * bit 0. PXP2 does not parse SEI messages and reports none, so the player uses its default timing.
     */
    private void sei(int at) { if (ok(at, 164)) m.fill(at, 0, 164); }
    /** Frame crop (16 bytes, copied by mpeg_vsh to info+16..28): the SPS cropping in pixels, assumed in the SPS order left, right, top, bottom. */
    private void crop(Codec x, int at) {
        if (!ok(at, 16)) return; m.write32(at, x.cropLeft); m.write32(at + 4, x.cropRight); m.write32(at + 8, x.cropTop); m.write32(at + 12, x.cropBottom);
    }

    /** Writes up to {@code max} ready pictures into the frame store and the caller's descriptors; sets the picture count. */
    private void output(int ctx, Codec x, int max) {
        int info = m.read32(ctx + 16), descriptors = m.read32(ctx + 44), n = 0;
        while (n < max && !x.ready.isEmpty()) {
            VideoDecoder.Picture p = x.ready.poll(); int w = p.width, h = p.height;
            if ((w & 15) != 0 || (h & 15) != 0 || w <= 0 || h <= 0 || w > 720 || h > 576) throw new Kernel.Stop("sceVideocodec: picture size " + w + "x" + h + " not supported");
            int slotSize = w * h * 3 / 2, slots = Math.max(2, m.read32(ctx + 60) + 1);
            if (x.store == 0 || x.slotSize != slotSize) {
                if (x.store != 0) k.sysmem.free(x.store);
                x.store = k.sysmem.allocate(1, "ME video frames", SysMem.LOW, slotSize * slots, 0);
                if (x.store == 0) throw new Kernel.Stop("sceVideocodec: no kernel memory for " + slots + " frames of " + w + "x" + h);
                x.slotSize = slotSize; x.slots = slots; x.next = 0;
            }
            int base = x.store + x.slotSize * x.next; x.next = (x.next + 1) % x.slots;
            if (p.yuv != null) m.write(base, p.yuv, 0, slotSize);
            else { m.fill(base, 16, w * h); m.fill(base + w * h, 128, w * h / 2); } // black (video range)
            int d = descriptors + n * 44, at = base; int[] sizes = parts(w, h);
            for (int i = 0; i < 8; i++) { m.write32(d + i * 4, at); at += sizes[i]; } // consecutive slices of the I420 image
            int extra = m.read32(ctx + 56); if (ok(extra, (n + 1) * 328)) m.fill(extra + n * 328, 0, 328); // byte 0 bit 1 clear: no pic_struct reported
            m.write32(info + 8, w); m.write32(info + 12, h); n++; pictures++;
        }
        m.write32(info + 32, n);
    }

    /**
     * Byte sizes of the eight parts of a w x h picture, as sceMpegBaseYCrCbCopy computes them (avcodec 08801f58, in 16-byte
     * units): luma {even macroblock columns, odd columns} x {two row halves} = ((w+16)/32 or w/32) x 16 x h/2 bytes,
     * then the four chroma parts at half those sizes.
     */
    static int[] parts(int w, int h) {
        int even = ((w + 16) >> 5) * 16 * (h / 2), odd = (w >> 5) * 16 * (h / 2);
        return new int[]{even, odd, even, odd, even / 2, odd / 2, even / 2, odd / 2};
    }

    // ---- H.264 bitstream helpers (only what is needed to size pictures without a host decoder) ----
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v & 255)); return s.toString(); }
    /** Returns the access unit as an Annex-B stream: kept if it starts with a start code, converted from 4-byte length prefixes otherwise. */
    static byte[] annexB(byte[] au) {
        if (au.length >= 4 && au[0] == 0 && au[1] == 0 && (au[2] == 1 || (au[2] == 0 && au[3] == 1))) return au;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(au.length + 16);
        for (int p = 0; p < au.length; ) {
            if (p + 4 > au.length) throw new Kernel.Stop("sceVideocodecDecode: access unit is neither Annex-B nor length-prefixed");
            int n = ((au[p] & 255) << 24) | ((au[p + 1] & 255) << 16) | ((au[p + 2] & 255) << 8) | (au[p + 3] & 255); p += 4;
            if (n <= 0 || n > au.length - p) throw new Kernel.Stop("sceVideocodecDecode: access unit is neither Annex-B nor length-prefixed");
            out.write(0); out.write(0); out.write(0); out.write(1); out.write(au, p, n); p += n;
        }
        return out.toByteArray();
    }
    /** Start offsets of the NAL units (first byte after each start code). */
    static List<Integer> nals(byte[] s) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i + 2 < s.length; i++) if (s[i] == 0 && s[i + 1] == 0 && s[i + 2] == 1) { out.add(i + 3); i += 2; }
        return out;
    }
    private static boolean containsPicture(byte[] s) { for (int p : nals(s)) { int t = s[p] & 31; if (t == 1 || t == 5) return true; } return false; }
    private static void scanParameterSets(Codec x, byte[] s) { for (int p : nals(s)) if ((s[p] & 31) == 7) sps(x, s, p + 1); }
    /** Coded picture size {width, height} from the first SPS in an Annex-B access unit, or null (helper for host decoders). */
    public static int[] pictureSize(byte[] au) { Codec x = new Codec(); scanParameterSets(x, au); return x.width > 0 ? new int[]{x.width, x.height} : null; }
    /** Parses the picture size and cropping from a sequence parameter set (ITU-T H.264 7.3.2.1.1). */
    private static void sps(Codec x, byte[] s, int at) {
        Bits b = new Bits(s, at); int profile = b.u(8); b.u(16); b.ue();
        int chroma = 1;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
            chroma = b.ue(); if (chroma == 3) b.u(1); b.ue(); b.ue(); b.u(1);
            if (b.u(1) != 0) for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) if (b.u(1) != 0) { int last = 8, next = 8; for (int j = 0; j < (i < 6 ? 16 : 64) && next != 0; j++) { next = (last + b.se() + 256) % 256; last = next == 0 ? last : next; } }
        }
        b.ue(); int poc = b.ue();
        if (poc == 0) b.ue(); else if (poc == 1) { b.u(1); b.se(); b.se(); int n = b.ue(); for (int i = 0; i < n; i++) b.se(); }
        b.ue(); b.u(1);
        int w = (b.ue() + 1) * 16, mapUnits = b.ue() + 1, frameMbsOnly = b.u(1); if (frameMbsOnly == 0) b.u(1); b.u(1);
        x.width = w; x.height = mapUnits * 16 * (2 - frameMbsOnly);
        x.cropLeft = x.cropRight = x.cropTop = x.cropBottom = 0;
        if (b.u(1) != 0) { int cx = chroma == 0 || chroma == 3 ? 1 : 2, cy = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly); x.cropLeft = b.ue() * cx; x.cropRight = b.ue() * cx; x.cropTop = b.ue() * cy; x.cropBottom = b.ue() * cy; }
    }
    /** Exp-Golomb bit reader over a NAL payload, skipping emulation-prevention bytes. */
    static final class Bits {
        private final byte[] s; private int p, bit, zeros;
        Bits(byte[] s, int at) { this.s = s; p = at; }
        int bit() {
            if (p >= s.length) return 0;
            if (bit == 0) { if (zeros >= 2 && s[p] == 3) { p++; zeros = 0; if (p >= s.length) return 0; } zeros = s[p] == 0 ? zeros + 1 : 0; }
            int v = (s[p] >> (7 - bit)) & 1; if (++bit == 8) { bit = 0; p++; } return v;
        }
        int u(int n) { int v = 0; for (int i = 0; i < n; i++) v = (v << 1) | bit(); return v; }
        int ue() { int z = 0; while (bit() == 0 && z < 32) z++; return (int)((1L << z) - 1 + u(z)); }
        int se() { int v = ue(); return (v & 1) != 0 ? (v + 1) / 2 : -(v / 2); }
    }

    // ---- sceMpegbase colour-space conversion ----
    private int cscWidth = 512, cscMode;
    /**
     * CSC matrix: 11 halfwords as passed to sceMpegbase_AC9E717E. avcodec's default (08804a40) is
     * {0x95, 0, 0xcc, 0x95, 0x3ce, 0x398, 0x95, 0x102, 0, 0x10, 1}: rows R, G, B of (Y, Cb, Cr) coefficients as 10-bit
     * signed values in 1/128 units, then the luma offset 16 (i.e. BT.601 video range: R = 1.164(Y-16) + 1.594 Cr' ...).
     * The meaning of the last halfword is unknown (not used).
     */
    private static final short[] DEFAULT_MATRIX = {0x95, 0, 0xcc, 0x95, 0x3ce, 0x398, 0x95, 0x102, 0, 0x10, 1};
    private final int[] matrix = new int[10];
    private void setMatrix(short[] h) { for (int i = 0; i < 9; i++) matrix[i] = (h[i] << 22) >> 22; matrix[9] = h[9] & 0x3ff; }
    private void registerCsc() {
        String lib = "sceMpegbase";
        // 492b5e4b: stores the default destination width (used when a CSC call passes width 0) and the default matrix; mode 0.
        k.register(lib, 0x492b5e4b, "sceMpegBaseCscInit", c -> { cscWidth = c.arg(0); cscMode = 0; setMatrix(DEFAULT_MATRIX); return 0; });
        // ac9e717e (width, matrix): as CscInit with the caller's 22-byte matrix.
        k.register(lib, 0xac9e717e, "sceMpegbase_AC9E717E (CSC init with matrix)", c -> {
            if (!ok(c.arg(1), 22)) return BAD_ADDRESS; short[] h = new short[11]; for (int i = 0; i < 11; i++) h[i] = (short)m.read16(c.arg(1) + i * 2);
            cscWidth = c.arg(0); cscMode = 0; setMatrix(h); return 0;
        });
        // 0530be4e: stores the destination pixel mode (halfword) used by the next conversions. The CSC numbering differs from
        // the GE's: mpeg_vsh translates GE formats 565/5551/4444/8888 (0-3) through its table at 09c79450 to 1, 2, 3, 0.
        k.register(lib, 0x0530be4e, "sceMpegbase_0530BE4E (set CSC pixel mode)", c -> { cscMode = c.arg(0) & 0xffff; return 0; });
        // bea18f91 (list): DMA copy of a chain of 16-byte entries {src, dst, more, size & 0xfff}; entries are consecutive,
        // at most 100 (1600-byte DMA list); a zero size or a longer chain fails with 0x80000104 before anything is copied.
        k.register(lib, 0xbea18f91, "sceMpegBasePESpacketCopy", c -> {
            int list = c.arg(0); List<int[]> copies = new ArrayList<>();
            for (int e = list; ; e += 16) {
                if (!ok(e, 16)) return BAD_ADDRESS;
                int src = m.read32(e), dst = m.read32(e + 4), more = m.read32(e + 8), size = m.read32(e + 12) & 0xfff;
                if (size == 0 || copies.size() == 100) return 0x80000104;
                copies.add(new int[]{src, dst, size});
                if (more == 0) break;
            }
            for (int[] x : copies) {
                if (!ok(x[0], x[2]) || !ok(x[1], x[2])) return BAD_ADDRESS;
                m.write(x[1], m.read(x[0], x[2]));
            }
            return 0;
        });
        // 7ac0321a (dst, src, flags): copies picture parts between two 48-byte YCrCb blocks (+0 height, +4 width in pixels,
        // +16..+44 the part pointers): flags bit 0 the parts at +16/+20/+32/+36, bit 1 those at +24/+28/+40/+44.
        k.register(lib, 0x7ac0321a, "sceMpegBaseYCrCbCopy", c -> {
            int dst = c.arg(0), src = c.arg(1), flags = c.arg(2); if (!ok(dst, 48) || !ok(src, 48)) return BAD_ADDRESS;
            int h = m.read32(src), w = m.read32(src + 4); int[] sizes = parts(w, h);
            if (h < 0 || w < 0 || sizes[0] / 16 > 0xbff4) return 0x80000104;
            for (int i = 0; i < 8; i++) {
                if ((flags & ((i & 2) == 0 ? 1 : 2)) == 0 || sizes[i] == 0) continue;
                int from = m.read32(src + 16 + i * 4), to = m.read32(dst + 16 + i * 4);
                if (!ok(from, sizes[i]) || !ok(to, sizes[i])) return BAD_ADDRESS; m.write(to, m.read(from, sizes[i]));
            }
            return 0;
        });
        // 91929a21 (dest, dest2, width, yuv): yuv +0 height/16, +4 width/16, +16..+44 the eight plane pointers of a descriptor.
        k.register(lib, 0x91929a21, "sceMpegBaseCscAvc", c -> {
            int dest = c.arg(0), dest2 = c.arg(1), width = c.arg(2) == 0 ? cscWidth : c.arg(2), yuv = c.arg(3);
            if (!ok(yuv, 48)) return BAD_ADDRESS;
            if (dest2 != 0) throw new Kernel.Stop("sceMpegBaseCscAvc with a second destination is not implemented");
            if (cscMode > 3) throw new Kernel.Stop("sceMpegBaseCscAvc pixel mode " + cscMode + " not implemented");
            int h = m.read32(yuv) * 16, w = m.read32(yuv + 4) * 16, bpp = cscMode == 0 ? 4 : 2;
            if (w <= 0 || h <= 0 || !ok(dest, (h - 1) * width * bpp + Math.min(w, width) * bpp)) return BAD_ADDRESS;
            int[] part = new int[8], sizes = parts(w, h); for (int i = 0; i < 8; i++) { part[i] = m.read32(yuv + 16 + i * 4); if (!ok(part[i], sizes[i])) return BAD_ADDRESS; }
            csc(dest, width, w, h, part); return 0;
        });
    }
    /** Converts one picture held in the eight parts (PXP2 layout, see class comment) to the destination pixel mode. */
    private void csc(int dest, int stride, int w, int h, int[] part) {
        int cw = w / 2, bpp = cscMode == 0 ? 4 : 2, cols = Math.min(w, stride), c = w * h / 4; int[] x = matrix;
        byte[] img = new byte[w * h * 3 / 2]; int[] sizes = parts(w, h);
        for (int i = 0, at = 0; i < 8; at += sizes[i], i++) m.read(part[i], img, at, sizes[i]);
        byte[] row = new byte[cols * bpp];
        for (int r = 0; r < h; r++) {
            for (int col = 0; col < cols; col++) {
                int yl = (img[r * w + col] & 255) - x[9], ci = w * h + (r >> 1) * cw + (col >> 1), u = (img[ci] & 255) - 128, v = (img[ci + c] & 255) - 128;
                int R = clamp((x[0] * yl + x[1] * u + x[2] * v + 64) >> 7), G = clamp((x[3] * yl + x[4] * u + x[5] * v + 64) >> 7), B = clamp((x[6] * yl + x[7] * u + x[8] * v + 64) >> 7);
                int px, at = col * bpp;
                switch (cscMode) {
                    case 1: px = (R >> 3) | ((G >> 2) << 5) | ((B >> 3) << 11); break;             // 565
                    case 2: px = (R >> 3) | ((G >> 3) << 5) | ((B >> 3) << 10) | 0x8000; break;    // 5551
                    case 3: px = (R >> 4) | ((G >> 4) << 4) | ((B >> 4) << 8) | 0xf000; break;     // 4444
                    default: px = R | (G << 8) | (B << 16) | 0xff000000;                          // 0: 8888
                }
                row[at] = (byte)px; row[at + 1] = (byte)(px >> 8); if (bpp == 4) { row[at + 2] = (byte)(px >> 16); row[at + 3] = (byte)(px >> 24); }
            }
            m.write(dest + r * stride * bpp, row);
        }
    }
    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }
}
