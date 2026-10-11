#!/bin/sh
# Builds the lsfg-vk Vulkan layer for a Linux container: aarch64, glibc (what the Arch Linux ARM runtime is), not the
# Android build that Wine containers use. It is GameNative's lsfg-vk-android at the tag the Wine build ships, cross
# compiled with zig, so no Linux machine is needed.
#
# Which code path matters. The stock "desktop Linux" path runs the frame generation on a second VkDevice and shares
# frames and semaphores with the game's device as OPAQUE_FD handles. Turnip on KGSL cannot export an OPAQUE_FD
# semaphore (vkCreateSemaphore fails with VK_ERROR_INVALID_EXTERNAL_HANDLE and the layer rebuilds its swapchain
# context over and over). The fork's Android path was written for exactly that: it runs the generation on the game's
# own device and queue and syncs with a wait-idle. Its code is under #ifdef __ANDROID__, so the sources are patched to
# a macro of our own (LSFGVK_SINGLE_DEVICE) and the two Android headers they include are stood in for by shim/.
#
#   ZIG=zig CMAKE=cmake STRIP=llvm-strip ./build.sh
#
# Needs git, perl, cmake (4.x: pip install cmake in a venv is enough), ninja, zig, and an llvm-strip (the NDK has one).
# The result goes to app/src/main/assets/lsfg_vk/linux-arm64/liblsfg-vk.so, which LsfgManager.prepareLinuxLaunch installs.
# Bump LINUX_RUNTIME_VERSION in LsfgManager when it changes.
#
# The binary has only been checked as an ELF (aarch64, needs libc.so.6 only, exports layer_vkGetInstanceProcAddr and
# layer_vkGetDeviceProcAddr, GLIBC <= 2.34). It was not run on a device when it was built.
set -e
cd "$(dirname "$0")"
ZIG=${ZIG:-zig}
CMAKE=${CMAKE:-cmake}
STRIP=${STRIP:-llvm-strip}
TAG=${TAG:-v1.0.4-android}
WORK=${WORK:-$(mktemp -d)}
OUT=$(cd ../.. && pwd)/app/src/main/assets/lsfg_vk/linux-arm64
echo "working in $WORK"

git clone --depth 1 --branch "$TAG" --recurse-submodules --shallow-submodules \
    https://github.com/GameNative/lsfg-vk-android "$WORK/src"
# volk wants the Vulkan headers; the version the first build used came with NDK r29 (1.3.275).
git clone --depth 1 --branch v1.3.275 https://github.com/KhronosGroup/Vulkan-Headers "$WORK/vkheaders"

# The single-device path is the code under __ANDROID__: give it a macro of its own.
grep -rl "__ANDROID__" "$WORK/src/src" "$WORK/src/include" "$WORK/src/framegen" | xargs perl -pi -e 's/__ANDROID__/LSFGVK_SINGLE_DEVICE/g'

mkdir -p "$WORK/tc"
for pair in "zigcc:cc" "zigcxx:c++"; do
    name=${pair%%:*}; mode=${pair##*:}
    printf '#!/bin/sh\nexec %s %s -target aarch64-linux-gnu.2.35 "$@"\n' "$ZIG" "$mode" > "$WORK/tc/$name"
    chmod +x "$WORK/tc/$name"
done
printf '#!/bin/sh\nexec %s ar "$@"\n' "$ZIG" > "$WORK/tc/zigar"; chmod +x "$WORK/tc/zigar"
printf '#!/bin/sh\nexec %s ranlib "$@"\n' "$ZIG" > "$WORK/tc/zigranlib"; chmod +x "$WORK/tc/zigranlib"
cat > "$WORK/tc/toolchain.cmake" <<TOOLCHAIN
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_C_COMPILER $WORK/tc/zigcc)
set(CMAKE_CXX_COMPILER $WORK/tc/zigcxx)
set(CMAKE_AR $WORK/tc/zigar)
set(CMAKE_RANLIB $WORK/tc/zigranlib)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
TOOLCHAIN

# -Wno-date-time: the framegen sources stamp __DATE__ / __TIME__, which zig's clang turns into an error.
# VK_USE_PLATFORM_ANDROID_KHR: the Android-only Vulkan entry points the sources name must exist for volk (never called here).
FLAGS="-I$WORK/vkheaders/include -I$(pwd)/shim -Wno-date-time -DLSFGVK_SINGLE_DEVICE=1 -DVK_USE_PLATFORM_ANDROID_KHR=1"
"$CMAKE" -S "$WORK/src" -B "$WORK/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$WORK/tc/toolchain.cmake" -DVOLK_PULL_IN_VULKAN=OFF \
    -DCMAKE_C_FLAGS="$FLAGS" -DCMAKE_CXX_FLAGS="$FLAGS" -DCMAKE_POLICY_VERSION_MINIMUM=3.5
"$CMAKE" --build "$WORK/build" -j8

mkdir -p "$OUT"
cp "$WORK/build/liblsfg-vk.so" "$OUT/liblsfg-vk.so"
"$STRIP" --strip-unneeded "$OUT/liblsfg-vk.so"
ls -l "$OUT/liblsfg-vk.so"
shasum -a 256 "$OUT/liblsfg-vk.so"
git -C "$WORK/src" rev-parse HEAD
