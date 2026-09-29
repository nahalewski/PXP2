/*
 * PXP2 CFW - shared definitions
 *
 * Copyright (C) 2026 PXP2 project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * ORIGINAL WORK. This code was written from scratch against public format
 * and NID documentation (psdevwiki, uOFW-style docs, the PSPSDK headers).
 * It does not copy or fork ARK-4, PRO, LME, ME, M33 or PPSSPP source.
 */
#ifndef PXP2CFW_H
#define PXP2CFW_H

#include <pspsdk.h>
#include <pspkerneltypes.h>

#define PXP2CFW_VERSION_MAJOR 0
#define PXP2CFW_VERSION_MINOR 1
#define PXP2CFW_VERSION_STR   "PXP2 CFW 0.1 (from-scratch, UNVERIFIED on hardware)"

/* ------------------------------------------------------------------ */
/* PSP hardware model identifiers.                                     */
/* Returned by sceKernelGetModel() / vshKernelGetModel(). The mapping  */
/* below is the public, well-documented one (psdevwiki "PSP models").  */
/* ------------------------------------------------------------------ */
typedef enum {
	PXP2_MODEL_1000 = 0,   /* PSP-1000 "phat"        */
	PXP2_MODEL_2000 = 1,   /* PSP-2000 "slim"        */
	PXP2_MODEL_3000 = 2,   /* PSP-3000 "brite"       */
	PXP2_MODEL_4000 = 3,   /* PSP-N1000 "Go"         */
	PXP2_MODEL_STREET = 4, /* PSP-E1000 "Street"     */
	PXP2_MODEL_UNKNOWN = -1
} pxp2_model_t;

/* The Go is the only model with internal storage ef0:. Everything     */
/* else is ms0: only. The Street (E1000) has no Wi-Fi.                  */
static inline int pxp2_model_has_ef0(pxp2_model_t m) { return m == PXP2_MODEL_4000; }

/* Primary and (Go) secondary storage roots. */
#define PXP2_MS_ROOT   "ms0:"
#define PXP2_EF_ROOT   "ef0:"

/* Layout on the Memory Stick (mirrored on ef0: for the Go). */
#define PXP2_DIR_ISO        "/ISO"
#define PXP2_DIR_SEPLUGINS  "/SEPLUGINS"
#define PXP2_DIR_CFW        "/PXP2CFW"          /* installed CFW payload   */
#define PXP2_CFG_PATH       "/PXP2CFW/cfw.cfg"  /* recovery/config file    */

/* ------------------------------------------------------------------ */
/* Boot / entry contract (see DESIGN.md "Entry").                     */
/* The user-supplied 6.61 kernel exploit (Infinity-2 hook point or a  */
/* HEN launcher) transfers control to the CFW loader stub in KERNEL   */
/* mode. We do NOT ship the exploit. The loader then starts           */
/* systemctrl.prx. This struct is the hand-off block the loader fills. */
/* ------------------------------------------------------------------ */
typedef struct {
	unsigned int  magic;        /* PXP2_BOOT_MAGIC                         */
	unsigned int  struct_size;  /* sizeof(pxp2_boot_args)                  */
	int           model;        /* pxp2_model_t, as read by the loader     */
	unsigned int  fw_version;   /* 0x06060010 for 6.61 (BCD-ish, see doc)  */
	char          cfw_root[64]; /* e.g. "ms0:/PXP2CFW" or "ef0:/PXP2CFW"   */
	unsigned int  boot_from_ef0;/* 1 on Go when booted off internal store  */
	unsigned int  recovery_hold;/* 1 if R was held at boot                 */
	unsigned int  reserved[8];
} pxp2_boot_args;

#define PXP2_BOOT_MAGIC 0x50583243u /* 'PX2C' */

#endif /* PXP2CFW_H */
