#!/bin/bash
# PXP2 CFW - build driver for WSL. Run via:
#   MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/Users/Ben/Desktop/pxp2/cfw/build.sh
set -e
export PSPDEV=$HOME/pspdev
export PATH=$PATH:$PSPDEV/bin
cd "$(dirname "$0")"
echo "== psp-gcc =="; psp-gcc --version | head -1
echo "== pspsdk  =="; psp-config --pspsdk-path
make clean || true
make all
echo "== build products =="
find . \( -name '*.prx' -o -name 'EBOOT.PBP' -o -name 'pxp2_compat.bin' \) | sort
