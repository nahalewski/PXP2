/*
 * PXP2 CFW - XMB Game-column ISO listing (kernel)
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * Makes the ISOs in ms0:/ISO appear in the XMB "Game -> Memory Stick" column
 * as launchable entries, without patching vshmain's list model by address.
 * The technique is the offset-free one recorded in ../psp.md ("Library"):
 * intercept vshmain's directory enumeration of ms0:/PSP/GAME and append one
 * virtual game folder per ISO, each exposing a synthetic EBOOT.PBP whose
 * PARAM.SFO is marked CATEGORY="MG" / BOOTABLE=1 so the VSH shows and boots
 * it. Launching such an entry is redirected to a real UMD/VSHDisc boot of the
 * backing image.
 *
 * HOOKING: purely by NID, against vshmain's OWN import stubs
 * (pxp2ctrl_find_import), so no hardcoded 6.61 text offset is used and the
 * hooks scope to vshmain only.
 *
 * ============================ VERIFICATION ============================
 * VERIFIED_DOC (public NIDs / formats, and PXP2-emulator findings in
 *   psp.md): the IoFileMgrForUser NIDs; the PBP container and PARAM.SFO
 *   layout; that the XMB lists ms0:/PSP/GAME, opens EBOOT.PBP and stats
 *   PARAM.SFO per folder; that a CATEGORY="MG" BOOTABLE entry appears and
 *   launches; that a disc launch uses disc0:/PSP_GAME/SYSDIR/BOOT.BIN.
 * UNVERIFIED on hardware: whether 6.61 retail vshmain enumerates and
 *   refreshes exactly as the PXP2 HLE VSH did; the precise moment the list
 *   re-reads; and whether the MG-launch interception below reaches the same
 *   LoadExec path on every model. These were exercised only in the emulator.
 *   The MECHANISM is offset-free; the BEHAVIOUR it relies on is marked here.
 * ORIGINAL WORK; no CFW source was used.
 * =====================================================================
 */
#include <string.h>
#include <pspkernel.h>
#include <pspiofilemgr.h>
#include <pspiofilemgr_kernel.h>
#include "systemctrl.h"
#include "offsets_661.h"

/* kernel heap (isodrv/kalloc.c) */
extern void *malloc(unsigned int size);
extern void  free(void *ptr);

/* ISO driver (isodrv/iso.c) */
extern int  iso_open(const char *path);
extern void iso_umd_set_inserted(int present);

/* sceVshBridge NIDs used for launching (VERIFIED_DOC, psp.md/offsets). */
#define NID_VSHDisc_bridge  0x63e69956u
#define NID_VSHMs2_bridge   0x21d4d038u
#define VSH_BRIDGE_LIB      "sceVshBridge"

/* SceKernelLoadExecVSHParam - public layout (psploadexec_kernel.h). */
struct pxp2_vshparam {
	unsigned int size;
	unsigned int args;
	void        *argp;
	const char  *key;
	unsigned int vshmain_args_size;
	void        *vshmain_args;
	char        *configfile;
	unsigned int unk4[3];
};

/* ---- IoFileMgrForUser NIDs (VERIFIED_DOC, PSPSDK) ------------------ */
#define NID_sceIoDopen    0xB29DDF9Cu
#define NID_sceIoDread    0xE3EB004Cu
#define NID_sceIoDclose   0xEB092469u
#define NID_sceIoGetstat  0xACE946E8u
#define NID_sceIoOpen     0x109F50BCu
#define NID_sceIoRead     0x6A638D83u
#define NID_sceIoClose    0x810C4BC3u
#define NID_sceIoLseek32  0x68963324u

#define GL_MAX_ISOS   64
#define GL_ROOT_MS    "ms0:/PSP/GAME"
#define GL_ROOT_EF    "ef0:/PSP/GAME"
#define GL_PREFIX     "PXPISO_"       /* virtual folder name prefix       */
#define GL_CATALOG    "ms0:/PXP2CFW/gamelist.txt"
#define GL_CATALOG_EF "ef0:/PXP2CFW/gamelist.txt"

typedef struct {
	char path[160];     /* ms0:/ISO/foo.iso                              */
	char disc_id[16];   /* ULUS10081 (also the virtual folder suffix)    */
	char title[128];
} gl_iso;

static gl_iso g_cat[GL_MAX_ISOS];
static int    g_cat_n = 0;

/* ---- original imported functions (filled at hook install) ---------- */
static int (*o_Dopen)(const char *dir);
static int (*o_Dread)(SceUID fd, SceIoDirent *de);
static int (*o_Dclose)(SceUID fd);
static int (*o_Getstat)(const char *file, SceIoStat *st);
static int (*o_Open)(const char *file, int flags, SceMode mode);
static int (*o_Read)(SceUID fd, void *buf, SceSize n);
static int (*o_Close)(SceUID fd);
static SceOff (*o_Lseek32)(SceUID fd, int off, int whence);
/* launch path */
static int (*o_VSHMs2)(const char *file, struct pxp2_vshparam *param);
static int (*vshdisc_fn)(const char *file, struct pxp2_vshparam *param);

/* ---- catalog load (written by vshctrl) ----------------------------- */
/* Each line: "path|disc_id|title". vshctrl writes it after scanning. */
static void gl_load_catalog(void)
{
	char buf[8192];
	SceUID fd;
	int n, i, ls;

	g_cat_n = 0;
	fd = sceIoOpen(GL_CATALOG, PSP_O_RDONLY, 0);
	if (fd < 0)
		fd = sceIoOpen(GL_CATALOG_EF, PSP_O_RDONLY, 0);
	if (fd < 0)
		return;
	n = sceIoRead(fd, buf, sizeof(buf) - 1);
	sceIoClose(fd);
	if (n <= 0)
		return;
	buf[n] = 0;

	ls = 0;
	for (i = 0; i <= n && g_cat_n < GL_MAX_ISOS; i++) {
		if (buf[i] == '\n' || buf[i] == 0) {
			char line[320];
			int len = i - ls;
			if (len > 0 && len < (int)sizeof(line)) {
				char *p1, *p2;
				memcpy(line, buf + ls, len);
				line[len] = 0;
				if (line[len-1] == '\r') line[len-1] = 0;
				p1 = strchr(line, '|');
				if (p1) {
					*p1++ = 0;
					p2 = strchr(p1, '|');
					if (p2) *p2++ = 0; else p2 = (char *)"";
					strncpy(g_cat[g_cat_n].path, line, sizeof(g_cat[0].path)-1);
					strncpy(g_cat[g_cat_n].disc_id, p1, sizeof(g_cat[0].disc_id)-1);
					strncpy(g_cat[g_cat_n].title, p2, sizeof(g_cat[0].title)-1);
					if (!g_cat[g_cat_n].disc_id[0])
						strcpy(g_cat[g_cat_n].disc_id, "PXPT00001");
					g_cat_n++;
				}
			}
			ls = i + 1;
		}
	}
}

/* Map a virtual folder name (PXPISO_<id>) or path back to a catalog index. */
static int gl_index_for_name(const char *name)
{
	int i;
	int pl = (int)strlen(GL_PREFIX);
	if (strncmp(name, GL_PREFIX, pl) != 0)
		return -1;
	for (i = 0; i < g_cat_n; i++)
		if (strcmp(name + pl, g_cat[i].disc_id) == 0)
			return i;
	return -1;
}

/* Is 'path' a ms0:/ef0: /PSP/GAME root (optionally trailing slash)? */
static int is_game_root(const char *path)
{
	return (strcmp(path, GL_ROOT_MS) == 0 || strcmp(path, GL_ROOT_MS "/") == 0 ||
	        strcmp(path, GL_ROOT_EF) == 0 || strcmp(path, GL_ROOT_EF "/") == 0);
}

/* If 'path' points inside a virtual folder, return catalog index and set
 * *tail to the remainder after the folder (e.g. "/EBOOT.PBP"). */
static int gl_parse_virtual(const char *path, const char **tail)
{
	const char *g = strstr(path, "/PSP/GAME/");
	const char *n, *slash;
	char name[64];
	int L, idx;
	if (!g) return -1;
	n = g + strlen("/PSP/GAME/");
	if (strncmp(n, GL_PREFIX, strlen(GL_PREFIX)) != 0)
		return -1;
	slash = strchr(n, '/');
	L = slash ? (int)(slash - n) : (int)strlen(n);
	if (L <= 0 || L >= (int)sizeof(name)) return -1;
	memcpy(name, n, L); name[L] = 0;
	idx = gl_index_for_name(name);
	if (idx < 0) return -1;
	if (tail) *tail = slash ? slash : "";
	return idx;
}

/* =====================================================================
 * Synthetic EBOOT.PBP builder (PARAM.SFO + minimal DATA.PSP stub)
 * ===================================================================== */

/* Minimal SFO writer. Returns bytes written into out. */
static unsigned int put_u32(unsigned char *p, unsigned int v)
{ p[0]=v; p[1]=v>>8; p[2]=v>>16; p[3]=v>>24; return 4; }

typedef struct { const char *key; int is_str; const char *sval; unsigned int ival; unsigned int maxlen; } sfo_kv;

static unsigned int build_sfo(unsigned char *out, unsigned int cap,
                              const char *title, const char *disc_id)
{
	/* Keys the 6.61 VSH MS launch path checks (psp.md "Virtual EBOOT"). */
	sfo_kv kv[] = {
		{ "BOOTABLE",        0, NULL, 1,       4  },
		{ "CATEGORY",        1, "MG", 0,       4  },
		{ "DISC_ID",         1, disc_id, 0,    16 },
		{ "DISC_VERSION",    1, "1.00", 0,     8  },
		{ "PARENTAL_LEVEL",  0, NULL, 1,       4  },
		{ "PSP_SYSTEM_VER",  1, "6.61", 0,     8  },
		{ "REGION",          0, NULL, 0x8000,  4  },
		{ "TITLE",           1, title, 0,      128 },
	};
	int nk = (int)(sizeof(kv)/sizeof(kv[0]));
	unsigned int key_tab_off, data_tab_off, off, i, koff, doff;
	unsigned char *keyarea;

	if (cap < 4096) return 0;
	memset(out, 0, cap);

	/* header (0x14) + index (0x10 each) */
	memcpy(out, "\0PSF", 4);
	put_u32(out + 4, 0x00000101);         /* version 1.1                  */
	key_tab_off  = 0x14 + nk * 0x10;
	/* round key table to 4 */
	data_tab_off = key_tab_off;
	/* first compute key area size */
	koff = 0;
	for (i = 0; i < (unsigned)nk; i++)
		koff += strlen(kv[i].key) + 1;
	koff = (koff + 3) & ~3u;
	data_tab_off = key_tab_off + koff;

	put_u32(out + 8,  key_tab_off);
	put_u32(out + 12, data_tab_off);
	put_u32(out + 16, nk);

	keyarea = out + key_tab_off;
	koff = 0; doff = 0;
	off = 0x14;
	for (i = 0; i < (unsigned)nk; i++) {
		unsigned int dlen, dmax = kv[i].maxlen;
		/* index entry */
		out[off+0] = koff & 0xff; out[off+1] = (koff>>8)&0xff;   /* key offset */
		out[off+2] = kv[i].is_str ? 4 : 4;                        /* fmt lo */
		out[off+3] = kv[i].is_str ? 0x02 : 0x04;                  /* 0x0204 utf8, 0x0404 int */
		if (kv[i].is_str) {
			dlen = strlen(kv[i].sval) + 1;
			if (dlen > dmax) dlen = dmax;
		} else {
			dlen = 4;
		}
		put_u32(out + off + 4, dlen);      /* data used  */
		put_u32(out + off + 8, dmax);      /* data max   */
		put_u32(out + off + 12, doff);     /* data offset*/
		off += 0x10;
		/* key text */
		strcpy((char *)keyarea + koff, kv[i].key);
		koff += strlen(kv[i].key) + 1;
		/* data */
		if (kv[i].is_str) {
			strncpy((char *)out + data_tab_off + doff, kv[i].sval, dmax - 1);
		} else {
			put_u32(out + data_tab_off + doff, kv[i].ival);
		}
		doff += dmax;
	}
	return data_tab_off + doff;
}

/* Build a whole virtual EBOOT.PBP into out. Returns total size. */
static unsigned int build_pbp(unsigned char *out, unsigned int cap, int idx)
{
	unsigned int sfo_off = 0x28, sfo_size, data_off, total;
	unsigned char stub[512];

	if (cap < 8192) return 0;
	memset(out, 0, cap);
	memcpy(out, "\0PBP", 4);
	put_u32(out + 4, 0x00010000);

	sfo_size = build_sfo(out + sfo_off, cap - sfo_off,
	                     g_cat[idx].title, g_cat[idx].disc_id);
	data_off = (sfo_off + sfo_size + 15) & ~15u;

	/* 8 section offsets at header+8: SFO, ICON0, ICON1, PIC0, PIC1, SND0,
	 * DATA.PSP, DATA.PSAR. We only ship SFO + a ~PSP DATA.PSP stub. */
	put_u32(out + 0x08, sfo_off);
	put_u32(out + 0x0C, data_off);  /* ICON0 (empty -> same as next)   */
	put_u32(out + 0x10, data_off);
	put_u32(out + 0x14, data_off);
	put_u32(out + 0x18, data_off);
	put_u32(out + 0x1C, data_off);
	put_u32(out + 0x20, data_off);  /* DATA.PSP                         */
	/* DATA.PSAR right after the 512-byte stub */
	memset(stub, 0, sizeof(stub));
	memcpy(stub, "~PSP", 4);        /* homebrew ~PSP tag (psp.md)       */
	memcpy(out + data_off, stub, sizeof(stub));
	put_u32(out + 0x24, data_off + sizeof(stub));
	total = data_off + sizeof(stub);
	return total;
}

/* =====================================================================
 * Virtual handle tables (dirs + files)
 * ===================================================================== */
#define GL_FAKE_DBASE  0x51000000   /* high, distinctive fake dir uids   */
#define GL_FAKE_FBASE  0x52000000   /* fake file uids                    */
#define GL_MAX_OPEN    8

/* Directory iterator over the injected game root: after the real listing
 * ends we emit one entry per catalog ISO. */
typedef struct { int used; SceUID real; int emit; } gl_diter;
static gl_diter g_diter[GL_MAX_OPEN];

/* Virtual EBOOT.PBP file reader. */
typedef struct { int used; int idx; unsigned int pos, size; unsigned char *buf; } gl_fh;
static gl_fh g_fh[GL_MAX_OPEN];



/* ---- hooked IoFileMgr entry points -------------------------------- */
static int h_Dopen(const char *dir)
{
	int i, idx;
	/* Virtual folder itself: return a fake dir uid we serve in Dread. */
	idx = gl_parse_virtual(dir, NULL);
	if (idx >= 0) {
		for (i = 0; i < GL_MAX_OPEN; i++) {
			if (!g_diter[i].used) {
				g_diter[i].used = 1;
				g_diter[i].real = -1;   /* -1 => virtual folder listing  */
				g_diter[i].emit = -1;   /* -1 => emit EBOOT.PBP then end  */
				return GL_FAKE_DBASE + i;
			}
		}
		return -1;
	}
	/* Real game root: wrap it so Dread can append our entries. */
	if (is_game_root(dir)) {
		SceUID real = o_Dopen(dir);
		if (real < 0) return real;
		for (i = 0; i < GL_MAX_OPEN; i++) {
			if (!g_diter[i].used) {
				g_diter[i].used = 1;
				g_diter[i].real = real;
				g_diter[i].emit = 0;    /* start emitting after real end  */
				return GL_FAKE_DBASE + i;
			}
		}
		return real; /* table full: behave as passthrough */
	}
	return o_Dopen(dir);
}

static int h_Dread(SceUID fd, SceIoDirent *de)
{
	int slot = fd - GL_FAKE_DBASE;
	gl_diter *it;
	if (slot < 0 || slot >= GL_MAX_OPEN || !g_diter[slot].used)
		return o_Dread(fd, de);
	it = &g_diter[slot];

	/* virtual folder: list a single EBOOT.PBP then end */
	if (it->real == -1) {
		if (it->emit == -1) {
			memset(de, 0, sizeof(*de));
			strcpy(de->d_name, "EBOOT.PBP");
			de->d_stat.st_mode = 0x2000 | 0x0124;
			de->d_stat.st_attr = 0x0020;
			de->d_stat.st_size = 0; /* size read via getstat/open */
			it->emit = 0;
			return 1;
		}
		return 0;
	}

	/* real root: forward until the real listing is exhausted */
	if (it->emit == 0) {
		int rc = o_Dread(it->real, de);
		if (rc > 0)
			return rc;
		it->emit = 1; /* real entries done; start injecting */
	}
	/* inject one virtual folder per catalog ISO */
	while (it->emit - 1 < g_cat_n) {
		int i = (it->emit++) - 1;
		memset(de, 0, sizeof(*de));
		/* PXPISO_<disc_id> as a directory entry */
		strcpy(de->d_name, GL_PREFIX);
		strncat(de->d_name, g_cat[i].disc_id,
		        sizeof(de->d_name) - strlen(de->d_name) - 1);
		de->d_stat.st_mode = 0x1000 | 0x0124;  /* dir */
		de->d_stat.st_attr = 0x0010;
		return 1;
	}
	return 0;
}

static int h_Dclose(SceUID fd)
{
	int slot = fd - GL_FAKE_DBASE;
	if (slot >= 0 && slot < GL_MAX_OPEN && g_diter[slot].used) {
		gl_diter *it = &g_diter[slot];
		int rc = 0;
		if (it->real >= 0) rc = o_Dclose(it->real);
		it->used = 0;
		return rc;
	}
	return o_Dclose(fd);
}

static int h_Getstat(const char *file, SceIoStat *st)
{
	const char *tail = NULL;
	int idx = gl_parse_virtual(file, &tail);
	if (idx >= 0) {
		memset(st, 0, sizeof(*st));
		if (tail && tail[0] == 0) {          /* the folder */
			st->st_mode = 0x1000 | 0x0124; st->st_attr = 0x0010;
			return 0;
		}
		/* a file inside (EBOOT.PBP / PARAM.PBP): report as a regular file */
		st->st_mode = 0x2000 | 0x0124; st->st_attr = 0x0020;
		st->st_size = 8192; /* upper bound; actual read is size-bounded    */
		return 0;
	}
	return o_Getstat(file, st);
}

static int h_Open(const char *file, int flags, SceMode mode)
{
	const char *tail = NULL;
	int idx = gl_parse_virtual(file, &tail);
	if (idx >= 0 && tail && (strstr(tail, "EBOOT.PBP") || strstr(tail, "PBOOT.PBP"))) {
		int i;
		for (i = 0; i < GL_MAX_OPEN; i++) {
			if (!g_fh[i].used) {
				unsigned char *b = (unsigned char *)malloc(8192);
				if (!b) return -1;
				g_fh[i].used = 1;
				g_fh[i].idx  = idx;
				g_fh[i].pos  = 0;
				g_fh[i].buf  = b;
				g_fh[i].size = build_pbp(b, 8192, idx);
				return GL_FAKE_FBASE + i;
			}
		}
		return -1;
	}
	return o_Open(file, flags, mode);
}

static int h_Read(SceUID fd, void *buf, SceSize n)
{
	int slot = fd - GL_FAKE_FBASE;
	gl_fh *f;
	unsigned int chunk;
	if (slot < 0 || slot >= GL_MAX_OPEN || !g_fh[slot].used)
		return o_Read(fd, buf, n);
	f = &g_fh[slot];
	if (f->pos >= f->size) return 0;
	chunk = f->size - f->pos;
	if (chunk > n) chunk = n;
	memcpy(buf, f->buf + f->pos, chunk);
	f->pos += chunk;
	return (int)chunk;
}

static int h_Close(SceUID fd)
{
	int slot = fd - GL_FAKE_FBASE;
	if (slot >= 0 && slot < GL_MAX_OPEN && g_fh[slot].used) {
		if (g_fh[slot].buf) free(g_fh[slot].buf);
		g_fh[slot].buf = NULL;
		g_fh[slot].used = 0;
		return 0;
	}
	return o_Close(fd);
}

static SceOff h_Lseek32(SceUID fd, int off, int whence)
{
	int slot = fd - GL_FAKE_FBASE;
	gl_fh *f;
	long long np;
	if (slot < 0 || slot >= GL_MAX_OPEN || !g_fh[slot].used)
		return o_Lseek32(fd, off, whence);
	f = &g_fh[slot];
	switch (whence) {
		case PSP_SEEK_SET: np = off; break;
		case PSP_SEEK_CUR: np = (long long)f->pos + off; break;
		case PSP_SEEK_END: np = (long long)f->size + off; break;
		default: return -1;
	}
	if (np < 0) np = 0;
	if (np > f->size) np = f->size;
	f->pos = (unsigned int)np;
	return (SceOff)np;
}

/* ---- launch redirect ----------------------------------------------
 * When the VSH launches one of our virtual entries via VSHMs2, mount the
 * backing ISO and boot it as a UMD via VSHDisc instead (psp.md's approach).
 * UNVERIFIED on hardware: that retail vshmain routes an MG EBOOT launch
 * through exactly this bridge on every model. */
static int h_VSHMs2(const char *file, struct pxp2_vshparam *param)
{
	const char *tail = NULL;
	int idx = file ? gl_parse_virtual(file, &tail) : -1;
	if (idx >= 0 && vshdisc_fn) {
		if (iso_open(g_cat[idx].path) == 0) {
			struct pxp2_vshparam p;
			iso_umd_set_inserted(1);
			memset(&p, 0, sizeof(p));
			p.size = sizeof(p);
			p.key  = "game";
			return vshdisc_fn("disc0:/PSP_GAME/SYSDIR/BOOT.BIN", &p);
		}
	}
	if (o_VSHMs2)
		return o_VSHMs2(file, param);
	return -1;
}

/* =====================================================================
 * Install
 * ===================================================================== */
static int hook_one(const char *lib, unsigned int nid, void *fn, void **orig)
{
	unsigned int stub = pxp2ctrl_find_import("vshmain_module", lib, nid);
	if (!stub)
		stub = pxp2ctrl_find_import("vsh_module", lib, nid);
	if (!stub)
		return 0;
	*orig = (void *)pxp2ctrl_hook_import(stub, fn);
	return 1;
}

int pxp2ctrl_install_gamelist(void)
{
	int n = 0;
	const char *L = "IoFileMgrForUser";

	gl_load_catalog();
	if (g_cat_n == 0)
		return 0; /* no ISOs catalogued -> nothing to inject */

	memset(g_diter, 0, sizeof(g_diter));
	memset(g_fh, 0, sizeof(g_fh));

	n += hook_one(L, NID_sceIoDopen,   h_Dopen,   (void **)&o_Dopen);
	n += hook_one(L, NID_sceIoDread,   h_Dread,   (void **)&o_Dread);
	n += hook_one(L, NID_sceIoDclose,  h_Dclose,  (void **)&o_Dclose);
	n += hook_one(L, NID_sceIoGetstat, h_Getstat, (void **)&o_Getstat);
	n += hook_one(L, NID_sceIoOpen,    h_Open,    (void **)&o_Open);
	n += hook_one(L, NID_sceIoRead,    h_Read,    (void **)&o_Read);
	n += hook_one(L, NID_sceIoClose,   h_Close,   (void **)&o_Close);
	n += hook_one(L, NID_sceIoLseek32, h_Lseek32, (void **)&o_Lseek32);

	/* Launch redirect: resolve the VSHDisc bridge and hook the VSHMs2
	 * bridge so picking a virtual entry mounts the ISO and boots it. */
	vshdisc_fn = (int (*)(const char *, struct pxp2_vshparam *))
	             pxp2ctrl_find_import("vshmain_module", VSH_BRIDGE_LIB, NID_VSHDisc_bridge);
	if (!vshdisc_fn)
		vshdisc_fn = (int (*)(const char *, struct pxp2_vshparam *))
		             pxp2ctrl_find_import("vsh_module", VSH_BRIDGE_LIB, NID_VSHDisc_bridge);
	{
		unsigned int stub = pxp2ctrl_find_import("vshmain_module", VSH_BRIDGE_LIB, NID_VSHMs2_bridge);
		if (!stub)
			stub = pxp2ctrl_find_import("vsh_module", VSH_BRIDGE_LIB, NID_VSHMs2_bridge);
		if (stub) {
			o_VSHMs2 = (int (*)(const char *, struct pxp2_vshparam *))
			           pxp2ctrl_hook_import(stub, h_VSHMs2);
			n++;
		}
	}
	return n;
}
