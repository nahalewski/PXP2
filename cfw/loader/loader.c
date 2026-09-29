/*
 * PXP2 CFW - loader / entry stub
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * ENTRY CONTRACT (see DESIGN.md "Entry"):
 *   A user-supplied 6.61 kernel exploit (an Infinity-2 compatible hook
 *   point or a HEN launcher) transfers control to pxp2_loader_entry() in
 *   KERNEL mode, with interrupts enabled and a valid thread context. We do
 *   NOT ship or bundle any exploit or any Sony firmware file.
 *
 *   pxp2_loader_entry() must:
 *     - be reached in kernel mode (module attribute 0x1000),
 *     - be safe to call exactly once, early, before the VSH is up,
 *     - return 0 on success; the exploit then lets the VSH continue to boot
 *       with our hooks installed.
 *
 * The loader:
 *   1. detects the model and whether we booted from ef0: (Go),
 *   2. builds pxp2_boot_args,
 *   3. loads and starts PXP2CFW/systemctrl.prx in kernel mode,
 *   4. if R is held, chain-loads PXP2CFW/recovery.prx first.
 *
 * PERSISTENCE is intentionally out of scope for this build: nothing is
 * written to flash0. The CFW is volatile and re-applied at each boot by the
 * exploit. This is the brick-safe HEN model.
 */
#include <string.h>
#include <pspkernel.h>
#include <pspmodulemgr.h>
#include <pspctrl.h>
#include <pspiofilemgr.h>
#include <pspsysmem_kernel.h>
#include "pxp2cfw.h"

PSP_MODULE_INFO("pxp2_loader", PSP_MODULE_KERNEL, 0, 1);

static pxp2_boot_args g_args;

/* Return the storage root that actually holds our payload. Prefer ms0:,
 * fall back to ef0: (Go). */
static const char *find_root(int *from_ef0)
{
	SceUID fd;
	*from_ef0 = 0;
	fd = sceIoOpen(PXP2_MS_ROOT "/PXP2CFW/systemctrl.prx", PSP_O_RDONLY, 0);
	if (fd >= 0) { sceIoClose(fd); return PXP2_MS_ROOT "/PXP2CFW"; }
	fd = sceIoOpen(PXP2_EF_ROOT "/PXP2CFW/systemctrl.prx", PSP_O_RDONLY, 0);
	if (fd >= 0) { sceIoClose(fd); *from_ef0 = 1; return PXP2_EF_ROOT "/PXP2CFW"; }
	return NULL;
}

static int r_is_held(void)
{
	SceCtrlData pad;
	sceCtrlSetSamplingCycle(0);
	sceCtrlSetSamplingMode(PSP_CTRL_MODE_DIGITAL);
	if (sceCtrlPeekBufferPositive(&pad, 1) > 0)
		return (pad.Buttons & PSP_CTRL_RTRIGGER) ? 1 : 0;
	return 0;
}

/* Load + start a kernel PRX from an absolute path. Returns modid or <0. */
static SceUID start_kernel_prx(const char *path, SceSize argsz, void *argp)
{
	SceUID mod;
	int status = 0;
	mod = sceKernelLoadModule(path, 0, NULL);
	if (mod < 0)
		return mod;
	if (sceKernelStartModule(mod, argsz, argp, &status, NULL) < 0)
		return -1;
	return mod;
}

int pxp2_loader_entry(void)
{
	const char *root;
	int from_ef0 = 0;
	char path[96];

	root = find_root(&from_ef0);
	if (!root)
		return -1; /* payload not present; do nothing (safe) */

	memset(&g_args, 0, sizeof(g_args));
	g_args.magic       = PXP2_BOOT_MAGIC;
	g_args.struct_size = sizeof(g_args);
	g_args.model       = sceKernelGetModel();
	g_args.fw_version  = 0x06060010; /* 6.61 marker (documented BCD-ish) */
	g_args.boot_from_ef0 = from_ef0;
	g_args.recovery_hold = r_is_held();
	strncpy(g_args.cfw_root, root, sizeof(g_args.cfw_root) - 1);

	/* Recovery (R held) is a normal homebrew EBOOT the user launches from
	 * the XMB, or that systemctrl can chain to; the loader only records the
	 * held state in the boot args. We do NOT start a user app from kernel
	 * context here. */

	strcpy(path, root);
	strcat(path, "/systemctrl.prx");
	if (start_kernel_prx(path, sizeof(g_args), &g_args) < 0)
		return -2;

	return 0;
}

int module_start(SceSize args, void *argp)
{
	(void)args; (void)argp;
	/* When loaded as a module by a HEN launcher, run the entry immediately. */
	return pxp2_loader_entry();
}

int module_stop(SceSize args, void *argp)
{
	(void)args; (void)argp;
	return 0;
}
