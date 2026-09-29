package org.pxp2.core;

import java.io.IOException;
import java.util.*;

/**
 * Original PRX module manager and dynamic linker. Imports resolve to exports of other loaded
 * modules when available (real Sony code runs), otherwise to HLE syscall stubs. Exports loaded
 * later are patched into earlier modules' stubs, so load order does not matter.
 */
public final class HleModules {
    public static final class LoadedModule {
        public int uid, block; public String path; public PrxLoader.Module prx; public boolean started;
        public String toString() { return String.format(Locale.ROOT, "%s @%08x size=%x (%s)", prx.name, prx.base, prx.size, path); }
    }
    /** An encrypted module whose envelope PXP2 cannot open (no key for its tag); carries the ~PSP module name. */
    public static final class EncryptedModuleException extends IOException {
        public final String moduleName;
        EncryptedModuleException(String message, String moduleName) { super(message); this.moduleName = moduleName; }
    }
    private final Kernel k;
    public final List<LoadedModule> modules = new ArrayList<>();
    private final Map<String, Integer> functionExports = new HashMap<>(), variableExports = new HashMap<>();
    private final Map<String, List<Integer>> pendingStubs = new HashMap<>(), pendingVariables = new HashMap<>();
    /** Libraries always served by HLE even if a loaded module exports them. */
    public final Set<String> hleOnly = new HashSet<>();

    public HleModules(Kernel k) {
        this.k = k; register();
        k.exitListeners.add((thread, status) -> {
            boolean startThread = false;
            for (Kernel.GuestThread other : k.threads()) if (other.wait != null && other.wait.type.equals("ModuleStart") && other.wait.id == thread.uid) {
                int[] info = (int[])other.wait.data; if (info[1] != 0) k.memory.write32(info[1], status);
                k.wake(other, status < 0 && info[2] != 0 ? status : info[0]); startThread = true;
            }
            // Like the firmware, the module_start/module_stop thread is deleted once it returns (frees its stack).
            if (startThread && thread.state != Kernel.State.DEAD) k.deleteThread(thread);
        });
    }

    private static int allocationSize(byte[] elf) throws IOException {
        int ph = FirmwareCrypto.word(elf, 28), count = (elf[44] & 255) | ((elf[45] & 255) << 8), size = 0;
        for (int i = 0; i < count && i < 16; i++) {
            int p = ph + i * 32; if (FirmwareCrypto.word(elf, p) != 1) continue;
            size = Math.max(size, FirmwareCrypto.word(elf, p + 8) + FirmwareCrypto.word(elf, p + 20));
        }
        if (size <= 0 || size > Memory.MAX_SIZE) throw new IOException("Invalid PRX allocation size");
        return size;
    }
    public LoadedModule load(String path, byte[] data, int partition) throws IOException { return load(path, data, partition, SysMem.LOW); }
    /** As {@link #load(String, byte[], int)} with a SysMem allocation type (plugins use HIGH for user memory; see Plugins). */
    public LoadedModule load(String path, byte[] data, int partition, int allocation) throws IOException {
        // Game-disc modules may carry an outer "~SCE" header (its size at +4, normally 0x40) in front of the ~PSP module.
        if (data.length >= 8 && FirmwareCrypto.word(data, 0) == 0x4543537e) {
            int skip = FirmwareCrypto.word(data, 4); if (skip <= 0 || skip >= data.length) throw new IOException("Invalid ~SCE header: " + path);
            data = Arrays.copyOfRange(data, skip, data.length);
        }
        byte[] elf;
        try { elf = FirmwareInstaller.decryptPrx(data); }
        catch (IOException e) {
            String name = data.length >= 0x26 ? new String(data, 0x0a, 28, java.nio.charset.StandardCharsets.US_ASCII).split("\0", 2)[0] : "?";
            throw new EncryptedModuleException(String.format(Locale.ROOT, "%s: ~PSP module '%s' tag %08x: %s", path, name, data.length >= 0xd4 ? FirmwareCrypto.word(data, 0xd0) : 0, e.getMessage()), name);
        }
        if (elf.length < 52 || FirmwareCrypto.word(elf, 0) != 0x464c457f) throw new IOException(String.format(Locale.ROOT, "Not a PRX ELF: %s (%d bytes, magic %08x)", path, elf.length, elf.length >= 4 ? FirmwareCrypto.word(elf, 0) : 0));
        int[] fixed = PrxLoader.staticRange(elf); // static homebrew ELF: reserve its link addresses and load without relocation
        int size = fixed[1] != 0 ? fixed[1] - fixed[0] : allocationSize(elf);
        int base = fixed[1] != 0 ? k.sysmem.allocate(partition, path, SysMem.ADDR, size, fixed[0]) : k.sysmem.allocate(partition, path, allocation, size, 0);
        if (base == 0) throw new IOException(fixed[1] != 0 ? String.format(Locale.ROOT, "Cannot reserve %08x-%08x for static ELF %s", fixed[0], fixed[1], path) : "Out of memory loading " + path);
        LoadedModule m = new LoadedModule(); m.block = base; m.path = path;
        try { m.prx = PrxLoader.load(elf, k.memory, fixed[1] != 0 ? 0 : base); }
        catch (IOException | RuntimeException e) { k.sysmem.free(base); throw new IOException(path + ": " + e.getMessage(), e); }
        m.uid = k.newUid(m); modules.add(m);
        link(m);
        if (onLinked != null) onLinked.accept(m); // plugin support: HleSystemCtrl re-applies syscall patches to new imports
        k.log("Loaded " + m);
        for (PostLoadPatcher p : postLoadPatchers) p.patch(m);
        return m;
    }
    /**
     * Hook point: called for every module right after it is mapped, relocated and linked, and before its module_start
     * runs (the moment a CFW plugin's module-start hook sees it). Used by CtfLoader (CTF theme patch records and float
     * tables); other features (e.g. seplugins) may register their own patchers here. Patchers must not throw.
     */
    public interface PostLoadPatcher { void patch(LoadedModule m); }
    public final List<PostLoadPatcher> postLoadPatchers = new ArrayList<>();
    private void writeStub(int address, String library, int nid) {
        String key = Kernel.key(library, nid); Integer target = hleOnly.contains(library) ? null : functionExports.get(key);
        if (target != null) { k.memory.write32(address, 0x08000000 | ((target >>> 2) & 0x03ffffff)); k.memory.write32(address + 4, 0); }
        else {
            k.memory.write32(address, 0x03e00008); k.memory.write32(address + 4, (k.syscallCode(library, nid) << 6) | 12);
            pendingStubs.computeIfAbsent(key, x -> new ArrayList<>()).add(address);
        }
    }
    private void link(LoadedModule m) {
        PrxLoader.Module prx = m.prx;
        for (PrxLoader.Symbol s : prx.imports) writeStub(s.address, s.library, s.nid);
        for (PrxLoader.Symbol s : prx.importVariables) {
            // The table holds (relocation list, nid) pairs; detect orientation by which word points into the module.
            int list = s.address, nid = s.nid;
            if (!(Integer.compareUnsigned(list - prx.base, prx.size) < 0) && Integer.compareUnsigned(nid - prx.base, prx.size) < 0) { int t = list; list = nid; nid = t; }
            String key = Kernel.key(s.library, nid); Integer value = variableExports.get(key);
            if (value != null) applyVariable(list, value); else pendingVariables.computeIfAbsent(key, x -> new ArrayList<>()).add(list);
        }
        for (PrxLoader.Symbol s : prx.exports) {
            if (s.library.isEmpty() || hleOnly.contains(s.library)) continue;
            for (String library : new String[]{s.library, EXPORT_ALIASES.get(s.library)}) {
                if (library == null) continue;
                String key = Kernel.key(library, s.nid); functionExports.put(key, s.address);
                List<Integer> stubs = pendingStubs.remove(key);
                if (stubs != null) for (int a : stubs) { k.memory.write32(a, 0x08000000 | ((s.address >>> 2) & 0x03ffffff)); k.memory.write32(a + 4, 0); }
            }
        }
        for (PrxLoader.Symbol s : prx.exportVariables) {
            if (s.library.isEmpty()) continue;
            String key = Kernel.key(s.library, s.nid); variableExports.put(key, s.address);
            List<Integer> lists = pendingVariables.remove(key);
            if (lists != null) for (int list : lists) applyVariable(list, s.address);
        }
    }
    /** Applies a variable-import relocation list: entries are (type << 26 | address >> 2), zero terminated. */
    private void applyVariable(int list, int value) {
        Memory mem = k.memory; List<Integer> his = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            int raw = mem.read32(list + i * 4); if (raw == 0) return;
            int type = raw >>> 26, address = (raw & 0x03ffffff) << 2, word = mem.read32(address);
            switch (type) {
                case 2: mem.write32(address, word + value); break;
                case 4: mem.write32(address, (word & 0xfc000000) | ((word + (value >>> 2)) & 0x03ffffff)); break;
                case 5: his.add(address); break;
                case 6: {
                    int lo = (short)word;
                    for (int hi : his) { int h = mem.read32(hi); int full = ((h & 0xffff) << 16) + lo + value; mem.write32(hi, (h & 0xffff0000) | (((full + 0x8000) >>> 16) & 0xffff)); }
                    his.clear(); mem.write32(address, (word & 0xffff0000) | ((lo + value) & 0xffff)); break;
                }
                default: throw new Kernel.Stop(String.format(Locale.ROOT, "Unsupported variable import relocation type %d at %08x", type, address));
            }
        }
    }
    public int unresolvedVariables() { int n = 0; for (List<Integer> l : pendingVariables.values()) n += l.size(); return n; }
    public List<String> unresolvedVariableNames() { return new ArrayList<>(pendingVariables.keySet()); }

    /** Starts module_start on a new thread. Returns the thread, or null when the module has no entry. */
    public Kernel.GuestThread start(LoadedModule m, int argSize, int argPointer) {
        m.started = true;
        if (m.prx.moduleStart == 0 || m.prx.moduleStart == -1) return null;
        boolean kernelMode = (m.prx.attributes & 0x1000) != 0; // kernel modules run on kernel threads with kernel stacks
        int priority = m.prx.startPriority > 0 ? m.prx.startPriority : 0x20, stack = m.prx.startStack > 0 ? m.prx.startStack : kernelMode ? 0x4000 : 0x40000;
        int attributes = kernelMode ? m.prx.startAttributes & ~0x80100000 : (m.prx.startAttributes | 0x80000000) & ~0x00100000;
        Kernel.GuestThread t = k.createThread(m.prx.name, m.prx.moduleStart, priority, stack, attributes, m.prx.gp);
        if (t == null) throw new Kernel.Stop("Cannot allocate module_start stack for " + m.prx.name);
        t.module = m.uid; k.startThread(t, argSize, argPointer);
        if (onStart != null) onStart.accept(m, t); // plugin support: sctrlHENSetStartModuleHandler runs before module_start
        return t;
    }
    /** Plugin support hooks (HleSystemCtrl): after a module is linked, and after its module_start thread is created. */
    public java.util.function.Consumer<LoadedModule> onLinked;
    public java.util.function.BiConsumer<LoadedModule, Kernel.GuestThread> onStart;
    public LoadedModule byAddress(int address) {
        for (LoadedModule m : modules) if (Integer.compareUnsigned(address - m.prx.base, m.prx.size) < 0) return m;
        return null;
    }

    private void register() {
        String lib = "ModuleMgrForUser";
        k.register(lib, 0x977de386, "sceKernelLoadModule", c -> loadModule(c.string(c.arg(0)), c.arg(2)));
        k.register(lib, 0x710f61b5, "sceKernelLoadModuleMs", c -> loadModule(c.string(c.arg(0)), c.arg(2)));
        // KUBridge: the user-mode bridge of custom firmware (SystemControl). Homebrew uses kuKernelLoadModule to load its
        // own kernel drivers or firmware kernel modules (flash0:/kd/), which PXP2 refuses (see loadModule); user-mode
        // modules load as with sceKernelLoadModule.
        k.register("KUBridge", 0x4c25ea72, "kuKernelLoadModule", c -> loadModule(c.string(c.arg(0)), c.arg(2), true));
        k.register("KUBridge", 0x24331850, "kuKernelGetModel", c -> k.model); // 0 = PSP-1000 (Kernel.model)
        k.register(lib, 0x50f0c1ec, "sceKernelStartModule", c -> {
            LoadedModule m = k.object(c.arg(0), LoadedModule.class); if (m == null) return 0x8002012e;
            if (m.started) return 0x80020133; // SCE_KERNEL_ERROR_ALREADY_STARTED
            Kernel.GuestThread t = start(m, c.arg(1), c.arg(2));
            if (t == null) { if (c.arg(3) != 0) c.memory.write32(c.arg(3), 0); return m.uid; }
            Kernel.Wait w = new Kernel.Wait("ModuleStart", t.uid); w.data = new int[]{m.uid, c.arg(3), 0};
            k.waitCurrent(w, 0, false); return m.uid;
        });
        k.register(lib, 0xd1ff982a, "sceKernelStopModule", c -> {
            LoadedModule m = k.object(c.arg(0), LoadedModule.class); if (m == null) return 0x8002012e;
            if (m.prx.moduleStop == 0) { if (c.arg(3) != 0) c.memory.write32(c.arg(3), 0); m.started = false; return m.uid; }
            Kernel.GuestThread t = k.createThread(m.prx.name + "_stop", m.prx.moduleStop, 0x20, 0x4000, 0x80000000, m.prx.gp);
            k.startThread(t, c.arg(1), c.arg(2)); m.started = false;
            Kernel.Wait w = new Kernel.Wait("ModuleStart", t.uid); w.data = new int[]{m.uid, c.arg(3), 0};
            k.waitCurrent(w, 0, false); return m.uid;
        });
        k.register(lib, 0x2e0911aa, "sceKernelUnloadModule", c -> {
            LoadedModule m = k.object(c.arg(0), LoadedModule.class); if (m == null) return 0x8002012e;
            // Memory is released; linked stubs of other modules pointing into it are not unpatched.
            modules.remove(m); k.sysmem.free(m.block); k.deleteUid(m.uid); k.log("Unloaded " + m.prx.name); return m.uid;
        });
        k.register(lib, 0xd675ebb8, "sceKernelSelfStopUnloadModule", c -> { k.exitThread(k.current, c.arg(1), true); return 0; });
        k.register(lib, 0xf0a26395, "sceKernelGetModuleId", c -> { LoadedModule m = byAddress(c.cpu.register(31)); return m == null ? 0x8002012e : m.uid; });
        k.register(lib, 0xd8b73127, "sceKernelGetModuleIdByAddress", c -> { LoadedModule m = byAddress(c.arg(0)); return m == null ? 0x8002012e : m.uid; });
        k.register(lib, 0xd2fbc957, "sceKernelGetModuleGPByAddress", c -> { LoadedModule m = byAddress(c.arg(0)); if (m == null) return 0x8002012e; c.memory.write32(c.arg(1), m.prx.gp); return 0; });
        k.register(lib, 0x644395e2, "sceKernelGetModuleIdList", c -> {
            int n = 0; for (LoadedModule m : modules) { if (n * 4 < c.arg(1)) c.memory.write32(c.arg(0) + n * 4, m.uid); n++; }
            if (c.arg(2) != 0) c.memory.write32(c.arg(2), n); return 0;
        });
        k.register(lib, 0x748cbed9, "sceKernelQueryModuleInfo", c -> {
            LoadedModule m = k.object(c.arg(0), LoadedModule.class); if (m == null) return 0x8002012e;
            int p = c.arg(1), size = c.memory.read32(p); if (size < 64) return Kernel.ILLEGAL_ARGUMENT;
            // SceKernelModuleInfo (PSPSDK pspmodulemgr.h, 96 bytes): +4 nsegment, +8 segmentaddr[4], +24 segmentsize[4],
            // +40 entry_addr, +44 gp_value, +48 text_addr, +52 text_size, +56 data_size, +60 bss_size, +64 attribute,
            // +66 version, +68 name[28]. (Fixed for plugins such as PSPWave, which locate a module's text through it.)
            c.memory.fill(p + 4, 0, Math.min(size, 96) - 4);
            c.memory.write8(p + 4, 1); c.memory.write32(p + 8, m.prx.base); c.memory.write32(p + 24, m.prx.size);
            c.memory.write32(p + 40, m.prx.entry); c.memory.write32(p + 44, m.prx.gp);
            c.memory.write32(p + 48, m.prx.base); c.memory.write32(p + 52, m.prx.textSize); c.memory.write32(p + 56, Math.max(0, m.prx.size - m.prx.textSize));
            if (size >= 68) { c.memory.write16(p + 64, m.prx.attributes); c.memory.write16(p + 66, m.prx.version); }
            if (size >= 96) c.memory.writeString(p + 68, m.prx.name, 28);
            return 0;
        });
    }
    /**
     * Firmware kernel modules whose libraries PXP2 serves from HLE instead of executing (they drive hardware PXP2 does
     * not emulate at register level). Loading them yields a resident placeholder module with no code.
     */
    public final Set<String> hleModules = new HashSet<>(Arrays.asList("ifhandle.prx", "pspnet.prx", "pspnet_inet.prx", "pspnet_apctl.prx",
        "pspnet_resolver.prx", "pspnet_adhoc.prx", "pspnet_adhocctl.prx", "pspnet_adhoc_matching.prx", "pspnet_adhoc_download.prx",
        "pspnet_adhoc_discover.prx", "pspnet_adhoc_transfer_int.prx", "pspnet_adhoc_auth.prx", "pspnet_upnp.prx", "pspnet_wispr.prx",
        "mlnbridge.prx", "mlnbridge_msapp.prx")); // mlnbridge drives the MagicGate DRM manager (sceMgr), not emulated
    /**
     * Sony libraries shipped on game discs that PXP2 serves from HLE instead. sc_sascore.prx (sceSasCore, the game sound
     * mixer) cannot use the firmware's copy: the 6.61 kd/sc_sascore.prx mixes on the Media Engine (it allocates ME
     * memory through sceMeMemory_driver at module_start), which PXP2 does not emulate. See HleGame. audiocodec.prx
     * (sceAudiocodec_Driver) and videocodec.prx (sceVideocodec_Driver) and mpegbase.prx (sceMpegbase), the Media Engine codec front ends, are served by
     * HleMedia and HleVideo, as for the VSH.
     */
    public final Set<String> hleGameModules = new HashSet<>(Arrays.asList("sc_sascore.prx", "audiocodec.prx", "videocodec.prx", "mpegbase.prx"));
    private int placeholder(String path) {
        LoadedModule m = new LoadedModule(); m.path = path; m.prx = new PrxLoader.Module();
        String file = path.substring(path.lastIndexOf('/') + 1); m.prx.name = "HLE:" + file; m.prx.moduleStart = 0;
        m.uid = k.newUid(m); modules.add(m); k.log("Serving " + path + " from HLE (not executed)"); return m.uid;
    }
    /**
     * Export libraries that also satisfy imports of another library name. The firmware's libfont_hv.prx (the VSH build of
     * sceFont_Library) exports "sceLibFont_HV" with exactly the NIDs games import from "sceLibFont".
     */
    private static final Map<String, String> EXPORT_ALIASES = Map.of("sceLibFont_HV", "sceLibFont");
    /** Firmware copies of disc libraries stored under another name (libfont.prx = sceFont_Library, the VSH's libfont_hv). */
    private static final Map<String, String> FIRMWARE_ALIASES = Map.of("libfont.prx", "vsh/module/libfont_hv.prx");
    /** SCE_KERNEL_ERROR_ILLEGAL_PERM_CALL: returned when user code asks to load a kernel-mode module. */
    public static final int KERNEL_MODULE_REFUSED = 0x80020149;
    /** Kernel-mode modules the game asked for and PXP2 refused (file names), in order. */
    public final List<String> refusedKernelModules = new ArrayList<>();
    /** Why the game cannot run fully (kernel-mode homebrew), for the host to show; null if nothing was refused. */
    public String unsupported() {
        return refusedKernelModules.isEmpty() ? null : "This homebrew needs kernel-mode modules (" + String.join(", ", refusedKernelModules) + "), which PXP2 does not support: it runs user-mode homebrew only";
    }
    /**
     * sceUtilityLoadAvModule / sceUtilityLoadModule: loads a firmware library from flash0 and runs its module_start while
     * the calling thread waits (returns 0, or module_start's error). A library that is already loaded is not loaded again.
     */
    public int loadUtilityModule(String path) {
        for (LoadedModule m : modules) if (m.path != null && m.path.equalsIgnoreCase(path)) return 0;
        int uid = loadModule(path, 0);
        LoadedModule m = k.object(uid, LoadedModule.class); if (m == null) return uid;
        Kernel.GuestThread t = start(m, 0, 0); if (t == null) return 0;
        Kernel.Wait w = new Kernel.Wait("ModuleStart", t.uid); w.data = new int[]{0, 0, 1};
        k.waitCurrent(w, 0, false); return 0;
    }
    public int loadModule(String path, int option) { return loadModule(path, option, false); }
    /** userOnly: refuse kernel-mode modules on any device (a CFW bridge asking for a firmware kernel module). */
    public int loadModule(String path, int option, boolean userOnly) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.contains("/kd/") && hleModules.contains(lower.substring(lower.lastIndexOf('/') + 1))) return placeholder(path);
        if (!lower.startsWith("flash0:") && hleGameModules.contains(lower.substring(lower.lastIndexOf('/') + 1))) return placeholder(path);
        int partition = 2;
        if (option != 0 && k.memory.read32(option) >= 8) { int text = k.memory.read32(option + 4); if (k.sysmem.validPartition(text)) partition = text; }
        try {
            byte[] data = k.io.readFile(path);
            int attributes = PrxLoader.moduleAttributes(data);
            if ((userOnly || !lower.startsWith("flash0:")) && attributes != -1 && (attributes & 0x1000) != 0) {
                // Kernel-mode homebrew modules (drivers loaded through a custom firmware's KUBridge or the like) call
                // kernel-only APIs and patch the firmware; PXP2 runs user-mode homebrew only, so the load is refused.
                String notice = "Kernel-mode module " + path + " (attribute 0x1000) is not supported: PXP2 runs user-mode homebrew only";
                k.log(notice); refusedKernelModules.add(path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf(':')) + 1));
                return KERNEL_MODULE_REFUSED;
            }
            if (option == 0 && lower.contains("/kd/")) partition = 1; // firmware kernel modules live in kernel memory
            LoadedModule m = load(k.io.vfs.absolute(path), data, partition); return m.uid;
        } catch (java.io.FileNotFoundException e) { k.log("LoadModule missing " + path); return 0x8002012f; }
        catch (EncryptedModuleException e) {
            // Game discs ship their own (older) copies of Sony system libraries, e.g. USRDIR/MODULE/sc_sascore.prx, sealed
            // with keys PXP2 does not have. The installed firmware carries the same library under the same file name in
            // flash0:/kd/, so that copy is loaded instead (same exports; newer revision).
            String file = lower.substring(lower.lastIndexOf('/') + 1), firmware = "flash0:/" + FIRMWARE_ALIASES.getOrDefault(file, "kd/" + file);
            if (!lower.startsWith("flash0:") && k.io.vfs.resolve(firmware) != null && k.io.vfs.resolve(firmware).file.isFile()) {
                k.log("Substituting firmware " + firmware + " for encrypted " + e.getMessage());
                try { return load(firmware, k.io.vfs.read(firmware), 1).uid; }
                catch (IOException f) { throw new Kernel.Stop("sceKernelLoadModule(" + firmware + ") failed: " + f.getMessage()); }
            }
            throw new Kernel.Stop("sceKernelLoadModule(" + path + ") failed: " + e.getMessage());
        }
        catch (IOException e) { throw new Kernel.Stop("sceKernelLoadModule(" + path + ") failed: " + e.getMessage()); }
    }
}
