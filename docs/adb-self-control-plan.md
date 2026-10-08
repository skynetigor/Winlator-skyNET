# Plan: built-in ADB client for pre-launch memory cleanup

Status: plan only, nothing implemented. Chosen approach: **embedded ADB client** that pairs with the phone's own Wireless Debugging (no Shizuku, no external app).

**Final review (2026-10-06) in one paragraph.** Doable in principle, but not proven on the target device, and the original scope is larger than the benefit justifies. The decisive unknown is whether an app can still connect to its own Wireless Debugging on Android 17 (see 2). Several cheaper tiers (section 14) cover most of the value without embedding ADB. Do the Tier 0 spike and the manual `adb shell am kill-all` measurement before building anything in sections 4 to 9.

## 0. Scope: Android 17 only, tuned for one phone

The feature exists only on **Android 17 (API 37)**, and for now it is tuned for **Samsung Galaxy Z Fold8 Ultra (SM-F976B), One UI 9**. On every other Android version it must be invisible and inert.

**Gate (single source of truth).** One object, `AdbFeature`, with `isSupported()`:
- `Build.VERSION.SDK_INT >= 37`, and
- while developing, an optional model allowlist (initially `["SM-F976B"]`; remove it when the feature is proven on other Android 17 devices). `compileSdk` is 35, so compare against the literal `37`, not a `Build.VERSION_CODES` constant.

**"Invisible and inert" means:**
- No UI: the switch, pairing block, test button, global Settings entry and the in-game button are not composed when `isSupported()` is false.
- No behaviour: `AdbPrepSettings.resolve(...)` returns `off` regardless of stored values; the launch hook, the monitor and the allowlisted actions never run; no library class is touched (initialise the ADB library lazily, only behind the gate).
- No noise: a `.wcfg` or shortcut carrying `adbPrepMode` is imported silently on other devices (the keys are kept so the profile round-trips, but ignored, with no warning).
- The `.wcfg` presets in `docs/beemng/configs/` stay portable.

**What Android 17 only lets us simplify (no support for older Wireless Debugging behaviour):**
- Design for "ADB Wi-Fi 2.0" only: its new mDNS stack, trusted-network rule and `adb_wifi_enabled` global.
- No `SDK_INT` branches for the phantom-process commands (Android 17 uses the `settings` key; the `device_config` form is Android-12-only and is dropped).
- No fallback pairing flows for old Android versions.

**Verified on this phone (read-only checks over ADB, 2026-10-06):** `ro.build.version.sdk=37`, release 17, model SM-F976B, One UI 9. `settings get global adb_wifi_enabled` returns 1 and `adb_allowed_connection_time` is 0 (no authorization timeout). `dumpsys adb` reports Wi-Fi ADB `enabled=true`, `is_trusted_network=true` and a `tls_port` (the port changes between sessions, so the app must discover it). `settings_enable_monitor_phantom_procs` is unset and `device_config max_phantom_processes` is unset (system defaults). No adb TLS properties are readable by apps (the logcat shows "Access denied finding property service.adb.tls.port"), so port discovery has to use `NsdManager`. The app cannot read `dumpsys adb`, but `Settings.Global.adb_wifi_enabled` is app-readable (UNVERIFIED for an app at targetSdk 28 on Android 17): use it to show "Wireless debugging is off".

**Android 17 specifics worth using:**
- If the one-time `pm grant ... WRITE_SECURE_SETTINGS` is done (Tier 2 in section 14), the app may be able to set `adb_wifi_enabled=1` itself, so a switch-off (untrusted network, Wi-Fi loss) can be undone without opening Developer options. UNVERIFIED on 17: test in the spike.
- Because Wireless Debugging is turned off on untrusted networks, the app should report "network not trusted" (when `adb_wifi_enabled` flips to 0 right after a network change) instead of a generic failure.

## 1. Goal

Let Winlator skyNET run a fixed set of shell-level actions on its own device, mainly "free memory and prepare for a heavy game" before BeamNG starts.

Non-goals: a free-form shell, root-only actions (`drop_caches`, CPU governor), automatic enablement of Wireless Debugging.

## 2. Feasibility

- Android 11+ Wireless Debugging exposes `adbd` over TLS on the local Wi-Fi interface, with a one-time pairing (6-digit code, or QR) and a per-session connect port. An app on the same phone can act as the ADB client and talk to its own `adbd`.
- The project already ships `conscrypt-android` and `bcprov` (`app/build.gradle`), which TLS-based ADB clients need. Re-check version compatibility with the chosen library.
- minSdk is 28 and targetSdk is 28, but the feature is gated to Android 17 only (section 0), so none of the older-Android Wireless Debugging behaviour matters.
- **Android 17 changed Wireless Debugging** ("ADB Wi-Fi 2.0", Android Developers blog, 2026-09): a new mDNS stack, and the daemon turns Wi-Fi ADB off on untrusted networks. The published material does not say whether self-connect from an on-device app still works, whether the connect port or mDNS service names changed, or what happens when the network is not "trusted". This is the single biggest risk (VERIFIED that the change exists; UNVERIFIED for self-connect).
- Wireless Debugging officially requires being connected to Wi-Fi (Android docs). A mobile-only session cannot use the feature. The pairing dialog is invalidated if the app leaves the foreground, so pairing needs split-screen or a pop-out window (LADB documents this); the Fold's split-screen helps. The connect port is, in my experience, new on every toggle, so rediscovery is required (UNVERIFIED for 17).
- Samsung Auto Blocker blocks USB commands; its effect on Wireless Debugging in One UI 8/9 is not documented (UNVERIFIED, test with it on and off).
- Open question to verify on the Fold8 (Android 17): whether pairing and connecting to localhost / the device's own Wi-Fi IP still works, and what Samsung's One UI does when Wi-Fi is off or changes.

## 3. Library choice (verify before committing)

- Candidate: **libadb-android** (MuntashirAkon), a pure-Java/Kotlin ADB client with wireless pairing. VERIFIED from its README: version `3.1.1`, JitPack coordinates `com.github.MuntashirAkon:libadb-android:3.1.1`, license **GPL-3.0-or-later OR Apache-2.0** (choose Apache-2.0; this repo is MIT), no native components, API is a subclass of `AbsAdbConnectionManager` with `pair(host, port, code)`, `connect(host, port)`, `autoConnect()`, `openStream()`. Gaps found in review:
  - The GitHub Releases page is empty and the last-commit date was not found, so maintenance status is unknown; the README says it has never had a security audit.
  - The README recommends `conscrypt-android:2.5.3`; the app has 2.5.2. A bump is probably trivial but must be tested.
  - JitPack is **not** configured: `settings.gradle` has only `google()` and `mavenCentral()` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so add `maven { url 'https://jitpack.io' }` there (a repo declared in `app/build.gradle` would be rejected).
  - The library's own minSdk and its fit with targetSdk 28 are unverified. mDNS discovery is not in the README; the app needs its own `NsdManager` code.
  Original list of things not yet verified:
  - current version and JitPack coordinates
  - license compatibility with this repo (check `LICENSE` here and the library's)
  - arm64-only native bits (the app is `arm64-v8a` only, so fine)
- Fallback if unusable: implement the ADB wire protocol (CNXN / AUTH / OPEN) over a TLS socket. This is substantially more work and mostly redundant with the library; avoid unless needed.

Decision gate (step 0, ~half a day): add the library in a throwaway branch, pair with the phone, run `id` and confirm `uid=2000(shell)`. If this fails, stop and reconsider Shizuku.

## 4. Architecture

New package `com.winlator.cmod.adb`:

| Class | Responsibility |
|---|---|
| `AdbController` | Singleton. Owns the connection: `isPaired()`, `isConnected()`, `connect()`, `disconnect()`, `run(AllowedAction)` returning stdout/exit code. |
| `AdbPairingManager` | Pairing flow: discover the pairing service via `NsdManager` (`_adb-tls-pairing._tcp`), take the 6-digit code, call library `pair()`. Stores the key pair. |
| `AdbKeyStore` | Generates and stores the RSA/ADB key pair in app-private storage (use Android Keystore-backed encryption for the private key where possible). |
| `AllowedAction` (enum) | The only things the app may run. Each maps to a fixed command template; no user-supplied strings reach the shell. |
| `MemoryPrepper` | Orchestrates "free memory": ordered list of actions, reports before/after `MemAvailable`. |
| `AdbSettingsScreen` | Compose UI under Settings (`ui/settings`): status, Pair, Connect, Test, toggles. |

Connection details:
- Connect via `NsdManager` discovery of `_adb-tls-connect._tcp` for the current port (it changes each time Wireless Debugging is toggled).
- Reconnect lazily: on launch, if Wireless Debugging is enabled and the key is paired, connect with a short timeout (≈3 s). Never block game launch on a failure; log and continue.
- All I/O off the main thread (executor already used elsewhere in the app, or a dedicated single thread).

## 5. Allowed actions (fixed allowlist)

| Action | Command template | Notes |
|---|---|---|
| `KILL_BACKGROUND` | `am kill-all` | Kills cached/background processes. |
| `FORCE_STOP_PACKAGES` | `am force-stop <pkg>` for each user-selected package | Packages chosen from a list built via `PackageManager`; validate against `^[A-Za-z0-9._]+$` and that the package is installed. Exclude our own package and system-critical ones. |
| `TRIM_MEMORY` | `am send-trim-memory <pid> <level>` | pid from our own `ps`/`pidof`; skip if not found. |
| `PHANTOM_PROCESS_GUARD` | `settings put global settings_enable_monitor_phantom_procs false` (the only form used; the `device_config max_phantom_processes` form is Android-12-only and is dropped). Both settings are unset on this phone today (system defaults) | Stops Android 12+ from killing Wine's child processes. **Persists across sessions**, so opt-in with a restore action. **Needs no embedded ADB**: the manifest already declares `WRITE_SECURE_SETTINGS` (line 26, unused); a one-time `pm grant` lets the app write it directly (see 14). Evidence it is needed is weak: device logs from 10-06 show about 9-14 phantom processes per Wine session; measure before adding. |
| `READ_MEMINFO` | `cat /proc/meminfo` | For before/after numbers. Can be done without ADB; use plain file read first. |
| `READ_THERMAL` (later) | `dumpsys thermalservice` | Optional overlay data. |

Explicitly out: `drop_caches`, governor writes, anything needing root, any command built from user text.

## 6. Settings scopes and UI

### 6.1 Scopes and precedence

Three levels, resolved most-specific first: **shortcut → container → global**. Each level stores a mode, and a shortcut or container can say "inherit".

| Scope | Storage | Where edited |
|---|---|---|
| Global | `SharedPreferences` (default prefs): `adb_prep_mode`, `adb_prep_periodic`, thresholds, package lists | Settings → "ADB self-control" |
| Container | `container.extraData`: `adbPrepMode`, `adbPrepPeriodic` | Container editor → **Advanced** tab: `ui/container/ContainerEditorV2.kt` (the "Advanced" category list is at line 334; its renderer is the `else ->` branch of `when (category)`). This editor serves both create and edit (`ContainerCreateComposeFragment`, opened from `ContainersSettingsActivity.kt:141-142`). `ContainerAdvancedPane`/`ContainerAdvancedComposeDialog` are reachable only via `ContainerOverviewFragment`, which nothing instantiates, so they are very likely dead code and need no work |
| Shortcut | `Shortcut.putExtra(...)` (same keys; `Shortcut.getExtra(name, fallback)` already supports fallbacks to container values, see `XServerDisplayActivity.java:1145`) | Shortcut editor → **Advanced** category (`ui/shortcut/ShortcutEditorV2.kt`, `ShortcutSettingsComposeDialog.kt`) |

Values: `inherit` (shortcut/container only), `off`, `light`, `aggressive`. Periodic is a separate switch with the same inheritance. Resolver: one helper, `AdbPrepSettings.resolve(shortcut, container, prefs)`, used everywhere so the precedence lives in one place. Note `Shortcut.getExtra(name, fallback)` (`Shortcut.java:145-149`) takes a plain default and does not understand the string `inherit`, so the resolver is genuinely new work; a missing key must also mean inherit.

Container `extraData` keys outside `ContainerProfile.EXCLUDED_EXTRA_MARKERS` survive `.wcfg` export/import, so the BeamNG presets can carry the setting. The pairing itself is device-wide and is never exported.

### 6.2 Advanced-tab control (container and shortcut)

One reusable composable, `AdbSelfControlSection`, used by both editors. Every use is wrapped in `if (AdbFeature.isSupported())` (section 0), so on other devices the Advanced tab looks exactly as it does today:

1. **Switch**: "Free memory with ADB" (maps to `off` vs the chosen level; the level selector appears under it when on).
2. **Pairing block** (shown when the switch is on and the device is not paired):
   - Text field for the 6-digit pairing code.
   - Pairing port field, auto-filled from NSD discovery of `_adb-tls-pairing._tcp` when available, editable otherwise.
   - **Pair** button.
   - Help text: Developer options → Wireless debugging → Pair device with pairing code.
3. **Test connection** button: connects, runs `id`, and shows "Connected as shell (uid 2000)" or the specific failure (Wireless Debugging off, not paired, timeout). Also reports current `MemAvailable`.
4. Status line: Paired / Not paired / Connected / Wireless Debugging off.

Important design decision: **the pairing code is not saved**. It is a one-time code that expires after pairing, and the pairing key is shared by the whole app, not by a container or shortcut. Entering it in any container's Advanced tab pairs the device once; all containers, shortcuts and the global screen then show "Paired". Persisting a code per container would store something that is already invalid and would imply a per-container pairing that does not exist. What *is* saved per container/shortcut is the switch and level.

### 6.3 Container creation

- `ContainerEditorV2.kt` builds a fresh `extraData` JSONObject on create (lines ~544-562) from a hard-coded key list, and the edit path saves via `container.putExtra(...)` (~466-490). **A new key is silently dropped unless added to both.** When a container is created (`ContainerCreateComposeFragment.kt`, and the importer path `ContainerManager.createContainerFromData`), the new container saves the chosen mode into `extraData` (`adbPrepMode`), defaulting to `inherit` so the global setting applies. The create screen shows the same switch; if the user turns it on and enters a pairing code there, pairing must run as a separate job (container creation is async through `createContainerAsync`, `ContainerManager.java:118-124`, and pairing needs the foreground), and a failure shows a message without blocking creation.
- Imported `.wcfg` containers keep their stored mode, but if the device is not paired, show "Pairing needed for memory cleanup" in the import summary instead of failing.

### 6.4 Global screen

Settings → "ADB self-control". There is no existing settings screen to extend: global settings are a `SettingsModel` in `ui/settings/SettingsComposeHost.kt` (data class at line 89, toggle rows ~235-275, callbacks in `SettingsFragment.java:567`), and containers are managed by the separate `ContainersSettingsActivity`. Add a section to `SettingsModel` or a new Activity. Same switch/pairing/test controls, the default mode and periodic setting, the package picker for `FORCE_STOP_PACKAGES`, the memory threshold and cooldown for periodic mode, and "Forget pairing".

## 7. Runtime integration

1. **Launch hook**: `setupXEnvironment` (`XServerDisplayActivity.java:1087`) already runs on a background executor (call site ~680-690, `Executors.newSingleThreadExecutor()`), so a blocking call there would delay launch, not run beside it. Run `MemoryPrepper` on its own executor and wait with `Future.get(timeout ≈ 5 s)`; there is no timeout helper in the codebase. Launch proceeds regardless of result.
2. **Periodic monitor** (see 7.1), started after launch if periodic is on.
3. **In-game panel** (optional, later): a "Free memory now" button in the existing Rendering sidebar (`SidebarCleanupView` is a graphics/HUD options binder, not a cleanup area, so this needs its own placement; the closer existing memory UI is `winhandler/TaskManagerSidebar.java:274`).
4. **Result UI**: toast or sidebar line: "Freed ~X MB (MemAvailable A → B)".

### 7.1 Periodic cleanup (optional milestone, only if measurement says it is needed)

A fixed one-minute kill loop is deliberately **not** the design: killed apps and services restart, which costs CPU and heat; cached processes are free until memory is tight; and BeamNG's spike happens at map load, not steadily. Instead:

- `MemoryMonitor` runs only while the game session is active, inside the game activity (so Winlator is in the foreground whenever it acts).
- Every 10–15 s it reads `/proc/meminfo` directly (no ADB, negligible cost).
- It triggers cleanup only if `MemAvailable` < threshold (default 1.5 GB, configurable) **and** the cooldown has elapsed (default 3–5 min).
- `light` level only runs `am kill-all` / trim; `aggressive` also force-stops the chosen packages.
- Stops with the session. Logs each trigger (before/after MemAvailable) so the benefit can be judged.
- If ADB is not connected when a trigger fires, skip silently and retry on the next cycle (no reconnect storm: back off to once per minute).

## 8. Permissions and manifest

- `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, and `INTERNET` (check what is already declared).
- Declared today: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `POST_NOTIFICATIONS`, `WRITE_SECURE_SETTINGS` (unused). **Missing: `CHANGE_WIFI_MULTICAST_STATE`**, likely needed for mDNS (add it). `NEARBY_WIFI_DEVICES` only applies at targetSdk 33+, so not needed at 28. The XR activity runs in a separate `:vr_process` and would need its own controller instance.
- Notification permission is not required if pairing uses an in-app dialog (the user opens Developer options → Wireless debugging → Pair with code, then types it in-app). Prefer the dialog over the system-notification RemoteInput flow: with split-screen on a Fold the in-app flow is more reliable. Add the notification route only if split-screen entry proves impractical.
- No new dangerous permissions are expected.

## 9. Security

- The private ADB key is app-private, never exported, never logged. Do not include it in `.wcfg` export (the profile builder only serialises container JSON, but add a test).
- Only `AllowedAction` values can execute; the executor rejects anything else. Package names are validated and checked against `PackageManager`. The app's own package is `com.winlator.cmod.dev` (applicationId), not `com.winlator.cmod`; exclude via `getPackageName()`.
- Show a one-time consent dialog explaining that the app will control its own device through ADB and which commands it can run.
- Revoke path: "Forget pairing" deletes the key and the stored host; the user can also revoke in Developer options → Revoke USB debugging authorizations.
- Log every executed action (action name only) to the existing logs directory.

## 10. Failure modes to handle

- Wireless Debugging off, no Wi-Fi, or Wi-Fi switched networks: show "not connected", skip silently at launch.
- Port changed since the last session: re-discover through NSD.
- Pairing code rejected or timed out: clear error, retry.
- `am kill-all` also kills the games' launcher or music app the user wants: provide an exclusion list; default light mode never force-stops anything.
- Samsung background-limit features may override results; treat results as best effort.

## 11. Milestones

0a. **Gate first (tiny, ships safely)**: add `AdbFeature` and make every later piece depend on it, with a test that it returns false for SDK < 37 and for a non-allowlisted model.
0. **Measure first (no code, ~30 min)**: from the PC, with a heavy BeamNG map loading, run `adb shell am kill-all` before launch and compare `MemAvailable`, load time and lmkd kills against a run without it. If there is no clear gain, stop; the rest of this plan has no purpose.
1. **Spike (decision gate)**: library in a scratch branch (add JitPack, bump Conscrypt); pair, connect to self, run `id`. Verify on this Fold8 (Android 17, One UI 9) with Wi-Fi on a trusted network (`is_trusted_network=true` today), then toggle Wireless Debugging, switch networks, and test Auto Blocker on and off. Exit criteria: `uid=2000(shell)` and a reconnect after each of those events.
2. **Core**: `AdbKeyStore`, `AdbPairingManager`, `AdbController` with reconnect and timeouts; unit tests for command templating and package-name validation.
3. **Shared UI + settings model**: `AdbPrepSettings.resolve(...)`, `AdbSelfControlSection` (switch, pairing code/port, Pair, Test connection, status), consent dialog, global Settings screen with "Forget pairing".
3b. **Per-scope wiring**: container Advanced tab (both `ContainerEditorV2.kt` and the older Advanced pane/dialog), shortcut Advanced category, container creation (`ContainerCreateComposeFragment.kt`, importer summary), `.wcfg` round trip.
4. **Actions**: `KILL_BACKGROUND`, `READ_MEMINFO`, `TRIM_MEMORY`, then package list + `FORCE_STOP_PACKAGES`, then `PHANTOM_PROCESS_GUARD` with restore.
5. **Launch integration**: resolved mode (shortcut → container → global), async with timeout, before/after memory report.
5b. **Periodic monitor** (optional): only after step 7 shows memory pressure during play is real.
6. **Presets**: set `adbPrepMode=light` in the BeamNG configs (`docs/beemng/configs/`) once it exists.
7. **Measure**: with and without prep (and, if built, with periodic on), same BeamNG map: MemAvailable at load, load time, whether heavy maps stop being killed. Keep the feature only if the numbers justify it.

## 12. Testing

- Unit: allowlist enforcement, package validation, command building, state machine for connect/pair.
- Manual on device: pair → kill background → check `MemAvailable`; toggle Wireless Debugging off mid-session; reboot (Wireless Debugging state, pairing persistence); change Wi-Fi network; deny consent.
- Unit: precedence resolver (shortcut over container over global, `inherit` handling); the monitor's threshold and cooldown logic with a fake clock and fake meminfo.
- Manual: pair from a container's Advanced tab and confirm a second container and the global screen show "Paired"; Test connection with Wireless Debugging off; create a container with the switch on and off and confirm the mode persists; shortcut override beats container.
- Gate: on an Android 16 device or emulator (or with the gate forced off), the Advanced tab, Settings and launch behave exactly as before and no ADB class is loaded.
- Regression: container launch with the feature off must be unchanged; `.wcfg` export/import round-trips the new `extraData` keys and never contains pairing data.

## 13. Open questions

- Library license and maintenance status (step 1 blocker).
- Does Wireless Debugging stay usable when the phone has no Wi-Fi connection? Some devices require a connected Wi-Fi network; mobile-only use may not work.
- Does One UI on Android 17 change the pairing UX or lock down self-connect?
- Does `am kill-all` on this device free a meaningful amount of memory? Measure before building the rest (can be tried manually with `adb shell am kill-all` from the PC first).
- Pairing is device-wide, so the pairing code is intentionally not stored per container or shortcut (see 6.2). Confirm this matches what you want from "save on container creation": the plan saves the switch and level, not the code.
- Two Advanced UIs exist for containers (the `ContainerEditorV2` category and the older `ContainerAdvancedPane`/dialog). Confirm during implementation which are still reachable and wire both, or remove the dead one.

## 14. Review findings: gaps and better options

**Verdict.** Doable in principle (precedents: LADB, Shizuku's wireless start), not proven on Android 17, and the measurable benefit is unproven. Google states third-party apps cannot improve memory behaviour, lmkd already evicts cached processes, and killed apps restart and cost CPU. The one plausible gain is a one-time clean before a RAM-heavy session. Treat the ADB client as the last tier, not the first.

**Cheaper tiers (do these first, none needs embedded ADB)**
- **Tier 0, no permissions.** Read `/proc/meminfo` for `MemAvailable` (code exists in `widget/WinlatorHUD.java:582`, `widget/FrameRating.java:73`). Show it in the launch overlay and sidebar. This alone makes every claim in this plan measurable.
- **Tier 1, normal permission.** `ActivityManager.killBackgroundProcesses(pkg)` with the `KILL_BACKGROUND_PROCESSES` permission, a normal (install-time) permission, kills background processes of chosen packages. This is my own analysis from the Android API, not verified on Android 17. It covers the "free memory before launch" case without ADB; the periodic monitor (7.1) also works on it.
- **Tier 2, one-time `pm grant` from the PC.** The manifest already declares `WRITE_SECURE_SETTINGS`. `adb shell pm grant com.winlator.cmod.dev android.permission.WRITE_SECURE_SETTINGS` lets the app write the phantom-process setting itself. One command, no pairing UI.
- **Tier 3, the embedded ADB client of sections 4 to 9.** Only for what the tiers above cannot do: `am force-stop` on arbitrary packages, `am kill-all`, and shell-only reads.

**Gaps fixed above or still open**
- Wrong claim corrected: `SidebarCleanupView` is not a cleanup area (7).
- Dead code removed from scope: the older Advanced pane/dialog (6.1).
- Missing items added: JitPack repo, Conscrypt bump, `CHANGE_WIFI_MULTICAST_STATE`, `.dev` applicationId, create-path JSON key, launch-hook timeout.
- Still open: library maintenance status, behaviour on Android 17, Samsung Auto Blocker, whether the pairing flow can be made less fragile than "stay in split-screen". The notification reply-input route (what Shizuku and LADB use) avoids leaving the foreground app and should be tested in the spike before choosing the in-app dialog.
- Still open: the phantom guard may be unnecessary on this device (about 9-14 phantom processes per Wine session in the 10-06 logs). Measure before building it.
- Value check: the 10-06 logs show Android killed Winlator itself while foreground (adj 0) under memory pressure. `kill-all` does not protect against that; lowering the game's memory use (texture quality, reported device memory) does. Prioritise those.

**Better by design**
- Make the monitor (7.1) and the pre-launch step share one `MemoryPrepper` that works at any tier, falling back down the tiers when ADB is unavailable, so the feature degrades instead of failing.
- Show a before/after `MemAvailable` figure every time, so users can see whether it helped.
- Default everything to off; ship Tier 0 and Tier 1 first.
