# PXP2 CFW - top-level build - GPL-3.0-or-later
# Builds every module. Requires pspdev / psp-gcc on PATH.

SUBDIRS = isodrv systemctrl loader xmbplugin recovery installer infinity

# isodrv has no PRX of its own (its objects are linked into systemctrl),
# so it is compiled via systemctrl. We build systemctrl (which reaches into
# ../isodrv), then the rest.

.PHONY: all clean $(SUBDIRS)

all: systemctrl loader xmbplugin recovery installer infinity

# isodrv objects are built by systemctrl's Makefile (it lists them in OBJS).
systemctrl:
	$(MAKE) -C systemctrl

loader:
	$(MAKE) -C loader

xmbplugin:
	$(MAKE) -C xmbplugin

recovery:
	$(MAKE) -C recovery

installer:
	$(MAKE) -C installer

# The Infinity 2 compatibility module (second entry variant). Raw .bin.
infinity:
	$(MAKE) -C infinity

clean:
	-$(MAKE) -C systemctrl clean
	-$(MAKE) -C loader clean
	-$(MAKE) -C xmbplugin clean
	-$(MAKE) -C recovery clean
	-$(MAKE) -C installer clean
	-$(MAKE) -C infinity clean
	-rm -f isodrv/*.o
