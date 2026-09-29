# PXP2 CFW - loader (kernel PRX) - GPL-3.0-or-later

TARGET = loader
OBJS = loader.o

BUILD_PRX = 1
USE_KERNEL_LIBS = 1
USE_KERNEL_LIBC = 1

PSP_FW_VERSION = 661

INCDIR   = ../include
CFLAGS   = -O2 -G0 -Wall
ASFLAGS  = $(CFLAGS)

PSPSDK = $(shell psp-config --pspsdk-path)
include $(PSPSDK)/lib/build.mak
