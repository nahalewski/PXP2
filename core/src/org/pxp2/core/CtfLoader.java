package org.pxp2.core;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Applies a CTF (CXMB theme file) the way the CXMB plugin behaves on hardware: flash0 file redirection, whole-module
 * replacement and in-memory module patches applied after each module loads and before its module_start runs. This is an
 * original implementation from the file format alone (see psp.md, "CTF themes").
 *
 * <h3>Layout</h3>
 * <pre>
 *  0x00  a complete, normal PTF (\0PTF). Theme Settings lists it; Apply copies the WHOLE CTF to
 *        flash1:/vsh/theme/custom_theme.dat, and the XMB draws its PTF part as usual.
 *  0x10  u32 magic  = 0xDEAD0660 (the target firmware, 6.60)
 *  0x14  u32 id     (theme identifier, not validated)
 *  0x18  u32 count  entries in the table
 *  0x1c  u32 ptfEnd end of the PTF part (payloads follow)
 *  table: the last count × 0x48 bytes: char path[64] (flash0-relative), u32 offset, u32 size
 * </pre>
 *
 * <h3>Entry kinds</h3>
 * <ul>
 * <li><b>File redirect</b> — any path whose payload is the file itself (.rco, .pmf, .bmp, .pgf, .dat, and a .prx whose
 *     payload starts with an ELF or ~PSP header). Served in place of the flash0 file ({@link Vfs#mountCtfEntry}; the
 *     module loader and sceIoOpen/sceIoGetstat both see it).</li>
 * <li><b>Patch list</b> — a .prx entry whose payload is not a module: {@code size} records of {u32 offset, u32 len,
 *     byte[len]}. {@code offset} is relative to the module's load address (text start, ELF vaddr 0); the bytes are
 *     written into the loaded module. Records outside the module (e.g. vshmain's -0xa0 record, which lands in the ELF
 *     header in file terms) are skipped.</li>
 * <li><b>Float table</b> — {@code /vsh/module/NAME.bin}: one little-endian float per float-constant load site of
 *     NAME.prx, in reverse code order. A site is {@code lui $at,hi} [{@code ori $at,$at,lo}] immediately followed by
 *     {@code mtc1 $at,$fN}. Each site gets the table's value (these are the XMB's layout, wave and animation
 *     constants). Applied only when the site count matches the table exactly.</li>
 * </ul>
 */
public final class CtfLoader {
    /** Magic word at offset 0x10 of a CTF file. */
    public static final int CTF_MAGIC = 0xDEAD0660;

    /** Returns true if {@code data} carries the CTF header magic. */
    public static boolean isCTF(byte[] data) {
        return data != null && data.length >= 0x20 && word(data, 0x10) == CTF_MAGIC;
    }

    /** One entry of the CTF table, classified. */
    public static final class Entry {
        public enum Kind { FILE, PATCH_LIST, FLOAT_TABLE, INVALID }
        public final String path; public final int offset, size; public Kind kind;
        /** FILE: the payload. PATCH_LIST: the records (offset, len, data...) as parsed. FLOAT_TABLE: the float words. */
        public byte[] payload; public final List<int[]> recordHeads = new ArrayList<>(); public final List<byte[]> recordData = new ArrayList<>();
        public int[] floats;
        Entry(String path, int offset, int size) { this.path = path; this.offset = offset; this.size = size; }
        /** The module file name this entry patches ("paf.prx"), or null. */
        public String module() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return kind == Kind.FLOAT_TABLE ? name.substring(0, name.length() - 4) + ".prx" : kind == Kind.PATCH_LIST ? name : null;
        }
        public String toString() {
            switch (kind) {
                case PATCH_LIST: return String.format(Locale.ROOT, "%s: patch list, %d records", path, recordHeads.size());
                case FLOAT_TABLE: return String.format(Locale.ROOT, "%s: float table, %d values", path, floats.length);
                case FILE: return String.format(Locale.ROOT, "%s: file, 0x%x bytes", path, size);
                default: return path + ": invalid";
            }
        }
    }

    /** Parses and classifies the entry table (no side effects). Returns an empty list for a non-CTF. */
    public static List<Entry> parse(byte[] data) {
        List<Entry> out = new ArrayList<>();
        if (!isCTF(data)) return out;
        int count = word(data, 0x18);
        if (count <= 0 || count > 1024) return out;
        int tableOff = data.length - count * 0x48;
        if (tableOff < 0x20) return out;
        for (int i = 0; i < count; i++) {
            int p = tableOff + i * 0x48;
            int end = p; while (end < p + 64 && data[end] != 0) end++;
            String path = new String(data, p, end - p, StandardCharsets.ISO_8859_1).replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!path.startsWith("/")) path = "/" + path;
            Entry e = new Entry(path, word(data, p + 64), word(data, p + 68)); out.add(e);
            e.kind = Entry.Kind.INVALID;
            if (e.offset < 0 || e.size < 0 || e.offset >= tableOff) continue;
            boolean module = path.endsWith(".prx");
            boolean moduleImage = module && e.offset + 4 <= tableOff && (word(data, e.offset) == 0x464c457f || word(data, e.offset) == 0x5053507e);
            if (module && !moduleImage) { // patch list: size is the record count
                int q = e.offset;
                for (int r = 0; r < e.size; r++) {
                    if (q + 8 > tableOff) { q = -1; break; }
                    int off = word(data, q), len = word(data, q + 4);
                    if (len <= 0 || len > 0x10000 || q + 8 + len > tableOff) { q = -1; break; }
                    e.recordHeads.add(new int[]{off, len}); e.recordData.add(Arrays.copyOfRange(data, q + 8, q + 8 + len)); q += 8 + len;
                }
                if (q >= 0) e.kind = Entry.Kind.PATCH_LIST;
            } else if (path.startsWith("/vsh/module/") && path.endsWith(".bin")) {
                if (e.size % 4 != 0 || e.size == 0 || (long)e.offset + e.size > tableOff) continue;
                e.floats = new int[e.size / 4];
                for (int j = 0; j < e.floats.length; j++) e.floats[j] = word(data, e.offset + j * 4);
                e.kind = Entry.Kind.FLOAT_TABLE;
            } else if (e.size > 0 && (long)e.offset + e.size <= tableOff) {
                e.payload = Arrays.copyOfRange(data, e.offset, e.offset + e.size);
                e.kind = Entry.Kind.FILE;
            }
        }
        return out;
    }

    /**
     * Installs a CTF: file entries become flash0 overrides on {@code vfs}, and patch lists and float tables are
     * registered as a {@link HleModules.PostLoadPatcher} applied to the named module whenever it loads (before its
     * module_start). Each applied item is logged with a "CTF" prefix.
     *
     * @return the number of entries installed (file overrides plus module patches), or 0 if {@code data} is not a CTF
     */
    public static int overlay(Vfs vfs, byte[] data, Kernel k) {
        List<Entry> entries = parse(data);
        Map<String, List<Entry>> patches = new HashMap<>();
        int installed = 0;
        for (Entry e : entries) {
            switch (e.kind) {
                case FILE:
                    vfs.mountCtfEntry("flash0:" + e.path, e.payload);
                    k.log(String.format(Locale.ROOT, "CTF overlay: flash0:%s (0x%x bytes)", e.path, e.size)); installed++; break;
                case PATCH_LIST: case FLOAT_TABLE:
                    patches.computeIfAbsent(e.module(), x -> new ArrayList<>()).add(e);
                    k.log("CTF module patch registered: " + e); installed++; break;
                default:
                    k.log(String.format(Locale.ROOT, "CTF entry skipped (invalid): %s offset=0x%x size=0x%x", e.path, e.offset, e.size));
            }
        }
        if (!patches.isEmpty() && k.modules != null) k.modules.postLoadPatchers.add(m -> {
            if (m.prx == null || m.path == null || m.prx.size <= 0) return;
            String lower = m.path.toLowerCase(Locale.ROOT);
            List<Entry> list = patches.get(lower.substring(lower.lastIndexOf('/') + 1));
            if (list == null || !lower.contains("/vsh/module/")) return;
            for (Entry e : list) {
                try { if (e.kind == Entry.Kind.PATCH_LIST) applyRecords(k, m, e); else applyFloats(k, m, e); }
                catch (RuntimeException ex) { k.log("CTF patch failed on " + m.prx.name + ": " + ex); }
            }
        });
        return installed;
    }

    /** Writes each record's bytes at module base + offset. */
    static void applyRecords(Kernel k, HleModules.LoadedModule m, Entry e) {
        Memory mem = k.memory; int base = m.prx.base, applied = 0;
        for (int i = 0; i < e.recordHeads.size(); i++) {
            int off = e.recordHeads.get(i)[0]; byte[] b = e.recordData.get(i);
            if (off < 0 || (long)off + b.length > m.prx.size) {
                k.log(String.format(Locale.ROOT, "CTF patch skipped: %s %+d (%d bytes) lies outside the module", m.prx.name, off, b.length)); continue;
            }
            int word = mem.read32(base + (off & ~3));
            StringBuilder before = new StringBuilder(), after = new StringBuilder();
            for (int j = 0; j < b.length; j++) { before.append(String.format(Locale.ROOT, "%02x", mem.read8(base + off + j))); after.append(String.format(Locale.ROOT, "%02x", b[j] & 255)); mem.write8(base + off + j, b[j] & 255); }
            k.log(String.format(Locale.ROOT, "CTF patch: %s +0x%x %s -> %s (was %08x = %s)", m.prx.name, off, before, after, word, Disasm.instruction(base + (off & ~3), word)));
            applied++;
        }
        k.log(String.format(Locale.ROOT, "CTF patch list %s: %d of %d records applied at %08x", e.path, applied, e.recordHeads.size(), base));
    }

    /** Float-constant load sites of a module: word addresses of {@code lui $at} (and its {@code ori}, or 0), in code order. */
    static List<int[]> floatSites(Memory mem, int start, int size) {
        List<int[]> sites = new ArrayList<>();
        for (int a = start; a + 8 <= start + size; a += 4) {
            int w = mem.read32(a); if ((w >>> 16) != 0x3c01) continue; // lui $at, hi
            int next = a + 4, ori = 0;
            if ((mem.read32(next) >>> 16) == 0x3421) { ori = next; next += 4; } // ori $at, $at, lo
            if (next + 4 <= start + size && (mem.read32(next) & 0xffff07ff) == 0x44810000) sites.add(new int[]{a, ori}); // mtc1 $at, $fN
        }
        return sites;
    }

    /** Stores the table's values into the module's float load sites (table order = reverse code order). */
    static void applyFloats(Kernel k, HleModules.LoadedModule m, Entry e) {
        Memory mem = k.memory; int text = m.prx.textSize > 0 ? m.prx.textSize : m.prx.size;
        List<int[]> sites = floatSites(mem, m.prx.base, text);
        if (sites.size() != e.floats.length) {
            k.log(String.format(Locale.ROOT, "CTF float table %s skipped: %s has %d float load sites, the table %d (other firmware build)", e.path, m.prx.name, sites.size(), e.floats.length));
            return;
        }
        int changed = 0, approximated = 0;
        for (int i = 0; i < e.floats.length; i++) {
            int[] s = sites.get(sites.size() - 1 - i); int lui = mem.read32(s[0]), ori = s[1] != 0 ? mem.read32(s[1]) : 0;
            int old = (lui << 16) | (ori & 0xffff), value = e.floats[i];
            if (old == value) continue;
            mem.write32(s[0], (lui & 0xffff0000) | (value >>> 16));
            if (s[1] != 0) mem.write32(s[1], (ori & 0xffff0000) | (value & 0xffff));
            else if ((value & 0xffff) != 0) approximated++; // no ori at this site: only the upper half can change
            int now = (value & 0xffff0000) | (s[1] != 0 ? value & 0xffff : 0);
            k.log(String.format(Locale.ROOT, "CTF float: %s +0x%x %s -> %s", m.prx.name, s[0] - m.prx.base, Float.intBitsToFloat(old), Float.intBitsToFloat(now)));
            changed++;
        }
        k.log(String.format(Locale.ROOT, "CTF float table %s: %d of %d constants changed in %s%s", e.path, changed, e.floats.length, m.prx.name,
            approximated > 0 ? " (" + approximated + " rounded: no ori at the site)" : ""));
    }

    private static int word(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off+1] & 0xff) << 8) | ((b[off+2] & 0xff) << 16) | ((b[off+3] & 0xff) << 24);
    }
}
