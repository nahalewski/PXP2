# PXP2 CFW - sceVshBridge import stubs
# Copyright (C) 2026 PXP2 project - GPL-3.0-or-later.
#
# Generates jump stubs for the firmware's sceVshBridge exports we use to
# launch discs/homebrew. NIDs are VERIFIED_DOC from ../psp.md:
#   0x63e69956  vshKernelLoadExecVSHDisc  (-> LoadExecForKernel:d8320a28)
#   0x21d4d038  vshKernelLoadExecVSHMs2   (-> LoadExecForKernel:d940c83c)
#   0xd47041ca  vshKernelExitVSHVSH       (-> LoadExecForKernel:08f7166c)
#
# These are the standard PSPSDK stub macros; the .S itself is original.

#include "pspstub.s"

	STUB_START "sceVshBridge", 0x40010000, 0x00030005
	STUB_FUNC  0x63e69956, sctrlKernelLoadExecVSHDisc
	STUB_FUNC  0x21d4d038, sctrlKernelLoadExecVSHMs2
	STUB_FUNC  0xd47041ca, sctrlKernelExitVSHVSH
	STUB_END
