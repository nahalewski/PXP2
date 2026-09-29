/*
 * PXP2 CFW - shared config load/save
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * The config is a tiny flat text file so it is safe to edit by hand and
 * cannot brick anything. Format: one "key=value" per line. Unknown keys
 * are ignored; missing keys keep their defaults.
 */
#include <string.h>
#include <stdio.h>
#include <pspiofilemgr.h>
#include "systemctrl.h"

static pxp2_config g_cfg;
static int g_loaded = 0;

static void set_defaults(pxp2_config *c)
{
	memset(c, 0, sizeof(*c));
	c->cfw_enabled      = 1;
	c->unsigned_allowed = 1;
	c->plugins_vsh      = 1;
	c->plugins_game     = 1;
	c->plugins_pops     = 1;
	c->umd_region_free  = 1;
	c->fake_umd_iso     = 1;
	c->hide_cfw_in_dnas = 0;
	c->iso_default[0]   = 0;
}

/* Try ms0: first, then ef0: (Go). Returns the path that opened, or NULL. */
static const char *cfg_path(void)
{
	static char path[64];
	SceUID fd;
	strcpy(path, PXP2_MS_ROOT PXP2_CFG_PATH);
	fd = sceIoOpen(path, PSP_O_RDONLY, 0);
	if (fd >= 0) { sceIoClose(fd); return path; }
	strcpy(path, PXP2_EF_ROOT PXP2_CFG_PATH);
	fd = sceIoOpen(path, PSP_O_RDONLY, 0);
	if (fd >= 0) { sceIoClose(fd); return path; }
	return NULL;
}

/* Kernel libc has no sscanf; parse a small signed decimal by hand. */
static int parse_int(const char *v)
{
	int n = 0, sign = 1;
	while (*v == ' ' || *v == '\t') v++;
	if (*v == '-') { sign = -1; v++; }
	else if (*v == '+') v++;
	while (*v >= '0' && *v <= '9') { n = n * 10 + (*v - '0'); v++; }
	return n * sign;
}

static void apply_kv(pxp2_config *c, const char *k, const char *v)
{
	if      (!strcmp(k, "cfw_enabled"))      c->cfw_enabled = parse_int(v);
	else if (!strcmp(k, "unsigned_allowed")) c->unsigned_allowed = parse_int(v);
	else if (!strcmp(k, "plugins_vsh"))      c->plugins_vsh = parse_int(v);
	else if (!strcmp(k, "plugins_game"))     c->plugins_game = parse_int(v);
	else if (!strcmp(k, "plugins_pops"))     c->plugins_pops = parse_int(v);
	else if (!strcmp(k, "umd_region_free"))  c->umd_region_free = parse_int(v);
	else if (!strcmp(k, "fake_umd_iso"))     c->fake_umd_iso = parse_int(v);
	else if (!strcmp(k, "hide_cfw_in_dnas")) c->hide_cfw_in_dnas = parse_int(v);
	else if (!strcmp(k, "iso_default")) {
		strncpy(c->iso_default, v, sizeof(c->iso_default) - 1);
		c->iso_default[sizeof(c->iso_default) - 1] = 0;
	}
}

int pxp2ctrl_config_load(pxp2_config *out)
{
	const char *path;
	SceUID fd;
	char buf[2048];
	int n, i, line_start;

	set_defaults(&g_cfg);

	path = cfg_path();
	if (path) {
		fd = sceIoOpen(path, PSP_O_RDONLY, 0);
		if (fd >= 0) {
			n = sceIoRead(fd, buf, sizeof(buf) - 1);
			sceIoClose(fd);
			if (n < 0) n = 0;
			buf[n] = 0;
			line_start = 0;
			for (i = 0; i <= n; i++) {
				if (buf[i] == '\n' || buf[i] == '\r' || buf[i] == 0) {
					char line[256];
					int len = i - line_start;
					if (len > 0 && len < (int)sizeof(line)) {
						char *eq;
						memcpy(line, buf + line_start, len);
						line[len] = 0;
						eq = strchr(line, '=');
						if (eq && line[0] != '#') {
							*eq = 0;
							apply_kv(&g_cfg, line, eq + 1);
						}
					}
					line_start = i + 1;
				}
			}
		}
	}

	g_loaded = 1;
	if (out) *out = g_cfg;
	return 0;
}

int pxp2ctrl_config_save(const pxp2_config *in)
{
	char path[64];
	SceUID fd;
	char buf[512];
	int len;

	if (in) g_cfg = *in;

	/* Write next to wherever the payload lives; default to ms0:. */
	strcpy(path, PXP2_MS_ROOT PXP2_CFG_PATH);
	fd = sceIoOpen(path, PSP_O_WRONLY | PSP_O_CREAT | PSP_O_TRUNC, 0777);
	if (fd < 0) {
		strcpy(path, PXP2_EF_ROOT PXP2_CFG_PATH);
		fd = sceIoOpen(path, PSP_O_WRONLY | PSP_O_CREAT | PSP_O_TRUNC, 0777);
	}
	if (fd < 0)
		return -1;

	len = sprintf(buf,
		"# PXP2 CFW config - safe to edit\n"
		"cfw_enabled=%d\n"
		"unsigned_allowed=%d\n"
		"plugins_vsh=%d\n"
		"plugins_game=%d\n"
		"plugins_pops=%d\n"
		"umd_region_free=%d\n"
		"fake_umd_iso=%d\n"
		"hide_cfw_in_dnas=%d\n"
		"iso_default=%s\n",
		g_cfg.cfw_enabled, g_cfg.unsigned_allowed,
		g_cfg.plugins_vsh, g_cfg.plugins_game, g_cfg.plugins_pops,
		g_cfg.umd_region_free, g_cfg.fake_umd_iso,
		g_cfg.hide_cfw_in_dnas, g_cfg.iso_default);

	sceIoWrite(fd, buf, len);
	sceIoClose(fd);
	return 0;
}

const pxp2_config *pxp2ctrl_config_current(void)
{
	if (!g_loaded)
		pxp2ctrl_config_load(NULL);
	return &g_cfg;
}
