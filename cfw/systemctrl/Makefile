# PXP2 CFW - systemctrl (kernel PRX) - GPL-3.0-or-later
# Builds systemctrl.prx, which also contains the ISO/UMD driver.

TARGET = systemctrl
OBJS = systemctrl.o hooks.o config.o plugins.o gamelist.o offsets_661.o \
       ../isodrv/iso.o ../isodrv/umd9660.o ../isodrv/iso9660fs.o ../isodrv/kalloc.o

BUILD_PRX = 1
PRX_EXPORTS = exports.exp

# Kernel-mode module.
USE_KERNEL_LIBS = 1
USE_KERNEL_LIBC = 1

PSP_FW_VERSION = 661

INCDIR   = ../include ../isodrv
CFLAGS   = -O2 -G0 -Wall -fno-builtin
ASFLAGS  = $(CFLAGS)

# zlib for the CSO path.
LIBS = -lz

PSPSDK = $(shell psp-config --pspsdk-path)
include $(PSPSDK)/lib/build.mak
