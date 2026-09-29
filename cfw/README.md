# PXP2 CFW

A **from-scratch** custom firmware for real Sony PSP hardware on official
firmware **6.61**, for all models: **PSP-1000, PSP-2000, PSP-3000, PSP Go
(N1000) and PSP Street (E1000)**.

It launches **ISO/CSO games** from the Memory Stick (`ms0:/ISO`, and
`ef0:/ISO` on the Go) and runs **homebrew `EBOOT.PBP`**, including static-ELF
homebrew, through a patched module loader.

## Status: EXPERIMENTAL — UNTESTED ON HARDWARE

> This code was written from public documentation and compiles cleanly with
> `psp-gcc` 15.2.0. **It has never been run on a real PSP.** Every hardware
> offset is marked UNVERIFIED. Do not assume anything here works on a device.
> The first-run flow is deliberately the safest possible (memory-stick only,
> no flash writes). See `DESIGN.md` for the honest list of what is unproven.

## What it is (and is not)

- **Original work.** No code was copied or forked from ARK-4, PRO, LME, ME,
  M33 or PPSSPP. It was written in C against public format and NID references
  (psdevwiki, uOFW-style docs, the PSPSDK headers) and against the 6.61
  behaviours PXP2 reverse-engineered in its emulator (recorded in `../psp.md`).
- **HEN-style, volatile.** The CFW loads from the Memory Stick after a
  **user-supplied 6.61 kernel exploit** (an Infinity-2 compatible hook point
  or a HEN launcher). **This project ships no Sony firmware and no exploit.**
- **Brick-safe by design.** The first version **never writes flash0.** Flash
  writing is compiled out. Persistence is a future, separate, opt-in tool.

## Licensing

GPL-3.0-or-later, like PXP2. See `LICENSE`. Every source file carries the
GPL header.

## Components

| Component | File | Role |
|---|---|---|
| Loader / entry stub | `loader/` → `loader.prx` | what the exploit calls; starts the CFW |
| System control | `systemctrl/` → `systemctrl.prx` | module-load hooks, unsigned-code policy, plugin API, and the ISO/UMD driver |
| ISO driver | `isodrv/` (linked into systemctrl) | virtual UMD backed by `.iso`/`.cso`, UMD state machine |
| VSH plugin | `xmbplugin/` → `vshctrl.prx` | lists ISOs, reads PARAM.SFO, launches via VSHDisc |
| Recovery menu | `recovery/` → `EBOOT.PBP` | hold **R** at boot; toggles, "boot normally", "disable CFW" |
| Installer | `installer/` → `EBOOT.PBP` | the "update"; copies the payload to the Memory Stick |

## Install flow

1. Build everything (see `BUILD.md`).
2. Put the installer and payload on the Memory Stick:
   ```
   ms0:/PSP/GAME/PXP2CFW/EBOOT.PBP        <- installer (installer/EBOOT.PBP)
   ms0:/PSP/GAME/PXP2CFW/systemctrl.prx   <- from systemctrl/
   ms0:/PSP/GAME/PXP2CFW/vshctrl.prx      <- from xmbplugin/
   ms0:/PSP/GAME/PXP2CFW/recovery.prx     <- (optional; recovery is also an EBOOT)
   ms0:/PSP/GAME/PXP2CFW/loader.prx       <- from loader/
   ```
   (On the Go, use `ef0:` instead of `ms0:`.)
3. On the PSP, run **PXP2 CFW Installer** from the Game menu. It copies the
   payload to `ms0:/PXP2CFW/` and creates `ms0:/ISO` and `ms0:/SEPLUGINS`.
   **It touches no flash.**
4. Run your own 6.61 kernel exploit and have it start
   `ms0:/PXP2CFW/loader.prx` in kernel mode (the "entry contract" in
   `DESIGN.md`). The loader then starts `systemctrl.prx`.
5. Put games in `ms0:/ISO` (`.iso` or `.cso`) and homebrew in
   `ms0:/PSP/GAME`.

To uninstall: delete the `PXP2CFW` folder. Nothing else was changed.

## Recovery menu

Hold **R** while the exploit runs (or launch the recovery EBOOT). It edits
`ms0:/PXP2CFW/cfw.cfg` only. Options: enable/disable CFW, allow unsigned
homebrew, per-mode plugins, UMD region-free, present-ISO-as-UMD, and
"boot normally". Choosing **disable CFW** clears `cfw_enabled`; systemctrl
then loads but stays inert on the next boot.

## Plugins (seplugins)

`ms0:/SEPLUGINS/vsh.txt`, `game.txt`, `pops.txt` are read per boot mode.
Format:
```
ms0:/seplugins/example.prx 1     # 1 = on, 0 = off
# comment
```

## Warnings

- Untested on hardware; use at your own risk.
- Only firmware **6.61** is targeted.
- You must supply your own legally-obtained exploit and your own game backups.
- Do not enable flash writing (it is not present in this build).
