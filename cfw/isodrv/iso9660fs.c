/*
 * PXP2 CFW - in-kernel ISO9660 read-only file layer (disc0:)
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * Provides open/read/lseek and dopen/dread/getstat over the mounted .iso/.cso
 * image. It sits on top of iso.c's raw sector reader (iso_read_sectors), so a
 * CSO image is decompressed transparently and this code never sees the
 * container format.
 *
 * ORIGINAL WORK, written from the public ISO9660 / ECMA-119 volume and
 * directory-record layout. No inferno / PRO / ARK source was used. The disc
 * device names ("disc0:", "isofs0:") and the fact that the guest boots
 * disc0:/PSP_GAME/SYSDIR/BOOT.BIN come from ../psp.md's UMD findings.
 */
#include <string.h>
#include "isodrv.h"

/* iso.c */
extern int iso_read_sectors(unsigned int lba, unsigned int count, void *buf);
extern int iso_is_open(void);

#define ISOFS_MAX_FILES 16
#define ISOFS_MAX_DIRS  8
#define PVD_LBA         16
#define ROOT_REC_OFF    156   /* root directory record inside the PVD  */

/* ---- open-file / open-dir tables ----------------------------------- */
typedef struct {
	int          used;
	unsigned int lba;         /* extent start                          */
	unsigned int size;        /* file size in bytes                    */
	unsigned long long pos;   /* read cursor                           */
} isofs_file;

typedef struct {
	int          used;
	unsigned int lba;         /* directory extent start                */
	unsigned int size;        /* directory extent length               */
	unsigned int off;         /* byte cursor within the extent         */
} isofs_dir;

static isofs_file g_files[ISOFS_MAX_FILES];
static isofs_dir  g_dirs[ISOFS_MAX_DIRS];

/* ---- little helpers ------------------------------------------------ */
static unsigned int le32(const unsigned char *p)
{
	return p[0] | (p[1] << 8) | (p[2] << 16) | ((unsigned int)p[3] << 24);
}

/* Skip any leading "device:" prefix and return the in-disc absolute path. */
static const char *strip_device(const char *path)
{
	const char *c = strchr(path, ':');
	return c ? c + 1 : path;
}

/* Case-insensitive compare of an ISO identifier (may carry a ";1" version)
 * against a wanted component. */
static int name_eq(const unsigned char *id, int idlen, const char *want, int wlen)
{
	int i;
	for (i = 0; i < idlen; i++)
		if (id[i] == ';') { idlen = i; break; }
	/* A trailing '.' is sometimes present on files with no extension. */
	if (idlen > 0 && id[idlen - 1] == '.') idlen--;
	if (idlen != wlen) return 0;
	for (i = 0; i < wlen; i++) {
		unsigned char a = id[i], b = (unsigned char)want[i];
		if (a >= 'a' && a <= 'z') a -= 32;
		if (b >= 'a' && b <= 'z') b -= 32;
		if (a != b) return 0;
	}
	return 1;
}

/* Copy an ISO identifier into out (stripping ";1"), NUL-terminated. */
static void copy_name(const unsigned char *id, int idlen, char *out, int cap)
{
	int i, n = idlen;
	for (i = 0; i < idlen; i++)
		if (id[i] == ';') { n = i; break; }
	if (n > 0 && id[n - 1] == '.') n--;
	if (n > cap - 1) n = cap - 1;
	for (i = 0; i < n; i++) out[i] = id[i];
	out[n] = 0;
}

/* Read the root directory record from the PVD. Returns 0 on success. */
static int read_root(unsigned int *root_lba, unsigned int *root_size)
{
	unsigned char sec[ISO_SECTOR_SIZE];
	if (!iso_is_open())
		return -1;
	if (iso_read_sectors(PVD_LBA, 1, sec) < 1)
		return -1;
	if (memcmp(sec + 1, "CD001", 5) != 0)
		return -1;
	*root_lba  = le32(sec + ROOT_REC_OFF + 2);
	*root_size = le32(sec + ROOT_REC_OFF + 10);
	return 0;
}

/* Find a named child inside the directory extent [dir_lba, dir_size).
 * want_dir: 1 match a directory, 0 match a file, -1 either. */
static int find_child(unsigned int dir_lba, unsigned int dir_size,
                      const char *name, int wlen, int want_dir,
                      iso9660_node *out)
{
	unsigned char sec[ISO_SECTOR_SIZE];
	unsigned int nsectors = (dir_size + ISO_SECTOR_SIZE - 1) / ISO_SECTOR_SIZE;
	unsigned int s;

	for (s = 0; s < nsectors; s++) {
		int off = 0;
		if (iso_read_sectors(dir_lba + s, 1, sec) < 1)
			return -1;
		while (off < ISO_SECTOR_SIZE) {
			unsigned char *rec = sec + off;
			int reclen = rec[0];
			if (reclen == 0)
				break; /* padding to end of sector */
			if (reclen >= 33 && off + reclen <= ISO_SECTOR_SIZE) {
				int idlen  = rec[32];
				int is_dir = (rec[25] & 0x02) ? 1 : 0;
				/* skip "." (id 0x00) and ".." (id 0x01) self records */
				if (!(idlen == 1 && (rec[33] == 0 || rec[33] == 1))) {
					if ((want_dir < 0 || is_dir == want_dir) &&
					    name_eq(rec + 33, idlen, name, wlen)) {
						out->lba    = le32(rec + 2);
						out->size   = le32(rec + 10);
						out->is_dir = is_dir;
						return 0;
					}
				}
			}
			off += reclen;
		}
	}
	return -1;
}

/* ---- public: path resolution -------------------------------------- */
int iso9660_lookup(const char *path, iso9660_node *out)
{
	unsigned int lba, size;
	const char *p = strip_device(path);

	if (read_root(&lba, &size) < 0)
		return -1;

	if (*p == '/') p++;

	/* Root itself. */
	if (*p == 0) {
		out->lba = lba; out->size = size; out->is_dir = 1;
		return 0;
	}

	for (;;) {
		const char *slash = strchr(p, '/');
		int last = (slash == NULL);
		int seg = last ? (int)strlen(p) : (int)(slash - p);
		iso9660_node node;
		if (seg <= 0)
			return -1;
		if (find_child(lba, size, p, seg, last ? -1 : 1, &node) < 0)
			return -1;
		if (last) {
			*out = node;
			return 0;
		}
		lba = node.lba; size = node.size;
		p = slash + 1;
	}
}

/* ---- public: file I/O --------------------------------------------- */
int iso9660_open(const char *path)
{
	iso9660_node node;
	int i;
	if (iso9660_lookup(path, &node) < 0 || node.is_dir)
		return -1;
	for (i = 0; i < ISOFS_MAX_FILES; i++) {
		if (!g_files[i].used) {
			g_files[i].used = 1;
			g_files[i].lba  = node.lba;
			g_files[i].size = node.size;
			g_files[i].pos  = 0;
			return i;
		}
	}
	return -1; /* table full */
}

int iso9660_read(int fd, void *buf, unsigned int len)
{
	isofs_file *f;
	unsigned char sec[ISO_SECTOR_SIZE];
	unsigned char *out = (unsigned char *)buf;
	unsigned int done = 0;

	if (fd < 0 || fd >= ISOFS_MAX_FILES || !g_files[fd].used)
		return -1;
	f = &g_files[fd];

	if (f->pos >= f->size)
		return 0;
	if (len > f->size - f->pos)
		len = (unsigned int)(f->size - f->pos);

	while (done < len) {
		unsigned long long cur = (unsigned long long)f->lba * ISO_SECTOR_SIZE + f->pos;
		unsigned int lba    = (unsigned int)(cur >> 11);
		unsigned int within = (unsigned int)(cur & 2047u);
		unsigned int chunk  = ISO_SECTOR_SIZE - within;
		if (chunk > len - done) chunk = len - done;
		if (iso_read_sectors(lba, 1, sec) < 1)
			return done > 0 ? (int)done : -1;
		memcpy(out + done, sec + within, chunk);
		done   += chunk;
		f->pos += chunk;
	}
	return (int)done;
}

long long iso9660_lseek(int fd, long long ofs, int whence)
{
	isofs_file *f;
	long long np;
	if (fd < 0 || fd >= ISOFS_MAX_FILES || !g_files[fd].used)
		return -1;
	f = &g_files[fd];
	switch (whence) {
		case 0: np = ofs; break;                        /* SEEK_SET */
		case 1: np = (long long)f->pos + ofs; break;    /* SEEK_CUR */
		case 2: np = (long long)f->size + ofs; break;   /* SEEK_END */
		default: return -1;
	}
	if (np < 0) np = 0;
	if (np > (long long)f->size) np = f->size;
	f->pos = (unsigned long long)np;
	return np;
}

int iso9660_close(int fd)
{
	if (fd < 0 || fd >= ISOFS_MAX_FILES || !g_files[fd].used)
		return -1;
	g_files[fd].used = 0;
	return 0;
}

/* ---- public: directory enumeration -------------------------------- */
int iso9660_dopen(const char *path)
{
	iso9660_node node;
	int i;
	if (iso9660_lookup(path, &node) < 0 || !node.is_dir)
		return -1;
	for (i = 0; i < ISOFS_MAX_DIRS; i++) {
		if (!g_dirs[i].used) {
			g_dirs[i].used = 1;
			g_dirs[i].lba  = node.lba;
			g_dirs[i].size = node.size;
			g_dirs[i].off  = 0;
			return i;
		}
	}
	return -1;
}

int iso9660_dread(int dfd, char *name_out, int name_cap,
                  int *is_dir_out, unsigned int *size_out)
{
	isofs_dir *d;
	unsigned char sec[ISO_SECTOR_SIZE];

	if (dfd < 0 || dfd >= ISOFS_MAX_DIRS || !g_dirs[dfd].used)
		return -1;
	d = &g_dirs[dfd];

	while (d->off < d->size) {
		unsigned int s      = d->off / ISO_SECTOR_SIZE;
		unsigned int within = d->off % ISO_SECTOR_SIZE;
		unsigned char *rec;
		int reclen, idlen, is_dir;

		if (iso_read_sectors(d->lba + s, 1, sec) < 1)
			return -1;
		rec    = sec + within;
		reclen = rec[0];
		if (reclen == 0) {
			/* padding: jump to next sector */
			d->off = (s + 1) * ISO_SECTOR_SIZE;
			continue;
		}
		if (within + reclen > ISO_SECTOR_SIZE) {
			d->off = (s + 1) * ISO_SECTOR_SIZE;
			continue;
		}
		d->off += reclen;
		if (reclen < 33)
			continue;
		idlen  = rec[32];
		is_dir = (rec[25] & 0x02) ? 1 : 0;
		/* skip "." and ".." */
		if (idlen == 1 && (rec[33] == 0 || rec[33] == 1))
			continue;
		if (name_out)
			copy_name(rec + 33, idlen, name_out, name_cap);
		if (is_dir_out) *is_dir_out = is_dir;
		if (size_out)   *size_out   = le32(rec + 10);
		return 1;
	}
	return 0; /* end */
}

int iso9660_dclose(int dfd)
{
	if (dfd < 0 || dfd >= ISOFS_MAX_DIRS || !g_dirs[dfd].used)
		return -1;
	g_dirs[dfd].used = 0;
	return 0;
}

void iso9660_reset(void)
{
	memset(g_files, 0, sizeof(g_files));
	memset(g_dirs,  0, sizeof(g_dirs));
}
