/*
 * PXP2 CFW - minimal user-mode ISO9660 reader (for the XMB plugin)
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * Reads a file out of an .iso by path (e.g. /PSP_GAME/PARAM.SFO). Public
 * ISO9660 format (ECMA-119). CSO is not read here; the plugin lists .iso
 * for metadata and hands the path to the kernel driver for the actual boot.
 */
#include <string.h>
#include <pspiofilemgr.h>
#include "iso9660.h"

#define SECTOR 2048

static int read_sector(SceUID fd, unsigned int lba, void *buf)
{
	if (sceIoLseek(fd, (SceOff)lba * SECTOR, PSP_SEEK_SET) < 0)
		return -1;
	return sceIoRead(fd, buf, SECTOR) == SECTOR ? 0 : -1;
}

static unsigned int le32(const unsigned char *p)
{
	return p[0] | (p[1] << 8) | (p[2] << 16) | ((unsigned int)p[3] << 24);
}

/* Case-insensitive compare of an ISO dir name (may carry ";1"). */
static int name_eq(const unsigned char *rec, int reclen, const char *want)
{
	int nlen = rec[0];
	int i;
	const unsigned char *nm = rec + 1;
	int wl = strlen(want);
	(void)reclen;
	/* strip version suffix ";1" */
	for (i = 0; i < nlen; i++)
		if (nm[i] == ';') { nlen = i; break; }
	if (nlen != wl) return 0;
	for (i = 0; i < wl; i++) {
		unsigned char a = nm[i], b = (unsigned char)want[i];
		if (a >= 'a' && a <= 'z') a -= 32;
		if (b >= 'a' && b <= 'z') b -= 32;
		if (a != b) return 0;
	}
	return 1;
}

/* Find a child record in a directory extent. Returns 0 and fills *out_lba /
 * *out_size on success. is_dir selects file vs directory. */
static int find_in_dir(SceUID fd, unsigned int dir_lba, unsigned int dir_size,
                       const char *name, int want_dir,
                       unsigned int *out_lba, unsigned int *out_size)
{
	unsigned char sec[SECTOR];
	unsigned int sectors = (dir_size + SECTOR - 1) / SECTOR;
	unsigned int s;

	for (s = 0; s < sectors; s++) {
		int off = 0;
		if (read_sector(fd, dir_lba + s, sec) < 0)
			return -1;
		while (off < SECTOR) {
			unsigned char *rec = sec + off;
			int reclen = rec[0];
			if (reclen == 0)
				break; /* rest of sector is padding */
			if (reclen >= 33) {
				int is_dir = (rec[25] & 0x02) ? 1 : 0;
				if (is_dir == want_dir && name_eq(rec + 32, rec[32], name)) {
					*out_lba  = le32(rec + 2);
					*out_size = le32(rec + 10);
					return 0;
				}
			}
			off += reclen;
		}
	}
	return -1;
}

/* Read /A/B or /A style path. Only handles absolute paths of depth <= 3. */
int iso9660_read_file(const char *iso_path, const char *file_path,
                      void *buf, int maxlen)
{
	SceUID fd;
	unsigned char pvd[SECTOR];
	unsigned int lba, size;
	char comp[64];
	const char *p = file_path;
	int rc = -1;

	fd = sceIoOpen(iso_path, PSP_O_RDONLY, 0);
	if (fd < 0)
		return -1;

	/* Primary Volume Descriptor at sector 16. Root dir record at +156. */
	if (read_sector(fd, 16, pvd) < 0)
		goto out;
	if (memcmp(pvd + 1, "CD001", 5) != 0)
		goto out;
	lba  = le32(pvd + 156 + 2);
	size = le32(pvd + 156 + 10);

	if (*p == '/') p++;
	while (*p) {
		int n = 0;
		const char *slash = strchr(p, '/');
		int last = (slash == NULL);
		int seglen = last ? (int)strlen(p) : (int)(slash - p);
		if (seglen <= 0 || seglen >= (int)sizeof(comp))
			goto out;
		memcpy(comp, p, seglen);
		comp[seglen] = 0;
		n = seglen;
		(void)n;
		if (find_in_dir(fd, lba, size, comp, last ? 0 : 1, &lba, &size) < 0)
			goto out;
		if (last)
			break;
		p = slash + 1;
	}

	/* lba/size now point at the file. Read up to maxlen. */
	{
		int want = (int)size < maxlen ? (int)size : maxlen;
		int done = 0;
		unsigned char *o = (unsigned char *)buf;
		while (done < want) {
			unsigned char sec[SECTOR];
			int chunk = want - done < SECTOR ? want - done : SECTOR;
			if (read_sector(fd, lba + done / SECTOR, sec) < 0)
				goto out;
			memcpy(o + done, sec, chunk);
			done += chunk;
		}
		rc = want;
	}
out:
	sceIoClose(fd);
	return rc;
}
