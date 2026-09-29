# PXP2 CFW — Design

This document describes the architecture, the hook points, the 6.61 offset
table and its verification status, the boot sequence, the ISO driver design,
what was taken from the PXP2 emulator findings (`../psp.md`), and the
hardware test plan.

> **Honesty statement.** None of this has been run on a real PSP. The code
> compiles cleanly (`psp-gcc` 15.2.0) but is otherwise unproven. NIDs and
> documented format constants are treated as reliable; concrete per-model
> flash text **addresses** are treated as UNVERIFIED and are left at 0 in the
> table, with the design falling back to NID-based hooking so first bring-up
> needs no hardcoded offsets.

## 1. Overview

PXP2 CFW is a HEN-style, volatile custom firmware. It is loaded from the
Memory Stick after a user-supplied 6.61 kernel exploit, installs kernel hooks
so unsigned homebrew and ISO games can run, and adds a VSH plugin plus a
recovery menu. It never writes flash0.

```
 exploit (user-supplied, not shipped)
        │  kernel mode
        ▼
 loader.prx ── builds pxp2_boot_args, detects model/ef0/R-held
        │  sceKernelLoadModule + StartModule (kernel)
        ▼
 systemctrl.prx ── module-load hooks, unsigned policy, plugin API,
        │           ISO/UMD driver (isodrv linked in), plugin loader
        ├── registers umd9660 driver (disc0:/umd0: → mounted ISO)
        ├── loads ms0:/SEPLUGINS/vsh.txt plugins
        └── vshctrl.prx (VSH plugin) lists ms0:/ISO and launches via VSHDisc
```

## 2. Entry contract

`loader/loader.c` exports `pxp2_loader_entry()` and also runs it from
`module_start`. The contract the exploit must satisfy:

- **CPU mode:** kernel mode (module attribute `0x1000`).
- **When:** early, before the VSH is fully up; called once.
- **State:** interrupts enabled, valid thread context, IO drivers for
  `ms0:`/`ef0:` available.
- **Return:** 0 on success; the exploit then lets the boot continue with our
  hooks installed.

The loader:
1. finds the payload root (`ms0:/PXP2CFW`, else `ef0:/PXP2CFW`),
2. fills `pxp2_boot_args` (magic, model via `sceKernelGetModel`, fw marker,
   `boot_from_ef0`, `recovery_hold` from the R trigger),
3. `sceKernelLoadModule` + `sceKernelStartModule` on `systemctrl.prx` in the
   kernel partition, passing `&pxp2_boot_args`.

We do **not** launch a user app (recovery) from kernel context; recovery is a
normal homebrew EBOOT and the loader only records `recovery_hold`.

**Persistence** (a flash resident, an IPL/`pspbtcnf` hook) is intentionally
out of scope for this build. Nothing is written to flash0. Re-applying the
CFW at each boot via the exploit is the brick-safe HEN model.

## 3. systemctrl (kernel)

### 3.1 Module-load hooks
`systemctrl.c` hooks `sceKernelStartModule` (via its `ModuleMgrForUser`
export stub, resolved by **NID** — `hooks.c: pxp2ctrl_find_export`). On each
start it resolves the `SceModule` from the uid, runs every registered plugin
`pxp2_module_handler` (patch point, after relocation, before the real
`module_start`), then chains to the original. This is how plugins patch
modules as they load.

Hooking is done by **rewriting the two-instruction jump stub** (`hooks.c:
pxp2ctrl_hook_import`): replace word 0 with `j <func>`, keep the delay slot,
write back D-cache and invalidate I-cache, with K1 cleared during the patch.
No hardcoded text address is required for this.

### 3.2 Unsigned homebrew / signature policy
`install_memlmd_hook()` hooks memlmd's decrypt export **by NID**
(`NID_memlmd_2F3D7E2D`). For a plain ELF or an unsigned `~PSP` (homebrew) it
reports success and leaves the buffer for the loader's ELF stage; for signed
Sony code it defers to the original decrypt, so official titles and firmware
modules keep working. If the NID/module is not present, the hook simply is not
installed (non-fatal), which is the brick-safe default.

This matches `../psp.md`'s findings on the `~PSP`/`~SCE` envelopes: homebrew
`DATA.PSP` starts with `~PSP` (or `~SCE` wrapping), and static ELFs (ET_EXEC
linked at `0x08900000`) are the common homebrew case — both are handled by
letting the loader see the plain ELF.

### 3.3 Plugin API
Exported as library `PXP2Ctrl` (`systemctrl/exports.exp`): version/model/mode
getters, `register/unregister_module_handler`, `find_module`, `find_export`,
`hook_import`, the unsigned-code toggle, `set/get_iso`, and config load/save.
See `include/systemctrl.h`.

### 3.4 Offset table — verification status
`include/offsets_661.h` + `systemctrl/offsets_661.c`.

- **NIDs and documented constants** (LoadExec variants, ModuleMgr NIDs,
  memlmd NIDs, UMD devctl codes, drive states, disc-type letters, CheckRegion
  NID) are tagged **VERIFIED_DOC** — they come from public sources or from the
  PXP2 emulator's reverse-engineering of the real 6.61 firmware in `psp.md`.
  NIDs are stable and independent of where bytes sit in flash.
- **Every concrete per-model text address** (`loadcore_start_module`,
  `loadexec_disc/ms2`, `memlmd_decrypt/sigcheck`, `vshmain_disc_check`) is
  **0 == UNVERIFIED** for all five models. They are placeholders. The design
  deliberately hooks by NID so these can stay empty on first bring-up. Do not
  ship non-zero values unless derived from a real 6.61 dump for that exact
  model.

The `psp.md` address `08a1ac40` for the vshmain disc check, and the HLE
launch-check address `089f42c0`, are **emulator** addresses, not retail text
addresses, and are recorded as such — they are not used as hardware offsets.

## 4. ISO driver (isodrv, linked into systemctrl)

`include/isodrv.h`, `isodrv/iso.c`, `isodrv/umd9660.c`, `isodrv/kalloc.c`.

- **Backing image:** one `.iso` or `.cso` mounted at a time (one UMD).
  `.iso` is raw 2048-byte sectors. `.cso` is CISO v1: header at 0, then a
  `(nblocks+1)` u32 index; each block is raw (bit 31 set) or **raw-deflate**
  (zlib `inflateInit2(-15)`). This is inferno-style plain sector reads plus a
  CSO decompression path, written from the public CISO format.
- **UMD state machine** (from `psp.md` "UMD drive"): states `0x01` no disc,
  `0x12` present+initialised, `0x32` activated. `iso_umd_set_inserted(1)` puts
  it at `0x32`. Disc type from UMD_DATA.BIN kind letters (G/V/A → 0x10/0x20/
  0x40); GAME is the safe boot default.
- **Devctl** (`iso_umd_devctl`): `0x01f20001` writes **only `out+4`** with the
  disc type (never clobbering the caller's size word — the exact bug fixed in
  `psp.md`); `0x01f20002/03` return the sector count; seek/cache hints are
  accepted as no-ops.
- **Driver registration** (`umd9660.c`): a `PspIoDrv`/`PspIoDrvFuncs` for
  `umd` giving raw sector open/read/lseek/devctl backed by `iso.c`. This is
  the public PSPSDK kernel I/O driver interface.
- **Launch path:** the VSH plugin writes the chosen path to
  `ms0:/PXP2CFW/launch.iso`; systemctrl reads it at boot
  (`pxp2ctrl_check_pending_iso`), mounts it, marks the UMD inserted, and the
  guest boots `disc0:/PSP_GAME/SYSDIR/BOOT.BIN` through the VSHDisc bridge —
  exactly the sequence `psp.md` recorded for a real UMD game launch.

**Staged / not yet complete (honest):** a full in-kernel ISO9660
`dopen`/`dread`/`getstat` layer on `disc0:`/`isofs0:` is not implemented; the
driver currently provides raw `umd0:` sector access and the state machine. The
user-mode plugin reads ISO9660 itself for metadata (`xmbplugin/iso9660.c`).
Wiring `disc0:` file access through the kernel driver, and deriving the disc
type by walking UMD_DATA.BIN in-kernel, are the next isodrv tasks.

## 5. XMB integration (vshctrl, user plugin)

`xmbplugin/vshctrl.c` scans `ms0:/ISO` and `ef0:/ISO`, reads each `.iso`'s
`/PSP_GAME/PARAM.SFO` (`iso9660.c` + `sfo.c`) for TITLE/DISC_ID, and exposes
`vshctrl_iso_count/title/path` and `vshctrl_launch_iso`. Launch writes the
handoff file and calls `sctrlKernelLoadExecVSHDisc` (`vshbridge.S` stubs, NIDs
`0x63e69956`/`0x21d4d038`/`0xd47041ca` from `psp.md`).

Homebrew in `ms0:/PSP/GAME` keeps working with **no XMB change**, because the
kernel loader patch (unsigned policy) handles plain-ELF and `~PSP` EBOOTs; the
VSH's own Memory-Stick launch path (checked in `psp.md`: PBP header, PARAM.SFO
keys, 512 bytes of DATA.PSP starting `~PSP`/`~SCE`) is unchanged.

**Staged (honest):** injecting new *items* directly into the retail XMB Game
column requires patching vshmain's list model, whose per-model 6.61 text
offsets are UNVERIFIED. The ISO model and the launch path are implemented and
independently testable; the list-injection hook is future work. Two integration
options are documented for when offsets are available: (a) patch the
`sceIoDopen("ms0:/PSP/GAME")` results to add virtual `EBOOT.PBP` folders (the
approach `psp.md` used in the emulator), or (b) hook the vshmain list builder
directly.

## 6. Recovery menu

`recovery/main.c` — a standalone homebrew EBOOT (so it runs even if
systemctrl failed to start). Hold **R** at boot, or launch it from the XMB. It
reads/writes only `ms0:/PXP2CFW/cfw.cfg` (a flat `key=value` text file, safe
to hand-edit). Toggles: CFW on/off, unsigned homebrew, vsh/game/pops plugins,
UMD region-free, present-ISO-as-UMD. Actions: save & boot, boot without
saving, disable CFW & boot. **No flash option exists.**

## 7. Installer ("update")

`installer/main.c` — a homebrew EBOOT that copies the payload from its own
folder to `<root>/PXP2CFW/` and creates `ISO`/`SEPLUGINS`. It writes only the
Memory Stick / internal storage. Flash writing is compiled out behind
`PXP2_ALLOW_FLASH_WRITE 0`, guarded by `#error` so it cannot be flipped on in
this build. On-screen warnings state the CFW is untested and that the user
must supply their own exploit.

## 8. What was taken from `../psp.md`

Findings (not code) used here:

- Boot flow / `pspbtcnf` structure and the VSH vs GAME mode module sets.
- The **LoadExec variants**: VSHDisc `d8320a28`, VSHMs2 `d940c83c`,
  ExitVSHVSH `08f7166c`, and their `sceVshBridge` NIDs
  (`63e69956`/`21d4d038`/`d47041ca`).
- The **UMD disc check**: devctl `0x01f20001` writing only `out+4`;
  UMD_DATA.BIN ≥ 48 bytes; the G/V/A kind letters; the drive states
  `0x12`/`0x32` and callback behaviour.
- The **Memory-Stick homebrew launch checks**: PBP header, PARAM.SFO keys
  (CATEGORY/DISC_ID/REGION/BOOTABLE), 512 bytes of DATA.PSP starting `~PSP`/
  `~SCE`.
- The **static-ELF vs PRX** distinction (ET_EXEC at `0x08900000`) that the
  unsigned-code path must accommodate.
- The **PBP layout** and the `~SCE`/`~PSP` envelopes.
- The CheckRegion bridge (`5c2983c2` → `sceChkreg 54495b19`) returning 1.

## 9. Hardware test plan (safest order)

Do these strictly in order. Stop at the first failure and diagnose; do not
skip ahead. Keep a second Memory Stick and a Pandora-style recovery available
before you start.

1. **HEN-only memory-stick boot.** Install via the installer. Run the exploit
   pointing at `loader.prx`. Confirm the PSP reaches the XMB with the CFW
   loaded and *no* game/ISO/plugins yet (`cfw_enabled=1`, everything else can
   be off). Success = normal XMB, no crash. This proves the entry contract and
   the kernel hooks install without hanging.
2. **Homebrew launch.** Put one known-good plain-ELF homebrew and one `~PSP`
   homebrew in `ms0:/PSP/GAME`. Launch from the XMB. Proves the unsigned-code
   policy and the loader patch.
3. **ISO launch.** Put one small `.iso` in `ms0:/ISO`. Launch it (via the
   plugin API / handoff). Proves the ISO driver, the UMD state machine and the
   VSHDisc path. Then repeat with a `.cso` to prove the inflate path.
4. **Plugins.** Add a trivial `vsh.txt` plugin, confirm it loads in VSH mode;
   then a `game.txt` plugin and confirm per-mode loading.
5. **Recovery menu.** Hold R, toggle options, confirm they persist in
   `cfw.cfg`; confirm "disable CFW" makes the next boot inert.
6. **Only then**, and only as a separate, reviewed, opt-in tool, consider any
   persistence work. It is **not** part of this build. Any flash work must be
   its own project with its own brick-recovery plan.

## 10. Known-unverified list (must be aware of)

- All per-model flash **text addresses** in `offsets_661.h` (all 0).
- Whether the memlmd NID hook alone suffices for every unsigned case on 6.61
  (the exact decrypt export semantics per model are documented by NID but
  unproven here).
- The in-kernel `disc0:` ISO9660 file layer (staged; only `umd0:` raw + state
  machine exist).
- XMB list-item injection (staged; model + launch implemented).
- The exact `SceKernelLoadExecVSHParam` field the 6.61 VSH expects for a
  plugin-initiated disc boot (we mirror `psp.md`'s recorded layout).
- Everything, ultimately: **no part of this has run on hardware.**
