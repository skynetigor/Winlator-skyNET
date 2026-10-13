# Linux containers: game profiles and the Steam container

Written 2026-10-13. Nothing here was compiled or run yet when this was written; the checks are listed at the end.

## Game profiles

The registry (`games.json` in the components repository, see its README, "Game profiles") says how to start a game and what works
for each version of it. `games.json` is a short index; a game's own file (`games/<id>.json`) is read only when something needs it.
The app keeps what it downloaded (`files/registry-cache/<branch>/`, 6 h for the index, 24 h for a game), falls back to the
last copy, and to the copy bundled in `assets/registry/` when there is nothing else (`contents/RegistryCache.java`). The launch
path never waits for the network.

A shortcut follows a profile through four extras (`gameprofile/GameProfiles.java`):

| Extra | Meaning |
| --- | --- |
| `gameProfile` | the game's id; `none` when the user took the profile off; empty: the registry's profile for a Steam game's app id |
| `gameProfileVersion` | a version the user chose; empty: it is recognised |
| `gameRoot` | the game's folder as the guest sees it, for a game Steam did not install |
| `noSteam` | empty: the profile's choice, `1` on, `0` off |

**Which version.** The profile's versions are tried in file order; a version is recognised when one of its detectors fits: the
Steam build id (only for Steam installs), the size or hash of files, or a version number found in the first or last bytes of a small
file (BeamNG: the end of `integrity.json`). Detectors only look at files, so a game that did not come from Steam is recognised the
same way. Nothing recognised: the profile's `default` version.

**What the profile gives a shortcut** (the shortcut's own setting always wins; an empty one means "profile default", then
"container default"): the executable, arguments, variables, resolution (`native` or `WxH`) and a few extras (scaling, upscaler, touch,
frame generation, see `GameProfile.ALLOWED_EXTRAS`).

**Launch without Steam.** A Steam game whose profile says so (BeamNG: `-nosteam`) is started as its own executable, not through the
client: the program of `steamapps/common/<installdir>/<executable>`, its folder as the working directory, through Box64 as any
program. Turning "Launch without Steam" off in the shortcut starts it through the client as before. A game that ships mimalloc
(BeamNG) gets the stub that keeps Box64 from mixing allocators either way (`SKY_MIMALLOC_STUB`, set by `LinuxSession.guestEnv` and used
by `assets/linux/skynet-launcher.sh` for a game the client starts).

**Resolution.** A session that runs the Steam client (the Steam shortcut, a game started through it) is always native: the game picks its
own mode through RandR, or the user sets it in Steam. Any other session: the shortcut's size, then the profile's, then the container's
(`GameProfiles.screenSize`).

Programs added by hand take a profile too: the "Add program" dialogs have a "Game profile" choice (game and version), and a program
picked from storage that is a registry game's executable is offered it.

## The Steam container

There is one Steam container, made by the app (`steam/SteamContainer.java`), marked `linuxManaged=steam`. It is created, from the
`steam` block of the registry's `linux.json` (runtime, Box64 build and preset, Turnip driver, OpenGL driver, Vulkan present), when the
Linux runtime, Box64 and the Turnip driver are all installed. Settings, Steam: the components to download (one button, one bar per
package), then the container's settings; they are greyed out until the container exists. The Steam shortcut in the library exists only while
the container's runtime does; the games installed in Steam keep getting shortcuts of their own (`LinuxSteamGames.sync`).
The container cannot be removed, renamed or duplicated from the list; "Reset Steam" in its settings deletes it and makes a new one.

Steam always runs native and in Big Picture mode. The old per-container Steam options (`linuxSteam`, `linuxSteamBigPicture`) are gone from
the container settings; an old container that has them is an ordinary container now.

## Settings screen

The app's Settings use the layout of the container editors (`ui/settings/GroupedSettings.kt`): the groups are listed on the left when the
width allows (720 dp or more), otherwise they are sections that open and close; what is chosen or open survives a fold or unfold.

## To check on the device

1. A Steam game with a profile (BeamNG): the library shortcut gets `gameProfile`; "Launch without Steam" starts `BeamNG.drive.x64 -nosteam`
   with no client; off, it goes through Steam and does not die with `free(): invalid size`.
2. Adding BeamNG from storage or by path with the "Game profile" choice: the version (0.34 / 0.39) is recognised from `integrity.json`.
3. The Steam screen from scratch (fresh app data): download, the container appearing by itself, the shortcut in the library, Steam native and
   in Big Picture.
4. Settings on the unfolded display (list on the left) and the cover display (sections).
