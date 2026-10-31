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
| `fix/dexcom-g7-link` | From `main`, which has upstream #595 since 2026-10-12 (was stacked on its PR head 8ba5f2fd9; old tips in `backup/2026-10-11/` and `backup/2026-10-12/`). Only what #595 does not cover: close the GATT of a stopped/released callback (the "two GATT clients" leak), scan starts postponed past Android's 5-in-30 s limit, bond receiver registered once, Wi-Fi requested once and only with the setting on, watch loss alarm not an alarm clock, one timed wake lock per session; and two commits to raise in #595's thread: a short post-session reconnect delay held by a timed wake lock instead of an alarm clock, and no next-slot alarm clock on the watch for a bonded G7 (background connect after the quiet period); plus a re-ask of the G7 when its reply is the previous reading; and (2026-10-11, found on the devices) background connect only for a G7 bonded here (#595 background-connected an unpaired G7 forever; an unpaired one now goes through a scan and normal pairing), and a G7 callback keeps the bond while another callback still dials it; after the review: a session under way is spared by the loss alarm, the bond is used again once back, an unpaired G7 is dialled only with a scanned device, and a hand-back resumes every callback that stood down. Our autoConnect gate, 5 s re-arm and stale-GATT/`bonded()` guards were dropped as superseded by #595. Not device-tested yet. | Residual of #595; send after it merges |
| `feat/dexcom-g7-watch` | On top of `fix/dexcom-g7-link`. Phone and watch both read one G7. Members: `fix/dexcom-stream-slots`, `feat/wear-sync-fill-gaps`, `fix/wear-sensor-alias`, `feat/wear-dexcom-record`, `feat/wear-dexcom-follow-sensor`, `feat/dexcom-session-ownership`, `feat/wear-sync-alongside`, `feat/wear-dexcom-watch-slot`, `fix/wear-sync-request-units`. Dexcom records keep 5-minute slots and a fixed start; the watch keeps one record per G7 (short alias vs full name) and finds the phone's calibration for it; the sensor handoff carries a G7 (code, Bluetooth name, start; not the key) and the phone hands each new G7 to the watch once; `SuperGattCallback` hooks (`holdsSensor`, `supportsWatchClaim`, `readsAlongside`, `stopTransport`) let both hold the G7 between sessions, neither standing down, the watch dialing only with "Direct sensor on watch", and stopping Bluetooth no longer unpairs it; the watch pairs on the smartwatch channel (auth byte 3) and stops at `auth != 1` (since 2026-10-22 upstream's #649 slot model, which took our rule: our four slot commits were dropped; a personal-only commit keeps the watch's pre-#649 G7 on slot 3); no routine sync push for a sensor both read, and a device that misses a reading asks from its loss alarm and takes only the slots it lacks (`Natives.streamSlotsFilled`); `requestSync` read milliseconds as seconds. Verified on both devices (both reading; phone-away and watch-away). Rebuilt 2026-10-11 on the new `fix/dexcom-g7-link` (lifetime from #595's model: 10 or 15 days from the code). Members added 2026-10-11 after the 10-10 sensor change: `fix/wear-dexcom-new-sensor` (the newest G7 is handed over, not the selected one; a driver of the wrong type is replaced after adoption; Watch status follows the handed G7; a Dexcom record's start never moves) and `fix/wear-sync-startup` (direct-reading status only for a new reading; a startup sync request to the other device, with an "answer me" flag on the first report; the phone no longer stands down after its own restart). Merged 2026-10-11 (owner): `fix/wear-loss-alarm-ahead` (while both read, the loss alarm at reading + 320 s instead of 380 s) and `fix/wear-sync-push-unless-read` (the phone pushes a reading of a sensor both read unless the watch reported reading that slot; before, a watch that could not read got readings only from its loss alarm, ~320 s late), and `fix/wear-sync-push-now` (the push carries the accepted reading at once, never dropped by the 45 s throttle; a pushed reading becomes the live reading on the receiving device (it showed the previous one for up to 330 s); the watch face redraws on every reading; a reading only the watch got is handled once on the phone, not twice), and `fix/wear-dexcom-forgotten` (a G7 forgotten on the watch stays forgotten; switching Direct on hands the newest paired G7). 2026-10-12: an app open keeps an earlier loss alarm (never later); the watch lists a G7 by its Bluetooth name like the phone; the `/sync2/own` status byte follows lastReadingMs (shared with alarm-routing: 0x01 charging, 0x02 answer, 0x04 charger unknown), then directAgeMs. | Not submitted |
| `feat/floating-glucose` | Floating glucose. Members: `fix/floating-shared-notification`, `fix/floating-keep-below-status-bar`, `feat/floating-details-popup`, `feat/floating-range-colors`, `feat/floating-mirror`, `fix/floating-trend-arrow`. Uses Notify's glucose channel and id without renaming the channel, detached when it stops; the pill stays on screen and below the status bar strip (2026-10-22: on upstream's #693 position model, the floor folded into its screen area; upstream itself allows the strip); "Details on tap" card (time/age, Δ, chart, IOB/COB); "Over the status bar" as an accessibility overlay (`FloatingAccessibilityService`, disabled in the manifest until the option is on), kept on the lock screen, removed while the screen is off and re-added with a fresh value; range colours; "Mirror layout"; trend arrow from the same points as the dashboard; the time to the next reading as a ring around the trend arrow (owner's pick on 2026-10-11; the temporary style picker and the bar, coloured bar and outline styles removed; with the trend arrow off there is no indicator). 2026-10-29: its settings rows re-laid on upstream's #704 settings grid in one commit ("Details on tap", details opacity, "Over the status bar", "Mirror layout" as one card group after the position rows; "Time to next reading" under Metrics); the shared-notification start keeps upstream's `forgetPublishedOngoingContent` before our repost. 2026-10-30: the service resolves the pill's reading with upstream's #615 peers and per-sensor history (in our "resolve the pill's reading in the service" commit). | Not submitted |
| `fix/wear-app` | Watch app fixes. Members: `fix/wear-swipe-back`, `fix/wear-toggle-refresh`, `fix/wear-main-opens-at-top`, `fix/wear-complication-freshness`, `fix/wear-notification-freshness`, `fix/wear-hide-disabled-calibration`. Wear OS 6 swipe-back; Exchange/Alerts/Settings switches redraw when the phone answers; opens on the graph; complications and the ongoing notification no longer run a reading behind and show "No value" once stale; calibration switched off on the phone hides calibration on the watch; every watch trend arrow (complications, main screen, alarm screen) uses the phone dashboard's rate, and complications refresh after a G7 backfill. On the watch the live reading was counted beside its stored row (ms vs whole seconds): `augmentHistory` now merges a live reading within 30 s of its row, fixing a flat arrow and a too-high alert rate on the watch (the phone is unchanged). 2026-10-11: the watch alarm screen's arrow text and the round-arrow complication follow the same rate and cut-offs as every other arrow. 2026-10-12: the Wear sync replay harness (upstream #603) fakes the refresh with a reading time. | Not submitted. Issue ctqvva/JugglucoNG#262 |
| `feat/glucose-display` | Range colours and status display. Members: `fix/glucose-range-colors`, `fix/wear-glucose-ranges`, `feat/notification-colored-status-icon`, `fix/notification-searching-status`. Range colours follow the configured bands; the watch uses the phone's ranges and colours; optional range-coloured status bar value (2026-10-29: its switch closes upstream's Typography card); no "Searching for sensors" between G7 sessions while readings are fresh. The + menu scrim (`fix/dashboard-add-menu-scrim`) was dropped 2026-10-29 for upstream's own (#718, a 0.55–0.78 gradient). | Not submitted |
| `fix/nightscout` | All Nightscout fixes on one branch: no silent switch to API v3 on a 404; treatment backoff on the boot clock, reset by a network change or "Send now"; own v1 treatments not received back as duplicates after a switch to v3; optional "Upload only on Wi-Fi" (default network's transport, no location); deleting a treatment in the journal deletes it on Nightscout when "Send amounts" is on (offline failures never drop it, works under v1 and v3); a refused receive no longer holds back sends; switching Follow off ends the follower's sensor record (no leftover card); 2026-10-11: with "Receive treatments" on, opening the app reads treatments back, and a reading does when the last read is 15 min old (it was only on leaving the app, a journal change or a network change); an app open inside the 5-min floor does a light read of the newest 20 (30 s floor). 2026-10-12: the reading-triggered read is a light read (newest 20), due with 30 s slack so it lands on the third reading; full reads stay main's (5-min floor) plus an app open past that floor. 2026-10-22: on upstream's #555 follower, the stop is set and the first history import runs under its publish gate. 2026-10-29: upstream's #736 (drop a refused token, retry once) is folded into our split treatment read (`fetchTreatmentsRead` with `tokenAuth`, `count`, `retryRejectedToken`). | Not submitted |
| `fix/nightscout-token-log` | From `main`, 2026-10-12. With logging on, the token exchange logged its whole answer (the Nightscout JWT, which carries the access token) to trace.log; now code and length only. | Not submitted (upstream privacy fix) |
| `fix/restart-after-update` | From `main`, 2026-10-22. `NumAlarm` also receives `MY_PACKAGE_REPLACED` and starts the service as after a reboot (an update ended it; on the watch, updated over adb, the process came back but not the service, and Android froze the app until it was opened). Test: `ServiceRestartManifestTests`. | Not submitted |
| `fix/health-connect` | Activity import re-runs on app foreground (at most every 15 min) and after the permission grant, reads every page, no step/exercise double count; backfilled and gap-filled readings reach Health Connect (cursor rewind); each feature asks only for its own permission, never on every reading; stable `clientRecordId` so re-sends replace instead of duplicating. 2026-10-29: the switch is on upstream's "Apps on this phone" screen (#698), so our `glucoseSwitchedOn` is called from `PhoneAppsSettings.setHealthConnectEnabled`. Not yet device-tested. | Not submitted |
| `feat/persistent-low-alarm` | "Persistent low" alert: rings when readings stay below a threshold for a duration, rides out compression dips (resets only above threshold + margin, held by Very low), optional "hold while rising". The episode start is taken from the stored readings (same start on phone and watch, after a restart or a settings change). Compares the reading as displayed (2026-10-11: a reading shown 3.9 is not below 3.9; recovery at threshold + margin as shown). 2026-10-29: found by the settings search (#698) under Low alerts; the stored readings go through #689's causal filter like the live value when it is on (the same commit is on `feat/alarm-routing`, which shares that code). | Not submitted |
| `fix/wear-alarm-settings-sync` | The watch takes every alert's settings and the shared alert settings from the phone (`c:`/`g:` lines on WearToggleSync), so watch alarms match the phone's. | Not submitted |
| `feat/alarm-routing` | On top of `fix/wear-alarm-settings-sync`. "Where alarms ring" (both / watch when connected / phone only), "On the watch" alarm style, shared snooze/dismiss/quiet window (`/sync2/silence`), test button follows the routing, watch alarm screen via a full-screen notification, Persistent high counted between readings and started from the stored readings. 2026-10-12: the charging status byte uses the layout shared with feat/dexcom-g7-watch. 2026-10-22: upstream's #631 (`WearAlertSync`, acknowledgement sync) runs beside `/sync2/silence`: `SnoozeManager` carries both `recordCancellation` and `fromPeer`, and upstream's remote path (`AlertActions.applyState`) passes `fromPeer` so a remote acknowledgement is not echoed on `/sync2/silence`; `lossalarm` returns upstream's boolean (held or answered on the watch = said). 2026-10-29: owner keeps both syncs; Persistent high's stored readings go through #689's causal filter. Upstream's #696 (alarm notification when the alarm window never shows) does nothing on the watch, where our full-screen notification does that job; its test harness (`AlarmWindowFallbackTests`) needs our `cancelAlarmScreenNotification` stub. | Not submitted |
| `feat/alarm-history` | 30-day alarm history and an active-snooze card on the alarm settings page, the watch's entries included (`/sync2/alarmhistory`). | Not submitted |
| `feat/quick-treatment-entry` | Quick treatment entry: one sheet (since 2026-10-29 upstream's: its type selector, animated height #758 and insulin window cards #759; our type tabs and fixed sheet height dropped, owner's choice; a type switch on a new entry still starts that type afresh, keeping time and note; `TEMP_BASAL` from #729 is never offered), per-insulin step/default dose/reminder times (Room v33; upstream still on v32), recent chips, last-dose/last-food line on new entries only, undo bar, basal reminder ("Tresiba due at 22:00", last dose named), quick tiles, app shortcuts, notification and pill "+ Insulin/+ Food" (switch "Quick log buttons"). | Not submitted |
| `feat/readings-as-dots` | Display → "Readings shown as" line / dots / line and dots, for the app's charts, the notification/AOD/widget/pill card chart and the watch chart + chart complication (mirrored to the watch through `SettingsRegistry`). | Not submitted |
| `fix/wear-remove-native-sensor` | From `main`, 2026-10-11. A sensor removed on the phone or forgotten on the watch ends its native record on the watch (every spelling of its name), so it leaves the list and no driver is built for it again (a removed G7 was paired anew from scratch). Readings stay. 2026-10-11: a removal frees only the callbacks of the records it ends (on the watch a Forget of the old G7 had also freed, and unpaired, the new G7's driver). | Not submitted |
| `fix/sensor-expiry-expected-end` | From `main`, 2026-10-11. The sensor-expiry alarm counts down to the expected end for native sensors (G7/ONE+/Stelo and Libre 2: +12 h; Libre 3/Accu-Chek/Air unchanged); Sibionics and managed drivers keep the official end. | Not submitted |
| `fix/glucose-display-conversion` | From `main`, 2026-10-11. One mg/dL → mmol/L conversion for every shown reading (phone rows × 0.0555, watch rows ÷ 18.0182 and the live value ÷ 18 gave 0.1 differences on ~12 % of readings); a second, droppable commit does the same for the managed drivers' live values. Alarms compare the reading as displayed (`ShownGlucose`, owner 2026-10-11: Low 3.9 rings at 3.8 and below, High 10.0 at 10.1 and above; Persistent high and custom alerts too). | Not submitted |
| `fix/trend-arrow-shared` | From `main`, 2026-10-11. The legacy floating glucose and canvas arrows use the shared trend angle (flat band, 45° at 1, vertical at 2 mg/dL/min, double above) and the dashboard's rate. | Not submitted |
| `fix/floating-island-centre` | From `main`, 2026-10-11. The island pill centres on the camera cutout (from the display's cutout), whatever the rotation, font or digits; the offset slider becomes a signed nudge (−20..20 dp, default 0, new key); the label gap uses the cutout's extent along the edge. 2026-10-29: the nudge slider is in upstream's island card (#704). With `feat/floating-glucose` only the service's imports conflict (keep both). | Not submitted |
| `fix/connect-pending-latch` | From `main`, 2026-10-11. A connect request due sooner replaces a pending later one (a pending warmup retry dropped every other connect until Reconnect). All drivers; with #595 Dexcom no longer uses the latch. | Not submitted |
| `fix/sensor-warmup-status` | From `main`, 2026-10-11. A G7's sensor line keeps "Sensor ready in N minutes" for the whole warmup once the sensor was reached, then "Waiting for first reading" (new string) for up to 15 min (uk wording fixed 2026-10-12). | Not submitted |
| `fix/dexcom-warmup-signal-loss` | From `main` (on #595's PR head until 2026-10-12), 2026-10-11. A G7 still warming up is reconnected without being marked "Loss of signal" while another sensor reads. | Not submitted (follows #595) |
| `fix/sensor-handover-first-reading` | From `main`, 2026-10-11. Automatic sensor handover waits for the new sensor's first reading while the old one still reads. | Not submitted |
| `feat/current-reading-hub` | From `main`, 2026-10-11 (DISPLAY-PLAN.md step 1). One current reading per device: `DisplayedReading` (value, text, one display arrow rate, freshness, revision), `CurrentReadingHub` fed once per accepted reading, `ReadingClock` with one wake at the next deadline. Stale = last reading + the sensor's interval (driver: G7 5 min, Libre 1 min; else median of recent gaps) + 20 s; "---" at +30 min. No consumers on the phone yet. 2026-10-12: a reading is published once (a time within 1 s of the published one keeps the published time; a changed look is a BACKFILL). 2026-10-30: its arrow ends at the shown reading (`DisplayTrendSource.endingAtShown`, #760 Fewer readings). | Not submitted (structural; needs the maintainer's slot) |
| `feat/wear-current-reading` | On top of `fix/wear-app` + `feat/current-reading-hub` (DISPLAY-PLAN.md step 2, watch). Every watch surface (complications, watch face, main-screen hero, ongoing activity, alarm screen) draws the hub's reading; `ComplicationFreshness` is the hub's clock; per-surface resolves and timers removed (MainScreen 30 s loop, MultiSensor's 300 s period, the direct complication refresh); IconValue LONG_TEXT works; one stale look (dimmed, no arrow; "---" at 30 min). 2026-10-29: upstream's glucose tile (#697) takes that stale look too (dimmed chart value, tile version changes when stale, "---"). Not device-tested yet. | Not submitted |
| `feat/phone-current-reading` | From `feat/current-reading-hub`, 2026-10-11 (DISPLAY-PLAN.md slice 2, steps 3, 4, 6). Every upstream phone surface draws the hub's reading: ongoing notification and status icon (one stale renderer: value kept, no arrow; "No new value since" alone at 30 min; `oldnotification`, the Handler deadline, the retained snapshot and the 750 ms follow-up removed), AOD (now goes stale), legacy `Floating`, alarm banner and screen (one arrow), dashboard hero. The phone's clock is an exact wake-up alarm (`setExactAndAllowWhileIdle`) sharing the loss alarm's wake when they coincide; `dowithglucose`/`processExternalCurrentReading` take an `Origin`. Outbound feeds and alert decisions untouched. 2026-10-22: upstream's #665 AOD freshness follows the hub (dimmed when stale, "No new value since" in place of the number once taken down); `processExternalCurrentReading` keeps upstream's `sensorStartMs` beside the `Origin`. 2026-10-29: on upstream's #675 (periodic glucose notification for watch forwarding): its content fingerprint, `PeriodicGlucoseUpdates` and announcement runnables kept; our hub-driven publish commits the announcement when posted and releases it when overtaken or failed, the startup restore too (it also posts with its fingerprint, unlike upstream, so the hub's first publish of the same reading is not posted twice); upstream's `shouldRunInteractiveFollowUp` dropped with our removed 750 ms follow-up. Upstream's Robolectric `NotificationForwardingSettingsTests` cannot run on this machine. Not device-tested yet. | Not submitted |
| `feat/floating-current-reading` | `feat/floating-glucose` + `feat/current-reading-hub` (slice 2, step 5). The pill and its card draw the hub's reading (`FloatingPillLook`); stale = dimmed, no arrow, the late ring alone; "---" at 30 min; the ring follows the hub's `nextExpectedAt` and stale moment. `FloatingPillWatchdog` and the pill's own staleness clock removed (the frame clock stays); 2026-10-12 also its screen-on log line. 2026-10-30: upstream's #615 peer sensor value fills the empty secondary slot: the service resolves the peers (`SecondarySensorDisplay`) on each hub publish and selection change, `FloatingPillLook.of(reading, peer)` (`FloatingPillPeerTests`). | Not submitted |

Dropped from `personal` on 2026-10-06 (branches deleted; their commits stay in `personal`'s history and can be brought back): `feat/xdrip-interapp-broadcast` (tip `933de71bc`, removed by `d95fd5f99`; the owner no longer uses xDrip+), `feat/wear-colored-complication` (tip `f4793672b`, reverted; maybe later), `feat/floating-hide-glucose-notification` (tip `41fd10b67`, removed by `7360a3b62`). Dropped 2026-10-08: `feat/samsung-now-bar` (Now Bar live notification and gauge; tip `c91fb9694`, kept as the local tag `dropped/feat-samsung-now-bar`, removed by `eb8ea6efc`). Dropped 2026-10-11 (owner, to stay close to upstream; `personal` rebuilt from `main` without them; tips kept as local tags): `fix/dashboard-chart-bounds` (tag `dropped/fix-dashboard-chart-bounds`; caused NEXT items 9 and 10) and `feat/alarm-settings-save` (tag `dropped/feat-alarm-settings-save`; with it went the personal glue commits `496268905`, `88008d003`). Dropped 2026-10-29 (owner, at the sync on upstream db1b9b4ac; tips kept as local tags): `feat/widget-rework` and `feat/widget-current-reading` (widgets not used; tags `dropped/feat-widget-rework` and `dropped/feat-widget-current-reading`, both already rebased on db1b9b4ac, with the `notifColor` fix #698 needed) and `fix/nightscout-jwt-renew` (superseded by upstream #736, which drops a refused token and retries once; tag `dropped/fix-nightscout-jwt-renew`, not rebased).

Open work and investigation notes: [NEXT.md](NEXT.md). Battery runs (method and
results): [BATTERY.md](BATTERY.md). Reviews of 2026-10-08 and what was fixed:
[REVIEW.md](REVIEW.md), [REVIEW-COPILOT.md](REVIEW-COPILOT.md),
[REVIEW-FIXES.md](REVIEW-FIXES.md).

### Sending branches upstream

The branches are not yet one-PR-each: [REVIEW.md](REVIEW.md) and
[REVIEW-COPILOT.md](REVIEW-COPILOT.md) (2026-10-08) list what to split, squash,
scrub and translate first, and the order to send them in. To send one as a pull
request to `ctqvva/JugglucoNG`:

1. Update `main` from upstream, then rebase the branch onto it (upstream moves).
2. Squash it to one commit if it has review follow-ups, e.g. on a fresh branch:
   `git checkout -b <name>-pr main && git merge --squash <branch> && git commit`.
3. Build and run the tests for that branch alone, then push it to `origin` and
   open the PR from the fork.

Dependencies: `feat/dexcom-g7-watch` needs `fix/dexcom-g7-link` (both on `main`, which has
upstream #595 since 2026-10-12);
`feat/alarm-routing` needs `fix/wear-alarm-settings-sync`; the four
`feat/*-current-reading` branches are their parent + a `--no-ff` merge of
`feat/current-reading-hub` + their own commits. Real
conflicts between branches (2026-10-11): `feat/dexcom-g7-watch` × `feat/alarm-routing`
(`SensorOwnershipRuntime.kt`, both extend the ownership report), `fix/wear-app` ×
`feat/glucose-display` (`TrailingThrottle.kt`), `feat/floating-glucose` ×
`fix/floating-island-centre` (2026-10-29: only the service's imports; keep both),
`feat/alarm-routing` × `feat/alarm-history` (2026-10-29: the test stubs in
`AlertRetryAcknowledgementTests.kt`; keep both). In `SensorOwnershipRuntime.kt`
`feat/alarm-routing` (watch charger state) and `feat/dexcom-g7-watch` (startup "answer
this report") each append one trailing byte to the ownership report: in `personal` they
share one status byte (bit 0 charging, bit 1 answer requested, bit 2 charger unknown);
keep that resolution (merge commit "Merge branch 'feat/dexcom-g7-watch' into personal" of
2026-10-11, with its test). All branches add strings to
the 16 `strings.xml` files (Hungarian, `values-hu`, still lacks ours).

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

Two ways (owner, 2026-10-30): a **quick sync** for routine updates, often; a **full sync**
(below) before sending pull requests, or every week or two. Run the script from the main
checkout. git rerere is on in this repository (`rerere.enabled`, `rerere.autoupdate`, local
config, not committed): a conflict resolved once is resolved the same way when it comes
back, in either kind of sync, and the script lists it as a `RERERE` line and goes on. Look
at those lines; `git rerere forget <file>` drops a resolution that turned out wrong. On a
new machine: `git config rerere.enabled true && git config rerere.autoupdate true`.

Quick sync: `personal` + one merge of the new upstream, no topic branch moved, so each
conflict is resolved once (in the full sync it comes up in the branch and again in the
rebuild).

```bash
S=scripts/personal/sync-upstream.sh
bash $S quick        # quick/<date> = personal + merge of upstream main; at a STOP: resolve
                     # in C:/source/ng-wt-sync, git add, then: bash $S continue
bash $S check        # test.sh, both release builds, the signer
bash scripts/personal/materialize-symlinks.sh --restore && git reset --keep quick/<date>
bash scripts/personal/materialize-symlinks.sh
git push origin main personal       # owner's go only; both fast-forward
```

A fix the merge needs beyond its conflicts (code that no longer compiles against upstream,
a test) goes in its own commit on top, prefixed `Quick:`; the next full sync replays it as a
personal commit, and moves it to its topic branch where it belongs. Between full syncs the
topic branches stay on an older upstream: rebase one before working on it or sending it.

Full sync: `scripts/personal/sync-upstream.sh` runs the steps below one at a time, resumably, in the
worktree `C:/source/ng-wt-sync` (`WT=` to change it); the branches, their merge order and
stacking are in `scripts/personal/branches.txt` (keep it in step with the table above).
`OLD=` names the personal being replaced (default `personal`), `DATE=` the backup tag date.

```bash
S=scripts/personal/sync-upstream.sh
bash $S forecast     # what upstream changed under each branch; conflicts a merge would hit
bash $S start        # backup/<date>/* tags; main = upstream/main
bash $S rebase       # every branch onto main as sync/<branch>; at a STOP: resolve in the
                     # worktree (bash $S keepboth <file> keeps both sides), git add, then:
bash $S continue     # (repeat until "all branches rebased")
bash $S adopt        # branches = their sync/ refs
bash $S rebuild      # rebuild/<date>: merges, $OLD's personal code commits, docs; at a STOP:
                     # resolve, git add, bash $S continue
bash $S verify       # every CHECK line must be explained (a dropped branch, an adaptation)
bash $S check        # test.sh, both release builds, the signer
```

The same steps by hand:

```bash
OLD=$(git rev-parse personal)                             # keep: the reference for step 5
for b in personal main $(git branch --format='%(refname:short)' | grep -E '^(fix|feat)/'); do
  git tag -f "backup/$(date +%F)/$b" "$b"; done            # local undo point
git fetch upstream
git branch -f main upstream/main && git push origin main  # main is not checked out
# 1. Rebase each topic onto main; stacked ones with --onto <parent> <old parent tip>
#    (dexcom-g7-watch on dexcom-g7-link, alarm-routing on wear-alarm-settings-sync); the
#    *-current-reading ones are recreated (parent, --no-ff merge of the rebased hub, their
#    own commits). Work in a separate worktree, onto new refs (sync/<branch>) so the
#    branches only move once all are done.
# 2. Rebuild personal on a temporary branch from main: git merge --no-ff each
#    branch in the table order; resolve with $OLD's version of each conflicted file
#    only where no branch touching it changed that file (scripts/personal/
#    rebuild-personal.sh does exactly this and stops for the rest); otherwise by hand.
# 3. Replay the personal-only CODE commits (find them with
#    git log --no-merges --first-parent main..$OLD, minus "Personal:" ones):
#    the leftover hidden notification channel; the details card's + Insulin/+ Food and
#    its Quick-log-buttons switch (two commits, after feat/floating-glucose and
#    feat/quick-treatment-entry); the hub's PEER origin (after feat/dexcom-g7-watch and
#    feat/wear-current-reading); the Dexcom harness faking UiRefreshBus; "Dexcom: the
#    watch's G7 paired before slots were selectable keeps slot 3"; and, after
#    feat/phone-current-reading, "Personal: the phone's loss check no longer redraws..."
#    (MobileGlucoseAlarms) and "Personal: carry over merge resolutions the rebuild lost".
#    The 2026-10-29 ids are in "Last full sync" below.
#    A file taken from $OLD (by hand or by the script) also carries what dropped branches
#    did to it: after dropping a branch, reverse its own patch on such files (2026-10-29:
#    SuperGattCallback.java and GlucoseAlarmsAsymmetryTests.kt kept widget changes).
#    Take old resolutions from $OLD as it stood before its own commits (the commit before
#    the first one), never from its tip: a file from the tip brings those commits' changes
#    along, their replay shrinks to the rest, and the part that rode along is lost when that
#    file is next merged by hand (10-30: the details card's + buttons). sync-upstream.sh does.
# 4. git checkout $OLD -- docs/personal scripts/personal CLAUDE.md && commit "Personal: …"
# 5. Verify: merge main into a copy of $OLD the plain way; its tree must equal the
#    rebuilt one (strings.xml line order aside). Any difference is lost integration.
# 6. Tests (scripts/personal/test.sh) and both release builds, then publish:
git push --force-with-lease=<branch>:<backup tag sha> origin <branches…>
bash scripts/personal/materialize-symlinks.sh --restore && git reset --keep <rebuilt>
bash scripts/personal/materialize-symlinks.sh            # Windows only
git push --force-with-lease=personal:$OLD origin personal
```

- `.git/info/attributes` holds `Common/src/main/res/values*/strings.xml merge=union`
  (local, not committed): the "both sides appended strings" conflicts resolve
  themselves. After each rebase/merge, check every `values*/strings.xml` for a
  duplicated `name=` (a union of two edits of one string keeps both). (`name="settings"`
  shows twice in three locales: one is upstream's commented-out line.)
- Last full sync: **2026-10-30 (main dad71b093, 77 upstream commits: #760 data smoothing
  targets with "Fewer readings" for the current value, arrow and Δ ending at the shown reading
  (24eb6482e, 62111cbc1), #615 a peer sensor in the pill's secondary slot, #779 IOB/COB on the
  watch and the watch store's `readings` apart from the chart `points` (0829245f6), #785
  Nightscout follower sends treatments, #788 Nightscout outage, #774 prediction analysis, ...)**.
  Old tips in `backup/2026-10-30/<branch>`; `$OLD` was `rebuild/2026-10-29` (never adopted as
  `personal`; tagged `backup/2026-10-30/rebuild-2026-10-29`). First sync run with
  `sync-upstream.sh` (`forecast` … `check`); 22 branches went through untouched or with
  keep-both conflicts. Handed to agents in their own worktrees while the script went on
  (`SKIP=`): `feat/floating-glucose` + `feat/floating-current-reading` (our service-side and
  hub-driven pill reading against #615's overlay rework: the service now resolves #615's peers,
  `FloatingPillLook` fills the secondary slot through `SecondarySensorDisplay.fallback`;
  `FloatingPillPeerTests`) and `feat/phone-current-reading` (#760's arrow/Δ cut on our path:
  `DisplayTrendSource.endingAt` for the notification's Δ, the dashboard header's
  `movementHistory` cut at the hub reading; upstream's paired-sensor hero takes our
  `showArrow`/dimmed value). Adaptations: `feat/current-reading-hub` cuts its arrow at the shown
  reading (`endingAtShown` in `dashboardRate`); `fix/wear-app` and `feat/wear-current-reading`
  measure row arrows over the store's `readings`. Rebuild: the script's merges with the old
  resolutions; by hand the island × floating imports and layout call (island's `centre`/`nudge`
  with floating-current's plain `Modifier`), and `SuperGattCallback.java` as the old resolution
  with upstream's change replayed (`git merge-file`). Verify: beyond the plain-merge conflict
  files, only the adaptations above, strings.xml order and the two blocks of
  `NightscoutJournalFollowerImporter.kt` in the other order.
- Previous full sync: **2026-10-29 (main db1b9b4ac, 62 upstream commits: #675 periodic glucose
  notification for watch forwarding, #697 watch glucose tile, #698 settings tab and search,
  #704 notification/floating/AOD settings on the settings grid, #718 + menu scrim, #729 AAPS
  temp basal, #736 Nightscout refused-token retry, #758/#759/#763 journal entry sheet, #689
  causal Kalman filter, #733 Clone take-over, ...)**. Old tips in `backup/2026-10-29/<branch>`.
  Before rebuilding, three read-only agents compared every branch with upstream and the
  owner chose: drop `fix/nightscout-jwt-renew` (#736) and the + menu scrim (#718), drop
  the widgets (not used), take upstream's journal entry sheet, keep the current-reading
  family (upstream still has a clock per surface) and both alarm syncs. Rebased in
  `C:/source/ng-wt-sync` onto `sync/<branch>` refs, then the branches moved; the floating
  branch was replayed keeping upstream's `DisplaySettingsScreens.kt` and re-laid in one
  commit; `feat/phone-current-reading` (Notify × #675) and `feat/quick-treatment-entry`
  (upstream's sheet) were redone by agents in `C:/source/ng-wt-phone` and
  `C:/source/ng-wt-qte`. Fix commits for upstream changes: `notifColor` (#698, on the dropped
  widget branch), temp basal in received edits (`fix/nightscout`), the tile's stale look
  (`feat/wear-current-reading`), settings search and causal filter (`feat/persistent-low-alarm`,
  `feat/alarm-routing`). `personal` rebuilt as `rebuild/2026-10-29` with
  `rebuild-personal.sh`, `feat/quick-treatment-entry` and `feat/phone-current-reading` merged
  last (they were still being redone); hand resolutions: `JournalRepository.kt` and three
  phone-merge files as the old resolution with upstream's change replayed (`git merge-file`).
  Personal code commits replayed: 56fdfd403, 984f8b9d8, fb9307353, c972ff096, 22c138d44,
  a2d7f2f30, 0b7d87084, 7c9487c94. Verify (step 5): every difference from a plain merge
  of `main` into the old personal is a dropped branch, a file the plain merge conflicts in,
  or one of the adaptations above.
- Earlier full sync: **2026-10-22 (main fc5e9bc07, 53 upstream commits: #649 Dexcom slots,
  #631 alarm acknowledgement sync, #665 stale AOD/widget, #693 floating position, #555
  follower polling, #636 AiDex Adaptive V2, ...)**. Old tips in `backup/2026-10-22/<branch>`.
  Done in one worktree (`C:/source/ng-wt-sync`), `personal` rebuilt on `rebuild/2026-10-22`
  with `rebuild-personal.sh`; where it stopped on a file upstream changed but no branch's
  own change to it did, the old resolution was kept with upstream's change replayed onto it
  (`git merge-file <old personal> <old main> <new main>`). Judgment calls (details in the
  branch rows): `feat/dexcom-g7-watch` drops its four slot commits for #649 (same rule) plus
  the personal slot-3 migration; `feat/alarm-routing` keeps `/sync2/silence` beside #631;
  `feat/floating-glucose` keeps the status-bar floor on #693's model; `feat/glucose-display`'s
  trailing coalescer stays and #665's freshness deadline delivers outside it;
  `feat/widget-rework` keeps Glance deleted (#665's Glance changes dropped with it);
  `fix/glucose-display-conversion`'s AiDex commit leaves #636's new conversion sites as they
  are. Verify (step 5): the only tree differences from a plain merge of `main` into the old
  personal beyond strings.xml order are the files of those calls and the new
  `fix/nightscout-jwt-renew`.
- Older full sync: **2026-10-12 (main 4f9a7e01e, upstream #595 merged)**. Every branch was
  rebased (five agents by group; old tips in `backup/2026-10-12/<branch>`, old personal
  `backup/2026-10-12/personal`) and `personal` rebuilt with `rebuild-personal.sh` (which now
  also refuses an old resolution of a file upstream changed). Branches that merged the hub
  (`feat/{wear,phone,floating,widget}-current-reading`) were recreated: parent branch, a
  `--no-ff` merge of the rebased hub, then their own commits. The personal-only code commits
  are listed in step 3 above; `08bf24170` (the + type menu removed) is no longer replayed:
  `feat/quick-treatment-entry` keeps upstream's #617 FAB menu (tap = our sheet on the last
  type, hold = upstream's type menu, each item opening our sheet on that tab). Branches now
  carry Hungarian strings (upstream's `values-hu`). The `merge=union` attribute duplicated
  strings that upstream reworded next to ours (fixed on `fix/nightscout`): check for
  duplicated `name=` after every rebase. Merge resolutions to keep: `AlertRuntimeManager.evaluatePersistentHighLocked`
  takes `feat/alarm-routing`'s `PersistentHighPolicy` block (it applies the shown-value rule)
  over `fix/glucose-display-conversion`'s one-line version.
  The upstream conflict forecast and plan for the next sync: [UPSTREAM.md](UPSTREAM.md).
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
