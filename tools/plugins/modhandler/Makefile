# PXP2 test plugin (VSH and GAME, kernel mode); 6.60 LoadCore NIDs. Build: tools/plugins/build.sh
TARGET = modhandler
OBJS = main.o
BUILD_PRX = 1
USE_KERNEL_LIBC = 1
USE_KERNEL_LIBS = 1
CFLAGS = -O2 -G0 -Wall -std=gnu99
LDFLAGS = -nostartfiles
LIBS = -lpspsystemctrl_kernel -lpsploadcore_kernel_660
PSPSDK = $(shell psp-config --pspsdk-path)
include $(PSPSDK)/lib/build_prx.mak
