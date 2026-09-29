/*
 * PXP2 CFW - ISO / CSO image reader + UMD state machine
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * ORIGINAL WORK. ISO9660 sectoring and the CISO v1 container are public
 * formats; the UMD drive-state values (0x01/0x12/0x32), the disc-type
 * devctl 0x01f20001 writing out+4, and the UMD_DATA.BIN kind letters come
 * from ../psp.md, which recorded them from the real 6.61 firmware. The
 * CSO decompression uses zlib inflate (raw deflate blocks), as documented.
 */
#include <string.h>
#include <stdlib.h>
#include <pspiofilemgr.h>
#include <pspkernel.h>
#include "isodrv.h"

/* zlib is part of the PSPSDK ports. Raw inflate for CSO blocks. */
#include <zlib.h>

/* Kernel libc/libgcc here has no 64-bit divide (__udivdi3). PSP images use
 * power-of-two block sizes, so we divide by shifting. */
static int log2_pow2(unsigned int v)
{
	int s = 0;
	if (v == 0) return 11; /* default 2048 */
	while ((v & 1) == 0 && s < 31) { v >>= 1; s++; }
	return s;
}

static SceUID     g_fd = -1;
static int        g_is_cso = 0;
static int        g_block_shift = 11; /* log2(block_size), default 2048 */
static cso_header g_cso;
static unsigned int *g_cso_index = NULL;   /* (nblocks+1) entries          */
static unsigned int  g_cso_nblocks = 0;
static unsigned long long g_total_bytes = 0;

/* UMD fake-drive state. */
static int g_umd_stat = UMD_STAT_NODISC;
static int g_disctype = UMD_DISCTYPE_GAME;

/* ---- helpers ------------------------------------------------------- */
static int read_at(unsigned long long off, void *buf, int len)
{
	if (g_fd < 0) return -1;
	if (sceIoLseek(g_fd, (SceOff)off, PSP_SEEK_SET) < 0) return -1;
	return sceIoRead(g_fd, buf, len);
}

/* Derive the disc type from UMD_DATA.BIN, exactly as vshmain does
 * (psp.md: read 48 bytes of /UMD_DATA.BIN, scan bytes 33..46 for the kind
 * letters G/V/A and OR 0x10/0x20/0x40). We read the file through the
 * in-kernel ISO9660 layer, which is backed by iso_read_sectors and so works
 * as soon as the image is open. Falls back to GAME if the file is absent
 * (the common, safe case). */
static void probe_disctype(void)
{
	unsigned char data[48];
	int fd, n, i, type = 0;

	g_disctype = UMD_DISCTYPE_GAME;

	fd = iso9660_open("/UMD_DATA.BIN");
	if (fd < 0)
		return;
	n = iso9660_read(fd, data, sizeof(data));
	iso9660_close(fd);
	if (n < 48)
		return; /* too short -> keep GAME default */

	for (i = 33; i <= 46; i++) {
		if (data[i] == 'G') type |= UMD_DISCTYPE_GAME;
		else if (data[i] == 'V') type |= UMD_DISCTYPE_VIDEO;
		else if (data[i] == 'A') type |= UMD_DISCTYPE_AUDIO;
	}
	if (type)
		g_disctype = type;
}

/* ---- open / close -------------------------------------------------- */
int iso_open(const char *path)
{
	char magic[4];

	iso_close();

	g_fd = sceIoOpen(path, PSP_O_RDONLY, 0);
	if (g_fd < 0)
		return -1;

	if (read_at(0, magic, 4) == 4 && memcmp(magic, "CISO", 4) == 0) {
		unsigned int idxbytes;
		g_is_cso = 1;
		if (read_at(0, &g_cso, sizeof(g_cso)) != (int)sizeof(g_cso))
			goto fail;
		if (g_cso.block_size == 0) g_cso.block_size = ISO_SECTOR_SIZE;
		g_block_shift = log2_pow2(g_cso.block_size);
		g_total_bytes = g_cso.total_bytes;
		g_cso_nblocks = (unsigned int)(g_total_bytes >> g_block_shift);
		idxbytes = (g_cso_nblocks + 1) * sizeof(unsigned int);
		g_cso_index = (unsigned int *)malloc(idxbytes);
		if (!g_cso_index)
			goto fail;
		if (read_at(sizeof(g_cso), g_cso_index, idxbytes) != (int)idxbytes)
			goto fail;
	} else {
		SceOff sz;
		g_is_cso = 0;
		sz = sceIoLseek(g_fd, 0, PSP_SEEK_END);
		if (sz < 0) goto fail;
		g_total_bytes = (unsigned long long)sz;
	}

	probe_disctype();
	g_umd_stat = UMD_STAT_NODISC; /* becomes present on iso_umd_set_inserted */
	return 0;

fail:
	iso_close();
	return -1;
}

void iso_close(void)
{
	if (g_cso_index) { free(g_cso_index); g_cso_index = NULL; }
	if (g_fd >= 0)   { sceIoClose(g_fd);  g_fd = -1; }
	g_is_cso = 0;
	g_cso_nblocks = 0;
	g_total_bytes = 0;
	g_umd_stat = UMD_STAT_NODISC;
}

int iso_is_open(void) { return g_fd >= 0; }

unsigned int iso_total_sectors(void)
{
	return (unsigned int)(g_total_bytes >> 11); /* /2048 */
}

/* ---- CSO block read ------------------------------------------------ */
/* Index entry: bit 31 = "not compressed" (stored raw); low 31 bits << align
 * give the file offset. The next entry gives the end, so length is implied.
 * This is the documented CISO v1 layout. */
static int cso_read_block(unsigned int block, unsigned char *out2048)
{
	unsigned int e  = g_cso_index[block];
	unsigned int e2 = g_cso_index[block + 1];
	unsigned int align = g_cso.align;
	unsigned long long pos  = (unsigned long long)(e  & 0x7FFFFFFFu) << align;
	unsigned long long pos2 = (unsigned long long)(e2 & 0x7FFFFFFFu) << align;
	int comp_len = (int)(pos2 - pos);
	unsigned char inbuf[ISO_SECTOR_SIZE * 2];

	if (comp_len <= 0 || comp_len > (int)sizeof(inbuf))
		return -1;
	if (read_at(pos, inbuf, comp_len) != comp_len)
		return -1;

	if (e & 0x80000000u) {
		/* stored uncompressed */
		int n = comp_len < ISO_SECTOR_SIZE ? comp_len : ISO_SECTOR_SIZE;
		memcpy(out2048, inbuf, n);
		if (n < ISO_SECTOR_SIZE)
			memset(out2048 + n, 0, ISO_SECTOR_SIZE - n);
		return 0;
	} else {
		/* raw deflate */
		z_stream zs;
		int ret;
		memset(&zs, 0, sizeof(zs));
		if (inflateInit2(&zs, -15) != Z_OK)
			return -1;
		zs.next_in = inbuf;      zs.avail_in  = comp_len;
		zs.next_out = out2048;   zs.avail_out = ISO_SECTOR_SIZE;
		ret = inflate(&zs, Z_FINISH);
		inflateEnd(&zs);
		if (ret != Z_STREAM_END && ret != Z_OK)
			return -1;
		return 0;
	}
}

/* ---- sector read --------------------------------------------------- */
int iso_read_sectors(unsigned int lba, unsigned int count, void *buf)
{
	unsigned char *out = (unsigned char *)buf;
	unsigned int i;

	if (g_fd < 0)
		return -1;

	if (!g_is_cso) {
		unsigned long long off = (unsigned long long)lba * ISO_SECTOR_SIZE;
		int want = count * ISO_SECTOR_SIZE;
		int got  = read_at(off, out, want);
		if (got < 0) return -1;
		return got / ISO_SECTOR_SIZE;
	}

	/* CSO: block_size may differ from 2048, but PSP images use 2048. */
	for (i = 0; i < count; i++) {
		unsigned int block = lba + i;
		if (block >= g_cso_nblocks)
			break;
		if (cso_read_block(block, out + i * ISO_SECTOR_SIZE) < 0)
			return -1;
	}
	return (int)i;
}

int iso_read_bytes(unsigned long long offset, unsigned int len, void *buf)
{
	unsigned char sector[ISO_SECTOR_SIZE];
	unsigned char *out = (unsigned char *)buf;
	unsigned int done = 0;

	while (done < len) {
		unsigned long long cur = offset + done;
		unsigned int lba = (unsigned int)(cur >> 11);       /* /2048 */
		unsigned int within = (unsigned int)(cur & 2047u);  /* %2048 */
		unsigned int chunk = ISO_SECTOR_SIZE - within;
		if (chunk > len - done) chunk = len - done;
		if (iso_read_sectors(lba, 1, sector) < 1)
			return -1;
		memcpy(out + done, sector + within, chunk);
		done += chunk;
	}
	return (int)done;
}

/* ---- UMD state machine -------------------------------------------- */
int iso_umd_get_stat(void)      { return g_umd_stat; }
int iso_umd_get_disctype(void)  { return g_disctype; }

void iso_umd_set_inserted(int present)
{
	if (present && g_fd >= 0)
		g_umd_stat = UMD_STAT_READY;   /* present + initialised + activated */
	else
		g_umd_stat = UMD_STAT_NODISC;
}

/* Devctl dispatcher, mirroring psp.md "devctl on umd0:". We write only the
 * documented output fields and never clobber the caller's size word. */
int iso_umd_devctl(unsigned int cmd, void *indata, int inlen,
                   void *outdata, int outlen)
{
	(void)indata; (void)inlen;
	switch (cmd) {
		case UMD_DEVCTL_DISCTYPE: /* 0x01f20001: write only out+4 */
			if (outdata && outlen >= 8)
				((int *)outdata)[1] = g_disctype;
			return 0;
		case UMD_DEVCTL_LBA_TOTAL: /* 0x01f20002 */
		case UMD_DEVCTL_LBA_LAST:  /* 0x01f20003 */
			if (outdata && outlen >= 4)
				((int *)outdata)[0] = (int)iso_total_sectors();
			return 0;
		default:
			/* seek/cache hints 0x01f100a3..a9, 0x01f300a5: accept as no-ops */
			return 0;
	}
}
