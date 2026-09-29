/*
 * PXP2 CFW - seplugins loader
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * Reads ms0:/SEPLUGINS/{vsh,game,pops}.txt (and ef0: on the Go) and loads
 * each enabled plugin for the current boot mode. File format is the widely
 * documented seplugins format:
 *
 *     ms0:/seplugins/myplugin.prx 1     ; 1 = enabled, 0 = disabled
 *     # comment
 *
 * ORIGINAL WORK: this is our own parser; the .txt format is a de-facto
 * public convention, not code.
 */
#include <string.h>
#include <pspiofilemgr.h>
#include <pspmodulemgr.h>
#include "systemctrl.h"

static void trim(char *s)
{
	int n = strlen(s);
	while (n > 0 && (s[n-1] == ' ' || s[n-1] == '\t' || s[n-1] == '\r' || s[n-1] == '\n'))
		s[--n] = 0;
}

static const char *list_name(pxp2_bootmode_t mode)
{
	switch (mode) {
		case PXP2_BOOT_GAME: return "game.txt";
		case PXP2_BOOT_POPS: return "pops.txt";
		default:             return "vsh.txt";
	}
}

/* Load and start one plugin PRX in the requested exec mode. */
static int start_plugin(const char *path)
{
	SceUID modid;
	int status = 0;
	modid = sceKernelLoadModule(path, 0, NULL);
	if (modid < 0)
		return modid;
	return sceKernelStartModule(modid, strlen(path) + 1, (void *)path, &status, NULL);
}

/* Parse one seplugins .txt and start enabled entries. Returns count. */
static int load_from(const char *dir)
{
	char listpath[128];
	const pxp2_config *cfg = pxp2ctrl_config_current();
	pxp2_bootmode_t mode = pxp2ctrl_get_bootmode();
	SceUID fd;
	char buf[4096];
	int n, i, line_start, started = 0;

	/* Respect the per-mode master switch. */
	if (mode == PXP2_BOOT_VSH  && !cfg->plugins_vsh)  return 0;
	if (mode == PXP2_BOOT_GAME && !cfg->plugins_game) return 0;
	if (mode == PXP2_BOOT_POPS && !cfg->plugins_pops) return 0;

	strcpy(listpath, dir);
	strcat(listpath, "/SEPLUGINS/");
	strcat(listpath, list_name(mode));

	fd = sceIoOpen(listpath, PSP_O_RDONLY, 0);
	if (fd < 0)
		return 0;
	n = sceIoRead(fd, buf, sizeof(buf) - 1);
	sceIoClose(fd);
	if (n < 0) n = 0;
	buf[n] = 0;

	line_start = 0;
	for (i = 0; i <= n; i++) {
		if (buf[i] == '\n' || buf[i] == 0) {
			char line[256];
			int len = i - line_start;
			if (len > 0 && len < (int)sizeof(line)) {
				char *sp;
				int enabled = 1;
				memcpy(line, buf + line_start, len);
				line[len] = 0;
				trim(line);
				if (line[0] && line[0] != '#') {
					/* Split off a trailing " 1" / " 0" enable flag. */
					sp = strrchr(line, ' ');
					if (sp) {
						enabled = (sp[1] == '1');
						*sp = 0;
						trim(line);
					}
					if (enabled && line[0]) {
						if (start_plugin(line) >= 0)
							started++;
					}
				}
			}
			line_start = i + 1;
		}
	}
	return started;
}

int pxp2ctrl_load_plugins(void)
{
	int total = 0;
	total += load_from(PXP2_MS_ROOT);
	if (pxp2ctrl_get_model() == PXP2_MODEL_4000)
		total += load_from(PXP2_EF_ROOT);
	return total;
}
