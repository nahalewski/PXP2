/*
 * PXP2 CFW - systemctrl public API (for plugins and other CFW modules)
 *
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later. See pxp2cfw.h header.
 *
 * ORIGINAL WORK written from public NID/format documentation. The function
 * names below are our own; where a name matches conventional CFW naming it
 * is because the concept (a module-load hook, a plugin table) is inherent to
 * the task, not because any code was copied.
 */
#ifndef PXP2_SYSTEMCTRL_H
#define PXP2_SYSTEMCTRL_H

#include <pspkerneltypes.h>
#include <psploadcore.h>
#include "pxp2cfw.h"

/* Boot mode the CFW is currently running the guest in. Chosen so a plugin
 * can ask "am I in the VSH, a game, or POPS?" the way seplugins expect. */
typedef enum {
	PXP2_BOOT_VSH  = 0,
	PXP2_BOOT_GAME = 1,
	PXP2_BOOT_POPS = 2,
	PXP2_BOOT_UPDATER = 3
} pxp2_bootmode_t;

/* --- Version / model ------------------------------------------------ */
int  pxp2ctrl_get_version(void);                 /* packed major<<8|minor */
pxp2_model_t pxp2ctrl_get_model(void);
pxp2_bootmode_t pxp2ctrl_get_bootmode(void);

/* --- Module patch callback -----------------------------------------
 * Registered by plugins that want to see every module as it is started,
 * so they can patch it. Called AFTER relocation, BEFORE module_start, in
 * the context of the loading thread. Return 0 to allow the start, <0 to
 * refuse (systemctrl will fail the load). modname is the SceModule name. */
typedef int (*pxp2_module_handler)(SceModule *mod, const char *modname);
int pxp2ctrl_register_module_handler(pxp2_module_handler h);
int pxp2ctrl_unregister_module_handler(pxp2_module_handler h);

/* --- Text patching helpers (kernel) --------------------------------- */
/* Find a loaded module by name; returns NULL if not present. */
SceModule *pxp2ctrl_find_module(const char *name);
/* Resolve an imported/exported stub by (module, library, nid). Returns the
 * address of the stub's jump target, or 0. Used to hook by NID. */
unsigned int pxp2ctrl_find_export(const char *modname, const char *libname, unsigned int nid);
/* Resolve a module's IMPORT stub for (library, nid): the address of the
 * 2-instruction jump stub inside that module which calls the given import.
 * Rewriting it with pxp2ctrl_hook_import redirects only that module's calls
 * (offset-free; walks the public stub table). Returns 0 if not found. */
unsigned int pxp2ctrl_find_import(const char *modname, const char *libname, unsigned int nid);
/* Overwrite a J-type stub at 'stub' to jump to 'func'. Flushes caches.
 * Returns the original target so the hook can chain. */
unsigned int pxp2ctrl_hook_import(unsigned int stub, void *func);

/* --- Unsigned-code policy ------------------------------------------- */
/* When enabled, systemctrl forces the loader down the "~PSP no-decrypt"
 * path for unsigned homebrew executables and skips signature enforcement
 * in the memlmd / sceMesgLed check. See DESIGN.md "Signature checks". */
void pxp2ctrl_set_unsigned_allowed(int allow);
int  pxp2ctrl_get_unsigned_allowed(void);

/* --- ISO driver bridge --------------------------------------------- */
/* systemctrl calls this to tell the ISO driver which image to serve as
 * the virtual UMD before a VSHDisc launch. path is e.g. ms0:/ISO/x.iso. */
int pxp2ctrl_set_iso(const char *path);
const char *pxp2ctrl_get_iso(void);

/* --- Config ---------------------------------------------------------
 * The single shared config the recovery menu edits. Kept small and flat. */
typedef struct {
	int   cfw_enabled;       /* master switch (recovery can clear it)      */
	int   unsigned_allowed;  /* mirror of the policy above                 */
	int   plugins_vsh;       /* load ms0:/SEPLUGINS/vsh.txt in VSH mode    */
	int   plugins_game;      /* load game.txt in game mode                 */
	int   plugins_pops;      /* load pops.txt in POPS mode                 */
	int   umd_region_free;   /* force CheckRegion=1 (as psp.md 5c2983c2)   */
	int   hide_cfw_in_dnas;  /* reserved                                   */
	int   fake_umd_iso;      /* present ISO as UMD via the state machine   */
	char  iso_default[128];  /* last-launched ISO, for convenience         */
} pxp2_config;

int  pxp2ctrl_config_load(pxp2_config *out);   /* from PXP2_CFG_PATH      */
int  pxp2ctrl_config_save(const pxp2_config *in);
const pxp2_config *pxp2ctrl_config_current(void);

/* --- XMB Game-column listing (gamelist.c) ---------------------------
 * Injects the ISOs from ms0:/ISO into the XMB Game -> Memory Stick column
 * as virtual game folders, by NID-hooking vshmain's IoFileMgr imports. See
 * gamelist.c and DESIGN.md section 5. Returns the number of hooks installed
 * (0 if vshmain or the needed imports were not found - non-fatal). */
int pxp2ctrl_install_gamelist(void);

#endif /* PXP2_SYSTEMCTRL_H */
