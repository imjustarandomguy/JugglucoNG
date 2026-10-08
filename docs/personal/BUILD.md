# Personal fork: build and maintenance guide

This guide is for the `personal` branch of
[imjustarandomguy/JugglucoNG](https://github.com/imjustarandomguy/JugglucoNG), a
fork of [ctqvva/JugglucoNG](https://github.com/ctqvva/JugglucoNG). It is written
so that an AI coding agent (or a person) can set up a fresh Windows machine,
build signed phone and watch APKs, and keep the fork current. Follow it in
order. Where a step needs the owner's decision or secrets, stop and ask.

## Branch model

| Branch | Purpose |
|---|---|
| `main` | Mirror of upstream `ctqvva/JugglucoNG` `main`. Never commit here. |
| `fix/*`, `feat/*` | One topic each, branched from `main` (or stacked on another topic, as noted). Each is a ready pull request for upstream. A topic merges its original one-change branches, which are then deleted; their commits stay in the topic. |
| `personal` | `main` + a merge of every `fix/*`/`feat/*` branch still useful, + this guide. **APKs are built from here.** |

Current personal branches (keep this list up to date):

| Branch | What it does | Upstream status |
|---|---|---|
| `fix/dexcom-g7-link` | Phone and G7 link. Members: `feat/dex-autoconnect`, `fix/dex-rearm-after-session`, `fix/dex-battery-hygiene`, `fix/dex-gatt-robustness`. A bonded G7 reconnects with autoConnect instead of direct-connect timeouts, armed 5 s after a session (own short wake lock) so it does not re-link while the sensor still advertises; watch loss alarm not an alarm clock (Doze), timed G7 wake lock, bond receiver registered once, Wi-Fi request only when Wi-Fi is on; G7 fixes from Juggluco (stale GATT callbacks checked under the callback's lock, `bonded()` null guard, scan start limit). Verified on the S26. | Not submitted |
| `feat/dexcom-g7-watch` | On top of `fix/dexcom-g7-link`. Phone and watch both read one G7. Members: `fix/dexcom-stream-slots`, `feat/wear-sync-fill-gaps`, `fix/wear-sensor-alias`, `feat/wear-dexcom-record`, `feat/wear-dexcom-follow-sensor`, `feat/dexcom-session-ownership`, `feat/wear-sync-alongside`, `feat/wear-dexcom-watch-slot`, `fix/wear-sync-request-units`. Dexcom records keep 5-minute slots and a fixed start; the watch keeps one record per G7 (short alias vs full name) and finds the phone's calibration for it; the sensor handoff carries a G7 (code, Bluetooth name, start; not the key) and the phone hands each new G7 to the watch once; `SuperGattCallback` hooks (`holdsSensor`, `supportsWatchClaim`, `readsAlongside`, `stopTransport`) let both hold the G7 between sessions, neither standing down, the watch dialing only with "Direct sensor on watch", and stopping Bluetooth no longer unpairs it; the watch pairs on the smartwatch channel (auth byte 3), else the receiver's (1), and stops at `auth != 1`; no routine sync push for a sensor both read, and a device that misses a reading asks from its loss alarm and takes only the slots it lacks (`Natives.streamSlotsFilled`); `requestSync` read milliseconds as seconds. Verified on both devices (both reading; phone-away and watch-away). The new-sensor handoff is not yet tested at a sensor change. | Not submitted |
| `feat/floating-glucose` | Floating glucose. Members: `fix/floating-shared-notification`, `fix/floating-keep-below-status-bar`, `feat/floating-details-popup`, `feat/floating-range-colors`, `feat/floating-mirror`, `fix/floating-trend-arrow`. Uses Notify's glucose channel and id without renaming the channel, detached when it stops; the pill stays on screen and below the status bar strip; "Details on tap" card (time/age, Δ, chart, IOB/COB); "Over the status bar" as an accessibility overlay (`FloatingAccessibilityService`, disabled in the manifest until the option is on), kept on the lock screen, removed while the screen is off and re-added with a fresh value; range colours; "Mirror layout"; trend arrow from the same points as the dashboard; next-reading indicator with a temporary style picker (bar, coloured bar, outline, arrow ring; remove the picker once one is chosen), on the long side in the landscape island. | Not submitted |
| `fix/wear-app` | Watch app fixes. Members: `fix/wear-swipe-back`, `fix/wear-toggle-refresh`, `fix/wear-main-opens-at-top`, `fix/wear-complication-freshness`, `fix/wear-notification-freshness`, `fix/wear-hide-disabled-calibration`. Wear OS 6 swipe-back; Exchange/Alerts/Settings switches redraw when the phone answers; opens on the graph; complications and the ongoing notification no longer run a reading behind and show "No value" once stale; calibration switched off on the phone hides calibration on the watch; every watch trend arrow (complications, main screen, alarm screen) uses the phone dashboard's rate, and complications refresh after a G7 backfill. On the watch the live reading was counted beside its stored row (ms vs whole seconds): `augmentHistory` now merges a live reading within 30 s of its row, fixing a flat arrow and a too-high alert rate on the watch (the phone is unchanged). | Not submitted. Issue ctqvva/JugglucoNG#262 |
| `feat/glucose-display` | Range colours and status display. Members: `fix/glucose-range-colors`, `fix/wear-glucose-ranges`, `feat/notification-colored-status-icon`, `fix/notification-searching-status`, `fix/dashboard-add-menu-scrim`. Range colours follow the configured bands; the watch uses the phone's ranges and colours; optional range-coloured status bar value; no "Searching for sensors" between G7 sessions while readings are fresh; dim scrim behind the dashboard + menu. | Not submitted |
| `fix/nightscout` | All Nightscout fixes on one branch: no silent switch to API v3 on a 404; treatment backoff on the boot clock, reset by a network change or "Send now"; own v1 treatments not received back as duplicates after a switch to v3; optional "Upload only on Wi-Fi" (default network's transport, no location); deleting a treatment in the journal deletes it on Nightscout when "Send amounts" is on (offline failures never drop it, works under v1 and v3); a refused receive no longer holds back sends; switching Follow off ends the follower's sensor record (no leftover card). Not yet device-tested. | Not submitted |
| `fix/health-connect` | Activity import re-runs on app foreground (at most every 15 min) and after the permission grant, reads every page, no step/exercise double count; backfilled and gap-filled readings reach Health Connect (cursor rewind); each feature asks only for its own permission, never on every reading; stable `clientRecordId` so re-sends replace instead of duplicating. Not yet device-tested. | Not submitted |
| `feat/persistent-low-alarm` | "Persistent low" alert: rings when readings stay below a threshold for a duration, rides out compression dips (resets only above threshold + margin, held by Very low), optional "hold while rising". The episode start is taken from the stored readings (same start on phone and watch, after a restart or a settings change). | Not submitted |
| `fix/wear-alarm-settings-sync` | The watch takes every alert's settings and the shared alert settings from the phone (`c:`/`g:` lines on WearToggleSync), so watch alarms match the phone's. | Not submitted |
| `feat/alarm-routing` | On top of `fix/wear-alarm-settings-sync`. "Where alarms ring" (both / watch when connected / phone only), "On the watch" alarm style, shared snooze/dismiss/quiet window (`/sync2/silence`), test button follows the routing, watch alarm screen via a full-screen notification, Persistent high counted between readings and started from the stored readings. | Not submitted |
| `feat/alarm-history` | 30-day alarm history and an active-snooze card on the alarm settings page, the watch's entries included (`/sync2/alarmhistory`). | Not submitted |
| `feat/quick-treatment-entry` | Quick treatment entry: one sheet with type tabs, per-insulin step/default dose/reminder times (Room v33), recent chips, last-dose/last-food line on new entries only, undo bar, basal reminder ("Tresiba due at 22:00", last dose named), quick tiles, app shortcuts, notification and pill "+ Insulin/+ Food" (switch "Quick log buttons"). | Not submitted |
| `fix/dashboard-chart-bounds` | Dashboard chart: right edge at most now + 10 min (or the prediction horizon while a prediction shows), left edge at the oldest reading; no y-axis drag (a vertical swipe scrolls the page, also from the line and the tooltip cards; the scrub starts sideways or after a ~200 ms hold); the y axis is the Chart range setting, widened to fit what is in view and back (animated); drawing clipped to the plot; the handle above the range picker is a drag target and the chosen height is remembered. | Not submitted |
| `feat/readings-as-dots` | Display → "Readings shown as" line / dots / line and dots, for the app's charts, the notification/AOD/widget/pill card chart and the watch chart + chart complication (mirrored to the watch through `SettingsRegistry`). | Not submitted |
| `feat/alarm-settings-save` | The alarm settings page edits a draft: a save bar ("N unsaved changes", Discard/Save), "Changed" markers, a leave prompt (back, tabs, notification links), the draft kept on disk until saved; nothing reaches the alarms or the watch before Save. Declares its own `GlobalAlertSettings`, which collides with `feat/alarm-routing`'s: merged into `personal` with personal's kept and the routing/watch-style settings wired through the draft (`57cd179d0`, personal-only). | Not submitted |
| `feat/samsung-now-bar` | Optional "Live notification": a second, silent promoted notification (Android 16+ Live Update, MetricStyle on 17, Samsung `ongoingActivityNoti` extras) so the value shows in the Now Bar, AOD and status bar chip. Optional "Show as gauge": ProgressStyle over the chart range in range colours with the trend arrow as tracker (Samsung draws it only in the expanded live notification). | Not submitted |
| `feat/widget-rework` | On top of `feat/glucose-display`. Both widgets ("Glucose", "Glucose chart") as plain RemoteViews (Glance removed): one render per reading and widget, nothing while the screen is off (drawn at screen on), stale look from the loss alarm; freely resizable with small/wide/tall layouts; per-widget settings with a live preview (long-press → Settings, and Settings → Widgets). | Not submitted |

Dropped from `personal` on 2026-10-06 (branches deleted; their commits stay in `personal`'s history and can be brought back): `feat/xdrip-interapp-broadcast` (tip `933de71bc`, removed by `d95fd5f99`; the owner no longer uses xDrip+), `feat/wear-colored-complication` (tip `f4793672b`, reverted; maybe later), `feat/floating-hide-glucose-notification` (tip `41fd10b67`, removed by `7360a3b62`).

Open work and investigation notes: [NEXT.md](NEXT.md). Battery runs (method and
results): [BATTERY.md](BATTERY.md).

### Sending branches upstream

Each `fix/*`/`feat/*` branch is one change against `main`, reviewed for
production (comments, texts, translations, performance, portability) on
2026-10-06. To send one as a pull request to `ctqvva/JugglucoNG`:

1. Update `main` from upstream, then rebase the branch onto it (upstream moves).
2. Squash it to one commit if it has review follow-ups, e.g. on a fresh branch:
   `git checkout -b <name>-pr main && git merge --squash <branch> && git commit`.
3. Build and run the tests for that branch alone, then push it to `origin` and
   open the PR from the fork.

Dependencies (send or merge the first before the second):
- `fix/floating-keep-below-status-bar` → `feat/floating-details-popup`
- `fix/wear-sensor-alias` → `feat/wear-dexcom-record`
- `feat/wear-dexcom-record` → `feat/wear-dexcom-follow-sensor`
- `fix/dexcom-stream-slots` → `feat/wear-sync-fill-gaps`
- `feat/dexcom-session-ownership` → `feat/wear-sync-alongside`
- `feat/dex-autoconnect` → `fix/dex-rearm-after-session`
- The G7 on the watch needs `fix/dexcom-stream-slots`, `feat/wear-dexcom-record`,
  `feat/dexcom-session-ownership`, `feat/wear-dexcom-watch-slot` and
  `feat/wear-sync-fill-gaps` together; each compiles on its own.

Branches that touch the same files and will conflict with each other once one
is merged upstream: the floating ones (overlay, service, settings screen), and
every branch that adds strings (they all append to the end of the 15
`strings.xml` files). Rebase the later ones after each merge.

`feat/dex-*` and `fix/dex-*` were rewritten (amended) on 2026-10-06: pushing
them to `origin` needs `--force-with-lease`.

Shelved (on `origin`, **not** in `personal`): `feat/wear-colored-complication`, a
range-coloured "Value + arrow" complication. On the owner's Google "Active"
watch face the provider's ring colour and colour image are ignored, so it
looked identical to the plain one. It was merged and then reverted in
`personal` (commits `ed6947dba`, `205ee4179`); to bring it back, revert those
reverts rather than merging the branch again.

Dropped (on `origin`, **not** in `personal`): `feat/floating-hide-glucose-notification`,
"Hide the glucose notification". Android still showed the foreground
notification, empty, in the status bar and shade. Blocking the
"glucoseNotification" channel in Android's settings hides it properly. Reverted
in `personal` (commit `7360a3b62`).

When a branch is merged upstream, drop it from `personal` (see "Updating").

## 1. Clone and configure git (per-repo identity)

The owner's global git identity is a work account. This repo must use the
personal GitHub account `imjustarandomguy` instead, for both commits and pushes.

```bash
git clone https://imjustarandomguy@github.com/imjustarandomguy/JugglucoNG.git
cd JugglucoNG
git remote add upstream https://github.com/ctqvva/JugglucoNG.git
git remote set-url --push upstream DISABLED
git config user.email 22948718+imjustarandomguy@users.noreply.github.com
git fetch upstream
git switch personal
git submodule update --init
```

- The username in the `origin` URL makes Git Credential Manager keep a separate
  sign-in for the personal account. On the first push a browser opens: sign in
  as **imjustarandomguy**, not the work account. Never handle the owner's
  GitHub credentials yourself.
- `user.name` comes from the global config; only the email is overridden.
- `libjuice` is a submodule; the native build does not configure without it.

### Windows: symlinks

The repo tracks four symlinks (`Common/src/wearSi/...` and `Common/src/smallSi`).
Unless Windows Developer Mode is on (corporate machines often block it), git
checks them out as small text files holding the link path, and the **wear**
build fails: `SiGattCallback.java:1: error: illegal '.'` from javac, and
`mergeWearReleaseJniLibFolders: Cannot invoke "java.io.File.equals(Object)"
because "current" is null` from AGP. Fix:

```bash
bash scripts/personal/materialize-symlinks.sh
```

It replaces each placeholder with a copy of its target (or removes it when the
target does not exist, as with `wearSi/jniLibs`) and marks the path
skip-worktree so `git status` stays clean and nothing is committed. Run it again
after any checkout, reset, rebase or pull. If git refuses an operation because
of those paths, run it with `--restore` first, then again without.

## 2. Toolchain

Read the versions from `Common/build.gradle` rather than trusting this file;
they change upstream:

| What | Where in `Common/build.gradle` | Value at time of writing |
|---|---|---|
| NDK | `ndkver` | `29.0.14206865` |
| CMake | `CMAKEVERSION` | `4.1.2` |
| compileSdk | `compileSdk` | `37` (package `platforms/android-37.0`) |
| targetSdk | `TARGETSDK` | `36` |

Steps:

1. **Android Studio** provides the SDK location (`%LOCALAPPDATA%\Android\Sdk`)
   and a bundled Java runtime (`C:\Program Files\Android\Android Studio\jbr`).
   If Android Studio is not installed, ask the owner before installing it.
2. **Java.** Upstream CI uses Temurin JDK 21. See "Build log" below for whether
   the Android Studio JBR worked on this machine.
3. **SDK command-line tools.** If `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`
   is missing, download the newest `commandlinetools-win-<build>_latest.zip`
   listed in `https://dl.google.com/android/repository/repository2-3.xml`
   (pick the highest build number numerically, and check the SHA-1 against the
   `<checksum>` in that XML). Extract with Windows' own tar, which copes with
   long paths where `Expand-Archive` fails:
   ```bash
   /c/Windows/System32/tar.exe -xf commandlinetools.zip -C "$LOCALAPPDATA/Android/Sdk/cmdline-tools/_x"
   mv "$LOCALAPPDATA/Android/Sdk/cmdline-tools/_x/cmdline-tools" "$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest"
   ```
4. **SDK packages.** Since cmdline-tools 23, `sdkmanager` is a deprecated
   shim; use the `android` CLI. Package names use `/`, not `;` (a `;` is also
   split apart by Windows batch files). `--no-metrics` opts out of usage
   reporting.
   ```bash
   export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
   SDK="$LOCALAPPDATA/Android/Sdk"
   "$SDK/cmdline-tools/latest/bin/android.exe" --no-metrics --sdk="$(cygpath -w "$SDK")" \
     sdk install ndk/29.0.14206865 cmake/4.1.2 platforms/android-37.0 platforms/android-36
   ```
   Do not auto-accept SDK licenses on the owner's behalf. If a license prompt
   appears that `%LOCALAPPDATA%\Android\Sdk\licenses` does not already cover,
   stop and ask. (Upstream's `scripts/dist/install-sdk.sh` pipes `y` into every
   license prompt; do not use it on the owner's machine.) The first Gradle
   build also installs Build-Tools 37 by itself under the same license.
5. **`local.properties`** (git-ignored) in the repo root:
   ```
   sdk.dir=C:/Users/<user>/AppData/Local/Android/Sdk
   ```

## 3. Signing

Every APK must be signed, and the phone and watch apps must share **one key**
(the Wearable Data Layer only connects apps with the same package name and
signature). An installed app can only be updated by an APK signed with the
same key.

- The owner creates the keystore themselves, so the password never passes
  through an agent:
  ```bash
  "C:/Program Files/Android/Android Studio/jbr/bin/keytool.exe" -genkeypair -v \
    -keystore C:/Users/<user>/.android/juggluco-ng-personal.jks -alias personal \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Frederic Morin"
  ```
- The build reads four Gradle properties. Put them in the **user-level**
  `%USERPROFILE%\.gradle\gradle.properties`, never in the repo:
  ```
  thekeyfile=C:/Users/<user>/.android/juggluco-ng-personal.jks
  thepassword=...
  thekeyalias=personal
  thekeypassword=...
  ```
- **Trap:** if these properties are missing, `Common/build.gradle` silently
  falls back to `Common/everyone.keystore`, which is public. Always verify the
  signer before installing (step 5).
- On a new machine, copy the existing `.jks` from the owner's backup. Do not
  create a new key, or the installed apps cannot be updated.

## 4. Build

From the repo root, in Git Bash:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :Common:assembleMobileRelease -PjugglucoAbi=arm64-v8a
./gradlew :Common:assembleWearRelease
./gradlew :Common:testMobileDebugUnitTest :Common:testWearDebugUnitTest
```

- `-PjugglucoAbi=arm64-v8a` builds one native ABI, which is much faster. Use it
  for the phone. The owner's Pixel Watch 3 reports `armeabi-v7a,armeabi` only
  (32-bit userspace), so `-PjugglucoAbi=armeabi-v7a` is enough for the watch.
  For any other watch, check first:
  `adb -s <watch> shell getprop ro.product.cpu.abilist`.
- Run the phone and watch builds as **separate** Gradle commands. The
  `-PjugglucoAbi` property applies to every task in the invocation, so
  `assembleMobileRelease assembleWearRelease -PjugglucoAbi=arm64-v8a` produces
  a watch APK without 32-bit libraries, which the Pixel Watch 3 rejects with
  `INSTALL_FAILED_NO_MATCHING_ABIS` (2026-10-06).
- Release builds run R8. A missing keep rule only fails in release, so always
  test the release APKs, not debug ones.
- Debug builds install as `tk.glucodata.ng.debug` beside the release app, with
  logging on. Handy for testing; do not use them day to day (battery).
- Output APKs:
  - phone: `Common/build/outputs/apk/mobile/release/JugglucoNG-<version>.apk`
  - watch: `Common/build/outputs/apk/wear/release/JugglucoNG-<version>-wear.apk`
- The owner's devices: phone Samsung Galaxy S26 (64-bit only, One UI 9), watch
  Pixel Watch 3. After a fresh install on the phone, set the app's battery use
  to Unrestricted and keep it off Samsung's "Sleeping apps" lists; a reinstall
  resets those settings.

## 5. Verify and install

Check the signer before installing anything:

```bash
"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs <apk>
```

The certificate must be the owner's: DN `CN=Frederic Morin`, SHA-256
`2e8c026d9628d12b985ceacf8067a7d2581e7ea393aff156334ab369b41c0092`. Anything
else (in particular the public `everyone.keystore`) means the signing
properties were not picked up; do not install. `apksigner.bat` needs `java` on
`PATH`, e.g. `export PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH"`.

- **First install** over an official JugglucoNG (signed by the maintainer)
  needs the official app uninstalled first, which deletes its data. Ask the
  owner to export their data first.
- Phone: USB or wireless debugging, then `adb install -r <phone apk>`.
- Watch: on the watch, Settings → Developer options → Wireless debugging, then
  `adb pair <ip:port>` with the code shown and `adb connect <ip:port>`, then
  `adb -s <watch> install -r <wear apk>`.
- Install phone and watch from the same build, so they share a protocol
  version.

## 6. Updating from upstream

```bash
bash scripts/personal/materialize-symlinks.sh --restore   # Windows only, see section 1
git fetch upstream
git switch main && git merge --ff-only upstream/main && git push origin main
# For each fix/feat branch still not merged upstream:
git switch fix/xyz && git rebase main && git push --force-with-lease origin fix/xyz
# Rebuild personal from scratch rather than merging main into it repeatedly:
git switch personal
git reset --hard main
git merge --no-ff fix/xyz   # repeat for each branch in the table above
git checkout <previous personal commit> -- docs/personal scripts/personal CLAUDE.md
git commit -m "Personal: build guide"
git push --force-with-lease origin personal
bash scripts/personal/materialize-symlinks.sh            # Windows only
```

- Before `reset --hard`, note the current `personal` commit (`git rev-parse
  personal`) so the guide, scripts and `CLAUDE.md` can be restored from it.
- Upstream moves fast (hundreds of commits a month). Rebase often; expect
  conflicts in files upstream is actively reworking.
- If a branch was merged upstream, delete it locally and on `origin`, and
  remove it from the table above.
- Pull requests go from a `fix/*` branch on `origin` to `ctqvva/JugglucoNG`
  `main`. New user-facing strings must be added to every `values-*` locale.

## Build log

Facts recorded from real builds on the owner's machines. Add to this; do not
delete entries that are still true.

- 2026-10-06, Windows 11 work laptop (no Developer Mode, no admin), first setup:
  - Java: the Android Studio JBR (JDK 25.0.3) runs Gradle 9.7.1, AGP, Kotlin,
    R8 and the unit tests fine. JDK 21 was not needed.
  - cmdline-tools build 16111833 (v23): `sdkmanager` is a shim for the
    `android` CLI; installed with `android sdk install` as in section 2.
  - The symlink workaround (section 1) was required for the wear build.
  - Times: first phone release build (arm64, cold caches) 8m44s; re-sign only
    2m; wear release 2m31s; `testWearDebugUnitTest` for one class 51s.
  - Phone APK ~25.6 MB, arm64-v8a only. Wear APK ~15.2 MB, arm64-v8a +
    armeabi-v7a.
  - Devices: Galaxy S26 (SM-S942W) and Pixel Watch 3, both Android 17
    (API 37). Wireless adb lists each device twice (IP:port and an
    `adb-…._adb-tls-connect._tcp` name); use the IP:port serial with `-s`.
  - Switched from the official alpha by uninstalling it on both devices, then
    `adb install`. Signature check on device: both packages report the same
    signature hash, which the Wearable Data Layer requires.
