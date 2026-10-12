# Chromium (CEF) in a Linux container: the X server must answer ListInputDevices like a real one

Found on 2026-10-12 while a Steam client showed a grey rectangle and then a black screen, and found again in BeamNG 0.39
(whose CEF dies the same way). Two different programs, two architectures (aarch64 native, x86-64 under Box64), one cause.

## Symptom

* Steam: the client runs, its `steamwebhelper` (Chromium 126) is started again every 10 s and each start ends within a
  second with exit code 133 (SIGTRAP, an aarch64 `brk`); the UI never draws.
* BeamNG 0.39: SIGILL (`ud2`) in `libcef.so` a second after the game's Lua engine is up.
* No message anywhere: official Chromium builds print nothing for a `CHECK`, and the web helper's log shows only unrelated
  errors (`bus.cc: Failed to connect to the bus`, `file_path_watcher_inotify.cc: Failed to read .../max_user_watches`).

## How it was found

1. `steamwebhelper` exits with 133: a tiny wrapper script of exactly the same size as Valve's `steamwebhelper.sh`
   (the client verifies sizes and re-installs the whole package when one differs) logged the exit status.
2. A 60-line `LD_PRELOAD` library (aarch64, `sigaction` replaced so that Chromium's own crash handler does not take over)
   printed the faulting address and the frame-pointer chain with the pointer-authentication bits masked off:
   `libcef.so+0x2dcee08`, called from `ozone_platform_x11`/`DeviceDataManager` code (the strings around the callers:
   `TOUCHPAD`, `disable-high-precision-scrolling`, `hand2`, `left_ptr`).
3. `llvm-objdump` (the NDK has it) on that function: it reads a reply, and ends with
   `CHECK_EQ(offset < 32 ? 0 : offset - 32, 4 * length)` where the offset has been moved one byte past the end of the data
   and aligned up. It is the generated reader of the XI 1.x `ListInputDevices` reply (`devices_len` is a byte, then a
   24-byte skip, a list of 8-byte devices, the class infos, the names). The BeamNG function is the same code.
4. The server's own trace (`.xtrace`, `XTrace` in logcat, now with the first 48 bytes of each reply) showed our reply:
   `01 02 <seq> 00000000 00 ...` that is 32 bytes, `length 0`, no devices. For zero devices Chromium needs `length 1`.

## The fix and its catch

`XInput2Extension.listInputDevices`. Writing `length 1` (a 36-byte reply) satisfied Chromium and broke the Steam client's
own windows: libXi's `XListInputDevices` returns without reading the data when there are no devices, and Xlib aborts with
`_XReply: Assertion '!xcb_xlib_extra_reply_data_left' failed`. Two readers disagree about an empty list, so the list is not
empty: the reply names the two master devices (Virtual core pointer and keyboard, no classes) and its length covers the data
plus one byte, rounded up to a word. Real X servers always have devices, which is why neither reader is ever asked about an
empty list.

## Smaller things fixed on the way

* The Steam installer needs `awk` and the runtime ships `gawk` without the execute bit (`chmod` before the installer).
* Steam exits with 42 after updating itself and wants a restart (the script restarts it, at most four times).
* `X_AllocColor` and `X_AllocNamedColor` were not answered (`ColorRequests`).
* Chromium logs an error when `/proc/sys/fs/inotify/max_*` are unreadable; they are now bound from small files
  (`LinuxSession`, with the other fake `/proc` files). Not the cause of the crash.
* Steam shortcut: `Winlator/logs/linux-steam.log` (stdout of the client) and a copy of the client's own log folder in
  `Winlator/logs/steam-logs/` every 10 s.

## Still open for Steam

The client draws its sign-in window now. Not looked at yet: signing in, the library, running a game; `failed to create
drawable` (the main window's GL context, the container runs software GL) and `The XTest extension is not available` appear
in the log; the web helper still runs with `-cef-disable-gpu` (software), the GPU mode through ANGLE/Vulkan has not been
tried again since the crash was fixed.
