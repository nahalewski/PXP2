package org.pxp2.core;

import java.io.*;
import java.util.*;

/** Original IoFileMgrForUser implementation over {@link Vfs}. Asynchronous calls complete immediately. */
public final class HleIo {
    public static final int ENOENT = 0x80010002, EBADF = 0x80010009, EACCES = 0x8001000d, EEXIST = 0x80010011, ENOTDIR = 0x80010014,
        EINVAL = 0x80010016, EMFILE = 0x80010018, ENODEV = 0x80010013, UNSUPPORTED = 0x80020325;
    private static final class Handle {
        final String path; RandomAccessFile file; File[] listing; int index; long asyncResult; boolean asyncPending;
        // UMD-backed handles: byte range of the image, current position; sector mode for raw "umd0:" access
        Iso9660 iso; long isoStart, isoLength, position; boolean sectorMode; List<Iso9660.Entry> isoListing;
        // Virtual library overlay (GameLibrary): an in-memory EBOOT.PBP, or extra directory entries after the real ones
        byte[] memory; List<VirtualEntry> extra; boolean closePending;
        Handle(String path) { this.path = path; }
    }
    /** A directory entry of the virtual PSP/GAME overlay. */
    private static final class VirtualEntry {
        final String name; final boolean directory; final long size, time;
        VirtualEntry(String name, boolean directory, long size, long time) { this.name = name; this.directory = directory; this.size = size; this.time = time; }
    }
    private final Kernel k; public final Vfs vfs;
    private final Map<Integer, Handle> handles = new HashMap<>();
    private int nextFd = 3;
    public final StringBuilder stdout = new StringBuilder();
    public final List<Integer> msCallbacks = new ArrayList<>();
    /** Guest I/O drivers registered via sceIoAddDrv (kernel). PXP2 records the registration but does not
     *  dispatch guest opens/reads through the driver's funcs (its HLE I/O serves ms0:/disc0: directly). */
    public int lastAddedDrv; public final List<String> addedDrivers = new ArrayList<>();

    public HleIo(Kernel k, Vfs vfs) { this.k = k; this.vfs = vfs; register(); }
    private int allocateFd() { for (int fd = 3; fd < 64; fd++) if (!handles.containsKey(fd)) return fd; return -1; }

    /** The inserted UMD image, or null. Mounted as disc0:/umd0:/umd1:/isofs0:. */
    public volatile Iso9660 umd;
    private String discPath(String path) {
        if (umd == null || path == null) return null;
        String abs = vfs.absolute(path); return abs != null && abs.startsWith("disc0:") ? abs.substring(6) : null;
    }
    /** Library games shown as virtual ms0:/PSP/GAME/PXPISO_* and PXPHB_* folders (null: none). */
    public volatile GameLibrary library;
    private File gameDir() { Vfs.Location l = vfs.resolve("ms0:/PSP/GAME"); return l == null ? null : l.file; }
    private String umdDiscId() { Iso9660 u = umd; return u == null ? null : u.discId(); }
    /** The visible library entry a guest path belongs to (its folder or its EBOOT.PBP), or null. */
    public GameLibrary.Item virtualItem(String path) {
        GameLibrary lib = library; if (lib == null || path == null) return null;
        String abs = vfs.absolute(path); if (GameLibrary.folderOf(abs) == null) return null;
        return lib.find(abs, gameDir(), umdDiscId());
    }
    private void statVirtual(VirtualEntry e, int at) {
        Memory m = k.memory; m.fill(at, 0, 88);
        m.write32(at, e.directory ? 0x11ff : 0x21ff); m.write32(at + 4, e.directory ? 0x10 : 0x20); m.write64(at + 8, e.directory ? 0 : e.size);
        writeTime(m, at + 16, e.time); writeTime(m, at + 32, e.time); writeTime(m, at + 48, e.time);
    }
    private VirtualEntry virtualEntry(GameLibrary.Item item, String path) {
        String inner = GameLibrary.innerOf(vfs.absolute(path)); if (inner == null) return null;
        long time = library.pbpTime(item);
        return inner.isEmpty() ? new VirtualEntry(item.folder, true, 0, time) : new VirtualEntry("EBOOT.PBP", false, library.pbpLength(item), time);
    }
    /** Reads a whole guest file from the UMD (disc0:/umd0:) or a Vfs device; used by the module loader and game boot. */
    public byte[] readFile(String path) throws IOException {
        GameLibrary.Item item = virtualItem(path);
        if (item != null) { if (!"EBOOT.PBP".equals(GameLibrary.innerOf(vfs.absolute(path)))) throw new FileNotFoundException(path); return library.pbp(item); }
        String inner = discPath(path);
        if (inner == null) return vfs.read(path);
        Iso9660.Entry e = umd.find(inner); if (e == null || e.directory) throw new FileNotFoundException(path);
        return umd.readFile(e);
    }
    private int openDisc(String path, String inner, int flags) {
        if ((flags & 2) != 0) return EACCES; // UMD is read-only
        int fd = allocateFd(); if (fd < 0) return EMFILE;
        Handle h = new Handle("disc0:" + inner); h.iso = umd;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("umd") && (inner.isEmpty() || inner.equals("/"))) { h.sectorMode = true; h.isoStart = 0; h.isoLength = umd.sectors * Iso9660.SECTOR; }
        else {
            try {
                Iso9660.Entry e = umd.find(inner);
                if (e == null) return ENOENT; if (e.directory) return EACCES;
                h.isoStart = e.sector * Iso9660.SECTOR; h.isoLength = e.size;
            } catch (IOException ex) { return ENOENT; }
        }
        handles.put(fd, h); return fd;
    }
    private void statDisc(Iso9660.Entry e, int at) {
        Memory m = k.memory; m.fill(at, 0, 88);
        m.write32(at, e.directory ? 0x116d : 0x216d); m.write32(at + 4, e.directory ? 0x10 : 0x20); m.write64(at + 8, e.directory ? 0 : e.size);
        m.write32(at + 64, (int)e.sector); // private[0]: start sector, as isofs reports it
    }
    int open(String path, int flags) {
        String inner = discPath(path); if (inner != null) return openDisc(path, inner, flags);
        GameLibrary.Item item = virtualItem(path);
        if (item != null) { // virtual library EBOOT.PBP: read-only, served from memory
            VirtualEntry e = virtualEntry(item, path); if (e == null) return ENOENT; if (e.directory) return EACCES;
            if ((flags & 2) != 0) return EACCES;
            int fd = allocateFd(); if (fd < 0) return EMFILE;
            Handle h = new Handle(vfs.absolute(path));
            try { h.memory = library.pbp(item); } catch (IOException ex) { k.log("Library entry unreadable: " + ex.getMessage()); return ENOENT; }
            handles.put(fd, h); return fd;
        }
        byte[] ctf = vfs.ctfOverride(path);
        if (ctf != null) { // CTF theme overlay (CtfLoader): the flash0 file is served from the theme, read-only, like CXMB's redirect
            if ((flags & 2) != 0) return EACCES;
            int fd = allocateFd(); if (fd < 0) return EMFILE;
            Handle h = new Handle(vfs.absolute(path)); h.memory = ctf; handles.put(fd, h);
            k.log("CTF overlay served " + h.path + " (" + ctf.length + " bytes)"); return fd;
        }
        Vfs.Location l = vfs.resolve(path);
        if (l == null) return ENODEV;
        boolean write = (flags & 2) != 0, create = (flags & 0x200) != 0;
        try {
            if (l.file.isDirectory()) return EACCES;
            if (!l.file.exists()) {
                if (!create || !write) return ENOENT;
                if (!l.writable) return EACCES;
                File parent = l.file.getParentFile(); if (parent == null || !parent.isDirectory()) return ENOENT;
            } else if ((flags & 0x800) != 0 && create) return EEXIST;
            if (write && !l.writable) return EACCES;
            int fd = allocateFd(); if (fd < 0) return EMFILE;
            Handle h = new Handle(l.path); h.file = new RandomAccessFile(l.file, write ? "rw" : "r");
            if ((flags & 0x400) != 0 && write) h.file.setLength(0);
            if ((flags & 0x100) != 0) h.file.seek(h.file.length());
            handles.put(fd, h); return fd;
        } catch (IOException e) { return ENOENT; }
    }
    private static void writeTime(Memory m, int at, long millis) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(millis);
        m.write16(at, c.get(Calendar.YEAR)); m.write16(at + 2, c.get(Calendar.MONTH) + 1); m.write16(at + 4, c.get(Calendar.DAY_OF_MONTH));
        m.write16(at + 6, c.get(Calendar.HOUR_OF_DAY)); m.write16(at + 8, c.get(Calendar.MINUTE)); m.write16(at + 10, c.get(Calendar.SECOND));
        m.write32(at + 12, c.get(Calendar.MILLISECOND) * 1000);
    }
    /** Writes a SceIoStat (88 bytes). */
    private void stat(File f, int at) {
        Memory m = k.memory; m.fill(at, 0, 88);
        boolean dir = f.isDirectory();
        m.write32(at, dir ? 0x11ff : 0x21ff); m.write32(at + 4, dir ? 0x10 : 0x20);
        m.write64(at + 8, dir ? 0 : f.length());
        long t = f.lastModified(); writeTime(m, at + 16, t); writeTime(m, at + 32, t); writeTime(m, at + 48, t);
    }

    private void register() {
        String lib = "IoFileMgrForUser";
        k.register(lib, 0x109f50bc, "sceIoOpen", c -> { String p = c.string(c.arg(0)); int fd = open(p, c.arg(1)); k.log(String.format(java.util.Locale.ROOT, "sceIoOpen %s flags=%x -> %08x", p, c.arg(1), fd)); return fd; });
        k.register(lib, 0x89aa9906, "sceIoOpenAsync", c -> { int fd = open(c.string(c.arg(0)), c.arg(1)); if (fd < 0) return fd; Handle h = handles.get(fd); h.asyncResult = fd; h.asyncPending = true; return fd; });
        k.register(lib, 0x810c4bc3, "sceIoClose", c -> close(c.arg(0)));
        k.register(lib, 0xff5940b6, "sceIoCloseAsync", c -> { Handle h = handles.get(c.arg(0)); if (h == null) return EBADF; h.asyncResult = 0; h.asyncPending = true; h.closePending = true; return 0; });
        k.register(lib, 0x6a638d83, "sceIoRead", c -> read(c.arg(0), c.arg(1), c.arg(2)));
        k.register(lib, 0xa0b5a7c2, "sceIoReadAsync", c -> async(c.arg(0), read(c.arg(0), c.arg(1), c.arg(2))));
        k.register(lib, 0x42ec03ac, "sceIoWrite", c -> write(c.arg(0), c.arg(1), c.arg(2)));
        k.register(lib, 0x0facab19, "sceIoWriteAsync", c -> async(c.arg(0), write(c.arg(0), c.arg(1), c.arg(2))));
        k.register(lib, 0x27eb27b8, "sceIoLseek", c -> { c.returnValue64(seek(c.arg(0), c.arg64(2), c.arg(4))); return 0; });
        k.register(lib, 0x68963324, "sceIoLseek32", c -> (int)seek(c.arg(0), c.arg(1), c.arg(2)));
        k.register(lib, 0x71b19e77, "sceIoLseekAsync", c -> async(c.arg(0), seek(c.arg(0), c.arg64(2), c.arg(4))));
        k.register(lib, 0x1b385d8f, "sceIoLseek32Async", c -> async(c.arg(0), seek(c.arg(0), c.arg(1), c.arg(2))));
        k.register(lib, 0xe23eec33, "sceIoWaitAsync", c -> waitAsync(c.arg(0), c.arg(1)));
        k.register(lib, 0x35dbd746, "sceIoWaitAsyncCB", c -> waitAsync(c.arg(0), c.arg(1)));
        k.register(lib, 0x3251ea56, "sceIoPollAsync", c -> waitAsync(c.arg(0), c.arg(1)));
        k.register(lib, 0xb29ddf9c, "sceIoDopen", c -> {
            String inner = discPath(c.string(c.arg(0)));
            if (inner != null) {
                try {
                    Iso9660.Entry e = umd.find(inner); if (e == null || !e.directory) return ENOENT;
                    int fd = allocateFd(); if (fd < 0) return EMFILE;
                    Handle h = new Handle("disc0:" + inner); h.iso = umd; h.isoListing = umd.list(e); handles.put(fd, h); return fd;
                } catch (IOException ex) { return ENOENT; }
            }
            GameLibrary.Item item = virtualItem(c.string(c.arg(0)));
            if (item != null) { // a virtual library folder holds just its EBOOT.PBP
                if (!"".equals(GameLibrary.innerOf(vfs.absolute(c.string(c.arg(0)))))) return ENOENT;
                int fd = allocateFd(); if (fd < 0) return EMFILE;
                Handle h = new Handle(vfs.absolute(c.string(c.arg(0)))); h.listing = new File[0];
                h.extra = Collections.singletonList(new VirtualEntry("EBOOT.PBP", false, library.pbpLength(item), library.pbpTime(item))); handles.put(fd, h); return fd;
            }
            Vfs.Location l = vfs.resolve(c.string(c.arg(0)));
            if (l == null) return ENODEV;
            if (!l.file.isDirectory()) { k.log("sceIoDopen missing " + c.string(c.arg(0))); return ENOENT; }
            int fd = allocateFd(); if (fd < 0) return EMFILE;
            Handle h = new Handle(l.path); File[] list = l.file.listFiles(); if (list == null) list = new File[0];
            Arrays.sort(list); h.listing = list; handles.put(fd, h);
            GameLibrary gl = library;
            if (gl != null && l.path.equalsIgnoreCase("ms0:/PSP/GAME")) { // library games after the real folders, de-duplicated
                h.extra = new ArrayList<>();
                for (GameLibrary.Item i : gl.gameDir(l.file, umdDiscId())) h.extra.add(new VirtualEntry(i.folder, true, 0, gl.pbpTime(i)));
            }
            k.log("sceIoDopen " + c.string(c.arg(0)) + " -> " + list.length + " entries" + (h.extra != null ? " + " + h.extra.size() + " library" : "")); return fd;
        });
        k.register(lib, 0xe3eb004c, "sceIoDread", c -> {
            Handle h = handles.get(c.arg(0)); if (h == null || (h.listing == null && h.isoListing == null)) return EBADF;
            if (h.isoListing != null) {
                if (h.index >= h.isoListing.size()) return 0;
                Iso9660.Entry e = h.isoListing.get(h.index++); statDisc(e, c.arg(1)); c.memory.writeString(c.arg(1) + 88, e.name, 256); return 1;
            }
            int at = c.arg(1); String name;
            if (h.index >= h.listing.length) {
                int x = h.index - h.listing.length; if (h.extra == null || x >= h.extra.size()) return 0;
                VirtualEntry v = h.extra.get(x); h.index++; statVirtual(v, at); name = v.name;
            } else { File f = h.listing[h.index++]; stat(f, at); name = f.getName(); }
            c.memory.writeString(at + 88, name, 256);
            // FAT Memory Stick drivers also fill the caller's private block (d_private at +344) with 8.3 and long names.
            int priv = c.memory.read32(at + 344);
            if (priv != 0 && c.memory.valid(priv, 4)) {
                int size = c.memory.read32(priv);
                if (size >= 0x414 && c.memory.valid(priv, 0x414)) { c.memory.writeString(priv + 4, shortName(name, h.index), 16); c.memory.writeString(priv + 20, name, 1024); }
                else if (size >= 0x13 && c.memory.valid(priv, 0x14)) c.memory.writeString(priv + 4, shortName(name, h.index), 16);
            }
            return 1;
        });
        k.register(lib, 0xeb092469, "sceIoDclose", c -> close(c.arg(0)));
        k.register(lib, 0x06a70004, "sceIoMkdir", c -> {
            Vfs.Location l = vfs.resolve(c.string(c.arg(0))); if (l == null) return ENODEV; if (!l.writable) return EACCES;
            if (l.file.exists()) return EEXIST; return l.file.mkdir() ? 0 : ENOENT;
        });
        k.register(lib, 0x1117c65f, "sceIoRmdir", c -> {
            Vfs.Location l = vfs.resolve(c.string(c.arg(0))); if (l == null) return ENODEV; if (!l.writable) return EACCES;
            return l.file.isDirectory() && l.file.delete() ? 0 : ENOENT;
        });
        k.register(lib, 0xf27a9c51, "sceIoRemove", c -> {
            Vfs.Location l = vfs.resolve(c.string(c.arg(0))); if (l == null) return ENODEV; if (!l.writable) return EACCES;
            return l.file.isFile() && l.file.delete() ? 0 : ENOENT;
        });
        k.register(lib, 0x779103a0, "sceIoRename", c -> {
            Vfs.Location a = vfs.resolve(c.string(c.arg(0))), b = vfs.resolve(c.string(c.arg(1)));
            if (a == null || b == null) return ENODEV; if (!a.writable || !b.writable) return EACCES;
            if (!a.file.exists()) return ENOENT; return a.file.renameTo(b.file) ? 0 : EEXIST;
        });
        k.register(lib, 0xace946e8, "sceIoGetstat", c -> {
            String inner = discPath(c.string(c.arg(0)));
            if (inner != null) { try { Iso9660.Entry e = umd.find(inner); if (e == null) return ENOENT; statDisc(e, c.arg(1)); return 0; } catch (IOException ex) { return ENOENT; } }
            GameLibrary.Item item = virtualItem(c.string(c.arg(0)));
            if (item != null) { VirtualEntry e = virtualEntry(item, c.string(c.arg(0))); if (e == null) return ENOENT; statVirtual(e, c.arg(1)); return 0; }
            byte[] ctf = vfs.ctfOverride(c.string(c.arg(0))); // CTF theme overlay: the file exists with the theme's size
            if (ctf != null) { String a = vfs.absolute(c.string(c.arg(0))); statVirtual(new VirtualEntry(a.substring(a.lastIndexOf('/') + 1), false, ctf.length, System.currentTimeMillis()), c.arg(1)); return 0; }
            Vfs.Location l = vfs.resolve(c.string(c.arg(0))); if (l == null) return ENODEV;
            if (!l.file.exists()) { k.log("sceIoGetstat missing " + c.string(c.arg(0))); return ENOENT; } stat(l.file, c.arg(1)); return 0;
        });
        k.register(lib, 0xb8a740f4, "sceIoChstat", c -> {
            Vfs.Location l = vfs.resolve(c.string(c.arg(0))); if (l == null) return ENODEV; return l.file.exists() ? 0 : ENOENT;
        });
        k.register(lib, 0x55f4717d, "sceIoChdir", c -> { String p = vfs.absolute(c.string(c.arg(0))); if (p == null) return EINVAL; vfs.cwd = p; return 0; });
        k.register(lib, 0xb2a628c1, "sceIoAssign", c -> { k.log("sceIoAssign " + c.string(c.arg(0)) + " <- " + c.string(c.arg(1))); return 0; });
        k.register(lib, 0x6d08a871, "sceIoUnassign", c -> 0);
        k.register(lib, 0x54f5fb11, "sceIoDevctl", c -> devctl(c.string(c.arg(0)), c.arg(1), c.arg(2), c.arg(3), c.arg(4), c.arg(5)));
        k.register(lib, 0x63632449, "sceIoIoctl", c -> {
            Handle h = handles.get(c.arg(0)); if (h == null) return EBADF;
            throw new Kernel.Stop(String.format(Locale.ROOT, "Unsupported sceIoIoctl %08x on %s", c.arg(1), h.path));
        });
        k.register(lib, 0x08bd7374, "sceIoGetDevType", c -> { Handle h = handles.get(c.arg(0)); if (h == null) return EBADF; return h.listing != null ? 0x10 : 0x20; });
        k.register(lib, 0xe8bc6571, "sceIoCancel", c -> handles.containsKey(c.arg(0)) ? 0 : EBADF);
        k.register(lib, 0xb293727f, "sceIoChangeAsyncPriority", c -> 0);
        k.register(lib, 0xa12a0514, "sceIoSetAsyncCallback", c -> 0);
        // Kernel I/O driver registration (IoFileMgrForKernel). A kernel module (e.g. a CFW's own umd0: driver)
        // registers a PspIoDrv here. PXP2's kernel is HLE and serves ms0:/disc0: directly, so it records the
        // registration and returns success but does NOT route guest opens/reads through the driver's funcs.
        String kio = "IoFileMgrForKernel";
        k.register(kio, 0x8e982a74, "sceIoAddDrv", c -> {
            int drv = c.arg(0); lastAddedDrv = drv;
            int namePtr = drv != 0 ? k.memory.read32(drv) : 0;
            String name = namePtr != 0 ? k.string(namePtr) : "?";
            addedDrivers.add(name);
            k.log("sceIoAddDrv: registered I/O driver '" + name + "' (PspIoDrv @" + Integer.toHexString(drv)
                + "); HLE I/O does not dispatch guest reads to it");
            return 0;
        });
        k.register(kio, 0xc7f35804, "sceIoDelDrv", c -> {
            String name = c.arg(0) != 0 ? k.string(c.arg(0)) : "?"; addedDrivers.remove(name);
            k.log("sceIoDelDrv: '" + name + "'"); return 0;
        });
    }
    /** FAT-style 8.3 alias: names that already fit are upper-cased, others become BASE~N.EXT. */
    static String shortName(String name, int n) {
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_$~!#%&\\-{}()@'`^]", "");
        String ext = dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") : "";
        if (ext.length() > 3) ext = ext.substring(0, 3);
        boolean fits = dot > 0 ? name.substring(0, dot).length() <= 8 && name.substring(0, dot).equals(base) : name.length() <= 8 && name.equals(base);
        if (!fits || base.isEmpty()) { String tail = "~" + n; base = (base.isEmpty() ? "FILE" : base).substring(0, Math.min(8 - tail.length(), Math.max(1, base.length()))) + tail; }
        return ext.isEmpty() ? base : base + "." + ext;
    }
    private int async(int fd, long result) { Handle h = handles.get(fd); if (h == null) return EBADF; h.asyncResult = result; h.asyncPending = true; return 0; }
    private int waitAsync(int fd, int resultAddress) {
        Handle h = handles.get(fd); if (h == null) return EBADF;
        if (!h.asyncPending) return 1; // no pending operation
        h.asyncPending = false; if (resultAddress != 0) k.memory.write64(resultAddress, h.asyncResult);
        if (h.closePending) close(fd); // sceIoCloseAsync completes here
        return 0;
    }
    private int close(int fd) {
        Handle h = handles.get(fd); if (h == null) return EBADF;
        if (h.asyncPending) { h.asyncPending = false; }
        try { if (h.file != null) h.file.close(); } catch (IOException ignored) { }
        handles.remove(fd); return 0;
    }
    /** I/O error returned to the guest when a streamed disc cannot be read (network failure). */
    public static final int EIO = 0x80010005;
    private int readFailures;
    private int read(int fd, int address, int size) {
        Handle h = handles.get(fd);
        if (h != null && h.memory != null) {
            if (size < 0) return EINVAL;
            int n = (int)Math.max(0, Math.min(size, h.memory.length - h.position)); if (n > 0) k.memory.write(address, h.memory, (int)h.position, n);
            h.position += n; return n;
        }
        if (h != null && h.iso != null && h.isoListing == null) {
            if (size < 0) return EINVAL;
            long unit = h.sectorMode ? Iso9660.SECTOR : 1, bytes = Math.min((long)size * unit, h.isoLength - h.position * unit);
            if (bytes <= 0) return 0;
            try {
                byte[] buffer = new byte[(int)bytes]; int n = h.iso.read(h.isoStart + h.position * unit, buffer, 0, buffer.length);
                k.memory.write(address, buffer, 0, n); h.position += n / unit; return (int)(n / unit);
            } catch (IOException e) { // e.g. the hub streaming the disc went away: the guest sees an I/O error, the emulator keeps running
                if (++readFailures <= 5 || readFailures % 500 == 0) k.log("UMD read failed (" + readFailures + ") on " + h.path + ": " + e.getMessage());
                return EIO;
            }
        }
        if (h == null || h.file == null) return EBADF;
        if (size < 0) return EINVAL;
        try {
            byte[] buffer = new byte[(int)Math.min(size, Math.max(0, h.file.length() - h.file.getFilePointer()))];
            int n = h.file.read(buffer); if (n <= 0) return 0;
            k.memory.write(address, buffer, 0, n); return n;
        } catch (IOException e) { return EINVAL; }
    }
    private int write(int fd, int address, int size) {
        if (fd == 1 || fd == 2) { String text = new String(k.memory.read(address, size), java.nio.charset.StandardCharsets.UTF_8); if (stdout.length() < 65536) stdout.append(text); return size; }
        Handle h = handles.get(fd); if (h == null || h.file == null) return EBADF;
        try { h.file.write(k.memory.read(address, size)); return size; } catch (IOException e) { return EACCES; }
    }
    private long seek(int fd, long offset, int whence) {
        Handle h = handles.get(fd);
        if (h != null && h.memory != null) {
            long target = (whence == 0 ? 0 : whence == 1 ? h.position : h.memory.length) + offset;
            if (target < 0 || whence < 0 || whence > 2) return EINVAL; h.position = target; return target;
        }
        if (h != null && h.iso != null && h.isoListing == null) { // offsets are in sectors for raw UMD access
            long end = h.sectorMode ? h.isoLength / Iso9660.SECTOR : h.isoLength;
            long target = (whence == 0 ? 0 : whence == 1 ? h.position : end) + offset;
            if (target < 0 || whence < 0 || whence > 2) return EINVAL; h.position = target; return target;
        }
        if (h == null || h.file == null) return EBADF;
        try {
            long base = whence == 0 ? 0 : whence == 1 ? h.file.getFilePointer() : h.file.length(), target = base + offset;
            if (target < 0 || whence < 0 || whence > 2) return EINVAL;
            h.file.seek(target); return target;
        } catch (IOException e) { return EINVAL; }
    }
    /**
     * UMD disc type for devctl 0x01f20001: 0x10 game, 0x20 video, 0x40 audio (OR-ed for mixed discs). vshmain requires it to
     * match the kind letters (G/V/A) it finds in UMD_DATA.BIN bytes 33..46, so it is derived from the same place.
     */
    int discType() {
        try {
            Iso9660.Entry e = umd == null ? null : umd.find("/UMD_DATA.BIN");
            if (e != null && e.size <= 4096) {
                byte[] b = umd.readFile(e); int type = 0;
                for (int i = 33; i < Math.min(47, b.length); i++) type |= b[i] == 'G' ? 0x10 : b[i] == 'V' ? 0x20 : b[i] == 'A' ? 0x40 : 0;
                if (type != 0) return type;
            }
            if (umd != null && umd.find("/PSP_GAME") == null && umd.find("/UMD_VIDEO") != null) return 0x20;
        } catch (java.io.IOException ignored) { }
        return 0x10;
    }
    public int devctl(String device, int command, int in, int inLength, int out, int outLength) {
        Memory m = k.memory; String d = device == null ? "" : device.toLowerCase(Locale.ROOT);
        if (d.startsWith("flash") && command == 0x5802) return 0; // flush the FAT device (host writes are already durable)
        // msstor.prx 0x02125802: Memory Stick media type (0x10/0x11 or 0x40/0x41 reported, others 0). PXP2 reports a PRO stick.
        if (d.startsWith("msstor") && command == 0x02125802) { if (out == 0 || outLength < 4) return 0x80220183; m.write32(out, 0x10); return 0; }
        if (d.startsWith("ms") || d.startsWith("fatms")) {
            switch (command) {
                case 0x02425818: { // free space: *(u32*)in -> {total clusters, free clusters, free clusters, sector size, sectors per cluster}
                    int p = m.read32(in); m.write32(p, 0x100000); m.write32(p + 4, 0xc0000); m.write32(p + 8, 0xc0000); m.write32(p + 12, 512); m.write32(p + 16, 64); return 0;
                }
                case 0x02415821: { int cb = m.read32(in); msCallbacks.add(cb); k.notifyCallback(cb, 1); return 0; } // insert/eject callback, notify inserted
                case 0x02415822: msCallbacks.remove((Integer)m.read32(in)); return 0;
                case 0x02025806: if (out != 0) m.write32(out, 1); return 0; // inserted
                case 0x02025801: if (out != 0) m.write32(out, 4); return 0; // ready
                case 0x02425823: if (out != 0) m.write32(out, 1); return 0; // assigned
                case 0x02425824: if (out != 0) m.write32(out, 0); return 0; // not write protected
                case 0x02425856: k.log(String.format(Locale.ROOT, "fatms setting 0x02425856 = %08x", m.read32(in))); return 0; // vshmain one-word setter
                default: break;
            }
        }
        if (d.startsWith("umd") || d.startsWith("isofs") || d.startsWith("disc")) {
            if (umd == null) return 0x80010013; // no medium
            switch (command) {
                case 0x01f20001: if (out != 0 && outLength >= 8) { m.write32(out + 4, discType()); } return 0; // disc type at +4 (+0 is the caller's size field)
                case 0x01f20002: case 0x01f20003: if (out != 0) m.write32(out, (int)umd.sectors); return 0; // current / total LBA count
                case 0x01f100a3: case 0x01f100a4: case 0x01f100a6: case 0x01f100a8: case 0x01f100a9: return 0; // seek / cache hints
                case 0x01f300a5: if (out != 0) m.write32(out, 1); return 0; // prepare cache: done
                case 0x01e18030: return 1; // "is the UMD a game disc" query used by the XMB
                default: break;
            }
        }
        throw new Kernel.Stop(String.format(Locale.ROOT, "Unsupported sceIoDevctl %s cmd=%08x in=%08x/%d out=%08x/%d", device, command, in, inLength, out, outLength));
    }
}
