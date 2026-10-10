#!/bin/sh
# Builds libskyshim.so (glibc aarch64) with zig as a cross compiler; no Linux machine needed.
#   ZIG=/path/to/zig ./build.sh   (default: zig from PATH)
set -e
cd "$(dirname "$0")"
ZIG=${ZIG:-zig}
$ZIG cc -target aarch64-linux-gnu.2.35 -shared -fPIC -O2 -Wall -o libskyshim.so skyshim.c -ldl
ls -l libskyshim.so
