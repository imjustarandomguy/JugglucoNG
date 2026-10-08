# Personal fork: open work and findings

Hand-off notes for the next session. Read [BUILD.md](BUILD.md) first for the
setup and branch model. Paths are relative to `Common/src/`.

## Status (2026-10-06)

In `personal`, built, installed and verified on the owner's devices:
- `feat/dex-autoconnect`: verified. A bonded G7 reconnects with
  `isDirect=false`, the session lasts ~4 s, and there are zero connect attempts
  or timeouts between sessions. The old build had a direct connect timing out
  (status 147) every ~10 s.
  - A short burst remains. Right after a session the G7 keeps advertising for
    up to ~1.5 s, and the background connect re-links 0–3 times, each ended
    by the sensor with status 19 (NG's `(tim-datatime)<60000` guard leaves the
    link idle). This costs well under 1 s of radio time per cycle. A possible
    refinement: delay re-arming slightly after a data session. A scheduler
    delay alone is unsafe if the CPU sleeps, so don't do it naively.
- `feat/xdrip-interapp-broadcast`: verified working with xDrip's data source
  "Inter-app broadcast". Dropped from `personal` later the same day (the
  owner no longer uses xDrip+); see BUILD.md for how to bring it back.

Branches were consolidated into topic branches on 2026-10-06. Older names in
these notes are members of the topics listed in BUILD.md.
- `fix/notification-searching-status`, `fix/dex-battery-hygiene`,
  `fix/dex-gatt-robustness`, `fix/wear-swipe-back`, `fix/wear-toggle-refresh`:
  installed and nothing broken. The battery effect on the watch (deep Doze)
  is not measured yet; check with `adb -s <watch> shell dumpsys deviceidle`.

How to verify the connect mode on the phone (Samsung does not log the usual
`connect()` line):
`BtGatt.GattService: clientConnect(tk.glucodata.ng) ... isDirect=false|true`
plus `BluetoothGatt: onClientConnectionState() status=0|19|147`.

## Night build test (2026-10-06, `personal` 8920b3e04, installed 23:27)

Owner's results: Watch and G7 (watch reads the G7 again, card says "Phone + watch"),
phone display fixes and Nightscout all good. Health Connect: testing over the next days.
Alarms: rang on both devices, and a dismiss on one stopped both. Long-acting insulin
upload to Nightscout stays as it is (owner's decision; it goes up as a Correction Bolus
with `isBasalInsulin`, `insulinType` and "Long-Acting" in the notes).

Found, to fix later (owner said notes only for now):

- **Editing a treatment that came from Nightscout does not stick.** Owner entered 5 U
  elsewhere, it arrived in NG, edited it to 6 there: nothing went up, and the next
  receive set it back to 5. Edits of Nightscout-origin rows are never uploaded (known
  from the delete work). Fix: with "Send amounts" on, upload the edit (v3 update by
  identifier, v1 by `_id`) and keep the importer from overwriting a local edit that has
  not been sent yet. Belongs on `fix/nightscout`.
- **The watch rang but showed nothing.** No alarm screen on the watch while it was
  ringing. Evidence: right after the alarm, `appops get tk.glucodata.ng` on the watch
  showed `SYSTEM_ALERT_WINDOW: default; rejectTime=...` (an overlay or background-start
  attempt was refused); `USE_FULL_SCREEN_INTENT` is granted. Look at how the wear
  `AlarmActivity` is started (overlay or background activity start) and use a
  full-screen-intent notification or the ongoing-activity path instead.
- **The watch rang about 5 s before the phone** (mode Both). Each device rings on its own
  G7 reading, and the phone's session normally comes about 3 s before the watch's, so the
  phone's ring path adds a delay: check the sound delay / vibrate-first setting, Notify's
  first-fire gate, or the 15 s check. Ask the owner which alarm it was and whether the
  phone vibrated first.
- **The dashboard + menu's backdrop should be darker.** It is
  `JournalFabMenuScrimAlpha = 0.32f` in `mobile/.../ui/journal/JournalFabMenuPopup.kt`
  (`fix/dashboard-add-menu-scrim`, in `feat/glucose-display`); try about 0.5–0.6.
- **The floating glucose still goes out of date until tapped,** seen on 2026-10-06 night on
  the lock screen and on 2026-10-07 morning with the phone unlocked and in use. So the
  screen-off/on fix (`feat/floating-glucose`, pill window removed while the screen is off
  and re-added with a fresh value) does not cover it: the pill misses updates while the
  screen is on too. Find what tapping does that the update path does not (tapping
  refreshes the value), and why a new reading does not reach the pill.

## Nightscout treatment format (owner's reference for the desktop app)

What NG uploads for journal entries, as of `personal` 8920b3e04. Source:
`mobile/.../data/journal/JournalTreatmentTransfer.kt` (`buildTreatmentJson`) and
`JournalTreatmentUploader.kt`.

Fields on every treatment:

| Field | Value |
|---|---|
| `date` | epoch milliseconds of the entry |
| `created_at` | the same time, ISO 8601 UTC with milliseconds: `2026-10-06T22:15:00.000Z` |
| `utcOffset` | `0` |
| `isValid` | `true` |
| `app`, `enteredBy` | `"JugglucoNG"` |
| `type` | NG's own type: `insulin`, `carbs`, `fingerstick`, `activity`, `note` |
| `journalTitle` | the entry's title in NG |
| `journalSource` | where the entry came from: `manual`, `health_connect`, `meter`, `pen`, `aaps`, `nightscout`, `api`, `clone`, … |
| `updated_at` | last change, ISO 8601 UTC |
| `notes` | as below; parts are joined with `" \| "` |

Per type:

| NG type | `eventType` | Extra fields |
|---|---|---|
| Insulin | `Correction Bolus` (rapid and long-acting alike) | `insulin` (units, number); `isBasalInsulin` (true for a long-acting preset); `insulinType` (preset name, else the title); `notes` = `"Rapid-Acting"` or `"Long-Acting"` plus the note; with a preset also `insulinOnsetMinutes`, `insulinDurationMinutes` |
| Carbs, under 12 g | `Carb Correction` | see carbs below |
| Carbs, 12 g or more | `Meal Bolus` | `carbs` (g); `food` (food name, else the title); `duration` and `durationInMilliseconds` (absorption, **milliseconds**); `absorptionTime` (minutes); `protein`/`proteinGrams` and `fat`/`fatGrams` when set; `notes` = the note |
| Fingerstick | `BG Check` | `glucose` and `glucoseValueMgDl` (always mg/dL), `glucoseType: "Finger"`, `units: "mg/dl"`, `notes` |
| Activity | `Exercise` | `duration` (**minutes** here), `intensity` (string, may be empty), `notes` = title plus note |
| Note | `Note` | `notes` = title plus note |

Absorption when none is set: `60 + 2·carbs + 1.5·protein + 2.5·fat` minutes, kept between
30 and 360.

Identity and endpoints:

- **v1** (`api-secret` header, SHA-1 of the secret): `POST /api/v1/treatments` with
  `identifier: "jng-j-<row id in hex>"` and no `_id`; NG keeps the `_id` the server answers.
- **v3** (`Authorization: Bearer <JWT>`, from `/api/v2/authorization/request/<token>`):
  - Create: `POST /api/v3/treatments`, with `_id` and `identifier` both
    `"jng-j-<row id hex>-<entry time ms hex>"`.
  - Change: `PATCH /api/v3/treatments/<identifier>`, with `date`, `created_at`,
    `utcOffset`, `_id` and `identifier` left out of the body; v3 refuses changes to them.
- **Delete:**
  - v3: `DELETE /api/v3/treatments/<id>?permanent=true`.
  - v1: `DELETE /api/v1/treatments/<_id>` for a Mongo id, otherwise a delete by query on
    `find[identifier]` with `find[created_at][$gte]=2000-01-01`.
- **Not uploaded:**
  - entries mirrored from AAPS, the API or Clone;
  - long-acting insulin unless "Send long-acting insulin" is on;
  - edits of entries received from Nightscout (a known bug, above).
- **Read back:** NG recognises long-acting from `isBasalInsulin` or "basal" / "long" /
  "nph" in `eventType`, `notes`, `insulinType` or the app name, and files it under a
  long-acting preset.

## Evening batch (2026-10-07), merged into `personal`, not built yet

Owner's reports and what was done (all unit-tested with `scripts/personal/test.sh`):
- **Persistent low rang on the phone only (19:57).** Not routing. The owner edited the
  alarm at 19:45; the phone started its 15 min from its last reading (19:42), the watch,
  asleep when the new settings arrived, from its next (19:47), due 20:02, by when the
  value was back at the line. Fix: both persistent alarms take the episode start from the
  stored readings (walk back ≤ 3 h, a gap > 20 min ends the walk), the in-memory count is
  reset on a settings change, and the watch evaluates right after settings arrive. Side
  effect, accepted: switching the alarm on (or its active hours opening) while already
  low for longer than the duration rings at the next check. The re-arm margin default is
  0.2 mmol/L (not 0.6).
- **Complication arrow flat while the app showed slightly up.** Three trend pipelines
  (complication: smoothed, uncalibrated, edge reading droppable; watch screen: calibrated,
  graph-smoothed, 35 min; phone: measured) met one hard cut (≤ 0.5 mg/dL/min = flat).
  Every watch arrow now uses the phone dashboard's rate; complications refresh after a G7
  backfill. The alert engine's rate is unchanged.
- **Pill next-reading bar:** in the landscape island it ran along the short side. Four
  styles behind a temporary picker (Display → Floating glucose → Next reading style):
  bar, coloured bar, outline, arrow ring. **Pick one, then remove the picker.**
- **Alarm settings:** draft + save bar + leave prompt (no reset confirmation needed:
  Discard undoes it). Test alert plays the saved settings (a note says so).
- **Dashboard chart:** bounded panning (future: 10 min, or the prediction horizon); no y drag (vertical swipes scroll the page), y axis = Chart range widened to what is in view; clipping, handle drag + remembered height. "Readings shown as" dots option
  everywhere (default line).
- **Lock screen:** Samsung offers no lock-screen widgets to other apps (One UI 9).
  Built instead: the Now Bar live notification (option, off by default) and the widget
  rework (Glance gone: this was item 5's battery cost), so a 1-row widget can go on the
  lock screen with Good Lock LockStar.
- **Quick entry:** no last-dose/last-food line on an existing entry; reminder now reads
  "Toujeo due at 21:00 / Last dose: 18 U, yesterday 21:04".

To test after the next install:
1. Widgets: existing widgets after the upgrade (may show "can't load" until the first
   render), resize both, Settings screen (long-press and Settings → Widgets), screen
   off → on, stale look, LockStar 1-row size. Battery: a run to compare with run 3.
2. Now Bar: Notification settings → Live notification. If nothing shows, Developer
   options → "Live notifications for all apps".
3. Pill styles, landscape island.
4. Alarm page: change, leave (prompt), save; watch gets the change once.
5. Chart: pan limits, y drag cap, handle, dots.
6. Watch: complication arrow vs app at a slight rise.
7. Persistent low: both devices ring on the same reading.

## To do (as of 2026-10-07 afternoon)

Merged into `personal` (1e1a3a711), compiled and unit-tested, **not built or installed
yet**; test all of it in the next round:

- **Nightscout:** an edit of a treatment received from Nightscout is sent back (v3 PATCH
  of the changed fields, v1 PUT of the whole document) and a pending edit is not
  overwritten by the next receive; the time of such a treatment is greyed out in the
  editor ("Time can't be changed for treatments from Nightscout"), because v3 makes
  `date` immutable. Check on the real server that 5 → 6 U sticks.
- **Watch alarm screen:** the watch raises alarms through a full-screen alarm
  notification (channel "Alarms") instead of a background activity start, which Wear OS
  refused. With another app in front Wear OS may show the alarm card instead; if the
  full screen is wanted there too: `adb shell appops set tk.glucodata.ng
  SYSTEM_ALERT_WINDOW allow` on the watch.
- **Persistent high** counts its duration between readings and fires on the reading that
  completes it, so phone and watch ring on the same G7 reading (was: the 15 s tick).
- **Test button** follows "Where alarms ring"; dismiss/snooze of a test stops it on the
  other device; tests never reach the history or the SMS watchdog.
- **Watch button:** "Stop reading on the watch" for the G7, "Return sensor to phone"
  otherwise; pressing it now also turns the phone's "Direct sensor on watch" off.
- **Floating glucose:** the pill's reading is resolved in the service on every reading
  (including watch gap fill); a stale reading is drawn dimmed (50 %); a thin bar along
  the bottom fills toward the next reading and turns amber when it is late ("Time to
  next reading", on by default).
- **Darker + menu backdrop** (0.55).

**Floating glucose self-check, to remove later.** `FloatingPillWatchdog` (every 30 s
with the screen on, and 3 s after each reading) repairs a pill that drew an older
reading than it was handed: it does what a tap does, then re-adds the window. The owner
considers this a band-aid: the data-path bugs above were real root causes, but the
"stale until tapped" redraw stall was never proven. Plan: during the test round, NG
logging on (Debug settings); the self-check logs `FloatingGlucose: pill behind: <step>`
for every repair. After about a week, pull `trace.log`:
- no repairs logged → the data-path fixes were the cause; remove the repair part, keep
  only the staleness clock (needed: a reading goes stale without any new data);
- repairs logged → the step is known; fix that properly, then remove the repair.

**Root cause found (2026-10-07, `a9ca9eb93` on `feat/floating-glucose`).** The first log
(10:11–10:29) had one repair, `pill behind: REDRAW` at 10:12:55, 3 s after the pill was
re-added at screen on (10:12:52.6); the reading had arrived at 10:12:47 with the screen
off. Samsung One UI stops Choreographer animation callbacks for a background process with
no window on screen (logcat: `Choreographer: CoreRune.SYSPERF_ACTIVE_APP_BBA_ENABLE : stop
animation in background states`, `BBA2 receive callback when in bg`), and Compose's frame
clock gets frames only from those callbacks. So after screen off (pill window removed) the
recomposer got no frame, and the re-added window drew the composition from before screen
off until something else forced a frame: a tap (the original "stale until tapped", seen in
the previous build's logcat at 09:16) or the self-check. Fix: the pill's own frame clock
(next vsync, or 50 ms later from a main-thread handler; still paused with the screen off),
and at screen on the window is added only after the composition holds the handed reading
(1 s timeout, then the self-check repairs). Next log: one line per screen on, `screen on:
pill window added after N ms with reading HH:mm:ss <value> rev R, composed before its
first frame`, and no `pill behind:` lines. If that holds for a week, remove the repair
part of `FloatingPillWatchdog` and keep only the staleness clock.

**Sensor identity refactor: decided against (2026-10-07).** The audit and plan are in
[SENSOR-IDENTITY.md](SENSOR-IDENTITY.md); the owner judged the 100+ file refactor too big
for what it buys. Instead, fix name-mismatch bugs one at a time when they show up, using
the alias key (`SensorIdentity.crossDeviceKey` / `sameNativeSensor`, added on
`feat/dexcom-g7-watch` for the "Watch: no report yet" fix). The audit's ranked at-risk list
says where to look first.

**Quick treatment entry (owner-approved spec, 2026-10-07).** The owner logs nearly every
treatment, mostly insulin (Fiasp, and Tresiba 25 U every night around 22:00).
1. The dashboard + button opens the entry sheet directly (the separate type menu goes;
   it offered the same five types). The sheet opens on the **last used type**, has the
   five types as tabs at the top, and a **fixed height** for every type (no jumping).
2. Insulin library, new fields per insulin: **step** (default 1 U; today the step is
   hard-coded 0.5 U in `JournalCompose.kt`, which the owner's pen can't do), **default
   dose** (empty by default, not 0), and for long-acting insulins **reminder times**
   (a list, none by default). Choosing an insulin fills its default dose; −/+ use its step.
3. **Recent chips**, insulin and food only: the 5 most used values over the last 7 days,
   most used first, from the whole journal (Nightscout/AAPS/watch imports included).
   Insulin: per selected insulin, in units. Food: carb amounts in grams. Tapping fills
   the amount.
4. **Last-dose context line** (new entries only since 2026-10-07: on an existing entry it
   named some other entry), insulin and food only, under the insulin choice / amount:
   "Last Fiasp: 6 U · 1 h 20 min ago" shown only while that dose is still active (the
   insulin's own duration from the library); "Last food: 45 g · 1 h 10 min ago" only
   within that meal's absorption time. No line otherwise. No duplicate warnings.
5. The system keyboard opens only when the user taps the number; its Done key saves.
6. **Undo bar** after Save: "Saved 6 U Fiasp · Undo" for a few seconds (the dashboard
   already has a snackbar host used for undoing deletes).
7. **Basal reminder:** at each reminder time, if no dose of that insulin (any source)
   was logged since halfway back to the previous reminder time (one daily 22:00 → since
   10:00; 08:00 and 20:00 → since 14:00 / since 02:00), notify "Tresiba due at 22:00" /
   "Last dose: 25 U, yesterday 22:04" (or "Not logged yet"; changed 2026-10-07 from "not
   logged / No dose since 10:00") with "Log 25 U" (default dose; "Log" opening the sheet
   when none) and "Snooze 30 min".
   One notification per reminder time; no repeats unless snoozed.
8. **Ways in without opening the app**, all opening the same sheet over the current
   screen: "+ Insulin" / "+ Food" in the floating pill's details card, a quick settings
   tile "Log insulin", a "Log" action on the glucose notification, app-icon shortcuts.
Not wanted: time chips (keep the current time field), time-window pre-selection
("Suggest between"), auto-filling basal from the + button, duplicate hints.

Still open:

1. **Alarm history: one entry per alarm,** marked "phone + watch" when both rang (merge
   by alert type and start time a few seconds apart; keep each device's outcome).
2. **Snooze status on the dashboard,** e.g. a chip "Low snoozed · 12 min" that opens the
   snooze card.
3. **Alarm events in the dashboard's history list below the graph,** with readings and
   treatments (possibly a marker on the graph).
5. **Glance widget cost: addressed by `feat/widget-rework` (2026-10-07), to measure.** Run 3 (2026-10-07) showed the big graph
   widget (`mobile/.../widget/ExpressiveAppWidget.kt`, Glance 1.1.1) costing about half of
   NG's phone background use: each refresh runs Glance's WorkManager `SessionWorker`, which
   held a wake lock up to ~45 s per run (30 runs, 5 min 39 s in 2 h 36 min, about one per
   reading). Look into ending the Glance session as soon as the widget is drawn (or
   updating without a long-lived session) instead of idling up to its timeout. Fallback
   options: accept it (~1 %/day), or the classic RemoteViews widget (no graph).
4. **To test:** Health Connect over the next days (owner); the new-G7 handover to the
   watch at the next sensor change (about 10.10).
6. **Coloured watch complication (bring back).** A range-coloured "value + arrow"
   complication for the watch face, shelved and dropped on 2026-10-06. Its commits are in
   `personal`'s history: branch tip `f4793672b` ("Wear: colored complication mirrors Value
   + arrow"), first commit `94d1690a2`, reverted in `personal` by `ed6947dba`/`205ee4179`.
   Re-apply on a new branch from `main` (cherry-pick both), then check it against what has
   changed since: the watch now gets the phone's range colours and thresholds
   (`feat/glucose-display`), and complications refresh on every reading
   (`fix/wear-app`'s complication freshness). Find out first why it was shelved (BUILD.md /
   NEXT.md history around 2026-10-06 00:16).
7. **Phone stands down from the G7 after its own restart.** After every install/reboot the
   phone hands the G7 over before its G7 driver exists ("handing … to the watch: standing
   down for 180s", seen 11:09 and again after the 16:54 install), so it misses the first
   reading (16:57:46 was read only by the watch; the phone resumed at 17:02:46). The
   ownership logic can't tell yet that the G7 is read alongside (`readsAlongside` needs the
   callback), so it treats it like a one-reader sensor. Fix: never stand down from a sensor
   the watch is set to read alongside (G7 with "Direct sensor on watch"), even before the
   callback exists. Also: after that restart the phone had **two** GATT clients registered
   for NG (`appIf 173` kept from 16:54, plus the current one) — the stand-down seems to leave
   its client registered; make sure it is closed so clients don't pile up across restarts.
   Seen again after the 23:07 install (23:07:46 read by the watch only; the phone was back
   at 23:12:46); not after the 22:26 one.
8. **Phone and watch show slightly different values** (2026-10-07 23:14: phone 7.0, watch
   7.1 for the same reading). Not the arrow bug (fixed). Check: calibration or smoothing
   applied on one side only, rounding, or the watch showing its own G7 stream rather than
   the phone-synced one. Not urgent (owner).

## Done on 2026-10-06 (installed in 8920b3e04)

- **Watch dropped the G7 at 18:11:** fixed on `feat/dexcom-g7-watch`. A Nightscout
  follower record removed on the phone made the watch rebuild its sensor list, and the
  rebuild turned Bluetooth off because of a native "use Bluetooth" flag that the phone's
  /netinfo clears. The watch now keeps Bluetooth on while "Direct sensor on watch" is on,
  re-dials the G7 if needed, and never builds a Bluetooth callback for a cloud record.
- **Reading status:** "Phone + watch" / "Phone" / "Watch" on the G7 card, two lines on the
  Wear OS page and in the watch's sensor list, red when the watch should be reading but
  hasn't for 15 min.
- **Alarms:** the watch applies the phone's full alarm settings
  (`fix/wear-alarm-settings-sync`); "Where alarms ring" plus shared snooze, dismiss and
  quiet window (`feat/alarm-routing`); alarm history and the snooze card
  (`feat/alarm-history`); Persistent low (`feat/persistent-low-alarm`).
- **Nightscout:** a new server address in Follow mode replaces the old follower at once.
- **Battery:** runs 1 and 2 are in [BATTERY.md](BATTERY.md).

## Open items

0. **Fixed (2026-10-06): the watch stopped showing readings after the
   ~00:49 install.** Not caused by the batch itself. The watch had two
   records for the G7: one under the 11-character short alias (made from the
   chunks sent right after pairing) and one under the 16-character full name,
   created at 00:49 when the restarted phone started sending the full name.
   Readings went into the second; the watch displayed the first. Cause: the
   phone cached the new sensor's short name as unknown before its record
   existed, so it was not resolved to the full name until the app restarted.
   Fix in `fix/wear-sensor-alias`. Verified: after installing it, the watch's
   deep sync put 224 readings into the displayed record and the screen showed
   current values. The stale full-name record still exists on the watch
   (harmless, no longer written); it can be removed with a long press in the
   watch's sensor list.
   - How it was found, for next time: a temporary build with `debuggable`
     and logging mirrored to logcat, then
     `adb shell run-as tk.glucodata.ng ls -R files/sensors` showed both
     record directories. Release builds strip `android.util.Log.i/w/d` (R8,
     `proguard-rules.log`) but keep `.e`, and NG's own log only goes to the
     private `files/logs/trace.log`.
   - The phone half of the fix (cache cleared in
     `SensorBluetooth.updateDevices`) only matters at the next sensor pairing.
   - Follow-up (2026-10-06): the phone sends the calibration payload under the
     full name, so the watch found none for the alias record. Readings went
     uncorrected and calibration stayed offered after "Manual calibration" was
     switched off on the phone. Payloads are now keyed by the alias.

1. **Done: coloured status bar icon.** It does show colour on the S26 (the
   earlier "no colour" report was a mistake).
2. **Coloured complication: shelved.** `feat/wear-colored-complication` is on
   `origin` and was reverted in `personal`. The Google "Active" face ignores a
   provider's `ColorRamp` and colour `SmallImage`. Only worth revisiting with a
   face that renders images, or with NG's own watch face.
3. **G7 directly on the watch** (the main item). Details below.
4. **Complication freshness**: never show an old value as current. Ideas: a
   trailing-edge throttle in `UiRefreshBus.refreshWatchFaceSurfaces` (it drops
   rather than postpones updates within 20 s), and timeline complication data
   (valid until reading + interval + 60 s, then dimmed, then "---"), plus
   `TimeDifferenceComplicationText` for "x min ago" without app wakeups.
5. 15-day G7 support (upstream `getDexMaxSecs`/GTIN): skipped, since the owner
   uses 10-day sensors.
6. **Built, to verify on the devices: watch hides calibration when it is off on
   the phone** (`fix/wear-hide-disabled-calibration`). Calibration on the phone
   is per sensor and per lane (auto/raw), set on the phone's calibration screen
   (`CalibrationManager.setEnabledForMode`); toggling it re-sends the payload to
   the watch (`requestUiRefreshAfterCalibrationChange` → `WearSync2.onCalibrationChanged`).
   The watch gates on the lane it displays (`ReadingActions.calibrationAvailable`).
   Test: switch calibration off on the phone; on the watch the Calibration row,
   the Sensor screen's Calibrate row and the chooser's Calibrate button go, and
   with the journal off readings stop being tappable. Switch it on again: all
   come back. Known oddity left alone: a calibration added from the watch is
   always stored in the phone's auto lane.
7. **Floating glucose: range colours and "Mirror layout"** (`feat/floating-range-colors`,
   `feat/floating-mirror`). Colours follow the app-wide "colour value by range"
   setting while the reading is current; a stale value turns back to white (the
   owner's choice; no "---"). The overlay has no timer of its own beyond a 30 s
   staleness recheck while a reading is fresh; its battery cost is negligible.
8. **Floating glucose: details, status bar and notification** (owner's requests).
   - "Details on tap" (`feat/floating-details-popup`): a card with time/age, Δ,
     chart and IOB/COB in its own window beside the pill. Verified on the S26
     (portrait, island).
   - App overlays sit below the status bar window, so nothing drawn in that strip
     can be touched. Free pill: kept below it (`fix/floating-keep-below-status-bar`).
     "Over the status bar" draws the pill as an accessibility overlay through
     `FloatingAccessibilityService` ("JugglucoNG floating glucose"), separate from
     the AOD service. Verified on the S26 for the island. Known: an accessibility
     overlay also draws over the expanded shade, the lock screen (kept: the
     owner wants it there) and the always-on display (hidden while the screen
     is off, against burn-in). Lesson from an earlier attempt to hide it on the
     lock screen: SystemUI sends USER_PRESENT, so a non-exported receiver never
     gets it; `dumpsys activity broadcasts` shows who received what.
     Original Juggluco and GlucoDataHandler accept the limit; xDrip+ has
     no floating overlay.
   - "Hide the glucose notification" (`feat/floating-hide-glucose-notification`):
     dropped. A channel the app creates switched off still left an empty
     notification in the status bar and shade. Instead the owner blocks the
     "glucoseNotification" channel in Android's settings: NG keeps running (the
     G7 stayed connected all night with it blocked), and alarms use other
     channels. Turn the channel back on if the floating glucose is turned off.
     The leftover "Glucose notification (hidden)" channel is deleted at startup
     from `7e5bba14e` on (personal only; not in the S26 build yet).
9. **Production-readiness review (2026-10-06)**: every branch was reviewed
   (comments, texts, code, performance, portability) and the findings applied.
   Still worth doing before sending upstream: a test on another phone/Android
   version, and Gradle lint.

## G7 directly on the watch: design and findings

The owner wants this to use NG's existing settings: "Direct sensor on watch"
and "Switch sensor automatically" (WearOS screen on the phone).

### Why it fails today
- `ManagedSensorHandoff.kt` (`createOutgoingPayload`/`applyIncoming`) carries
  only managed-driver prefs (AiDex, Anytime, iCan, MQ, Ottai, Sibionics). There
  is nothing for Dexcom, whose record is native.
- `WearSensorClaim.checkClaim` (~line 230) requires
  `candidate is ManagedBluetoothSensorDriver`. `DexGattCallback` extends plain
  `SuperGattCallback`.
- `DexGattCallback.onConnectionStateChange` never calls `super`, so
  `SuperGattCallback.locallyConnectedGatt` / `hasLocallyConnectedGatt()` is
  never set for Dex.
- **Ownership assumes a sensor that stays connected.**
  `SensorOwnershipRuntime.holdsLiveConnection` (~line 870) counts only a
  currently connected GATT. `WearSensorClaim.onLocalGattDisconnected` drops
  CONNECTED to REQUESTING on every disconnect, and `CLAIM_TIMEOUT_MS` is 3 min.
  A G7 is disconnected about 4 min 56 s of every 5 min, so the claim would
  collapse every cycle.
- **Record-name collision (the hard part).** In companion mode the watch
  writes synced readings into a generic "direct-stream shell" created by
  `Natives.ensureSensorShell(serial, ...)` (`WearSync2.kt` ~line 485,
  `cpp/g.cpp ensureSensorShellInternal`, `cpp/sensoren.hpp
  ensureDirectStreamShell` ~line 430). It has the G7's own name.
  `makeDexComSensorindex` (`cpp/sensoren.hpp` ~line 640) **reuses any existing
  record with that name** and only calls `SensorGlucoseData::mkdatabaseDex`
  for a new one. So on a watch that already has the shell, a G7 import
  produces a non-Dexcom record and no `DexGattCallback`.
  `removeSensorById` only marks a record removed (`sensors->removesensor`), and
  `findsensorm` still finds it by name.
  - Options: (a) convert in place: rewrite `info.dat` with `mkdatabaseDex`
    (it overwrites a non-Dexcom header), then `resensordata(index)`; check the
    poll geometry; or (b) a one-time `adb shell pm clear tk.glucodata.ng` on
    the watch so the Dexcom record is created before any shell, after which
    `ensureDirectStreamShell` finds and reuses it. `initInfoFile` keeps an
    existing header with starttime > 1e9, days ≥ 10 and dupl > 0.
  - Checked (2026-10-06): it does not. `storeGlucoseStreamSample`
    (`cpp/g.cpp`) puts a reading in slot `(t - start) / 60`; a Dexcom record
    is indexed `(secsSinceStart - age) / 300` (`cpp/dexcom/java.cpp`
    `DEXSECONDS`). A shell can't be converted in place either: its slots
    are per minute.

### How original Juggluco does it (`C:\source\Juggluco`, researched 2026-10-06)
- No pairing key is copied. Its native mirror sync sends the record's header
  (dexcom flag, start time, days), the QR text `siId` (the sensor's 16-char
  name ends in the PIN), and `DexDeviceName` + `deviceaddress`. `sharedKey`
  is never sent.
- The watch pairs from scratch: EC-JPAKE with the PIN, then `createBond` →
  its own LE bond (a consent prompt on the watch). Later reconnects use the
  short `dex8AES` path.
- Handover is manual (phone's Wear OS dialog, or the watch's "Switch"). The
  device that lets go stops Bluetooth, and `DexGattCallback.free()` always
  unbonds. Taking the sensor back means pairing again. No out-of-range
  fallback.
- NG keeps that native sync compiled but the watch drops `/data` on purpose
  (commit d12ce176b, one writer for the watch's readings), so reviving it is
  not an option. NG's `/sensorhandoff` carries the same fields instead.

### Device tests on 2026-10-06 (what we learned)
1. Handover (phone stood down and unbonded, watch paired on the same
   channel byte 0x02): the watch's PIN exchange worked (`dex8AES verified`),
   then the G7 answered `auth=2 bond=2` and hung up (HCI 0x13) when the
   watch asked for the certificate exchange, three times. The phone
   re-pairing right after got `auth=1` and completed (prompt on the phone).
2. Same with the channel byte 0x01 on the watch: `auth=1`, certificates,
   bond (prompt on the watch), readings; the watch's readings reached the
   phone in the right slots (`polls.dat` phase 222). The phone re-paired on
   0x02 afterwards.
3. Research (xDrip4iOS `AuthRequestTxMessage.swift`, xDrip+ keks, Dexcom
   FAQ): the byte is a separate pairing per display channel: 2 phone app,
   1 receiver or AID pump, 3 smartwatch (Dexcom's Direct to Watch). Nobody
   had validated 3 on a G7. Upstream Juggluco sends certificates after
   `auth != 1`, which is what made the sensor hang up.
4. After a session, autoConnect re-links 4-5 times in ~2.5 s before the G7
   stops advertising (same burst as on the phone).

### Plan and status
1. Done, `fix/dexcom-stream-slots`: 5-minute slots for a Dexcom record
   written by time; a stream never moves a Dexcom record's start.
2. Built, `feat/wear-dexcom-record`: the handoff carries `{name, code,
   deviceName, start}` and the watch converts its record in place.
3. Built, `feat/dexcom-session-ownership`: a G7 is read by phone and watch
   at once over their own channels (`readsAlongside`): nobody stands down
   or unpairs; the watch dials it only with "Direct sensor on watch", and
   its claim waits until it is paired. Stopping Bluetooth keeps the pairing
   (`stopTransport`).
4. Built, `feat/wear-dexcom-watch-slot`: the watch asks for channel 3, falls
   back to 1 (remembered per sensor) when refused; stop at `auth != 1`.
5. Built, `feat/wear-sync-fill-gaps`: a device reading the sensor takes the
   other's readings only for slots it lacks (`Natives.streamSlotsFilled`).
6. Done, `feat/wear-sync-alongside`: no routine push for a sensor both read;
   a device that missed a reading asks from its loss alarm (~80 s after the
   missed wake; the owner is fine with that, no extra timer).
7. Done, `fix/dex-rearm-after-session`: background connect armed 5 s after
   a session; 2 connection events per session instead of up to 12.
8. Done, `fix/wear-sync-request-units`: `requestSync` read
   `Natives.lastglucosetime()` (milliseconds) as seconds, so every
   incremental request asked "since now" and got nothing.
9. Built, `feat/wear-dexcom-follow-sensor`: the phone hands each new G7 to
   the watch while "Direct sensor on watch" is on (test at the next sensor
   change). To do: check the watch's battery while it looks for an
   unpaired or out-of-range G7 (continuous scan?).

### Device test results (2026-10-06, both devices read)
- Smartwatch channel (3) accepted on the first try: `auth=1 bond=2`,
  certificates, prompt on the watch, bonded; later sessions `auth=1 bond=1`.
  The phone kept reading on channel 2 throughout: no stand-down, no unbond.
- 15 min both reading: every reading on both, no duplicates.
- Phone away for the 16:42 wake: the watch read it; the phone's loss alarm
  asked the watch at 16:44:02 and stored it (`ingested 1/4 (missing
  only)`), passing it on to xDrip+.
- Watch away for the 16:47 wake: the watch's alarm asked the phone at
  16:49:01 and stored it (`ingested 1/5 (missing only)`).
- Battery run: normal builds installed 16:57; `dumpsys batterystats --reset` on
  both at 16:58 (phone 95 %, watch 86 %), adb disconnected. Read
  `dumpsys batterystats` on both before charging; compare NG's share
  (Bluetooth, wakelocks, CPU) with the drain since then.

### Earlier design notes
1. **Native**, in `cpp/dexcom/java.cpp` (inside `#ifdef DEXCOM`):
   - `dexExportRecord(name)` returns the QR text (`info->siId`, length
     `siIdlen`; the 4-digit PIN is its last 4 chars, `getDexPin`), the
     `sharedKey` (16 bytes), `DexDeviceName[12]`, `deviceaddress[18]` and
     `starttime`.
   - `dexImportPairing(name, key, deviceName, address, start)`.
   - Find the record with `sensors->sensorindexshort(name)` +
     `getSensorData` (see `str2sensorptr` in `cpp/g.cpp`).
2. **Payload**: an optional `"dexcom"` object in the `/sensorhandoff` JSON;
   older watches ignore unknown keys.
   - Watch side: handle the shell (above), then
     `Natives.addSIscangetName(qr, int[])` (as `wearSi/.../PhotoScan.tryConnect`
     does), then `dexImportPairing`, then `SensorBluetooth.updateDevices()`,
     then `WearSync2.requestSync(deep=true)`.
   - With the key the watch skips EC-JPAKE (`isAuthenticated` is true): AES
     challenge → certificate exchange → **its own LE bond** (a consent prompt on
     the watch). If the G7 rejects the key, `resetCerts` zeroes it and the next
     session pairs again with the PIN.
3. **Session-based ownership**:
   - Add a `SuperGattCallback` hook, e.g. `ownershipGraceMs()` (Dex: ~11 min),
     so that "holds the sensor" means connected **or** a local reading within
     the grace period on a driver that is not paused. Use it in
     `holdsLiveConnection` and in the claim checks.
   - Have Dex call `super.onConnectionStateChange`. Make
     `onLocalGattDisconnected` ignore drivers with grace > 0.
   - Let `checkClaim` accept a non-managed driver through a
     `supportsWatchClaim()` hook.
   - Per-driver claim timeout and phone stand-down window (Dex ~20 min: wait
     for the advertisement plus the user accepting the pairing).
4. **Unbond on Bluetooth stop**: `Applic.setbluetooth(false)` →
   `SensorBluetooth.destructor` → `removeDevices` → `DexGattCallback.free()` →
   `unbond()`. Releasing a sensor to the other device must not unbond it.
   Split `free()` (sensor ended) from a transport stop.
5. **Backfill to the phone**:
   - The watch's `WearSync2` push covers only the last 8 readings and is
     throttled to once per 45 s, and the phone can't ask (only the phone answers
     `SYNC2_REQ`, `MessageReceiver.kt` ~line 89).
   - At `0x59` (backfill end) in `DexGattCallback`, push since the last reading
     before the session. The phone's `XdripInterApp.requestCatchUp` already
     fills xDrip gaps from history.
6. **Watch pairing UX**:
   - Relax the wear-only unbond rules in `DexGattCallback`: `triedinvain >
     (isWearable?1:4)`, the 8-minute "takes too long", and the
     "dex8AES different" path.
   - Keep the screen on and play the bonding sound while waiting for the
     prompt.

### Test plan (devices connected, logs on)
- Handover: watch log shows the handoff applied, then `dex8AES verified` or
  JPAKE fallback, then createBond → prompt → `BOND_BONDED` → readings, then
  `WearSensorClaim ... CONNECTED`. The phone stands down and ingests chunks.
- No claim release at each G7 disconnect.
- Watch off for 20 min: the phone takes the sensor back. Note whether the phone
  needs to pair again (this answers whether the G7 keeps two bonds).
- Readings and backfill reach xDrip/GDH through the phone.
