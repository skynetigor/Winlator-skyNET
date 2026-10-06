# BeamNG 0.34 — Winlator skyNET Container Setup

Target device: Samsung Galaxy Z Fold8 Ultra (SM-F976B), Snapdragon 8 Elite (8× Oryon V2), 11 GB RAM.
App package: `com.winlator.cmod.dev` (Winlator skyNET v1.1).

---

## Container profile

| Setting | Value |
|---|---|
| **Export file** | `BeemNG_0_34_SD8Elite_optimized.wcfg.json` |
| **Container name** | `BeemNG_0_34_SD8Elite_optimized` |
| **Screen size** | 1280×1024 |
| **Wine/Proton** | `proton-11.0-2-x86_64` |
| **Emulator** | Box64 0.4.2 |
| **Graphics driver** | Turnip Gen8 V36 (freedreno backend) |
| **DXVK** | 2.6-2-gplasync |
| **CPU affinity** | `0,1,2,3,4,5,6,7` (all 8 Oryon cores — SD8 Elite has no efficiency cores) |
| **GPU memory** | 4096 MB (graphicsDriverConfig + dxwrapperConfig) |
| **Audio** | ALSA |

---

## Environment variables

```
WRAPPER_MAX_IMAGE_COUNT=0
ZINK_DESCRIPTORS=lazy
MESA_SHADER_CACHE_DISABLE=false
MESA_SHADER_CACHE_MAX_SIZE=4G
MESA_DISK_CACHE_DATABASE=1
mesa_glthread=true
WINEESYNC=1
WINE_LARGE_ADDRESS_AWARE=1
PROTON_FORCE_LARGE_ADDRESS_AWARE=1
MESA_GL_VERSION_OVERRIDE=3.3
BOX64_DYNAREC_BIGBLOCK=1
BOX64_DYNAREC_FORWARD=256
BOX64_DYNACACHE=1
BOX64_DYNACACHE_LIMIT=4096
```

### Rationale

**`WRAPPER_MAX_IMAGE_COUNT=0`**
Removes the Vulkan swapchain image count cap. Winlator's wrapper layer defaults to a low cap that can cause frame-pacing issues with mailbox present mode.

**`ZINK_DESCRIPTORS=lazy`**
Defers Zink descriptor set updates until draw time. Reduces redundant descriptor flushes on Turnip, which lacks native lazy-update hardware paths.

**`MESA_SHADER_CACHE_MAX_SIZE=4G`**
BeamNG 0.34 compiles a large number of Mesa/Turnip shader variants. The default 512 MB cap fills quickly and causes cache eviction, forcing re-compilation across sessions. 4 G gives enough headroom for long-term accumulation.
Source: empirical — original container had `512MB`.

**`MESA_DISK_CACHE_DATABASE=1`**
Switches Mesa's on-disk shader cache to a single SQLite database file instead of individual files. More efficient for large caches; also avoids the directory-entry limit issue on FAT-adjacent filesystems.

**`mesa_glthread=true`**
Offloads OpenGL API calls to a background thread (Mesa's glthread). Reduces CPU stalls on the game thread. BeamNG uses D3D11 → DXVK → Vulkan, but Zink-path calls still benefit.

**`WINEESYNC=1`**
Replaces Wine's pthread-based synchronization with Linux eventfd (esync). Cuts synchronization overhead for multi-threaded games. BeamNG spawns many threads (physics at 2000 Hz per vehicle, streaming, renderer).

**`WINE_LARGE_ADDRESS_AWARE=1` / `PROTON_FORCE_LARGE_ADDRESS_AWARE=1`**
Forces the 64-bit Wine/Proton process to use the full address space rather than the legacy 2 GB user-space limit. Necessary when BeamNG's working set + DXVK + Wine overhead exceeds 2 GB.

**`MESA_GL_VERSION_OVERRIDE=3.3`**
Reports OpenGL 3.3 compatibility to the game. BeamNG checks GL version at startup; the Zink/Turnip stack advertises a version that triggers a fallback path without this override.

**`BOX64_DYNAREC_BIGBLOCK=1`**
**Critical for BeamNG.** The default Box64 PERFORMANCE preset uses `BIGBLOCK=3`, which generates large translated code blocks. LuaJIT (which BeamNG uses for physics/scripting at 2000 Hz) produces many small, hot code blocks. Large blocks cause the JIT to re-translate LuaJIT's own JIT output, creating a translation cascade. `BIGBLOCK=1` keeps blocks small enough for LuaJIT to work correctly.
Source: Box64 docs; LuaJIT + Box64 interaction notes.

**`BOX64_DYNAREC_FORWARD=256`**
Increases the forward-scan window for the Box64 dynamic recompiler. Allows better branch prediction across a 256-instruction window. Improves throughput for BeamNG's simulation loops.

**`BOX64_DYNACACHE=1` / `BOX64_DYNACACHE_LIMIT=4096`**
Enables Box64's persistent translation cache up to 4096 entries. On subsequent launches, Box64 reuses translated code blocks from disk instead of recompiling from scratch. Reduces startup JIT overhead.

---

## CPU pinning

SD8 Elite (SM8850) has **no efficiency cores** — all 8 Oryon V2 cores are performance-class. Do **not** add `BOX64_SKIPCPU` or `BOX64_MAXCPU` to restrict cores. Using all 8 is correct.

Verified via: `adb shell cat /proc/cpuinfo | grep -i "CPU part"` — all cores report `CPU part 0x002` (Oryon).

---

## DXVK configuration

File location: alongside the BeamNG executable in `Bin64/`:
```
/sdcard/Download/Games/BeemNG_0_34_Steam_Depot/Bin64/dxvk.conf
```

```ini
dxvk.enableAsync = True
dxvk.gplAsyncCache = True
dxgi.maxFrameLatency = 1
d3d9.maxFrameLatency = 1
dxgi.numBackBuffers = 2
dxvk.allowMemoryOvercommit = True
dxgi.maxDeviceMemory = 4096
dxgi.maxSharedMemory = 2048
```

**`dxvk.enableAsync = True`**
Compiles graphics pipelines asynchronously. Game can render while new shaders compile; avoids complete freezes on first encounter of a new shader state.

**`dxvk.gplAsyncCache = True`**
Enables Graphics Pipeline Library async pre-compilation cache. On the second launch, DXVK pre-compiles cached shader variants in background threads before the game requests them, reducing in-game stutter.

**`dxgi.maxFrameLatency = 1`**
Caps DXGI frame latency to 1. Reduces input lag by limiting the number of pre-queued frames.

**`dxgi.numBackBuffers = 2`**
Requests 2 back buffers. With mailbox present mode, DXVK will select an image count ≥ 3; this value is a minimum hint.

**`dxvk.allowMemoryOvercommit = True`**
Lets DXVK allocate more device memory than the reported budget. Adreno unified memory architecture shares RAM between CPU and GPU; DXVK's budget reporting is conservative on Turnip.

**`dxgi.maxDeviceMemory = 4096`**
Reports 4 GB VRAM to the game. Must match `graphicsDriverConfig.maxDeviceMemory` in the container and the container's `dxwrapperConfig.videoMemorySize`.

**`dxgi.maxSharedMemory = 2048`**
Reports 2 GB shared system memory to DXGI.

### DXVK shader cache location

DXVK stores its compiled pipeline cache inside the Wine prefix, **not** in the game's Bin64 directory:
```
C:\users\xuser\AppData\Local\dxvk\<hash>.dxvk.bin
```
On Android: `/data/user/0/com.winlator.cmod.dev/files/imagefs/home/xuser-<N>/.wine/drive_c/users/xuser/AppData/Local/dxvk/`

The cache is per-container. A fresh container has no cache — first map load will be slow due to async shader compilation. **Second load of the same map will be significantly faster.**

---

## BeamNG launch arguments

Desktop shortcut Exec line:
```
env WINEPREFIX="/home/xuser-2/.wine" wine "/storage/emulated/0/Download/Games/BeemNG_0_34_Steam_Depot/Bin64/BeamNG.drive.x64.exe" -nosteam
```

**`-nosteam`**: Disables Steam integration checks. Required when running outside Steam (depot install).

Game data path on device:
```
/sdcard/Download/Games/BeemNG_0_34_Steam_Depot/
```

---

## dxwrapperConfig string

```
version=2.6-2-gplasync,framerate=0,async=1,asyncCache=1,vkd3dVersion=None,vkd3dLevel=12_1,
ddrawrapper=none,csmt=3,gpuName=NVIDIA GeForce GTX 480,videoMemorySize=4096,
strict_shader_math=1,OffscreenRenderingMode=fbo,renderer=gl
```

The `gpuName` value is a dummy name used internally by Winlator — it does not affect actual GPU selection.

---

## Performance notes

### First-run map loading (slow objects)
On the first load of any map, DXVK compiles shaders asynchronously. Objects appear and load slowly until their shader variants are compiled. This is normal for a fresh container with no DXVK cache. Run the same map a second time — loading will be substantially faster.

### LuaJIT and Box64
BeamNG runs physics at 2000 Hz per vehicle in a LuaJIT JIT-compiled Lua VM. Box64 translates LuaJIT's x86_64 JIT output to ARM64. If `BOX64_DYNAREC_BIGBLOCK` is set to 3 (the PERFORMANCE preset default), Box64 generates translation blocks too large for LuaJIT's output pattern, causing re-translation loops. `BIGBLOCK=1` is the correct value for this workload.

### Memory
BeamNG's working set for Grid V2 with 1 vehicle is approximately 3–4 GB including DXVK buffers, Wine overhead, and BeamNG assets. The 4096 MB device/video memory configuration gives the game headroom without thrashing. Adreno 840's unified memory pool is ~11 GB shared with the OS.

---

## Key files on device

| File | Path |
|---|---|
| Container export | `/sdcard/Download/Games/Winlator_components/BeemNG_0_34_SD8Elite_optimized.wcfg.json` |
| Original container | `/sdcard/Download/Games/Winlator_components/BeemNG_0_34_Proton11x64_snap8e5.wcfg.json` |
| DXVK config | `/sdcard/Download/Games/BeemNG_0_34_Steam_Depot/Bin64/dxvk.conf` |
| BeamNG executable | `/sdcard/Download/Games/BeemNG_0_34_Steam_Depot/Bin64/BeamNG.drive.x64.exe` |
| Desktop shortcut | `/data/user/0/com.winlator.cmod.dev/files/imagefs/home/xuser-2/.wine/drive_c/users/xuser/Desktop/BeamNG drive x64.desktop` |
| Winlator logs | `/sdcard/Winlator/logs/` |

---

## Debugging / log collection

To capture BeamNG process output for future diagnostics:
1. Open Winlator skyNET → Settings
2. Enable **"Wine debug"** (writes `beamng.drive.x64_<timestamp>.txt` to `/sdcard/Winlator/logs/`)
3. Reproduce the issue, then pull the log:
   ```bash
   adb pull /sdcard/Winlator/logs/beamng.drive.x64_<timestamp>.txt
   ```

The DXVK log is written to Bin64 alongside the exe:
```
/sdcard/Download/Games/BeemNG_0_34_Steam_Depot/Bin64/BeamNG.drive.x64_d3d11.log
```

---

## Sources

- Box64 DynaRec env var reference: https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md
- LuaJIT + BIGBLOCK interaction: Box64 issue tracker; BIGBLOCK=1 recommended for LuaJIT workloads
- DXVK async/GPLAsync: https://github.com/doitsujin/dxvk and gplasync fork readme
- Mesa MESA_SHADER_CACHE_MAX_SIZE: Mesa3D docs
- Adreno 840 (SD8 Elite) core topology confirmed via `/proc/cpuinfo` on SM-F976B — all 8 cores `CPU part 0x002` (Oryon V2), no efficiency cores
- BeamNG physics architecture: BeamNG developer blog — 2000 Hz Lua physics per vehicle, LuaJIT-compiled
