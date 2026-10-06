# BeamNG tuning: test configs, component sources, env vars

Companion to [container-setup.md](container-setup.md). Research date: 2026-10-06.
**VERIFIED** = read in code, docs or release notes. **UNVERIFIED** = inferred or not checked.

## 1. Test configs (`configs/*.wcfg.json`)

Import via **+ add container → Import**. Every file is derived from the working export `BeemNG_0_34_SD8Elite_optimized.wcfg.json`, so it inherits that container's driver (`Turnip Gen8 V36`, also as `rendererDriverId`), GPU-name spoof (`NVIDIA GeForce GTX 1060`), `extraData` and input settings. Each differs from `BNG_01` by one setting.
Not exported from the app: if an import rejects one, say which and why.

**Black-screen investigation (device logs, 2026-10-06)**
- Early runs: BeamNG stalled right after start (`beamng.log` stopped after 3 lines). Wine's `winex11.so` (Proton 11.0-2) segfaulted while initialising inside BeamNG's process, so the progress dialog could never open. A hand-made Proton 9 container then showed 32 access violations in `win32u.so` (fontconfig init, brushes, locks), so the cause is not specific to one Wine build, the import, LSFG (ruled out) or the driver and GPU spoof (corrected to match the working export; no change).
- Every failing run used the Box64 PERFORMANCE settings (`CALLRET=1`, `BIGBLOCK=1`, `FORWARD=256`, `SAFEFLAGS=1`, `STRONGMEM=0`).
- After a fresh app reinstall, a default container (COMPATIBILITY preset: `SAFEFLAGS=2`, `BIGBLOCK=0`, `STRONGMEM=1`, `FORWARD=128`, `CALLRET=0`) ran BeamNG with zero access violations and rising memory/CPU use.
- Not yet separated: the reinstall itself vs the preset. Confirm by importing `BNG_07` (COMPATIBILITY) on this install, then raise one Box64 setting per run (`CALLRET`, `BIGBLOCK`, `FORWARD`, `SAFEFLAGS`) until the crashes return.
- Treat `container-setup.md`'s claim that `BIGBLOCK=1` is right for LuaJIT as unverified.

| File | Change | Question it answers |
|---|---|---|
| `BNG_00_reference_copy` | Your export, unchanged except the name | Control: uses the PERFORMANCE Box64 settings that crashed on this device |
| `BNG_01_baseline_clean` | 00 minus env vars that do nothing on the D3D11→DXVK path (`ZINK_DESCRIPTORS`, `mesa_glthread`, `MESA_GL_VERSION_OVERRIDE`, `WINE/PROTON_*LARGE_ADDRESS_AWARE`, `BOX64_DYNACACHE_LIMIT`) | Do those vars matter? |
| `BNG_02_bigblock0` | `BOX64_DYNAREC_BIGBLOCK=0` | Box64 docs suggest 0 for JIT-heavy programs (LuaJIT) |
| `BNG_03_bigblock3` | `BOX64_DYNAREC_BIGBLOCK=3` | Box64 docs suggest 3 for Wine programs |
| `BNG_04_tu_noconform` | adds `TU_DEBUG=noconform` | Effect of the Turnip debug flag the app uses by default (your export sets none) |
| `BNG_05_lowmem_cool` | 720p, 60 fps cap, 2048 MB reported memory, 2 DXVK compiler threads | Heat and RAM headroom on heavy maps |
| `BNG_06_fex_arm64ec` | `proton-11.0-1-arm64ec` + FEXCore 2601 + DXVK 2.3.1 arm64ec gplasync, Box64 vars removed | Whole-stack comparison against Box64 |

**Before testing**
- Imports need `proton-11.0-2-x86_64`, DXVK `2.6-2-gplasync` and `Turnip Gen8 V36` (the export records its download URL); the importer fetches them.
- Container `envVars` are applied **after** the Box64 preset (`GuestProgramLauncherComponent.java:461`), so `BOX64_*` entries in `envVars` override the PERFORMANCE preset (VERIFIED; the device's launch log shows `BIGBLOCK=1`, `FORWARD=256` taking effect).
- Test protocol: same map (Grid V2 then a heavy one), same vehicle, same 60 s route; run each map twice so the shader cache is warm; note fps, `MemAvailable` and battery temperature.

## 2. Where the app gets its components (VERIFIED from code)

| Component | Source | Notes |
|---|---|---|
| Remote catalog (Wine/Proton, DXVK, D7VK, VKD3D, Box64, WOWBox64, FEXCore) | `https://raw.githubusercontent.com/StevenMXZ/Winlator-Contents/main/contents.json` (`ContentsManager.java:27`) | Overridable via pref `downloadable_contents_url`; installs as `.wcp` into `files/contents/<Type>/<verName>-<verCode>/` |
| Proton 9/10/11 built-ins | `Other-backup/winlator-imagefs-v2` releases `c/`, `d/`; `proton-11.0-2-x86_64` from `GameNative/proton-wine` release `proton-11.0-2-20260928` (`ProtonPackageManager.java:26-81`) | SHA-256 checked |
| Turnip / Adreno drivers | `StevenMXZ/freedreno_turnip-CI`, `whitebelyash/AdrenoToolsDrivers`, `Weab-chan/freedreno_turnip-CI` releases; custom repos in pref `custom_driver_repos` (`RepositoryManagerDialog.java:119-131`) | Zips with `meta.json`. Repos named "kimchi" or URL containing "k11mch1" are filtered out (`:139-143`) |
| imagefs / Proton 9 arm64ec | `Other-backup/winlator-imagefs-v2` (`app/build.gradle`) | Build time only |
| Bundled | Box64 0.4.2, WowBox64 0.4.2, FEXCore 2601, DXVK 1.10.3 / 1.11.1-sarek / 2.3.1 (+arm64ec variants), VKD3D 2.8 / 2.14.1, Turnip 26.2.0 / v863 | `app/src/main/assets/` |

## 3. Newer or better components to try

| Component | Newest found | Why | Status |
|---|---|---|---|
| Turnip | [Banners-Turnip](https://github.com/The412Banner/Banners-Turnip/releases) `v26.3.0-20261006-r2` (CI only; its A8xx variants failed to build in that run). Device-tested-era builds: `v26.3.0-20260809-r3` and `-20260811-r11` | Based on whitebelyash's gen8 stack with A8xx fixes. Its headline fix targets D3D12, so it won't help BeamNG's D3D11 path | Add as a custom driver repo (`custom_driver_repos`) or install the zip manually. VERIFIED that releases exist; not verified on your device |
| Box64 | v0.4.4 (2026-08-02): DynaCache on by default with compression, accuracy fixes. Winlator 11.2 beta bumps to 0.4.4 | DynaCache cuts relaunch JIT cost | Not in the bundled assets; needs a catalog `.wcp` (not checked whether one exists) |
| FEX-Emu | FEX-2609 (2026-09-08): JIT improvements plus an on-disk JIT code cache (FOZ) | Less stutter after the first run | Needs a catalog `.wcp` (not checked) |
| DXVK | 3.1.1 (2026-09-15); 3.x has CPU-overhead fixes | Newer than the 2.6-2 you use | UNVERIFIED that it works with the Winlator wrapper or Turnip; try in a copy of `BNG_01` |
| Proton / Wine | GameNative `proton-wine` builds, newer than `proton-11.0-2-20260928`, with runtime ntsync selection (`/dev/ntsync` or userspace ntsync) | ntsync can cut sync overhead on many-thread games | UNVERIFIED beyond a release snippet |
| BeamNG renderer | `-gfx vk` (Vulkan 1.3; docs say 16 GB RAM / 6 GB VRAM minimum) | Drops the DXVK layer | VERIFIED launch arg. Retail support unconfirmed: the page is BeamNG.tech documentation. 11 GB shared RAM is under their minimum, so watch for blur and crashes |
| BeamNG native Linux binary | Since Jan 2026, BeamNG's Steam default on Linux is a native Vulkan binary | Could run under FEX or Box64 without Wine | No reports found. Experiment only |

## 4. Env vars

**Likely no effect on this stack** (UNVERIFIED reasoning: these only act on the OpenGL/Zink path, and D3D11 → DXVK goes straight to Vulkan): `ZINK_DESCRIPTORS`, `mesa_glthread`, `MESA_GL_VERSION_OVERRIDE`. Also `WINE_LARGE_ADDRESS_AWARE` / `PROTON_FORCE_LARGE_ADDRESS_AWARE` (64-bit executable). Dropped from `BNG_01`; add them back to one config if you want to prove it.
`WINEESYNC=1` and `WINE_FAST_YIELD=1` are already set by the app, and `WINEDEBUG=-all` is the default when Wine debug is off (VERIFIED `XServerDisplayActivity.java:1130-1133`).

**Box64** (documented defaults: https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md)
- `BOX64_DYNAREC_BIGBLOCK`: 0 small blocks (suggested for JIT-heavy programs), 3 all memory (suggested for Wine programs). **I found no source for "BIGBLOCK=1 is correct for LuaJIT"**, which is what `container-setup.md` claims. Configs 02, 03 and 01 test 0, 3 and 1.
- `BOX64_DYNAREC_SAFEFLAGS=0` is faster but riskier (the docs' Factorio profile uses it). `BOX64_DYNAREC_STRONGMEM` costs speed; raise it only to fix crashes. `BOX64_DYNAREC_ALIGNED_ATOMICS=1` is faster but gives SIGBUS on misaligned atomics (spelled `ALIGNED_ATOMICS`, not `ALIGN_ATOMICS`). `BOX64_DYNAREC_WAIT=0` may help heavily threaded programs.
- `BOX64_DYNACACHE=1` is the default from 0.4.4.
- Candidate follow-ups, one at a time on the best of 01–03: `SAFEFLAGS=0`, `CALLRET=1`, `WAIT=0`.

**Turnip / Mesa**
- `TU_DEBUG=sysmem` is Banners-Turnip's workaround for A830 glitches (VERIFIED); `noconform` is the existing default. Other flags (`nolrz`, `gmem`, UBWC) were not researched.
- Avoid flags that disable UBWC: compression saves bandwidth and memory (UNVERIFIED for Turnip).

**DXVK**
- `DXVK_CONFIG` takes `dxvk.*` options and is already wired in the app (`DXVKConfigDialog.java:304-342`). `BNG_05` sets `dxvk.numCompilerThreads=2`. Whether that option exists in the 2.6-2 gplasync build is UNVERIFIED; if DXVK ignores it, nothing breaks.
- `DXVK_ASYNC=1` / `DXVK_GPLASYNCCACHE=1` are set by the app from `async=1,asyncCache=1`, and are gplasync-fork options, not upstream DXVK 3.x ones (UNVERIFIED for 3.x).
- `DXVK_STATE_CACHE_PATH` is set by the app; keep the container so the cache persists.

**Frame pacing and heat**
- `framerate=60` in `dxwrapperConfig` (sets `DXVK_FRAME_RATE`) is the biggest heat lever (`BNG_05`).
- `maxDeviceMemory` / `videoMemorySize` do not allocate memory; they set what the game is told, which drives texture streaming. Adreno has no separate VRAM.

## 5. Corrections to `container-setup.md`

- The 8 Elite has 2 prime + 6 performance cores. The doc's "no efficiency cores" is probably right (all report the same part id), but "all 8 are equal" is not guaranteed. Pinning is not part of these configs.
- `dxvk.enableAsync` / `dxvk.gplAsyncCache` only exist in the gplasync fork.
- `MESA_SHADER_CACHE_MAX_SIZE=4G`: a 4 GB disk cache for shaders Mesa compiles is unlikely to fill; the DXVK state cache is the one that matters for BeamNG. UNVERIFIED.

**Added after the investigation:** `BNG_07_compat_nocache` (COMPATIBILITY preset, no Box64 cache), `BNG_08_perf_nocache` (PERFORMANCE, cache off) and `BNG_09_proton10_compat` (Proton 10.0-5) isolate the Box64 and Wine variables.
