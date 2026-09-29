# PXP2 — original PSP emulator

PXP2 is an independent PSP emulator written from scratch in Java for Android (package `org.pxp2.fresh`, label **PXP2**). It contains no PPSSPP or other emulator code. It runs the real Sony PSP system software (6.61), which the app downloads from PlayStation servers and decrypts on the device. Nothing from Sony is bundled.

## Downloads

Get the latest build from [Releases](https://github.com/nahalewski/PXP2/releases):

- **`PXP2-<version>.apk`**: Android release build (Android 8.0 / API 26 or newer).
- **`PXP2-<version>-debug.apk`**: debug build, with logging, for bug reports.
- **Windows:** a portable `PXP2.exe` (desktop emulator with the PC Hub built in) is in development and will be added to Releases when it's ready.

## Status

Tested on the Pixel 9 Pro Fold, Galaxy Tab S11 and Razer Edge 5G, and in the desktop test harness.

**Working**
- **XMB:** the real 6.61 XMB boots and runs at 60 fps on the Fold. It includes the boot animation and sound, first-boot settings repair, Sony's PGF fonts, the clock and battery bar, the animated wave, and every column.
- **Games:** UMD/ISO games launch from the XMB into GAME mode. Dragon Ball Z Shin Budokai plays: menus, story mode and 3D battles, with sound effects through an HLE `sceSasCore` mixer. Games can also stream from the PC Hub over HTTP.
- **Themes:** PTF themes and CTF (CXMB) themes, tested with the LittleBigPlanet dynamic theme. The app UI follows the active XMB theme.
- **Media:** music (ATRAC3plus in pure Java, MP3 and FLAC) with visualizers, and H.264 video in the XMB player.
- **Homebrew:** Memory Stick homebrew, including static ELFs, and SEPLUGINS through an HLE SystemControl.
- **Display:** internal resolution (1× up to the screen's maximum), texture upscaling, anti-aliasing, true widescreen, Pixel/Smooth/Sharp filters, and 60/120/144 Hz output.
- **Input:** a touch layer with on-screen PSP controls, gamepads (the Razer Kishi and PlayStation controllers), hardware volume keys wired to the PSP volume, and on-screen controls that hide automatically when a controller connects.
- **Library:** `PXP/ISO`, Memory Stick homebrew and PC Hub games appear together in the XMB Game column, one entry per game. The per-game 3D UMD view spins the retail disc label.
- **System memory:** 32, 64 (default) or 128 MB. Only large-memory homebrew benefits.

**Built on the PC, not yet verified on a phone**
- HOME button "quit game" screen, and a quick menu (long-press HOME or ☰) with live display settings, brightness, volume, gamepad show/hide and disc swap.
- Razer Kishi fixes: Select arriving as Back, and the app offered as the controller's handler.

**In progress** (partly written, not finished)
- Frame generation (30→60, or up to the display's refresh rate) with geometry motion vectors. The core and GPU shader exist but aren't wired into the renderer; it is off.
- CHD and CSO disc images, and CHD conversion in the PC Hub transfer. The readers and tests exist; end-to-end testing isn't done.
- `.pxp` container: PXP2's own deduplicating disc format, with tools in `tools/pxp/`.
- XMB-style previews in the app's game list (animated icon, menu sound, background).
- A decoder fix for animated XMB icons on Android hardware video decoders (DBZ's icon is scrambled on the phone).
- A PSP Settings menu in the app for changing XMB settings outside the XMB.
- Planned: HD texture packs with an in-game on/off toggle, and a per-device, per-game auto-settings database stored in INI files.

**Not yet**
- ATRAC3 and AAC audio decoding (silent), the utility dialogs (message box, on-screen keyboard), retail EBOOT decryption for discs whose `BOOT.BIN` is empty, kernel-mode homebrew and networking.
- PS1 games don't run in PXP2 yet. The PC Hub converts them to PSP-format EBOOTs for a real PSP.

See [`psp.md`](psp.md) for the full findings and fix log.

## PC Hub

`pxp_server.py` serves your own PSP ISOs, UMD videos, homebrew and PS1 discs to the app over Wi-Fi or USB. The app finds the hub automatically; there's no IP to type.

- It converts PS1 discs to PSP-format EBOOTs on first download and fetches artwork from the libretro databases.
- Run `start-server.bat`, or `python pxp_server.py --root D:\Games\PSP`. `--help` lists every option.
- It needs Python 3.8 or newer. PS1 conversion and artwork also need Pillow, numpy and scipy.

## Project layout

| Path | What it is |
|---|---|
| `core/src/org/pxp2/core` | The emulator: Allegrex CPU/FPU/VFPU interpreter, memory, firmware pipeline, PRX loader, HLE kernel, GE renderer, audio and video |
| `core/tests` | Unit tests and development tools (`FrameDump`, `Bench`, `PixelStats`, `ExportMap`, `Dis`, `Xref`…) |
| `app/` | Android front end: setup/recovery screen, XMB surface, touch/gamepad input, Server tab |
| `pxp_server.py`, `tools/` | PC Hub and its helpers: PS1 converter, artwork, disc metadata, compression and `.pxp` tools |
| `cfw/` | PXP2 CFW: an experimental from-scratch custom firmware for real PSPs. It compiles with `psp-gcc` but is **untested on hardware**. It never writes to flash. See `cfw/README.md` |
| `PXP2V/` | PXP2V: a from-scratch PS Vita emulator, at an early stage |

## Architecture (core)

- **CPU:** `Cpu`, `Vfpu`, `Memory`. An Allegrex interpreter (integer, FPU, VFPU) over 32 MiB RAM (up to 128 MiB), 2 MiB VRAM and a 16 KiB scratchpad.
- **Firmware pipeline:** `PbpPackage`, `PsarArchive`, `FirmwareCrypto`, `KlDecoder`, `FirmwareInstaller`. They verify the 6.61 update and decrypt it into a private virtual flash. See `CRYPTO-NOTICES.md` for research credits.
- **Module loader:** `PrxLoader` and `HleModules` relocate PRXs and link imports to real Sony code or to HLE syscalls.
- **HLE kernel:** `Kernel`, `SysMem`, `HleThreads`, `HleIo`, `Vfs`, `HleSystem`, `Registry`, `HleMedia`, `HleVsh`, `HleGame`, `HleSystemCtrl`. It covers threads, sync objects, memory, file I/O, display, controller, audio, UMD and USB state, and VSH bridge services.
  - **Honesty rule:** unimplemented functions stop with a report instead of returning invented values.
- **Graphics:** `Ge` and `GeRenderer`. A multithreaded software rasteriser with transforms, lighting, CLUT/swizzled/DXT textures, blending, Bézier/spline patches, and internal-resolution shadow buffers.
- **Boot:** `PspSystem` boots the VSH modules in `pspbtcnf.bin` order and handles VSH restarts and GAME-mode reboots.

## Build and test

- Requirements: JDK 17, Android SDK 36, Gradle 8.14.3.
- `build.ps1` builds the release and debug APKs into `dist/` and runs lint.
- `test-core.ps1` compiles the core and runs the CPU, pipeline and library tests.
- `run-boot.sh` and `boot-brief.sh` run the headless VSH boot diagnostic against `local-test/install`.
- `cfw/build.sh` builds the custom firmware with the PSPDEV toolchain.

CPU reference: MIPS32 Architecture for Programmers Vol. II-A. Format references are listed in `CRYPTO-NOTICES.md`.

## License

GPL-3.0-or-later. See [`LICENSE-GPL-3.0.txt`](LICENSE-GPL-3.0.txt) and [`CRYPTO-NOTICES.md`](CRYPTO-NOTICES.md). Sony firmware, games and resources are not included. You supply them from your own console or from Sony's servers.
