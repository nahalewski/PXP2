/*
 * PXP2 test plugin "modhandler" (VSH and GAME, kernel mode) - original code, GPL-3.0-or-later.
 *
 * - Registers a start-module handler (sctrlHENSetStartModuleHandler, chained to the previous one) that prints every
 *   module starting after it, and checks that sceKernelFindModuleByName (6.60 NID) returns the same control block.
 * - Hooks sceDisplayGetVcount by patching the function's first instructions (MAKE_JUMP style) with a
 *   trampoline for the original, then reports the call count; this checks that directly patched code runs
 *   (no stale decoded instructions in the emulator).
 * - Resolves an export of a real guest module with sctrlHENFindFunction when it starts.
 */
#include <pspsdk.h>
#include <pspkernel.h>
#include <pspkdebug.h>
#include <psploadcore.h>
#include <systemctrl.h>
#include <string.h>

PSP_MODULE_INFO("PXP2_ModHandler", 0x1000, 1, 0);

#define J(target) (0x08000000 | (((unsigned int)(target) >> 2) & 0x03ffffff))

static STMOD_HANDLER previous;
static unsigned int trampoline[4];
static unsigned int vcount_calls;
static int started_modules;

static int hook_get_vcount(void)
{
	unsigned int n = ++vcount_calls;
	if (n == 1 || n == 300) Kprintf("modhandler: patched sceDisplayGetVcount called %u times\n", n);
	return ((int (*)(void))trampoline)();
}

static int on_module_start(SceModule *mod)
{
	SceModule *found = sceKernelFindModuleByName(mod->modname);
	started_modules++;
	Kprintf("modhandler: start #%d %s uid %08x text %08x size %x, FindModuleByName %s\n", started_modules, mod->modname,
		mod->modid, (unsigned int)mod->text_addr, (unsigned int)mod->text_size, found == mod ? "matches" : "DIFFERS");
	if (strcmp(mod->modname, "vsh_module") == 0) {
		unsigned int a = sctrlHENFindFunction("sceATRAC3plus_Library", "sceAtrac3plus", 0x780F88D1); /* sceAtracGetAtracID */
		Kprintf("modhandler: sceATRAC3plus_Library sceAtracGetAtracID export at %08x\n", a);
	}
	return previous ? previous(mod) : 0;
}

int module_start(SceSize args, void *argp)
{
	Kprintf("modhandler: SceModule offsets modname %d modid %d text_addr %d text_size %d\n",
		(int)offsetof(SceModule, modname), (int)offsetof(SceModule, modid), (int)offsetof(SceModule, text_addr), (int)offsetof(SceModule, text_size));
	previous = sctrlHENSetStartModuleHandler(on_module_start);
	unsigned int addr = sctrlHENFindFunction("sceDisplay_Service", "sceDisplay", 0x9C6EAAD7);
	if (addr) {
		unsigned int *code = (unsigned int *)addr;
		trampoline[0] = code[0]; trampoline[1] = code[1];      /* the original first two instructions */
		trampoline[2] = J(addr + 8); trampoline[3] = 0;        /* continue in the original function */
		code[0] = J(hook_get_vcount); code[1] = 0;            /* j hook; nop */
		sceKernelDcacheWritebackAll(); sceKernelIcacheInvalidateAll();
	}
	Kprintf("modhandler: handler installed (previous %08x), sceDisplayGetVcount at %08x patched\n", (unsigned int)previous, addr);
	return 0;
}

int module_stop(SceSize args, void *argp)
{
	return 0;
}
