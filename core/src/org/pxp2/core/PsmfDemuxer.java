package org.pxp2.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls the H.264 video out of a PSMF file (ICON1.PMF, gameboot.pmf, movie PMFs).
 *
 * <p>Format, as far as this class needs it (PXP2's own reading of the files; see psp.md "PSMF demux"):
 * <ul>
 * <li>Header: "PSMF" + a four-digit version ("0012", "0014", "0015"), then big-endian words: +8 offset of the stream
 *     data (normally 0x800), +12 its length. Files without the magic are read as a bare MPEG program stream.</li>
 * <li>The stream data is an MPEG-2 program stream (ISO/IEC 13818-1): pack headers (00 00 01 BA), an optional system
 *     header (BB), padding (BE) and PES packets. Video is stream id E0 (the first E0..EF seen is taken); PSP audio would
 *     be private stream 1 (BD) and is ignored.</li>
 * <li>The PES payloads concatenate to an Annex-B H.264 elementary stream (AUD, SPS, PPS, SEI and slices). It is cut into
 *     access units at the boundaries of ITU-T H.264 7.4.1.2.3: an access unit delimiter, or SEI/SPS/PPS (and types 14..18),
 *     or a slice with first_mb_in_slice = 0, once the current unit already holds a slice.</li>
 * </ul>
 * Pure Java with no platform dependencies, so the desktop tests and the Android app share it.
 */
public final class PsmfDemuxer {
    private PsmfDemuxer() { }

    /** One H.264 access unit (Annex-B, start codes included) and the PTS of the PES packet it starts in (90 kHz, or -1). */
    public static final class AccessUnit {
        public final byte[] data; public final long pts90k;
        AccessUnit(byte[] data, long pts90k) { this.data = data; this.pts90k = pts90k; }
    }

    /** The PSMF version string ("0015"), or null when the data has no PSMF header. */
    public static String version(byte[] file) {
        if (file == null || file.length < 16 || file[0] != 'P' || file[1] != 'S' || file[2] != 'M' || file[3] != 'F') return null;
        return new String(file, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** The concatenated video elementary stream (Annex-B H.264). Empty if the file holds no video PES packets. */
    public static byte[] videoStream(byte[] file) { return demux(file, null); }

    /** The video access units in decode order. */
    public static List<AccessUnit> accessUnits(byte[] file) {
        List<long[]> pesStarts = new ArrayList<>(); // {offset in the elementary stream, pts}
        byte[] es = demux(file, pesStarts);
        return split(es, pesStarts);
    }

    // ------------------------------------------------------------------------------------------------ program stream
    private static byte[] demux(byte[] f, List<long[]> pesStarts) {
        ByteArrayOutputStream es = new ByteArrayOutputStream(f == null ? 0 : f.length);
        if (f == null) return es.toByteArray();
        int pos = 0, end = f.length;
        if (version(f) != null) {
            int off = be32(f, 8), len = be32(f, 12);
            pos = off > 0 && off < f.length ? off : 0x800;
            if (len > 0 && (long)pos + len <= f.length) end = pos + len;
        }
        int videoId = -1;
        while (pos + 4 <= end) {
            if (f[pos] != 0 || f[pos + 1] != 0 || f[pos + 2] != 1) { pos = nextStartCode(f, pos + 1, end); continue; }
            int id = f[pos + 3] & 255;
            if (id == 0xBA) { // pack header: MPEG-2 (01xx xxxx) is 14 bytes plus stuffing, MPEG-1 is 12
                if (pos + 14 > end) break;
                pos += (f[pos + 4] & 0xC0) == 0x40 ? 14 + (f[pos + 13] & 7) : 12;
                continue;
            }
            if (id == 0xB9) { pos += 4; continue; } // program end code
            if (id < 0xBB) { pos = nextStartCode(f, pos + 4, end); continue; } // not a PS packet (stray ES start code)
            if (pos + 6 > end) break;
            int len = (f[pos + 4] & 255) << 8 | (f[pos + 5] & 255);
            int packetEnd = len == 0 ? nextPack(f, pos + 6, end) : Math.min(end, pos + 6 + len);
            boolean video = id >= 0xE0 && id <= 0xEF;
            if (video && videoId < 0) videoId = id;
            if (id == videoId) {
                int h = pos + 6; long pts = -1; int payload;
                if ((f[h] & 0xC0) == 0x80) { // MPEG-2 PES header
                    int flags = (f[h + 1] & 255) >> 6, hdrLen = f[h + 2] & 255;
                    if ((flags & 2) != 0 && h + 8 <= packetEnd) pts = timestamp(f, h + 3);
                    payload = h + 3 + hdrLen;
                } else { // MPEG-1 PES header: stuffing, optional STD buffer, PTS/DTS
                    int p = h; while (p < packetEnd && (f[p] & 255) == 0xFF) p++;
                    if (p < packetEnd && (f[p] & 0xC0) == 0x40) p += 2;
                    if (p < packetEnd && (f[p] & 0xF0) == 0x20) { pts = timestamp(f, p); p += 5; }
                    else if (p < packetEnd && (f[p] & 0xF0) == 0x30) { pts = timestamp(f, p); p += 10; }
                    else p++;
                    payload = p;
                }
                if (payload < packetEnd) {
                    if (pesStarts != null) pesStarts.add(new long[]{es.size(), pts});
                    es.write(f, payload, packetEnd - payload);
                }
            }
            pos = packetEnd;
        }
        return es.toByteArray();
    }
    private static long timestamp(byte[] f, int p) { // 33-bit PTS/DTS in 5 bytes with marker bits
        return ((long)(f[p] & 0x0E) << 29) | ((f[p + 1] & 255L) << 22) | ((f[p + 2] & 0xFEL) << 14) | ((f[p + 3] & 255L) << 7) | ((f[p + 4] & 255L) >> 1);
    }
    private static int nextStartCode(byte[] f, int from, int end) {
        for (int i = from; i + 3 <= end; i++) if (f[i] == 0 && f[i + 1] == 0 && f[i + 2] == 1) return i;
        return end;
    }
    private static int nextPack(byte[] f, int from, int end) { // end of an unbounded (length 0) PES packet
        for (int i = from; i + 4 <= end; i++) if (f[i] == 0 && f[i + 1] == 0 && f[i + 2] == 1 && ((f[i + 3] & 255) == 0xBA || (f[i + 3] & 255) == 0xB9)) return i;
        return end;
    }
    private static int be32(byte[] b, int o) { return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255); }

    // ------------------------------------------------------------------------------------------------ access units
    private static List<AccessUnit> split(byte[] es, List<long[]> pesStarts) {
        List<AccessUnit> out = new ArrayList<>();
        int auStart = -1; boolean hasSlice = false; int pesIndex = 0;
        int nal = findNal(es, 0);
        while (nal >= 0) {
            int payload = nal + ((es[nal + 2] == 1) ? 3 : 4); // after the start code
            if (payload >= es.length) break;
            int type = es[payload] & 0x1F;
            boolean vcl = type == 1 || type == 5;
            boolean startsNew = auStart < 0
                || type == 9
                || (hasSlice && (type == 6 || type == 7 || type == 8 || (type >= 14 && type <= 18)))
                || (hasSlice && vcl && payload + 1 < es.length && (es[payload + 1] & 0x80) != 0); // first_mb_in_slice == 0
            if (startsNew) {
                if (auStart >= 0) { pesIndex = emit(out, es, auStart, nal, pesStarts, pesIndex); }
                auStart = nal; hasSlice = false;
            }
            if (vcl) hasSlice = true;
            nal = findNal(es, payload + 1);
        }
        if (auStart >= 0) emit(out, es, auStart, es.length, pesStarts, pesIndex);
        return out;
    }
    private static int emit(List<AccessUnit> out, byte[] es, int from, int to, List<long[]> pesStarts, int pesIndex) {
        long pts = -1; // the PTS of the PES packet in which this access unit begins, if that packet began inside it
        while (pesIndex < pesStarts.size() && pesStarts.get(pesIndex)[0] < to) {
            long[] p = pesStarts.get(pesIndex);
            if (p[0] >= from && pts < 0) pts = p[1];
            pesIndex++;
        }
        byte[] au = new byte[to - from]; System.arraycopy(es, from, au, 0, au.length);
        out.add(new AccessUnit(au, pts));
        return pesIndex;
    }
    /** Offset of the next start code (00 00 01, or 00 00 00 01 when preceded by a zero) at or after {@code from}, or -1. */
    private static int findNal(byte[] es, int from) {
        for (int i = Math.max(0, from); i + 3 <= es.length; i++) {
            if (es[i] == 0 && es[i + 1] == 0) {
                if (es[i + 2] == 1) return i;
                if (es[i + 2] == 0 && i + 3 < es.length && es[i + 3] == 1) return i;
            }
        }
        return -1;
    }
}
