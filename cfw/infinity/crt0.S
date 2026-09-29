# PXP2 CFW - Infinity compat module startup stub - GPL-3.0-or-later.
#
# The compat binary is a raw image loaded by Infinity at its documented
# compatibility-module address and entered at its start. This stub is the
# whole "startup": jump straight to compat_entry with the arguments Infinity
# already placed in a0..a6 (btcnf, size, insert_btcnf, replace_btcnf, model,
# is_recovery, firmware). No C runtime is set up or needed - compat_entry is
# leaf-simple and returns a value in v0.
#
# ORIGINAL WORK (a one-instruction tail-call). The register/argument order is
# Infinity's public compat_entry ABI (see infinity_compat.h).

	.set noreorder
	.text
	.global _start
	.ent _start
_start:
	j   compat_entry
	nop
	.end _start
