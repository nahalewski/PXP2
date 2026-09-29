/*
 * PXP2 CFW - ISO virtual UMD driver public interface
 *
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * ORIGINAL WORK from the public ISO9660 / CSO format descriptions and the
 * UMD state behaviour recorded in ../psp.md ("UMD drive"). No inferno/PRO
 * source was used.
 */
#ifndef PXP2_ISODRV_H
#define PXP2_ISODRV_H

#include <pspkerneltypes.h>

#define ISO_SECTOR_SIZE 2048

/* UMD drive states and devctl codes (VERIFIED_DOC, from ../psp.md). */
#define UMD_STAT_NODISC       0x01
#define UMD_STAT_PRESENT      0x12
#define UMD_STAT_READY        0x32
#define UMD_DISCTYPE_GAME     0x10
#define UMD_DISCTYPE_VIDEO    0x20
#define UMD_DISCTYPE_AUDIO    0x40
#define UMD_DEVCTL_DISCTYPE   0x01F20001u
#define UMD_DEVCTL_LBA_TOTAL  0x01F20002u
#define UMD_DEVCTL_LBA_LAST   0x01F20003u

/* CISO (.cso) v1 header - public format. */
typedef struct {
	char         magic[4];     /* "CISO"                                   */
	unsigned int header_size;  /* usually 0                                */
	unsigned long long total_bytes; /* size of the decompressed ISO        */
	unsigned int block_size;   /* usually 0x800 (2048)                     */
	unsigned char version;     /* 1                                        */
	unsigned char align;       /* index alignment shift                    */
	unsigned char reserved[2];
	/* followed by (total_bytes/block_size + 1) u32 index entries          */
} cso_header;

/* Open the backing image. Accepts .iso and .cso. Detects which by magic.
 * Returns 0 on success. Only one image is mounted at a time (one UMD). */
int  iso_open(const char *path);
void iso_close(void);
int  iso_is_open(void);

/* Total number of 2048-byte sectors in the (decompressed) image. */
unsigned int iso_total_sectors(void);

/* Read 'count' sectors starting at LBA 'lba' into buf. Handles CSO block
 * decompression transparently. Returns sectors read, or <0 on error. */
int iso_read_sectors(unsigned int lba, unsigned int count, void *buf);

/* Byte-accurate read (for file-system level access from isofs). */
int iso_read_bytes(unsigned long long offset, unsigned int len, void *buf);

/* --- UMD state machine (see psp.md "Drive state") -------------------
 * The driver keeps the fake drive state the VSH polls. systemctrl calls
 * iso_set_inserted(1) once an ISO is mounted; the state then advances
 * NODISC -> PRESENT -> READY exactly as the emulator observed. */
int iso_umd_get_stat(void);            /* 0x01 / 0x12 / 0x32               */
void iso_umd_set_inserted(int present);
int iso_umd_get_disctype(void);        /* 0x10/0x20/0x40 from UMD_DATA.BIN */

/* Devctl dispatcher used by the umd9660 block-device hook. cmd is the
 * 0x01f2xxxx / 0x01f1xxxx code; see psp.md. Returns 0 or an error code. */
int iso_umd_devctl(unsigned int cmd, void *indata, int inlen,
                   void *outdata, int outlen);

/* --- disc0: ISO9660 file layer (isodrv/iso9660fs.c) -----------------
 * A read-only in-kernel ISO9660 filesystem built on top of the raw sector
 * reader above (iso_read_sectors, so CSO is handled transparently). It
 * provides open/read/lseek and dopen/dread/getstat for files on the
 * mounted disc, which the guest reaches as disc0:/... . Public ECMA-119
 * layout only; no code from any CFW.
 *
 * Paths may include a leading "xxx:" device prefix (e.g.
 * "disc0:/PSP_GAME/PARAM.SFO"); it is ignored. Absolute inside the disc. */

/* A resolved directory record: where a file/dir lives on the disc. */
typedef struct {
	unsigned int lba;       /* first sector of the extent               */
	unsigned int size;      /* byte length of the extent                */
	int          is_dir;    /* 1 if a directory                          */
} iso9660_node;

/* Resolve a path to its record. Returns 0 on success. */
int iso9660_lookup(const char *path, iso9660_node *out);

/* File I/O by opaque handle (index >= 0, or <0 on error). */
int    iso9660_open(const char *path);
int    iso9660_read(int fd, void *buf, unsigned int len);
long long iso9660_lseek(int fd, long long ofs, int whence);
int    iso9660_close(int fd);

/* Directory enumeration by opaque handle. name_out receives the child's
 * name (without the ";1" version suffix). Returns >0 while entries remain,
 * 0 at end, <0 on error. is_dir_out / size_out may be NULL. */
int iso9660_dopen(const char *path);
int iso9660_dread(int dfd, char *name_out, int name_cap,
                  int *is_dir_out, unsigned int *size_out);
int iso9660_dclose(int dfd);

/* Reset all cached FS handles (called on mount/unmount). */
void iso9660_reset(void);

#endif /* PXP2_ISODRV_H */
