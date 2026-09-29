/*
 * PXP2 CFW - Recovery Menu
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * A minimal, brick-safe recovery menu. Entered by holding R at boot (the
 * loader stub sets recovery_hold and chain-loads this EBOOT). It edits the
 * shared config file and offers "boot normally" and "disable CFW".
 *
 * It deliberately does NOTHING dangerous: it only reads/writes the plain
 * text config on the Memory Stick. It never touches flash0. There is no
 * flash option in this build.
 */
#include <pspkernel.h>
#include <pspdisplay.h>
#include <pspctrl.h>
#include <pspdebug.h>
#include <string.h>
#include <stdio.h>
#include <pspiofilemgr.h>

PSP_MODULE_INFO("PXP2_RECOVERY", 0, 1, 0);
PSP_MAIN_THREAD_ATTR(THREAD_ATTR_USER | THREAD_ATTR_VFPU);

/* Config lives in the same flat format config.c writes. We keep an
 * independent copy of the parser here so recovery has no dependency on the
 * kernel module (recovery must run even if systemctrl failed to start). */
typedef struct {
	int cfw_enabled, unsigned_allowed;
	int plugins_vsh, plugins_game, plugins_pops;
	int umd_region_free, fake_umd_iso;
	char iso_default[128];
} rcfg;

static const char *CFG_MS = "ms0:/PXP2CFW/cfw.cfg";
static const char *CFG_EF = "ef0:/PXP2CFW/cfw.cfg";
static char g_cfgpath[64];

static int exit_cb(int a1, int a2, void *arg){(void)a1;(void)a2;(void)arg;sceKernelExitGame();return 0;}
static int cb_thread(SceSize a, void *ap){(void)a;(void)ap;
	int id = sceKernelCreateCallback("exit", exit_cb, NULL);
	sceKernelRegisterExitCallback(id);
	sceKernelSleepThreadCB();
	return 0;
}
static void setup_callbacks(void){
	int th = sceKernelCreateThread("cb", cb_thread, 0x11, 0xFA0, 0, 0);
	if (th >= 0) sceKernelStartThread(th, 0, 0);
}

static int parse_int(const char *v){int n=0;sscanf(v,"%d",&n);return n;}

static void load_cfg(rcfg *c)
{
	SceUID fd;
	char buf[2048];
	int n, i, ls;
	memset(c, 0, sizeof(*c));
	c->cfw_enabled = 1; c->unsigned_allowed = 1;
	c->plugins_vsh = 1; c->plugins_game = 1; c->plugins_pops = 1;
	c->umd_region_free = 1; c->fake_umd_iso = 1;

	strcpy(g_cfgpath, CFG_MS);
	fd = sceIoOpen(CFG_MS, PSP_O_RDONLY, 0);
	if (fd < 0) { strcpy(g_cfgpath, CFG_EF); fd = sceIoOpen(CFG_EF, PSP_O_RDONLY, 0); }
	if (fd < 0) {
		/* No existing config (first run). The probe above left g_cfgpath at
		 * the ef0: fallback, but ef0: exists only on the Go, so save_cfg
		 * would silently fail on every other model. Pick the write target
		 * from where the CFW payload actually lives, defaulting to ms0:. */
		SceUID d = sceIoDopen("ms0:/PXP2CFW");
		if (d >= 0) { sceIoClose(d); strcpy(g_cfgpath, CFG_MS); }
		else {
			d = sceIoDopen("ef0:/PXP2CFW");
			if (d >= 0) { sceIoClose(d); strcpy(g_cfgpath, CFG_EF); }
			else strcpy(g_cfgpath, CFG_MS);
		}
		return;
	}
	n = sceIoRead(fd, buf, sizeof(buf) - 1); sceIoClose(fd);
	if (n < 0) n = 0;
	buf[n] = 0;
	ls = 0;
	for (i = 0; i <= n; i++) {
		if (buf[i]=='\n'||buf[i]=='\r'||buf[i]==0) {
			char line[256]; int len = i - ls;
			if (len > 0 && len < (int)sizeof(line)) {
				char *eq; memcpy(line, buf+ls, len); line[len]=0;
				eq = strchr(line, '=');
				if (eq && line[0] != '#') {
					*eq = 0;
					if(!strcmp(line,"cfw_enabled"))c->cfw_enabled=parse_int(eq+1);
					else if(!strcmp(line,"unsigned_allowed"))c->unsigned_allowed=parse_int(eq+1);
					else if(!strcmp(line,"plugins_vsh"))c->plugins_vsh=parse_int(eq+1);
					else if(!strcmp(line,"plugins_game"))c->plugins_game=parse_int(eq+1);
					else if(!strcmp(line,"plugins_pops"))c->plugins_pops=parse_int(eq+1);
					else if(!strcmp(line,"umd_region_free"))c->umd_region_free=parse_int(eq+1);
					else if(!strcmp(line,"fake_umd_iso"))c->fake_umd_iso=parse_int(eq+1);
				}
			}
			ls = i + 1;
		}
	}
}

static void save_cfg(const rcfg *c)
{
	SceUID fd = sceIoOpen(g_cfgpath, PSP_O_WRONLY|PSP_O_CREAT|PSP_O_TRUNC, 0777);
	char buf[512]; int len;
	if (fd < 0) return;
	len = sprintf(buf,
		"# PXP2 CFW config - safe to edit\n"
		"cfw_enabled=%d\nunsigned_allowed=%d\n"
		"plugins_vsh=%d\nplugins_game=%d\nplugins_pops=%d\n"
		"umd_region_free=%d\nfake_umd_iso=%d\niso_default=%s\n",
		c->cfw_enabled, c->unsigned_allowed,
		c->plugins_vsh, c->plugins_game, c->plugins_pops,
		c->umd_region_free, c->fake_umd_iso, c->iso_default);
	sceIoWrite(fd, buf, len); sceIoClose(fd);
}

#define N_ITEMS 8

static const char *labels[N_ITEMS] = {
	"CFW enabled",
	"Allow unsigned homebrew",
	"VSH plugins",
	"GAME plugins",
	"POPS plugins",
	"UMD region free",
	"Present ISO as UMD",
	"Save and boot normally",
};

static int *field_ptr(rcfg *c, int i)
{
	switch (i) {
		case 0: return &c->cfw_enabled;
		case 1: return &c->unsigned_allowed;
		case 2: return &c->plugins_vsh;
		case 3: return &c->plugins_game;
		case 4: return &c->plugins_pops;
		case 5: return &c->umd_region_free;
		case 6: return &c->fake_umd_iso;
		default: return NULL;
	}
}

static void draw(const rcfg *c, int sel)
{
	int i;
	pspDebugScreenClear();
	pspDebugScreenSetXY(0, 0);
	printf("  PXP2 CFW - Recovery Menu\n");
	printf("  (UNVERIFIED build - no flash writes)\n\n");
	for (i = 0; i < N_ITEMS; i++) {
		const int *f = NULL;
		rcfg tmp = *c;
		f = field_ptr(&tmp, i);
		printf("  %s %-26s", (i == sel) ? ">" : " ", labels[i]);
		if (f) printf(" [%s]\n", *f ? "ON " : "OFF");
		else   printf("\n");
	}
	printf("\n  Up/Down: move   X: toggle/confirm\n");
	printf("  O: boot normally   START: disable CFW & boot\n");
}

int main(void)
{
	rcfg c;
	SceCtrlData pad, old;
	int sel = 0;

	setup_callbacks();
	pspDebugScreenInit();
	sceCtrlSetSamplingCycle(0);
	sceCtrlSetSamplingMode(PSP_CTRL_MODE_DIGITAL);

	load_cfg(&c);
	memset(&old, 0, sizeof(old));
	draw(&c, sel);

	for (;;) {
		unsigned int pressed;
		sceCtrlReadBufferPositive(&pad, 1);
		pressed = pad.Buttons & ~old.Buttons;
		old = pad;

		if (pressed & PSP_CTRL_UP)   { sel = (sel + N_ITEMS - 1) % N_ITEMS; draw(&c, sel); }
		if (pressed & PSP_CTRL_DOWN) { sel = (sel + 1) % N_ITEMS; draw(&c, sel); }

		if (pressed & PSP_CTRL_CROSS) {
			int *f = field_ptr(&c, sel);
			if (f) { *f = !*f; draw(&c, sel); }
			else {
				/* Save and boot normally. */
				save_cfg(&c);
				sceKernelExitGame();
			}
		}
		if (pressed & PSP_CTRL_CIRCLE) {
			/* Boot normally without saving. */
			sceKernelExitGame();
		}
		if (pressed & PSP_CTRL_START) {
			c.cfw_enabled = 0;
			save_cfg(&c);
			sceKernelExitGame();
		}
		sceDisplayWaitVblankStart();
	}
	return 0;
}
