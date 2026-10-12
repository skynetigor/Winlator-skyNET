# FEX in Linux containers

FEX (FEX-Emu) is the second x86-64 emulator a Linux container can use, next to Box64. It has not been run on a device
yet: everything below was written and built against FEX's sources and the two packages, and checked as far as a
desktop machine can check it.

## What FEX needs that Box64 does not

Box64 runs a program against the runtime's own libraries through wrappers. FEX translates the program and its system
calls, and the program needs an x86-64 userland to run in: a libc, a dynamic loader, the libraries it links against.
That is a **root filesystem** (`files/linux-rootfs/<id>`), a second package kind next to the emulator
(`files/linux-emulators/<id>`). FEX looks a program's files up in it first and on the phone's own file system second, so
the game folder under `/storage/emulated/0/...` is seen as it is.

## The packages

| Package | Made by | Size |
| --- | --- | --- |
| `fex-2610` (emulator) | `tools/linux-fex/package-fex.sh`: FEX-Emu's PPA build (Ubuntu 22.04 series, glibc >= 2.34) | 2.1 MB |
| `rootfs-ubuntu-24-04-2026-08-11` | `tools/linux-fex/package-rootfs.sh`: FEX's official Ubuntu 24.04 SquashFS, unpacked | 437 MB (1.8 GB unpacked) |

FEX's own images are SquashFS or EroFS files that FEX mounts through FUSE, which a proot session does not have, so the
root filesystem is unpacked on a computer and shipped as a tar.zst like the others. The emulator package keeps only
what a session uses: `bin/FEX`, `bin/FEXServer`, the Vulkan/OpenGL/drm/ALSA thunks and their database.

Both go into the registry (`winlator-skynet-components/linux.json`, `emulators` and `rootfs`); each script prints the
entry. Publishing them is a release in that repository (see the add-component notes).

## A session with FEX (`LinuxFex`, `LinuxSession`)

* The program is started as `FEX <program>`.
* A container's settings: the FEX build, the root filesystem, a preset and the thunks switch. The presets are the ones
  of FEXCore for Wine (`FEX_TSOENABLED`, `FEX_MULTIBLOCK`, ...), which FEX reads from the environment.
* `$HOME/.config/fex-emu/Config.json` is written for each start: the root filesystem, the thunk library folders, and the
  guest's `LD_PRELOAD` of our x86-64 shims. FEX can take any option from a `FEX_<OPTION>` variable, but it splits the
  variable at the last `=`, so the guest's environment (`LD_PRELOAD=...`) can only come from the file. A list (`Env`) is
  written as a repeated key, which is what FEX's JSON reader expects.
* Thunks: with them on, a game's x86-64 `libvulkan.so.1` and `libGL.so.1` are replaced by FEX's thunk libraries, which
  call the runtime's own libraries; so the game draws through the container's Turnip like a native program, and
  Vulkan layers of the runtime (frame generation) apply to it. Off: the root filesystem's own Mesa runs under emulation.
* The program's own x86-64 libraries (libXss for a game with an embedded Chromium, SDL) come from the root filesystem,
  not from the runtime's staged aarch64 copies, which Box64 needs and FEX does not.

## What was checked on the device (Z Fold8 Ultra, Android 17)

Installed by hand into the app's folders (the registry has no entries yet), in a container "Linux FEX":

* **FEX runs under proot.** An x86-64 program reports `machine=x86_64`, sees the root filesystem (`/etc/os-release` of
  Ubuntu 24.04), the phone's file system for paths the root filesystem lacks (`/root`, `/storage/emulated/0`,
  `/tmp/.X11-unix`), and its guest `LD_PRELOAD`. File calls through a symbolic link into shared storage work.
* **The Vulkan thunk works.** An x86-64 `vkdmabuf` finds `Adreno (TM) 840` with the dma-buf extensions through FEX.
* **BeamNG 0.34 starts under FEX** and runs its Lua engine, but it was not taken to a window. Three things stood in the way:
  1. *Shared storage is mounted noexec.* FEX maps a program and its libraries as executable (`mmap` failed with
     `EACCES`: "Failed to map 64-bit elf file"), where Box64 reads them. A program for FEX has to be copied into the
     container, like an ARM64 one. The Linux programs screen now does that for FEX and offers to copy the whole game
     folder: BeamNG finds `content`, `lua`, `settings` ... beside `BinLinux`, and its virtual file system does not follow
     symbolic links, so links back to the data on shared storage leave it with "cannot open file '/lua/...'".
  2. *The root filesystem lacks `libatomic.so.1`*, which BeamNG's binary links. `package-rootfs.sh` adds it (the
     files in the installed copy were put there by hand). Found by comparing every library the game's files need with the
     root filesystem's x86-64 libraries; nothing else was missing.
  3. *With the OpenGL thunk on, the start hung* (every thread asleep behind eight llvmpipe worker threads, no output).
     With it off the game went on. The thunk setting is therefore Vulkan only.

## What is not known

* Whether the game gets to a picture and how fast it is next to Box64: not run past the Lua start-up, because the
  copy of the whole game folder was not done on the device. The first run has no code cache either.
* Whether the guest's OpenGL (llvmpipe of the root filesystem or the runtime's, through the thunk) can be made to work.
