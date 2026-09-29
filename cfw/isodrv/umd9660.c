/*
 * PXP2 CFW - virtual UMD block/file device (umd9660)
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * Registers I/O drivers that make the mounted .iso/.cso appear as the real
 * UMD would: a raw sector device on umd0: and an ISO9660 filesystem on
 * disc0: / isofs0:. Reads are backed by isodrv/iso.c ("inferno-style" plain
 * sector reads plus the CSO path). Devctl 0x01f2xxxx is answered from the
 * UMD state machine.
 *
 * ORIGINAL WORK. The SceIoDrv / PspIoDrvFuncs structures used here are the
 * public PSPSDK kernel I/O driver interface (<pspkerneltypes.h> /
 * <pspiofilemgr_kernel.h>), not code from any CFW.
 */
#include <string.h>
#include <stdlib.h>
#include <pspkernel.h>
#include <pspiofilemgr_kernel.h>
#include "isodrv.h"

/* Per-open file position for umd0: raw access. */
typedef struct {
	unsigned long long pos;
} umd_file;

/* ---- raw sector device (umd0:) ------------------------------------- */
static int umd_init(PspIoDrvArg *arg)  { (void)arg; return 0; }
static int umd_exit(PspIoDrvArg *arg)  { (void)arg; return 0; }

static int umd_open(PspIoDrvFileArg *f, char *file, int flags, SceMode mode)
{
	umd_file *uf;
	(void)file; (void)flags; (void)mode;
	if (!iso_is_open())
		return -1;
	uf = (umd_file *)malloc(sizeof(umd_file));
	if (!uf) return -1;
	uf->pos = 0;
	f->arg = (void *)uf;
	return 0;
}

static int umd_close(PspIoDrvFileArg *f)
{
	if (f->arg) { free(f->arg); f->arg = NULL; }
	return 0;
}

static int umd_read(PspIoDrvFileArg *f, char *data, int len)
{
	umd_file *uf = (umd_file *)f->arg;
	int got;
	if (!uf) return -1;
	got = iso_read_bytes(uf->pos, (unsigned int)len, data);
	if (got > 0) uf->pos += got;
	return got;
}

static SceOff umd_lseek(PspIoDrvFileArg *f, SceOff ofs, int whence)
{
	umd_file *uf = (umd_file *)f->arg;
	unsigned long long total = (unsigned long long)iso_total_sectors() * ISO_SECTOR_SIZE;
	if (!uf) return -1;
	switch (whence) {
		case PSP_SEEK_SET: uf->pos = (unsigned long long)ofs; break;
		case PSP_SEEK_CUR: uf->pos += (unsigned long long)ofs; break;
		case PSP_SEEK_END: uf->pos = total + (unsigned long long)ofs; break;
		default: return -1;
	}
	return (SceOff)uf->pos;
}

/* Devctl for umd0: - forward to the ISO UMD state machine. */
static int umd_devctl(PspIoDrvFileArg *f, const char *devname, unsigned int cmd,
                      void *indata, int inlen, void *outdata, int outlen)
{
	(void)f; (void)devname;
	return iso_umd_devctl(cmd, indata, inlen, outdata, outlen);
}

static PspIoDrvFuncs umd_funcs = {
	.IoInit   = umd_init,
	.IoExit   = umd_exit,
	.IoOpen   = umd_open,
	.IoClose  = umd_close,
	.IoRead   = umd_read,
	.IoWrite  = NULL,
	.IoLseek  = umd_lseek,
	.IoIoctl  = NULL,
	.IoRemove = NULL,
	.IoMkdir  = NULL,
	.IoRmdir  = NULL,
	.IoDopen  = NULL,
	.IoDclose = NULL,
	.IoDread  = NULL,
	.IoGetstat = NULL,
	.IoChstat  = NULL,
	.IoRename  = NULL,
	.IoChdir   = NULL,
	.IoMount   = NULL,
	.IoUmount  = NULL,
	.IoDevctl  = umd_devctl,
	.IoUnk21   = NULL,
};

static PspIoDrv umd_drv = {
	.name    = "umd",
	.dev_type = 0x04,     /* block device flag (documented) */
	.unk2    = 0x800,
	.name2   = "UMD9660",
	.funcs   = &umd_funcs,
};

/* ---- ISO9660 filesystem device (disc0: / isofs0:) ------------------
 * Read-only file access to the mounted image, backed by iso9660fs.c. This
 * is the layer the guest's BOOT.BIN and the firmware isofs use to open
 * files off the disc (disc0:/PSP_GAME/...). Handles are small ints stored
 * in f->arg (offset by 1 so 0 stays "no handle"). */

static int fs_init(PspIoDrvArg *arg) { (void)arg; return 0; }
static int fs_exit(PspIoDrvArg *arg) { (void)arg; return 0; }

static int fs_open(PspIoDrvFileArg *f, char *file, int flags, SceMode mode)
{
	int fd;
	(void)mode;
	/* Read-only filesystem: reject write intents. */
	if (flags & (PSP_O_WRONLY | PSP_O_CREAT | PSP_O_TRUNC | PSP_O_APPEND))
		return -1;
	if (!iso_is_open())
		return -1;
	fd = iso9660_open(file);
	if (fd < 0)
		return -1;
	f->arg = (void *)(fd + 1);
	return 0;
}

static int fs_close(PspIoDrvFileArg *f)
{
	int fd = (int)f->arg - 1;
	f->arg = NULL;
	if (fd < 0) return -1;
	return iso9660_close(fd);
}

static int fs_read(PspIoDrvFileArg *f, char *data, int len)
{
	int fd = (int)f->arg - 1;
	if (fd < 0 || len < 0) return -1;
	return iso9660_read(fd, data, (unsigned int)len);
}

static SceOff fs_lseek(PspIoDrvFileArg *f, SceOff ofs, int whence)
{
	int fd = (int)f->arg - 1;
	if (fd < 0) return -1;
	return (SceOff)iso9660_lseek(fd, (long long)ofs, whence);
}

/* Fill a SceIoStat from an ISO node. */
static void fill_stat(SceIoStat *st, int is_dir, unsigned int size)
{
	memset(st, 0, sizeof(*st));
	if (is_dir) {
		st->st_mode = 0x1000 | 0x0124; /* FIO_S_IFDIR | r-x r-x r-x        */
		st->st_attr = 0x0010;          /* FIO_SO_IFDIR                     */
	} else {
		st->st_mode = 0x2000 | 0x0124; /* FIO_S_IFREG | r-- perms          */
		st->st_attr = 0x0020;          /* FIO_SO_IFREG                     */
	}
	st->st_size = (SceOff)size;
}

static int fs_getstat(PspIoDrvFileArg *f, const char *file, SceIoStat *stat)
{
	iso9660_node node;
	(void)f;
	if (!iso_is_open())
		return -1;
	if (iso9660_lookup(file, &node) < 0)
		return -1;
	fill_stat(stat, node.is_dir, node.size);
	return 0;
}

static int fs_dopen(PspIoDrvFileArg *f, const char *dirname)
{
	int dfd;
	if (!iso_is_open())
		return -1;
	dfd = iso9660_dopen(dirname);
	if (dfd < 0)
		return -1;
	f->arg = (void *)(dfd + 1);
	return 0;
}

static int fs_dread(PspIoDrvFileArg *f, SceIoDirent *dir)
{
	int dfd = (int)f->arg - 1;
	int is_dir = 0, rc;
	unsigned int size = 0;
	if (dfd < 0) return -1;
	memset(dir, 0, sizeof(*dir));
	rc = iso9660_dread(dfd, dir->d_name, (int)sizeof(dir->d_name), &is_dir, &size);
	if (rc <= 0)
		return rc; /* 0 = end, <0 = error */
	fill_stat(&dir->d_stat, is_dir, size);
	return 1; /* one entry read; callers loop until 0 */
}

static int fs_dclose(PspIoDrvFileArg *f)
{
	int dfd = (int)f->arg - 1;
	f->arg = NULL;
	if (dfd < 0) return -1;
	return iso9660_dclose(dfd);
}

/* disc0: also answers the UMD devctls (disc type, LBA counts) that the VSH
 * issues against the filesystem device during a launch. */
static int fs_devctl(PspIoDrvFileArg *f, const char *devname, unsigned int cmd,
                     void *indata, int inlen, void *outdata, int outlen)
{
	(void)f; (void)devname;
	return iso_umd_devctl(cmd, indata, inlen, outdata, outlen);
}

static PspIoDrvFuncs fs_funcs = {
	.IoInit    = fs_init,
	.IoExit    = fs_exit,
	.IoOpen    = fs_open,
	.IoClose   = fs_close,
	.IoRead    = fs_read,
	.IoWrite   = NULL,
	.IoLseek   = fs_lseek,
	.IoIoctl   = NULL,
	.IoRemove  = NULL,
	.IoMkdir   = NULL,
	.IoRmdir   = NULL,
	.IoDopen   = fs_dopen,
	.IoDclose  = fs_dclose,
	.IoDread   = fs_dread,
	.IoGetstat = fs_getstat,
	.IoChstat  = NULL,
	.IoRename  = NULL,
	.IoChdir   = NULL,
	.IoMount   = NULL,
	.IoUmount  = NULL,
	.IoDevctl  = fs_devctl,
	.IoUnk21   = NULL,
};

static PspIoDrv disc0_drv = {
	.name     = "disc0",
	.dev_type = 0x10,     /* filesystem device flag (documented) */
	.unk2     = 0x800,
	.name2    = "ISOFS",
	.funcs    = &fs_funcs,
};

static PspIoDrv isofs0_drv = {
	.name     = "isofs0",
	.dev_type = 0x10,
	.unk2     = 0x800,
	.name2    = "ISOFS",
	.funcs    = &fs_funcs,
};

/*
 * Public entry the loader calls after mounting an ISO. Registers the umd
 * driver so umd0:/disc0: resolve to our image. For first bring-up we rely
 * on the firmware's own isofs (fatms-backed) being redirected by the
 * systemctrl I/O hook to our sector reader; a full ISO9660 dopen/dread
 * layer is planned (see DESIGN.md "ISO driver - staged").
 */
int umd9660_register(void)
{
	int rc;
	iso9660_reset();
	sceIoDelDrv("umd");
	sceIoDelDrv("disc0");
	sceIoDelDrv("isofs0");
	rc = sceIoAddDrv(&umd_drv);
	/* Register the ISO9660 file layer so disc0:/isofs0: resolve to files
	 * on the mounted image. These are additive; a failure of one does not
	 * stop the raw umd0: device from working. */
	sceIoAddDrv(&disc0_drv);
	sceIoAddDrv(&isofs0_drv);
	return rc;
}

int umd9660_unregister(void)
{
	sceIoDelDrv("disc0");
	sceIoDelDrv("isofs0");
	return sceIoDelDrv("umd");
}
