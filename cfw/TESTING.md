# PXP2 CFW — Testing

## Emulator smoke test (PXP2, 2026-09-28)

This records a functional smoke test of the CFW's own logic inside the **PXP2
PSP emulator** (the Java core in this repo, driven by `core/tests/FrameDump`
and two purpose-built harnesses). It is **not** proof the CFW works on
hardware.

### What this can and cannot prove

PXP2 emulates the MIPS CPU faithfully and runs Sony's real **user** VSH
modules, but its **kernel is HLE** (implemented in Java). Sony kernel
functions are syscall stubs, not real 6.61 MIPS code. Consequences:

- User-mode CFW parts (installer EBOOT, recovery EBOOT, the `vshctrl` VSH
  plugin) run as real guest MIPS and were exercised end to end.
- The kernel PRXs (`systemctrl.prx`, `loader.prx`) load, relocate and run
  their `module_start` / `pxp2_loader_entry` as real MIPS, but their **hooks
  target Sony kernel code PXP2 does not run**, so the hooks resolve nothing
  and install nothing (safe no-ops here). This is documented, not faked.

Test rig: `FrameDump local-test/install/flash-661-01g-v1 <storage> <out>
<frames>` on a **scratch copy** of `local-test/storage`; the retail Dragon
Ball Z ISO was used only via the scratch memory stick and never copied
elsewhere. Two harnesses (kept outside the repo, in the session scratchpad)
call the plugins' exported functions directly by running a guest thread at
each export and reading `$v0`.

### Results

| Component | Tested | Result | Evidence |
|---|---|---|---|
| **installer/EBOOT.PBP** | Boot as MS homebrew from `ms0:/PSP/GAME/PXP2CFW_INSTALL/`, press X, install | Menu shows, X installs, copies `systemctrl.prx`/`vshctrl.prx`/`loader.prx` to `ms0:/PXP2CFW/`, creates `ISO`+`SEPLUGINS`, **no flash writes** | guest stdout log; dest dir listing; IO trace shows only `ms0:` mkdir/copy, no `flash0:` write opens |
| **recovery/EBOOT.PBP** | Boot, navigate, toggle "UMD region free", Save & boot; also disable-CFW (START); reload | Menu navigates; writes `ms0:/PXP2CFW/cfw.cfg` with the toggled value; disable-CFW writes `cfw_enabled=0`; reload reads saved value back | guest stdout; `cfw.cfg` contents after each run |
| **xmbplugin/vshctrl.prx** | Load as a VSH plugin via the SEPLUGINS loader during XMB boot; scan `ms0:/ISO`; launch hand-off; XMB still boots | Loads/relocates (48 imports), resident; scanned 2 ISOs; parsed `PARAM.SFO` titles ("Dragon Ball Z Shin Budokai", "PXP2 Test Disc"); `vshctrl_launch_iso` wrote `ms0:/PXP2CFW/launch.iso` and invoked VSHDisc (gamePath `disc0:/PSP_GAME/SYSDIR/BOOT.BIN`, key `game`); XMB boots to frame 700 | harness output; `launch.iso` contents; frame00700.png (XMB rendered) |
| **systemctrl.prx** (kernel) | Load into partition 1, run `module_start`; exercise exported API + ISO open | Loads/relocates (28 imports, 17 exports, 648 relocs); `module_start` completes (exit 0): reads `cfw.cfg`, registers `umd` I/O driver, mounts the pending ISO (`iso_open` OK), scans plugins. Exports work: `get_version`=0x0001, `get_model`=0, `get_unsigned_allowed`=1, `set_iso("ms0:/ISO/DBZ.iso")`=0 (iso_open OK), `get_iso` returns it | kernel harness output |
| **loader.prx** (kernel) | Load into partition 1, run `pxp2_loader_entry` | Loads/relocates (10 imports); finds payload root, calls `sceKernelGetModel`/R-probe, then `sceKernelLoadModule` on `systemctrl.prx` — which PXP2 refuses (kernel module, HLE limit). Loader returns -2 gracefully (no crash) | kernel harness output |
| **Regressions** | Plain XMB 700-frame boot; CoreTest/PipelineTest/LibraryTest/PluginsTest | Baseline boots single-pass to frame 700 "Stopped by host"; all four tests pass | logs |

### Bugs fixed in the CFW (C sources)

1. **`installer/main.c` — payload source folder was hardcoded.** The
   installer read its payload from a fixed `<root>/PSP/GAME/PXP2CFW`,
   ignoring where the EBOOT was actually launched from. Run from any other
   folder (e.g. the tested `PXP2CFW_INSTALL`), it copied nothing ("[skip]
   … not bundled"). Fixed to derive its own directory from `argv[0]`
   (`main(int argc, char *argv[])` + `pick_srcdir`), falling back to the old
   default. Verified: from `PXP2CFW_INSTALL` it now copies the payload.

2. **`recovery/main.c` — first-run "Save" wrote to a nonexistent device.**
   When no `cfw.cfg` existed yet, `load_cfg` left `g_cfgpath` pointing at the
   `ef0:` fallback (probed last). `save_cfg` then wrote to
   `ef0:/PXP2CFW/cfw.cfg`, which exists only on the PSP Go — so on a
   1000/2000/3000/Street the very first Save silently did nothing. Fixed to
   choose the write target from where the CFW payload actually lives
   (`ms0:` by default, `ef0:` only if that is where `PXP2CFW` is). Verified:
   first-run Save now writes `ms0:/PXP2CFW/cfw.cfg`.

3. **`xmbplugin/vshctrl.c` — plugin called `sceKernelExitGame`, killing the
   VSH.** vshctrl used `main()` (per the old BUILD.md note). The PSPSDK user
   crt0 calls `sceKernelExitGame()` when `main()` returns; for a resident VSH
   plugin that tears the running VSH down. In PXP2 this produced an **endless
   VSH reboot loop** (observed: 17 000+ restarts); on hardware it would
   hang/kill the XMB. Fixed so `main()` does its one-time scan and then never
   returns (`sceKernelSleepThread` loop), staying resident with its exported
   API callable. (Defining our own `module_start` is not possible for a user
   PRX: `crt0_prx.o` already defines a non-weak one — see BUILD.md.) Verified:
   the XMB now boots single-pass to frame 700 with vshctrl loaded.

### Limits that need real hardware (or a bigger PXP2 feature)

- **Kernel hooks never take effect here.** `systemctrl` hooks
  `sceKernelStartModule` (via ModuleMgrForUser) and memlmd's decrypt **by
  NID**; on PXP2 these are HLE syscalls, not guest modules with export
  tables, so `pxp2ctrl_find_export` returns 0 and no stub is patched. The
  unsigned-code policy and module-load patching therefore cannot be verified
  in the emulator — they require a real 6.61 kernel.
- **`umd0:` I/O is not dispatched through the guest driver.** PXP2 records
  `sceIoAddDrv("umd")` (a small gap added, see below) but its HLE I/O serves
  `ms0:`/`disc0:` directly; guest opens/reads are not routed to the driver's
  `PspIoDrvFuncs`. So the ISO driver's `umd_read`/`umd_devctl 0x01f20001` and
  the UMD state machine (0x12/0x32) as reached *through the device* were not
  exercised. The image layer (`iso_open`, verified on two ISOs incl. the
  retail DBZ image) works; sector-read/CSO/devctl/state code is by inspection
  only here.
- **`loader.prx` cannot chain-load `systemctrl.prx`.** PXP2 refuses
  `sceKernelLoadModule` of a kernel-attribute module from `ms0:` (its HLE
  kernel runs user modules only). The loader's own logic is correct; the
  chain-load step needs a real kernel.
- **pspDebugScreen text is not rasterized.** The installer/recovery use
  `printf` (captured via guest stdout, fd 1) which is the authoritative
  evidence; PXP2 does not render libpspdebug's software framebuffer, so
  screenshots of those two EBOOTs are blank. Not a CFW defect.
- Everything ultimately: **nothing here has run on a real PSP.**

### PXP2 changes made to support the test (core/, minimal)

- `PrxLoader.load`: accept a static ELF (ET_EXEC) whose first program header
  is non-loadable (e.g. `PT_MIPS_ABIFLAGS`/`REGINFO`). Previously the
  "PRX first segment must load" check rejected the installer/recovery EBOOTs.
  `textSize` now derives from the first *loadable* segment.
- `HleIo`: added kernel `sceIoAddDrv`/`sceIoDelDrv` (IoFileMgrForKernel)
  stubs that record the registered driver and return success, so a driver
  PRX (like `systemctrl`) can complete `module_start`. They do **not**
  dispatch guest I/O through the driver (documented above).

### Minor notes (not bugs, not fixed)

- `README.md`/installer list `recovery.prx` in the payload, but recovery is
  built as `EBOOT.PBP`, not a `.prx`; the installer harmlessly `[skip]`s it.
- `vshctrl.c`'s header comment says it "can extract ICON0"; the current code
  reads only `PARAM.SFO` (TITLE/DISC_ID). ICON0 extraction is not
  implemented.
