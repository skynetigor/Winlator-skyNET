---
name: add-component
description: Use when asked to add or publish a component to the Winlator skyNET registry (Wine/Proton, DXVK, VKD3D, Box64/WOWBox64, FEXCore, Turnip/Adrenotools driver, game container config). Verify the registry first, then look for builds that already work in Winlator, then compile ourselves; build, release and registry steps for builds that are not downloadable.
---

# Adding a component to winlator-skynet-components

The app reads **only** `contents.json` from `skynetigor/winlator-skynet-components` (clone next to this repo:
`/Users/ihor/Documents/winlator-skynet-components`). Raw URL:
`https://raw.githubusercontent.com/skynetigor/winlator-skynet-components/main/contents.json`.
Work in that order and stop at the first step that gives the user what they asked for.

## 1. Is it already in the registry?

Fetch the live registry and check type and version. Compare by what the app shows (`verName`), not just `name`.

```sh
python3 -I - <<'EOF'
import json, urllib.request
d = json.load(urllib.request.urlopen('https://raw.githubusercontent.com/skynetigor/winlator-skynet-components/main/contents.json'))
for x in d:
    if x['type'] == 'FEXCore': print(x['id'], x['verName'], x['remoteUrl'])   # change the type / filter
EOF
```

- The raw URL is cached ~5 minutes after a push; for the authoritative file use
  `gh api repos/skynetigor/winlator-skynet-components/contents/contents.json --jq .content | base64 -d`.
- Check every candidate link (`curl -sIL -o /dev/null -w '%{http_code}' URL`, expect 200) and look inside the package
  (below). A registry entry can exist and still be wrong or dead.
- Tell the user what exists, including near misses (e.g. upstream tag with no release, `2609` present but not `2609.1`).

## 2. Is there a build that already works in Winlator?

Check the known sources before building anything. Always open the package and compare it with the formats below; a
release existing is not enough.

| Kind | Known sources |
| --- | --- |
| DXVK/VKD3D/Box64/WOWBox64/FEXCore `.wcp` | `StevenMXZ/Winlator-Contents`, `ziad9267/Winlator-Contents` (already mirrored in the registry) |
| Proton/Wine for Android | `GameNative/proton-wine` (Valve Proton 11.x, `x86_64` and `arm64ec` `.wcp`, built daily). Stock Linux builds (proton-cachyos, GE, Valve tarballs) are NOT drop-in: they are glibc Steam-runtime builds, the working packages are bionic/Android-patched |
| Turnip / Adrenotools zips | `StevenMXZ/freedreno_turnip-CI`, `StevenMXZ/Adreno-Tools-Drivers`, `whitebelyash/AdrenoToolsDrivers`, `Droid-Deck/Drivers-CI` |
| Upstream sources for our own builds | `doitsujin/dxvk`, `ptitSeb/box64`, `FEX-Emu/FEX` (releases have no packages; the registry's FEX entries are repacks of the PPA build), `HansKristian-Work/vkd3d-proton` |

`gh api repos/<owner>/<repo>/releases --jq '.[0:5][]|[.tag_name,.published_at[0:10],([.assets[].name]|join(", "))]|@tsv'`
lists recent releases. Do not copy a driver or component from a source you could not open and check.

### Stock Linux Wine does not run here

Tested with `proton-cachyos` x86_64 repacked as a Winlator Proton: the container is created, but launching fails at once with
`Error: Global Symbol __libc_start_main not found, cannot apply R_X86_64_GLOB_DAT ... in .../bin/wine` followed by SIGABRT
(Box64's wrapped libc has no glibc `__libc_start_main`). Android-ready Wine/Proton links against bionic (`libc.so`, `libdl.so`,
interpreter `/system/bin/linker64`, as in GameNative's packages); stock builds link `libc.so.6` and `ld-linux-x86-64.so.2`.
Check a candidate with `llvm-readelf -d lib/wine/x86_64-unix/ntdll.so | grep NEEDED` before repacking: `libc.so.6` means it needs a
rebuild for Android, not a repack. The plain `arm64` variants (Wine ARM64 + FEX wow64) are not an `arm64ec` runtime and the
app's identifier pattern (`WineInfo`) knows no such architecture.

### What the app accepts

- **`.wcp`** = tar compressed with **xz** (or zstd; the app tries xz first). It contains `profile.json` plus the files.
  **It must contain directory entries** (`system32/`, `syswow64/`, ...): the extractor does not create parent folders,
  and a tar with only file entries fails with `ERR_BADTAR`. Python: `t.add(dir, name, recursive=False)` before the files.
  `profile.json`: `type` (Wine, Proton, DXVK, D7VK, VKD3D, Box64, WOWBox64, FEXCore), `versionName`, `versionCode`,
  `description`, `files: [{source, target}]` with targets `${system32}`, `${syswow64}`, `${libdir}`, `${bindir}`.
  Each type has a trusted target list (`ContentsManager.*_TRUST_FILES`): DXVK `d3d8/d3d9/d3d10core/d3d11/dxgi` in both
  system dirs, VKD3D `d3d12/d3d12core`, Box64 `${bindir}/box64`, WOWBox64 `${system32}/wowbox64.dll`, FEXCore
  `libwow64fex.dll`, `libarm64ecfex.dll` and the two `aarch64-unix/*.so`.
- **Wine/Proton `.wcp`**: `bin/`, `lib/wine/{x86_64-unix,x86_64-windows,i386-windows}`, `share/wine`, `prefixPack.txz`
  (the prefix under `.wine/`), and `"wine": {"binPath":"bin","libPath":"lib","prefixPack":"prefixPack.txz"}` in `profile.json`.
- **Adrenotools driver zip** (`RendererDriver`): `meta.json` (`name`, `driverVersion`, `libraryName`) and the library at the
  zip root. Bundle zips with the driver under `android/` are installed only by builds containing commit `4c7866d`
  (`AdrenotoolsManager.promoteBundledAndroidDriver`); older builds reject them.
- **Game container** (`Container`): a `.wcfg` export. Use `scripts/containers/build-registry.py`; never hand-edit those entries.

### Registry entry rules

Flat objects: `id` (stable, never changes), `name` (free text), `type`, `verName`, `verCode`, `remoteUrl`; drivers also
`source`. The **Components screen shows `verName`, not `name`**, so put any prefix the user wants (e.g. `exp`) in `verName`
(and keep `versionName` in `profile.json` equal to it). ARM64EC builds must have `arm64ec` in `verName` (the app filters
on it); a plain `arm64` build is not ARM64EC. Wine/Proton `verName` must fit `[label-]<version>[-<build>][-label]-<arch>` with arch `x86_64`, `x86` or `arm64ec` (for example
`exp-cachyos-11.0-20261005-slr-x86_64`); anything else makes the app fall back to the bundled runtime and container creation fails.
Ids: `<type>-<verName>` lowercased, e.g. `fexcore-2610`,
`renderer-driver-whitebelyash-tu-v32-mainline-turnip-v32`. Insert new `RendererDriver` entries after the last one.

## 3. Compile it ourselves (only when nothing usable exists)

Prefer the committed recipes in the registry repo: `scripts/dxvk/` (`build-arm64ec.sh`, `arm64ec.cross.txt`, `pack.py`) and
`scripts/proton/repack-cachyos.py` (a repack, not a rebuild). Work in the session scratchpad, not the repos.

- Toolchain without installing anything system-wide: download `mstorsjo/llvm-mingw` (`...-ucrt-macos-universal.tar.xz`) for
  `arm64ec-w64-mingw32` and `aarch64-w64-mingw32`, `python3 -m venv` + `pip install meson`, `brew` has `ninja` and `glslang`.
- Clone the upstream **tag** (full clone: shallow clones choke on annotated tags), `git submodule update --init`.
- DXVK 3.0 needs `-Dcpp_args=['-include','new','-include','memory','-include','algorithm']` with libc++.
- Verify the result: `llvm-readobj --coff-load-config` should show ARM64EC code maps; PE machine 0x8664 is normal for ARM64EC.
- Record provenance as you go (upstream tag and commit, source tarball sha256, tool versions, flags) for the release notes.
- A build you could not run is **untested**: say so in the notes and to the user.

## 4. Deploy a build that cannot be downloaded

1. **Package** in the format above. Test with an extractor that does *not* create parent folders (mimic the app) and
   check file lists. Keep the packaging script in `scripts/` and commit it first, so the notes can link to it.
2. **Test on the phone before publishing** when there is real doubt it runs: `adb -s RFGL80SVVLP push` the file to
   `/sdcard/Download/games/winlator_components/`, install with *Settings > Components > Install local ...*, then launch
   (logs: `/sdcard/Winlator/logs`). Prefer the USB serial; the wireless link stalls on big transfers.
3. **Release** in `skynetigor/winlator-skynet-components`: tag `<component>-<version>` (e.g. `dxvk-3.1.1`), one release per
   version with all variants attached, `--target main` (a short SHA is rejected). Notes must say: what it is, downloads
   with SHA-256, upstream tag/commit/tarball sha256, how each package was made, toolchain versions and flags, link to the
   build script commit, package contents, install steps, licence, and anything untested.
   `gh release create <tag> -R skynetigor/winlator-skynet-components --target main --title "..." --notes-file notes.md files...`
   To fix assets later: `gh release upload --clobber` and `gh release edit --notes-file` (update the SHAs).
   Do not redistribute proprietary files (e.g. `Lossless.dll`); GPL builds need their licence and a source link.
4. **Registry**: add the entry with `remoteUrl = https://github.com/skynetigor/winlator-skynet-components/releases/download/<tag>/<file>`,
   commit, push to `main`.
5. **Verify live**: `gh api .../contents/contents.json` shows the entry, and every new `remoteUrl` returns 200 with
   `curl -sIL` (use GET-based curl, not Python `HEAD`, which fails on the redirect).

## Rules of thumb

- Registry changes and releases are public. Do them when the user asked to add or publish; confirm first when the build is
  large, untested, or a history rewrite/force-push would be needed (`--force-with-lease` only on explicit approval).
- Be explicit about what you verified and what you assumed (e.g. "matched by date, not by commit").
- macOS gotchas: BSD `sed -i ''` has no `\s`; in zsh `"$b:path"` expands as a modifier, write `"${b}:path"`; `git rm` removes
  an emptied directory.
- After app-side changes (installer, Components UI) the phone needs a new build; older installs keep the old behaviour.
