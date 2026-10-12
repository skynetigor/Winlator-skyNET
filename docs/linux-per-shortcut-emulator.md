# Linux containers: Box64 / FEX chosen per shortcut (plan)

Status: plan, nothing implemented. Goal: one Linux container is enough; the x86 emulator (and its settings) can be
overridden by a shortcut, "Container default" being the default.

## What is already global

Emulator builds (`LinuxEmulator.list`, `baseDir`) and FEX root filesystems (`LinuxRootfs`) are installed per app, not per
container. A container only stores a choice (`linuxEmulator`, `linuxEmulatorId`, `linuxEmulatorPreset`, `linuxRootfsId`,
`linuxFexPreset`, `linuxFexThunks`). So no install changes are needed.

## Where the container's choice is read today

All of these take a `Container`; none knows the shortcut:
- `LinuxEmulator.choice(container)` / `resolve(context, container)`
- `LinuxRootfs.resolve(context, container)`
- `LinuxSession.programCommand(...)`: chooses the emulator path for an x86-64 ELF, refuses when none / not installed / FEX
  without rootfs.
- `LinuxSession` session setup (~line 352): the FEX rootfs binds (`/proc /sys /dev`, shm, input) and (~451-510) `guestEnv`:
  Box64 variables and preset, `SKY_X86_EMULATOR`, FEX preset.
- `LinuxFex` writes `$HOME/.config/fex-emu/Config.json` (rootfs, thunks, `Env` LD_PRELOAD of the x86 shims).

The proot binds, the guest environment and Config.json are decided when the session is built, so the override must reach
that point, not only `programCommand`.

## Design

1. **One value object**: `LinuxEmulatorSetup` (choice, installed build, rootfs, presets, thunks) built by
   `LinuxEmulatorSetup.of(context, container, shortcut)`: every field is the shortcut's extra when not empty, else the
   container's. `shortcut == null` gives today's behaviour (Steam client, tools, debug shell). Replace the direct
   `choice/resolve` calls in `LinuxSession` and `LinuxFex` with it.
2. **Shortcut extras** (empty = container default), same names as the container's so the setup code is shared:
   `linuxEmulator` (box64 / fex), `linuxEmulatorId`, `linuxEmulatorPreset`, `linuxRootfsId`, `linuxFexPreset`,
   `linuxFexThunks`. "None" is not offered for a shortcut.
3. **Editor**: a "x86 emulator" group in `LinuxShortcutEditor.kt` (category `program`, not for Steam games), reusing the
   pickers from `LinuxEditorParts.kt` that the container editor uses; the FEX-only rows (rootfs, preset, thunks) shown only
   when FEX is selected; the build picker lists only installed builds of that emulator.
4. **Session**: pass the shortcut into the session build, so the binds, `guestEnv` and `Config.json` use the setup.
   The refusals keep their wording (not installed, FEX needs a rootfs).
5. **FEX needs the program inside the container** (storage is noexec): when FEX is selected for a shortcut whose program
   is on shared storage, refuse with a message telling to use "Program only" / copy, instead of failing in proot.

## Open questions to check first (read the code, then decide)

- How a shortcut start reaches `LinuxSession`: whether a running session (same container, other shortcut) is reused or a
  new one is started per shortcut. If it is reused, the first shortcut's emulator wins and the plan needs "start a new
  session when the setup differs" (or refuse while a session with another setup runs).
- Whether `Config.json` is rewritten at every session start (it is believed to be) and not cached by FEX across sessions.
- Whether Box64 and FEX leave files in `$HOME` that disturb each other (shader caches, Steam files). Test one game on both
  in the same container.

## Steam games (separate step, not in the first change)

Steam games run inside the Steam client's session, and the emulator there is set when that session starts
(`SKY_X86_EMULATOR` and the shim). Only Box64 is supported for them today. Per-game choice would need
`skynet-launcher.sh` to pick the emulator per `appId` and FEX to work for games under Steam (untested). Until then the
emulator rows are hidden for Steam game shortcuts.

## Verification (only when asked to build / run)

- A Box64 container with a shortcut set to FEX (program copied in): the program starts under FEX; and the other way round.
- "Container default" shortcuts behave as before; the Steam client and the debug shell are unchanged.
- The refusals: emulator removed, FEX without rootfs, FEX with a program on shared storage.
- Two shortcuts of one container with different emulators, started one after another and at the same time.
