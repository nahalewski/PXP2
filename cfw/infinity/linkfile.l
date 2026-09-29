/*
 * PXP2 CFW - Infinity compat module link script - GPL-3.0-or-later.
 *
 * Links the compat binary at Infinity's documented compatibility-module load
 * address (COMPATIBILITY_MODULE_ADDR = 0x88F50000 in Infinity 2.0.3's
 * firmware/compat_interface/CMakeLists.txt). This is Infinity's own placement
 * address for the module it loads, not a Sony firmware text offset; it is
 * VERIFIED_DOC against Infinity's public source. Entry is compat_entry, the
 * public ABI Infinity calls.
 */
OUTPUT_FORMAT("elf32-littlemips")
OUTPUT_ARCH(mips)

ENTRY(_start)

SECTIONS
{
  . = 0x88F50000;
  .text.start : { *(.text.start) }
  .text       : { *(.text) }
  .rodata     : { *(.rodata) *(.rodata.*) }
  .data       : { *(.data) }
  .bss        : { *(.bss) *(COMMON) }
}
