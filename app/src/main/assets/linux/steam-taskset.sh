#!/bin/bash
# Stands in for taskset in the Steam client's session (the app puts it first in PATH, see LinuxSession.steamCommand).
#
# The client starts its web helper (Chromium) through steamwebhelper.sh as "taskset <mask> steamwebhelper <flags>", with
# --disable-gpu and --disable-gpu-compositing on the list: the whole interface is then drawn on the CPU. Here the helper is
# started without those two and with ANGLE on Vulkan, so its GPU process draws on the phone's GPU through Turnip. OpenGL is
# no way for it: the X server has no GLX the way ANGLE needs it (a pbuffer, GLX_EXT_create_context_es_profile).
# The GPU process dies a few times as it starts and Chromium restarts it; the third start holds.
# SKY_STEAM_GPU=0 (the container's "linuxSteamGpu" extra) leaves the helper as the client asked for it.
if [ "$SKY_STEAM_GPU" != 0 ] && [[ "$2" == */steamwebhelper ]]; then
  args=()
  for a in "${@:3}"; do
    case "$a" in
      --disable-gpu|--disable-gpu-compositing) ;;
      *) args+=("$a") ;;
    esac
  done
  exec /usr/bin/taskset "$1" "$2" "${args[@]}" \
    --use-gl=angle --use-angle=vulkan --enable-features=Vulkan,VulkanFromANGLE,DefaultANGLEVulkan \
    --ignore-gpu-blocklist --disable-gpu-memory-buffer-compositor-resources --disable-zero-copy
fi
exec /usr/bin/taskset "$@"
