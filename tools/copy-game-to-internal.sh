#!/bin/bash
# Copies a game folder from the phone's shared storage (FUSE, slow for Wine) into the app's internal storage
# (f2fs, fast), and shows progress while it runs. Default place: Z:\home\games\<name> in every container
# (survives an ImageFS update, needs no container). With -c it goes to that container's C:\Games\<name> instead.
#
# The copy runs on the phone (survives a dropped Wi-Fi ADB link); this script only polls it.
# Needs the debug build (run-as), and enough free space in /data.
#
# Usage:
#   tools/copy-game-to-internal.sh [-s GAME_DIR] [-e EXE] [-c CONTAINER_ID] [-n NAME] [-p PACKAGE] [--clean]
#   (no arguments: BeamNG 0.34 Steam depot, /sdcard/Download/Games/BeemNG_0_34_Steam_Depot)
#
#   -s  game folder on the phone
#   -e  the exe inside it, relative (default: Bin64/BeamNG.drive.x64.exe)
#   -c  container id: copy into that container's C:\Games instead of Z:\home\games
#   -n  folder name (default: the source folder's name)
#   -p  app package (default: com.winlator.cmod.dev2)
#   --clean  delete the destination first (a clean copy)
#
# Afterwards point the shortcut at the new path (shown at the end) and compare load times.

set -u

ADB="${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
[ -x "$ADB" ] || ADB=adb
PKG=com.winlator.cmod.dev2
# Defaults for BeamNG 0.34 (Steam depot) on this phone; override with -s / -e.
SRC="/sdcard/Download/Games/BeemNG_0_34_Steam_Depot"
EXE_REL="Bin64/BeamNG.drive.x64.exe"
CID=""
NAME=""
CLEAN=0

while [ $# -gt 0 ]; do
  case "$1" in
    -s) SRC="$2"; shift 2 ;;
    -e) EXE_REL="$2"; shift 2 ;;
    -c) CID="$2"; shift 2 ;;
    -n) NAME="$2"; shift 2 ;;
    -p) PKG="$2"; shift 2 ;;
    --clean) CLEAN=1; shift ;;
    -h|--help) sed -n 2,20p "$0"; exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

SRC="${SRC%/}"
[ -n "$NAME" ] || NAME="$(basename "$SRC")"

# Runs a command on the phone as the app (so it can write to the app's private storage).
app() { "$ADB" shell "run-as $PKG sh -c '$1'" 2>&1; }

human() { # KB -> text
  awk -v k="$1" 'BEGIN { if (k >= 1048576) printf "%.1f GB", k/1048576; else printf "%.0f MB", k/1024 }'
}

echo "== Checking the phone"
"$ADB" get-state >/dev/null 2>&1 || { echo "No device over ADB."; exit 1; }
out="$(app 'echo ok')"
[ "$out" = "ok" ] || { echo "run-as $PKG failed ($out). Is the debug build installed?"; exit 1; }
# The app must be allowed to read shared storage.
"$ADB" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1

"$ADB" shell "[ -d '$SRC' ]" || { echo "Not found on the phone: $SRC"; exit 1; }

if [ -n "$CID" ]; then
  app "[ -d files/imagefs/home/xuser-$CID ]" >/dev/null
  "$ADB" shell "run-as $PKG [ -d files/imagefs/home/xuser-$CID ]" || { echo "No container $CID."; exit 1; }
  DEST="files/imagefs/home/xuser-$CID/.wine/drive_c/Games/$NAME"
  WINPATH="C:\\Games\\$NAME (container $CID)"
else
  DEST="files/imagefs/home/games/$NAME"
  WINPATH="Z:\\home\\games\\$NAME (any container)"
fi

echo "== Measuring $SRC"
SRC_KB="$("$ADB" shell "du -sk '$SRC'" | awk '{print $1}')"
SRC_FILES="$("$ADB" shell "find '$SRC' -type f | wc -l" | tr -d '\r ')"
FREE_KB="$("$ADB" shell "df -k /data" | tail -1 | awk '{print $4}')"
echo "   Size:  $(human "$SRC_KB"), $SRC_FILES files"
echo "   Free:  $(human "$FREE_KB") in /data"
if [ "$FREE_KB" -lt $((SRC_KB + SRC_KB / 20)) ]; then
  echo "Not enough free space for the copy."; exit 1
fi

echo "== Copying to $WINPATH"
[ "$CLEAN" = 1 ] && app "rm -rf '$DEST'" >/dev/null
app "mkdir -p '$DEST'; rm -f files/copy.done files/copy.log" >/dev/null
# In the background on the phone; the exit code of cp goes to files/copy.done.
app "setsid sh -c \"cp -a '$SRC'/. '$DEST'/ > files/copy.log 2>&1; echo \\\$? > files/copy.done\" > /dev/null 2>&1 &" >/dev/null

START=$(date +%s)
trap 'echo; echo "Stopped watching. The copy keeps running on the phone (files/copy.done appears when it ends)."; exit 130' INT

while true; do
  NOW_KB="$(app "du -sk '$DEST'" | awk '{print $1}')"
  case "$NOW_KB" in ''|*[!0-9]*) NOW_KB=0 ;; esac
  ELAPSED=$(( $(date +%s) - START )); [ "$ELAPSED" -lt 1 ] && ELAPSED=1
  PCT=$(( NOW_KB * 100 / (SRC_KB > 0 ? SRC_KB : 1) )); [ "$PCT" -gt 100 ] && PCT=100
  RATE_KB=$(( NOW_KB / ELAPSED ))
  if [ "$RATE_KB" -gt 0 ]; then
    ETA=$(( (SRC_KB - NOW_KB) / RATE_KB )); [ "$ETA" -lt 0 ] && ETA=0
    ETA_TXT="$(printf '%02d:%02d' $((ETA / 60)) $((ETA % 60)))"
  else
    ETA_TXT="--:--"
  fi
  FILLED=$(( PCT * 30 / 100 ))
  BAR="$(printf '%*s' "$FILLED" '' | tr ' ' '#')$(printf '%*s' $((30 - FILLED)) '' | tr ' ' '.')"
  printf '\r[%s] %3d%%  %s / %s  %s/s  left %s   ' "$BAR" "$PCT" "$(human "$NOW_KB")" "$(human "$SRC_KB")" "$(human "$RATE_KB")" "$ETA_TXT"
  if [ "$(app 'cat files/copy.done 2>/dev/null')" != "" ]; then break; fi
  sleep 2
done
echo

CODE="$(app 'cat files/copy.done')"
if [ "$CODE" != "0" ]; then
  echo "cp ended with code $CODE. Log:"; app 'tail -20 files/copy.log'; exit 1
fi

echo "== Checking"
DEST_FILES="$(app "find '$DEST' -type f | wc -l" | tr -d '\r ')"
DEST_KB="$(app "du -sk '$DEST'" | awk '{print $1}')"
echo "   Files: $SRC_FILES on the phone's storage, $DEST_FILES copied"
echo "   Size:  $(human "$SRC_KB") -> $(human "$DEST_KB")"
[ "$SRC_FILES" = "$DEST_FILES" ] && echo "   OK" || echo "   File counts differ: check files/copy.log"
echo
echo "Done in $(( $(date +%s) - START )) s. The game is now at $WINPATH"
if "$ADB" shell "run-as $PKG [ -f '$DEST/$EXE_REL' ]"; then
  WINDIR="${WINPATH%% *}"
  echo
  echo "Exe for the shortcut:   $WINDIR\\${EXE_REL//\//\\}"
  echo "Start folder:           $WINDIR\\$(dirname "$EXE_REL" | sed 's#/#\\#g')"
  echo "Launch option (outside Steam): -nosteam"
else
  echo "Warning: $EXE_REL was not found in the copy."
fi
