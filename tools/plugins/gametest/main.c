/*
 * PXP2 test plugin "gametest" (GAME, kernel mode) - original code, GPL-3.0-or-later.
 *
 * Prints through Kprintf what a game plugin typically asks the CFW and the kernel: HEN/SE versions, the model, the
 * boot device and API type, the SE config, and the running game's PARAM.SFO (sctrlGetInitPARAM). A thread then
 * reports three times, two seconds apart, which shows the plugin keeps running next to the game.
 */
#include <pspsdk.h>
#include <pspkernel.h>
#include <pspkdebug.h>
#include <pspinit.h>
#include <pspsysmem_kernel.h>
#include <systemctrl.h>
#include <systemctrl_se.h>
#include <string.h>

PSP_MODULE_INFO("PXP2_GameTest", 0x1000, 1, 0);

static int ticker(SceSize args, void *argp)
{
	for (int i = 1; i <= 3; i++) {
		sceKernelDelayThread(2 * 1000 * 1000);
		Kprintf("gametest: tick %d, system time %u us\n", i, (unsigned int)sceKernelGetSystemTimeLow());
	}
	return sceKernelExitDeleteThread(0);
}

int module_start(SceSize args, void *argp)
{
	char title[128], disc[16];
	u16 type = 0; u32 len = 0;
	SEConfigARK config;

	Kprintf("gametest: hello from a game plugin, HEN %08x.%d, SE %08x, model %d\n",
		sctrlHENGetVersion(), sctrlHENGetMinorVersion(), sctrlSEGetVersion(), sceKernelGetModel());
	Kprintf("gametest: boot from %02x, apitype %03x, key config %03x, file '%s'\n",
		sceKernelBootFrom(), sceKernelInitApitype(), sceKernelApplicationType(), sceKernelInitFileName());
	memset(title, 0, sizeof(title)); memset(disc, 0, sizeof(disc));
	int r1 = sctrlGetInitPARAM("TITLE", &type, &len, title);
	Kprintf("gametest: TITLE -> %d, type %04x, length %d, '%s'\n", r1, type, (int)len, title);
	int r2 = sctrlGetInitPARAM("DISC_ID", &type, &len, disc);
	Kprintf("gametest: DISC_ID -> %d, '%s'\n", r2, disc);
	memset(&config, 0xaa, sizeof(config));
	sctrlSEGetConfigEx((void *)&config, sizeof(config));
	Kprintf("gametest: SEConfig (ARK layout, %d bytes) magic %08x\n", (int)sizeof(config), (unsigned int)config.magic);

	SceUID th = sceKernelCreateThread("gametest_ticker", ticker, 0x30, 0x1000, 0, NULL);
	if (th >= 0) sceKernelStartThread(th, 0, NULL);
	Kprintf("gametest: ticker thread %08x\n", th);
	return 0;
}

int module_stop(SceSize args, void *argp)
{
	return 0;
}
