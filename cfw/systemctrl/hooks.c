/*
 * PXP2 CFW - kernel patch / hook primitives
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * ORIGINAL WORK. MIPS stub rewriting and cache flushing are standard,
 * documented operations; the code here was written directly from the
 * MIPS ISA and the PSPSDK cache intrinsics, not copied from any CFW.
 */
#include <string.h>
#include <pspsdk.h>
#include <pspkerneltypes.h>
#include <psploadcore.h>
#include <pspmodulemgr_kernel.h>
#include <pspsysmem_kernel.h>
#include "systemctrl.h"

/* Cache maintenance. On the Allegrex a self-modified instruction must be
 * written back from D-cache and the I-cache line invalidated. PSPSDK
 * exposes these; declare them in case the headers in use do not. */
extern void sceKernelDcacheWritebackRange(const void *p, unsigned int n);
extern void sceKernelIcacheInvalidateRange(const void *p, unsigned int n);
extern void sceKernelIcacheClearAll(void);

/* Build a MIPS "j target" instruction (opcode 2). Target must be in the
 * same 256MB region as the caller (always true within kernel text). */
static unsigned int make_j(unsigned int target)
{
	return 0x08000000u | ((target >> 2) & 0x03FFFFFFu);
}

/* Decode the target of a "j" (or "jal") instruction word. */
static unsigned int decode_j_target(unsigned int instr, unsigned int at_addr)
{
	unsigned int top = at_addr & 0xF0000000u;
	return top | ((instr & 0x03FFFFFFu) << 2);
}

/*
 * Overwrite the two-instruction import stub at 'stub' so that it jumps to
 * 'func'. A PSP import stub is:
 *      j   real_func        ; word 0
 *      nop                  ; word 1 (delay slot; may be 'sll zero')
 * We replace word 0 with "j func" and leave the delay slot. Returns the
 * ORIGINAL jump target so the hook can call through.
 */
unsigned int pxp2ctrl_hook_import(unsigned int stub, void *func)
{
	volatile unsigned int *p = (volatile unsigned int *)stub;
	unsigned int orig_instr, orig_target, k1;

	if (!stub || !func)
		return 0;

	/* Access kernel memory with interrupts disabled to avoid a torn write
	 * being executed by another thread mid-patch. */
	k1 = pspSdkSetK1(0);

	orig_instr  = p[0];
	orig_target = decode_j_target(orig_instr, stub);

	p[0] = make_j((unsigned int)func);
	/* Keep the existing delay-slot instruction (p[1]) untouched. */

	sceKernelDcacheWritebackRange((void *)stub, 8);
	sceKernelIcacheInvalidateRange((void *)stub, 8);

	pspSdkSetK1(k1);
	return orig_target;
}

/* Patch a single 32-bit word in kernel text (e.g. NOP out a branch). */
unsigned int pxp2ctrl_poke32(unsigned int addr, unsigned int value)
{
	volatile unsigned int *p = (volatile unsigned int *)addr;
	unsigned int old, k1;
	if (!addr) return 0;
	k1 = pspSdkSetK1(0);
	old = *p;
	*p = value;
	sceKernelDcacheWritebackRange((void *)addr, 4);
	sceKernelIcacheInvalidateRange((void *)addr, 4);
	pspSdkSetK1(k1);
	return old;
}

/* ---- module / export discovery ------------------------------------- */

SceModule *pxp2ctrl_find_module(const char *name)
{
	return (SceModule *)sceKernelFindModuleByName((char *)name);
}

/*
 * Walk a module's export table to find the stub-jump target for a given
 * (library, nid). The SceModule ent_top/ent_size describe the export
 * descriptor list; each descriptor has a name pointer, flags, counts and
 * a table of NIDs followed by a table of addresses. This layout is public
 * (PSPSDK <pspmodulemgr.h> / psdevwiki "PRX export table").
 */
unsigned int pxp2ctrl_find_export(const char *modname, const char *libname,
                                  unsigned int nid)
{
	SceModule *mod = pxp2ctrl_find_module(modname);
	unsigned int addr = 0, k1;
	void *ent, *end;

	if (!mod)
		return 0;

	k1 = pspSdkSetK1(0);
	ent = mod->ent_top;
	end = (void *)((unsigned int)mod->ent_top + mod->ent_size);

	while (ent < end) {
		/* Export descriptor: name(4), flags(4), counts(4). */
		struct {
			const char *name;
			unsigned int flags;
			unsigned short var_count_unused;
			unsigned char  fcount_lo;
			unsigned char  vcount;
		} *d = ent;
		unsigned int total, i;
		unsigned int *nids;
		unsigned int *addrs;
		/* The counts word packs the number of functions and variables.
		 * Recover them the documented way. */
		unsigned int counts = *((unsigned int *)((char *)ent + 8));
		unsigned int fcount = (counts >> 16) & 0xFFFF;
		unsigned int vcount = counts & 0xFFFF;
		unsigned int entlen = (*((unsigned char *)((char *)ent + 6))); /* size in words */
		if (entlen == 0) entlen = 4;

		total = fcount + vcount;
		nids  = (unsigned int *)(*((unsigned int *)((char *)ent + 12)));
		addrs = nids + total;

		if (!libname || (d->name && strcmp(d->name, libname) == 0)) {
			for (i = 0; i < fcount; i++) {
				if (nids[i] == nid) {
					addr = addrs[i];
					goto done;
				}
			}
		}
		ent = (void *)((char *)ent + entlen * 4);
		(void)d;
	}
done:
	pspSdkSetK1(k1);
	return addr;
}

/*
 * Walk a module's IMPORT (stub) table to find the 2-instruction jump stub
 * that calls a given (library, nid). Returns the stub address so it can be
 * rewritten with pxp2ctrl_hook_import; this hooks only that module's calls,
 * without any hardcoded text offset.
 *
 * The import-stub-table entry layout is public (psdevwiki "PRX import
 * table"): name(4), version(2), flags(2), len_words(1), vcount(1),
 * stub_count(2), nid_table(4), jump_table(4). Each imported function has a
 * 2-instruction stub at jump_table[i*2].
 */
unsigned int pxp2ctrl_find_import(const char *modname, const char *libname,
                                  unsigned int nid)
{
	SceModule *mod = pxp2ctrl_find_module(modname);
	unsigned int addr = 0, k1;
	char *ent, *end;

	if (!mod || !mod->stub_top)
		return 0;

	k1 = pspSdkSetK1(0);
	ent = (char *)mod->stub_top;
	end = ent + mod->stub_size;

	while (ent + 20 <= end) {
		const char  *name       = *(const char **)(ent + 0);
		unsigned int len_words  = *((unsigned char *)(ent + 8));
		unsigned int stub_count = *((unsigned short *)(ent + 10));
		unsigned int *nids      = *(unsigned int **)(ent + 12);
		unsigned int *jumps     = *(unsigned int **)(ent + 16);
		unsigned int i;

		if (len_words == 0) len_words = 5;

		if ((!libname || (name && strcmp(name, libname) == 0)) && nids && jumps) {
			for (i = 0; i < stub_count; i++) {
				if (nids[i] == nid) {
					addr = (unsigned int)&jumps[i * 2];
					goto done_imp;
				}
			}
		}
		ent += len_words * 4;
	}
done_imp:
	pspSdkSetK1(k1);
	return addr;
}
