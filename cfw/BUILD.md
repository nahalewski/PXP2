# Building PXP2 CFW

PXP2 CFW is written in C to PSPSDK conventions and builds with the `pspdev`
toolchain (`psp-gcc`). It has been compiled cleanly against:

- psp-gcc (GCC) 15.2.0
- PSPSDK at `/home/ben/pspdev/psp/sdk` (WSL Ubuntu)

All five products build with no warnings or errors. **Nothing here has been
run on real PSP hardware** — see the "Unverified" sections in `DESIGN.md`.

## Prerequisites

- The `pspdev` toolchain on `PATH`, with `PSPDEV` set. On this machine it
  lives in WSL Ubuntu:
  ```sh
  export PSPDEV=$HOME/pspdev
  export PATH=$PATH:$PSPDEV/bin
  ```
- `zlib` for PSP (ships with pspdev as `libz.a`) — used by the CSO path.

## Build (from Git Bash on Windows, driving WSL)

Git Bash expands `$PATH` before WSL sees it, so put the build in a script and
run the script under WSL:

```sh
MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/Users/Ben/Desktop/pxp2/cfw/build.sh
```

`build.sh` exports `PSPDEV`/`PATH` inside WSL, runs `make clean && make all`,
and lists the products.

## Build (directly in a pspdev shell)

```sh
cd cfw
make            # builds every module
make clean
```

## Products

| Path | Type | Mode | Notes |
|---|---|---|---|
| `systemctrl/systemctrl.prx` | PRX | kernel | includes the ISO/UMD driver + kalloc |
| `loader/loader.prx`         | PRX | kernel | entry stub the exploit calls |
| `xmbplugin/vshctrl.prx`     | PRX | user   | VSH plugin: ISO list + launch |
| `recovery/EBOOT.PBP`        | PBP | user   | recovery menu homebrew |
| `installer/EBOOT.PBP`       | PBP | user   | the "update" installer |

## Layout of the build

- `systemctrl/Makefile` compiles the `isodrv/*.c` objects directly into
  `systemctrl.prx` (they share one kernel address space and one heap), so
  there is no separate `isodrv.prx`.
- Kernel PRXs use `USE_KERNEL_LIBS=1 USE_KERNEL_LIBC=1` and are built with
  `-fno-builtin` (see "Toolchain notes" below).
- User EBOOTs are packed with `mksfoex` + `pack-pbp` by `build.mak`.

## Toolchain notes / fixes made during bring-up

These were the real compiler/linker issues fixed so the tree builds:

1. **`SceModule2` does not exist in PSPSDK.** That is a third-party CFW type.
   We use the public `SceModule` from `<psploadcore.h>` and include that
   header before `<pspmodulemgr_kernel.h>` (which references `SceModule`).
2. **Kernel libc has no `malloc`/`free`/`calloc`/`realloc`.** They are needed
   by our CSO index allocation and by `libz`. `isodrv/kalloc.c` provides them
   on top of `sceKernelAllocPartitionMemory` (kernel partition).
3. **No 64-bit division (`__udivdi3`) in the kernel link.** ISO images use
   power-of-two sector/block sizes, so `iso.c` divides by shifting
   (`>> 11` for 2048, `>> log2(block_size)` for CSO blocks).
4. **Kernel libc has no `sscanf`.** `config.c` parses integers by hand.
5. **`-fno-builtin`** on the kernel module stops GCC turning `strcat` into
   `stpcpy` and emitting other builtins the kernel libc does not export.
6. **User plugins must not define `module_start`.** The PSPSDK user crt0
   already defines it (calling `main`). `vshctrl` therefore defines `main()`
   and exports its API through `exports.exp`; kernel PRXs keep their own
   `module_start`/`module_stop` because the kernel crt0 does not clash.
7. **`sceKernelGetModel()` is kernel-only.** The user-mode installer detects
   the Go by probing `ef0:` instead of calling it.

## After building

Copy the products into the installer's payload folder and run the installer
on the PSP (see `README.md` → "Install flow"). Then follow the test plan in
`DESIGN.md` in the given order, starting with the safest step.
