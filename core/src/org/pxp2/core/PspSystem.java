package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Assembles the PXP2 HLE machine (CPU, memory, kernel and system libraries) and boots the real
 * Sony VSH user modules in the order given by the firmware's own pspbtcnf.bin VSH mode.
 */
public final class PspSystem {
    public final Kernel kernel;
    /** Default main RAM size in MiB: 64, the PSP-2000/3000/Go (see psp.md "System memory size"). */
    public static final int DEFAULT_RAM_MB = 64;
    /** Main RAM size of this machine in MiB (32, 64 or 128); kept across VSH restarts and game boots. */
    public final int ramMegabytes;
    public final HleIo io; public final HleModules modules; public final HleThreads threads; public final HleSystem system; public final Ge ge;
    public final HleVsh vsh; public final HleMedia media; public final HleVideo video; public final HleNet net; public final GeRenderer renderer; public final HleGame gameLib;
    /** SEPLUGINS support: the HLE SystemControl API and the plugin loader (see Plugins, psp.md "SEPLUGINS"). */
    public final HleSystemCtrl sctrl; public final Plugins plugins;
    private final StringBuilder report = new StringBuilder();

    private final File flash0, storage;
    private byte[] vshmainArgs;
    private final File memoryStick;
    public PspSystem(File flash0, File storage) { this(flash0, storage, new File(storage, "ms0")); }
    public PspSystem(File flash0, File storage, File memoryStick) { this(flash0, storage, memoryStick, DEFAULT_RAM_MB); }
    /** @param memoryStick host folder used as the Memory Stick (ms0:), e.g. /storage/emulated/0/PXP on Android */
    /** Folder for the library metadata cache (virtual EBOOT.PBPs); default {@code <storage>/library-cache}. Set before constructing. */
    public static volatile File libraryCache;
    /**
     * Hands the core the games to show in the XMB besides PSP/GAME: local ISOs and the hub catalog (see
     * {@link GameLibrary#localIsos} and {@link GameLibrary#parseCatalog}). Any thread; metadata is fetched in the background.
     */
    public static void setLibrary(List<LibraryEntry> entries) { GameLibrary.shared().setEntries(entries); }
    /** ICON0 (PNG, ideally 144×80) for the "Exit to PXP2" XMB entry; set by the host app before constructing. */
    public static volatile byte[] exitAppIcon;
    /**
     * @param ramMegabytes main RAM: 32 (PSP-1000), 64 (PSP-2000/3000/Go) or 128 (non-standard "double"). The reported
     *        model stays PSP-1000 at every size (the firmware is the 01g image); the extra RAM appears as partitions 8/10
     *        and, for homebrew that asks for it (PARAM.SFO MEMSIZE = 1), as the CFW-style high-memory user partition.
     */
    public PspSystem(File flash0, File storage, File memoryStick, int ramMegabytes) {
        if (ramMegabytes != 32 && ramMegabytes != 64 && ramMegabytes != 128) throw new IllegalArgumentException("RAM must be 32, 64 or 128 MiB: " + ramMegabytes);
        this.ramMegabytes = ramMegabytes; kernel = new Kernel(ramMegabytes << 20);
        this.flash0 = flash0; this.storage = storage; this.memoryStick = memoryStick;
        Vfs vfs = new Vfs();
        vfs.mount("flash0", flash0, false);
        File flash1 = new File(storage, "flash1"), ms0 = memoryStick;
        // flash1:/vsh/theme holds the installed custom theme (custom_theme.dat, written by Theme Settings); PSP/THEME holds .ptf files.
        for (File d : new File[]{flash1, new File(flash1, "vsh/theme"), new File(ms0, "PSP/THEME"), new File(ms0, "SEPLUGINS"), new File(ms0, "PSP/GAME"), new File(ms0, "PSP/SAVEDATA"), new File(ms0, "PSP/COMMON"), new File(ms0, "PSP/SYSTEM"), new File(ms0, "MUSIC"), new File(ms0, "PICTURE"), new File(ms0, "VIDEO")}) d.mkdirs();
        ExitApp.install(ms0, exitAppIcon); // the XMB always offers "Exit to PXP2" (launching it is intercepted in HleVsh)
        vfs.mount("flash1", flash1, true); vfs.mount("ms0", ms0, true);
        io = new HleIo(kernel, vfs); kernel.io = io;
        threads = new HleThreads(kernel); kernel.threadman = threads;
        modules = new HleModules(kernel); kernel.modules = modules;
        system = new HleSystem(kernel); ge = new Ge(kernel);
        ge.renderer = renderer = new GeRenderer(kernel.memory);
        system.registry.addFonts(new File(flash0, "font"));
        system.registryFile = new File(flash1, "registry/pxp2-registry.bin");
        if (system.registryFile.isFile()) { try { system.registry.load(system.registryFile); } catch (IOException e) { kernel.log("Registry reset: " + e.getMessage()); } }
        // CTF theme overlay: if custom_theme.dat carries DEAD0660 magic, shadow listed flash0 paths in memory.
        File ctfDat = new File(flash1, "vsh/theme/custom_theme.dat");
        if (ctfDat.isFile() && ctfDat.length() >= 0x20 && ctfDat.length() <= 32L * 1024 * 1024) {
            try {
                byte[] ctfBytes = java.nio.file.Files.readAllBytes(ctfDat.toPath());
                if (CtfLoader.isCTF(ctfBytes)) {
                    int n = CtfLoader.overlay(vfs, ctfBytes, kernel);
                    kernel.log("CTF theme loaded: " + n + " flash0 paths overridden");
                }
            } catch (IOException e) { kernel.log("CTF load failed: " + e.getMessage()); }
        }
        // Library games (local and hub ISOs, hub homebrew) appear as virtual ms0:/PSP/GAME entries; see GameLibrary.
        GameLibrary library = GameLibrary.shared(); library.attachCache(libraryCache != null ? libraryCache : new File(storage, "library-cache")); io.library = library;
        vsh = new HleVsh(kernel, system); media = new HleMedia(kernel); video = new HleVideo(kernel); net = new HleNet(kernel); gameLib = new HleGame(kernel, system);
        sctrl = new HleSystemCtrl(kernel); sctrl.seConfigFile = new File(flash1, "pxp2-seconfig.bin"); plugins = new Plugins(kernel, sctrl, ms0);
    }

    /** One entry of the firmware boot configuration. */
    public static final class BootModule { public final String path; public final int flags, loadMode; BootModule(String p, int f, int l) { path = p; flags = f; loadMode = l; } }
    /** Parses pspbtcnf.bin and returns the modules enabled for the given mode bit (VSH = 1). */
    public static List<BootModule> bootConfig(byte[] data, int modeBit) throws IOException {
        if (FirmwareCrypto.word(data, 0) != 0x0f803001) throw new IOException("Invalid pspbtcnf.bin signature");
        int moduleStart = FirmwareCrypto.word(data, 0x20), count = FirmwareCrypto.word(data, 0x24), names = FirmwareCrypto.word(data, 0x30);
        if (count <= 0 || count > 256) throw new IOException("Invalid boot module count");
        List<BootModule> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int p = moduleStart + i * 32, offset = FirmwareCrypto.word(data, p), flags = (data[p + 8] & 255) | ((data[p + 9] & 255) << 8), loadMode = (data[p + 10] & 255) | ((data[p + 11] & 255) << 8);
            int start = names + offset, end = start; while (end < data.length && data[end] != 0) end++;
            if ((flags & modeBit) != 0) out.add(new BootModule(new String(data, start, end - start, StandardCharsets.US_ASCII), flags, loadMode));
        }
        return out;
    }

    /** Loads and starts the VSH modules, then runs for at most {@code budget} instructions in total. */
    public String bootVsh(long budget) throws IOException {
        long start = kernel.instructions();
        String result = startVsh(budget);
        if (result.equals("Completed")) result = kernel.run(Math.max(1, budget - (kernel.instructions() - start)), null);
        report.append("Stop: ").append(result).append('\n');
        return result;
    }
    /** Continues the running VSH until the next host stop (e.g. a vblank hook setting stopRequested). */
    /** Inserts a UMD image (ISO 9660), or ejects with {@code null}; UMD callbacks are told the new drive state. */
    public void insertUmd(File image) throws IOException { insertUmd(image == null ? null : IsoSource.open(image)); }
    /** Inserts a disc from any source (e.g. {@link HttpIsoSource} streaming from the PC hub), or ejects with {@code null}. */
    public void insertUmd(IsoSource source) throws IOException {
        Iso9660 old = io.umd; io.umd = source == null ? null : new Iso9660(source);
        if (old != null) old.close();
        media.discChanged();
    }
    private volatile File pendingUmd; private volatile boolean umdRequest;
    /** Any thread: swap the disc while the system runs (applied at the start of the next {@link #run} slice). */
    public void requestUmdSwap(File image) { pendingUmd = image; umdRequest = true; }
    /**
     * Emulator thread: hot-swaps the disc like a real drive — eject now (callbacks see "no disc"), and after one second
     * of emulated time insert {@code image} (or leave the tray empty for null).
     */
    public void swapUmd(File image) {
        try { insertUmd((IsoSource)null); } catch (IOException ignored) { }
        if (image != null) kernel.schedule(kernel.now() + 1_000_000, () -> {
            try { insertUmd(image); } catch (IOException e) { kernel.log("UMD swap failed: " + e.getMessage()); }
        });
    }
    public String run(long budget) {
        if (umdRequest) { umdRequest = false; swapUmd(pendingUmd); }
        if (homeQuitRequest) { homeQuitRequest = false; homeQuit(); }
        return kernel.run(budget, null);
    }

    // ---- HOME menu ("Quit game?") ------------------------------------------------------------------
    /** PSP HOME button bit. In GAME mode the host shows its HOME menu for it and games never see the bit (see HleSystem.ctrlMask). */
    public static final int HOME_BUTTON = 0x10000;
    /** Emulated time a game gets to exit after its exit callback was notified before PXP2 forces the return to the XMB. */
    public static final long HOME_EXIT_TIMEOUT_MICROS = 5_000_000L;
    /** True while a game or homebrew runs (GAME mode); false in the XMB, where HOME goes to the VSH as a normal button. */
    public boolean inGame() { return gamePath != null; }
    /** The PSP's enter-button setting (registry /CONFIG/SYSTEM/XMB button_assign): true when ○ confirms (value 0, Japanese layout). */
    public boolean circleConfirms() {
        try { Registry.Value v = system.registry.get("/CONFIG/SYSTEM/XMB", "button_assign"); return v != null && v.data.length >= 1 && v.data[0] == 0; }
        catch (RuntimeException e) { return false; }
    }
    private volatile boolean homeQuitRequest;
    /** What the last {@link #homeQuit} did: "callback", "exit" or "not in a game" (null until then). */
    public volatile String homeQuitResult;
    /** Any thread: the user chose Yes in the HOME menu; applied on the emulation thread at the start of the next {@link #run} slice. */
    public void requestHomeQuit() { homeQuitRequest = true; }
    /**
     * Emulation thread: "Quit game?" -> Yes, as the PSP kernel does it. If the game registered an exit callback
     * (LoadExecForUser sceKernelRegisterExitCallback), that callback is notified through the normal callback mechanism
     * and runs on its owner thread (typically a thread sleeping in sceKernelSleepThreadCB); the game then cleans up and
     * calls sceKernelExitGame, which reboots to the VSH. If the game has not exited after
     * {@link #HOME_EXIT_TIMEOUT_MICROS} of emulated time, PXP2 forces the return. Without a callback the game is ended
     * at once through the same reboot path as sceKernelExitGame.
     */
    public String homeQuit() {
        if (!inGame()) return homeQuitResult = "not in a game";
        if (gameLib.exitRequested) return homeQuitResult = "exit";
        int cb = gameLib.exitCallback;
        if (cb != 0 && kernel.notifyCallback(cb, 0)) {
            kernel.log(String.format(Locale.ROOT, "HOME menu: quit game; exit callback %08x notified, waiting up to %d s for sceKernelExitGame", cb, HOME_EXIT_TIMEOUT_MICROS / 1_000_000));
            kernel.schedule(kernel.now() + HOME_EXIT_TIMEOUT_MICROS, () -> {
                if (gameLib.exitRequested) return;
                kernel.log("HOME menu: the game did not exit within the timeout; forcing the return to the XMB");
                gameLib.exitRequested = true; kernel.stopRequested = true;
            });
            return homeQuitResult = "callback";
        }
        kernel.log(cb == 0 ? "HOME menu: quit game; no exit callback registered, returning to the XMB" : String.format(Locale.ROOT, "HOME menu: quit game; exit callback %08x is not a callback, returning to the XMB", cb));
        gameLib.exitRequested = true; kernel.stopRequested = true;
        return homeQuitResult = "exit";
    }
    /** Updates battery status and notifies power callbacks. */
    public void updateBattery(int percent, boolean charging, boolean plugged, int tempC, int voltMv) {
        system.updateBattery(percent, charging, plugged, tempC, voltMv);
    }
    /**
     * Loads the VSH user modules listed in pspbtcnf.bin and runs each module_start. Returns "Completed" on success.
     * After a disc-boot reboot (vshKernelLoadExecVSHDisc) this boots the game instead (see {@link #startGame}).
     */
    public String startVsh(long budget) throws IOException {
        if (gamePath != null) return startGame(budget);
        List<BootModule> config = bootConfig(io.vfs.read("flash0:/kd/pspbtcnf.bin"), 1);
        List<String> user = new ArrayList<>();
        for (BootModule b : config) if ((b.loadMode & 0x8000) != 0 && (b.loadMode & 0x6) != 0) user.add(b.path);
        report.append("VSH boot modules from pspbtcnf.bin: ").append(config.size()).append(" total, ").append(user.size()).append(" user-mode\n");
        String result = "Not started"; long start = kernel.instructions();
        for (String path : user) {
            if (path.endsWith("usersystemlib.prx")) { report.append("  ").append(path).append(": served by HLE Kernel_Library\n"); continue; }
            HleModules.LoadedModule m = modules.load("flash0:" + path, io.vfs.read("flash0:" + path), 2);
            report.append(String.format(Locale.ROOT, "  %s: %s at %08x (%d imports, %d exports)\n", path, m.prx.name, m.prx.base, m.prx.imports.size(), m.prx.exports.size()));
        }
        for (HleModules.LoadedModule m : new ArrayList<>(modules.modules)) {
            int argSize = 0, argp = 0;
            if (vshmainArgs != null && vshmainArgs.length > 0 && m.prx.name.equals("vsh_module")) {
                argp = kernel.sysmem.allocate(2, "vshmain args", SysMem.LOW, vshmainArgs.length, 0); kernel.memory.write(argp, vshmainArgs); argSize = vshmainArgs.length;
            }
            // SEPLUGINS: VSH plugins load and start after the system libraries, just before vshmain (see Plugins).
            if (m.prx.name.equals("vsh_module")) { plugins.loadAndStart(HleSystemCtrl.Mode.VSH, null, null); for (Plugins.Result r : plugins.results) report.append("  plugin ").append(r).append('\n'); }
            Kernel.GuestThread t = modules.start(m, argSize, argp);
            if (t == null) continue;
            long remaining = budget - (kernel.instructions() - start);
            result = kernel.run(Math.max(1, remaining), () -> t.state == Kernel.State.DORMANT || t.state == Kernel.State.DEAD);
            report.append(String.format(Locale.ROOT, "module_start %s -> %s (exit %08x)\n", m.prx.name, result.split("\n")[0], t.exitStatus));
            if (!result.equals("Completed")) break;
            if (t.state == Kernel.State.DORMANT) kernel.deleteThread(t);
        }
        return result;
    }

    /**
     * Performs a reboot the guest requested: a fresh machine on the same flash0/storage, keeping the in-memory registry
     * and the inserted UMD. vshKernelExitVSHVSH restarts the VSH with the requested vshmain arguments;
     * vshKernelLoadExecVSHDisc boots the disc game ({@link #startVsh} then dispatches to {@link #startGame});
     * sceKernelExitGame returns to the VSH. Returns null when no reboot was requested.
     */
    public PspSystem restartIfRequested() {
        if (!restartPending()) return null;
        PspSystem next = new PspSystem(flash0, storage, memoryStick, ramMegabytes);
        next.system.registry = system.registry; next.io.umd = io.umd; next.restarts = restarts + 1;
        if (vsh.gameBootRequested) { next.gamePath = vsh.gamePath; next.gameArgs = vsh.rebootArgs; next.gameKey = vsh.gameKey; next.vsh.gameDiscId = vsh.gameDiscId; next.vsh.gameInfoArgs = vsh.gameInfoArgs; }
        else next.vshmainArgs = vsh.rebootArgs;
        return next;
    }
    public int restarts;
    /** True when the guest asked for a reboot: VSH restart, disc boot from the XMB, or the game quitting to the XMB. */
    public boolean restartPending() { return vsh.rebootRequested || gameLib.exitRequested; }

    /** Game-mode boot request carried over a LoadExec reboot: executable path, LoadExec args and key ("game"). */
    public String gamePath, gameKey; public byte[] gameArgs;
    /** The loaded game executable (null until {@link #startGame} succeeds). */
    public HleModules.LoadedModule game;
    /** Human-readable reason when the game could not be started (e.g. an executable encrypted with an unknown key). */
    public String gameError;

    /**
     * Boots a UMD game the way LoadExec's GAME mode does. pspbtcnf.bin GAME mode (bit 2) lists only kernel modules
     * (served by the HLE kernel) plus usersystemlib (HLE Kernel_Library), so the only guest code is the game itself.
     * The XMB asks for disc0:/PSP_GAME/SYSDIR/BOOT.BIN, but retail loadexec boots the encrypted EBOOT.BIN next to it
     * (BOOT.BIN is an unencrypted development copy that is normally zero-filled). The executable is loaded into user
     * partition 2 at 0x08804000 and its module_start receives the path as its argument, as on hardware.
     */
    public String startGame(long budget) throws IOException {
        report.append("GAME boot: ").append(gamePath).append(" key=").append(gameKey).append(" disc=").append(vsh.gameDiscId).append('\n');
        String dir = gamePath.substring(0, gamePath.lastIndexOf('/') + 1), eboot = dir + "EBOOT.BIN", path = eboot;
        byte[] data = null;
        if (gamePath.toUpperCase(Locale.ROOT).endsWith(".PBP")) { // Memory Stick homebrew: the executable is the PBP's DATA.PSP
            byte[] pbp;
            try { pbp = io.readFile(gamePath); } catch (FileNotFoundException e) { return fail("Game executable not found: " + gamePath); }
            if (pbp.length < 40 || FirmwareCrypto.word(pbp, 0) != 0x50425000) return fail("Not an EBOOT.PBP: " + gamePath);
            int start = FirmwareCrypto.word(pbp, 0x20), end = FirmwareCrypto.word(pbp, 0x24);
            if (end <= start || end > pbp.length) end = pbp.length;
            if (start < 40 || start >= end) return fail("EBOOT.PBP has no DATA.PSP: " + gamePath);
            int psar = FirmwareCrypto.word(pbp, 0x24);
            if (psar > 0 && psar + 8 <= pbp.length && new String(pbp, psar, 8, StandardCharsets.US_ASCII).equals("NPUMDIMG"))
                return fail("EBOOT.PBP is a PSN-format UMD image (NPUMDIMG in DATA.PSAR); its PSN encryption is not supported: " + gamePath);
            data = Arrays.copyOfRange(pbp, start, end); path = gamePath;
            // PSPSDK PSP_LARGE_MEMORY homebrew sets MEMSIZE = 1 in PARAM.SFO; CFW then boots it with the high-memory
            // layout (user partition 52 MiB on a 64 MiB PSP). PXP2 does the same when the machine has more than 32 MiB.
            int sfoAt = FirmwareCrypto.word(pbp, 0x08), sfoEnd = FirmwareCrypto.word(pbp, 0x0c);
            if (sfoAt >= 40 && sfoEnd > sfoAt && sfoEnd <= pbp.length) try {
                Object memsize = ParamSfo.parse(Arrays.copyOfRange(pbp, sfoAt, sfoEnd)).get("MEMSIZE");
                if (memsize instanceof Integer && (Integer)memsize == 1) {
                    boolean high = kernel.sysmem.useHighMemory();
                    kernel.log(high ? String.format(Locale.ROOT, "PARAM.SFO MEMSIZE=1: high-memory layout, user partition %d MiB", kernel.sysmem.partitionSize(2) >> 20)
                                    : "PARAM.SFO MEMSIZE=1: high-memory layout needs more than 32 MiB of RAM; keeping the 24 MiB user partition");
                    report.append("  MEMSIZE=1: ").append(high ? "high-memory layout" : "not available at 32 MiB").append('\n');
                }
            } catch (IOException e) { kernel.log("PARAM.SFO unreadable: " + e.getMessage()); }
            kernel.log(String.format(Locale.ROOT, "Homebrew %s: DATA.PSP %d bytes, magic %08x", gamePath, data.length, data.length >= 4 ? FirmwareCrypto.word(data, 0) : 0));
        }
        if (data == null) try {
            byte[] boot = io.readFile(gamePath); int nonZero = 0; for (byte b : boot) if (b != 0) nonZero++;
            kernel.log(String.format(Locale.ROOT, "Game BOOT.BIN: %d bytes, %d non-zero, magic %08x", boot.length, nonZero, boot.length >= 4 ? FirmwareCrypto.word(boot, 0) : 0));
            if (nonZero > 0 && boot.length >= 4 && FirmwareCrypto.word(boot, 0) == 0x464c457f) path = gamePath; // a plain ELF BOOT.BIN is usable as is
        } catch (FileNotFoundException e) { kernel.log("Game BOOT.BIN absent"); }
        if (data == null && !path.equals(eboot)) try { // record the retail executable's envelope too (tag = game key the firmware would use)
            byte[] e = io.readFile(eboot);
            if (e.length >= 0x150 && FirmwareCrypto.word(e, 0) == 0x5053507e) kernel.log(String.format(Locale.ROOT, "Game EBOOT.BIN: %d bytes, ~PSP tag %08x (not needed: BOOT.BIN is a plain ELF)", e.length, FirmwareCrypto.word(e, 0xd0)));
        } catch (FileNotFoundException ignored) { }
        if (data == null) try { data = io.readFile(path); } catch (FileNotFoundException e) { return fail("Game executable not found: " + path); }
        int magic = data.length >= 4 ? FirmwareCrypto.word(data, 0) : 0;
        if (magic == 0x5053507e && data.length >= 0x150) {
            int tag = FirmwareCrypto.word(data, 0xd0), attr = (data[4] & 255) | ((data[5] & 255) << 8), elfSize = FirmwareCrypto.word(data, 0x28), pspSize = FirmwareCrypto.word(data, 0x2c);
            String name = new String(data, 0x0a, 28, StandardCharsets.US_ASCII).split("\0", 2)[0];
            kernel.log(String.format(Locale.ROOT, "Game executable %s: ~PSP module '%s' attr %04x, tag %08x, decrypt mode %d, elf %d bytes, file %d bytes",
                path, name, attr, tag, data[0x7c] & 255, elfSize, pspSize));
            try { data = FirmwareInstaller.decryptPrx(data); }
            // Homebrew "signed" to run on official firmware wraps its DATA.PSP in an NPDRM user envelope (tag 0xADF305F0,
            // decrypt mode 13); PXP2 has no key for it, so such EBOOTs stop here with the reason.
            catch (IOException e) { return fail(String.format(Locale.ROOT, "Game executable is encrypted (tag 0x%08x%s); decryption key not available (%s)", tag, tag == 0xadf305f0 ? ", signed homebrew / NPDRM" : "", e.getMessage())); }
        }
        if (data.length < 52 || FirmwareCrypto.word(data, 0) != 0x464c457f) return fail(String.format(Locale.ROOT, "Game executable is not an ELF (magic %08x)", magic));
        StringBuilder ph = new StringBuilder();
        int phOff = FirmwareCrypto.word(data, 28), phCount = (data[44] & 255) | ((data[45] & 255) << 8);
        for (int i = 0; i < phCount && i < 8; i++) { int p = phOff + i * 32; ph.append(String.format(Locale.ROOT, " [type %x off %x va %x pa %x file %x mem %x]", FirmwareCrypto.word(data, p), FirmwareCrypto.word(data, p + 4), FirmwareCrypto.word(data, p + 8), FirmwareCrypto.word(data, p + 12), FirmwareCrypto.word(data, p + 16), FirmwareCrypto.word(data, p + 20))); }
        kernel.log(String.format(Locale.ROOT, "Game ELF %s: %d bytes, e_type %04x, entry %08x, %d program headers%s", path, data.length, (data[16] & 255) | ((data[17] & 255) << 8), FirmwareCrypto.word(data, 24), phCount, ph));
        // Kernel-mode homebrew (module attribute 0x1000: 1.50-era kernel EBOOTs, CFW plugins) runs in kernel mode and
        // calls kernel-only APIs; PXP2's HLE kernel runs user-mode executables only.
        int attributes = PrxLoader.moduleAttributes(data);
        if (attributes != -1 && (attributes & 0x1000) != 0)
            return fail(String.format(Locale.ROOT, "Kernel-mode homebrew is not supported (module attribute %04x): PXP2 runs user-mode homebrew only", attributes));
        // Retail titles are linked for 0x08804000: the first 16 KiB of user memory stay reserved as on hardware. Static
        // homebrew ELFs (ET_EXEC, usually linked at 0x08900000) reserve their own link range when loaded.
        kernel.sysmem.allocate(2, "game reserved", SysMem.ADDR, 0x4000, 0x08800000);
        system.ctrlMask = ~HOME_BUTTON; // HOME belongs to the system's HOME menu: games never read it
        // SEPLUGINS: game plugins load and start before the game executable is loaded (see Plugins).
        plugins.loadAndStart(HleSystemCtrl.Mode.GAME, path, vsh.gameDiscId);
        for (Plugins.Result r : plugins.results) report.append("  plugin ").append(r).append('\n');
        try { game = modules.load(path, data, 2); }
        catch (IOException e) { return fail("Game executable could not be loaded: " + e.getMessage()); }
        report.append(String.format(Locale.ROOT, "  game module %s at %08x (%d imports, %d exports)\n", game.prx.name, game.prx.base, game.prx.imports.size(), game.prx.exports.size()));
        byte[] arg = (path + "\0").getBytes(StandardCharsets.US_ASCII);
        int argp = kernel.sysmem.allocate(2, "game args", SysMem.HIGH, arg.length, 0); kernel.memory.write(argp, arg);
        Kernel.GuestThread t = modules.start(game, arg.length, argp);
        if (t == null) return fail("Game executable has no entry point");
        return "Completed";
    }
    private String fail(String message) { gameError = message; kernel.log(message); report.append(message).append('\n'); return message; }

    public String report() {
        StringBuilder s = new StringBuilder("PXP2 HLE kernel: real Sony VSH modules on the original interpreter\n");
        s.append(report);
        s.append(String.format(Locale.ROOT, "Guest instructions: %,d; virtual time %.3f s; vblanks %d; HLE functions %d\n", kernel.instructions(), kernel.now() / 1e6, system.vcount, kernel.implementedFunctions()));
        s.append(String.format(Locale.ROOT, "GE: %d lists finished, %d commands, %d primitives; rendered %d triangles, %d sprites, %d pixels; %d patches tessellated\n",
            ge.finishedLists, ge.commands, ge.primitives, renderer.triangles, renderer.sprites, renderer.pixels, renderer.patches));
        s.append("Unresolved variable imports: ").append(modules.unresolvedVariables()).append(' ').append(modules.unresolvedVariableNames()).append('\n');
        s.append("Memory:\n").append(kernel.sysmem.report());
        s.append(kernel.threadReport());
        s.append("HLE calls by count:\n").append(kernel.callSummary());
        if (io.stdout.length() > 0) s.append("Guest stdout:\n").append(io.stdout).append('\n');
        s.append("Kernel log:\n"); for (String l : kernel.log) s.append("  ").append(l).append('\n');
        int from = Math.max(0, kernel.callTrace.size() - 40);
        s.append("Last HLE calls:\n"); for (String l : kernel.callTrace.subList(from, kernel.callTrace.size())) s.append("  ").append(l).append('\n');
        s.append("Last instructions:\n").append(kernel.recentPcs());
        return s.toString();
    }

    /** Headless diagnostic entry point used by tests and the Android frontend. */
    public static String diagnose(File installation, File storage, long budget) throws IOException { return diagnose(installation, storage, budget, ""); }
    /** As {@link #diagnose(File, File, long)}, with comma-separated hex PC watchpoints. */
    public static String diagnose(File installation, File storage, long budget, String watch) throws IOException {
        FirmwareInstaller.verify(installation.toPath());
        PspSystem psp = new PspSystem(new File(installation, "flash0"), storage);
        for (String w : watch.split(",")) {
            if (w.contains("-")) { String[] r = w.split("-"); psp.kernel.traceLow = (int)Long.parseLong(r[0], 16); psp.kernel.traceHigh = (int)Long.parseLong(r[1], 16); }
            else if (w.startsWith("@")) psp.kernel.watchWords.add((int)Long.parseLong(w.substring(1), 16));
            else if (!w.isEmpty()) psp.kernel.watch.add((int)Long.parseLong(w, 16));
        }
        psp.bootVsh(budget);
        String full = psp.report();
        Files.write(new File(storage, "vsh-boot-report.txt").toPath(), full.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(storage, "vsh-calls.txt").toPath(), String.join(System.lineSeparator(), psp.kernel.callTrace).getBytes(StandardCharsets.UTF_8));
        return full;
    }
}
