package org.pxp2.core;

import java.io.IOException;

/** Original HLE for VSH-only services (sceVshBridge, sceImpose, sceUtility internals) reached by the XMB. */
public final class HleVsh {
    private final Kernel k; private final HleSystem system;
    public final int[] callbacks = new int[4];
    public int powerFlags, displayFlag, utilsValue; public final int[] rtcConf = new int[4];
    public volatile boolean rebootRequested; public byte[] rebootParam, rebootArgs;
    /** Game identity handed to the kernel before a UMD/game launch (sceKernelSetParamSfo): disc ID and the raw arguments. */
    public String gameDiscId; public int[] gameInfoArgs;
    /** Set by vshKernelLoadExecVSHDisc: the executable path the XMB asked to boot and the LoadExec key ("game"). */
    /** Set when the user launches the XMB's "Exit to PXP2" entry: the host should leave the emulator. */
    public volatile boolean exitToHostRequested;
    public volatile boolean gameBootRequested; public String gamePath, gameKey;
    /** Impose parameters with first-boot defaults: volume 15, brightness 3, language 1 (English), date format 2. */
    private int imposeButtons, imposeChanges; static final int IMPOSE_VOLUME_CHANGED = 0x1;
    public static final int IMPOSE_VOLUME = 0x1, IMPOSE_MUTE = 0x8;
    private static boolean imposeId(int id) {
        if (id >= 0x80000001 && id <= 0x80000012) return true;
        switch (id) { case 0x1: case 0x2: case 0x4: case 0x8: case 0x10: case 0x20: case 0x40: case 0x80: case 0x100: case 0x200: case 0x400: case 0x2000: case 0x20000000: return true; default: return false; }
    }
    public final java.util.Map<Integer, Integer> impose = new java.util.HashMap<>(java.util.Map.of(0x80000001, 15, 0x80000002, 3, 0x80000007, 2, 0x80000008, 1, IMPOSE_VOLUME, 15, 0x80, 1));
    public static final int IMPOSE_BRIGHTNESS = 0x2;
    /**
     * Volume, mute and brightness persist in the registry keys the real impose driver reads and writes (strings of
     * kd/impose_01g.prx): /CONFIG/SYSTEM/SOUND main_volume (0..30) and mute, /CONFIG/SYSTEM backlight_brightness.
     * The values are seeded from the registry on first use (it is loaded, or handed over on a VSH restart, after this
     * object is built) and written back, and saved to flash1, whenever they change.
     */
    private Registry imposeSource;
    private void syncImpose() {
        Registry r = system.registry; if (r == imposeSource) return; imposeSource = r;
        int volume = Math.max(0, Math.min(30, registryInt(r, "/CONFIG/SYSTEM/SOUND", "main_volume", 15)));
        int brightness = registryInt(r, "/CONFIG/SYSTEM", "backlight_brightness", 3);
        impose.put(IMPOSE_VOLUME, volume); impose.put(0x80000001, volume);
        impose.put(IMPOSE_MUTE, registryInt(r, "/CONFIG/SYSTEM/SOUND", "mute", 0));
        impose.put(IMPOSE_BRIGHTNESS, brightness); impose.put(0x80000002, brightness);
    }
    private void storeImpose() {
        Registry r = system.registry;
        boolean changed = storeInt(r, "/CONFIG/SYSTEM/SOUND", "main_volume", impose.getOrDefault(IMPOSE_VOLUME, 15))
            | storeInt(r, "/CONFIG/SYSTEM/SOUND", "mute", impose.getOrDefault(IMPOSE_MUTE, 0))
            | storeInt(r, "/CONFIG/SYSTEM", "backlight_brightness", impose.getOrDefault(IMPOSE_BRIGHTNESS, 3));
        if (changed && system.registryFile != null) try { r.save(system.registryFile); } catch (IOException e) { k.log("Registry save failed: " + e.getMessage()); }
    }
    private static int registryInt(Registry r, String category, String key, int fallback) {
        Registry.Value v = r.get(category, key);
        return v == null || v.type != Registry.INT || v.data.length < 4 ? fallback : (v.data[0] & 255) | (v.data[1] & 255) << 8 | (v.data[2] & 255) << 16 | v.data[3] << 24;
    }
    private static boolean storeInt(Registry r, String category, String key, int value) {
        if (registryInt(r, category, key, ~value) == value) return false;
        r.set(category, key, Registry.INT, new byte[]{(byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)}); return true;
    }
    public HleVsh(Kernel k, HleSystem system) { this.k = k; this.system = system; register(); }
    /**
     * sceVshBridge NIDs are identified by tracing each export of the firmware's kd/vshbridge.prx to the
     * kernel function it wraps (see core/tests/ExportMap.java). The wrapped kernel call is noted per entry.
     */
    /** Copies a /CONFIG/NETWORK/GO_MESSENGER string (64 bytes) the way utility.prx does. */
    private int goMessenger(int buffer, String key) {
        if (buffer == 0) return 0x80110602;
        Registry.Value v = system.registry.get("/CONFIG/NETWORK/GO_MESSENGER", key);
        k.memory.fill(buffer, 0, 64); if (v != null) k.memory.write(buffer, v.data, 0, Math.min(63, v.data.length)); return 0;
    }
    private void register() {
        String lib = "sceVshBridge";
        // -> SysMemForKernel:07c586a1, no arguments; stored by vshmain at start-up: hardware model (0 = PSP-1000).
        k.register(lib, 0x21c243fe, "vshKernelGetModel", c -> k.model);
        k.register(lib, 0x5c2983c2, "vshChkregCheckRegion", c -> 1); // -> sceChkreg_driver 54495b19 (umd region check; every region is allowed)
        k.register(lib, 0xaab9a9ef, "vshKernelSetParamSfo", c -> { // -> SysMemForKernel 807179e7: PARAM.SFO values for the game about to boot
            gameDiscId = c.arg(0) == 0 ? null : c.string(c.arg(0));
            gameInfoArgs = new int[]{c.arg(0), c.arg(1), c.arg(2), c.arg(3), c.arg(4), c.arg(5)};
            k.log("Game info: disc " + gameDiscId + String.format(java.util.Locale.ROOT, " args=%08x,%08x,%08x,%08x,%08x,%08x", c.arg(0), c.arg(1), c.arg(2), c.arg(3), c.arg(4), c.arg(5))
                + (c.arg(2) != 0 ? " a2=" + c.string(c.arg(2)) : "") + (c.arg(3) != 0 ? " a3=" + c.string(c.arg(3)) : ""));
            return 0;
        });
        k.register(lib, 0x3a46c639, "vshKDebugIsToolMode", c -> 0); // -> KDebugForKernel 27b23800 (development/tool-hardware query; retail = 0)
        // -> bridge-internal table of four VSH event callback slots (0x80000102 bad slot, 0x80000020 busy, 0x80000025 empty).
        k.register(lib, 0xdb7c3d5a, "vshBridgeRegisterCallback", c -> {
            int slot = c.arg(0); if (slot < 0 || slot >= 4) return 0x80000102; if (callbacks[slot] != 0) return 0x80000020;
            callbacks[slot] = c.arg(1); return 0;
        });
        // -> scePower_driver:ba566cd0(flags 0x28 / 0x828 at start-up); result unused by vshmain. Recorded only.
        k.register(lib, 0xc949966c, "vshPowerSetFlags", c -> { powerFlags = c.arg(0); return 0; });
        // -> sceChkreg_driver:59f8491d (sceChkregGetPsCode); vshmain reads the product code at +2 (defaulting to 4, North America).
        k.register(lib, 0x61001d64, "vshChkregGetPsCode", c -> {
            int p = c.arg(0); k.memory.write16(p, 1); k.memory.write16(p + 2, 4); k.memory.write16(p + 4, 1); k.memory.write16(p + 6, 0); return 0;
        });
        // -> sceGe_driver:547ec5f0, returns ge.prx's probed EDRAM hardware size (2 MiB on PSP-1000; paf compares with 4 MiB).
        k.register(lib, 0x3512f4bc, "vshGeEdramGetHwSize", c -> Ge.EDRAM_SIZE);
        // Bridge exports whose wrapped 6.61 kernel function is literally "return 0" (hpremote/impose stubs on PSP-1000).
        for (int nid : new int[]{0x0296ca2b, 0x228b9bc0, 0x63047647, 0x7e117907, 0xf8601869})
            k.register(lib, nid, String.format(java.util.Locale.ROOT, "vshBridge_%08x (firmware no-op)", nid), c -> 0);
        // -> sceDisplay_driver:996881d2, returns a display_01g.prx global that is only set (0x0200000a) on a hardware
        //    fault path during display init; vshmain shows a special screen when positive. Normal boot: 0.
        k.register(lib, 0x0543156c, "vshDisplayGetInitFault", c -> 0);
        // -> sceRtc_driver:e09880cf, the same rtc.prx routine as sceRtcSetAlarmTick.
        k.register(lib, 0x29cdffba, "vshRtcSetAlarmTick", c -> system.setAlarmTick(c.arg(0)));
        // -> sceCtrl_driver:be30ced0, the same ctrl.prx entry as sceCtrlReadBufferPositive (blocking read).
        k.register(lib, 0xc6395c03, "vshCtrlReadBufferPositive", c -> system.readBuffer(c.arg(0), c.arg(1), false, true));
        // -> ModuleMgrForKernel:d5ddab1f (path, flags, SceKernelLMOption*): paf's plugin loader (e.g. opening_plugin.prx).
        k.register(lib, 0xa5628f0d, "vshKernelLoadModuleVSH", c -> k.modules.loadModule(k.string(c.arg(0)), c.arg(2)));
        // -> sceMSAudio_driver:543dbdd7: msaudio.prx (MagicGate music) driver initialisation; only resets driver state.
        k.register(lib, 0xce32cbef, "vshMSAudioInit", c -> 0);
        // -> sceMSAudio_driver:19474552: registers a handler block {.., func, size} with the MagicGate manager.
        //    No MagicGate media exists here, so the handler is recorded and never called.
        k.register(lib, 0x6caeb765, "vshMSAudioRegisterHandler", c -> 0);
        // -> sceMSAudio_driver:59b4ee6d: returns 0x80508004 while the MagicGate session state is < 2 (no media authenticated).
        k.register(lib, 0x53bfd101, "vshMSAudioGetInfo", c -> 0x80508004);
        // -> sceMeCore_driver:5dff5c50(mode): Media Engine boot/mode request (vshmain passes 3, ignores result). No ME emulation yet.
        k.register(lib, 0x9427c909, "vshMeBootStart", c -> { k.log("Media Engine mode request " + c.arg(0) + " (ME not emulated)"); return 0; });
        // -> sceHprm_driver:e9b776be: registers a headphone-remote handler block; no remote is attached so it never fires.
        k.register(lib, 0xd3a07961, "vshHprmRegisterHandler", c -> 0);
        // Bridge 088005cc: validates the user buffers, then forwards (dev, cmd, in, inLen, out, outLen) to the kernel devctl.
        k.register(lib, 0x2380dc08, "vshIoDevctl", c -> k.io.devctl(k.string(c.arg(0)), c.arg(1), c.arg(2), c.arg(3), c.arg(4), c.arg(5)));
        // -> sceIdStorage_driver:6fe062d1 (key, offset, buffer, length): per-unit factory data lookup. No IdStorage image
        //    exists for PXP2, so every key reports absent; vshmain has fallbacks for missing keys (e.g. 0x46/0x47).
        k.register(lib, 0x4db43867, "vshIdStorageLookup", c -> { k.log(String.format(java.util.Locale.ROOT, "IdStorage key %04x absent", c.arg(0))); return 0x80000025; });
        // Impose (volume/brightness/EQ/format) parameters: impose_01g.prx GetParam(id) returns the value in v0 and
        // 0x80000107 for unknown ids; SetParam(id, value). Ids are 0x80000001..0x80000012.
        //    impose_01g's dc3becff also takes the single-bit ids used by the volume overlay: 0x1 volume (reads 0 while
        //    muted), 0x2 brightness, 0x4 equaliser, 0x8 mute, 0x10 AVLS, 0x20/0x40 time/date format, 0x80 language,
        //    0x100, 0x200, 0x400, 0x2000, 0x20000000.
        k.register(lib, 0x4a596d2d, "vshImposeSetParam", c -> {
            int id = c.arg(0); if (!imposeId(id)) return 0x80000107;
            syncImpose(); impose.put(id, c.arg(1));
            if (id == IMPOSE_VOLUME || id == 0x80000001) { impose.put(IMPOSE_VOLUME, c.arg(1)); impose.put(0x80000001, c.arg(1)); }
            if (id == IMPOSE_BRIGHTNESS || id == 0x80000002) { impose.put(IMPOSE_BRIGHTNESS, c.arg(1)); impose.put(0x80000002, c.arg(1)); }
            if (id == IMPOSE_VOLUME || id == 0x80000001 || id == IMPOSE_MUTE || id == IMPOSE_BRIGHTNESS || id == 0x80000002) storeImpose();
            return 0;
        });
        k.register(lib, 0x639c3cb3, "vshImposeGetParam", c -> {
            int id = c.arg(0); if (!imposeId(id)) return 0x80000107;
            syncImpose();
            if (id == IMPOSE_VOLUME && impose.getOrDefault(IMPOSE_MUTE, 0) != 0) return 0;
            return impose.getOrDefault(id, 0);
        });
        // -> sceImpose_driver:0f067e16: returns and clears impose's pending-change word (arguments unused).
        //    PXP2's impose driver is HLE, so the volume buttons are handled here: each press of VOL+/VOL- (ctrl bits
        //    0x100000/0x200000) steps volume 0..30 and a change is reported, which makes the XMB show its volume bar.
        k.register(lib, 0x5894c339, "vshImposeTakeChanges", c -> {
            int b = system.buttons, pressed = b & ~imposeButtons; imposeButtons = b;
            syncImpose();
            if ((pressed & 0x300000) != 0) {
                int v = Math.max(0, Math.min(30, impose.getOrDefault(IMPOSE_VOLUME, 15) + ((pressed & 0x100000) != 0 ? 1 : -1)));
                impose.put(IMPOSE_VOLUME, v); impose.put(0x80000001, v); impose.put(IMPOSE_MUTE, 0); // any volume press unmutes
                imposeChanges |= IMPOSE_VOLUME_CHANGED;
                storeImpose();
            }
            int changes = imposeChanges; imposeChanges = 0; return changes;
        });
        // -> sceMePower_driver:1862b784: sends Media Engine command 0x186 (unit power control). No ME emulation yet.
        k.register(lib, 0x74dbe57e, "vshMePowerControl", c -> { k.log(String.format(java.util.Locale.ROOT, "ME power control %d,%d (ME not emulated)", c.arg(0), c.arg(1))); return 0; });
        // Other sceMePower_driver wrappers (Media Engine unit power commands); logged, no ME emulation yet.
        for (int nid : new int[]{0x88c35487, 0xd7d7e7b6})
            k.register(lib, nid, String.format(java.util.Locale.ROOT, "vshMePower_%08x", nid), c -> { k.log(String.format(java.util.Locale.ROOT, "ME power command %d,%d (ME not emulated)", c.arg(0), c.arg(1))); return 0; });
        // -> sceChkreg_driver:6894a027 (buffer, index): chkreg data backed by IdStorage; chkreg.prx returns 0x80000041
        //    when that data cannot be read, which is PXP2's situation (no IdStorage image).
        k.register(lib, 0x0d7a4fe4, "vshChkregGetData", c -> 0x80000041);
        // sceResmgr:9dc14891(buffer, size, sizeOut): decrypts vsh/etc/index_01g.dat in place. The firmware ships the same
        // record in plain text as flash0:/vsh/etc/version.txt, which is returned verbatim.
        k.register("sceResmgr", 0x9dc14891, "sceResmgrDecryptIndex", c -> {
            byte[] text;
            try { text = k.io.vfs.read("flash0:/vsh/etc/version.txt"); } catch (java.io.IOException e) { return 0x80000025; }
            int n = Math.min(text.length, c.arg(1) - 1); k.memory.write(c.arg(0), text, 0, n); k.memory.write8(c.arg(0) + n, 0);
            if (c.arg(2) != 0) k.memory.write32(c.arg(2), n); return 0;
        });
        // Display bridges. 3552ab11 only stores a display flag word; dea197d4/0e20f177 are the standard Get/SetMode.
        k.register(lib, 0x7d1c13b5, "vshDisplaySetFlag", c -> { displayFlag = c.arg(0); return 0; });
        k.register(lib, 0x5e5af7a2, "vshDisplayGetMode", c -> { k.memory.write32(c.arg(0), system.displayMode); k.memory.write32(c.arg(1), system.displayWidth); k.memory.write32(c.arg(2), system.displayHeight); return 0; });
        k.register(lib, 0xbd5ebfba, "vshDisplaySetMode", c -> { system.displayMode = c.arg(0); system.displayWidth = c.arg(1); system.displayHeight = c.arg(2); return 0; });
        // -> LoadExecForKernel:08f7166c(SceKernelLoadExecVSHParam*): restart the VSH. The parameter block is captured and
        //    the host performs the reboot (see PspSystem), passing vshmain_args to the new vshmain.
        k.register(lib, 0xd47041ca, "vshKernelExitVSHVSH", c -> {
            int p = c.arg(0); int size = p == 0 ? 0 : k.memory.read32(p);
            rebootParam = p == 0 ? new byte[0] : k.memory.read(p, Math.min(size, 0x30));
            if (p != 0 && size >= 0x18) { int argSize = k.memory.read32(p + 16), argp = k.memory.read32(p + 20); rebootArgs = argSize > 0 && argp != 0 ? k.memory.read(argp, Math.min(argSize, 0x1000)) : new byte[0]; }
            String key = p != 0 && size >= 16 ? k.string(k.memory.read32(p + 12)) : null;
            k.log("VSH requested restart; key=" + key + " vshmain_args=" + (rebootArgs == null ? 0 : rebootArgs.length) + " bytes");
            rebootRequested = true; k.stopRequested = true; return 0;
        });
        // -> LoadExecForKernel:d8320a28 = sceKernelLoadExecVSHDisc(path, SceKernelLoadExecVSHParam*): the XMB launches a UMD
        //    game. vshmain passes "disc0:/PSP_GAME/SYSDIR/BOOT.BIN" with {size 0x30, args, argp, key "game", ...}. As for
        //    the VSH restart, the parameters are captured and the host reboots the machine into game mode (PspSystem).
        k.register(lib, 0x63e69956, "vshKernelLoadExecVSHDisc", c -> loadExec(c, "disc"));
        // -> LoadExecForKernel:d940c83c = sceKernelLoadExecVSHMs2(path, param): the XMB launches Memory Stick homebrew
        //    (ms0:/PSP/GAME/<dir>/EBOOT.PBP), after checking PARAM.SFO (DISC_ID, REGION, BOOTABLE, CATEGORY "MG") and that
        //    DATA.PSP starts with "~PSP"/"~SCE". PXP2's own "Exit to PXP2" entry is intercepted here and returns to the app.
        //    Library entries (virtual PXPISO_*/PXPHB_* folders, see GameLibrary) are intercepted too: an ISO is inserted as
        //    the UMD and booted like a disc launch; server homebrew is downloaded to PSP/GAME first.
        k.register(lib, 0x21d4d038, "vshKernelLoadExecVSHMs2", c -> {
            String path = c.string(c.arg(0));
            if (ExitApp.isExitPath(path)) { k.log("Exit to PXP2 selected"); exitToHostRequested = true; k.stopRequested = true; return 0; }
            GameLibrary.Item item = k.io.virtualItem(path);
            if (item != null) return launchLibrary(c, item);
            return loadExec(c, "ms");
        });
        registerMore(lib);
    }
    /** Message for the host to show (e.g. a library download that failed); the host clears it. */
    public volatile String hostMessage;
    /** Error returned to vshmain when a library game cannot be started (it shows its own error dialog). */
    static final int LIBRARY_LAUNCH_FAILED = 0x80010005;
    /**
     * Launches a library entry picked in the XMB. ISO (local or streamed from the hub): the image becomes the inserted UMD
     * and the machine reboots into it exactly as for vshKernelLoadExecVSHDisc; after the game exits, the XMB comes back
     * with that disc in the drive. Server homebrew: downloaded (and unpacked) into ms0:/PSP/GAME/<name>, then launched
     * from there like any Memory Stick homebrew. This blocks the emulator for the length of the download.
     */
    private int launchLibrary(Kernel c, GameLibrary.Item item) {
        GameLibrary lib = k.io.library;
        if (item.isIso()) {
            Iso9660 iso;
            try { iso = new Iso9660(item.entry.openIso()); }
            catch (IOException | RuntimeException e) { return libraryFailed(item, "the disc image could not be opened: " + e.getMessage()); }
            Iso9660 old = k.io.umd; k.io.umd = iso;
            if (old != null) try { old.close(); } catch (IOException ignored) { }
            k.log("Library disc inserted: " + iso.source().describe() + " (" + item.discId + ")");
            int r = loadExec(c, "disc (library " + item.folder + ")", "disc0:/PSP_GAME/SYSDIR/BOOT.BIN");
            gameKey = "game"; if (item.discId != null) gameDiscId = item.discId;
            return r;
        }
        java.io.File gameRoot = k.io.vfs.resolve("ms0:/PSP/GAME").file, dir;
        try { k.log("Library homebrew: downloading " + item.entry.fileName); dir = lib.installHomebrew(item, gameRoot); }
        catch (IOException | RuntimeException e) { return libraryFailed(item, "the homebrew could not be downloaded: " + e.getMessage()); }
        k.log("Library homebrew installed to " + dir);
        return loadExec(c, "ms (downloaded)", "ms0:/PSP/GAME/" + dir.getName() + "/EBOOT.PBP");
    }
    private int libraryFailed(GameLibrary.Item item, String why) {
        String title = item.title != null ? item.title : item.entry.name;
        hostMessage = title + ": " + why; k.log("Library launch failed: " + hostMessage);
        return LIBRARY_LAUNCH_FAILED;
    }
    private int loadExec(Kernel c, String kind) { return loadExec(c, kind, c.string(c.arg(0))); }
    /** Captures a LoadExec request (path + SceKernelLoadExecVSHParam) and asks the host to reboot into it. */
    private int loadExec(Kernel c, String kind, String path) {
        {
            int p = c.arg(1); int size = p == 0 ? 0 : k.memory.read32(p);
            gamePath = path;
            rebootParam = p == 0 ? new byte[0] : k.memory.read(p, Math.min(size, 0x30));
            rebootArgs = null;
            if (p != 0 && size >= 12) { int argSize = k.memory.read32(p + 4), argp = k.memory.read32(p + 8); rebootArgs = argSize > 0 && argp != 0 ? k.memory.read(argp, Math.min(argSize, 0x1000)) : new byte[0]; }
            gameKey = p != 0 && size >= 16 && k.memory.read32(p + 12) != 0 ? k.string(k.memory.read32(p + 12)) : null;
            k.log("VSH requested " + kind + " boot " + gamePath + "; key=" + gameKey + " args=" + (rebootArgs == null ? 0 : rebootArgs.length) + " bytes");
            gameBootRequested = true; rebootRequested = true; k.stopRequested = true; return 0;
        }
    }
    private void registerMore(String lib) {
        // -> scePower_driver:5f5006d2: returns a power-driver hardware flag set at init from the syscon (baryon) version;
        //    the PSP-1000 path leaves it 0.
        k.register(lib, 0x06bf847b, "vshPowerGetHardwareFlag", c -> 0);
        // -> sceRtc_driver:dff30673(a0..a3): stores four RTC configuration words (Date & Time settings). Recorded.
        k.register(lib, 0xd49c57e3, "vshRtcSetConf", c -> { for (int i = 0; i < 4; i++) rtcConf[i] = c.arg(i); return 0; });
        // sceUtility:28d35634(buffer): utility.prx copies registry /CONFIG/NETWORK/GO_MESSENGER/auth_name (64 bytes) into buffer.
        k.register("sceUtility", 0x28d35634, "sceUtilityGetGoMessengerAuthName", c -> goMessenger(c.arg(0), "auth_name"));
        k.register("sceUtility", 0xef3582b2, "sceUtilityGetGoMessengerAuthKey", c -> goMessenger(c.arg(0), "auth_key"));
        // UtilsForKernel get/set pair (a6b0a6b8 / 39ffb756): msvideo saves the value, sets 0 during playback, restores it.
        k.register(lib, 0x96c67d2c, "vshUtilsGetValue", c -> utilsValue);
        k.register(lib, 0x74c48d87, "vshUtilsSetValue", c -> { utilsValue = c.arg(0); return 0; });
        // -> sceMgVideo_driver:44b6c121: tears down MagicGate video sessions 0x20/0x10; nothing open without DRM, so 0.
        k.register(lib, 0xc40f345f, "vshMgVideoReset", c -> 0);
        // -> sceMgVideo_driver:7894cce9(session, cmd, in, inLen, out, outLen): Memory Stick devctl through the MG video driver.
        k.register(lib, 0x474e9245, "vshMgVideoDevctl", c -> k.io.devctl("ms0:", c.arg(1), c.arg(2), c.arg(3), c.arg(4), c.arg(5)));
        // sceUtility_private getters of utility.prx state words (+0x18, +0x1c, +0x24, +0x28 of its state block); utility's
        // module_start clears them to 0 and nothing else in the module sets them, so they read 0.
        for (int nid : new int[]{0x5dcbd3c0, 0x10d1b3ff, 0x5809b44a, 0x1e82fb49})
            k.register("sceUtility_private", nid, String.format(java.util.Locale.ROOT, "sceUtility_private_%08x (state word)", nid), c -> 0);
        k.register(lib, 0x837c457a, "vshBridgeUnregisterCallback", c -> {
            int slot = c.arg(0); if (slot < 0 || slot >= 4) return 0x80000102; if (callbacks[slot] == 0) return 0x80000025;
            callbacks[slot] = 0; return 0;
        });
    }
}
