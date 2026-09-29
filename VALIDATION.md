# Fresh implementation validation — 2026-09-28

## Game boot (UMD) — Dragon Ball Z Shin Budokai (ULUS10081)

- Desktop harness (`FrameDump -Dumd=… -Dpress=1000:4000`): ✕ on the disc in the XMB plays the game-boot animation, calls `vshKernelLoadExecVSHDisc`, and PXP2 reboots into GAME mode.
- The disc's BOOT.BIN (a plain ELF PRX with section relocations) loads at `0x08804000`. EBOOT.BIN (tag `0xC0CB167C`) is not needed.
- Frames verified:
  - the Atari, Okratron 5000 and Dimps logos;
  - the title screen;
  - Name Entry;
  - a save written with the Savedata utility (LISTSAVE) and found again with LIST;
  - Dragon Road dialogue with PGF text;
  - a 3D battle (Goku vs Gohan) with correct cel-shaded characters after the backface-culling fix.
- XMB frames are pixel-identical with and without the culling change.
- Known gaps: game audio is silent (sceSasCore mixing is not implemented), there are no utility dialog UIs, and the HOME menu is missing.
- The Android build (`build.ps1`) passes; `XmbActivity` follows the game reboot through `PspSystem.restartPending()`.

## Version 0.3.0 — real 6.61 XMB boots (HLE kernel, software GE)

- On the desktop harness (`FrameDump`, `VshBootTest`), the VSH boots from `pspbtcnf.bin`:
  - `libatrac3plus`, `paf`, `common_gui`, `common_util` and `vshmain` start;
  - the opening plays, with the ATRAC boot sound decoded as silence;
  - `system_plugin`, `topmenu_plugin`, `sysconf_plugin` and `impose_plugin` load.
- First boot shows Sony's "Setting information is corrupted" prompt. Pressing O triggers `vshKernelExitVSHVSH`, and PXP2 restarts the VSH with the supplied vshmain arguments. The next boot reaches the XMB.
- XMB verified in frames:
  - Game column with Saved Data Utility and the Memory Stick;
  - clock (host date/time), battery and volume icons;
  - the September purple wave;
  - navigation to Video (Memory Stick free space), Music (SensMe), Photo (Camera) and Extras (Digital Comics).
- Registry types corrected to the PSP codes (int = 2). `/REGISTRY/category_version` = 0x66 passes vshmain's integrity check. The registry persists to `flash1/registry/pxp2-registry.bin`.
- Pixel 9 Pro Fold: the setup screen and XMB run, with touch controls visible.
  - Debug APK: 14 fps, with CPU 26.7 ms + GE 30.4 ms per frame. Optimisation is ongoing; the release APK is now built.
- Desktop frame cost: 16.4 ms → 7.0 ms per frame, from the texture cache, span rasteriser and parallel band rendering.
- Known gaps: audio/video decoding, Media Engine, games/UMD, networking, lines/fog/stencil in the renderer.


## Version 0.2.0 package extraction and executable loader

- Existing CPU tests and 34 additional package/ELF checks passed. Tests include bad signatures, versions, offsets, source checksum, changed extracted files, truncated data, empty sections, zero-filled BSS, wrong architecture/endianness, invalid entry/memory/alignment, unsupported instructions, syscalls, and instruction limits.
- Android assembleDebug and lintDebug passed: zero errors, seven nonblocking warnings (backup rules, missing icon, untranslated labels).
- Updated `org.pxp2.fresh` on the Pixel 9 Pro Fold without clearing its downloaded update.
- Actual on-device package extraction produced PARAM.SFO (2,080 bytes), ICON0.PNG (11,261 bytes), DATA.PSP (5,718,512 bytes) and DATA.PSAR (26,848,656 bytes), with a SHA-256 manifest. The host extraction matched these results.
- On-device original ELF execution passed: 22 completed instructions, sum 15, five delay slots, stop at BREAK at 0x08800018.
- On-device readiness check found the encrypted ~PSP updater and PSAR version 3. Firmware instructions executed: zero. Decrypted virtual flash and firmware boot remain unimplemented.
- APK archive contains no native libraries or bundled PBP/PSAR/PRX files. APK SHA-256: fb4d9dacc12cf438156019e0312fab6113ea2588723261e01f3d122076587023.
- Local screenshots are in `local-test/evidence/`. Test inputs and extracted Sony files are excluded from source control and APK packaging.

## Previous 0.1.0 validation

- Java core tests passed: integer loop, branch delay slots, little-endian memory, bounds and alignment, zero register, signed and unsigned bytes, loads/stores, call/return, unsupported opcode rejection.
- Android debug build and lint passed (four nonblocking warnings: backup configuration, application icon, and two untranslated button labels).
- Installed `org.pxp2.fresh` on the user-selected Pixel 9 Pro Fold.
- On-device original CPU diagnostic passed: 22 instructions, sum 15, five delay-slot executions.
- Downloaded Sony 6.61 directly through the fresh app. Saved 32,580,549 bytes. Independently checked the device file with sha256sum: dc23a6dabdaed40bbfaad811d1f170346b911ba9750478fe5928d9f10c94f552.
- Downloaded firmware survived an APK update. The update package has NOT been extracted, installed as PSP flash, or executed.

The previous fork is archived separately at `C:\Users\Ben\Desktop\pxp2-archived-fork-20260928`. It is not a build dependency. Prior fork game/XMB results do not apply to this implementation. This is an early CPU and Android frontend foundation, not yet a functional PSP game emulator.
