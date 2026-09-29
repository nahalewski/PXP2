#!/bin/bash
# Builds the PXP2 test plugins with the pspdev toolchain (run inside WSL):
#   MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/Users/Ben/Desktop/pxp2/tools/plugins/build.sh
# Each plugin's PRX is copied next to its sources (prebuilt/).
set -e
export PSPDEV=${PSPDEV:-$HOME/pspdev}
export PATH="$PSPDEV/bin:$PATH"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$here/prebuilt"
for p in vshtest gametest modhandler faulttest; do
  echo "== $p"
  make -C "$here/$p" clean >/dev/null
  make -C "$here/$p"
  cp "$here/$p/$p.prx" "$here/prebuilt/$p.prx"
  make -C "$here/$p" clean >/dev/null
done
ls -l "$here/prebuilt"
