/*
 * PXP2 CFW - Infinity 2 compatibility module (the "Infinity entry")
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * WHAT THIS IS
 * ------------
 * A tiny compatibility module in the form Infinity 2 loads at boot. Infinity
 * (installed by the user, not shipped here) reads flash1:/config.inf for the
 * key "inf_boot_compat_path"; if it points at this binary, Infinity loads it
 * to its documented compatibility-module address and calls compat_entry()
 * during the reboot phase, passing us the boot module list (btcnf) and two
 * callbacks to edit it (see infinity_compat.h and cfw/INFINITY.md).
 *
 * WHAT IT DOES
 * ------------
 * It uses ONLY those Infinity-provided callbacks to insert PXP2 CFW's kernel
 * module (pxp2_systemctrl.prx) into the boot module list for every runlevel.
 * From then on the boot behaves like the HEN path: systemctrl comes up in
 * kernel mode, installs its hooks, registers the ISO driver and loads the VSH
 * plugin. loader.prx is not used on this path; systemctrl is started directly
 * by the firmware module loader.
 *
 * WHAT IT DELIBERATELY DOES NOT DO (scope + safety)
 * -------------------------------------------------
 *   - No flash / NAND / IPL writes. The user installs Infinity and copies
 *     PXP2 CFW into place themselves.
 *   - No hardcoded 6.61 text offsets. It calls Infinity's callbacks only.
 *   - No IPL/bootloader/loadcore byte-patching. In particular it does NOT
 *     reproduce PRO/ME's loadcore decrypt/signcheck patch. That means an
 *     UNSIGNED pxp2_systemctrl.prx inserted here will only load if the boot
 *     path already accepts it (see the GAP note in cfw/INFINITY.md). This is
 *     reported honestly rather than guessed at.
 *
 * ORIGINAL WORK. The compat_entry contract it matches is Infinity's public
 * MIT interface (credited in infinity_compat.h and INFINITY.md); no Infinity
 * source is copied.
 */
#include "infinity_compat.h"

/* Where the user places the CFW kernel module inside flash for the Infinity
 * path. Infinity's own modules live in flash0:/kd; PXP2 uses a distinct name
 * so it never collides with a Sony or PRO/ME module. The btcnf path is the
 * in-flash "/kd/..." form (no device prefix), matching how Infinity's own
 * compat modules name their inserts. */
#define PXP2_SYSTEMCTRL_BTCNF   "/kd/pxp2_systemctrl.prx"

/* Anchor module already present in every 6.61 boot list. init.prx is the
 * standard, model-independent anchor PRO/ME insert relative to. */
#define PXP2_ANCHOR_MODULE      "/kd/init.prx"

int compat_entry(BtcnfHeader *btcnf, int btcnf_size,
                 INSERT_BTCNF insert_btcnf, REPLACE_BTCNF replace_btcnf,
                 int model, int is_recovery, int firmware)
{
	(void)model; (void)is_recovery; (void)firmware; (void)replace_btcnf;

	if (!btcnf || !insert_btcnf)
		return btcnf_size;

	/* Insert PXP2 systemctrl for every runlevel, so the CFW is active in the
	 * VSH, in games, in the updater and in the other apitypes - the same
	 * coverage the HEN loader gives once it starts systemctrl. */
	insert_btcnf(PXP2_SYSTEMCTRL_BTCNF, PXP2_ANCHOR_MODULE, btcnf, &btcnf_size,
	             (u16)(BOOTLOAD_VSH | BOOTLOAD_GAME | BOOTLOAD_UPDATER |
	                   BOOTLOAD_POPS | BOOTLOAD_APP | BOOTLOAD_UMDEMU |
	                   BOOTLOAD_MLNAPP));

	return btcnf_size;
}
