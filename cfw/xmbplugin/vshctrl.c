/*
 * PXP2 CFW - VSH plugin: ISO enumeration + launch
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * User-mode module loaded in VSH mode. It:
 *   - scans ms0:/ISO (and ef0:/ISO on the Go) for .iso/.cso images,
 *   - reads PARAM.SFO (title) and can extract ICON0 for each,
 *   - launches a selected ISO through sceVshBridge VSHDisc.
 *
 * HONEST STATUS: injecting new items directly into the retail XMB "Game"
 * column requires patching vshmain's list model, whose 6.61 per-model text
 * offsets are UNVERIFIED (see include/offsets_661.h). This module therefore
 * implements the ISO model and the launch path (both testable in isolation)
 * and exposes vshctrl_launch_iso(); the actual XMB list hook is staged and
 * documented in DESIGN.md. Homebrew in ms0:/PSP/GAME keeps working with no
 * XMB change because the kernel loader patch handles it.
 */
#include <string.h>
#include <stdio.h>
#include <pspkernel.h>
#include <pspiofilemgr.h>
#include "vshbridge.h"
#include "iso9660.h"
#include "sfo.h"

PSP_MODULE_INFO("pxp2_vshctrl", 0, 0, 1);
PSP_MAIN_THREAD_ATTR(0);

#define MAX_ISOS 512

typedef struct {
	char path[160];
	char title[128];
	char disc_id[16];
} iso_entry;

static iso_entry g_isos[MAX_ISOS];
static int g_iso_count = 0;

static int ends_with_ci(const char *s, const char *suf)
{
	int ls = strlen(s), lf = strlen(suf), i;
	if (ls < lf) return 0;
	for (i = 0; i < lf; i++) {
		char a = s[ls - lf + i], b = suf[i];
		if (a >= 'A' && a <= 'Z') a += 32;
		if (b >= 'A' && b <= 'Z') b += 32;
		if (a != b) return 0;
	}
	return 1;
}

static void scan_dir(const char *dir)
{
	SceUID d;
	SceIoDirent de;

	d = sceIoDopen(dir);
	if (d < 0)
		return;

	memset(&de, 0, sizeof(de));
	while (g_iso_count < MAX_ISOS && sceIoDread(d, &de) > 0) {
		if (FIO_S_ISDIR(de.d_stat.st_mode))
			continue;
		if (ends_with_ci(de.d_name, ".iso") || ends_with_ci(de.d_name, ".cso")) {
			iso_entry *e = &g_isos[g_iso_count];
			char sfo[2048];
			int n;
			/* Bounded join of dir + "/" + name into e->path. */
			n = snprintf(e->path, sizeof(e->path), "%s/%s", dir, de.d_name);
			if (n <= 0 || n >= (int)sizeof(e->path)) {
				memset(&de, 0, sizeof(de));
				continue;
			}
			e->title[0] = 0;
			e->disc_id[0] = 0;

			/* .cso metadata not read by the user reader; label by file. */
			if (ends_with_ci(de.d_name, ".iso")) {
				n = iso9660_read_file(e->path, "/PSP_GAME/PARAM.SFO", sfo, sizeof(sfo));
				if (n > 0) {
					sfo_get_string(sfo, n, "TITLE", e->title, sizeof(e->title));
					sfo_get_string(sfo, n, "DISC_ID", e->disc_id, sizeof(e->disc_id));
				}
			}
			if (e->title[0] == 0) {
				/* Fall back to the file name without extension. */
				int L = strlen(de.d_name);
				strncpy(e->title, de.d_name, sizeof(e->title) - 1);
				if (L > 4) e->title[L - 4] = 0;
			}
			g_iso_count++;
		}
		memset(&de, 0, sizeof(de));
	}
	sceIoClose(d);
}

/* Write the catalog the kernel Game-column injector (systemctrl/gamelist.c)
 * consumes: one line per ISO, "path|disc_id|title". Written to
 * ms0:/PXP2CFW (ef0: on the Go). This is the hand-off from the user-mode
 * scan to the kernel list-injection hooks. */
static void write_catalog(void)
{
	static const char *outs[2] = {
		"ms0:/PXP2CFW/gamelist.txt", "ef0:/PXP2CFW/gamelist.txt"
	};
	int o;
	for (o = 0; o < 2; o++) {
		SceUID fd = sceIoOpen(outs[o], PSP_O_WRONLY | PSP_O_CREAT | PSP_O_TRUNC, 0777);
		int i;
		if (fd < 0)
			continue;
		for (i = 0; i < g_iso_count; i++) {
			char line[320];
			const char *id = g_isos[i].disc_id[0] ? g_isos[i].disc_id : "PXPT00001";
			int n = snprintf(line, sizeof(line), "%s|%s|%s\n",
			                 g_isos[i].path, id, g_isos[i].title);
			if (n > 0 && n < (int)sizeof(line))
				sceIoWrite(fd, line, n);
		}
		sceIoClose(fd);
		return; /* wrote the first available target */
	}
}

int vshctrl_rescan(void)
{
	g_iso_count = 0;
	scan_dir("ms0:/ISO");
	/* Go internal storage. sceKernelGetModel()==4 is the Go; scanning ef0:
	 * when absent simply fails the Dopen, which is harmless. */
	scan_dir("ef0:/ISO");
	write_catalog();
	return g_iso_count;
}

int vshctrl_iso_count(void) { return g_iso_count; }
const char *vshctrl_iso_title(int i) { return (i >= 0 && i < g_iso_count) ? g_isos[i].title : NULL; }
const char *vshctrl_iso_path (int i) { return (i >= 0 && i < g_iso_count) ? g_isos[i].path  : NULL; }

/*
 * Launch an ISO by index. We tell the kernel systemctrl which image to
 * mount (via its export), then reboot into the disc through the VSHDisc
 * bridge exactly as the XMB does for a real UMD (psp.md "Game launch").
 */
int vshctrl_launch_iso(int index)
{
	struct SceKernelLoadExecVSHParam param;
	static char args[256];
	const char *path;
	SceUID fd;

	if (index < 0 || index >= g_iso_count)
		return -1;
	path = g_isos[index].path;

	/* Hand the selected image to the kernel driver via a tiny handoff file.
	 * systemctrl reads ms0:/PXP2CFW/launch.iso at the VSHDisc boot and mounts
	 * it as the virtual UMD. Using a file avoids a cross-module kernel import
	 * from this user PRX and keeps the modules independently linkable. */
	fd = sceIoOpen("ms0:/PXP2CFW/launch.iso",
	               PSP_O_WRONLY | PSP_O_CREAT | PSP_O_TRUNC, 0777);
	if (fd < 0)
		fd = sceIoOpen("ef0:/PXP2CFW/launch.iso",
		               PSP_O_WRONLY | PSP_O_CREAT | PSP_O_TRUNC, 0777);
	if (fd >= 0) {
		sceIoWrite(fd, path, strlen(path));
		sceIoClose(fd);
	}

	/* Boot BOOT.BIN off disc0: (the CFW ISO driver now backs disc0:). */
	memset(&param, 0, sizeof(param));
	strcpy(args, "disc0:/PSP_GAME/SYSDIR/BOOT.BIN");
	param.size = sizeof(param);
	param.args = strlen(args) + 1;
	param.argp = args;
	param.key  = "game";

	return sctrlKernelLoadExecVSHDisc("disc0:/PSP_GAME/SYSDIR/BOOT.BIN", &param);
}

/*
 * Entry point. This user-mode plugin is loaded via sceKernelStartModule by
 * systemctrl's plugin loader (or a seplugins loader). The PSPSDK user crt0
 * provides module_start, which runs main() on the user_main thread.
 *
 * CRITICAL: main() must NEVER return. The PSPSDK user crt0's main-wrapper
 * calls sceKernelExitGame() once main() returns, which for a resident VSH
 * plugin tears the running VSH down - an endless reboot loop in PXP2, and a
 * killed/hung XMB on real hardware. A resident plugin therefore does its
 * one-time setup and then sleeps its thread forever, staying loaded so its
 * exported vshctrl_* API remains callable. (We cannot instead define our
 * own module_start: the user crt0 in crt0_prx.o already defines a non-weak
 * one, so that would be a duplicate-symbol link error - see BUILD.md.)
 */
int main(void)
{
	vshctrl_rescan();
	/* Stay resident: never let the crt0 call sceKernelExitGame(). */
	for (;;)
		sceKernelSleepThread();
	return 0; /* not reached */
}
