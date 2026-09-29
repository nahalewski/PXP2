/*
 * PXP2 CFW - 6.61 per-model NID / offset table
 *
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * =====================================================================
 *  VERIFICATION STATUS  --  READ THIS BEFORE TRUSTING ANY NUMBER HERE
 * =====================================================================
 * We CANNOT test on real hardware in this environment. Every entry is
 * tagged with a status:
 *
 *   VERIFIED_DOC : a NID or a documented constant taken from a public
 *                  source (PSPSDK headers, psdevwiki, uOFW-style docs)
 *                  or reverse-engineered by the PXP2 emulator against
 *                  the real 6.61 firmware and recorded in ../psp.md.
 *                  These are NIDs and behaviours, which are stable and
 *                  do not depend on where a byte sits in flash.
 *
 *   UNVERIFIED   : a concrete text ADDRESS / OFFSET inside a specific
 *                  6.61 module that we did NOT derive here. These are
 *                  placeholders (0) and MUST be filled in from a real
 *                  6.61 dump before the CFW can hook by address. Hooking
 *                  by NID (see systemctrl) avoids most of these.
 *
 * The whole design prefers NID-based hooking precisely so that the
 * UNVERIFIED address table can stay empty on first bring-up.
 * =====================================================================
 */
#ifndef PXP2_OFFSETS_661_H
#define PXP2_OFFSETS_661_H

#include "pxp2cfw.h"

/* ---- NIDs (VERIFIED_DOC: public / from psp.md emulator RE) ---------- */
/* LoadExec kernel variants - psp.md section "LoadExec variants".        */
#define NID_LoadExecVSHDisc   0xD8320A28u  /* VERIFIED_DOC (psp.md)       */
#define NID_LoadExecVSHMs2    0xD940C83Cu  /* VERIFIED_DOC (psp.md)       */
#define NID_ExitVSHVSH        0x08F7166Cu  /* VERIFIED_DOC (psp.md)       */

/* ModuleMgr load/start - names are the public PSPSDK NIDs.              */
#define NID_sceKernelLoadModule        0x977DE386u /* VERIFIED_DOC        */
#define NID_sceKernelLoadModuleByID    0xB7F46618u /* VERIFIED_DOC        */
#define NID_sceKernelStartModule       0x50F0C1ECu /* VERIFIED_DOC        */
#define NID_sceKernelLoadModuleBufferUsb 0xF9275D98u /* VERIFIED_DOC      */
/* Kernel-mode module load used by the VSH (psp.md a5628f0d wrapper).    */
#define NID_vshKernelLoadModuleVSH     0xA5628F0Du /* VERIFIED_DOC        */

/* memlmd / signature path NIDs (psdevwiki). Used to neuter the sign
 * check for unsigned homebrew. NIDs are stable; the module is memlmd.   */
#define NID_memlmd_9D36A439            0x9D36A439u /* VERIFIED_DOC: CheckExec */
#define NID_memlmd_2F3D7E2D            0x2F3D7E2Du /* VERIFIED_DOC: decrypt   */
#define NID_sceMesgLed_hint            0x00000000u /* UNVERIFIED: see note    */

/* UMD / disc check constants (VERIFIED_DOC, psp.md "UMD drive") live in
 * include/isodrv.h so the driver can use them without the NID table. */

/* CheckRegion bridge target (psp.md 5c2983c2 -> sceChkreg 54495b19).    */
#define NID_sceChkregCheckRegion 0x54495B19u /* VERIFIED_DOC             */

/* ---- Per-model text addresses inside 6.61 modules ------------------- */
/* ALL UNVERIFIED. Filled with 0 deliberately. A value of 0 means "hook
 * by NID instead / not yet derived". Do not ship non-zero values here
 * unless they came from a real 6.61 dump for that exact model.         */
typedef struct {
	pxp2_model_t model;
	const char  *label;

	/* sceLoadExec / loadcore text hooks (0 => use NID hooking) */
	unsigned int loadcore_start_module;   /* UNVERIFIED */
	unsigned int loadexec_disc;           /* UNVERIFIED */
	unsigned int loadexec_ms2;            /* UNVERIFIED */

	/* memlmd decrypt entry, for the ~PSP no-decrypt path */
	unsigned int memlmd_decrypt;          /* UNVERIFIED */
	unsigned int memlmd_sigcheck;         /* UNVERIFIED */

	/* vshmain disc-check site (psp.md 08a1ac40 was the *emulator* HLE
	 * address; the retail text address per model is UNVERIFIED). */
	unsigned int vshmain_disc_check;      /* UNVERIFIED */
} pxp2_model_offsets;

/* One row per supported model. NID hooking works with these all zero.  */
extern const pxp2_model_offsets pxp2_offsets_661[5];

/* Look up the row for a model; never returns NULL (falls back to 1000). */
const pxp2_model_offsets *pxp2_offsets_for(pxp2_model_t model);

#endif /* PXP2_OFFSETS_661_H */
