#!/bin/sh
# Packs one of FEX's official x86-64 root filesystems for the Linux containers.
#
#   DISTRO="Ubuntu 24.04 (SquashFS)" ./package-rootfs.sh
#
# FEX runs a program in an x86-64 userland (a libc, a dynamic loader, the libraries the program links against) and
# looks the program's files up there first. FEX publishes ready images at https://rootfs.fex-emu.gg (a SquashFS or an
# EroFS file each, listed in RootFS_links.json). The phone cannot mount either (no FUSE under proot), so the image is
# unpacked here and shipped as a tar.zst, which the app extracts like any other Linux package.
#
# On macOS the unpacking is done on a case-sensitive disk image: a Linux file system can hold two files whose names
# differ only in case, and the default (case-insensitive) one would silently merge them.
#
# Needs: curl, python3, unsquashfs (brew install squashfs), zstd, tar, and hdiutil on macOS. About 12 GB of scratch
# space. Prints the registry entry for linux.json.
set -e
DISTRO=${DISTRO:-"Ubuntu 24.04 (SquashFS)"}
WORK=${WORK:-$(mktemp -d)}
OUT=${OUT:-$PWD}
mkdir -p "$WORK" "$OUT"
echo "working in $WORK"

curl -fsSL -o "$WORK/links.json" https://rootfs.fex-emu.gg/RootFS_links.json
IMAGE_URL=$(python3 -I - "$WORK/links.json" "$DISTRO" <<'PY'
import json, sys
entry = json.load(open(sys.argv[1]))["v1"][sys.argv[2]]
if entry["Type"] != "squashfs":
    sys.exit("only the SquashFS images are handled (unsquashfs); pick the '(SquashFS)' entry")
print(entry["URL"])
PY
)
NAME=$(basename "$IMAGE_URL" .sqsh)            # Ubuntu_24_04
DATE=$(echo "$IMAGE_URL" | sed 's#.*/\([0-9-]*\)/[^/]*$#\1#')
ID=$(echo "rootfs-$NAME-$DATE" | tr 'A-Z_' 'a-z-')
echo "image $IMAGE_URL -> $ID"

[ -f "$WORK/$NAME.sqsh" ] || curl -fL -o "$WORK/$NAME.sqsh" "$IMAGE_URL"

TREE="$WORK/tree"
if [ "$(uname)" = "Darwin" ]; then
    SPARSE="$WORK/rootfs.sparseimage"
    [ -f "$SPARSE" ] || hdiutil create -size 14g -fs "Case-sensitive APFS" -volname fexrootfs -type SPARSE "$SPARSE" >/dev/null
    MOUNT="$WORK/mount"
    mkdir -p "$MOUNT"
    hdiutil attach "$SPARSE" -mountpoint "$MOUNT" -nobrowse >/dev/null
    trap 'hdiutil detach "$MOUNT" >/dev/null 2>&1 || true' EXIT
    TREE="$MOUNT/tree"
fi
rm -rf "$TREE"
# Device nodes cannot be created without root; they are of no use in the archive anyway (the session has /dev).
unsquashfs -no-xattrs -n -f -d "$TREE" "$WORK/$NAME.sqsh" || echo "(unsquashfs reported errors: device nodes it cannot create are expected)"

# Libraries a game links against that FEX's images do not carry (the runtime's own copy is what Box64 uses instead).
# BeamNG's binary needs libatomic.so.1: found by comparing the needed libraries of its files with this tree.
EXTRA_DEBS="http://archive.ubuntu.com/ubuntu/pool/main/g/gcc-14/libatomic1_14.2.0-4ubuntu2~24.04.1_amd64.deb"
for deb in $EXTRA_DEBS; do
    curl -fsSL -o "$WORK/extra.deb" "$deb"
    rm -rf "$WORK/extra"; mkdir -p "$WORK/extra"
    (cd "$WORK/extra" && ar x ../extra.deb && zstd -dc data.tar.zst | tar -x)
    cp -R "$WORK/extra/usr/." "$TREE/usr/"
done

for dir in tmp proc sys dev run; do mkdir -p "$TREE/$dir"; done
# The guest's loader reads /etc/ld.so.preload, and when the image has none FEX gives it the phone's runtime one (an
# aarch64 library list) which it can not load, with an error line for every program started. An empty file keeps it quiet.
: > "$TREE/etc/ld.so.preload"
echo
echo "contents:"
du -sh "$TREE" | cut -f1
for lib in libX11.so.6 libXext.so.6 libXss.so.1 libGL.so.1 libvulkan.so.1 libSDL2-2.0.so.0 libasound.so.2 libpulse.so.0 libstdc++.so.6; do
    if find "$TREE/usr/lib/x86_64-linux-gnu" "$TREE/usr/lib64" "$TREE/lib64" "$TREE/usr/lib" -maxdepth 1 -name "$lib" 2>/dev/null | grep -q .; then
        printf '  %-22s yes\n' "$lib"
    else
        printf '  %-22s NO (x86-64)\n' "$lib"
    fi
done

ARCHIVE="$OUT/$ID.tar.zst"
(cd "$TREE" && COPYFILE_DISABLE=1 tar --uid 0 --gid 0 --numeric-owner -cf - .) | zstd -19 -T0 -q -o "$ARCHIVE"
SHA=$(shasum -a 256 "$ARCHIVE" | cut -d' ' -f1)
SIZE=$(wc -c < "$ARCHIVE" | tr -d ' ')
echo
echo "$ARCHIVE  $SIZE bytes"
cat <<JSON

{
  "id": "$ID",
  "name": "$(echo "${DISTRO%% (*}") x86-64 root filesystem",
  "version": "$DATE",
  "channel": "prerelease",
  "source": "$IMAGE_URL",
  "url": "https://github.com/skynetigor/winlator-skynet-components/releases/download/linux-$ID/$ID.tar.zst",
  "sha256": "$SHA",
  "size": $SIZE
}
JSON
