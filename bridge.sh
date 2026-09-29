#!/bin/sh
# Show which kernel function a sceVshBridge NID wraps, and disassemble that kernel function if found in kd/.
J='/c/Users/Ben/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin'; F=local-test/install/flash-661-01g-v1/flash0
line=$(grep -i "$1" local-test/vshbridge-map.txt); echo "$line"
target=$(echo "$line" | grep -o '[A-Za-z_]*:[0-9a-f]\{8\}' | grep -v "f6427665\|sceVshBridge" | head -1)
[ -z "$target" ] && exit 0
lib=${target%%:*}; nid=${target##*:}
for f in $F/kd/*.prx; do
  out=$("$J/java" -cp core/build ExportMap "$f" "$lib" ${2:-40} 2>/dev/null | grep -A${2:-40} ":$nid ")
  [ -n "$out" ] && { echo "== $f"; echo "$out"; break; }
done
