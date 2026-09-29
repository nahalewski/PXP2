/*
 * PXP2 CFW - Installer ("update")
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * A homebrew EBOOT.PBP that copies the CFW payload from its own folder to
 * <root>/PXP2CFW/ on the Memory Stick (or ef0: on the Go). It writes ONLY
 * the Memory Stick / internal storage. It NEVER writes flash0 in this
 * build. There is no flash option here at all - persistent/flash install is
 * a separate, future, opt-in tool and is disabled in code.
 *
 * Brick avoidance is the priority: every step is a plain file copy that can
 * be undone by deleting the PXP2CFW folder.
 */
#include <pspkernel.h>
#include <pspdisplay.h>
#include <pspctrl.h>
#include <pspdebug.h>
#include <pspiofilemgr.h>
#include <string.h>
#include <stdio.h>

PSP_MODULE_INFO("PXP2_CFW_INSTALLER", 0, 1, 0);
PSP_MAIN_THREAD_ATTR(THREAD_ATTR_USER | THREAD_ATTR_VFPU);

/* HARD SAFETY GATE: flash writing is compiled out. Do not flip this in this
 * build. A real flash installer must be its own tool with its own reviews. */
#define PXP2_ALLOW_FLASH_WRITE 0

static int exit_cb(int a,int b,void*p){(void)a;(void)b;(void)p;sceKernelExitGame();return 0;}
static int cb_thr(SceSize a,void*p){(void)a;(void)p;
	int id=sceKernelCreateCallback("e",exit_cb,0);sceKernelRegisterExitCallback(id);
	sceKernelSleepThreadCB();return 0;}
static void setup_cb(void){int t=sceKernelCreateThread("cb",cb_thr,0x11,0xFA0,0,0);
	if(t>=0)sceKernelStartThread(t,0,0);}

/* The files the installer ships in its own folder and copies to PXP2CFW/. */
static const char *payload[] = {
	"systemctrl.prx",
	"vshctrl.prx",
	"recovery.prx",
	"loader.prx",
	NULL
};

static int file_exists(const char *p){SceUID f=sceIoOpen(p,PSP_O_RDONLY,0);if(f>=0){sceIoClose(f);return 1;}return 0;}

static int copy_file(const char *src, const char *dst)
{
	SceUID in, out;
	static char buf[16 * 1024];
	int n;
	in = sceIoOpen(src, PSP_O_RDONLY, 0);
	if (in < 0) return -1;
	out = sceIoOpen(dst, PSP_O_WRONLY | PSP_O_CREAT | PSP_O_TRUNC, 0777);
	if (out < 0) { sceIoClose(in); return -2; }
	while ((n = sceIoRead(in, buf, sizeof(buf))) > 0) {
		if (sceIoWrite(out, buf, n) != n) { sceIoClose(in); sceIoClose(out); return -3; }
	}
	sceIoClose(in); sceIoClose(out);
	return 0;
}

/* Where is this EBOOT running from? Determine root (ms0 or ef0). */
static const char *pick_root(void)
{
	/* User mode cannot call the kernel-only sceKernelGetModel(), so we just
	 * probe for ef0: (only the Go has it). If ef0:/PSP/GAME opens we install
	 * there; otherwise the Memory Stick. */
	SceUID d = sceIoDopen("ef0:/PSP/GAME");
	if (d >= 0) { sceIoClose(d); return "ef0:"; }
	return "ms0:";
}

static void mkdirs(const char *root)
{
	char p[64];
	sprintf(p, "%s/PXP2CFW", root);        sceIoMkdir(p, 0777);
	sprintf(p, "%s/ISO", root);            sceIoMkdir(p, 0777);
	sprintf(p, "%s/SEPLUGINS", root);      sceIoMkdir(p, 0777);
}

/* Derive the directory this EBOOT.PBP was launched from, so the payload is
 * read from beside the installer wherever the user put it (not a hardcoded
 * folder name). argv[0] is the boot path the loader passes to main(). On
 * failure we fall back to the documented default folder. */
static void pick_srcdir(char *dst, int dstlen, const char *argv0, const char *root)
{
	if (argv0 && argv0[0]) {
		int n = strlen(argv0);
		/* Strip the trailing "/EBOOT.PBP" (or any final path component). */
		while (n > 0 && argv0[n - 1] != '/' && argv0[n - 1] != '\\') n--;
		if (n > 1 && n < dstlen) {
			memcpy(dst, argv0, n - 1); /* drop the trailing slash */
			dst[n - 1] = 0;
			return;
		}
	}
	snprintf(dst, dstlen, "%s/PSP/GAME/PXP2CFW", root);
}

int main(int argc, char *argv[])
{
	SceCtrlData pad, old;
	const char *root;
	char srcdir[160];
	int confirmed = 0;

	setup_cb();
	pspDebugScreenInit();
	sceCtrlSetSamplingCycle(0);
	sceCtrlSetSamplingMode(PSP_CTRL_MODE_DIGITAL);

	root = pick_root();
	/* The installer's own directory holds the payload. Find it from argv[0]
	 * so it works from any folder the user copies the bundle into. */
	pick_srcdir(srcdir, sizeof(srcdir), argc > 0 ? argv[0] : 0, root);

	pspDebugScreenClear();
	printf("\n  PXP2 CFW Installer (update)\n");
	printf("  %s\n\n", "0.1 - UNVERIFIED, use at your own risk");
	printf("  This installs a from-scratch custom firmware\n");
	printf("  payload to %s/PXP2CFW/.\n\n", root);
	printf("  WARNINGS:\n");
	printf("   - This build is UNTESTED on real hardware.\n");
	printf("   - It does NOT write flash0. It only copies\n");
	printf("     files to your Memory Stick / internal storage.\n");
	printf("   - CFW is loaded via a separate 6.61 exploit you\n");
	printf("     must supply. This installer ships no exploit.\n");
	printf("   - To uninstall: delete the PXP2CFW folder.\n\n");
	printf("  Press X to install, O to quit.\n");

	memset(&old, 0, sizeof(old));
	for (;;) {
		unsigned int pressed;
		sceCtrlReadBufferPositive(&pad, 1);
		pressed = pad.Buttons & ~old.Buttons;
		old = pad;
		if (pressed & PSP_CTRL_CIRCLE) sceKernelExitGame();
		if (pressed & PSP_CTRL_CROSS) { confirmed = 1; break; }
		sceDisplayWaitVblankStart();
	}

	if (confirmed) {
		int i, errs = 0;
		char src[224], dst[160];
		pspDebugScreenClear();
		printf("\n  Installing to %s/PXP2CFW ...\n\n", root);

#if PXP2_ALLOW_FLASH_WRITE
#error "Flash writing must not be enabled in this build."
#endif

		mkdirs(root);
		for (i = 0; payload[i]; i++) {
			sprintf(src, "%s/%s", srcdir, payload[i]);
			sprintf(dst, "%s/PXP2CFW/%s", root, payload[i]);
			if (!file_exists(src)) {
				printf("   [skip] %-16s (not bundled)\n", payload[i]);
				continue;
			}
			if (copy_file(src, dst) == 0)
				printf("   [ ok ] %s\n", payload[i]);
			else {
				printf("   [FAIL] %s\n", payload[i]);
				errs++;
			}
		}
		printf("\n  %s\n", errs ? "Completed with errors." : "Done. No flash was touched.");
		printf("\n  Next: run your 6.61 kernel exploit and point it\n");
		printf("  at %s/PXP2CFW/loader.prx (see README).\n", root);
		printf("\n  Press X to exit.\n");
		for (;;) {
			sceCtrlReadBufferPositive(&pad, 1);
			if (pad.Buttons & PSP_CTRL_CROSS) break;
			sceDisplayWaitVblankStart();
		}
	}

	sceKernelExitGame();
	return 0;
}
