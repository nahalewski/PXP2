# PXP2 CFW - Installer ("update") homebrew EBOOT.PBP - GPL-3.0-or-later

TARGET = installer
OBJS = main.o

INCDIR   = ../include
CFLAGS   = -O2 -G0 -Wall
ASFLAGS  = $(CFLAGS)
LIBDIR   =
LDFLAGS  =
LIBS     = -lpspdebug -lpspge -lpspdisplay -lpspctrl

EXTRA_TARGETS   = EBOOT.PBP
PSP_EBOOT_TITLE = PXP2 CFW Installer

PSPSDK = $(shell psp-config --pspsdk-path)
include $(PSPSDK)/lib/build.mak
