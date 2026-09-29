# PXP2 test plugin (user mode, misbehaves on purpose). Build: tools/plugins/build.sh
TARGET = faulttest
OBJS = main.o sysreg.o
BUILD_PRX = 1
CFLAGS = -O2 -G0 -Wall -std=gnu99
LDFLAGS = -nostartfiles
PSPSDK = $(shell psp-config --pspsdk-path)
include $(PSPSDK)/lib/build_prx.mak
