#!/bin/bash
# Correctness test of libfscache.so (the stat/readdir cache for Wine) on the phone, without Wine: creates, renames, deletes
# files and directories under a cached root, also from a second process, and checks that every answer is current.
# Usage: tools/fscache-test/run.sh /path/to/libfscache.so   (the library from the built APK, lib/arm64-v8a/)
set -e
ADB="${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
NDK="${NDK:-/opt/homebrew/share/android-commandlinetools/ndk/29.0.14206865}"
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang"
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
"$CC" -O1 -o "$TMP/fscache_test" "$HERE/fscache_test.c"
"$ADB" push "$TMP/fscache_test" /data/local/tmp/fscache_test >/dev/null
"$ADB" push "$1" /data/local/tmp/libfscache.so >/dev/null
"$ADB" shell "chmod 755 /data/local/tmp/fscache_test; rm -rf /data/local/tmp/fst /data/local/tmp/fst.shm; mkdir -p /data/local/tmp/fst; \
  dd if=/dev/zero of=/data/local/tmp/fst.shm bs=1k count=80 2>/dev/null; cd /data/local/tmp && \
  LD_PRELOAD=/data/local/tmp/libfscache.so SKY_FSCACHE=1 SKY_FSCACHE_ROOTS=/data/local/tmp/fst SKY_FSCACHE_SHM=/data/local/tmp/fst.shm ./fscache_test; \
  rc=\$?; rm -rf /data/local/tmp/fst /data/local/tmp/fst.shm /data/local/tmp/fscache_test /data/local/tmp/libfscache.so; exit \$rc"
