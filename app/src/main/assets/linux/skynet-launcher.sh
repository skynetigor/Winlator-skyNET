#!/bin/bash
# Stands in for Valve's fex-compat-tool in a Winlator skyNET Linux container that runs x86-64 programs with Box64.
#
# The Steam client for arm64 starts an x86 game through that tool: FEX in a Steam Linux Runtime container (pressure-vessel),
# which needs user namespaces that proot does not give. Steam calls it as
#   fex-compat-tool run -- <runtime>/_v2-entry-point --verb=waitforexitandrun -- <the game's command>
# This script runs the game's command itself. A native program runs as it is; an x86-64 one is handed to Box64 by
# libskyshim's exec functions (SKY_X86_EMULATOR), so it needs nothing here. A 32-bit x86 program cannot run this way.
mkdir -p /storage/emulated/0/Winlator/logs 2>/dev/null
log=/storage/emulated/0/Winlator/logs/steam-game-launch.log
args=("$@")
# drop "run" / "waitforexitandrun" and the "--" after it
case "${args[0]}" in run|waitforexitandrun) args=("${args[@]:1}");; esac
[ "${args[0]}" = "--" ] && args=("${args[@]:1}")
# what follows the runtime's entry point and its own "--" is the game's command
cmd=("${args[@]}")
for i in "${!args[@]}"; do
    if [ "${args[$i]}" = "--" ]; then cmd=("${args[@]:$((i + 1))}"); break; fi
done
{
    echo "== $(date) skynet-launcher"
    echo "args: $*"
    echo "run: ${cmd[*]}"
} >> "$log" 2>&1
# the Steam runtime's overlay libraries are not there; Steam's own preload would only produce warnings
unset LD_PRELOAD
exec "${cmd[@]}" >> "$log" 2>&1
