# BeamNG.drive 0.39 under Box64 in a Linux container

Status on 2026-10-12, Box64 0.4.4, container "Linux Box64", the game started straight from shared storage
(`/storage/emulated/0/Download/Games/BeamNG_0_39/BinLinux/BeamNG.drive.x64`, argument `-nosteam`). 0.34 runs; 0.39 does
not yet. Each stop below was found by running the game on the device; the last one is still open.

## Where it stops, in the order it is reached

| # | Symptom in the game's log | Cause | Status |
| --- | --- | --- | --- |
| 1 | `free(): invalid size`, SIGABRT after 1 s, backtrace `box64/free` <- `libdxcompiler.so+1959281` | 0.39 ships mimalloc (`libmimalloc.so.3`, the exe calls `mi_zalloc`, `mi_heap_new`, ...) and on Linux it takes over `malloc`/`free` for the whole process. Box64 cannot interpose the wrapped glibc, so a block mimalloc allocated reaches glibc's `free`. `BOX64_MALLOC_HACK` 0, 1, 2 only change where it dies | **Fixed.** `tools/linux-shim/mimalloc_compat.c` (`libmimalloc_compat_x64.so`): the `mi_*` functions the game imports, built on plain `malloc`, no `malloc`/`free` of its own. It must be preloaded: `BOX64_LD_PRELOAD=...:libmimalloc.so.3` (the file named like the library's soname). `BOX64_LD_LIBRARY_PATH` is not enough, the game's own copy is found first. **Not wired into the app yet** (tried by hand through `Winlator/linux-env.txt`) |
| 2 | SIGSEGV in `box64/vkGetInstanceProcAddr`, just after `no wrapper for vkCmdDispatchTileQCOM` | Box64's `resolveSymbol` (`src/wrapped/wrappedvulkan.c`) dereferences the missing wrapper when the driver returns a function Box64 has no wrapper for. Turnip 26.x (and the runtime's own) lists `VK_QCOM_tile_shading`, and the game asks for every function | **Fixed** by `scripts/linux/box64/patches/0002-vulkan-function-without-wrapper.patch` (components repo, not committed): return NULL when there is no wrapper. Tried with a Box64 built on the Mac (zig cc, see below) and put over the installed binary. A release of the patched build is needed |
| 3 | SIGILL (`ud2`) at `libcef.so+0x8770d6b`, 10 s after start | CEF's `base::i18n::InitializeICU` cannot open `icudtl.dat`: it looks in the **game root** (`.../BeamNG_0_39/icudtl.dat`), the depot has it only in `BinLinux/`. Found with an `open()` tracer (`LD_PRELOAD`). `/proc/self/exe` is fine (a test program showed Box64 returns the game's path) | **Worked around** by copying `icudtl.dat`, `resources.pak`, `chrome_100_percent.pak`, `chrome_200_percent.pak`, `snapshot_blob.bin`, `v8_context_snapshot.bin` from `BinLinux/` into the game root (28 MB). The app should do this without touching the user's folder, for example an `open()` fallback in `libskyshim_x64.so` for these names |
| 4 | SIGILL (`ud2`) at `libcef.so+0x39043bf`, about 1 s after `GELUA VM loaded` | Our X server's reply to XI 1.x `ListInputDevices` with no devices was 32 bytes with `length=0`; Chromium's reader (`x11::Input::ListInputDevices`) aligns the end of the names list one byte past an empty list and aborts when the length does not cover it. Same crash in Steam's web helper (aarch64 `brk`), see [linux-cef-x11.md](linux-cef-x11.md) | **Fixed** in `XInput2Extension.listInputDevices`: the reply lists the two master devices, which both Chromium and libXi accept |

## How it was investigated (for the next person)

* The game's own log is `Winlator/logs/beamng.drive_<time>.txt` (stdout of the program, Box64's messages included); the
  app's log is `beamng.drive_winlator_<time>.txt`. `BOX64_LOG=1` is on in a debug build.
* Debug aids that exist in a **debug build only** (code in `LinuxSession.java`):
  * `Winlator/linux-env.txt`: one `KEY=VALUE` per line, added to every Linux session (after the container's own
    variables and the Box64 preset), so a Box64 setting can be tried without the container editor.
  * `Winlator/box64-override`: a Box64 binary that replaces the installed one (copied into the emulator's folder once
    per file; shared storage cannot run programs). `Winlator/box64-override-files/*` are copied beside it.
* An `open()`/`access()`/`readlink("/proc/self/exe")` tracer for x86-64 programs: a 40-line `LD_PRELOAD` library
  (`BOX64_LD_PRELOAD=...:opentrace_x64.so`); it printed the missing `icudtl.dat` path.
* A Box64 built on a Mac, no Linux machine: `zig cc -target aarch64-linux-gnu.2.34` with a cmake from `pip`, the
  compilers wrapped in two scripts (`zig cc`/`zig ar`), `-mcpu=cortex_a76` and `-Wno-date-time` added (zig's
  spelling and its default `-Werror=date-time`). Docker Desktop is not usable here (sign-in is enforced).
* A static look at the failing code: `llvm-objdump` (the NDK has it) on `libcef.so`; the CHECK messages are strings
  in `.rodata` (`../../base/i18n/icu_util.cc`, `Couldn't mmap icu data file`, `Can't find icudtl.dat`).

## What to try next on #4

1. Find out whether the CEF zygote started and what it did: its output is not in the game's log (`--log-severity=disable`
   is passed by the game, our own `--enable-logging` and `--single-process` are ignored by it). A wrapper that logs the
   `execve` the game makes for the child, or a copy of `libcef.so` with the CHECK message patched in, would show it.
2. Compare with 0.34, whose CEF works: the same container, the same Box64.
3. Look at the other files the game's CEF expects in the game root (it passes `--locales-dir-path=<root>/locales/cef_linux`).
4. Ask upstream: Box64 issues about CEF/Chromium newer than M13x and `ud2`.

## Settings that were used

Container "Linux Box64", Performance preset, Turnip `turnip-v26.3.0-20261011-a8xx-linux`, frame generation on (turning it
off changed nothing), Box64 0.4.4 with patches 0001 (programs from shared storage) and 0002 (Vulkan), game started from
shared storage with Box64 (no copy needed, unlike FEX).

## Result (2026-10-12, later)

With the four fixes BeamNG 0.39 reaches its main menu (about 115 FPS, Alpha v.0.39.4) on Box64 from shared storage. The app does
items 1 and 3 by itself now (`LinuxSession.guestEnv`: the mimalloc stub is preloaded for a program whose folder has
`libmimalloc.so.3`; `SKY_RESOURCE_DIR` makes `libskyshim_x64.so` open `icudtl.dat` and the `.pak`/`.bin` files from the
program's folder when the game root lacks them). Item 4 is the X server. Item 2 still needs the patched Box64 (here a
debug build copies it over the installed one; users need a published build).

## What was left on the phone (written before the fix; the debug leftovers were removed afterwards) (to clean up or reuse)

* In the game folder `Download/Games/BeamNG_0_39/`: copies of `icudtl.dat`, `resources.pak`, `chrome_100_percent.pak`,
  `chrome_200_percent.pak`, `snapshot_blob.bin` (28 MB in all; `v8_context_snapshot.bin` was copied too). Needed for #3;
  harmless to delete if the work is rolled back.
* `Winlator/linux-env.txt` (only `BOX64_LD_PRELOAD` with the mimalloc stub), `Winlator/x86-overrides/` (the stub as
  `libmimalloc.so.3`, the `open()` tracer `opentrace_x64.so`), `Winlator/box64-override` (the patched Box64) and
  `Winlator/box64-override-files/` (CEF resources copied beside Box64 during an experiment that did not help; not
  needed). The debug build copies the override over its installed Box64 once.
* Linux Box64 container: the shortcut "BeamNG 0.39" (launch options `-nosteam`), and three test shortcuts `selfexe`,
  `selfexe2`, `selfexe3` (the first two have a wrong path) that can be deleted.
* Container settings were not changed in the end (the Box64 variables went through `linux-env.txt`).

## Files in the repository

* `tools/linux-shim/mimalloc_compat.c`, `libmimalloc_compat_x64.so` (the stub, committed earlier, built by `build.sh`).
* `app/src/main/java/com/winlator/cmod/linux/LinuxSession.java`: `debugEnv` (`linux-env.txt`) and `installDebugBox64`
  (`box64-override`), both for debug builds only. Not committed.
* `winlator-skynet-components/scripts/linux/box64/patches/0002-vulkan-function-without-wrapper.patch`: not committed
  there; it needs a new Box64 build and release (`linux-box64-0.4.4`, the registry entry in `linux.json`).
* `docs/linux-fex-plan.md`: FEX is parked; this game runs on Box64 only.

## Decisions that were open

1. Publish a Box64 build with patch 0002 (a public release in the components repo; the registry entry in `linux.json`);
   until then 0.39 works only in a debug build with `Winlator/box64-override`.
