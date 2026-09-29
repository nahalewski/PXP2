/*
 * PXP2 CFW - Infinity 2 compatibility-module interface
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * =====================================================================
 *  INTEROPERABILITY INTERFACE  --  credit and provenance
 * =====================================================================
 * The type layout and the compat_entry() function-pointer signature in this
 * header describe the PUBLIC hand-off contract of Infinity 2, an established
 * separate project by David "Davee" Morgan:
 *
 *     https://github.com/DaveeFTW/Infinity   (version 2.0.3)
 *     Licence: MIT (Copyright (C) 2015-2019 David "Davee" Morgan)
 *
 * Infinity's own headers that define this contract are:
 *     firmware/compat_interface/include/compat_interface.h   (compat_entry,
 *         INSERT_BTCNF / REPLACE_BTCNF, COMPAT_ENTRY)
 *     sdk/include/pspinit.h                                  (BtcnfHeader,
 *         ModuleEntry, BOOTLOAD_* flags)
 *
 * We re-declare the SAME contract here (independently, for GPL cleanliness)
 * so that a PXP2 compatibility module can interoperate with an Infinity
 * installation the user set up themselves. No Infinity source is copied into
 * this project, and no Infinity, Sony or third-party binary is bundled.
 * These are the minimum declarations needed to match Infinity's ABI.
 *
 * VERIFICATION: the signature and the btcnf layout are VERIFIED_DOC against
 * Infinity's public source (see cfw/INFINITY.md). They are not hardware
 * offsets. This module contains NO hardcoded firmware text addresses.
 * =====================================================================
 */
#ifndef PXP2_INFINITY_COMPAT_H
#define PXP2_INFINITY_COMPAT_H

typedef unsigned int   u32;
typedef unsigned short u16;
typedef unsigned char  u8;

/* pspbtcnf boot-config header (ECMA-ish packed layout used by the PSP boot
 * loader). Matches Infinity's pspinit.h BtcnfHeader. */
typedef struct BtcnfHeader {
	u32 signature;    /* 0x00: 0x0F803001                                 */
	u32 devkit;       /* 0x04                                             */
	u32 unknown[2];   /* 0x08                                             */
	u32 modestart;    /* 0x10                                             */
	int nmodes;       /* 0x14                                             */
	u32 unknown2[2];  /* 0x18                                             */
	u32 modulestart;  /* 0x20                                             */
	int nmodules;     /* 0x24                                             */
	u32 unknown3[2];  /* 0x28                                             */
	u32 modnamestart; /* 0x30                                             */
	u32 modnameend;   /* 0x34                                             */
	u32 unknown4[2];  /* 0x38                                             */
} __attribute__((packed)) BtcnfHeader;

/* Boot runlevel flags (Infinity pspinit.h BootLoadFlags). */
enum {
	BOOTLOAD_VSH     = 1,
	BOOTLOAD_GAME    = 2,
	BOOTLOAD_UPDATER = 4,
	BOOTLOAD_POPS    = 8,
	BOOTLOAD_APP     = 32,
	BOOTLOAD_UMDEMU  = 64,
	BOOTLOAD_MLNAPP  = 128,
};

/* Callbacks Infinity passes to the compat module. They edit the in-memory
 * btcnf buffer; the module never touches flash itself. */
typedef int (*INSERT_BTCNF)(char *new_mod, char *before_mod,
                            BtcnfHeader *header, int *size, u16 flags);
typedef int (*REPLACE_BTCNF)(char *new_mod, char *replace_mod,
                             BtcnfHeader *header, int *size, u16 flags);

/* The entry Infinity calls in the reboot phase. Returns the new btcnf size. */
int compat_entry(BtcnfHeader *btcnf, int btcnf_size,
                 INSERT_BTCNF insert_btcnf, REPLACE_BTCNF replace_btcnf,
                 int model, int is_recovery, int firmware);

#endif /* PXP2_INFINITY_COMPAT_H */
