# PXP2 CFW - vshctrl (user-mode VSH plugin) - GPL-3.0-or-later

TARGET = vshctrl
OBJS = vshctrl.o iso9660.o sfo.o vshbridge.o

BUILD_PRX = 1
PRX_EXPORTS = exports.exp

PSP_FW_VERSION = 661

INCDIR   = ../include
CFLAGS   = -O2 -G0 -Wall
ASFLAGS  = $(CFLAGS)

PSPSDK = $(shell psp-config --pspsdk-path)
include $(PSPSDK)/lib/build.mak
