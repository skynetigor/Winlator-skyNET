# FEX in Linux containers: where it stands and what is next

Status on 2026-10-12. How FEX is wired in is described in [linux-fex.md](linux-fex.md); this file keeps what was
learned running it on the device (Z Fold8 Ultra, Android 17, container "Linux FEX", BeamNG.drive 0.34 and 0.39) and
the plan from here.

## In one paragraph

**Update (2026-10-12, later): BeamNG 0.39 runs on FEX**, from the first-run screens through the main menu into West Coast USA,
about 27 FPS GPU-bound (Box64 gave about 34 on the same map). It had stopped at the game's CEF menu for two reasons, both
fixed now:

1. proot refuses `execveat` with a directory descriptor, which FEX uses to start a program and a guest's `fexecve` reaches.
   `libskyshim` (aarch64) now turns `execveat`/`fexecve`/`syscall(SYS_execveat)` into an `execve` of the path the descriptor names
   (`/proc/self/fd/N`). No proot rebuild was needed.
2. FEX looks a path up in the root filesystem first, and its `/proc`, `/sys` and `/dev` are empty folders. Chromium opens
   "/proc" and stats "self/task/" inside it (its sandbox check, and so every helper process, died on that), which found
   nothing. The session now binds the real `/proc`, `/sys`, `/dev`, `/dev/shm` and `/dev/input` over the root filesystem's
   (`LinuxSession.build`).

The program is still copied into the container (shared storage is noexec for FEX): "Program only" copy of BinLinux with the
data bound in. The `ld.so ... cannot be preloaded` lines in the game's log are the host's loader refusing the guest's x86-64
`LD_PRELOAD` in FEX's own processes; they are harmless. What is left: the Lua error about
`/sys/class/power_supply/BAT0` (permission denied) when the menu opens, and the speed.

(The text below is what was known before.)

## What works

| Thing | Evidence |
| --- | --- |
| FEX under proot, x86-64 programs against the Ubuntu 24.04 root filesystem | `machine=x86_64`, `/etc/os-release` of Ubuntu |
| Vulkan thunk: the x86-64 game draws through the runtime's Turnip | game log: `Linking address ... to host invoker`, `GPU (Vulkan)` adapter, HUD "Vulkan, Adreno (TM) 840" |
| BeamNG data found with only the program copied (`Program only`) | 0.39: `GELUA VM loaded` with `lua`, `content`, `settings` bound from shared storage |
| BeamNG 0.34 whole-game copy | 04:19 run: all modules loaded in ~5 s, 521 missions, input bindings |
| Launch options, per-shortcut settings, FEX presets and thunk switch in the editor | tested earlier in the session |

## What does not work, and why

### 1. The game's menu never appears (blocker)

* The game starts its CEF render process about 20 s in. proot prints
  `proot error: execveat() with non-AT_FDCWD fd is not currently supported` and the process is never created.
* The game waits 120 s (`Timeout on CefTexture renderer: mRendererTimeout 120.020 > 120.000`), then shuts itself down
  (`fatalSubprocessError ... Shutting down as response to CEF error`). The screen shows colour noise the whole time,
  because nothing draws the UI.
* Box64 starts the same helper without trouble, so this is specific to FEX.
* The cause is on FEX's side of the syscall: `Source/Tools/LinuxEmulation/LinuxSyscalls/Syscalls.cpp`, the end of the
  execve handler, calls `syscall(SYS_execveat, Args.dirfd, "/proc/self/exe", ...)` (and `execveat(Args.dirfd, Filename,
  ...)` for an x86-64 binary that needs no loader). When the guest itself calls `execveat`/`fexecve` with a file
  descriptor, that descriptor is passed on. The runtime's proot (Termux fork v5.1.107.92, prebuilt, `opt/android-host/proot`)
  rejects every `execveat` whose directory fd is not `AT_FDCWD`.
* Not verified: which call in the game makes the descriptor-based exec (CEF's process launcher, or the crash reporter),
  and whether the SIGTRAP seen on 0.39 right after the two proot errors is the same problem or a separate one.

The colour noise itself is not a bug: under Box64 the same noise shows for 2-3 s at start, until the first UI frame.

### 2. Shared storage is `noexec`

* FEX maps a program and its libraries with `PROT_EXEC`; on `/storage/emulated/0` the kernel answers `EACCES`
  ("Failed to map 64-bit elf file"). The Wine/Proton way (read the PE file into memory when `mmap` is refused) has no
  counterpart in FEX's ELF loader. `chmod +x`, changing environment variables (`FEX_APP_DATA_LOCATION`, `TMPDIR`) and
  invoking `FEXBash`/`FEXInterpreter` do not change that.
* Box64 does not have the problem: its runs of `/storage/emulated/0/Download/Games/BeemNG_0_34_Steam_Depot/BinLinux/...`
  worked (logs of 04:50 and 04:58). **Box64 needs no copy at all.**
* Workaround that exists (the Linux programs screen, `LinuxProgramsFragment.kt`):

  | Choice | Result | Size |
  | --- | --- | --- |
  | Program only, data stays on storage | copies `BinLinux` into `programs/<game>/`; the other folders are bound in from storage by proot (`.binds`, `LinuxSession.bindDataFolders`) | 464 MB for BeamNG |
  | Whole game | copies everything | 50 GB for BeamNG |
  | Folder only | copies only the program folder; a game that keeps data beside it cannot start | 464 MB |

* A bind is used rather than a symbolic link because BeamNG's virtual file system does not follow symbolic links.
* A copy that fails is removed; a copy no shortcut uses is listed in the program list with a delete button; removing a
  program offers to delete its copied files.

### 3. Thunks cannot be turned off

Without the Vulkan thunk the x86-64 guest has no GPU adapter (`Not able to find graphics adapter`, only the Null device)
and BeamNG quits; the root filesystem has no software Vulkan either. The GL thunk hung earlier and stays off.

## Smaller things found on the way

* The root filesystem lacked `libatomic.so.1`; `package-rootfs.sh` adds it. The published package has not been rebuilt
  since (the copy on the device was patched by hand, and so was `ld.so.preload`).
* Piping a FEX session's output into `head` ends the game with SIGPIPE; not a bug, but it spoils tests.
* The packages are not in the registry. `fex-2610` and the rootfs exist only in the session's scratch folder
  (`fex-2610-linux-aarch64.tar.zst` 2144305 bytes, sha256 `5a6049749a96564434bec293cf873fcff2d24b7241cca645f8a8d8f81d6c5921`;
  `rootfs-ubuntu-24-04-2026-08-11.tar.zst` 437359044 bytes, sha256
  `66eceb6747a4531b30be668f4c45bc298e735d30699568a3d06f3cf7e6b72396`, built before the libatomic and `ld.so.preload`
  changes).
* FEX's speed against Box64 has not been measured. Do that once the menu works, on the BeamNG grid map.

## Plan

1. **Make proot accept `execveat` with a descriptor** (the blocker).
   * Where: the Termux fork's source, not `app/src/main/cpp/proot` (an old 5.1.0 snapshot that cannot exec on a current
     Android). The Bannerlator repo builds the fork in `.github/workflows/build-proot.yml` with patches under
     `tools/proot/patches`; the committed binary is Termux's own, deliberately (our own build broke GTK, see
     `tools/linuxfs/prebuilt/proot/README.md` there).
   * Change: for `execveat` with a directory fd other than `AT_FDCWD`, or `AT_EMPTY_PATH` with an empty path, read the
     target of `/proc/<pid>/fd/<n>` (joined with the directory fd's path for a relative name) and handle the call as the
     `execve` of that path, as the existing exec code already does for `execve`.
   * Build it with the NDK, check it first on a tiny program (a C test that calls `execveat(fd, "", ..., AT_EMPTY_PATH)`),
     then on BeamNG. The patched binary replaces `opt/android-host/proot` in the runtime; the app stages the runtime
     itself, so this goes out as a new runtime release (`linuxfs-r10`), not as an app update.
   * Before building anything, find out who makes the call: run the game once with proot's `-v 5` and FEX's strace log
     (`FEX_STRACELOG`/`FEX_OUTPUTLOG`), and read which binary and arguments the failing `execveat` has.
2. **Check 0.39** after step 1: the SIGTRAP at about 20 s may go away with the helper process. If it stays, take it up
   as a separate problem (the log of the game plus FEX's log).
3. **Measure FEX against Box64** on the same map, with the same upscaler and output settings.
4. **Publish** `fex-2610` and a rebuilt root filesystem (add-component notes: package, test on the device first, release,
   registry entry in `linux.json`). Do not publish until step 1 is done: a FEX container that cannot show a Chromium UI
   is not worth offering.
5. **Optional, to avoid copying for FEX too**: try a small `LD_PRELOAD` that retries `mmap` without `PROT_EXEC` when it
   fails with `EACCES`. It would only work if FEX's loader goes through libc (its system calls are mostly raw), so check
   FEX's source first; do not start with it.

## Decision for now

Until step 1 is done, a game that needs CEF (BeamNG) runs on **Box64**, straight from shared storage, with no copy.
FEX stays an option in the container editor for programs that do not start helper processes.
