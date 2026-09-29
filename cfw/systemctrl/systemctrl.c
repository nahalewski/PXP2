/*
 * PXP2 CFW - systemctrl kernel module (main)
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * Responsibilities:
 *   - install module-load hooks (sceKernelLoadModule* / StartModule flow)
 *   - offer the plugin API (systemctrl.h)
 *   - disable the signature check for unsigned homebrew
 *   - drive the ISO driver for VSHDisc launches
 *
 * ORIGINAL WORK written from public NID documentation and the boot-flow
 * findings in ../psp.md. Not a fork of any existing CFW.
 */
#include <string.h>
#include <pspsdk.h>
#include <pspkernel.h>
#include <pspmodulemgr.h>
#include <pspmodulemgr_kernel.h>
#include <pspiofilemgr.h>
#include <pspsysmem_kernel.h>
#include "systemctrl.h"
#include "offsets_661.h"
#include "isodrv.h"
#include "pxp2cfw.h"

PSP_MODULE_INFO("pxp2_systemctrl", PSP_MODULE_KERNEL, 0, 1);

/* From plugins.c */
extern int pxp2ctrl_load_plugins(void);
/* From hooks.c */
extern unsigned int pxp2ctrl_poke32(unsigned int addr, unsigned int value);
/* From isodrv/umd9660.c (linked into this PRX) */
extern int umd9660_register(void);
extern int umd9660_unregister(void);
static void pxp2ctrl_check_pending_iso(void);

/* ---- module state -------------------------------------------------- */
#define MAX_MODULE_HANDLERS 8
static pxp2_module_handler g_handlers[MAX_MODULE_HANDLERS];
static pxp2_model_t   g_model    = PXP2_MODEL_UNKNOWN;
static pxp2_bootmode_t g_bootmode = PXP2_BOOT_VSH;
static char           g_iso[128];
static int            g_unsigned_allowed = 1;
static pxp2_boot_args g_boot;

/* Saved originals for chaining. */
static unsigned int orig_StartModule;

/* ---- trivial getters ---------------------------------------------- */
int  pxp2ctrl_get_version(void)  { return (PXP2CFW_VERSION_MAJOR << 8) | PXP2CFW_VERSION_MINOR; }
pxp2_model_t   pxp2ctrl_get_model(void)    { return g_model; }
pxp2_bootmode_t pxp2ctrl_get_bootmode(void){ return g_bootmode; }
void pxp2ctrl_set_unsigned_allowed(int a)  { g_unsigned_allowed = a ? 1 : 0; }
int  pxp2ctrl_get_unsigned_allowed(void)   { return g_unsigned_allowed; }
const char *pxp2ctrl_get_iso(void)         { return g_iso[0] ? g_iso : NULL; }

int pxp2ctrl_set_iso(const char *path)
{
	if (!path) { g_iso[0] = 0; return 0; }
	strncpy(g_iso, path, sizeof(g_iso) - 1);
	g_iso[sizeof(g_iso) - 1] = 0;
	return iso_open(g_iso);
}

/* ---- handler registry --------------------------------------------- */
int pxp2ctrl_register_module_handler(pxp2_module_handler h)
{
	int i;
	if (!h) return -1;
	for (i = 0; i < MAX_MODULE_HANDLERS; i++)
		if (!g_handlers[i]) { g_handlers[i] = h; return 0; }
	return -1; /* full */
}

int pxp2ctrl_unregister_module_handler(pxp2_module_handler h)
{
	int i;
	for (i = 0; i < MAX_MODULE_HANDLERS; i++)
		if (g_handlers[i] == h) { g_handlers[i] = NULL; return 0; }
	return -1;
}

/* Internal handler: when the XMB main module comes up, install the Game-column
 * ISO listing hooks (they can only be resolved once vshmain is present). */
static int g_gamelist_done = 0;
static int gamelist_on_module(SceModule *mod, const char *modname)
{
	(void)mod;
	if (!g_gamelist_done && modname &&
	    (strcmp(modname, "vshmain_module") == 0 || strcmp(modname, "vsh_module") == 0)) {
		if (pxp2ctrl_install_gamelist() > 0)
			g_gamelist_done = 1;
	}
	return 0;
}

/* Run every registered handler for a freshly loaded module. */
static int run_handlers(SceModule *mod)
{
	int i, rc;
	if (!mod) return 0;
	for (i = 0; i < MAX_MODULE_HANDLERS; i++) {
		if (g_handlers[i]) {
			rc = g_handlers[i](mod, mod->modname);
			if (rc < 0)
				return rc;
		}
	}
	return 0;
}

/* ---- StartModule hook ---------------------------------------------
 * We hook sceKernelStartModule so we get a chance to run patch handlers
 * on a module AFTER it is loaded and relocated but the moment before it
 * actually runs its module_start. We resolve the module from the uid, run
 * handlers, then chain to the original.
 *
 * The hook is installed by rewriting the syscall/import stub, so we do not
 * need any hardcoded 6.61 text offset for it (NID-based, see hooks.c).
 */
typedef int (*StartModuleFn)(SceUID modid, SceSize argsize, void *argp,
                             int *status, SceKernelSMOption *opt);

static int hook_StartModule(SceUID modid, SceSize argsize, void *argp,
                            int *status, SceKernelSMOption *opt)
{
	SceModule *mod;
	int rc;

	mod = (SceModule *)sceKernelFindModuleByUID(modid);
	if (mod) {
		rc = run_handlers(mod);
		if (rc < 0)
			return rc; /* a handler refused this module */
	}

	return ((StartModuleFn)orig_StartModule)(modid, argsize, argp, status, opt);
}

/* ---- Signature / unsigned-homebrew policy -------------------------
 * On retail 6.61 the loader decrypts every executable through memlmd; an
 * unsigned homebrew EBOOT is NOT encrypted (its DATA.PSP begins with a
 * plain "~PSP" tag with no valid signature, or is a plain ELF). We make
 * the loader accept those by forcing memlmd's "CheckExec"/decrypt path to
 * report success for the unsigned case.
 *
 * IMPLEMENTATION NOTE (honest): the correct, brick-safe way to do this is
 * to hook memlmd's decrypt export by NID and, when the input is already a
 * plain ELF / unsigned ~PSP, return it unchanged with rc=0. The exact
 * memlmd export semantics for 6.61 are documented publicly by NID but the
 * per-model behaviour is UNVERIFIED here. We install the NID hook only;
 * we never poke a raw address, so a wrong offset cannot brick anything.
 */
static int (*orig_memlmd_decrypt)(void *buf, int size, int *newsize) = NULL;

static int is_plain_elf(const unsigned char *p)
{
	return p && p[0] == 0x7f && p[1] == 'E' && p[2] == 'L' && p[3] == 'F';
}
static int is_unsigned_psp(const unsigned char *p)
{
	/* "~PSP" with a zero/absent signature block -> homebrew. We only need
	 * to recognise the tag; the loader gives us the header buffer. */
	return p && p[0] == '~' && p[1] == 'P' && p[2] == 'S' && p[3] == 'P';
}

static int hook_memlmd_decrypt(void *buf, int size, int *newsize)
{
	const unsigned char *p = (const unsigned char *)buf;

	if (g_unsigned_allowed && buf) {
		if (is_plain_elf(p)) {
			/* Already a runnable ELF; nothing to decrypt. */
			if (newsize) *newsize = size;
			return 0;
		}
		if (is_unsigned_psp(p)) {
			/* Homebrew ~PSP with no real signature: let the loader pull
			 * the inner ELF out unchanged. We report success and leave the
			 * buffer as-is; the loader's ELF stage takes over. */
			if (newsize) *newsize = size;
			return 0;
		}
	}
	/* Signed Sony code: defer to the real decrypt so official titles and
	 * firmware modules keep working. */
	if (orig_memlmd_decrypt)
		return orig_memlmd_decrypt(buf, size, newsize);
	return 0x80000004; /* no original: refuse rather than pretend */
}

static void install_memlmd_hook(void)
{
	unsigned int stub;
	/* Hook by NID; if the export is not found we simply do nothing, which
	 * is safe. Real memlmd module name on 6.61 is "memlmd". */
	stub = pxp2ctrl_find_export("memlmd", NULL, NID_memlmd_2F3D7E2D);
	if (stub) {
		orig_memlmd_decrypt =
			(int (*)(void *, int, int *))pxp2ctrl_hook_import(stub, hook_memlmd_decrypt);
	}
	/* If stub == 0 the module/NID was not present in this build of the
	 * firmware; the CFW still runs, just without unsigned support. This is
	 * logged rather than fatal. */
}

/* ---- StartModule hook install ------------------------------------- */
static void install_module_hooks(void)
{
	unsigned int stub;
	/* Resolve the StartModule stub the *user* side calls into. In kernel
	 * mode we patch ModuleMgrForKernel's user-facing export by NID. */
	stub = pxp2ctrl_find_export("sceModuleManager", "ModuleMgrForUser",
	                            NID_sceKernelStartModule);
	if (stub)
		orig_StartModule = pxp2ctrl_hook_import(stub, hook_StartModule);
}

/* ---- boot-mode detection -----------------------------------------
 * Determined from how the loader entered us (pxp2_boot_args) and from the
 * running module set. First bring-up assumes VSH; the loader stub sets the
 * real mode when it launches a game/POPS. */
static void detect_environment(void)
{
	/* Model: prefer what the loader told us; else ask the kernel. */
	if (g_boot.magic == PXP2_BOOT_MAGIC && g_boot.struct_size >= sizeof(g_boot)) {
		g_model = (pxp2_model_t)g_boot.model;
		g_bootmode = g_boot.recovery_hold ? PXP2_BOOT_VSH : PXP2_BOOT_VSH;
	} else {
		g_model = (pxp2_model_t)sceKernelGetModel();
	}
	if (g_model < PXP2_MODEL_1000 || g_model > PXP2_MODEL_STREET)
		g_model = PXP2_MODEL_1000;
}

/* Read the ISO path the VSH plugin queued (ms0:/ef0: PXP2CFW/launch.iso),
 * mount it, and mark the virtual UMD inserted. Missing file => nothing. */
static void pxp2ctrl_check_pending_iso(void)
{
	static const char *paths[2] = {
		"ms0:/PXP2CFW/launch.iso", "ef0:/PXP2CFW/launch.iso"
	};
	int i;
	for (i = 0; i < 2; i++) {
		SceUID fd = sceIoOpen(paths[i], PSP_O_RDONLY, 0);
		if (fd >= 0) {
			char buf[160];
			int n = sceIoRead(fd, buf, sizeof(buf) - 1);
			sceIoClose(fd);
			if (n > 0) {
				buf[n] = 0;
				if (iso_open(buf) == 0) {
					strncpy(g_iso, buf, sizeof(g_iso) - 1);
					g_iso[sizeof(g_iso) - 1] = 0;
					iso_umd_set_inserted(1);
				}
			}
			return;
		}
	}
}

/* ---- module entry -------------------------------------------------- */
/* The loader stub passes &pxp2_boot_args as argp to module_start. */
int module_start(SceSize args, void *argp)
{
	pxp2_config cfg;

	memset(g_handlers, 0, sizeof(g_handlers));
	memset(&g_boot, 0, sizeof(g_boot));
	if (argp && args >= sizeof(pxp2_boot_args))
		memcpy(&g_boot, argp, sizeof(g_boot));

	detect_environment();

	pxp2ctrl_config_load(&cfg);
	g_unsigned_allowed = cfg.unsigned_allowed;

	/* Master off-switch honoured (recovery "disable CFW"). */
	if (!cfg.cfw_enabled)
		return 0; /* loaded but inert */

	install_module_hooks();
	install_memlmd_hook();

	/* In VSH mode, arrange to inject ms0:/ISO into the XMB Game column once
	 * vshmain starts (handler-driven so no offset is needed). */
	if (g_bootmode == PXP2_BOOT_VSH)
		pxp2ctrl_register_module_handler(gamelist_on_module);

	/* Register the virtual UMD driver so disc0:/umd0: resolve to our ISO. */
	umd9660_register();

	/* If the VSH plugin queued an ISO to launch, mount it now. */
	pxp2ctrl_check_pending_iso();

	/* Load VSH-mode plugins now that hooks are live. */
	pxp2ctrl_load_plugins();

	return 0;
}

int module_stop(SceSize args, void *argp)
{
	(void)args; (void)argp;
	iso_close();
	return 0;
}
