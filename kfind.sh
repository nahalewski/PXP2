#!/bin/sh
# Disassemble an exported firmware function: kfind.sh <library> <nid> [count]
J='/c/Users/Ben/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin'; F=local-test/install/flash-661-01g-v1/flash0
for f in $F/kd/*.prx $F/vsh/module/*.prx; do
  out=$("$J/java" -cp core/build ExportMap "$f" "$1" ${3:-30} 2>/dev/null | grep -A${3:-30} ":$2 ")
  [ -n "$out" ] && { echo "== $f"; echo "$out"; break; }
done
