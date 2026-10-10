#!/bin/sh
# Builds libskyshim.so (glibc aarch64) with zig as a cross compiler; no Linux machine needed.
#   ZIG=/path/to/zig ./build.sh   (default: zig from PATH)
set -e
cd "$(dirname "$0")"
ZIG=${ZIG:-zig}
$ZIG cc -target aarch64-linux-gnu.2.35 -shared -fPIC -O2 -Wall -o libskyshim.so skyshim.c -ldl
ls -l libskyshim.so
# x86_64 helper for Box64 (CEF without the setuid sandbox); copy both into app/src/main/assets/linux/
$ZIG cc -target x86_64-linux-gnu.2.17 -shared -fPIC -O1 -Wall -o libcefnosb_x64.so cefnosb.c -ldl
ls -l libcefnosb_x64.so
