#!/bin/bash
# PXP2 CFW - build a single subdir (or all) in WSL. Run via:
#   MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/Users/Ben/Desktop/pxp2/cfw/build-one.sh <subdir>
# With no arg, cleans isodrv objects and builds everything.
set -e
export PSPDEV=$HOME/pspdev
export PATH=$PATH:$PSPDEV/bin
cd "$(dirname "$0")"
if [ -z "$1" ]; then
	rm -f isodrv/*.o
	make all
else
	make -C "$1"
fi
