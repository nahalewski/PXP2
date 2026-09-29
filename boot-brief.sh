#!/bin/sh
# Rebuild and show the stop reason plus the last HLE calls of the VSH boot diagnostic.
sh "$(dirname "$0")/run-boot.sh" ${1:-50000000} > /tmp/pxp2-boot.txt 2>&1
grep -m1 -A2 "^Stop" /tmp/pxp2-boot.txt; grep "^Guest instr\|^GE:\|^Host" /tmp/pxp2-boot.txt
sed -n '/^Threads/,/^HLE calls/p' /tmp/pxp2-boot.txt | head -20
sed -n '/Last HLE calls/,/Last instr/p' /tmp/pxp2-boot.txt | tail -${2:-12}
