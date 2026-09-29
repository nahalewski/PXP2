# PXP2 — PSP findings and fix log

This is a working notebook of everything learned while bringing up the real Sony 6.61 VSH/XMB on PXP2's own HLE kernel. Each entry says what was observed, how it was established (the evidence), and what was changed.

**How behaviour is established.** Most `sceVshBridge` and driver NIDs in 6.61 are obfuscated. They are identified with PXP2's own tools, never by guessing:

- `core/tests/ExportMap.java`: traces each export of a firmware module to the kernel function it wraps. The map for `kd/vshbridge.prx` is kept in `local-test/vshbridge-map.txt`.
- `bridge.sh <nid>`: disassembles the wrapped kernel function inside the firmware's own `kd/*.prx`.
- `kfind.sh <library> <nid>`: disassembles any firmware export.
- `core/tests/Dis.java`, `Xref.java`, `Stubs.java` and `Str.java`: disassembly, address cross-references, import stub names and strings.
- `FrameDump`:
  - `-Dpress=frame:mask` presses buttons at given frames;
  - `-Dwatch=pc,...` sets PC watchpoints (with a0–a3/v0 logged);
  - `-Dlog=regex` prints matching kernel log lines;
  - `-Dwide=f` renders widescreen.
  - `-Dscale=N` renders at N× internal resolution (`-Dssaa`, `-Dsmooth`, `-Dtexfilter`, `-Dfrom`: see §6).
- `-Dpxp2.traceRegistry=true`: logs every registry lookup with the type and size returned.

Unimplemented imports stop emulation with a report (`Kernel.syscall`), so every gap is found explicitly rather than hidden.

---

## 1. Firmware and boot

### Boot order
- The VSH boot order comes from the firmware's own `flash0:/kd/pspbtcnf.bin`, VSH mode bit 1: 76 modules in total, 6 of them user-mode.
- The user modules start in this order: `usersystemlib` (served by HLE `Kernel_Library`), `libatrac3plus`, `paf`, `common_gui`, `common_util`, `vshmain`.

### Module format quirks
- Some library PRXs, such as `kd/mpeg_vsh.prx`, have ELF `e_entry = 0xffffffff`, meaning no entry point. The loader must accept this. **Fix:** `PrxLoader` treats it as "no entry" (commit "PRX entry outside allocation").
- `module_start` and `module_stop` come from the export "syslib" (NIDs `0xD632ACDB`, `0xCEE8593C`). Their thread parameters come from `0x0F7C276C`.
- Variable imports are resolved through relocation lists. Entries are `type << 26 | address >> 2`, zero-terminated, with types 2 (32-bit), 4 (J26), 5/6 (HI16/LO16).

### Thread attributes
- `0x00100000` is `NO_FILLSTACK`, **not** "kernel". The user bit is `0x80000000`. Kernel modules are flagged by module attribute `0x1000`.
- **Error fixed:** VSH thread stacks were being allocated in kernel memory.
- **Error fixed:** kernel modules loaded from `flash0:/kd/` went into user memory. They now load into partition 1 and start on kernel threads with a 16 KB default stack.

### module_start thread lifetime
- Like the firmware, the `module_start`/`module_stop` thread is deleted when it returns.
- **Error fixed:** PXP2 kept each finished thread's 256 KB stack. Five such stacks (opening, impose, msvideo ×2, font) filled the 24 MB user partition, so later plugin loads failed with "Out of memory loading mlncmn.prx".

### Cold boot vs restart
- The UMD-equipped model path (`vshKernelGetModel` result 0–3, 6, 8, 10) does not register the post-opening transition itself. It waits for the **UMD driver to report drive state to the registered UMD callback**.
- **Fix:** `sceUmdRegisterUMDCallBack` now immediately notifies with `0x01` (no disc). Before this, the XMB stalled after the boot animation and never loaded `topmenu_plugin`.

### First-boot repair and VSH restart
- On first boot vshmain shows "Setting information is corrupted. Press ○…".
- ○ calls `vshKernelExitVSHVSH` (bridge `d47041ca` → `LoadExecForKernel:08f7166c`) with a `SceKernelLoadExecVSHParam`:
  - size `0x30`;
  - key `"vsh"`;
  - 1024 bytes of `vshmain_args`, which are 16 × 64-byte event records. Record 1 flags the repair.
- **Implementation:** `PspSystem.restartIfRequested()` builds a fresh machine and passes those arguments to the new vshmain's `module_start`. The registry carries over.

### The opening
- `opening_plugin` exposes an interface through `scePaf:675047b1`. vshmain registers completion callback `089e7368` via entry 1. The plugin calls it with 1 when both the animation and the boot sound are finished.
- The sound thread uses `libatrac3plus` and `sceAudio`.
- **Error fixed:** `sceAtracSetDataAndGetID` returned `0x80630003` ("no ID") because `libatrac3plus` could not allocate its ID pool: `0x80000022` from its reinit routine.
  - **Root cause:** `sceAudiocodecCheckNeedMem` reported 1 MiB per decoder, but 2×AT3+ plus 2×AT3 must fit in `0x19000` bytes of ME EDRAM.
  - **Fix:** needed memory is now `0x7bc0` (AT3+) and `0x3de0` (AT3).
- **Also fixed:** `sceAudiocodecGetEDRAM` must return the caller's buffer (`ctx+16`) aligned to 64 bytes in `ctx+12`. It must not allocate memory.

---

## 2. Registry (`/CONFIG`, `/DATA`, `/REGISTRY`)

- **Key types are 1 = directory, 2 = int, 3 = string, 4 = binary.**
  - **Error fixed:** ints were reported as type 1, so vshmain's registry check (`089e5760`, which expects `/REGISTRY/category_version` to be type 2, size 4, value `0x66`) declared the settings corrupt on every boot.
- `/CONFIG/VIDEO/menu_language`, `sound_language` and `subtitle_language` are **binary** 2-byte language codes (`"en"`). vshmain's generic registry getter (`089e5d98`) is called with (type, max size). A mismatch returns `0x80108401`, which shows as "An internal error has occurred (80108401)" when opening Video Settings.
- `/DATA/FONT/PROPERTY/INFOn` is filled from the PGF font headers (sizes at `0x24`/`0x28`, resolutions at `0x2c`/`0x30`, font name at `0x35`, type at `0x75`). Order: `jpn0`, `ltn0`–`ltn15`, `kr0`.
- `/CONFIG/NETWORK/GO_MESSENGER/auth_name` and `auth_key` are read by `sceUtility:28d35634` / `ef3582b2` (64-byte copies).
- The registry persists to `flash1/registry/pxp2-registry.bin` (PXP2's own format).
- When loading, saved values whose type conflicts with a corrected default are ignored, so installs saved with the old types heal automatically.

### XMB settings → registry map (PSP Settings screen)

**How it was traced.** A scratch harness booted the XMB on a copy of `local-test/storage`, drove it with button presses (the `-Dpress` scheme), and logged every `Registry.set` through a `ChangeListener` plus every impose change, while each Settings item was opened, moved one step and confirmed, over its whole list. Screenshots of each open list give the labels. The keys were cross-checked against the strings of `sysconf_plugin.prx`, `vshmain.prx` and `kd/impose_01g.prx`. **Verified** = the XMB wrote exactly this value for this label under PXP2 (or, for button assign and colours, the XMB was booted with the value and showed the effect). **Inferred** = the key is in the firmware strings but the XMB path could not be exercised.

- **Settings column (PSP-1000, 6.61):** USB Connection, Video, Photo, System, Theme, Date & Time, Power Save, Sound, Security, RSS Channel, Network. There is no Remote Play or PSN entry, no UMD Cache or USB Charge (the model is PSP-1000), and no WLAN switch (it is a hardware switch).
- **Values are written when the list closes** with ✕, and the XMB reads them at start-up (frame 1–7 reads: `button_assign`, `THEME/*`, `language`, `date_format`, `time_format`, `oem`, `operation_sound_mode`, `umd_autoboot`, `exh_mode`, `usb_auto_connect`; `parental_level` at the XMB). So a host editor must work on the saved file while the XMB is not running, and changes apply on the next start.

| XMB item | Registry key (type) | Values (label = value) | Status |
|---|---|---|---|
| Video > UMD™ Menu Language | `/CONFIG/VIDEO/menu_language` (bin, 2) | ISO codes: da, de, en, es, fi, fr, it, ja, ko, nl, no, pt, ru, sv, zh; "Language Options" opens a code entry | verified |
| Video > UMD™ Volume | `/CONFIG/VIDEO/appended_volume` (int) | Normal 0, +1 1, +2 2 | verified |
| Video > Audio Language | `/CONFIG/VIDEO/sound_language` (bin, 2) | "00" Original Language, then the codes above | verified |
| Video > Subtitle Language | `/CONFIG/VIDEO/subtitle_language` (bin, 2) | the codes above | verified |
| Video > L/R Button | `/CONFIG/VIDEO/lr_button_enable` | Do Not Use 0, Skip 1 | verified |
| Video > Sequential Playback | `/CONFIG/VIDEO/list_play_mode` | Off 0, On 1 | verified |
| Video > Title Display | `/CONFIG/VIDEO/title_display_mode` | Title 0, File Name 1 | verified |
| Photo > Slideshow Speed | `/CONFIG/PHOTO/slideshow_speed` | Fast 0, Normal 1, Slow 2 | verified |
| System > Nickname | `/CONFIG/SYSTEM/owner_name` (string) | text (the XMB shows the saved value; the OSK was not driven, the 20-character limit is assumed) | key verified, length inferred |
| System > System Language | `/CONFIG/SYSTEM/XMB/language` | Japanese 0, English 1, French 2, Spanish 3, German 4, Italian 5, Dutch 6, Portuguese 7, Russian 8, Korean 9, Traditional Chinese 10, Simplified Chinese 11 (list order is alphabetical by native name). Impose param 0x80 mirrors it | verified (all 12) |
| System > Character Set | `/CONFIG/SYSTEM/CHARACTER_SET/oem` + `ansi` | US 437 = 1/19, Multilingual Latin 1 850 = 5/19, Russian 866 = 11/18, Shift-JIS 932 = 13/13, GBK 936 = 14/14, Korean 949 = 15/15, Big5 950 = 16/16 | verified (all 7) |
| System > UMD™ Auto-Start | `/CONFIG/SYSTEM/umd_autoboot` | Off 0, On 1 | verified |
| System > USB Auto Connect | `/CONFIG/SYSTEM/usb_auto_connect` | Off 0, On 1 | verified |
| (Enter button, not in this XMB's menu) | `/CONFIG/SYSTEM/XMB/button_assign` | 1 = ✕ Enter / ○ Back, 0 = ○ Enter / ✕ Back | verified (booted with 0: dialogs show "✕ Back ○ Enter" and ○ confirms) |
| Theme > Theme | `/CONFIG/SYSTEM/XMB/THEME/custom_theme_mode` + `wallpaper_mode` + `color_mode` | Original 0/0/0, Classic 3/4/0, Custom Theme 2/3/0. Applying any theme resets the colour to By Month. A custom theme is `flash1:/vsh/theme/custom_theme.dat`, a copy of the applied .ptf/.ctf | verified |
| Theme > Color | `THEME/color_mode` + `THEME/system_color` | By Month = color_mode 0; otherwise color_mode 1 and system_color 0..11 = the January..December colours (silver, gold, lime, pink, green, lavender, turquoise, blue, purple, orange, brown, red) | verified (all 12 booted and screenshot) |
| Theme > Background | `THEME/wallpaper_mode` | Original 0, Wallpaper 1 (needs `flash1:/vsh/theme/wallpaper.bmp`, set from Photo; without it the XMB says "There is no wallpaper set" and reverts), Custom Theme 3, Classic 4 | verified (1 reverts without a wallpaper; the file name is from `dialogmain.prx`) |
| Date & Time > Date Format | `/CONFIG/DATE/date_format` | YYYY/MM/DD 0, MM/DD/YYYY 1, DD/MM/YYYY 2 (impose 0x40) | verified |
| Date & Time > Time Format | `/CONFIG/DATE/time_format` | 24 Hour Clock 0, 12 Hour Clock 1 (impose 0x20) | verified |
| Date & Time > Time Zone | `/CONFIG/DATE/time_zone_area` (string) + `time_zone_offset` (minutes) | 109 zones, e.g. united_kingdom/0 "GMT+00:00 London", netherlands/60 "GMT+01:00 Amsterdam", japan/540; the full ordered table is `XmbSettings.TIME_ZONES` | verified (every entry was stepped through) |
| Date & Time > Daylight Saving | `/CONFIG/DATE/summer_time` | Standard 0, Daylight Saving 1 | verified |
| Date & Time > Date and Time | — | PXP2's RTC follows the host clock | n/a |
| Power Save > Backlight Auto-Off | `/CONFIG/SYSTEM/POWER_SAVING/backlight_off_interval` (seconds) | Off 0, 2 min 120, 5 min 300, 10 min 600 | verified |
| Power Save > Auto Sleep | `POWER_SAVING/suspend_interval` (seconds) | Off 0, 5 min 300, 10 min 600, 15 min 900 | verified |
| Power Save > WLAN Power Save | `POWER_SAVING/wlan_mode` | Off 0, On 1 | verified |
| Sound > AVLS | `/CONFIG/SYSTEM/SOUND/avls` | Off 0, On 1 (impose 0x10 mirrors it) | verified |
| Sound > Dynamic Normalizer | `SOUND/dynamic_normalizer` | Off 0, On 1 | verified |
| Sound > Key Tone | `SOUND/operation_sound_mode` | Off 0, On 1 | verified |
| Security > Change Password | `/CONFIG/SYSTEM/LOCK/password` (string, 4 digits, default "0000") | | inferred (key in `vshmain.prx`) |
| Security > Parental Control Level | `LOCK/parental_level` | Off 0 (shown), 1–11 | 0 verified; 1–11 inferred |
| Security > Internet Browser Start Control | `LOCK/browser_start` | Off 0 (shown), On 1 | 0 verified; 1 inferred |
| RSS Channel > Item Save Options | `/CONFIG/RSS/download_items` | Latest item 1, Last 2 items 2, Last 5 5, Last 10 10, All items 0 | verified |
| Network > Ad Hoc Mode (channel) | `/CONFIG/NETWORK/ADHOC/channel` | Automatic 0, Ch 1 1, Ch 6 6, Ch 11 11 | verified (after the fix below) |
| Network > Infrastructure Mode | `/CONFIG/NETWORK/INFRASTRUCTURE/<n>/…` | saved connections; listed read-only | n/a |
| Volume (impose) | `/CONFIG/SYSTEM/SOUND/main_volume` | 0–30 | verified (key in `impose_01g.prx`; persisted by HleVsh) |
| Mute (impose) | `/CONFIG/SYSTEM/SOUND/mute` | Off 0, On 1 | verified (same) |
| Brightness (impose) | `/CONFIG/SYSTEM/backlight_brightness` | levels 1–4, default 3 | key from `impose_01g.prx`; range inferred |

- **Security under PXP2:** opening Parental Control Level fails with "An internal error has occurred (80110B02)" before the password prompt, so levels 1–11 and browser On could not be traced.
- **Error fixed — Ad Hoc channel save stopped the emulator:** Network Settings > Ad Hoc Mode saves through `sceUtilitySetSystemParamInt` (`sceUtility:45c18506`, id 2), which was unimplemented. It is now HLE (`HleGame`), writing the same registry keys `sceUtilityGetSystemParamInt` reads, and saving the registry.
- **Impose persistence:** volume, mute and brightness were only in `HleVsh.impose` and reset to 15/0/3 at every start. The real impose driver keeps them in the registry keys above, so HleVsh now seeds them from the registry on first use (after the load, and after a VSH restart hands the registry over) and writes them back, saving the file, when they change (volume keys or `vshImposeSetParam`). Tested: VOL+ ×3, VOL− gives `main_volume` 17 in the saved file. A registry with 5/muted/2 boots with impose volume 5, mute 1 and brightness 2. `backlight_brightness` (3) was added to the first-boot defaults.
- **Launcher colour fix:** `LauncherIconManager.resolveColorIndex` treated `system_color` as its own icon index and any non-zero `custom_theme_mode` as a custom theme. It now maps system_color 0..11 through the month table and treats only mode 2 as custom (Classic is 3).
- **The PSP Settings screen** (`XmbSettingsActivity`, model in `core/.../XmbSettings.java`) shows exactly the rows above, grouped like the Settings column. Each change loads the saved registry, changes one setting and saves it with `Registry.save`. Edits are refused ("Close the XMB to change settings") while XmbActivity's emulation thread is alive. Desktop proof: Spanish, ○-enter and Green written through `XmbSettings` give a Spanish, green XMB whose dialogs read "✕ Atrás ○ Aceptar" (`local-test/evidence/xmb-settings/`).

---

## 3. sceVshBridge NIDs (6.61) — identified

| NID | Wraps | Behaviour in PXP2 |
|---|---|---|
| 21c243fe | SysMemForKernel:07c586a1 | model: 0 (PSP-1000) |
| db7c3d5a / 837c457a | bridge callback table (4 slots) | register/unregister callback slot (0x80000102 bad slot, 0x80000020 busy, 0x80000025 empty) |
| c949966c | scePower_driver:ba566cd0 | power flags setter (0x28/0x828), recorded |
| 61001d64 | sceChkreg_driver:59f8491d | sceChkregGetPsCode: {1, 4 (North America), 1, 0} |
| 3512f4bc | sceGe_driver:547ec5f0 | EDRAM hardware size, 2 MiB |
| 0296ca2b 228b9bc0 63047647 7e117907 f8601869 | hprm/impose stubs that literally return 0 | 0 |
| 0543156c | sceDisplay_driver:996881d2 | display init fault flag, 0 |
| 29cdffba | sceRtc_driver:e09880cf | same routine as sceRtcSetAlarmTick |
| c6395c03 | sceCtrl_driver:be30ced0 | same routine as sceCtrlReadBufferPositive |
| a5628f0d | ModuleMgrForKernel:d5ddab1f | vshKernelLoadModuleVSH |
| ce32cbef / 6caeb765 / 53bfd101 | sceMSAudio_driver (MagicGate) | init / register handler / 0x80508004 "not ready" |
| 9427c909 | sceMeCore_driver:5dff5c50 | Media Engine mode request (logged; ME not emulated) |
| 74dbe57e 88c35487 d7d7e7b6 | sceMePower_driver | ME power commands (logged) |
| d3a07961 | sceHprm_driver:e9b776be | headphone remote handler/poll, 0 |
| 06bf847b | scePower_driver:5f5006d2 | power hardware flag from syscon version, 0 on PSP-1000 |
| 2380dc08 | IoFileMgrForKernel devctl | vshIoDevctl, forwarded |
| 4db43867 | sceIdStorage_driver:6fe062d1 | IdStorage lookup: no image, 0x80000025 |
| 4a596d2d / 639c3cb3 | sceImpose_driver:3c318569 / dc3becff | impose SetParam / GetParam (ids 0x80000001–0x80000012; 0x80000107 unknown) |
| 5894c339 | sceImpose_driver:0f067e16 | take pending changes, 0 |
| 0d7a4fe4 | sceChkreg_driver:6894a027 | chkreg IdStorage data: 0x80000041 (unreadable) |
| d47041ca | LoadExecForKernel:08f7166c | vshKernelExitVSHVSH (restart) |
| 7d1c13b5 / 5e5af7a2 / bd5ebfba | sceDisplay_driver | flag store / GetMode / SetMode |
| d49c57e3 | sceRtc_driver:dff30673 | stores 4 RTC configuration words |
| af135135 7816d515 eb7b7992 ca223904 | sceVaudio_driver 03b6807d / 67585dfd / 8986295e / 27acc20b | Vaudio reserve / release / output (timed, mixed) / setting |
| 96c67d2c / 74c48d87 | UtilsForKernel:a6b0a6b8 / 39ffb756 | get/set value pair (msvideo saves, clears, restores) |

**Other NIDs established from the firmware:**

- `scePower:db62c9cf` (read-and-clear pending request), `78a1a796` (IsSuspendRequired).
- `sceRtc:c2ddbeb5` / `7d1fbed3` (Get/SetAlarmTick).
- `e51b4b7a`, `26d7a24a`, `cf3a2ca8`: TickAddDays/Hours/Weeks, with an int argument.
- `e6605bca`: TickAddMinutes, with an s64 argument.
- `sceResmgr:9dc14891`: decrypts `vsh/etc/index_01g.dat`. The firmware ships the same text as `flash0:/vsh/etc/version.txt`.
- `sceUsb:8bfc3de8` / `89de0dc5`: USB state callback register/unregister.
- `SysMemUserForUser:2a3e5280`: sceKernelQueryMemoryInfo.
- `sceNetApctl_lib:7cfab990`: internal state handler.

---

## 4. Devices

- `fatms0:` devctl `0x02425856`: vshmain one-word setter. It is recorded.
- `flashfat*:` devctl `0x5802`: FAT flush.
- `msstor0p1:` devctl `0x02125802`: Memory Stick media type. `msstor.prx` reports `0x10/0x11/0x40/0x41` or 0, and PXP2 reports `0x10` (PRO).
- **Memory Stick directory entries:** FAT drivers fill the caller's `d_private` block (`SceIoDirent+344`) with an 8.3 short name at +4 and a long name at +20 when its size is at least `0x414`.
  - **Error fixed:** the music browser skipped every file ("There are no tracks") because this block was left empty.
- **Network kernel modules** (`ifhandle`, `pspnet*`) and **MagicGate DRM drivers** (`mlnbridge*`) are hardware drivers. Loading them yields HLE placeholder modules, and their libraries are served by `HleNet`. WLAN reads as switched off.

---

### UMD drive (ISO images)

- **Images:** `Iso9660` reads the image. `umd0:` is raw, addressed in sectors; `disc0:`/`isofs0:` are the file system. Insert a disc with `PspSystem.insertUmd(File)` or `insertUmd(IsoSource)`, which carries over VSH restarts. `Iso9660` reads through `IsoSource`: a local file, or `HttpIsoSource`, which streams the image from the PC hub (see "Library" below). On Android, choose it with **Installer → UMD drive** from `PXP/ISO`. On the desktop, pass `-Dumd=image.iso` (or an `http://` hub URL) to FrameDump.
- **Drive state** (`sceUmdGetDriveStat`, UMD callbacks): 0x01 no disc, 0x12 present and initialised, 0x32 once activated. Callbacks are notified on register, activate/deactivate and insert.
- **devctl on `umd0:`:**
  - `0x01f20001` disc type. It writes only `out+4`: 0x10 game, 0x20 video, 0x40 audio. **Error fixed:** writing `out+0` clobbered the caller's size field.
  - `0x01f20002`/`03` LBA counts.
  - `0x01f100a3..a9` seek/cache hints.
  - `0x01f300a5` prepare cache.
- **Bridge `5c2983c2`** = `sceChkreg_driver:54495b19` (CheckRegion). It returns 1, so every region is allowed.
- **vshmain disc check** (at `08a1ac40`, found by disassembly):
  1. devctl disc type, where `s0 & 0x7070` must be non-zero.
  2. A region callback fills the allowed-kind flags.
  3. It reads **48 bytes** of `disc0:/UMD_DATA.BIN` and rejects shorter files ("The disc could not be read").
  4. It scans bytes 33..46 for the kind letters `G`/`V`/`A`. The OR of 0x10/0x20/0x40 must **equal** the devctl disc type.
  5. It stats `PSP_GAME/SYSDIR/UPDATE/PARAM.SFO` and `PSP_GAME/PARAM.SFO`.
  
  PXP2 therefore derives the disc type from UMD_DATA.BIN itself. **Error fixed:** a UMD Video disc was reported as a game and failed the check.
- **Results:**
  - A synthetic disc (`tools/make-test-umd.py`, original content only) appears in the Game column with its icon and title. This was verified on the Fold.
  - A UMD Video (Advent Children) now appears in the Video column. The XMB then reads `UMD_VIDEO/PARAM.SFO`, `ICON1.PMF` (animated icon, which needs the video decoder), `SND0.AT3` (which needs ATRAC3) and `PIC1.PNG`. Until those decoders exist its entry has no icon or title.
- **Hot-swap (background UMD swap):** `PspSystem.requestUmdSwap(File)` can be called from any thread and is applied at the next run slice. It ejects first, and callbacks get 0x01. After 1 s of emulated time it inserts the new disc and reports 0x12, then 0x32 after a 300 ms spin-up.
  - **Error fixed:** resetting activation on a disc change left the new disc stuck at "present". vshmain activates the drive once at boot, and its callback (`08a1ab40`) acts only on the ready bit 0x20.
  - This was verified on the PC (game → video → game) and on the Fold through the in-game **DISC** button, which lists `PXP/ISO` plus Eject.
- **Still to do:** UMD Video playback needs the UMD Video player path (MPEG-PS with AVC and ATRAC3plus).

### Themes

- **Menu path:** Settings → Theme Settings → Theme. The list shows the built-in Original and Classic themes, and the XMB scans `ms0:/PSP/THEME` for `.ptf` files. On the phone that folder is `PXP/PSP/THEME`, which PxpStorage creates.
- **Built-in theme:** switching to Classic works. The XMB shows "Please wait…", reloads the theme and keeps the setting.
- **Custom `.ptf` themes work.** Tested with the "PlayStation 15th Anniversary" official theme, a 213 KB file from the archive.org collection-of-psp-themes, downloaded with the user's approval.
  - **Sequence:** the Theme list shows the PTF with its icon, the preview renders it, and Apply writes `flash1:/vsh/theme/custom_theme.dat`, which is a copy of the PTF.
  - **Result:** the XMB reloads with the theme's wallpaper and icons, and it persists across cold boots.
  - **Error fixed:** Apply failed with **80108351** because `flash1:/vsh/theme` did not exist, so the create returned 0x80010002. PspSystem now creates `flash1/vsh/theme` (and `ms0/PSP/THEME`) at startup.
- **Open issue:** the Classic wave renders with stair-stepped edges.

### CTF themes (CXMB format) — loader implemented and tested

- **CTF files:**
  - `local-test/themes/PS3.theme.6.60.ctf` (3.5 MB, pspunk.com) — 25 entries (19 whole-file, 3 inline patch lists, 3 `.bin` blobs).
  - `local-test/themes/lbp-extracted/LittleBigPlanet.Dynamic.660.ctf` (4.3 MB, komstation.com) — **active test CTF**. Pushed to `PXP/PSP/THEME/` on both devices.
- **Layout** (shared format):
  - **0x00:** complete PTF (`\0PTF`). Theme Settings lists it; Apply copies the **whole CTF** to `flash1:/vsh/theme/custom_theme.dat`.
  - **0x10:** u32 magic `0xDEAD0660`. **0x14:** u32 theme ID. **0x18:** u32 count. **0x1c:** u32 ptfEnd.
  - **Entry table:** last `count × 0x48` bytes: `char path[64]`, `u32 offset`, `u32 size`.
- **Entry kinds** (worked out from the LBP CTF, the ~75 CTFs in `themes/ctf/` and the 6.61 modules in `flash-661-01g-v1/flash0`; the kind does **not** depend on `offset < ptfEnd`):
  - **File redirect:** any entry whose payload is the file itself: `.rco`, `.pmf` (`gameboot.pmf`), `.bmp` (`01-12.bmp`, `01-12_03g.bmp`, `13-27.bmp`), `/font/ltn0.pgf`, `custom_theme.dat`, and a `.prx` whose payload starts with `\x7fELF` (plain, decrypted module). Paths vary in case (`GAMEBOOT.PMF`, `/font/LTN0.PGF`); matching is case-insensitive.
  - **Patch list:** a `.prx` entry whose payload is not a module (in practice only `paf.prx`, `vshmain.prx`, `common_gui.prx`). `size` is the **record count**. Records are `u32 offset, u32 len, byte[len]`, packed.
    - **Addressing:** `offset` is relative to the module's **load address** (text start = ELF vaddr 0). Checked against the 6.61 images: every record lands on a `lui $at` / `ori $at` immediate or on a float in `.rodata`, and the "old" bytes match. Example: PS3 theme `paf.prx +0x106909` changes `lui $at,0x4348` (200.0) to 0x4448 (800.0).
    - **vshmain's `0xffffff60` (−0xa0) record** writes 2 bytes (`0c 00`) over what would be the ELF header in file terms (text starts at file offset 0xa0). It lies outside the loaded module, so it is skipped. Every vshmain list in `themes/ctf/` has it, apparently an artefact of the theme tool.
  - **Float table** (`/vsh/module/NAME.bin`, only seen in LBP): one float per **float-constant load site** of `NAME.prx`, in **reverse code order**.
    - **What a site is:** `lui $at,hi`, an optional `ori $at,$at,lo`, then immediately `mtc1 $at,$fN`. The 6.61 site counts match the tables exactly (paf 2212, common_gui 145, opening_plugin 49). Other `lui` registers are not sites (paf has one `lui $v0` NaN load that the table skips).
    - **Contents:** the table is the module's full constant set with the theme's edits. LBP changes 11 paf constants: four 200.0 → 700.0 at `+0x106908/0x10693c/0x10a23c/0x10a27c`, the same sites the PS3 theme patches, plus swapped pairs at `+0x117cf8..0x117e28`. It changes 10 in common_gui: 320 → 272, 480 → 144, −240 → −72, −235 → −65, 40 → 0, 100 → 200. opening_plugin is unchanged.
- **LBP CTF — 26 entries:**
  - 2 plain-ELF `.prx`: opening_plugin, update_plugin. These are stock 6.60 builds; they differ from 6.61 only in one version byte.
  - 3 float tables: common_gui, opening_plugin, paf.
  - 18 `.rco`, including system_plugin_bg/fg, topmenu_plugin/icon and opening_plugin.
  - `gameboot.pmf`, the inner `custom_theme.dat`, and `ltn0.pgf` ("Omnes Bold").
- **PS3 CTF — 25 entries:** 19 file redirects and 3 patch lists (common_gui 2, paf 20, vshmain 7).
- **Loader — `CtfLoader.java`** (CXMB behaviour, original code):
  - **`parse()`** classifies the entries.
  - **`overlay(vfs, data, kernel)`** mounts the file redirects with `Vfs.mountCtfEntry`. It registers patch lists and float tables as a `HleModules.PostLoadPatcher`, which runs after mapping, relocation and linking and before `module_start`, keyed by module file name under `/vsh/module/`.
  - **Log lines:** `CTF overlay:` (mounted), `CTF overlay served` (sceIoOpen), `CTF patch:` (one per record, with the old instruction), `CTF float:` (one per changed constant), plus a summary per list or table.
  - **Safety:** a float table is applied only when the site count matches (otherwise logged and skipped). A site without an `ori` gets only the upper half.
- **Error fixed — the overlay was only half wired:** `Vfs.read` (module loads) saw it, but `HleIo.open` and `sceIoGetstat` went straight to the host file.
  - **Effect:** every RCO, `gameboot.pmf` and `ltn0.pgf` came from the real flash0, and the theme showed only its PTF wallpaper and icons.
  - **Fix:** they now serve `Vfs.ctfOverride(path)` as a read-only in-memory handle.
- **Hook points added:** `HleModules.postLoadPatchers` (for other features too, e.g. seplugins), `Vfs.ctfOverride`, and the overlay check in `HleIo.open` and `sceIoGetstat`.
- **Result (LBP, frames 700/900/1100/1300):** the dynamic layers now draw and animate: Sackboy waving, the sun, the swinging kangaroo sign, the rope and the LittleBigPlanet logo sliding in. The font is the theme's Omnes Bold. Before, only the PTF wallpaper and icons showed.
- **Regressions checked:** plain XMB boot (no custom theme) ends "Stopped by host" at 700; PS3 Theme (3 patch lists, 28 records applied, the −0xa0 skipped) and Allure (39 paf + 9 vshmain records, 15 plain-ELF module redirects) reach the XMB.
- **Open issue — Matrix Dynamic:** its modified `opening_plugin.prx` (code changed from `+0x3308` on) plays its own boot sound from its `opening_plugin.rco`, then spins in the ATRAC streaming loop (`+0x2170`..`+0x2238`, AddStreamData of 0 bytes, no blocking call).
  - **Symptom:** that thread runs at priority 0x10, so SCE_VSH_GRAPHICS (0x20) starves and the boot stays on the Matrix logo.
  - **What does not trigger it:** the stock prx with the theme's RCO, or the theme's prx with the stock RCO (the state before the sceIoOpen fix). The likely cause is the loop/end-of-stream handling of the looping boot sound under PXP2 (sceAudiocodec results), not the CTF loader.
- **To apply on device:** Theme Settings → Theme → select CTF → Apply → restart. Or copy CTF directly to `flash1/vsh/theme/custom_theme.dat` (with `/CONFIG/SYSTEM/XMB/THEME custom_theme_mode` = 2). The overlay is read when PspSystem is constructed, so a CTF applied from Theme Settings takes effect after the next VSH restart, as with CXMB.
- **Risk:** replacement `.prx` files target 6.60. The 6.60 system modules share the 6.61 NIDs and layout (all patch records hit the expected instructions), but a heavily modified module can still misbehave (see Matrix).


### Game launch from the XMB (Dragon Ball Z Shin Budokai, ULUS10081)

- **Launch sequence:** ✕ on the disc calls three bridges in order:
  1. `3a46c639` = `KDebugForKernel:27b23800`, a tool/development-hardware query. Retail returns 0.
  2. `aab9a9ef` = `SysMemForKernel:807179e7`, sceKernelSetParamSfo with the disc ID string and the version "00.00".
  3. `63e69956` = `LoadExecForKernel:d8320a28`, sceKernelLoadExecVSHDisc("disc0:/PSP_GAME/SYSDIR/BOOT.BIN", param).
- **Game boot** (reboot into GAME mode, loading the executable): see "Game boot" below.

### Game boot

**Result: Dragon Ball Z Shin Budokai boots from the XMB and plays.** Frames verified on the desktop: the XMB's "PSP" game-boot animation, the Atari / Okratron 5000 / Dimps logos, the title screen (New Game / Load Game), Name Entry, a save written and listed, Dragon Road story dialogue with PGF text, and a 3D Goku vs Gohan battle with sky, terrain and cel-shaded characters. Sound: ADX music and movie audio play through sceAudio, and the menu/battle sound effects and voices through the HLE SAS mixer (see §5 "sceSasCore").

- **LoadExec disc boot** (`HleVsh`, bridge `63e69956` → `LoadExecForKernel:d8320a28`, sceKernelLoadExecVSHDisc(path, param)):
  - `SceKernelLoadExecVSHParam` here: size `0x30`, args `0x20` bytes, argp, key `"game"` at +12.
  - Like the VSH restart, PXP2 records the path, args and key, sets `rebootRequested` and stops. `PspSystem.restartIfRequested()` builds a fresh machine that keeps the registry and the inserted UMD, and carries `gamePath`. `startVsh` then dispatches to `startGame`, so `FrameDump` and the Android loop need no extra step.
  - The XMB's game-boot animation plays before the call.
- **GAME mode boot list:** `pspbtcnf.bin` mode bit 2 has 48 modules, and the only user-mode one is `usersystemlib`, which HLE `Kernel_Library` serves. All the others are kernel modules that the HLE kernel covers. The game executable is therefore the only guest code.
- **Which executable:**
  - **BOOT.BIN:** 2,112,493 bytes, a **plain ELF PRX** (1.57 M non-zero bytes), not the zero-filled placeholder many discs carry.
  - **EBOOT.BIN:** 2,112,832 bytes, `~PSP` tag **`0xC0CB167C`**. That is an old-style game envelope with a 144-byte key that FirmwareCrypto does not have.
  - `startGame` uses BOOT.BIN when it is a non-empty ELF. Otherwise it uses EBOOT.BIN, and if that is encrypted with an unknown tag it stops cleanly with "Game executable is encrypted (tag 0x…); decryption key not available". No game keys were added.
- **Loading:**
  - The executable goes into user partition 2 at `0x08804000`, with the first 16 KiB reserved as on hardware.
  - Its `module_start` gets the path as its argument. For DBZ the module is `DBZP_0035`.
  - The start thread creates `user_main` (priority 0x20, 256 KiB).
- **Error fixed (loader): section relocations.** The ELF has only two `PT_LOAD` headers and no packed `0x700000a1` relocation segment, so every pointer stayed unrelocated. The result was "Invalid PRX symbol table range".
  - Older PRXs keep relocations in **section headers of type `0x700000a0`**. These are Elf32_Rel pairs where `r_info` = type | offset-segment << 8 | address-segment << 16.
  - `PrxLoader.relocateSections` applies R_MIPS_32, 26, HI16 and LO16. HI16 is paired with the next LO16, using the signed low half as the addend.
- **Error fixed (threads): k0.** crt0 executes `sw s5, 4(k0)`. PXP2 set k0 to the thread uid (`0x00100015`), so the store faulted at `0x00100019`.
  - Each thread now has a 256-byte k0 block: k0 points at it, +0xc0 holds the thread id, +0xc8 the stack base and +0xf8/+0xfc hold −1.
  - The block is allocated just above the thread's stack. The stack layout the VSH was tuned against (sp = top − 64) is unchanged.
- **Game-disc copies of Sony modules** (`USRDIR/MODULE/*.prx`):
  - They carry an outer **`~SCE` header** (size at +4, 0x40) in front of `~PSP`. The loader now strips it.
  - They are sealed with tags **`0x4467415D`** (sc_sascore, audiocodec, videocodec, mpegbase) and **`0x3ACE4DCE`** (libatrac3plus, mpeg, libfont). Both are legacy 144-byte keys that PXP2 does not have.
  - PXP2 handles each module in one of three ways, and logs which (`HleModules.loadModule`):

    | Disc module | Handling |
    |---|---|
    | `sc_sascore.prx` | HLE placeholder; `sceSasCore` in `HleGame`. The 6.61 `kd/sc_sascore.prx` cannot substitute because its module_start allocates Media Engine memory (`sceMeMemory_driver:c4eda9f4`, 0xb634 bytes). |
    | `audiocodec.prx`, `videocodec.prx`, `mpegbase.prx` | HLE placeholders; `HleMedia` and `HleVideo` serve the libraries, as for the VSH. |
    | `libatrac3plus.prx`, `mpeg.prx` | Loaded from the same file name in `flash0:/kd/`: the newer firmware copy of the same library. |
    | `libfont.prx` | Loaded from `flash0:/vsh/module/libfont_hv.prx`. It exports `sceLibFont_HV` with exactly the NIDs games import from `sceLibFont`, so the linker aliases that library (`EXPORT_ALIASES`). Fonts come from `flash0:/font`. |
- **User imports added** (`HleGame`, `HleNet`):
  - **LoadExecForUser:** `4ac57943` sceKernelRegisterExitCallback is recorded (and logged) for the HOME menu. `05572a5f`/`2ac9954b` sceKernelExitGame(WithStatus) reboot to the VSH (`gameLib.exitRequested`). See "HOME button and HOME menu".
  - **StdioForUser:** `172d316e`/`a6bab2e9`/`f78ba90a` stdin/out/err are fds 0/1/2.
  - **sceUtility:**
    - Load/UnloadNet/Av/UsbModule succeed, because their libraries are HLE.
    - `a5da2406`/`34b78343` GetSystemParamInt/String read the registry: ids 1 nickname … 9 button assign, 10 parental level; an invalid id returns 0x80110103.
    - **Savedata** (`50c4cd57` InitStart, `9790b33c` ShutdownStart, `d4b95ffb` Update, `8874dbe0` GetStatus) has no dialog UI. The status runs 1 → 2 → 3 through GetStatus, then 4 → 0 after shutdown. Supported modes:
      - 0/2/4 (auto/list) load, with 0x80110307 when there is no data;
      - 1/3/5 (auto/list) save, which writes the file, the icons and a PSF 1.1 `PARAM.SFO` to `ms0:/PSP/SAVEDATA/<game><save>/`;
      - 11 LIST, with `*`/`?` wildcards and 72-byte id entries.

      Data is stored as the game hands it over; the savedata encryption layer is not applied.
  - **sceSasCore:** Init (grain and voice checks), Core/CoreWithMix, voice setters, key on/off, end/pause flags, envelope heights and reverb setters. DBZ inits grain 704, 32 voices, 44.1 kHz. **Mixed in software by `SasCore`** (see §5 "sceSasCore").
  - **sceNetAdhoc / sceNetAdhocctl / sceNetAdhocMatching:** Init and Term. adhocctl Add/DelHandler, GetState (0 = disconnected) and Disconnect. No peers are ever found.
- **Error fixed (GE): backface culling was inverted.** Battle characters and the sky rendered as black silhouettes, because the black outline hull (material colour 000000, textures off) was drawn over the front faces.
  - With screen y pointing down, a positive signed area is clockwise, and CULL=1 keeps counter-clockwise faces. The test was flipped.
  - The XMB never enables culling (its patches use PFACE), and its frames are pixel-identical before and after the change.
- **Also added:** GE texture-map modes 1 (texture-matrix projection) and 2 (shade/environment mapping: u, v = (1 + N·L)/2 for the two lights in `0xc1`). DBZ does not use them.
- **Desktop tools:**
  - `FrameDump -Dgame=disc0:/PSP_GAME/SYSDIR/BOOT.BIN` (with `-Dumd`) boots the disc directly, without the XMB.
  - `-Ddis=addr:count` disassembles guest memory at the stop.
- **Open:**
  - One ATRAC3plus frame is reported invalid. (SAS mixing is done: see §5.)
  - Savedata modes beyond 0–5 and 11, and the dialog UIs (MsgDialog, OSK).
  - The `0xC0CB167C` / `0x4467415D` / `0x3ACE4DCE` keys for titles whose BOOT.BIN is empty.

## 5. Audio

- **Mixing:** every output (sceAudio channels, Output2, SRC, Vaudio) is mixed onto a 44.1 kHz stereo timeline in emulated time (`AudioMixer`). Android plays it through AudioTrack.
- **MP3:** `sceAudiocodecDecode` codec `0x1002` takes the frame size from the MPEG header. PCM comes from Android MediaCodec. **Confirmed on the Pixel 9 Pro Fold: music plays and the visualisers work.**
- **ATRAC3plus (codec `0x1000`): decoded in pure Java (`Atrac3plus`), so it works on both desktop and Android. The boot sound plays.**
  - **Wiring:** `sceAudiocodecDecode` reads the frame at ctx+24 and writes 2048 stereo 16-bit samples to ctx+32.
    - There is one decoder per ctx address, created on the first decode and discarded by `sceAudiocodecInit`.
    - The channel count comes from the config bytes ctx+40/41: bits 12..10 of the 16-bit value, the same layout as the RIFF/OMA codec info. Frame bytes are `(config & 0x3ff) * 8 + 8`.
    - Mono is duplicated to both output channels. An invalid frame gives silence, logged once.
  - **Boot sound stream:** one context, `28 45` = 44.1 kHz stereo, 560-byte frames, 88 frames (4.09 s). No ATRAC3 frames are seen during boot.
  - **Pipeline:** the bitstream is parsed into:
    - word lengths, scale factors and code-table indexes, each with several coding modes including VQ shapes and deltas to the other channel;
    - the Huffman-coded spectrum (112 code tables);
    - stereo swap/negate flags, window shapes, gain-control points and tonal (sine-wave) components;
    - noise.

    Reconstruction then runs:
    1. inverse quantisation with power compensation (noise fill);
    2. per subband, a 128→256 IMDCT: a DCT-IV through a 64-point complex FFT, with sine or steep windows, and odd subbands spectrally reversed;
    3. gain compensation and overlap-add;
    4. tone synthesis with Hann cross-fades;
    5. the 16-band inverse PQF (384-tap prototype).
  - **Tables:** the format constants are in `Atrac3plusTables` (data only, from FFmpeg; LGPL-2.1+ credit in CRYPTO-NOTICES.md). Each byte table is an octal-escaped string, which keeps `<clinit>` small. Spectrum code-length counts must be read **unsigned**: one count is 172, which wraps in FFmpeg's `int8_t` declaration.
  - **Verification** (`core/tests/Atrac3plusTest.java`): the boot-sound frames were dumped with `-Dpxp2.atracDump=dir` (HleMedia writes `<codec>-<ctx>.raw` plus the first 64 ctx bytes). They were wrapped in a 96-byte EA3 header (`EA3\1\0\x60\xff\xff`, codec id 1 at byte 32, params `00 28 45` at bytes 33..35) and decoded by ffmpeg (`-f oma -i boot.oma -f f32le`).
    - Against ffmpeg: **SNR 134.5 dB, max error 2.1e-7 (0.01 LSB)** over all 88 frames, with no bad frames.
    - The fast DCT-IV matches the direct formula within 1.5e-6.
    - Speed: **0.07 ms per frame** on desktop (46 ms of audio).
  - **End to end** (`core/tests/BootAudio.java`, which boots the VSH and writes the mixer output to WAV): the opening's mixed audio matches the reference with correlation 0.998 and 36 dB SNR per 0.19 s window.
    - libatrac starts playback 2416 samples into the decoded stream, which is the stream's leading delay.
    - The residual comes from the mixer: volume 0.985 and 1-sample slips from emulated-time rounding at buffer boundaries. It is not from the decoder.
- **ATRAC3 (codec `0x1001`):** not decoded yet; it still produces silence of the approximate length. It is a different codec (QMF + MDCT with its own tables), and the boot sound does not use it.

### sceSasCore: game sound-effect mixer (HLE, `SasCore`)

- **Why HLE:** games load the firmware's `sc_sascore`, which mixes on the Media Engine. PXP2 serves `sceSasCore` itself (`HleGame.registerSas` → `SasCore.java`): one mixer per core address, 32 voices, one grain rendered per `__sceSasCore` call. The game's own audio thread (DBZ: `am_sndp_server`) then sends the grain to a sceAudio channel (DBZ: channel 4, 0x2c0 samples).
- **Voice sources:**
  - **VAG ADPCM** (`__sceSasSetVoice` 99944089: core, voice, addr, bytes, loop). 16-byte blocks: byte 0 = predictor << 4 | shift, byte 1 = flags, 28 nibbles (low first). s = (nibble << 12 >> shift) + ((s1·f0 + s2·f1 + 32) >> 6), with (f0, f1) = (0,0) (60,0) (115,−52) (98,−55) (122,−60); shift > 12 is treated as 9.
  - **Loop rules:** flag bit 2 marks the loop start. Bit 0 ends the sample, unless bit 1 is also set, which jumps back to the loop start. Flags 7 is an end marker (not decoded). The SetVoice loop switch applies only when data runs past `size` without an end flag. **Evidence:** DBZ passes loop 1 for every one-shot effect, and its data ends `…, flags 1, flags 7` with no key-off. Honouring the switch at flag 1 made them repeat forever.
  - **PCM** (`e1cd9561`: core, voice, addr, samples, loopPos): 16-bit mono; loopPos in [0, samples) loops there, otherwise one-shot. DBZ does not use it.
  - **Noise** (`b7660a23`: freq 0..63): SPU-style 16-bit LFSR clocked by freq; ignores pitch.
- **Pitch** `ad84d37f` (1..0x4000, 0x1000 = 1.0 at 44.1 kHz): 12-bit fractional position with linear interpolation. DBZ uses 0x5ce (16 kHz data) and 0x3fd (11 kHz).
- **Volumes** `440ca7d8` (core, voice, l, r, effectL, effectR), each −0x1000..0x1000 (1.0 = 0x1000). Voice sample · (envelope >> 15) >> 15, then · vol >> 12, summed in int and clamped to 16 bits. DBZ: 0x780/0xaa0 dry, 0xc80 effect send.
- **Envelope** (height 0..0x40000000; phases attack → decay → sustain → release):
  - `019b25eb` SetADSR (flags 1 A | 2 D | 4 S | 8 R, four rates), `9ec3676a` SetADSRmode (curves 0 linear up, 1 linear down, 2 linear "bent" (¼ speed above ¾), 3 exponential down, 4 exponential up, 5 direct), `5f9529f6` SetSL.
  - Rate units: linear curves move by `rate` per sample; exponential decrease removes height·rate/2³⁰ per sample.
  - `cbcd4f79` SetSimpleADSR takes the SPU ADSR1/ADSR2 bitfields (attack mode/rate, decay rate, sustain level (n+1)·2²⁶, sustain mode/direction/rate, release mode/rate). Each 7-bit SPU rate r is converted to the same units: the 15-bit level moves by `(7 or 8 − (r&3)) << max(0, 11 − r/4)` every `2^max(0, r/4 − 11)` samples, so rate = that step · 2¹⁵ / cycles. Attack/sustain "exponential" increase = bent curve, decay = exponential decrease, 0x7f = never. **Check:** attack rate 0x40 reaches the peak in 149,797 samples against the SPU formula's 149,824.
  - DBZ uses SimpleADSR `800f / 9fe0`: fastest bent attack, sustain level max, sustain never moves, fastest exponential release.
- **Key on/off and queries:**
  - KeyOn `76f01aca` restarts the decoder and the envelope. KeyOff `a0cf2fa4` enters release. The voice ends (height 0) when the release reaches 0, or when a one-shot source plays out (after interpolating its last sample down to 0).
  - GetEndFlag `68a46b95` = bit per voice not playing. GetEnvelopeHeight `74ae582a` / GetAllEnvelopeHeights `07f58c24` return the live height (DBZ polls it ~24 times per grain). SetPause `787d04d5` (voice bits, on/off) freezes voices; GetPauseFlag `2c8e6ab3`.
- **Output:** `__sceSasCore` a3589d81 (core, out) writes `grain` stereo 16-bit frames (mode 1: 4 channels, dry L/R then wet L/R). `__sceSasCoreWithMix` 50a14dfc (core, inOut, lvol, rvol) adds the grain to the existing samples scaled by lvol/rvol >> 12. Which side the volumes scale is not verified; DBZ does not call it.
- **Reverb (approximate, original):** `33d4ab37` RevType (0 off … 9), `267a6dd2` RevParam (delay, feedback) for the echo/delay types, `d5a229c9` RevEVOL, `f983b186` RevVON (dry, wet switches).
  - It is a Schroeder reverb: 4 damped combs + 2 allpasses per channel, sized per type; types 6..8 use one feedback delay. Only the effect-send bus feeds it.
  - The state is float. The first integer version had feedback-loop rounding that left a −3/−4 DC limit cycle after every sound.
  - DBZ: type 2 (studio small), EVOL 0x1000/0x1000, VON dry 1, wet 1.
- **Verification:**
  - VAGs keyed on by DBZ were read from guest memory at KeyOn and decoded through `SasCore` (pitch 1.0, direct envelope). The result is **bit-exact** against an independent integer implementation of the formula (21,084 + 1,652 + 11,340 samples, 0 mismatches), and ~70 dB SNR against a floating-point version of the formula.
  - Synthetic checks: a flag-3 loop keeps playing; the key-off release ends the voice; noise is non-silent; a PCM one-shot at pitch 2.0 gives 50 samples from 100 and ends; the reverb tail decays to exact 0.
  - **Cost:** 0 bytes allocated over 2,000 grains. 32 looping voices + reverb take ~140 µs per 704-sample grain on the desktop (<1 % of real time).
- **In game (FrameDump, `-Dwav`):** the title → New Game → Name Entry → Main Menu → Arcade → Goku vs Trunks run keys on 35 voices from 17 VAGs (menu cursor/confirm sounds, character voices, hits).
  - Menu effects measure SAS RMS ~1,850 and peak 11,130. Battle 2-second windows reach RMS 4,900 and peak 27,800.
  - Silence between sounds is exactly 0. There is no clipping in the SAS output or in the final mix (ADX music underneath, RMS ~2,550).
  - The negative window means in battle (down to −318) come from the source data: several impact VAGs decode with a mean of −290 to −571.
- **Title music is not SAS:** it is CRI ADX on its own sceAudio channel (with ATRAC3plus for the intro movie), which already played before this work.

---

### Volume buttons (impose)

- **Two ID schemes:** `vshImposeGetParam` → `sceImpose_driver:dc3becff` accepts both the `0x80000001..` ids and single-bit ids. The bit ids are used by `impose_plugin` for the volume overlay:
  - 0x1 volume, which reads 0 while muted;
  - 0x2 brightness, 0x4 equaliser, 0x8 mute, 0x10 AVLS, 0x20/0x40 time/date format, 0x80 language;
  - 0x100, 0x200, 0x400, 0x2000 and 0x20000000, all accepted.
  
  **Error fixed:** only `0x8000000x` was accepted, so bit 0x1 returned 0x80000107. That is why the status bar always showed a muted speaker.
- **Volume keys:** the impose driver is HLE, so `vshImposeTakeChanges` (polled every frame) does the key handling. A VOL+ press (0x100000) or VOL− press (0x200000) steps the volume between 0 and 30, clears mute and returns change bit 0x1. The XMB then draws its volume bar.
- **Android:** the volume keys map to those PSP buttons and are consumed, so Android's volume panel stays hidden. The mixer applies `gain = volume/30` (0 when muted). Key presses are held for at least 40 ms, because an instant tap (e.g. `adb input keyevent`) falls between two vblank samples.

## 6. Rendering (GE)

- The XMB wave is spline patches, with 5-way morphing and a CLUT8 texture, drawn additively (`SRC_ALPHA` + FIX `0xffffff`).
- **Error fixed:** the per-frame texture cache flush caused about 35 decodes per frame. It was replaced with content checksums.
- **Performance** (desktop, per frame): 16.4 ms → 3.08 ms, from:
  - the span rasteriser;
  - parallel row-chunk bands;
  - batched pixel pipelines;
  - a single flush per list;
  - the CPU fast paths.
  - Output is pixel-identical; `core/tests/GeFuzz.java` covers 100k random cases.
- **Error fixed: speckled fades in DBZ Shin Budokai.** The title fade-in/out and the dimmed title backdrop behind Name Entry showed as mostly black with scattered undimmed pixels, repeating every 60 px (it looked like 4 shrunk copies of the title). It was not render-to-texture: the backdrop texture (CLUT8 at `08ade9a0`) and the list were unchanged, and dumping VRAM after each primitive showed the scene correct until the last draw.
  - The fade is a through-mode, untextured black triangle strip (18 vertices, columns 60 px wide, z = 65535) blended with material alpha. **Depth test is on with GEQUAL** against the 65535 already written by the backdrop and logo sprites.
  - The triangle rasteriser interpolated depth as `az*w0 + bz*w1 + cz*w2` and truncated it. The barycentric weights sum to 1 only within float rounding, so at scattered pixels a constant 65535 came out as 65534 and failed GEQUAL. Those pixels were left undimmed, in the same pattern in every strip column.
  - Fix (`GeRenderer.Raster.triangle`): when all three vertex depths are equal, every pixel gets exactly that depth. Varying depths are interpolated as before. XMB frames 700/900 are pixel-identical, and GeFuzz (random depths) reports 20k identical cases.
- **True widescreen:** anamorphic rendering. Screen x is compressed by the display aspect factor (a wider field of view for 3D and correct shapes for 2D). Background-like, full-width batches are exempt.

### Internal resolution, texture upscaling and anti-aliasing (2026-09-28)

Settings → Display now has **Resolution: 1× / 2× / … / Max**, **Texture upscaling: Off / Bilinear / Enhanced** and **Anti-aliasing: Off / SSAA 2× / Edge smoothing**. They are stored in SharedPreferences `pxp2` (`resolution`, 0 = Max; `texture_filter`; `antialias`) and applied by `XmbActivity`. The defaults are 1× / Off / Off, which take exactly the old code paths.

**Internal resolution (`GeRenderer.setScale`).** At scale k the GE draws into *shadow* buffers, not into VRAM:
- Every framebuffer and depth buffer the GE draws into gets a shadow. It has the same pixel format and layout, with k× the columns and k× the rows. A state that draws into a shadow gets k× screen-space vertex positions (after the viewport, true-widescreen compression and the through-mode coordinates) and a k× scissor, `(x0·k, y0·k)–((x1+1)·k−1, (y1+1)·k−1)`. The unchanged rasteriser then fills the shadow.
- Sprite edges fall on multiples of k, so 2D quads and through-mode tiles line up exactly with no seams. A point covers k×k pixels. Lines are still not rasterised at any scale; when they are, their width should be k. Clear-mode sprites are scaled like any other sprite.
- Row bands are 16·k rows, so there are still 32 bands for the worker threads. Spans hold 2048·k pixels.
- **VRAM stays the guest's 480×272 picture.** After every flush, the rectangle drawn at high resolution is written back to VRAM (the *resolve*). Colour is box-filtered; for 16-bit formats each bit field is averaged. Depth is point-sampled. The resolve runs on the GE worker threads.
- So the CPU, `sceDisplay`, block transfers, CLUT loads and 1× texture reads always see a coherent picture. Render-to-texture hazard tracking still uses guest (VRAM) addresses.
- **Writes behind the GE's back.** `Memory` now records the VRAM byte range written through the bus (CPU stores, `write`/`fill`/`copy`: DMA, video, block transfers). Before a shadow is drawn, sampled or shown, pixels whose VRAM bytes differ from the last resolved copy are copied up (k×k replication).
  - Celeste (drawn by the CPU through SDL, 0 triangles) shows correctly at 2×.
  - If a colour buffer overlaps its own depth buffer, that state falls back to 1× drawing straight into VRAM. The batch is flushed so the two kinds of drawing stay in submission order.
- **Render-to-texture.** A texture whose address, buffer width and format (565/5551/4444/8888, unswizzled) match a shadow samples the shadow's pixels, at k× the texel density. Texels outside the shadowed rectangle come from 1× VRAM.
  - Anything else falls back to the coherent 1× VRAM: CLUT views of a framebuffer, other strides, swizzled textures, and a texture that is the current render target.
  - A state that samples a shadow forces a flush before anything later draws into that shadow, because textures are snapshots at 1×.
- **Block transfers.** Transfers between two shadowed framebuffers also copy the high-resolution pixels.
- **Memory limits.**
  - Shadows start 272 rows high. They grow only when drawing goes lower, so double buffers at `0x0` and `0x88000` never overlap even with a 512-row scissor.
  - Shadows over the memory budget (a third of the heap) are evicted, least recently used first.
  - `XmbActivity` caps the render scale at 8 (the scale including SSAA).
- **Presentation.** `GeRenderer.frame()` returns the displayed buffer at render resolution: 480k×272k ARGB, or 480×272 when that buffer has no shadow. `XmbActivity` puts it into a bitmap of that size and draws it with the chosen picture filter. There is no 1× round trip.
  - The sharp-upscale filter prescales only by the remaining integer factor. At Max there is no prescale.
  - True widescreen works unchanged. Compression happens in 1× coordinates before scaling, and the anamorphic frame is stretched by the presenter.
- The overlay now shows the scale. It stays on screen when the widescreen picture overhangs the edge (the fps field used to be cut off). Once a second, logcat gets a line such as `PXP2 perf 42.0 fps cpu 5.2 ge 13.8 present 2.1 ms scale 2 frame 960x544`.

**Texture upscaling (`GeRenderer.textureFilter`).**
- **Bilinear** is a 4-tap filter with the texture's wrap/clamp modes. Texel centres are at +0.5, so a 1:1 through-mode sprite still samples exact texels.
- **Enhanced** runs Scale2x (the public EPX/AdvMAME2x rule, an original implementation) on the decoded texture: twice (4×) for textures up to 128×128, once (2×) up to 256×256. It then samples the result bilinearly.
  - It uses a colour tolerance of 24 per channel, and a matched corner moves only halfway to its neighbours. With exact replacement, the XMB's already anti-aliased icons got new stair steps.
  - The upscale is kept with its entry in the existing texture cache, so it is made once per texture content.

**Anti-aliasing.**
- **SSAA 2×** renders at twice the chosen scale, and `frame()` box-averages 2×2 samples.
- **Edge smoothing** is an original, simplified pass in the spirit of FXAA's sub-pixel step. Where local luma contrast is high, the pixel is blended toward its 3×3 neighbourhood. It runs on the output frame on the CPU.

**What each option does to the XMB.**
- The icons are small CLUT textures drawn about 1:1. The wave is spline patches (5-way morph, CLUT8, additive).
- **Resolution** makes the wave's edge and every polygon or sprite edge k× finer. Text drawn from the font texture with minification ("Game" at 1× has broken glyphs) becomes clean. Icon *textures* stay at their texel resolution (nearest magnification).
- **Bilinear** removes the blockiness of magnified icons and wallpapers. **Enhanced** keeps thin icon lines (globe, update arrows) crisper than bilinear.
- **SSAA / edge smoothing** soften the remaining polygon stair steps (the wave edge, the zoomed icon outlines).

**Verification.**
- **1× is unchanged.**
  - XMB frames 500/700/900/1200 are pixel-identical to the previous build in a back-to-back run (`PngDiff`: 0 pixels).
  - DBZ frames 3750/4000/4500/4800/5200 (title, main menu, Dragon Road; `-Dvideo=ffmpeg`, START at 3100/3400, ✕ at 4300/4600) are identical.
  - Celeste frames 400–1300 and CrossCraft ST frames 300–1000 are identical.
  - `GeFuzz` reports 100 000 cases identical. CoreTest, PipelineTest, LibraryTest and PluginsTest pass.
- **Robustness.** New GeFuzz mode `-Dscale=N -Dtexfilter=M` sets the candidate's scale and filter. It checks that nothing throws and reports how many bytes differ. Scales 2 and 3 with filters 0 and 2 each ran 20 000 cases without errors.
- **Higher scales.** DBZ at 2× (title, menus) matches 1× after downsampling (mean channel difference 0.3–1.8, from edge anti-aliasing). CrossCraft at 2× renders correctly with depth.
- **FrameDump options.** `-Dscale=N` writes the 480N×272N frame. Also `-Dssaa=2`, `-Dsmooth=true`, `-Dtexfilter=1|2`, and `-Dfrom=frame`, which prints host, GE and resolve ms per frame at the last frame.
- **Evidence** (`local-test/evidence/upscale/`):
  - `xmb-{1x,2x,4x}-frame900.png`, `xmb-4x-{bilinear,enhanced}-frame900.png`, `xmb-2x-{smooth,ssaa}-frame900.png`, `xmb-lbp-theme-4x-frame900.png`;
  - the crops `compare-icons-1x-vs-4x.png`, `compare-4x-bilinear-vs-enhanced.png` and `compare-1x-2x-2xsmooth-2xssaa.png`;
  - `dbz-title-2x-frame4000.png`, `dbz-menu-2x-frame4500.png`, `crosscraft-2x-frame1000.png`, `celeste-2x-frame1000.png`;
  - device screenshots `device-edge-{1x,2x,3x}.png` and `device-edge-1x-vs-3x.png`.

**Performance.**

Desktop, XMB wave scene, frames 300–1200, 4 GE workers:

| Scale | Host ms/frame | GE ms/frame | Of which resolve |
|---|---|---|---|
| 1× | 3.9–4.9 | 1.6–1.9 | 0 |
| 2× | 8.8 | 5.4 | 0.5 |
| 4× | 20.8 | 17.8 | 1.5 |
| 4× bilinear / enhanced | 29 / 28 | 26 / 25 | |

Other desktop results:
- 1× bilinear: 5.6 / 2.5 ms.
- 2× + smoothing: 8.4 / 5.9 ms.
- 2× SSAA (4× render): 22.5 / 19.6 ms.
- CrossCraft 2×: 17.2 / 11.6 ms.
- DBZ menus 2×: 2.9 / 2.2 ms.

Razer Edge 5G (2400×1080, Max = 3×; sharp filter, true widescreen, 120 Hz), XMB wave scene (Game → PSP Update), steady-state logcat samples:

| Setting | fps | cpu / ge / present (ms) |
|---|---|---|
| 1× (before, same code path) | 48–51 | 4.7–6.7 / 4.8–7.9 / 7.7–11.4 |
| 2× | 41–43 | 5.2–7.8 / 12.7–17.8 / 1.4–2.1 |
| 3× (Max) | 24–26 | 5.3–8.6 / 27–30 / 3.6–5.3 |
| 1× Bilinear | 43 | 5 / 6.3–8.1 / 9–10 |
| 1× Enhanced | 44–45 | 5 / 6.5–11 / 9–11 |
| 2× Enhanced | 34–37 | 5.5–8.7 / 20–22 / 2–2.8 |
| 1× Edge smoothing | 46–48 | 5–7 / 5–7 / 9.2–14.6 |
| 1× SSAA 2× | 28 | 5–7 / 14–16 / 12–18 |
| 2× Edge smoothing | 34 | 5–6 / 14.3–15.3 / 7.2–9.9 |

On the DBZ disc item, whose PIC1 background is heavier, the Edge gave 42 fps at 1× (ge 8–9, present 8–12), 29–30 fps at 2× (ge 21–25) and 16–17 fps at 3× (ge 44–55).
- At 2× the presenter got *faster* (present 2 ms instead of 8–11 ms). The sharp filter no longer prescales the 480×272 frame 3× every frame, and that partly offsets the 4× GE work.
- **Playable:** 2× is usable in the XMB on the Edge (41–43 fps). 3× is not (24–26 fps).
- The Galaxy Tab S11 Ultra (SM-X930) was not connected to adb during this work (only the Edge and the Fold were listed), so it has no measurements. Its 1× GE time (3.7–4.8 ms) is about the Edge's, and its present time (12.6 ms, a 6× prescale) would drop the most. Expect about 2× to hold near 50–60 fps and 3× to be around 25–30 fps. Max there is 6× (2960×1848), which is not playable on a software rasteriser (≈36× the pixels).
- The Fold was not measured, to leave it to the other agents' tests.

**Limits.**
- Lines are not rasterised, at any scale.
- A resolve runs after every flush. Games that flush many times per frame pay for it in each flush (bounded by the drawn rectangle).
- CLUT or swizzled views of a framebuffer, and framebuffer-as-its-own-texture, sample at 1×.
- Depth is resolved by point sampling.
- Video frames that the ME/CPU writes into VRAM are shown at 1× detail, replicated.
- Edge smoothing and SSAA run on the CPU at output resolution.

---

## 7. Video

**Path reached:**
1. Video column → Memory Stick loads `msvideo_plugin` and `msvideo_main_plugin`, then `mp4msv`, `libhttp`, `libparse_*` and the network placeholders.
2. The list shows "Playlists" plus clips.
3. Playing a clip runs Sony's `kd/mpeg_vsh.prx` (guest code) on top of PXP2's HLE `sceVideocodec` (Media Engine H.264) and `sceMpegbase` (DMA copies and colour-space conversion); see "Media Engine video" below. **The test clip plays at 29.97 fps in the XMB player** (desktop: FrameDump `-Dvideo=ffmpeg`).

**Fixes on the way:**
- **Memory:**
  - `module_start` stacks are now freed;
  - kernel modules go into the kernel partition;
  - `mlnbridge` is served from HLE.
- **Bridge calls:**
  - `c40f345f` (MG video reset, returns 0);
  - `474e9245` (MG video Memory Stick devctl pass-through);
  - `96c67d2c` / `74c48d87` (UtilsForKernel get/set).
- **Vaudio variants (from `kd/vaudio.prx`):** `e8e78dc8` (samples, rate, channels) is a reservation through the same routine as `sceVaudioChReserve` (`08800ac8`: samples 256–2048 else `0x80000104`, channels 2 or 4 else `0x80000108`). `504e4745` (n) only stores a halfword of the reserved channel (1024–4096, else `0x800001fe`; `0x80000001` when none is reserved) that the output routine hands to the ME.
  - **Error fixed:** PXP2 treated `504e4745` as the reservation. The player calls it before *every* output with (2048, 0, 0), so each call reset the channel's pacing and rate: audio ran unpaced and the player skipped ~450 video frames to catch up.
- **`sceDisplayGetMode`:** null pointers are skipped, like `display.prx` (`08801784`); the player's AudioRenderer passes null width/height.

**Test media:**
- PC: a generated `PXP2 Test Pattern.MP4` (H.264 Main 480×272 + AAC, ffmpeg `-f psp`).
- Phone: the Grinch (1966) converted to the same format (166 MB) and copied to `PXP/VIDEO`.

### Media Engine video: `sceVideocodec` / `sceMpegbase` (HLE, `HleVideo`)

`kd/avcodec.prx` exports both libraries. Each `sceVideocodec` export validates the context and forwards it to `sceMeVideo_driver` (the ME itself is not emulated). The layout below comes from avcodec's argument checks and cache write-back/invalidate ranges (inputs are written back, outputs invalidated after the ME returns) and from how `mpeg_vsh` fills and reads the fields. Addresses: avcodec loaded at `08800000`, mpeg_vsh at `09c6ec00`.

**Context (96 bytes; `ctx+16` block is 40 bytes for H.264, 256 for types 1/2):**

| Offset | Dir | Meaning (evidence) |
|---|---|---|
| +0 | out | `0x05100601`, written by Open (`08800d60`) |
| +16 | in | info block. Outputs read by mpeg_vsh: +8 width, +12 height (→ CSC block `h/16`, `w/16` at `09c7000c`), +32 pictures returned (`09c6ffb8`); +16..+28 overwritten by mpeg_vsh with the crop block |
| +20/+24 | in/out | ME EDRAM: Open reports the size at +24 (mpeg_vsh reads it right after Open, `09c776c0`); GetEDRAM allocates +24 (+63) bytes of ME memory, raw at +92, 64-aligned at +20 (`08800c74`) |
| +28/+32 | in | caller memory (mpeg_vsh passes its buffer, default `0x09000000`), written back by Init |
| +36/+40 | in | access unit address/size (`09c6ff2c`); Annex-B with SPS/PPS in band |
| +44 | out | `ctx+60` picture descriptors of 44 bytes; words 0–7 are the plane-part pointers |
| +48 | out | 100-byte block (64 bytes invalidated) |
| +52 | in | bit 31: also fill SEI (+80); bit 30: also fill crop (+84) (set by mpeg_vsh in player modes 4–6) |
| +56 | out | optional `ctx+60` × 328-byte per-picture blocks; mpeg_vsh tests byte 0 bit 1 and then reads a pic_struct byte at +32 |
| +60 | in | number of descriptors (mpeg_vsh: 4) |
| +64..+76 | in | stream limits for ME command 1 (`745a7b7a`): max width, max height, 2/3, 0/1 by player mode |
| +80/+84/+88 | in | SEI block (164 bytes), crop block (16 bytes), word for ME command 7 (`893b32b1`) |

**Exports:** `c01ec829` Open, `2d31f5b1` GetEDRAM, `4f160bf4` ReleaseEDRAM, `17099f0a` Init, `dba273fa` Decode (ME `8768915d`), `a2f0564e` Stop (the ME returns the pictures still held for reordering: mpeg_vsh converts `info+32` pictures after it, `09c70124`), `307e6e1c` Delete, `26927d19` ME command 3, `745a7b7a` command 1, `627b7d42` command 5 (SEI into +80), `17cf7d2c` command 6 (crop into +84), `893b32b1` command 7. Not implemented (stop with a report): `2f385e7f` (types 1/2 only), `d95c24d5` (a 56-byte ME colour-conversion request, used only by other mpeg_vsh paths), codec types other than 0.

**Picture parts** (`sceMpegBaseYCrCbCopy`, `08801f58`, sizes in 16-byte units): luma = {even macroblock columns, odd columns} × {two row halves}, i.e. `((w+16)/32` or `w/32) × 16 × h/2` bytes; chroma parts are half those sizes. The pixel arrangement inside the parts is private to the ME and the CSC unit (guest code only copies them), so PXP2 stores a plain I420 picture and the eight pointers are consecutive slices of it.

**`sceMpegbase`:**
- `492b5e4b` CscInit(width): default destination width, default matrix, mode 0. `ac9e717e`(width, matrix): the same with the caller's 22-byte matrix.
- `0530be4e`(mode): CSC pixel mode. **CSC numbering is not the GE's:** mpeg_vsh maps GE 565/5551/4444/8888 through its table at `09c79450` to 1/2/3/0.
- `91929a21` CscAvc(dest, dest2, width, yuv): yuv +0 `h/16`, +4 `w/16`, +16..+44 the parts.
- Default matrix (`08804a40`): `{0x95, 0, 0xcc, 0x95, 0x3ce, 0x398, 0x95, 0x102, 0, 0x10, 1}`. These are rows R, G, B of (Y, Cb, Cr) as 10-bit signed values in 1/128 units, then the luma offset 16, i.e. BT.601 video range. The last halfword is not understood (unused).
- `7ac0321a` YCrCbCopy(dst, src, flags): flags bit 0 copies parts +16/+20/+32/+36, bit 1 the others.
- `bea18f91` PESpacketCopy(list): entries of 16 bytes `{src, dst, more, size & 0xfff}`, consecutive, at most 100; a zero size fails with `0x80000104`. In `kd/mpeg_vsh.prx` (animated icon preview for games with `ICON1.PMF`), `dst` is in the Media Engine's 2 MiB eDRAM (`0x0004a000`). `Memory` maps ME eDRAM (`0x00000001`..`0x00200000`) so DMA transfers succeed without `0x80000023` `BAD_ADDRESS`.

**Implementation:**
- `HleVideo` implements all of the above. A host `VideoDecoder` gets each access unit and returns I420 pictures; each Decode returns at most one picture (the ME's steady state), and Stop drains the rest.
- **Android:** `AndroidVideo` (MediaCodec `video/avc`, ByteBuffer mode, YUV_420_888 → I420).
- **Desktop:** `core/tests/FfmpegVideoDecoder` (ffmpeg subprocess; FrameDump `-Dvideo=ffmpeg`).
- **Without a host decoder,** every access unit that holds a slice yields a black picture of the SPS size, so the timing is unchanged.
- SEI parsing is not implemented: the SEI block and the per-picture blocks report "none" (flag bits clear), so the player uses its default timing.
- The crop block is filled from the SPS in the order left, right, top, bottom. **This order is an assumption;** it is zero for the test clip.
- The CSC writes alpha 0xff in 8888 mode (the hardware's alpha is unknown).

**Evidence:**
- `local-test/frames-video-gepatch/frame01900.png`: frame 316, timecode 10.544 s, 10.6 s after ✕.
- `frame02800.png`: the end of the clip.
- Playback runs 1 picture per 2 vblanks.

**Open (video):**
- **GE texture cache (not fixed, `GeRenderer` is off-limits):** the player draws the converted picture from VRAM `0x04110000` as a 512×512 8888 Bézier-patch texture. That span runs past the end of VRAM, so `GeRenderer.checksum` returns the constant −1, the cached texture is never invalidated, and the screen keeps showing the **first** video frame (see `local-test/frames-video/frame01900.png`). Clamping the checksummed length to mapped memory fixes it; the evidence frames above were made with that change applied to a test copy only.
- **AAC:** `sceAudiocodecDecode` codec `0x1003` gets only the address of a raw AAC frame (no length; the ME finds the frame end). PXP2 has no AAC bitstream parser, so it outputs silence of the correct length (`sceAudiocodecGetOutputBytes`, `59176a0f`: 0x1000 bytes, or 0x2000 when ctx byte 45 is set).
- The first picture appears after the host decoder's latency (MediaCodec: device dependent; ffmpeg ≈6 access units); up to that many trailing pictures can be lost at Stop, which returns at most `ctx+60`.
- `27acc20b` is probably `sceVaudioChReserveBuffering`; PXP2 currently treats it as a setting.

## 7b. Android front end (touch, layout, build)

- **Touch layer:** swipes on the picture act as the D-pad and are inverted to follow XMB scroll direction (finger left → RIGHT, finger up → DOWN). Tap → ✕, long-press → △, two-finger tap → ○.
- **Icon taps:** a tap on an XMB category icon (row y≈30–112 in PSP coordinates, 76 px spacing from the selected column at x≈108) or on a list item (y≈135 + 65·n) is turned into the matching D-pad presses plus ✕. This was verified on the Fold: tapping the film icon moved to the Video column.
- **Unfolded Fold (2076×2152, near-square):** the controls covered the picture. Screens taller than 3:4 now draw the picture at the top, full width, and lay the pad out underneath it (`XmbActivity.tall`). This was verified at 60 fps.
- **Crash fixes seen on the phone:**
  - `06bf847b` (power flag);
  - `sceUtility_private` getters `5dcbd3c0`, `10d1b3ff`, `5809b44a` and `1e82fb49` now return 0.
- **Build error fixed:** `build.ps1` ran with `$ErrorActionPreference='Stop'`, so a javac deprecation note on stderr aborted the script *after* Gradle succeeded. The APK was never copied to `dist/`, and a stale build was installed. Gradle now runs under `Continue`.
- **Speed on the Fold:** 59–60 fps (cpu ≈4 ms, ge ≈11 ms, present 2–4 ms).

### App UI follows the XMB theme; per-game 3D UMD

- **XMB theme** (`XmbTheme.java`): the installer, catalog, homebrew and server views take their accent, status/navigation bar and gradient from the XMB colour index (`LauncherIconManager`, read from the emulated registry). Their backdrop is a blurred snapshot of the XMB saved when the emulator is left (`files/xmb_wallpaper.png`), so Classic, custom PTF and CTF wallpapers carry over. The UI rebuilds on resume when the theme signature changes.
- **3D UMD** (`Umd3DView`):
  - **Front-cover bug fixed:** `umd_caddy_front.png` has an opaque disc painted in it (centre ≈ (251.5, 252), r ≈ 216 of 512), which covered the spinning disc layer. The game's label is now masked into that painted disc and spins about its centre.
  - **Per-game label:** built from the game's own PIC1/PIC0 (ISO `PSP_GAME` or `UMD_VIDEO`, or EBOOT.PBP) plus ICON0 and title.
  - **Removed:** the network screenshot lookup that replaced the art.
  - **Scaling bug fixed:** the synthesized disc used to be masked twice, which shrank it.
  - **Catalog:** the top "active drive" card is gone.

### "Exit to PXP2" XMB entry and homebrew launch

- **The entry:** `ExitApp` writes `ms0:/PSP/GAME/PXP2_EXIT/EBOOT.PBP` at every start. Its contents are:
  - PARAM.SFO: CATEGORY "MG", DISC_ID, DISC_VERSION, BOOTABLE, REGION 0x8000, TITLE "Exit to PXP2";
  - ICON0: the app icon on the XMB colour, generated by the host (a generated power icon is the fallback);
  - DATA.PSP: a 512-byte `~PSP` stub.
- **vshmain's launch checks** (from `089f42c0`):
  1. It reads the 0x28-byte PBP header and the PARAM.SFO (size ≤ 5120), and parses the keys DISC_ID, REGION, BOOTABLE and CATEGORY. "MG" is accepted.
  2. It reads **512 bytes** of DATA.PSP, which must start with `~PSP` or `~SCE`.
  3. Otherwise it shows "This game cannot be started. The data is corrupted."
- **Launch call:** `21d4d038` = `LoadExecForKernel:d940c83c`, sceKernelLoadExecVSHMs2(path "ms0:/PSP/GAME/<dir>/EBOOT.PBP", param).
  - The exit app's path sets `HleVsh.exitToHostRequested`, and XmbActivity finishes, back to the PXP2 UI.
  - Other homebrew goes through the same game-boot request as UMD discs. `startGame` takes the executable from the PBP's DATA.PSP (a plain ELF, or a `~PSP` module that FirmwareCrypto can open).
  - **Static ELFs (fixed):** most homebrew DATA.PSP files are **static ELFs** (`e_type` 2, ET_EXEC, linked at `0x08900000`). They now load at their link addresses: `HleModules.load` reserves the range (`SysMem.ADDR`) and `PrxLoader` finds sceModuleInfo through the `.rodata.sceModuleInfo` section, with no relocation. See "Memory Stick homebrew" below for what the hub titles reach.

### Memory Stick homebrew (hub titles)

Tested with `FrameDump -Dgame=ms0:/PSP/GAME/<dir>/EBOOT.PBP` on a scratch Memory Stick. The zips were unpacked there, never into real storage. New FrameDump option: `-Dstdout=true` prints what the guest wrote to fds 1/2.

| Title (zip) | Reaches | Blocker / notes |
|---|---|---|
| 2048 v.1.0 (the zip holds **1808**, a LuaPlayer strategy game) | Title screen (frame 300), then the map of Spain with units (gameplay, frames 450+ after ✕/START) | — |
| Celeste v.0.1 (SDL2) | Title (frame 400), then level 100 M, playable (frames 600–1300) | — |
| AntiTetris (JGE) | Gameplay: falling pieces, score, the "Hit START" pause screen | — |
| BlockOut II PSP (SDL) | Title, main menu, level select and the game screen | The 3D pit's wireframe grid does not show (GE line drawing, not investigated). `./images/font.png` is also missing from the zip itself. |
| Cowboy Coder John | Gameplay (the scrolling source listing and money counter) | — |
| CrossCraft SurvivalTest ST3 | Title menu, then in the world (frames 500–1000) | Stands in for CrossCraft Classic, whose zip is a PS Vita `.vpk`. |
| Cannonball PSP | Runs, black screen | Needs the OutRun arcade ROMs in `roms/`, which the package does not include ("cannot open rom: …" on stdout). |
| CMFileManager PSP 4.12 | Stops with the kernel-mode message | Loads its own kernel drivers (`audio_driver.prx`, `display_driver.prx`, `fs_driver.prx`) and firmware kernel modules through the CFW's KUBridge, then calls their exports. |
| 3D Tic Tac Toe (LuaPlayer) | Stops with the kernel-mode message | A 1.50-era kernel executable (sceModuleInfo attribute 0x1000). |
| Buggy Mess | Stops with a clear message | A PSN-format EBOOT: DATA.PSAR is an `NPUMDIMG` image with PSN encryption. |
| Lamecraft, PSPMania, Silveredge, SyobonPSP, TempGBA4PSP | Stop with a clear message | DATA.PSP is `~PSP`-signed homebrew, tag **`0xADF305F0`** (NPDRM user envelope, decrypt mode 13). `startGame` already passes a `~PSP` DATA.PSP to FirmwareCrypto, but there is no key for this tag. |
| Blockamok Remix, CrossCraft Classic | Not PSP | PS Vita builds. |

- **Error fixed (kernel): an interrupt raised inside a syscall replayed the syscall.** 1808 called `sceGeListEnQueue` thousands of times within microseconds, getting ids 0, 1, 2 … until the JVM ran out of memory.
  - pspgu's sceGuInit enqueues its init list with a GE callback id. The list runs synchronously and ends with FINISH, so the finish callback was started with `Kernel.interrupt` **while the syscall was still executing**.
  - At that point the CPU is mid-step, and its PC is still the `syscall` in the stub's delay slot. The interrupt saved that PC as its return point. The rest of the step then advanced the handler's PC, skipping its first instruction. When the handler returned, the stub's `syscall` ran again: another enqueue, another finish callback, and so on.
  - Fix: when `Kernel.interrupt` is called during a syscall, it now posts the start, so the handler begins after the syscall instruction has completed. That is the same point where posted thread switches happen. Handlers raised from timer events are unchanged. XMB frames 300/700 and the DBZ boot give the same triangle and sprite counts as before.
- **GE display-list queue** (`Ge`; semantics as documented for the PSP GE driver):
  - **Slots:** there are 64 display-list slots. A list holds its slot from sceGeListEnQueue until it completes or is dequeued, and the returned id is the slot.
    - With every slot in use, enqueue returns `SCE_ERROR_OUT_OF_MEMORY` (0x80000022) and logs once.
    - Enqueueing a list address that is still queued returns `SCE_ERROR_BUSY` (0x80000021).
    - A guest enqueue loop can therefore no longer run the host out of memory.
  - **Order:** lists run in queue order (EnQueueHead puts a list first). The head runs synchronously until END, its stall address, or sceGeBreak. The next list starts only when the head completes. sceGeListUpdateStallAddr and sceGeContinue resume the queue.
  - **Sync:**
    - sceGeListSync(id, 0) and sceGeDrawSync(0) block the calling thread until that list (or the whole queue) has completed, then return 0. Because execution is synchronous, only a stalled or paused list can make them wait, and it waits until another thread moves the stall address on.
    - Mode 1 peeks: DONE 0, QUEUED 1, DRAWING 2, STALL 3, PAUSED 4. Other modes return 0x80000107.
  - **Callbacks:** signal and finish callbacks (sceGeSetCallback) run in interrupt context once the syscall that ran the list returns. Their arguments are (SIGNAL/FINISH data & 0xffff, callback argument).
  - **pspgu loops:** each of these now completes in one pass:
    - sceGuInit (enqueue + ListSync);
    - sceGuStart/sceGuFinish (enqueue with stall = list start, then UpdateStallAddr past FINISH/END);
    - sceGuSync (DrawSync/ListSync);
    - sceDisplayWaitVblankStart/SetFrameBuf in sceGuSwapBuffers.

    1808 makes about one enqueue, one DrawSync and 17 stall updates per frame.
  - **`sceGeEdramSetSize` (5baa5439):** sizes up to 2 MiB succeed. Larger ones return 0x80000104, as on the PSP-1000 that PXP2 models. CrossCraft asks for 4 MiB and carries on.
- **Loader fixes** (`PrxLoader`):
  - A PRX may have a **single PT_LOAD** with its relocations in `0x700000a0` sections (CMFileManager).
  - **R_MIPS_32 on unaligned data** is applied bytewise (packed structures). Other types must still be aligned.
  - **R_MIPS_GPREL16 (type 7)** needs no change, because gp moves with the module (AntiTetris, BlockOut II, Cowboy Coder John).
  - `PrxLoader.moduleAttributes(bytes)` reads sceModuleInfo's attributes without loading. It handles a `~PSP` header, a PRX (the first segment's `p_paddr`) and a static ELF (`.rodata.sceModuleInfo`).
- **Kernel-mode homebrew is not supported, and says so:**
  - A game executable with attribute 0x1000 stops at boot with "Kernel-mode homebrew is not supported (module attribute 1000): PXP2 runs user-mode homebrew only".
  - These loads return 0x80020149 (ILLEGAL_PERM_CALL), and the modules are listed in `HleModules.refusedKernelModules`:
    - sceKernelLoadModule of a kernel module from outside flash0;
    - **KUBridge** `kuKernelLoadModule` (4c25ea72) of any kernel module.

    User-mode modules load normally through KUBridge. `kuKernelGetModel` (24331850) returns 0 (PSP-1000).
  - If the game then calls an import that nothing provides, the stop reason is "This homebrew needs kernel-mode modules (audio_driver.prx, …), which PXP2 does not support: it runs user-mode homebrew only. Stopped at its call to fs_driver:15edc23a."
  - CMFileManager's `fs_driver` exports are thin wrappers: a k1 switch, then one IoFileMgrForKernel call. They could be mapped onto the user sceIo calls, but that would mean emulating one app's driver, so it was left unsupported.
- **User imports added:**
  - **`sceHprm`** (HleSystem): PeekCurrentKey and Peek/ReadLatch report no remote and all keys released; IsRemote/Headphone/MicrophoneExist return 0. SDL-based homebrew polls it every frame.
  - **`sceUtilityLoadAvModule` and `sceUtilityLoadModule`** (0x300+ ids) now **load the firmware library** and run its module_start while the caller waits (`HleModules.loadUtilityModule`):
    - atrac3plus: `kd/libatrac3plus.prx`;
    - mpegbase: `kd/mpeg.prx`;
    - mp3: `kd/libmp3.prx`;
    - aac: `kd/libaac.prx`.

    avcodec, sascore and vaudio stay HLE. AntiTetris calls sceMpegInit right after loading mpegbase.
  - **`sceUsbstorBoot`:** SetCapacity and Register/UnregisterNotify succeed (there is no USB host).
- **Clear stops:**
  - A DATA.PSAR that starts with `NPUMDIMG` stops with "PSN-format UMD image … not supported".
  - The encrypted-executable message names tag 0xADF305F0 as signed homebrew / NPDRM.

### HOME button and HOME menu

**Real PSP behaviour.** In a game or homebrew, HOME is never delivered to the game. The system shows its HOME screen over the paused game ("Do you want to quit the game?" Yes / No, with the clock and battery). Yes notifies the callback the game registered with LoadExecForUser sceKernelRegisterExitCallback; the game cleans up and calls sceKernelExitGame, and the PSP reboots into the XMB. A game without an exit callback is ended by the kernel directly. In the XMB, HOME opens no menu: the VSH reads it like any other button, and PXP2 passes it through unchanged.

**PXP2 flow** (`PspSystem.homeQuit`, `HleGame`, `HleSystem.ctrlMask`):
- **HOME is swallowed in GAME mode.** `startGame` sets `HleSystem.ctrlMask = ~0x10000`, so the sceCtrl Peek/Read buffers and latches never report HOME to a game. The app also drops the bit before it reaches the machine. The VSH (mask all ones) still gets it.
- **Yes:** `requestHomeQuit()` (any thread) is applied at the start of the next `run` slice, on the emulation thread:
  - **Exit callback registered:** `Kernel.notifyCallback(cb, 0)`. The callback runs on its owner thread through the normal callback mechanism, at that thread's next CB wait (sceKernelSleepThreadCB, sceDisplayWaitVblankStartCB, …). A timer event **5 s of emulated time** later (`HOME_EXIT_TIMEOUT_MICROS`) forces `gameLib.exitRequested` if the game has not called sceKernelExitGame by then, and logs "the game did not exit within the timeout".
  - **No callback** (or the registered uid is not a callback): `gameLib.exitRequested` is set at once. This is the same reboot path as sceKernelExitGame (`restartPending` / `restartIfRequested`).
- **No:** emulation resumes unchanged; nothing is sent to the guest.
- **Error fixed (kernel): callbacks on the current thread never ran.** `Kernel.schedule` runs a pending callback for a thread in a CB wait. When that thread was still `current`, because it had just entered the wait (DBZ's `user_main` in sceDisplayWaitVblankStartCB), the callback frame was built from its stale saved context and the CPU was never switched to the callback. The live registers are now saved first, and the callback is loaded into the CPU. XMB frame 700 GE counts are unchanged (1,415,330 triangles, 696 sprites).
- **FrameDump:** `-Dhomeat=frame:yes|no,...` simulates the HOME screen's answer at a frame. In the XMB it only reports that HOME goes to the VSH. The boot line reports "game exit to the XMB at t=… ms".

**Evidence (desktop, FrameDump):**
- **DBZ Shin Budokai** (`-Dgame`, `-Dhomeat=3700:no,3900:yes`, HOME held at 3890):
  - `user_main` registers exit callback `00100015` (entry `08811c04`) at start-up.
  - "No" at frame 3700 changes nothing.
  - "Yes" at frame 3900 (t = 65.06 s): the callback runs 17 ms later on `user_main` (watchpoint `08811c04`, a0 = 1, the notify count) and calls sceKernelExitGame. Boot 1 is the XMB; frame 4700 shows the Game column with DBZ selected.
  - Before the kernel fix the callback never ran, and the 5 s timeout forced the return at t = 70.06 s, so the timeout path is exercised too.
- **Celeste v0.1** (homebrew, SDL2): registers exit callback `00100019`. "Yes" at frame 500: its `update_thread` calls sceKernelExitGame in the same slice, and the machine reboots into the XMB (frame 1200: 1,415,330 triangles, 696 sprites, the normal XMB).
- No title without an exit callback was available. That path is the ExitGame path every game exit already uses.

**Android (`XmbActivity`, `HomeMenuView`):**
- HOME comes from the controller's PS / Guide / Razer button (`KEYCODE_BUTTON_MODE`), keyboard H and the on-screen HOME button.
- **Short press in a game:** the HOME screen (`HomeMenuView`, original drawing):
  - the frozen game frame, dimmed;
  - HOME, the date, the time and the battery at the top;
  - "Do you want to quit the game?" with Yes / No (No preselected), and button hints.

  D-pad, hat or left stick moves; the confirm button chooses; the other button (or HOME again) answers No; touch taps an answer. Confirm follows the PSP registry's `button_assign` (`PspSystem.circleConfirms`: 0 means ○ enters). Emulation is paused while the screen is up.
- **Short press in the XMB:** passes through to the VSH as before.
- **Long press (600 ms), in games and in the XMB:** the PXP2 quick menu (below).

### PXP2 quick menu, brightness and the Razer Kishi

**Quick menu** (`QuickMenuView`): opened by a long press of HOME / PS / the Razer button, or by the small ☰ button beside the on-screen HOME. Emulation pauses while it is open. It is a PSP-styled list tinted with `XmbTheme.accent`. D-pad or stick up/down selects, left/right changes, confirm activates, and back or HOME resumes; touch taps, drags sliders and scrolls. Rows:
- **Resume.**
- **Display:** Picture filter; Screen mode (original / stretch / true widescreen); Refresh rate (60/120/144 Hz, applied at once with `Surface.setFrameRate`); Resolution (Max or 1×…N×); Texture filtering; Anti-aliasing.
  - All are applied live, because the emulation loop reads these fields every slice, and saved to the same "pxp2" preference keys as Settings (`filter`, `aspect`, `refresh`, `resolution`, `texture_filter`, `antialias`).
  - System memory is shown read-only ("applies on restart").
- **System:**
  - **Brightness** (0 = follow Android, otherwise 5–100 %): sets `WindowManager.LayoutParams.screenBrightness` for PXP2's window only, with no system setting changed, and is saved as `brightness`. It also sets the PSP impose brightness (ids `0x2` / `0x80000002`) to a level from 0 to 3. PXP2's HLE default is 3; the level range is assumed, not verified against the firmware.
  - **Volume:** the PSP impose volume, 0–30 (setting it unmutes). The volume now also carries over game-boot and game-exit reboots.
  - **Swap disc** (the existing UMD menu) and **Return to PXP2**.
- **Controls:**
  - **On-screen gamepad:** Auto (hidden while a controller is in use) / Always shown / Hidden, saved as `pad_mode`. ☰ stays visible and tappable when the pad is hidden.
  - A "Last controller key" readout, and notes on Razer Nexus.
- Impose writes are queued and applied on the emulation thread (`applyMenuChanges`), because the impose map is not thread-safe.

**BACK:**
- A controller's BACK is the PSP's SELECT (the Kishi's Select / View button arrives as `KEYCODE_BACK`).
- A navigation-bar or touch BACK closes an open menu. Otherwise it asks "Leave the PSP?" (Leave / Stay) instead of closing the emulator.
- A key counts as a controller key when its source is gamepad or joystick, or when its device has vendor `0x1532` or "Kishi" in its name. That covers the Kishi's separate "Consumer Control" device.

**Razer Kishi V2 Pro** (the Razer Edge's built-in pad). From an earlier `dumpsys input`, it appears as "Razer Razer Kishi V2 Pro" (`1532:0717`, Generic.kl, KEYBOARD|GAMEPAD|JOYSTICK) plus "… Consumer Control" (KEYBOARD, no layout file).
- **Mapping:**
  - face buttons are positional (A ✕, B ○, X □, Y △);
  - L1/R1 and L2/R2 (keys or trigger axes) are L/R;
  - D-pad keys and hat are the D-pad, and the left stick is analog;
  - START/MENU is START, and BACK is SELECT (above).
- **The Razer / Nexus button:** if it reaches the app as `KEYCODE_BUTTON_MODE`, it is HOME (short press: the HOME screen in games; long press: the quick menu). A `KEYCODE_HOME` from the Consumer Control device is consumed by Android itself and **cannot be intercepted** by any app.
- **Nexus auto-launch:** an app cannot disable another app, and PXP2 changes no system settings.
  - PXP2 declares a `USB_DEVICE_ATTACHED` filter on its launcher activity (`res/xml/kishi_device_filter.xml`: vendor 0x1532 with product 0x0717, plus vendor 0x1532 alone for the other Kishi models, whose product ids were not available). Android can then offer PXP2 when a USB Kishi attaches, and "Always" keeps PXP2 as the handler.
  - The quick menu notes that Nexus's own "launch on controller connect" can be turned off in Razer Nexus's settings.
- **Unknown controller keys** are logged to logcat (tag `PXP2Input`: key name, code, scan code, device name and ids) and shown in the quick menu's "Last controller key" row.
- **Not verified on the Razer Edge in this session:** its Kishi was not attached (`getevent -lp` listed no Razer device). The key codes above come from the earlier dumpsys and Android's Generic.kl.

### Gamepad fixes

- **Right stick pressed L/R:** `AXIS_Z`/`AXIS_RZ` were treated as L2/R2, but on DualShock 4, DualSense, Kishi and Xbox-style pads they are the **right stick**. Triggers now use LTRIGGER/BRAKE and RTRIGGER/GAS only.
- **D-pad presses dropped:** stick motion cleared all D-pad bits, releasing D-pad buttons that arrive as key events (Kishi, DS4). Only bits the axes set are cleared now.
- **Generic HID face buttons:** raw BUTTON_1..4 from a pad without a layout (PlayStation HID order □ ✕ ○ △) are now mapped in that order. Pads with Android key layouts use positional BUTTON_A/B/X/Y, which was already correct.

### Device test results (2026-09-28)

| Device | SoC | Android | XMB | cpu / ge / present (ms) |
|---|---|---|---|---|
| Pixel 9 Pro Fold (inner) | Tensor G4 | 16 | 59–60 fps | 3.5–4.6 / 10–11 / 2–4 |
| Galaxy Tab S11 Ultra (SM-X930) | MediaTek Dimensity MT6991 | 16 | 56–58 fps | 2.6–2.9 / 3.7–4.8 / **12.6** |
| Razer Edge 5G | Snapdragon G3x Gen 1 (SG8175P) | 16 | 57 fps | 4.0 / 5.2 / **7.1** |

- On the large displays, **present** is the bottleneck: drawing the frame to a 2960×1848 or 2400×1080 surface with the sharp-upscale filter. CPU and GE have headroom there.
- Tab: "Exit to PXP2" was verified. The Edge's first-run setup (download, verify, decrypt; 312 files, 215 modules) completed on device.

### Game-launch sound (gameboot.pmf)

- **Error fixed:** in movie streams (PSMF), every ATRAC3plus frame is prefixed with an 8-byte header `0F D0 <cfg hi> <cfg lo> 00 00 00 00`, and the movie player passes the header's address to sceAudiocodecDecode. The decoder read the header as frame data. The first frame came out invalid, then the output was clipped noise (peak 32768), which is the crackle heard on launch. The decoder now skips the header and reports frame + 8 bytes as consumed. The launch sound now has a clean envelope in the recorded mix.
- **FrameDump `-Dwav=out.wav`** records the mixer output (44.1 kHz stereo).

### Library: PXP/ISO and hub games in the XMB Game column

**Request:** show the games on the PC hub in the XMB next to the Memory Stick homebrew, one entry per game even when the same disc is also in `PXP/ISO` or the same homebrew is already installed.

**Result:** every ISO in `PXP/ISO`, every ISO on the hub and every hub homebrew package appears in **Game → Memory Stick** with the game's own icon, PIC0/PIC1 backgrounds and (local ISOs) animated ICON1. ✕ on a hub ISO streams it with HTTP Range requests and boots it. **Dragon Ball Z Shin Budokai, streamed from a local hub, reaches its title screen** (New Game / Load Game) after 92 Range requests (23 MB).

- **Hand-off (host → core):** `PspSystem.setLibrary(List<LibraryEntry>)` (static, any thread) and `PspSystem.libraryCache` (metadata folder).
  - `LibraryEntry.localIso(File)`, `serverIso(hub, "games", file, size, name)`, `serverHomebrew(hub, file, size, isDir, name)`.
  - Helpers: `GameLibrary.localIsos(dir)` and `GameLibrary.parseCatalog(hubUrl, json)` (a small JSON reader, so the desktop and the app share it). `.cso`, PBP games and the `videos` list are skipped.
  - **Android:** `XmbActivity.handOverLibrary()` runs before the machine is built. It passes `PXP/ISO` and the last catalog, which the Server tab now saves (`server_catalog_json`, next to `server_hub_url`). The cache is `<app cache>/library`.
- **Virtual entries** (`GameLibrary`, served by `HleIo`; nothing is written to the Memory Stick):
  - Folder `ms0:/PSP/GAME/PXPISO_<id>/EBOOT.PBP` for ISOs and `PXPHB_<id>` for hub homebrew. `<id>` is the first 8 hex digits of SHA-1 of the cache key.
  - `sceIoDopen("ms0:/PSP/GAME")` returns the real folders, then the visible library folders. Dopen of a virtual folder lists `EBOOT.PBP`. Getstat, Open (read-only), Read, Lseek and `HleIo.readFile` work on it. Writes return EACCES.
  - vshmain lists the folder each time the Memory Stick item is opened (one Dopen, then EBOOT.PBP/PBOOT.PBP opens and PARAM.PBP stats per folder), so entries that finish scanning later appear the next time the list is opened.
- **Virtual EBOOT.PBP** (`ExitApp.pbp(sfo, icon0, icon1, pic0, pic1, snd0)`):
  - **PARAM.SFO:** the ISO's own `PSP_GAME/PARAM.SFO` (`ParamSfo` parse/build) with CATEGORY set to "MG" and BOOTABLE to 1, and REGION 0x8000, DISC_VERSION, PSP_SYSTEM_VER and PARENTAL_LEVEL added if missing. vshmain's Memory Stick launch checks need these.
  - **Images:** `ICON0.PNG`, `PIC0.PNG` and `PIC1.PNG`. `ICON1.PMF` and `SND0.AT3` are added for local ISOs only, when each is at most 768 KiB. They are skipped for hub ISOs to keep the scan light.
  - **DATA.PSP:** a 512-byte `~PSP` stub.
  - **Discs skipped:** discs without `PSP_GAME/PARAM.SFO` (UMD Video) and non-ISO files.
  - **Observed:** as on hardware, the XMB shows PIC0 (the game's info card) instead of the TITLE text when PIC0 exists. The DBZ entry shows its "Come and see who is the strongest!" card.
- **Hub homebrew metadata** is read without downloading the package:
  - `.pbp`: Range reads of the header, SFO, ICON0 and PIC1.
  - Folder: the same, on `file=<dir>/EBOOT.PBP`.
  - `.zip`: the central directory is found through the end record (one Range read of the last 64 KiB). The shallowest `*/EBOOT.PBP` entry is chosen, and its data is **inflated from a Range stream only up to the end of PIC1** (or of ICON0 when PIC1 ends past 2 MiB). Its parent folder name becomes the install folder.
  - On the user's hub, 6 of 113 zips hold no EBOOT.PBP (Vita `eboot.bin`, 3DS `.cia`) and are skipped.
- **De-duplication** (`GameLibrary.gameDir`, recomputed at every Dopen):
  1. ISOs are keyed by DISC_ID. Local ISOs come first, so a local copy wins over the hub, and among equals the first by file name wins.
  2. An ISO with the inserted UMD's DISC_ID is hidden (`Iso9660.discId()`); the UMD icon already shows it.
  3. Hub homebrew is hidden when a real `PSP/GAME` folder has the same name, or an EBOOT.PBP there has the same TITLE + DISC_ID. Hub duplicates show once. PSPSDK homebrew often shares DISC_ID `UCJS10041`, so TITLE is part of the key.
- **Launching** (`HleVsh`, bridge `21d4d038` sceKernelLoadExecVSHMs2): `HleIo.virtualItem(path)` recognises a library entry.
  - **ISO (local or hub):** the image becomes `io.umd`, and the request is recorded exactly like `vshKernelLoadExecVSHDisc`: `disc0:/PSP_GAME/SYSDIR/BOOT.BIN`, key "game" and the entry's DISC_ID. `restartIfRequested` carries the disc into the game machine and back to the XMB when the game exits, so the MS entry is then hidden by rule 2 and the disc shows as the UMD.
  - **Hub homebrew:** simplest robust approach. It is downloaded on the emulator thread into `ms0:/PSP/GAME/<folder>` (a `.pbp` becomes `<name>/EBOOT.PBP`; a zip has its EBOOT.PBP folder unpacked, with paths escaping the target refused). It then launches from there like installed homebrew. The hub serves a folder entry as a zip made on the fly. After this the real folder hides the hub entry (rule 3).
  - **Failure:** an error (0x80010005) goes back to vshmain, and `HleVsh.hostMessage` explains it (Android shows it as a toast). **Verified:** with the hub stopped before launch, vshmain restarts to the XMB and the message reads "Dragon Ball Z Shin Budokai: the disc image could not be opened: … Connection refused".
- **Streaming** (`HttpIsoSource`):
  - **Chunks and cache:** 256 KiB chunks from `Range: bytes=a-b`, where 206 is required. The LRU cache holds 16 chunks (4 MiB). The length comes from the catalog or a `bytes=0-0` probe (`Content-Range`).
  - **Read-ahead:** when reads are sequential, the next 2 chunks are fetched on a background thread. Concurrent requests for one chunk share a single fetch.
  - **Errors:** a failed chunk is retried twice (200/400 ms). Then the source is "offline" for 3 s, and reads fail immediately rather than blocking for about 15 s each.
  - **Guest side:** `HleIo` returns **EIO 0x80010005** for a failed read and logs the first 5 failures and every 500th ("UMD read failed (n) on …").
  - **Verified:** in `LibraryTest` (a dead hub gives an IOException in 0.6 s, and cached chunks stay readable). Also in a DBZ run where the hub was killed at frame 2000: `data_sys_us.afs` reads failed with EIO, the game waited, and FrameDump ran to "Stopped by host".
  - **Metadata scanning** uses 64 KiB chunks and no read-ahead. About 6 requests and 350 KiB per ISO.
- **Metadata cache:** `<cache>/<sha1(key)>.pbp` (the virtual EBOOT itself) or `.none` (skipped, with the reason).
  - **Key:** hub URL + category + file name + size for hub files, and path + size + mtime for local ISOs. The install folder and zip entry path of homebrew are kept in the cached SFO (`PXP2_FOLDER`, `PXP2_ZIP_EBOOT`).
  - **Scanning:** cached entries are ready immediately. Others are scanned in the background (1 thread for local, 3 for the hub) and appear once ready. Network failures are not cached and are retried at the next `setLibrary`.
  - **Measured on the dev PC**, hub on localhost with 10 retail ISOs and 113 homebrew zips: cold scan **3.1 s** for 123 entries (9.7 MB cache), warm **0.0 s**. There were 114 entries in the Memory Stick list.
- **Hub (`pxp_server.py`):**
  - Range requests: suffix ranges (`bytes=-N`), end clamped to the file size, and 416 with `Content-Range: bytes */size`.
  - Connections: `protocol_version = "HTTP/1.1"` keeps connections alive between Range reads, and the server stays `ThreadingMixIn`.
  - Paths: requests are resolved inside the category folder.
  - Folder homebrew is sent as a zip.
  - Options: `--port`, `--root` (this run only) and `--no-discovery` for test instances.
- **Server tab:**
  - **Stream ISO** no longer copies the first 10 MB into a cache file (reads past 10 MB used to fail). It opens the image once over HTTP to check it, then starts `XmbActivity` with the extra `stream_umd` = the hub URL, which `IsoSource.open` inserts as an `HttpIsoSource`. The saved UMD setting is left unchanged.
  - **Transfer:** a homebrew `.pbp` now lands in `PSP/GAME/<name>/EBOOT.PBP`, and a folder in `PSP/GAME/<folder>/`.
- **Desktop testing:**
  - FrameDump options:
    - `-Disodirs=dir;dir` adds local ISOs;
    - `-Dhub=http://host:port` adds the hub catalog;
    - `-Dlibcache=dir`;
    - `-Dlibwait=s` waits for the scan (default 120);
    - `-Dlibfilter=regex` filters by file name;
    - `-Dmark=frame:file` creates a file at a frame, so a script can stop the hub.
  - FrameDump also prints the stream statistics.
  - `tools/make-test-umd.py out.iso [DISC_ID] [TITLE]` makes discs with other IDs.
  - `core/tests/LibraryTest` needs no firmware.
- **Tests run:**
  - **Fixtures:** 3 local ISOs (two sharing PXPT00001) and 3 hub ISOs (PXPT00001, and two PXPT00002). Hub homebrew: zip, stored-zip duplicate, folder, `.pbp`, a zip named like the local `TEST` folder, and a zip with `TEST`'s TITLE + DISC_ID. Result: **6 entries** (Local A, Local C, one Server B and three homebrew), with the duplicates hidden. Inserting `Local A.iso` as the UMD leaves 5.
  - `G:/GAMES/PSP/GAMES` as the local folder plus the same library on the hub: 20 ISOs gave **10 entries**, all local. Launching DBZ from it inserts the local file ("Library disc inserted: G:\GAMES\...") and boots it.
  - **Folder homebrew:** ✕ on it downloaded and unpacked `HbDir` (EBOOT.PBP + data.bin) into PSP/GAME and launched it.
  - **Open:**
    - Library entries that finish scanning while the XMB shows the list appear only when the list is opened again.
    - A hub ISO launched from the XMB stays the inserted disc for later VSH restarts, but the host's saved UMD setting is not updated.
    - HOME-menu exit is now implemented, and "game exits → XMB" is verified with DBZ (see "HOME button and HOME menu").

### PC Hub auto-detection (Server tab)

- **No IP entry:** the Server tab finds `pxp_server.py` by itself, in this order:
  1. the last hub that worked;
  2. `127.0.0.1:8080` (a hub forwarded with `adb reverse`);
  3. a UDP broadcast `PXP2_DISCOVER` to port **8081**, which the hub answers with `PXP2_HUB <port>`;
  4. a parallel scan of the device's /24 subnet on port 8080.
  
  Each candidate is confirmed with `GET /api/ping` (new; JSON), or `/api/catalog` on older hubs. "Search again" forgets the last hub.
- **Code:** `HubDiscovery.java` (app); `discovery_responder()` and `/api/ping` in `pxp_server.py`.
- **Firewall finding:** on the dev PC, Windows Firewall allows python.exe inbound only on the **Public** profile, while the LAN (Ethernet) is **Private**. Devices therefore cannot reach TCP 8080 or UDP 8081 until an inbound rule allows them on Private. The user has to add that rule. It was verified end to end on the Tab S11 through `adb reverse` (124 items).

### PS1 games and artwork

**Request:** list PS1 games in the Server tab and, once transferred, in the Games tab; the hub converts each disc to a PSP "PS1 Classic" EBOOT.PBP; artwork is pulled automatically; real UMD label art on the 3D UMD; PSP discs are saved as "<Game Name> [<DISC_ID>].iso".

**PXP2 cannot run POPS yet.** Converted EBOOTs are for real PSPs. The app lists them with the note "PS1 (runs on PSP; PXP2 support coming)", has no play or 3D UMD button for them, and never hands them to another app. (A DuckStation hand-off that another change had added to `GameCatalogView` was removed completely.)

- **Converter** (`tools/pxp_pops.py`, our own code, GPL-3.0 like PXP2; also a CLI: `python tools/pxp_pops.py <image> <out EBOOT.PBP>`):
  - **Inputs:** `.cue` with one or many `FILE`s (MODE2/2352, MODE1/2352 and AUDIO tracks; `PREGAP` before a track's own file), raw `.bin`/`.img` (2352-byte sectors, found by the sync pattern), and cooked `.iso`/`.img` (2048-byte sectors). A cooked sector is rebuilt as Mode 2 Form 1 with sync, BCD header, subheader, EDC and P/Q ECC (ECMA-130, numpy-vectorised). EDC/ECC were checked **bit-exact against real Yu-Gi-Oh! sectors**.
  - **DISC_ID:** `SYSTEM.CNF` `BOOT=cdrom:\SLUS_014.11;1` read through ISO9660 → `SLUS01411`. A disc without `SYSTEM.CNF` gets a stable made-up `PXPSnnnnn`.
  - **PBP:** `\0PBP`, version 0x00010000, eight offsets.
    - PARAM.SFO: BOOTABLE 1, CATEGORY "ME", DISC_ID, DISC_VERSION "1.00", LICENSE, PARENTAL_LEVEL 3, PSP_SYSTEM_VER "3.01", REGION 0x8000 and TITLE; keys sorted, strings as UTF-8 (0x0204).
    - ICON0 144×80, PIC1 480×272 and DATA.PSP. There is no PIC0, so the XMB shows the TITLE text.
    - DATA.PSAR starts on a 0x10000 boundary.
  - **PSISOIMG0000 (single disc):**

    | Offset | Contents |
    |---|---|
    | +0x0C | offset of STARTDAT |
    | +0x400 | `_SLUS_01411` |
    | +0x800 | TOC: 10-byte entries A0 (PSEC 0x20 = CD-XA), A1, A2 (lead-out), then the tracks; BCD MSF +150 |
    | +0xBFC | 0x100000 |
    | +0x1220 | STARTDAT offset + 0x2D31, then FF 07, then the title at +0x122C |
    | +0x4000 | index: 32 bytes per 0x9300-byte block (16 raw sectors): u32 offset, u16 stored size, u16 flags, 16 bytes of SHA-1 of the raw block |
    | +0x100000 | the blocks, raw deflate (level 6, threaded; a block that does not shrink is stored with size 0x9300) |

    After the blocks come STARTDAT (0x50-byte header, a PNG, and a 4976-byte `\0PGD` trailer).
  - **DATA.PSP is not included.** It is the small signed Sony module that starts POPS, and every PS1 EBOOT carries one. PXP2 cannot ship it or write it.
    - Give the hub one you own: a `DATA.PSP`, or any PS1 EBOOT.PBP of yours (for example a PSN purchase), put in `pops_base/` next to `pxp_server.py`, or passed with `--pops-data-psp PATH` or `PXP2_POPS_DATA_PSP`. It is taken from the PBP's DATA.PSP section only when that EBOOT's CATEGORY is "ME".
    - **Without one**, the EBOOT is still built with a 512-byte placeholder so the art, SFO and image can be checked, but it **will not boot on a PSP**. The catalog then says `"pops_ready": false`, and both the app and the web page show it.
    - Adding a base later triggers reconversion, because the cache key includes its SHA-1.
  - **Limits:**
    - **Multi-disc:** each disc is its own EBOOT in its own `PSP/GAME/<DISC_ID>/` (The Legend of Dragoon → SCUS94491, SCUS94584, SCUS94585, SCUS94586). There is no PSTITLEIMG0000 wrapper, so no disc swapping, and POPS memory cards are per folder.
    - **Audio:** CD-DA tracks are kept in the image and TOC, but not converted to the ATRAC3 track table at +0xC00, so CD audio may be silent.
    - **Other formats:** `.ccd`, `.mds`, `.chd`, `.ecm` and MODE2/2336 cues are not read.
    - **Not verified on a PSP:** no real PSP was available. The layout follows the format notes that are documented as booting on ARK-4.
- **Hub** (`pxp_server.py`):
  - **PS1 library:** the saved `ps1_root` setting, else `PS1` next to the PSP root (the user's hub: `G:\GAMES\PS1`, 5 discs), or `--ps1-root`. Each top-level folder or `.cue`/`.bin`/`.img`/`.iso`/`.pbp` file is one entry.
  - **Catalog `ps1` fields:** `disc_id`, `name` (from the Redump name, "Legend of Dragoon, The (USA, Canada) (Disc 1)" → "The Legend of Dragoon (Disc 1)"), `artwork_icon`/`artwork_bg`/`artwork_box` (`/art/ps1/<ID>/icon0.png|pic1.png|box.png`), `device_folder` = DISC_ID, `filename_for_device` "EBOOT.PBP", `device_path` "PSP/GAME/<ID>/EBOOT.PBP", `converted`, `pops_ready` and `note`.
  - **Listing is fast:** it reads a few sectors per disc (cached) and never waits for the network. Art and the Redump table load in background threads.
  - **Download:** `/download?cat=ps1&file=<entry>` converts on the first request, caches in `hub_cache/ps1/<ID>/EBOOT.PBP` plus `build.json` (key: source size and mtime, converter version, DATA.PSP and art hashes), then serves with Range support and `Content-Disposition: EBOOT.PBP`. `/api/push_device` pushes to `/sdcard/PXP/PSP/GAME/<ID>/EBOOT.PBP`.
  - **Options:** `--cache DIR` (default `hub_cache/`), `--ps1-root`, `--pops-data-psp`.
  - **Leftovers (not used, not removed; that is the user's call):** `tools/ps1_converter.py`, `tools/popstation.py` + `tools/gamedb.py` (pop-fe code with an embedded Sony DATA.PSP blob), `tools/test_artwork.py`, `web/converted_ps1/` and `web/test_yugioh_EBOOT.PBP` came from an earlier attempt. The hub no longer imports them.
- **Artwork sources** (`tools/pxp_art.py`; fetched on demand, cached in `hub_cache/art` and `hub_cache/db`, nothing bundled; the images belong to their publishers and are only cached for the user's own library):

  | Source | Used for | Licence and notes |
  |---|---|---|
  | libretro-database `metadat/redump/Sony - PlayStation.dat` and `Sony - PlayStation Portable.dat` | serial → Redump name | CC-BY-SA-4.0; refreshed monthly |
  | libretro-thumbnails `Named_Boxarts`/`Named_Titles`/`Named_Snaps` (thumbnails.libretro.com, GitHub raw as fallback) | PS1 cover, title and snap; PSP cover | Box art is the publishers' |
  | xlenore/psx-covers `covers/default/<SLUS-01411>.jpg` | PS1 cover fallback by serial | No licence file |
  | LaunchBox Games DB (`gamesdb.launchbox-app.com/Metadata.zip`, about 108 MB, fetched once a month; images from `images.launchbox-app.com`) | "Disc" (UMD label scans) and "Box - Front" | Indexed to `launchbox_sony.json`: 1991 PSP and 4550 PS1 titles with images; about half of all PSP games have a disc scan |

  No other public UMD disc-label set keyed by serial was found. GameTDB has no PSP set, and libretro has no disc images.
- **Matching:**
  1. The serial gives the Redump name through the DAT, choosing the entry whose region matches the serial prefix: SLUS/SCUS/ULUS/UCUS → USA; SLES/SCES/ULES/UCES → Europe; SLPS/SCPS/SLPM/ULJM/ULJS/NPJH → Japan.
  2. The Redump name is matched to each image set by a **normalised title**: tags in brackets dropped, "Title, The" → "The Title", "&" → "and", lower case, non-alphanumerics removed.
  3. Among equal titles, the score prefers the same `(Disc n)`, then the serial's region ("World" and "Canada" count for USA), then fewer extra tags. Beta, Demo, Proto and Kiosk are penalised.
  4. With no exact normalised title, `difflib` takes the closest one with ratio ≥ 0.86 (0.9 for LaunchBox).
  5. Example: "Legend of Dragoon, The (USA, Canada) (Disc 1)" finds thumbnail "Legend of Dragoon, The (USA) (Disc 1)"; "3rd Birthday, The (USA)" finds LaunchBox "The 3rd Birthday" (North America disc).
- **Prepared images:**
  - **ICON0 (144×80):** the title screen cropped to 1.8:1 around the logo, like PS1 Classics icons. Without a title screen, the cover is fitted to the full height at its own aspect over a blurred, darkened copy of itself.
  - **PIC1 (480×272):** a gameplay snap (else the title screen, else a blurred cover with the cover on the right), cover-fitted without distortion, dimmed about 28 %, with a left shade under the XMB text.
  - **UMD label:** the disc circle in a LaunchBox scan is found automatically. The search looks for the circle whose rim is darkest against the brightest ring just outside it (the caddy plastic or a transparent background): a 256-px coarse search, then refinement at full size. The label is cut to a 512×512 PNG with the disc inscribed and transparent outside. The user's "The 3rd Birthday" example (ULUS-10567) and DBZ Shin Budokai both come out centred.
  - **Endpoints:** `/api/art/umd?id=ULUS10567`, `/api/art/box?sys=psp|ps1&id=…`. They return 404 on a real miss and 503 while the LaunchBox index is still being built, so the app does not cache that as a miss.
- **App:**
  - **Server tab:** PS1 cards show the hub's ICON0 (loaded in the background, cached in `cache/hub_art`), the DISC_ID, the target `PSP/GAME/<ID>`, and the PS1 note (plus "the hub has no DATA.PSP yet" when `pops_ready` is false). "Convert & Transfer" waits up to 15 minutes for the conversion and saves to `PXP/PSP/GAME/<DISC_ID>/EBOOT.PBP`.
  - **Games tab:** PS1 EBOOTs (CATEGORY "ME") show the PS1 badge and note, with no buttons.
  - **3D UMD** (`Umd3DView.loadHubArt(hub, DISC_ID, hasOwnArt)`):
    1. On a background thread it asks the hub for the UMD label.
    2. If there is one, it replaces the synthesized label, masked onto the caddy's painted disc (centre (251.5, 252), r 216 of 512) and spinning as before.
    3. If there is none and the ISO has no PIC1/PIC0, the cover becomes the synthesized label's artwork.
    4. Files and day-long misses are cached in `cache/umd_labels`. A newer `setGame` wins over a late reply.
  - **Double masking fixed:** `renderCustomGameDisc` masked its own output and `setDiscBitmap` masked it again, which shrank the label to r 182. It is now masked once.
- **Rename before transfer (PSP discs):**
  - **Name:** `"<TITLE> [ULUS-10081].iso"`. TITLE is PARAM.SFO TITLE (control characters and ™®© removed, ": " → " - ", `<>:"/\|?*` → space). The ID is DISC_ID with a dash after the 4 letters. `.cso` keeps `.cso`; PARAM.SFO is read from `.iso` and CISO `.cso` (`tools/pxp_psp.py`).
  - **Where the hub applies it:**
    - catalog `filename_for_device` (plus `title` and `disc_id`);
    - `Content-Disposition` on `/download?cat=games` (ASCII `filename=` and UTF-8 `filename*=`);
    - `/api/push_device`.
  - **Examples from the user's hub:**
    - "Dragon Ball Z - Shin Budokai.iso" → "Dragon Ball Z Shin Budokai [ULUS-10081].iso";
    - "FFVII Crisis Core.iso" → "CRISIS CORE -FINAL FANTASY VII- [ULUS-10336].iso";
    - "Loco Roco.iso" → "LocoRoco [UCUS-98662].iso".
  - **App side:** the app saves `PXP/ISO/<filename_for_device>` (from an older hub it takes the `Content-Disposition` name) and still counts an old copy under the PC name as transferred. `/download` and streaming still use the PC file name, so nothing else changes.
  - **Dedupe:** unchanged, because `GameLibrary` keys ISOs by the DISC_ID read from inside the image, not by file name.
  - **Source files on the PC are never renamed** unless asked explicitly: `python pxp_server.py --rename-sources-dry-run` lists and `--rename-sources` renames the discs in `<root>/GAMES`, then exits. It is off by default.
- **Format references:** used for the layout only; no code was copied.
  - PSDevWiki pages "Eboot.PBP", "PSISOIMG0000" and "POPS".
  - `jamescook/psp-toolkit` `docs/eboot-format.md` (MIT), whose notes are tested on ARK-4.
  - ECMA-130 for EDC/ECC.
- **Tests run** (dev PC, hub instances on ports 8093/8094/8095 with temporary roots and caches):
  - **Real disc:** Yu-Gi-Oh! Forbidden Memories (USA) `.cue`/`.bin`, 494 MB, converts in **10.8 s** to a 208 MB EBOOT. All 13,762 blocks inflate back to the source, and the SHA-1s in the index match.
  - **Independent reader:** pop-fe's `dump_pbp` (used only as a checker) extracts the EBOOT to an image whose data is identical to the source bin. SHA-1 d5785a41… is the Redump hash.
  - **Formats:** the same disc as a truncated `.cue`, raw `.img` and cooked `.iso` gives the same PARAM.SFO, `_SLUS_01411` and the TOC A0 41/01/20, A1 01, A2 lead-out 02:42:00, track 1 00:02:00. Sampled sectors match the source.
  - **Library IDs:** all 5 discs are identified (SLUS01411, SCUS94491/94584/94585/94586) and titled from Redump.
  - **Art:** ICON0/PIC1 are made for Yu-Gi-Oh! and Dragoon from libretro box, title and snap.
  - **UMD labels:** made for ULUS-10567 (The 3rd Birthday; the user's example) and ULUS-10081.
  - **Cold cache:** the catalog answered in 0.27 s; `/api/art/umd` returned 503 while the 108 MB LaunchBox index downloaded and was built, then 200.
  - **Downloads:** Content-Disposition `The 3rd Birthday [ULUS-10567].iso` with a working 206 Range, and `EBOOT.PBP` for PS1.
  - **Web page:** the PS1 tab lists 5 discs with icons and the note. A pre-existing JS syntax error (a `'G:\…\'` hint inside the Python template) had left the page's lists empty; it is fixed.
  - **Rename option:** `--rename-sources-dry-run` then `--rename-sources` on a temporary copy.
  - **Build:** `build.ps1` passes, lint included.
- **Not device-tested:** the Tab S11 (SM_X930) was not connected. A temporary Android emulator stayed "offline" in adb, so it was deleted again. The Server tab, Games tab and 3D UMD changes are verified by the build and lint only.

### SEPLUGINS (CFW plugins) and the HLE SystemControl

**Result:** CFW plugin PRXs load from the Memory Stick in VSH and GAME mode, with an HLE SystemControl API behind them. Three original test plugins and one real third-party plugin (PSPWave 1.1.0) work. A broken plugin is logged and skipped; boot always continues.

**Code:** `Plugins.java` (configuration, loading, fault isolation), `HleSystemCtrl.java` (SystemControl, KUBridge, kernel-mode libraries, SceModule2 blocks, hooks). Hooks elsewhere are small and marked "plugin support":
- `Kernel.importResolver` (imports with no HLE function of their own) and `Kernel.faultHandler` (a fault on a plugin thread ends that thread);
- `HleModules.load(..., allocation)`, `HleModules.onLinked`, `HleModules.onStart`;
- two calls in `PspSystem` (VSH and GAME boot) and `ms0/SEPLUGINS` created at start-up.

**Configuration** (ms0:, every path component case-insensitive; `ef0:` paths are read from ms0:):
- `SEPLUGINS/plugins.txt` (ARK style): `mode, path, on|off`. mode is `vsh`, `game`, `pops`, `always`/`all`, `umd` (disc games), `homebrew` (Memory Stick games) or a game ID such as `ULUS10081`.
- `SEPLUGINS/vsh.txt`, `game.txt`, `pops.txt` (PRO/ME style): `path 1|0`. A line without a flag counts as enabled.
- `#` and `;` start comments. plugins.txt entries come first, then the legacy file; a path loads only once.

**Boot-phase order (design choice):**
- **VSH:** pspbtcnf user modules load, then usersystemlib (HLE), libatrac3plus, paf, common_gui and common_util run module_start. Then **VSH plugins load and run module_start**, and only then vshmain (`vsh_module`) starts. Plugins therefore see every system library, and their start-module handlers see vshmain and every XMB plugin (opening_plugin, impose_plugin, …) start.
- **GAME:** the GAME-mode system modules are HLE. **Game plugins load and start before the game executable is loaded**, the order PRO/ARK use (plugins start before init loads the game), so a start-module handler sees the game module start (`DBZP_0035` for DBZ) and can patch it before its module_start.
- **POPS:** does not exist in PXP2, so pops entries are never loaded.

**Loading:**
- **Memory:** kernel plugins (module attribute `0x1000`) go into kernel partition 1. User plugins go into user partition 2, allocated from the top, so the game's link address (`0x08804000`, or a static ELF's range) stays free.
- **Start:** module_start runs on its own thread (kernel thread for kernel plugins) with the plugin path as its argument. The machine runs until it returns, for at most 200 M instructions (`Plugins.startBudget`); after that, boot continues with the thread still running. A return value of 1 (no resident) unloads the plugin.
- **Log:** one kernel-log line per plugin, e.g. `SEPLUGINS VSH: ms0:/seplugins/vshtest.prx: started PXP2_VshTest at 08000100 (kernel, 4 imports); module_start returned 00000000`, or `not loaded: file not found`, `module_start faulted: …`, `module_start still running …`. The lines also go into the boot report.

**Failure isolation:**
- **Imports PXP2 lacks:** when the plugin is linked, each gets a stub that logs and returns `0x8002013a` (library not linked), instead of stopping emulation. The same stub still stops emulation if a non-plugin module calls it.
- **Faults:** a CPU fault (bad address, bad instruction) on a thread owned by a plugin (its module_start thread, or a thread whose entry lies in plugin code) ends that thread, and emulation continues. A fault in plugin code running on a *system* thread (inside a hook) cannot be isolated; it is logged as such and stops emulation as before.

**Kernel-mode libraries for plugins:**
- **The problem:** PXP2 serves only user-mode library names, but plugins import kernel names. The PSPSDK stub archives in pspdev give two NID sets: `libpspkernel.a` (old NIDs, mostly equal to the user NIDs) and `*_kernel_660.a` (6.60 NIDs).
- **Same NIDs:** `HleSystemCtrl` maps these kernel names onto the user HLE libraries with the same NIDs: ThreadManForKernel, SysMemForKernel, IoFileMgrForKernel, ModuleMgrForKernel, LoadExecForKernel, UtilsForKernel, InterruptManagerForKernel, StdioForKernel, sceCtrl_driver, sceDisplay_driver, sceGe_driver, scePower_driver, sceRtc_driver, sceReg_driver, sceAudio_driver, sceHprm_driver and sceUsb_driver.
- **Renumbered NIDs:** the 19 6.60 NIDs that differ from the user NID of the same function are listed explicitly (ModuleMgr Load/Start/Stop/Unload/QueryModuleInfo/GetModuleIdList…, SysMem Alloc/FreePartitionMemory…, the SubIntr functions). All of them come from the stub archives.
- **Implemented directly:**
  - LoadCoreForKernel sceKernelFindModuleByName `cf8a41b1`/`f6b1bf0f`, ByAddress `fb8ae27d`/`bc99c625` and ByUID `cce4a157`/`40972e6e`;
  - KDebugForKernel Kprintf `84f370bc`, which goes to the kernel log and guest stdout;
  - SysMemForKernel sceKernelGetModel `6373995d`/`07c586a1` (0, PSP-1000) and SetDdrMemoryProtection;
  - ThreadManForKernel sceKernelGetUserLevel `f6427665`;
  - InterruptManagerForKernel sceKernelQuerySystemCall;
  - InitForKernel BootFrom `27932388` (0x20 disc, 0x40 MS, 0 VSH), ApplicationType `7233b5bc` (0x100/0x200/0x300), InitApitype `7a2333ad` (0x120 disc, 0x141 MS, 0x210 VSH; the VSH value is unverified) and InitFileName `a6e71b93`;
  - SysclibForKernel: memset/cpy/move/cmp, str(n)len/cmp/cpy, strcat, strchr, strrchr, toupper/lower, strtol/ul, and (s)(n)printf with an original formatter.
- **Error fixed (not plugin-specific):** `sceKernelQueryModuleInfo` wrote SceKernelModuleInfo with the wrong layout (for example, the name at +64 instead of +68, and the gp where text_addr belongs). It now follows the PSPSDK layout (`+48 text_addr`, `+64 attribute`, `+68 name`). PSPWave finds vshmain's text through it.

**SceModule2 blocks:** sceKernelFindModuleByName and the start-module handler pass a 256-byte block in kernel memory, laid out like the 6.xx SceModule2 of the public CFW/PSPSDK headers: `+0x08 modname`, `+0x2c modid`, `+0x50 module_start`, `+0x6c text_addr`, `+0x70 text_size`, and so on. The test plugin compiled against pspsdk `psploadcore.h` prints `offsetof(text_addr) = 108 (0x6c)`, which matches.

**SystemControl (`SystemCtrlForKernel` and `SystemCtrlForUser`) NIDs:**
- **How they are derived:** CFW exports use hash NIDs, the first 4 bytes of SHA-1(name) little-endian (psp-build-exports `PSP_EXPORT_FUNC`). `HleSystemCtrl.hashNid` computes them from the names.
- **Verified:** every hash equals the NID in pspdev's `libpspsystemctrl_kernel.a`/`_user.a`/`libpspkubridge.a` (checked with a script over the archives). `PluginsTest` checks four of them.
- **Extra non-hash NIDs:** these are also taken from those archives. Their names also appear in the public ARK-4 `exports.exp`.

| Function | NID | Behaviour in PXP2 |
|---|---|---|
| sctrlHENGetVersion / GetMinorVersion | 1090a2e1 / 5328b431 | `0x00001000` / 0 (PXP2's own values) |
| sctrlSEGetVersion | hash | `0x00060000` (PXP2's own) |
| sctrlHENIsSE / IsDevhook / IsToolKit | hash / hash / hash + `22a45db7` | 1 / 0 / 0 |
| sctrlHENFindFunction | 159af5cc | guest export of a loaded module, else an HLE entry point (below), else 0 |
| sctrlHENFindFunctionInMod | hash | same, by SceModule2 |
| sctrlHENSetStartModuleHandler | 1c90becb | handler runs on the module's start thread before module_start (a0 = SceModule2*, gp = the handler module's gp); returns the previous handler |
| sctrlHENPatchSyscall | f988c1dc, 826668e9, 02bfcb5f | user-mode imports reaching `addr` jump to `newaddr` (existing and later modules); kernel-mode importers are untouched, as with a syscall-table patch |
| sctrlKernelQuerySystemCall | hash | syscall code of an HLE entry point, else −1 |
| sctrlSEGetConfig(Ex) / SetConfig(Ex) / ApplyConfig | 16c3b7ee… / `2f157baf` | 36-byte blob (ARK SEConfig size; writing 36 is safe for the larger PRO/M33 layouts), saved in `flash1/pxp2-seconfig.bin` |
| sctrlKernelSetUserLevel / SetDevkitVersion | eb74fe45 / hash | store and return the previous value (user level default 4, unverified) |
| sctrlHENSetMemory / ApplyMemory / SetSpeed | 745286d1 / hash / hash | SetMemory with p2 > 24 switches to the high-memory layout if RAM > 32 MiB and the user partition is still empty (see "System memory size"), else logged; returns 0. Clocks not emulated |
| sctrlModuleTextAddr / sctrlPatchModule | hash | text address / write one word |
| sctrlFindImportByNID / sctrlHookImportByNID | hash | stub address / write `j func` (or `jr ra; li v0,0` for NULL) |
| sctrlGetInitPARAM / sctrlGetSfoPARAM | hash | values from the running game's PARAM.SFO (disc or PBP) / any SFO or PBP |
| sctrlKernelBootFrom, sctrlKernelMsIsEf, sctrlGetThreadUIDByName, sctrlKernelRand, sctrlFlushCache, sctrlHENIsSystemBooted, sctrlIsLoadingPlugins, sctrlSEGetDiscType, sctrlSEGet/SetUmdFile (`ac56b90b`/`b64186d0` too) | hash | as named (UMD file: empty string, set ignored) |
| sctrlKernelLoadExecVSHDisc / Ms2 / WithApitype, sctrlKernelExitVSH | hash | forwarded to the existing HLE LoadExec paths (sceVshBridge `63e69956` / `21d4d038`; sceKernelExitGame in GAME mode) |
| KUBridge kuKernelGetModel, GetUserLevel, BootFrom, InitApitype, InitKeyConfig, InitFileName, Peekw/Pokew, Memcpy, IcacheInvalidateAll, FindModuleByName, LoadModule, SetDdrMemoryProtection | hash | as named. kuKernelLoadModule/GetModel stay with HleModules' own registration; `reg` never overrides an existing NID |

Not implemented (plugins that call them get `0x8002013a`): the NID resolver, HENFindImport/HijackFunction/FindJAL helpers, LoadModuleOnReboot, the decrypt/homebrew-loader handler registrations, PSX VRAM handler, the decompressors (Deflate/Gzip/LZ4/LZO), `oe_malloc`, ISO/UMD mounting (`sctrlSEMountUmdFromFile`), and exit-to-launcher/recovery.

**Hooking (the interpreter has no decode cache):** `Cpu.step` decodes every instruction from memory each time (CACHE is a no-op), so text patches take effect at once, and IcacheInvalidate/DcacheWriteback are no-ops.
- **Guest functions:** for real guest modules (paf, vshmain, the game), `sctrlHENFindFunction` returns the real export address and plugins patch it like hardware.
- **HLE functions:** these have no guest code. `FindFunction` then returns an *entry point* in kernel memory (`jr ra; syscall n`), and from then on every import of that function, in current and future modules, jumps through it. Patching those two instructions with `j hook; nop` therefore hooks all callers, and a trampoline holding the two original instructions calls the original.

**Tests** (`tools/plugins/`: sources, Makefiles and `prebuilt/*.prx`, all original; built with `tools/plugins/build.sh` in WSL, pspdev psp-gcc 15.2.0):

| Plugin | Mode | Exercises | Result |
|---|---|---|---|
| `vshtest.prx` | VSH, kernel | FindFunction(sceDisplay, SetFrameBuf) + PatchSyscall; the hook draws "PXP2 VSH PLUGIN OK" and a frame counter into the framebuffer, then calls the original | Overlay visible on every XMB frame (`local-test/evidence/seplugins-vshtest-frame700.png`, counter 696 at frame 700) |
| `gametest.prx` | GAME, kernel | Kprintf, HEN/SE versions, sceKernelGetModel, InitForKernel queries, sctrlGetInitPARAM, sctrlSEGetConfigEx, a thread with sceKernelDelayThread | DBZ: "boot from 20, apitype 120, key config 200, file 'disc0:/PSP_GAME/SYSDIR/BOOT.BIN'", TITLE "Dragon Ball Z Shin Budokai" (type 0204, 27), DISC_ID ULUS10081, ticks at 2.02/4.02/6.02 s |
| `modhandler.prx` | VSH + GAME, kernel | SetStartModuleHandler (chained), sceKernelFindModuleByName (6.60 NID), FindFunction on a guest export, direct text patch of sceDisplayGetVcount with a trampoline | VSH: handler sees vsh_module, opening_plugin_module, impose_plugin_module; FindModuleByName matches; sceAtracGetAtracID resolved to `0880019c` (libatrac3plus export); the patched function counted 300 calls. DBZ: handler sees `DBZP_0035` (text 08804000) before its module_start, then sceATRAC3plus_Library and sceFont_Library_HV |
| `faulttest.prx` | VSH, **user** | calls an unimplemented import (sceSysreg_driver `de59dacb`), then a thread writes through NULL | Import returns `0x8002013a`; the thread is ended ("plugin thread faulttest_crasher faulted, thread ended, emulation continues"); XMB runs to frame 700 |

- **Also covered by the runs:** a missing PRX in vsh.txt ("not loaded: file not found"), a `0`-flagged entry being skipped, mixed-case paths (`ms0:/SEPLUGINS/ModHandler.prx` for `modhandler.prx`, `VSH.TXT`), and CRLF files.
- **Real plugin (user's HOMEBREW folder, unpacked to scratch only): PSPWave 1.1.0** (ARK-5 plugin: XMB wave colours).
  - It is a kernel-attribute module importing only user libraries. It polls `SEPLUGINS/PSPWave/PSPWave.txt` (`MENU n #RRGGBB` lines, normally written by its EBOOT editor), finds `vsh_module` through sceKernelGetModuleIdList + sceKernelQueryModuleInfo, patches vshmain's palette floats, and replaces the `01-12(_03g).bmp` / `13-27.bmp` path strings in vshmain with `ms0:/SEPLUGINS/PSPWave/1.bmp` / `2.bmp`. It requires at least 12 / 22 records of 6176 bytes.
  - With a test PSPWave.txt and channel-rotated copies of the firmware's `01-12.bmp`, the three paths in vshmain now read `ms0:/SEPLUGINS/PSPWave/…`, and the September XMB background turns from pink to yellow (`seplugins-pspwave-frame700.png` vs `seplugins-pspwave-off-frame700.png`). It needed the QueryModuleInfo layout fix above.
  - No other real seplugins were found: the 126 HOMEBREW archives hold only per-app PRX libraries, and `themes/` holds PTF/CTF files only.
- **Regressions:**
  - No-SEPLUGINS XMB boot on `local-test/storage`: "Stopped by host" at 700 frames, with GE counts identical to the build before the change.
  - DBZ `-Dgame` with gametest + modhandler: GE triangle/sprite counts at frames 600/3000/3500/3750/4000/4500 are identical to the run without plugins.
  - CoreTest, PipelineTest, LibraryTest and the new `PluginsTest` (config parsing and editing, NID hashes) pass. `build.ps1` passes, lint included (0 errors).
- **Open (found in testing):** with the LittleBigPlanet dynamic CTF installed in `local-test/storage`, `vshtest` (drawing every frame) makes paf's ScePafJob hit an out-of-memory null pointer around 6.1 s (malloc of 8 bytes returns 0 at `088739dc`). Without the CTF theme the same plugin runs cleanly. A busy-loop hook of similar length did not trigger it, but hooks that store to memory did. The cause is not understood yet (paf heap pressure from the CTF's replaced RCOs/PRXs plus changed timing is suspected).

**Android:**
- PxpStorage creates `PXP/SEPLUGINS`.
- Settings has a **Plugins** card (`PluginsList`): one row per PRX found in PXP/SEPLUGINS (two levels deep) or named in the configuration, with **XMB** and **Game** check boxes. A check box rewrites that plugin's existing line in place (plugins.txt keeps its `mode, path, on|off` format, legacy files `path 1|0`), or appends `path 1` to `vsh.txt`/`game.txt`. Changes apply at the next XMB or game start.
- This was built and linted; it was not exercised on a device in this session.

### System memory size (32 / 64 / 128 MB) and the model decision

**Request:** keep 64 MB and offer a setting to double it.

**What PXP2 emulated before:** 32 MiB of RAM at 0x08000000 (a PSP-1000). `SysMem` had partition 1 kernel 0x08000000 (4 MiB; 3/4 alias it), 5 VSH/volatile 0x08400000 (4 MiB) and 2 user 0x08800000 (24 MiB; 6 aliases it). Every model query returned 0 (PSP-1000).

**Now:** RAM is chosen when the machine is built: `new PspSystem(flash0, storage, ms0, ramMegabytes)` → `Kernel(ramBytes)` → `Memory(ramBytes)`. The default is 64 MiB (`PspSystem.DEFAULT_RAM_MB`). `new Memory()` / `new Kernel()` stay 32 MiB for the desktop analysis tools. The size carries over VSH restarts and game boots (`restartIfRequested`).

- **Address decoding:** `Memory` still strips the segment bits with `& 0x1fffffff` (0x8…, 0x4…, 0xC…, 0xA… mirrors), and RAM ends at 0x0bffffff (64 MiB) or 0x0fffffff (128 MiB). No size overlaps VRAM (0x04000000), scratchpad or the ME eDRAM window, so the masks need no change. Past the end of RAM an access faults; RAM is not mirrored.
  - **Fast paths:** `Memory` and the `Cpu` inline loads/stores used a fixed `0xfe000000` range mask (32 MiB). They now use `Memory.ramMask = ~(size − 1)` (`| 1`, `| 3` for alignment), held in a final field and read into a local once per `Cpu.step`. Without this, accesses above 32 MiB would still be correct (slow path) but slower. `CoreTest` checks lw/sw/lbu through the fast path at 0x0b000010 for 64 and 128 MiB, the mirrors and the faults at 0x0c000000 / 0x10000000.
  - Range checks that used the constant `Memory.SIZE` (`PrxLoader` static ELF and PRX segments, `HleThreads` FPL size) use the machine's size.
- **Partition layouts** (`SysMem`, `report()` prints them with their base):

  | RAM | 1 kernel | 5 VSH / volatile | 2 user (6) | 8 | 10 |
  |---|---|---|---|---|---|
  | 32 MiB (PSP-1000) | 08000000, 4 MiB | 08400000, 4 MiB | 08800000, 24 MiB | — | — |
  | 64 MiB (PSP-2000/3000/Go) | same | same | 08800000, 24 MiB | 0a000000, 28 MiB | 0bc00000, 4 MiB |
  | 64 MiB, high-memory layout | same | same | 08800000, **52 MiB** | — | 0bc00000, 4 MiB |
  | 128 MiB (non-standard) | same | same | 08800000, 24 MiB | 0a000000, 92 MiB | 0fc00000, 4 MiB |
  | 128 MiB, high-memory layout | same | same | 08800000, **116 MiB** | — | 0fc00000, 4 MiB |

  - The slim partitions 8 (0x8a000000, 0x1c00000, kernel attribute 0xc) and 10 (0x8bc00000, 0x400000) are as reported for the PSP-2000 on ps2dev (forums.ps2dev.org, "PSP Slim RAM partitions"). On 6.61 games still get the 24 MiB user partition; the extra memory is for the kernel/VSH. The 128 MiB rows are PXP2's own extrapolation (partition 8 takes everything up to partition 10 at the top).
  - **sceKernelVolatileMemLock** keeps handing out partition 5 (0x08400000, 4 MiB) at every size, as on hardware.
  - **High-memory layout** (CFW style, `SysMem.useHighMemory()`): partition 8's memory is added to the user partition. PXP2 applies it at boot when a Memory Stick homebrew's PARAM.SFO has **MEMSIZE = 1** (PSPSDK `PSP_LARGE_MEMORY`) and RAM > 32 MiB, and for `sctrlHENSetMemory(p2 > 24, …)` if nothing has been allocated in the user partition yet. At 32 MiB it logs that the layout is unavailable. PSP-1000 CFWs likewise cannot give more than 24 MiB.
  - sceKernelTotalFreeMemSize / MaxFreeMemSize still report partition 2, so they follow the layout.
- **Model decision: the reported model stays 0 (PSP-1000) at every RAM size.** `Kernel.model` feeds sceKernelGetModel (`SysMemForKernel` 6373995d/07c586a1), vshKernelGetModel (bridge 21c243fe), KUBridge kuKernelGetModel and sctrl's copy. The power hardware flag (`06bf847b`), EDRAM size (2 MiB, `sceGeEdramSetSize`) and the other syscon-derived values stay at their PSP-1000 values.
  - **Why:** the installed firmware is `flash-661-01g-v1`, the PSP-1000 image. It has no 02g+ files (`*_02g.prx`, `pspbtcnf_02g.bin`, `index_02g.dat`, …), and a 2000/3000 also has 4 MiB of eDRAM, which PXP2 does not emulate.
  - **Tested:** FrameDump `-Dram=64 -Dmodel=1` (PSP-2000): the XMB boots into the multilingual "A serious error has occurred. Contact the technical support line" screen (`local-test/evidence/ram64-model1-01g-fatal-frame700.png`). `-Dmodel=2` (PSP-3000) stops at once on the unimplemented `sceVshBridge:1d5c579f` ("color_space_mode", the 3000's display colour space).
  - So 64/128 MiB is "extra memory" on a PSP-1000, which is what the high-memory CFW layout is on real hardware for large-memory homebrew. `-Dmodel=n` is kept as a FrameDump experiment option only.
- **Android:** Settings has a **System memory** card: "System memory: 32 MB (PSP-1000) / 64 MB (PSP-2000/3000/Go, default) / 128 MB (double, non-standard)", saved as `ram_mb` in the "pxp2" preferences and passed to `PspSystem` by `XmbActivity`. It applies at the next XMB start.
- **Verification** (desktop, FrameDump `-Dram=` and `-Dmem=true`, which prints TotalFree/MaxFree and the partition report):
  - **XMB, 700 frames:** "Stopped by host" at 32, 64 and 128 MiB, with identical GE counts (1,415,330 triangles, 696 sprites at frame 700). Frames 300/700 are pixel-identical, apart from the status-bar clock. Free memory after boot: user 3596 KiB total / 3533 KiB largest at every size.
  - **DBZ Shin Budokai** (`-Dgame`, START at 3100/3400, `-Dvideo=ffmpeg`): the title screen (New Game / Load Game) at frame 4000 at 64 and 128 MiB (`ram64-dbz-title-frame4000.png`). Frames 3000/3750/4000 are pixel-identical between 64 and 128 MiB, and 32 vs 64 MiB without presses too. User partition: 5325 KiB free.
  - **Celeste v0.1** (MEMSIZE = 1, SDL2) from a scratch Memory Stick: at 64 MiB the log says "high-memory layout, user partition 52 MiB". Its newlib heap asks sceKernelMaxFreeMemSize and gets 0x0314e400 (49.3 MiB), against 0x0154e400 (21.3 MiB) at 32 MiB and 0x0714e400 (113.3 MiB) at 128 MiB. The game runs into level 100 M at every size (`ram64-celeste-highmem-frame1000.png`, `ram128-…`); its heap then reaches above 0x0a000000, which exercises the new fast paths.
  - CoreTest (new RAM-size and partition checks), PipelineTest, LibraryTest and PluginsTest pass.
- **Open:** a real 02g+ model would need a 02g+ flash image (the installer only builds the 01g image) and 4 MiB eDRAM. The VSH itself never uses partitions 8/10 on the 01g firmware, so the XMB behaves the same at every size.

## 8. Open items

- **Video:** H.264 plays (see §7). Still open: the GE texture-cache fix for VRAM textures that run past 2 MiB, AAC decoding (a bitstream parser is needed to find frame ends), SEI/pic_struct reporting and MPEG-4 (codec types 1/2).
- **Media Engine:** not emulated at register level. H.264 is decoded by the host (`HleVideo`) and ATRAC3plus in Java; ATRAC3 is not decoded.
- **Games:** UMD games boot from the XMB (DBZ Shin Budokai plays, see "Game boot"). Still to do: the utility dialogs, and retail EBOOT decryption (game keys) for discs whose BOOT.BIN is empty.
- **Homebrew and CFW:** SEPLUGINS and the HLE SystemControl are in (see "SEPLUGINS"); still open there: the unimplemented sctrl helpers, POPS, fake SceModule2 entries for HLE-served firmware modules (FindModuleByName("sceController_Service") returns NULL), and the CTF + drawing-plugin paf out-of-memory. Static-ELF homebrew now loads (see "Memory Stick homebrew"). Still open:
  - kernel-mode homebrew;
  - signed (tag 0xADF305F0) and PSN-format (NPUMDIMG) EBOOTs;
  - GE line drawing for BlockOut II's pit;
  - the utility dialogs (MsgDialog, OSK), which none of the tested titles needed.
- **Networking:** host networking, ad-hoc and infrastructure.

---

## 9. PXP2 CFW (from-scratch custom firmware)

A separate, original custom firmware for **real** 6.61 hardware (all models:
1000/2000/3000/Go/Street) lives in `cfw/`. It is not part of the emulator and
reuses none of the Java code — only the findings above. It compiles cleanly
with `psp-gcc` 15.2.0 but is **untested on hardware**.

- Uses these psp.md findings: the LoadExec variants and their sceVshBridge
  NIDs (VSHDisc `63e69956`/`d8320a28`, VSHMs2 `21d4d038`/`d940c83c`,
  ExitVSHVSH `d47041ca`/`08f7166c`); the UMD disc check (devctl `0x01f20001`
  writing only `out+4`, the G/V/A letters, states `0x12`/`0x32`); the MS
  homebrew launch checks (`~PSP`/`~SCE`, 512 bytes, SFO keys); static-ELF vs
  PRX loading; the PBP/`~SCE` layout; CheckRegion `5c2983c2`.
- Note: the emulator addresses here (`08a1ac40`, `089f42c0`) are HLE
  addresses, not retail flash offsets; the CFW marks all per-model text
  offsets UNVERIFIED and hooks by NID instead.
- See `cfw/README.md`, `cfw/DESIGN.md`, `cfw/BUILD.md`.

### Emulator smoke test (2026-09-28)

The CFW was smoke-tested inside PXP2 (see `cfw/TESTING.md` for the full
table). PXP2's kernel is HLE, so this validates the CFW's own logic, not
hardware behaviour. Findings:

- **User parts run end to end.** The installer and recovery EBOOTs and the
  `vshctrl` VSH plugin run as real guest MIPS. vshctrl loaded via the
  SEPLUGINS loader, scanned `ms0:/ISO`, parsed `PARAM.SFO` titles (incl. the
  retail DBZ image), wrote the `launch.iso` hand-off and invoked VSHDisc; the
  XMB still booted to frame 700.
- **Kernel PRXs load and run `module_start` but their hooks no-op.**
  `systemctrl`/`loader` load into partition 1 and relocate; `systemctrl`'s
  `module_start` completes (config, umd driver register, pending-ISO mount,
  exported PXP2Ctrl API all work). Their NID hooks target Sony kernel code
  PXP2 does not run, so nothing is patched (safe). `loader` correctly tries
  to `sceKernelLoadModule` systemctrl, which PXP2 refuses (kernel module).
- **Three real CFW bugs were found and fixed** (installer hardcoded payload
  folder; recovery first-run Save wrote to `ef0:` on non-Go models; vshctrl
  called `sceKernelExitGame` on `main()` return, killing the VSH). Details in
  `cfw/TESTING.md`.
- **Two minimal PXP2 gaps were filled** to load these modules: `PrxLoader`
  accepts a static ELF with a non-loadable first program header; `HleIo` has
  stub kernel `sceIoAddDrv`/`sceIoDelDrv` (registration only — guest I/O is
  not dispatched through the driver).
