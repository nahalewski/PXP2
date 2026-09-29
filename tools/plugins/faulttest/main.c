/*
 * PXP2 test plugin "faulttest" (user mode) - original code, GPL-3.0-or-later.
 *
 * Misbehaves on purpose to check that a broken plugin cannot break boot: it calls a function PXP2 does not
 * implement (sceSysregMeResetEnable, a hardware register driver), then starts a thread that writes through a null
 * pointer. Expected: the call returns an error, the faulting thread is ended, the XMB/game keeps running.
 */
#include <pspsdk.h>
#include <pspkernel.h>
#include <string.h>

PSP_MODULE_INFO("PXP2_FaultTest", 0, 1, 0);

int sceSysregMeResetEnable(void);

static void say(const char *s) { sceIoWrite(1, s, strlen(s)); }

static int crasher(SceSize args, void *argp)
{
	sceKernelDelayThread(500 * 1000);
	say("faulttest: thread about to write through a null pointer\n");
	*(volatile int *)0 = 0x1234;
	say("faulttest: still alive (unexpected)\n");
	return 0;
}

int module_start(SceSize args, void *argp)
{
	char msg[80];
	int r = sceSysregMeResetEnable();
	say("faulttest: user-mode plugin started\n");
	strcpy(msg, "faulttest: missing import returned 0x");
	for (int i = 7; i >= 0; i--) { int d = (r >> (i * 4)) & 15; msg[strlen(msg) + 1] = 0; msg[strlen(msg)] = d < 10 ? '0' + d : 'a' + d - 10; }
	strcat(msg, "\n"); say(msg);
	SceUID th = sceKernelCreateThread("faulttest_crasher", crasher, 0x30, 0x1000, 0, NULL);
	if (th >= 0) sceKernelStartThread(th, 0, NULL);
	return 0;
}

int module_stop(SceSize args, void *argp)
{
	return 0;
}
