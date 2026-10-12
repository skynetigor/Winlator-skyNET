#!/bin/sh
# Packs FEX (the x86-64 emulator) for the Linux containers from the project's own Ubuntu PPA build.
#
#   VERSION=2610 SERIES=j ./package-fex.sh
#
# FEX-Emu/FEX publishes no binaries on GitHub; its PPA (ppa:fex-emu/fex) builds .deb files for each Ubuntu series. The
# build for the OLDEST series (j = 22.04, needs glibc >= 2.34) is the one to take: a binary asks for the glibc it was
# built with, and the Arch Linux ARM runtime of the containers has a newer one. The "armv8.4" variant is for cores with
# ARMv8.4 (every Snapdragon 8 Gen 1 and later).
#
# What goes into the package (it is one folder, which the installer flattens into files/linux-emulators/<id>):
#   bin/FEX                      the emulator and loader: "FEX <x86-64 program> [arguments]" runs a program
#   bin/FEXServer                started by FEX itself, looked up beside it
#   lib/fex-emu/HostThunks/      the aarch64 side of the GPU thunks (Vulkan, OpenGL, ...)
#   share/fex-emu/GuestThunks/   their x86-64 side, laid over the root filesystem's libraries
#   share/fex-emu/ThunksDB.json  where each thunk is laid over
# Left out: FEXConfig (Qt), FEXRootFSFetcher, FEXBash, FEXGetConfig, the 32-bit thunks (32-bit programs are not run).
#
# Needs: curl, ar, zstd, tar. Prints the registry entry for linux.json.
set -e
VERSION=${VERSION:-2610}
SERIES=${SERIES:-j}
WORK=${WORK:-$(mktemp -d)}
OUT=${OUT:-$PWD}
mkdir -p "$WORK" "$OUT"
NAME="fex-$VERSION-linux-aarch64"
DEB="fex-emu-armv8.4_${VERSION}-1~${SERIES}_arm64.deb"
URL="https://ppa.launchpadcontent.net/fex-emu/fex/ubuntu/pool/main/f/fex-emu-armv8.4/$DEB"

echo "working in $WORK"
curl -fL -o "$WORK/$DEB" "$URL"
mkdir -p "$WORK/deb" "$WORK/$NAME"
(cd "$WORK/deb" && ar x "../$DEB" && zstd -dc data.tar.zst | tar -x)

D="$WORK/deb/usr"
mkdir -p "$WORK/$NAME/bin" "$WORK/$NAME/lib/fex-emu" "$WORK/$NAME/share/fex-emu" "$WORK/$NAME/share/doc"
cp "$D/bin/FEX" "$D/bin/FEXServer" "$WORK/$NAME/bin/"
cp -R "$D/lib/aarch64-linux-gnu/fex-emu/HostThunks" "$WORK/$NAME/lib/fex-emu/HostThunks"
cp -R "$D/share/fex-emu/GuestThunks" "$WORK/$NAME/share/fex-emu/GuestThunks"
cp "$D/share/fex-emu/ThunksDB.json" "$WORK/$NAME/share/fex-emu/"
cp "$D/share/doc/fex-emu-armv8.4/copyright" "$WORK/$NAME/share/doc/fex-emu-copyright"
chmod 755 "$WORK/$NAME/bin/"*

(cd "$WORK" && COPYFILE_DISABLE=1 tar -cf - "$NAME" | zstd -19 -T0 -q -o "$OUT/$NAME.tar.zst")
SHA=$(shasum -a 256 "$OUT/$NAME.tar.zst" | cut -d' ' -f1)
SIZE=$(wc -c < "$OUT/$NAME.tar.zst" | tr -d ' ')
echo
echo "$OUT/$NAME.tar.zst  $SIZE bytes"
READELF=$(command -v llvm-readelf || command -v readelf || true)
[ -n "$READELF" ] && echo "needs glibc >= $("$READELF" -V "$WORK/$NAME/bin/FEX" 2>/dev/null | grep -oE 'GLIBC_[0-9.]+' | sort -V -u | tail -1 | cut -d_ -f2)"
cat <<JSON

{
  "id": "fex-$VERSION",
  "name": "FEX",
  "emulator": "fex",
  "version": "$VERSION",
  "channel": "prerelease",
  "url": "https://github.com/skynetigor/winlator-skynet-components/releases/download/linux-fex-$VERSION/$NAME.tar.zst",
  "sha256": "$SHA",
  "size": $SIZE
}
JSON
