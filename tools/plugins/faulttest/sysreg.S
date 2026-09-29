# Import stub for one hardware-driver function PXP2 does not implement (NID from the PSPSDK libpspkernel.a).
	.set noreorder

#include "pspimport.s"

	IMPORT_START "sceSysreg_driver",0x00010000
	IMPORT_FUNC  "sceSysreg_driver",0xDE59DACB,sceSysregMeResetEnable
