# Input in Linux containers: what is known

Working notes from tracking down why BeamNG.drive takes neither mouse clicks nor a gamepad in its menu.
Everything below was measured on the device (Galaxy Z Fold8 Ultra, container 6, runtime `bannerlator-r9`).

## Settled

**The gamepad pipeline works.** The app writes evdev events into `imagefs/dev/input/`, proot binds that
folder over `/dev/input`, and `libskyshim` answers the evdev ioctls on the files there. SDL lists an
`Xbox 360 Controller` with its built-in mapping and receives buttons, axes and the hat, natively and under
Box64. The bundled testers (`tools/input-test`, a shortcut in every Linux container) show this.

What had been missing was not code but a bind: SDL finds a pad by *reading the directory*, and Android's own
`/dev/input` is unreadable by an app, so nothing was ever listed. The shim alone only answers a path a
program already knows.

**BeamNG receives everything and acts on none of it.** Measured:

| Layer | How it was checked | Result |
|---|---|---|
| evdev file | size grows when a pad button is pressed | written |
| the game reading it | `pos` of its fd catches up with the size | read in full |
| the game seeing a pad | its own log | `Controller connected: X360 Controller`, 56 bindings |
| mouse | X protocol trace | ButtonPress and ButtonRelease sent to its window |
| focus | X protocol trace | FocusIn sent |

**Steam Input is not the cause.** With `-nosteam` the Steam errors leave the game's log, the pad is still
detected, and clicks and the D-pad still do nothing.

**BeamNG is an SDL2 program.** Its binary carries 992 `SDL_*` symbols (`SDL_Init`, `SDL_PollEvent`,
`SDL_GameControllerOpen`, `SDL_GetKeyboardState`) and ships no SDL library: SDL2 is compiled into it.

## Why mouse and keyboard did nothing (solved)

The server's GLX extension reported `first_event = 0`. A program that loads libGL (SDL does, for its OpenGL
support) makes Xlib hand GLX the 17 event codes from that number, and GLX's wire-to-event converter drops what
it does not know. So the core events 0..16 (KeyPress, ButtonPress, MotionNotify, EnterNotify, FocusIn,
KeymapNotify) never reached the program, while ConfigureNotify, PropertyNotify and the XInput2 generic events
(codes above 17) did. GLX now reports 111, the one run of 17 free codes.

How it was found, for next time:

- A bare Xlib client (`tools/input-test/xtest.c`) receives every event, natively and under Box64, with the
  XInput2 selections SDL makes. So neither the server's delivery nor Box64's libX11 wrapper was at fault.
- The Debian SDL 2.32.4 built for aarch64 worked, the same version built for x86-64 under Box64 did not. SDL
  built by hand (zig, `DEBUG_XEVENTS` on) worked until OpenGL support was compiled in: libGL's GLX is what
  broke it. BeamNG's SDL2 is static and has the same OpenGL support.
- The tester's "X events seen by SDL" counter (SYSWM events) says nothing about what SDL dispatches: native SDL
  showed no core events there and still got every click. The earlier conclusion drawn from it was wrong.

With the fix: SDL has keyboard and mouse focus, clicks arrive in the testers and in BeamNG, and the touch
simulation (`simTouchScreen`) clicks too. The pointer is not drawn in either direct-touch mode.

Tools: `tools/input-test/build.sh` builds the testers (copied into `app/src/debug/assets/linux`, so only debug
builds carry them and only debug builds add their shortcuts) and, given `SYSROOT`, the `xtest` probe. A debug
build also logs per-window event delivery when the container has a `.xtrace` file in its home.

## Why the pad was silent in BeamNG (confirmed on device)

SDL2 drops joystick events while no window has keyboard focus (`SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS`
defaults to off). BeamNG's window never gets `SDL_WINDOW_INPUT_FOCUS`, so a pad that SDL lists and reads is
filtered at the last step. `LinuxSession` now sets `SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS=1`. Confirmed: the pad then
works in BeamNG's menu and in a map. Focus and mouse clicks remain a separate, open problem.

Also found: the XI2 device event wrote `XIGroupState` as 16 bytes instead of 4 (framing stayed consistent, but
the button mask and valuators were read from the wrong offsets). Fixed.

## Fixed along the way

- `ButtonRelease` was looked up and delivered by the *pointer-motion* mask. A program that reads motion some
  other way selects no motion at all, so its press arrived and its release was dropped, and a menu acts on
  the release. Wine never noticed because it selects motion.
- `SDL_Init` hung for ever on an unanswered Xkb `GetNames`.
- Nothing handed a new window the focus, because a Linux session has no window manager. The server does it
  now, on map, and repeats it over the next seconds: the announcement at map time lands before a toolkit has
  asked for focus events.
- The shim answered `EVIOCSCLOCKID` with success and kept stamping events on the app's wall clock, so a
  program that asked for the monotonic clock read every button as decades out of date.

## Things worth knowing about the runtime

- `libSDL2-2.0.so.0` is sdl2-compat; `libSDL3.so.0` is the real library behind it.
- **Box64 puts a native library in place of a bundled one** where it has a wrapper for it. A game that ships
  its own SDL2 gets the runtime's sdl2-compat instead, which lacks symbols such as `SDL_roundf` and
  `SDL_RWsize`. `BOX64_EMULATED_LIBS` turns that off per library.
- Box64 0.4.4 cannot run a binary built against glibc 2.38 (`wcslcpy`, `wcslcat` are missing from its libc
  wrapper), which is why the SuperTux AppImage does not start. It is the only AppImage SuperTux publishes,
  and the components registry offers no newer Box64.
