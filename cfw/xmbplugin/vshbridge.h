/*
 * PXP2 CFW - sceVshBridge launch prototypes - GPL-3.0-or-later.
 *
 * SceKernelLoadExecVSHParam layout is public (PSPSDK <psploadexec_kernel.h>
 * / psdevwiki). psp.md records the exact fields the 6.61 VSH uses.
 */
#ifndef PXP2_VSHBRIDGE_H
#define PXP2_VSHBRIDGE_H

#include <pspkerneltypes.h>

struct SceKernelLoadExecVSHParam {
	SceSize size;        /* 0x30 for these paths (psp.md)               */
	SceSize args;        /* bytes of argp                               */
	void   *argp;
	const char *key;     /* "game" / "vsh"                              */
	SceSize vshmain_args_size;
	void   *vshmain_args;
	char   *configfile;
	unsigned int unk4[3];
};

/* Provided by vshbridge.S stubs. */
int sctrlKernelLoadExecVSHDisc(const char *file, struct SceKernelLoadExecVSHParam *param);
int sctrlKernelLoadExecVSHMs2 (const char *file, struct SceKernelLoadExecVSHParam *param);
int sctrlKernelExitVSHVSH     (struct SceKernelLoadExecVSHParam *param);

#endif
