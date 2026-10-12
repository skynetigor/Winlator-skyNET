#!/bin/sh
# Builds libskyshim.so (glibc aarch64) with zig as a cross compiler; no Linux machine needed.
#   ZIG=/path/to/zig ./build.sh   (default: zig from PATH)
set -e
cd "$(dirname "$0")"
ZIG=${ZIG:-zig}
$ZIG cc -target aarch64-linux-gnu.2.35 -shared -fPIC -O2 -Wall -o libskyshim.so skyshim.c -ldl
ls -l libskyshim.so
# x86_64 helpers for Box64; copy all three into app/src/main/assets/linux/
$ZIG cc -target x86_64-linux-gnu.2.17 -shared -fPIC -O1 -Wall -o libcefnosb_x64.so cefnosb.c -ldl
ls -l libcefnosb_x64.so
# A program running under Box64 is an x86-64 process: the aarch64 library above is never loaded into it, so the
# gamepad (and the uevent socket) need the same shim built for that architecture.
$ZIG cc -target x86_64-linux-gnu.2.17 -shared -fPIC -O2 -Wall -o libskyshim_x64.so skyshim.c -ldl
ls -l libskyshim_x64.so
# A game that ships mimalloc (BeamNG 0.39): its mi_* functions on plain malloc, installed as libmimalloc.so.3 (the soname).
$ZIG cc -target x86_64-linux-gnu.2.17 -shared -fPIC -O2 -Wall -o libmimalloc_compat_x64.so mimalloc_compat.c -lpthread
ls -l libmimalloc_compat_x64.so
