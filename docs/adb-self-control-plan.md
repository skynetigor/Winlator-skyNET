# Plan: built-in ADB client for pre-launch memory cleanup

Status: plan only, nothing implemented. Chosen approach: **embedded ADB client** that pairs with the phone's own Wireless Debugging (no Shizuku, no external app).

## 1. Goal

Let Winlator skyNET run a fixed set of shell-level actions on its own device, mainly "free memory and prepare for a heavy game" before BeamNG starts.

Non-goals: a free-form shell, root-only actions (`drop_caches`, CPU governor), automatic enablement of Wireless Debugging.

## 2. Feasibility

- Android 11+ Wireless Debugging exposes `adbd` over TLS on the local Wi-Fi interface, with a one-time pairing (6-digit code, or QR) and a per-session connect port. An app on the same phone can act as the ADB client and talk to its own `adbd`.
- The project already ships `conscrypt-android` and `bcprov` (`app/build.gradle`), which TLS-based ADB clients need. Re-check version compatibility with the chosen library.
- minSdk is 28 and targetSdk is 28. Wireless Debugging only exists on 11+, so the feature must be gated by `Build.VERSION.SDK_INT >= 30` and degrade gracefully (hide the UI) below that.
- Open question to verify on the Fold8 (Android 17): whether pairing and connecting to localhost / the device's own Wi-Fi IP still works, and what Samsung's One UI does when Wi-Fi is off or changes.

## 3. Library choice (verify before committing)

- Candidate: **libadb-android** (MuntashirAkon), a pure-Java/Kotlin ADB client with wireless pairing, used by LADB-like tools. Not verified yet:
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
| `PHANTOM_PROCESS_GUARD` | `device_config put activity_manager max_phantom_processes 2147483647` and `settings put global settings_enable_monitor_phantom_procs false` | Stops Android 12+ from killing Wine's child processes. **Persists across sessions**, so make it opt-in with a clear label and a "restore defaults" action. Behaviour on Android 17 unverified. |
| `READ_MEMINFO` | `cat /proc/meminfo` | For before/after numbers. Can be done without ADB; use plain file read first. |
| `READ_THERMAL` (later) | `dumpsys thermalservice` | Optional overlay data. |

Explicitly out: `drop_caches`, governor writes, anything needing root, any command built from user text.

## 6. Settings scopes and UI

### 6.1 Scopes and precedence

Three levels, resolved most-specific first: **shortcut → container → global**. Each level stores a mode, and a shortcut or container can say "inherit".

| Scope | Storage | Where edited |
|---|---|---|
| Global | `SharedPreferences` (default prefs): `adb_prep_mode`, `adb_prep_periodic`, thresholds, package lists | Settings → "ADB self-control" |
| Container | `container.extraData`: `adbPrepMode`, `adbPrepPeriodic` | Container editor → **Advanced** tab (`ui/container/ContainerEditorV2.kt` has the "Advanced" category; the older `ContainerAdvancedPane.kt` / `ContainerAdvancedComposeDialog.kt` also need the control) |
| Shortcut | `Shortcut.putExtra(...)` (same keys; `Shortcut.getExtra(name, fallback)` already supports fallbacks to container values, see `XServerDisplayActivity.java:1145`) | Shortcut editor → **Advanced** category (`ui/shortcut/ShortcutEditorV2.kt`, `ShortcutSettingsComposeDialog.kt`) |

Values: `inherit` (shortcut/container only), `off`, `light`, `aggressive`. Periodic is a separate switch with the same inheritance. Resolver: one helper, `AdbPrepSettings.resolve(shortcut, container, prefs)`, used everywhere so the precedence lives in one place.

Container `extraData` keys outside `ContainerProfile.EXCLUDED_EXTRA_MARKERS` survive `.wcfg` export/import, so the BeamNG presets can carry the setting. The pairing itself is device-wide and is never exported.

### 6.2 Advanced-tab control (container and shortcut)

One reusable composable, `AdbSelfControlSection`, used by both editors:

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

- When a container is created (`ContainerCreateComposeFragment.kt`, and the importer path `ContainerManager.createContainerFromData`), the new container saves the chosen mode into `extraData` (`adbPrepMode`), defaulting to `inherit` so the global setting applies. The create screen shows the same switch; if the user turns it on and enters a pairing code there, pairing runs at save time and a failure shows a message without blocking container creation.
- Imported `.wcfg` containers keep their stored mode, but if the device is not paired, show "Pairing needed for memory cleanup" in the import summary instead of failing.

### 6.4 Global screen

Settings → "ADB self-control": same switch/pairing/test controls, the default mode and periodic setting, the package picker for `FORCE_STOP_PACKAGES`, the memory threshold and cooldown for periodic mode, and "Forget pairing".

## 7. Runtime integration

1. **Launch hook**: in `XServerDisplayActivity`, before the environment starts (around `setupXEnvironment`, ~line 1087), call `MemoryPrepper.runIfEnabled(resolvedSettings)` asynchronously with a timeout (≈5 s). Launch proceeds regardless of result.
2. **Periodic monitor** (see 7.1), started after launch if periodic is on.
3. **In-game panel** (optional, later): a "Free memory now" button in the existing Rendering sidebar (`SidebarCleanupView` suggests an existing cleanup area; reuse it).
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
- `CHANGE_WIFI_MULTICAST_STATE` may be needed for mDNS on some devices; verify.
- Notification permission is not required if pairing uses an in-app dialog (the user opens Developer options → Wireless debugging → Pair with code, then types it in-app). Prefer the dialog over the system-notification RemoteInput flow: with split-screen on a Fold the in-app flow is more reliable. Add the notification route only if split-screen entry proves impractical.
- No new dangerous permissions are expected.

## 9. Security

- The private ADB key is app-private, never exported, never logged. Do not include it in `.wcfg` export (the profile builder only serialises container JSON, but add a test).
- Only `AllowedAction` values can execute; the executor rejects anything else. Package names are validated and checked against `PackageManager`.
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

1. **Spike (decision gate)**: library in a scratch branch; pair, connect to self, run `id`. Verify on Android 17 / Fold8. Exit criteria: `uid=2000(shell)`.
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
- Regression: container launch with the feature off must be unchanged; `.wcfg` export/import round-trips the new `extraData` keys and never contains pairing data.

## 13. Open questions

- Library license and maintenance status (step 1 blocker).
- Does Wireless Debugging stay usable when the phone has no Wi-Fi connection? Some devices require a connected Wi-Fi network; mobile-only use may not work.
- Does One UI on Android 17 change the pairing UX or lock down self-connect?
- Does `am kill-all` on this device free a meaningful amount of memory? Measure before building the rest (can be tried manually with `adb shell am kill-all` from the PC first).
- Pairing is device-wide, so the pairing code is intentionally not stored per container or shortcut (see 6.2). Confirm this matches what you want from "save on container creation": the plan saves the switch and level, not the code.
- Two Advanced UIs exist for containers (the `ContainerEditorV2` category and the older `ContainerAdvancedPane`/dialog). Confirm during implementation which are still reachable and wire both, or remove the dead one.
