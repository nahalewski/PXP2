#!/bin/sh
# Rebuild the core and run the VSH boot diagnostic against the local firmware install.
J='/c/Users/Ben/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin'
cd "$(dirname "$0")" && rm -rf core/build && mkdir -p core/build local-test/storage &&
"$J/javac" -Xlint:-options -d core/build core/src/org/pxp2/core/*.java core/tests/*.java &&
"$J/java" -cp core/build VshBootTest local-test/install/flash-661-01g-v1 local-test/storage ${1:-50000000} ${WATCH:-}
