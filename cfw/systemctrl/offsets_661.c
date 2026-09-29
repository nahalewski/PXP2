/*
 * PXP2 CFW - 6.61 offset table data
 * Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
 *
 * See include/offsets_661.h for the verification-status contract.
 * Every text address below is 0 == UNVERIFIED. NID hooking is used
 * instead so the CFW can come up without any hardcoded flash offsets.
 */
#include "offsets_661.h"

const pxp2_model_offsets pxp2_offsets_661[5] = {
	/* model,            label,        loadcore, disc, ms2, decrypt, sig, vshchk */
	{ PXP2_MODEL_1000,   "PSP-1000",   0,0,0, 0,0, 0 }, /* all UNVERIFIED */
	{ PXP2_MODEL_2000,   "PSP-2000",   0,0,0, 0,0, 0 }, /* all UNVERIFIED */
	{ PXP2_MODEL_3000,   "PSP-3000",   0,0,0, 0,0, 0 }, /* all UNVERIFIED */
	{ PXP2_MODEL_4000,   "PSP-Go",     0,0,0, 0,0, 0 }, /* all UNVERIFIED */
	{ PXP2_MODEL_STREET, "PSP-E1000",  0,0,0, 0,0, 0 }, /* all UNVERIFIED */
};

const pxp2_model_offsets *pxp2_offsets_for(pxp2_model_t model)
{
	int i;
	for (i = 0; i < 5; i++) {
		if (pxp2_offsets_661[i].model == model)
			return &pxp2_offsets_661[i];
	}
	/* Fall back to PSP-1000 row. */
	return &pxp2_offsets_661[0];
}
