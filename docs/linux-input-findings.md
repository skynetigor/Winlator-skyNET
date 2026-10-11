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

## The reproduction

The runtime's `libSDL2-2.0.so.0` is **sdl2-compat over SDL3**, not SDL2, so testing against it says little
about a game with SDL2 inside. A real SDL2 (Debian `libsdl2-2.0-0` 2.32.4 for aarch64, with
`libsamplerate0`) dropped into a directory on `LD_LIBRARY_PATH` makes the mouse tester run on the same
library BeamNG has, and it then **reproduces BeamNG's symptom exactly**, in half a minute rather than the
two and a half the game takes to start:

- motion works, the pointer position is right;
- the window has `SDL_WINDOW_MOUSE_FOCUS`;
- it never gets `SDL_WINDOW_INPUT_FOCUS`, so every key is dropped;
- `SDL_GetMouseState` stays `0x0` while a button is held for three seconds: SDL never sees the button at all.

Real SDL2 selects exactly the XInput2 masks BeamNG does — `40001c00` (XI_Motion plus the three touch events)
on its window, raw button and motion events on the root — which is what makes the two equivalent.

## Where the search stands

The server sends what SDL asked for, to the window SDL asked on:

```
core ButtonPress: pointWindow=4194312 grabWindow=4194312 enabled=true listeners=1
```

and the counts for one click and one key press come out as ButtonPress 1, ButtonRelease 1, KeyPress 1,
KeyRelease 1, FocusIn 6, MotionNotify 47, XI2 generic events 96. SDL raises only the motion.

### Narrowed to this

The window is right: SDL reports its own X window as `0x400008`, which is the 4194312 the server delivers
to. That lead is closed.

With `SDL_SYSWMEVENT` turned on, SDL reports every X event its own dispatcher handles. Over a whole run with
a click and a key press, it saw:

```
X events seen by SDL: 22=1 28=3 35=2
```

ConfigureNotify once, PropertyNotify three times, GenericEvent twice — the two GenericEvents being the XI2
raw button press and release. **No core event ever reaches SDL's dispatcher**: not ButtonPress, not
KeyPress, not FocusIn, not EnterNotify, though the server sends all of them to that window and a plain Xlib
client on the same server receives them normally.

So the question is no longer "why does SDL ignore our events" but "why do our core events never arrive on
SDL's connection, while our XI2 events on the same connection do".

Ruled out so far: the window id; the input method (`XMODIFIERS=@im=none` changes nothing);
`XIPassiveGrabDevice`, which this SDL never uses, so the handler written for it in `XInput2Extension` is
dead code.

Worth trying next:

1. Diff the two connections in the request trace — what SDL does at setup that the raw Xlib probe does not.
   The raw probe gets core events; SDL does not; something in between differs.
2. Check the server's own delivery once a client holds XI2 selections. The trace says the core listener is
   there (`listeners=1`) and the bytes are written, so suspect what is written rather than whether:
   the event's sequence number, or a flush that waits behind another thread's hold on the stream lock.
3. SDL asked `GetInputFocus` nine times in one run — it confirms focus by asking rather than trusting the
   event. If the core FocusIn never arrives, that polling is all it has left.

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
