package org.pxp2.core;

import java.util.Locale;

/**
 * Original HLE for user libraries that only games reach (the XMB never imports them): LoadExecForUser exit handling
 * and other game-facing entry points. NIDs are the standard, publicly documented user-library NIDs.
 */
public final class HleGame {
    private final Kernel k; private final HleSystem system;
    /** Callback registered with sceKernelRegisterExitCallback (HOME menu "quit game"), or 0. */
    public int exitCallback;
    /** Set when the game called sceKernelExitGame; the host returns to the XMB. */
    public volatile boolean exitRequested;
    private int imposeLanguage = 1, imposeButton = 1;

    public HleGame(Kernel k, HleSystem system) { this.k = k; this.system = system; register(); }

    private void register() {
        String lib = "LoadExecForUser";
        // Registers the callback run when the user quits from the HOME menu ("Quit game?" -> Yes, see PspSystem.homeQuit).
        k.register(lib, 0x4ac57943, "sceKernelRegisterExitCallback", c -> {
            exitCallback = c.arg(0);
            k.log(String.format(Locale.ROOT, "sceKernelRegisterExitCallback(%08x)%s", exitCallback, k.object(exitCallback, Kernel.Callback.class) == null ? " (not a callback uid)" : ""));
            return 0;
        });
        // Quitting the game reboots into the VSH, like LoadExec's exit path: the host builds a fresh machine (PspSystem).
        k.register(lib, 0x05572a5f, "sceKernelExitGame", c -> { k.log("Game called sceKernelExitGame"); exitRequested = true; k.stopRequested = true; return 0; });
        k.register(lib, 0x2ac9954b, "sceKernelExitGameWithStatus", c -> { k.log(String.format(Locale.ROOT, "Game exited with status %08x", c.arg(0))); exitRequested = true; k.stopRequested = true; return 0; });
        // Standard stream descriptors (sceIoWrite on 1/2 goes to the guest stdout log).
        k.register("StdioForUser", 0x172d316e, "sceKernelStdin", c -> 0);
        k.register("StdioForUser", 0xa6bab2e9, "sceKernelStdout", c -> 1);
        k.register("StdioForUser", 0xf78ba90a, "sceKernelStderr", c -> 2);
        registerSas();
        registerUtility();
        // sceImpose (user): language/button mode is stored for GetLanguageMode (PSP default: English, O = confirm? the
        // game's own setting wins); the HOME popup and battery icon calls have no host-side effect.
        k.register("sceImpose", 0x36aa6e91, "sceImposeSetLanguageMode", c -> { imposeLanguage = c.arg(0); imposeButton = c.arg(1); return 0; });
        k.register("sceImpose", 0x24fd7bcf, "sceImposeGetLanguageMode", c -> {
            if (k.memory.valid(c.arg(0), 4)) k.memory.write32(c.arg(0), imposeLanguage); if (k.memory.valid(c.arg(1), 4)) k.memory.write32(c.arg(1), imposeButton); return 0; });
        k.register("sceImpose", 0x8c943191, "sceImposeGetBatteryIconStatus", c -> {
            if (k.memory.valid(c.arg(0), 4)) k.memory.write32(c.arg(0), 1); if (k.memory.valid(c.arg(1), 4)) k.memory.write32(c.arg(1), 3); return 0; });
        k.register("sceImpose", 0x381bd9e7, "sceImposeHomeButton", c -> 0);
        k.register("sceImpose", 0x5595a71a, "sceImposeSetHomePopup", c -> 0);
        k.register("sceImpose", 0x0f341be4, "sceImposeGetHomePopup", c -> 1);
    }

    /**
     * Common utility dialog status machine: 0 none, 1 initialising, 2 visible/running, 3 finished (waiting for
     * ShutdownStart), 4 shut down. Each GetStatus call advances 1 -> 2 -> 3 and 4 -> 0, which is how games poll.
     */
    private static final class Dialog { int status; int next() { int s = status; if (s == 1 || s == 2) status = s + 1; else if (s == 4) status = 0; return s; } }
    private final Dialog save = new Dialog();

    /**
     * SceUtilitySavedataParam (0x600 bytes, pspsdk layout): base.result +0x1c, mode +0x30, gameName[13] +0x3c,
     * saveName[20] +0x4c, saveNameList +0x60, fileName[13] +0x64, dataBuf +0x74, dataBufSize +0x78, dataSize +0x7c,
     * sfoParam {title[128], savedataTitle[128], detail[1024], parentalLevel} +0x80, icon0/icon1/pic1/snd0 file data
     * {buf, bufSize, size, unused} +0x584/+0x594/+0x5a4/+0x5b4. Saves live in ms0:/PSP/SAVEDATA/&lt;gameName&gt;&lt;saveName&gt;/.
     * Data is stored as the game hands it over (PXP2 does not apply the savedata encryption layer).
     */
    private void savedata(int p) {
        Memory m = k.memory; int mode = m.read32(p + 0x30);
        String game = m.string(p + 0x3c, 13), name = m.string(p + 0x4c, 20), file = m.string(p + 0x64, 13);
        int buf = m.read32(p + 0x74), bufSize = m.read32(p + 0x78), size = m.read32(p + 0x7c);
        if ((mode == 4 || mode == 5) && m.read32(p + 0x60) != 0) { // list modes: first entry of saveNameList (existing one for load)
            String first = null;
            for (int i = 0, list = m.read32(p + 0x60); i < 100; i++) {
                String n = m.string(list + i * 20, 20); if (n.isEmpty()) break; if (first == null) first = n;
                if (mode == 4 && dir(game, n).isDirectory()) { first = n; break; }
            }
            if (first != null) name = first;
        }
        java.io.File dir = dir(game, name); int result;
        switch (mode) {
            case 0: case 2: case 4: { // (auto/list) load
                java.io.File f = new java.io.File(dir, file);
                if (!f.isFile()) { result = 0x80110307; break; } // LOAD_NO_DATA
                try { byte[] d = java.nio.file.Files.readAllBytes(f.toPath()); int n = Math.min(d.length, bufSize); m.write(buf, d, 0, n); m.write32(p + 0x7c, n); result = 0; }
                catch (java.io.IOException e) { result = 0x80110305; } // LOAD_ACCESS_ERROR
                break;
            }
            case 1: case 3: case 5: { // (auto/list) save
                try {
                    dir.mkdirs();
                    java.nio.file.Files.write(new java.io.File(dir, file).toPath(), m.read(buf, Math.min(size, bufSize)));
                    String[] icons = {"ICON0.PNG", "ICON1.PMF", "PIC1.PNG", "SND0.AT3"};
                    for (int i = 0; i < 4; i++) { int fd = p + 0x584 + i * 16, b = m.read32(fd), s = m.read32(fd + 8); if (b != 0 && s > 0) java.nio.file.Files.write(new java.io.File(dir, icons[i]).toPath(), m.read(b, s)); }
                    java.nio.file.Files.write(new java.io.File(dir, "PARAM.SFO").toPath(), sfo(game + name, file, m.string(p + 0x80, 128), m.string(p + 0x100, 128), m.string(p + 0x180, 1024), m.read8(p + 0x580)));
                    result = 0;
                } catch (java.io.IOException e) { k.log("Savedata write failed: " + e.getMessage()); result = 0x80110385; } // SAVE_ACCESS_ERROR
                break;
            }
            case 11: { // LIST: idList +0x5f4 -> {maxCount, resultCount, entries}; entry {mode, ctime, atime, mtime, name[20]} = 72 bytes
                int info = m.read32(p + 0x5f4); if (info == 0) { result = 0x80110308; break; }
                int max = m.read32(info), entries = m.read32(info + 8), n = 0;
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(game + name).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"), java.util.regex.Pattern.CASE_INSENSITIVE);
                java.io.File root = dir("", ""); String[] names = root.list(); if (names != null) java.util.Arrays.sort(names);
                if (names != null) for (String d : names) {
                    if (n >= max || !pattern.matcher(d).matches() || !new java.io.File(root, d).isDirectory()) continue;
                    int e = entries + n * 72; m.fill(e, 0, 72); m.write32(e, 0x11ff); // directory, rwx
                    java.time.LocalDateTime t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(new java.io.File(root, d).lastModified()), java.time.ZoneId.systemDefault());
                    for (int i = 0; i < 3; i++) { int at = e + 4 + i * 16; m.write16(at, t.getYear()); m.write16(at + 2, t.getMonthValue()); m.write16(at + 4, t.getDayOfMonth()); m.write16(at + 6, t.getHour()); m.write16(at + 8, t.getMinute()); m.write16(at + 10, t.getSecond()); }
                    m.writeString(e + 52, d.substring(Math.min(game.length(), d.length())), 20); n++;
                }
                m.write32(info + 4, n); result = 0; break;
            }
            default: throw new Kernel.Stop(String.format(Locale.ROOT, "sceUtilitySavedata mode %d (%s%s/%s) not implemented", mode, game, name, file));
        }
        m.write32(p + 0x1c, result);
        k.log(String.format(Locale.ROOT, "Savedata mode %d %s%s/%s -> %08x", mode, game, name, file, result));
    }
    private java.io.File dir(String game, String name) {
        Vfs.Location l = k.io.vfs.resolve("ms0:/PSP/SAVEDATA/" + game + name); return l == null ? new java.io.File("") : l.file;
    }
    /** Writes a save-data PARAM.SFO (PSF 1.1: header, 16-byte index entries, sorted key table, 4-aligned data table). */
    private static byte[] sfo(String directory, String file, String title, String savedataTitle, String detail, int parental) {
        java.util.TreeMap<String, Object> v = new java.util.TreeMap<>();
        v.put("CATEGORY", "MS"); v.put("PARENTAL_LEVEL", parental); v.put("SAVEDATA_DETAIL", detail); v.put("SAVEDATA_DIRECTORY", directory);
        v.put("SAVEDATA_FILE_LIST", new byte[3168]); v.put("SAVEDATA_PARAMS", new byte[128]); v.put("SAVEDATA_TITLE", savedataTitle); v.put("TITLE", title);
        java.io.ByteArrayOutputStream keys = new java.io.ByteArrayOutputStream(), data = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer index = java.nio.ByteBuffer.allocate(16 * v.size()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (java.util.Map.Entry<String, Object> e : v.entrySet()) {
            byte[] raw; int fmt, len, max;
            if (e.getValue() instanceof Integer) { int x = (Integer)e.getValue(); raw = new byte[]{(byte)x, (byte)(x >> 8), (byte)(x >> 16), (byte)(x >> 24)}; fmt = 0x0404; len = max = 4; }
            else if (e.getValue() instanceof byte[]) { raw = (byte[])e.getValue(); fmt = 0x0004; len = max = raw.length; }
            else { byte[] s = ((String)e.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8); max = e.getKey().equals("SAVEDATA_DETAIL") ? 1024 : e.getKey().equals("SAVEDATA_DIRECTORY") ? 64 : 128; if (e.getKey().equals("CATEGORY")) max = 4; len = Math.min(s.length + 1, max); raw = java.util.Arrays.copyOf(s, max); fmt = 0x0204; }
            index.putShort((short)keys.size()).putShort((short)fmt).putInt(len).putInt(max).putInt(data.size());
            byte[] kb = (e.getKey() + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII); keys.write(kb, 0, kb.length); data.write(raw, 0, raw.length);
        }
        while (keys.size() % 4 != 0) keys.write(0);
        int keyTable = 20 + index.capacity(), dataTable = keyTable + keys.size();
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(dataTable + data.size()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46535000).putInt(0x0101).putInt(keyTable).putInt(dataTable).putInt(v.size()).put(index.array()).put(keys.toByteArray()).put(data.toByteArray());
        return out.array();
    }
    /** sceUtilityGetSystemParam ids and their registry keys. */
    private static final java.util.Map<Integer, String[]> SYSTEM_PARAMS = java.util.Map.of(
        1, new String[]{"/CONFIG/SYSTEM", "owner_name"}, 2, new String[]{"/CONFIG/NETWORK/ADHOC", "channel"},
        3, new String[]{"/CONFIG/SYSTEM/POWER_SAVING", "wlan_mode"}, 4, new String[]{"/CONFIG/DATE", "date_format"},
        5, new String[]{"/CONFIG/DATE", "time_format"}, 6, new String[]{"/CONFIG/DATE", "time_zone_offset"},
        7, new String[]{"/CONFIG/DATE", "summer_time"}, 8, new String[]{"/CONFIG/SYSTEM/XMB", "language"},
        9, new String[]{"/CONFIG/SYSTEM/XMB", "button_assign"}, 10, new String[]{"/CONFIG/SYSTEM/LOCK", "parental_level"});
    /**
     * sceUtilityLoadAvModule ids (pspsdk): 0 avcodec, 1 sascore, 2 atrac3plus, 3 mpegbase, 4 mp3, 5 vaudio, 6 aac, 7 g729.
     * avcodec (sceAudiocodec/sceVideocodec), sascore and vaudio are HLE; the others are the firmware's own libraries from
     * flash0:/kd/, as a game's disc copies of them are (see HleModules.loadModule). g729 is not supported.
     */
    private static final String[] AV_MODULES = {null, null, "flash0:/kd/libatrac3plus.prx", "flash0:/kd/mpeg.prx", "flash0:/kd/libmp3.prx", null, "flash0:/kd/libaac.prx", null};
    private int loadAv(int id) {
        if (id < 0 || id >= AV_MODULES.length) return 0x80110f01; // invalid module id
        if (AV_MODULES[id] == null) { k.log("sceUtilityLoadAvModule " + id + " (served by HLE)"); return 0; }
        return k.modules.loadUtilityModule(AV_MODULES[id]);
    }
    // ---- sceUtility (game side) ---------------------------------------------------------------------------------
    private void registerUtility() {
        String lib = "sceUtility";
        // Module loaders (id: net 1-7, usb, av 0-7, generic 0x100+). Net and usb libraries are HLE here (HleNet, HleMedia),
        // so loading them succeeds with nothing to do; av libraries are loaded as described at loadAv.
        k.register(lib, 0x1579a159, "sceUtilityLoadNetModule", c -> { k.log("sceUtilityLoadNetModule " + c.arg(0) + " (served by HLE)"); return 0; });
        k.register(lib, 0x64d50c56, "sceUtilityUnloadNetModule", c -> 0);
        k.register(lib, 0xc629af26, "sceUtilityLoadAvModule", c -> loadAv(c.arg(0)));
        k.register(lib, 0xf7d8d092, "sceUtilityUnloadAvModule", c -> 0);
        // sceUtilityLoadModule (id 0x100+ net, 0x200+ usb, 0x300+ av, 0x400+ np, 0x500+ mp4/libfont...): av ids map to the
        // sceUtilityLoadAvModule table; everything else is served by HLE.
        k.register(lib, 0x2a2b3de0, "sceUtilityLoadModule", c -> (c.arg(0) & 0xff00) == 0x300 ? loadAv(c.arg(0) & 0xff) : 0);
        k.register(lib, 0xe49bfe92, "sceUtilityUnloadModule", c -> 0);
        // System parameters come from the registry keys the XMB's settings write (ids per the public pspsdk list).
        k.register(lib, 0xa5da2406, "sceUtilityGetSystemParamInt", c -> {
            String[] key = SYSTEM_PARAMS.get(c.arg(0)); if (key == null || c.arg(0) == 1) return 0x80110103; // invalid id / not an int
            Registry.Value v = system.registry.get(key[0], key[1]); int value = v == null || v.data.length < 4 ? 0 : (v.data[0] & 255) | ((v.data[1] & 255) << 8) | ((v.data[2] & 255) << 16) | (v.data[3] << 24);
            k.memory.write32(c.arg(1), value); return 0;
        });
        // (id, value): the XMB's Network Settings > Ad Hoc Mode saves its channel this way (id 2; 0 = automatic, 1/6/11).
        k.register(lib, 0x45c18506, "sceUtilitySetSystemParamInt", c -> {
            String[] key = SYSTEM_PARAMS.get(c.arg(0)); if (key == null || c.arg(0) == 1) return 0x80110103;
            int x = c.arg(1); system.registry.set(key[0], key[1], Registry.INT, new byte[]{(byte)x, (byte)(x >>> 8), (byte)(x >>> 16), (byte)(x >>> 24)});
            if (system.registryFile != null) try { system.registry.save(system.registryFile); } catch (java.io.IOException e) { k.log("Registry save failed: " + e.getMessage()); }
            return 0;
        });
        k.register(lib, 0x34b78343, "sceUtilityGetSystemParamString", c -> { // (id, buffer, length): only the nickname (1) is a string
            if (c.arg(0) != 1) return 0x80110103;
            Registry.Value v = system.registry.get("/CONFIG/SYSTEM", "owner_name"); String name = v == null ? "PXP2" : new String(v.data, java.nio.charset.StandardCharsets.UTF_8).split("\0", 2)[0];
            byte[] b = name.getBytes(java.nio.charset.StandardCharsets.UTF_8); int n = Math.min(b.length, c.arg(2) - 1); if (n < 0) return 0x80110104;
            k.memory.write(c.arg(1), b, 0, n); k.memory.write8(c.arg(1) + n, 0); return 0;
        });
        // Save data utility: performed at InitStart without the dialog UI (see savedata()).
        k.register(lib, 0x50c4cd57, "sceUtilitySavedataInitStart", c -> { if (save.status != 0) return 0x80110001; savedata(c.arg(0)); save.status = 1; return 0; });
        k.register(lib, 0x9790b33c, "sceUtilitySavedataShutdownStart", c -> { if (save.status != 3) return 0x80110003; save.status = 4; return 0; });
        k.register(lib, 0xd4b95ffb, "sceUtilitySavedataUpdate", c -> 0);
        k.register(lib, 0x8874dbe0, "sceUtilitySavedataGetStatus", c -> save.next());
        k.register(lib, 0x0d5bc6d2, "sceUtilityLoadUsbModule", c -> 0);
        k.register(lib, 0xf64910f0, "sceUtilityUnloadUsbModule", c -> 0);
    }

    // ---- sceSasCore (game sound mixer; the disc's sc_sascore.prx is served here, see HleModules.hleGameModules) ------
    /** Mixer state per SAS core (the game's 64-byte-aligned core buffer address is the key); mixing is in SasCore. */
    private final java.util.Map<Integer, SasCore> sas = new java.util.HashMap<>();
    private SasCore sas(int core) { return sas.computeIfAbsent(core, x -> new SasCore(k.memory, 256, 32, 0, 44100)); }
    private static final int SAS_INVALID_GRAIN = 0x80420001, SAS_INVALID_MAX_VOICES = 0x80420002, SAS_INVALID_OUTPUT_MODE = 0x80420003,
        SAS_INVALID_SAMPLE_RATE = 0x80420004, SAS_INVALID_VOICE = 0x80420010, SAS_INVALID_ADSR_CURVE = 0x80420013, SAS_INVALID_PARAMETER = 0x80420014,
        SAS_INVALID_ENVELOPE_RATE = 0x80420019;
    private SasCore.Voice voice(Kernel c) { int v = c.arg(1); return v < 0 || v >= 32 ? null : sas(c.arg(0)).voices[v]; }
    /**
     * Voices, pitch, volumes, ADSR envelopes, noise and a small reverb are mixed by {@link SasCore}; __sceSasCore
     * renders one grain of 16-bit stereo into guest memory each call (the game's audio thread then outputs it through
     * sceAudio). Argument orders are the publicly documented ones.
     */
    private void registerSas() {
        String lib = "sceSasCore";
        k.register(lib, 0x42778a9f, "__sceSasInit", c -> { // (core, grainSamples, maxVoices, outputMode, sampleRate)
            int grain = c.arg(1), voices = c.arg(2), mode = c.arg(3), rate = c.arg(4);
            if (grain < 0x40 || grain > 0x800 || (grain & 0x1f) != 0) return SAS_INVALID_GRAIN;
            if (voices < 1 || voices > 32) return SAS_INVALID_MAX_VOICES;
            if (mode != 0 && mode != 1) return SAS_INVALID_OUTPUT_MODE;
            if (rate != 44100) return SAS_INVALID_SAMPLE_RATE;
            sas.put(c.arg(0), new SasCore(k.memory, grain, voices, mode, rate));
            k.log(String.format(Locale.ROOT, "SAS init core %08x grain %d voices %d mode %d rate %d", c.arg(0), grain, voices, mode, rate)); return 0;
        });
        // (core, out): mixes one grain into out (16-bit stereo, or 4 channels in multichannel mode).
        k.register(lib, 0xa3589d81, "__sceSasCore", c -> { sas(c.arg(0)).mix(c.arg(1), 0, 0, 0); return 0; });
        // (core, inOut, leftVol, rightVol): adds one grain to the samples already in inOut, which are scaled by the volumes.
        k.register(lib, 0x50a14dfc, "__sceSasCoreWithMix", c -> { sas(c.arg(0)).mix(c.arg(1), c.arg(1), c.arg(2), c.arg(3)); return 0; });
        k.register(lib, 0x99944089, "__sceSasSetVoice", c -> { // (core, voice, vagAddr, sizeBytes, loop 0/1)
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            if (c.arg(3) <= 0 || (c.arg(3) & 15) != 0 || c.arg(4) < 0 || c.arg(4) > 1) return SAS_INVALID_PARAMETER;
            boolean same = v.type == SasCore.TYPE_VAG && v.addr == c.arg(2);
            v.type = SasCore.TYPE_VAG; v.addr = c.arg(2); v.size = c.arg(3); v.loop = c.arg(4);
            if (v.playing && !same) { v.restart(); v.cur = v.next(); } // new data on a sounding voice starts from its beginning
            return 0;
        });
        k.register(lib, 0xe1cd9561, "__sceSasSetVoicePCM", c -> { // (core, voice, pcmAddr, samples, loopPos or -1): 16-bit mono
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            if (c.arg(3) <= 0 || c.arg(3) > 0x10000) return SAS_INVALID_PARAMETER;
            v.type = SasCore.TYPE_PCM; v.addr = c.arg(2); v.size = c.arg(3); v.loop = c.arg(4); return 0;
        });
        k.register(lib, 0xb7660a23, "__sceSasSetNoise", c -> { // (core, voice, freq 0..63)
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            if (c.arg(2) < 0 || c.arg(2) > 63) return SAS_INVALID_PARAMETER;
            v.type = SasCore.TYPE_NOISE; v.noiseFreq = c.arg(2); return 0;
        });
        k.register(lib, 0xad84d37f, "__sceSasSetPitch", c -> { // (core, voice, pitch 1..0x4000; 0x1000 = 1.0)
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            if (c.arg(2) < 1 || c.arg(2) > 0x4000) return SAS_INVALID_PARAMETER;
            v.pitch = c.arg(2); return 0;
        });
        k.register(lib, 0x440ca7d8, "__sceSasSetVolume", c -> { // (core, voice, left, right, effectLeft, effectRight), each -0x1000..0x1000
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            for (int i = 2; i < 6; i++) if (Math.abs(c.arg(i)) > 0x1000) return SAS_INVALID_PARAMETER;
            v.volL = c.arg(2); v.volR = c.arg(3); v.effL = c.arg(4); v.effR = c.arg(5); return 0;
        });
        k.register(lib, 0x019b25eb, "__sceSasSetADSR", c -> { // (core, voice, flags 1 A|2 D|4 S|8 R, a, d, s, r): rates
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            for (int i = 0; i < 4; i++) if ((c.arg(2) & (1 << i)) != 0 && c.arg(3 + i) < 0) return SAS_INVALID_ENVELOPE_RATE;
            for (int i = 0; i < 4; i++) if ((c.arg(2) & (1 << i)) != 0) v.rate[i] = c.arg(3 + i);
            return 0;
        });
        k.register(lib, 0x9ec3676a, "__sceSasSetADSRmode", c -> { // (core, voice, flags, a, d, s, r): curve modes 0..5
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            for (int i = 0; i < 4; i++) if ((c.arg(2) & (1 << i)) != 0 && (c.arg(3 + i) < 0 || c.arg(3 + i) > 5)) return SAS_INVALID_ADSR_CURVE;
            for (int i = 0; i < 4; i++) if ((c.arg(2) & (1 << i)) != 0) v.mode[i] = c.arg(3 + i);
            return 0;
        });
        k.register(lib, 0x5f9529f6, "__sceSasSetSL", c -> { // (core, voice, sustainLevel 0..0x40000000)
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            if (c.arg(2) < 0 || c.arg(2) > SasCore.ENVELOPE_MAX) return SAS_INVALID_PARAMETER;
            v.sustainLevel = c.arg(2); return 0;
        });
        k.register(lib, 0xcbcd4f79, "__sceSasSetSimpleADSR", c -> { // (core, voice, ADSR1, ADSR2): SPU envelope bitfields
            SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE;
            sas(c.arg(0)).simpleAdsr(v, c.arg(2) & 0xffff, c.arg(3) & 0xffff); return 0;
        });
        k.register(lib, 0x76f01aca, "__sceSasSetKeyOn", c -> { SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE; v.paused = false; v.keyOn(); return 0; });
        k.register(lib, 0xa0cf2fa4, "__sceSasSetKeyOff", c -> { SasCore.Voice v = voice(c); if (v == null) return SAS_INVALID_VOICE; v.keyOff(); return 0; });
        // End flag: bit n set while voice n is silent (never keyed on, released to zero, or a one-shot that played out).
        k.register(lib, 0x68a46b95, "__sceSasGetEndFlag", c -> { SasCore s = sas(c.arg(0)); int f = 0; for (int v = 0; v < 32; v++) if (!s.voices[v].playing) f |= 1 << v; return f; });
        k.register(lib, 0x74ae582a, "__sceSasGetEnvelopeHeight", c -> { SasCore.Voice v = voice(c); return v == null ? SAS_INVALID_VOICE : v.height; });
        k.register(lib, 0x07f58c24, "__sceSasGetAllEnvelopeHeights", c -> { SasCore s = sas(c.arg(0)); for (int v = 0; v < 32; v++) k.memory.write32(c.arg(1) + v * 4, s.voices[v].height); return 0; });
        k.register(lib, 0x787d04d5, "__sceSasSetPause", c -> { SasCore s = sas(c.arg(0)); for (int v = 0; v < 32; v++) if ((c.arg(1) & (1 << v)) != 0) s.voices[v].paused = c.arg(2) != 0; return 0; });
        k.register(lib, 0x2c8e6ab3, "__sceSasGetPauseFlag", c -> { SasCore s = sas(c.arg(0)); int f = 0; for (int v = 0; v < 32; v++) if (s.voices[v].paused) f |= 1 << v; return f; });
        k.register(lib, 0xd1e0a01e, "__sceSasSetGrain", c -> { if (c.arg(1) < 0x40 || c.arg(1) > 0x800 || (c.arg(1) & 0x1f) != 0) return SAS_INVALID_GRAIN; sas(c.arg(0)).grain = c.arg(1); return 0; });
        k.register(lib, 0xbd11b7c2, "__sceSasGetGrain", c -> sas(c.arg(0)).grain);
        k.register(lib, 0xe855bf76, "__sceSasSetOutputmode", c -> { if (c.arg(1) != 0 && c.arg(1) != 1) return SAS_INVALID_OUTPUT_MODE; sas(c.arg(0)).outputMode = c.arg(1); return 0; });
        k.register(lib, 0xe175ef66, "__sceSasGetOutputmode", c -> sas(c.arg(0)).outputMode);
        // Reverb: type 0..9, (delay, feedback) for the echo/delay types, effect volumes, and the dry/wet output switches.
        k.register(lib, 0x33d4ab37, "__sceSasRevType", c -> { if (c.arg(1) < -1 || c.arg(1) > 9) return SAS_INVALID_PARAMETER; sas(c.arg(0)).revType = Math.max(0, c.arg(1)); return 0; });
        k.register(lib, 0x267a6dd2, "__sceSasRevParam", c -> { SasCore s = sas(c.arg(0)); s.revDelay = c.arg(1); s.revFeedback = c.arg(2); return 0; });
        k.register(lib, 0xd5a229c9, "__sceSasRevEVOL", c -> { SasCore s = sas(c.arg(0)); s.revVolL = c.arg(1); s.revVolR = c.arg(2); return 0; });
        k.register(lib, 0xf983b186, "__sceSasRevVON", c -> { SasCore s = sas(c.arg(0)); s.dryOn = c.arg(1); s.wetOn = c.arg(2); return 0; });
    }
}
