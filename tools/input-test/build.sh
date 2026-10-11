#!/bin/sh
# Builds the input testers for both architectures a Linux container runs: native aarch64, and x86-64 for
# programs under Box64. zig is the cross compiler, so no Linux machine is needed.
#   ZIG=/path/to/zig ./build.sh   (default: zig from PATH)
set -e
cd "$(dirname "$0")"
ZIG=${ZIG:-zig}
for name in gamepadtest mousetest; do
    $ZIG cc -target aarch64-linux-gnu.2.35 -O2 -Wall -o "$name" "$name".c -ldl
    $ZIG cc -target x86_64-linux-gnu.2.17 -O2 -Wall -o "$name"_x64 "$name".c -ldl
done
ls -l gamepadtest gamepadtest_x64 mousetest mousetest_x64
