# PXP2 CFW - Infinity 2 compatibility module - GPL-3.0-or-later.
# Produces pxp2_compat.bin: a raw binary Infinity loads at boot and enters at
# compat_entry (see main.c / infinity_compat.h / ../INFINITY.md).
#
# This is a freestanding image: no PSPSDK crt0, no libc, no PRX wrapper. It is
# the second build variant alongside the HEN loader.prx.

TARGET  = pxp2_compat
OBJS    = crt0.o main.o

PSPSDK  = $(shell psp-config --pspsdk-path)
PSPDEV  = $(shell psp-config --psp-prefix)

CC      = psp-gcc
OBJCOPY = psp-objcopy

INCDIR  = -I. -I../include
CFLAGS  = -Os -G0 -Wall -fno-builtin -fno-pic -mno-abicalls -mno-long-calls $(INCDIR)
ASFLAGS = $(CFLAGS)
LDFLAGS = -nostartfiles -nostdlib -T linkfile.l

all: $(TARGET).bin

$(TARGET).elf: $(OBJS) linkfile.l
	$(CC) $(CFLAGS) $(LDFLAGS) $(OBJS) -o $@

$(TARGET).bin: $(TARGET).elf
	$(OBJCOPY) -O binary $< $@

crt0.o: crt0.S
	$(CC) $(ASFLAGS) -c $< -o $@

main.o: main.c infinity_compat.h
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	-rm -f $(OBJS) $(TARGET).elf $(TARGET).bin

.PHONY: all clean
