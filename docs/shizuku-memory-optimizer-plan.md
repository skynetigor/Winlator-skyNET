# Plan: Shizuku-based background app restriction during container sessions

Status: plan only, nothing implemented. Written 2026-10-10.

Relation to [adb-self-control-plan.md](adb-self-control-plan.md): that plan chose an embedded ADB client and explicitly rejected Shizuku. This is the alternative: delegate privilege to Shizuku instead of embedding ADB. Pick one; do not build both. Shizuku is far less code (no pairing UI, no mDNS discovery, no TLS client) at the cost of a hard dependency on an external app.

## 1. Goal

When a container starts, restrict a user-chosen set of third-party apps so they cannot run or restart in the background. When the container stops, undo exactly what was changed. Motivation: 11 GB RAM is tight for Steam + Wine + Android UI. A manual test on SM-F976B (2026-10-10) raised MemAvailable from about 2.6 GB to 4.4 GB after `am kill-all` plus force-stopping 13 user apps.

Non-goals: touching system apps (SystemUI, launcher, keyboard, wallpaper, Google Play services), disabling or uninstalling packages, root-only tweaks, a free-form shell.

## 2. Expected benefit (measure first)

Android's low-memory killer already evicts cached apps under pressure and the device has 16 GB swap, so the real gain is mostly avoiding mid-game churn (apps relaunching, swap thrash), not a large permanent increase. Before building, repeat the manual measurement over `adb shell` during an actual BeamNG session with and without restrictions, and compare frame pacing and `MemAvailable`. If the difference is small, stop here.

## 3. Mechanism

Commands executed with shell privilege, per selected package `<pkg>`:

| Phase | Command | Effect |
|---|---|---|
| Apply | `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` | blocks background execution, jobs, alarms waking it |
| Apply | `am set-standby-bucket <pkg> restricted` | most restrictive standby bucket |
| Apply | `am force-stop <pkg>` | kills it now |
| Restore | `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND default` | back to the system default |
| Restore | `am set-standby-bucket <pkg> active` | normal bucket (the system re-buckets by usage later) |

Order on apply: restrict first, then force-stop, otherwise a broadcast can relaunch the app between the two steps. Do not use `pm disable-user`; restoring is simpler and safer without it. Do not relaunch apps on restore, they start on demand.

Record the previous app-op mode (`cmd appops get <pkg> RUN_ANY_IN_BACKGROUND`) and standby bucket (`am get-standby-bucket <pkg>`) before changing, and restore those values instead of assuming defaults. The user may already have manually restricted an app.

Limits to document: FCM push from Google Play services can still wake restricted apps in some cases, and Samsung's battery manager may override standby buckets. Treat the feature as best effort.

## 4. Architecture

New package `com.winlator.cmod.memprep`:

- `MemPrepSettings`: reads and writes SharedPreferences: enabled flag, selected package set. Also resolves the per-container override (inherit, on, off).
- `ShizukuGate`: `isAvailable()` = `Shizuku.pingBinder()` and permission granted; `requestPermission(activity)`; exposes a status enum (NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY) for the UI.
- `ShellService`: the Shizuku `UserService` (AIDL `IShellService.exec(String[] argv)` returns exit code and output). Runs in the shell-privileged process. Only a fixed allowlist of command shapes is accepted (see section 7).
- `MemPrepController`: `apply(context, packages)` and `restore(context)`. Persists a journal (package, previous app-op mode, previous bucket) to a file before each change so a crash can be undone.
- `PackagePicker`: lists launchable third-party apps, excludes system apps, the app's own package, installed keyboards, the default launcher and a hard-coded protected list.

Dependencies (`app/build.gradle`): `dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider` (current versions to be checked at implementation time). Manifest: `ShizukuProvider` with `android:authorities="${applicationId}.shizuku"`, `android:multiprocess="false"`, `android:exported="true"`, and `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`. Check that this does not conflict with the `com.winlator.cmod.dev` applicationId suffix.

## 5. Integration points

Verify each against the current code before editing.

- **Start:** `XServerDisplayActivity.onCreate` (line about 310), after the container is resolved and before the environment starts. Run `apply` on a background thread. Never block launch: if Shizuku is not READY, skip and show a toast.
- **Stop:** `XServerDisplayActivity.exit()` (line about 935), which is guarded by an `exiting` flag so it runs once, and again defensively in `onDestroy()` (line about 1002) in case `exit()` was bypassed. `restore` must be idempotent.
- **Crash and kill recovery:** on `MainActivity` start (and `Application.onCreate`), if a journal file exists and no container session is active, run `restore`. This covers the activity being killed by the system or a native crash.
- **Linux containers:** `LinuxSession` and `LinuxProgramLauncherComponent` are currently modified in the working tree. Confirm the same activity lifecycle covers Linux sessions, otherwise add the same start and stop hooks there.
- **Settings:** a global "Free memory during games" section in `SettingsFragment`: toggle, Shizuku status row with a "Grant" button, package picker, "Restore now" button. A per-container override in `ContainerDetailFragment` (inherit, on, off), stored in the container data like the other options.
- **`.wcfg` import:** carry the per-container key through import and export like other container keys; ignore it silently if Shizuku is unavailable.

## 6. Failure handling

| Situation | Behaviour |
|---|---|
| Shizuku not installed or not running | feature skipped, toast "Shizuku not running, memory prep skipped" |
| Permission denied | skipped, status row shows how to grant |
| One package command fails | log, continue with the rest, journal only successful changes |
| Shizuku dies mid-session | restore on exit fails, keep the journal, retry on next app start and when Shizuku reconnects (`Shizuku.addBinderReceivedListener`) |
| Reboot | app-ops and standby buckets are persisted by the system, so the journal restore on next launch is still needed |
| App uninstalled between apply and restore | ignore the error and drop the journal entry |

## 7. Security and safety

- Shell commands are built only from allowlisted templates with a validated package name (`^[A-Za-z0-9._]+$` and present in `PackageManager`); no string concatenation of user input into a shell line.
- The protected list is enforced inside `ShellService`, not only in the UI: system UID apps, `com.android.systemui`, `com.google.android.gms`, the launcher, the input method, the phone and emergency apps, and the app itself.
- The `UserService` runs with shell privilege, so keep its AIDL surface minimal: no generic `exec`, only `apply(pkg)`, `restore(pkg, mode, bucket)`, `getState(pkg)`.
- Document in the settings text that notifications from restricted apps pause during play.

## 8. Phases

1. **Spike (half a day):** manual measurement during BeamNG as in section 2. Add Shizuku dependency and a throwaway button that restricts and restores one package. Confirm the `UserService` works under the `.dev` applicationId on Android 17.
2. **Core:** `ShizukuGate`, `ShellService`, `MemPrepController` with journal, unit tests for journal and command building (no device needed).
3. **Hooks:** start, stop, crash recovery in the activity lifecycle, then Linux sessions.
4. **UI:** global settings, package picker, per-container override, `.wcfg` keys.
5. **Hardening:** device pass on SM-F976B: force-kill Winlator mid-game and verify recovery, reboot with a pending journal, Shizuku stopped mid-session, an app the user had pre-restricted.

## 9. Open questions

- Is the dependency on Shizuku acceptable for users (extra app, restart after each reboot via wireless debugging)? If not, revert to the embedded ADB plan.
- Should the default package set be empty (opt-in per app) or pre-filled with the heaviest third-party apps found at first run? Recommended: empty, with a "suggest heavy apps" button.
- Should the feature be gated to Android 17 and SM-F976B like the ADB plan, or available wherever Shizuku works? Recommended: available wherever Shizuku is READY, since nothing here is Android 17 specific.
- Keep the standby-bucket change at all? If `RUN_ANY_IN_BACKGROUND` alone gives the same result in the spike, drop it to reduce Samsung battery-manager interference.
