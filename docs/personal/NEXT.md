# Personal fork: open work and findings

Hand-off notes for the next session. Read [BUILD.md](BUILD.md) first for the
setup and branch model. Paths are relative to `Common/src/`.

## Where things stand (2026-10-11 morning) — start here

Overnight work of 2026-10-11 (owner asleep; **nothing installed on the devices**, adb
disconnected). `personal` was rebuilt from the same `main` (b8f3e2e3b; no upstream sync
yet): old tip in tag `backup/2026-10-11/personal`, every old branch tip in
`backup/2026-10-11/*`.
- **Dropped:** `fix/dashboard-chart-bounds`, `feat/alarm-settings-save` (tags
  `dropped/…`). Alarm edits apply at once again; the chart is upstream's.
- **Dexcom stacked on upstream PR #595** (`fix/dexcom-g7-link`, `feat/dexcom-g7-watch`
  rebuilt; see BUILD.md and UPSTREAM.md). Includes two commits worth raising in #595's
  thread (short post-session delay on a timed wake lock instead of an alarm clock; no
  next-slot alarm clock on the watch for a bonded G7).
- **New fix branches** (all from main unless noted, all upstream-sendable): watch removal
  ends native records; expiry alarm on the expected end; one mmol/L conversion; shared
  trend arrow (legacy floating, watch alarm screen, round-arrow complication); island pill
  centred on the cutout (slider now a ±20 dp nudge, default 0); a sooner connect replaces a
  pending one; warmup countdown until the first reading; no "Loss of signal" during warmup
  (on #595); handover waits for the first reading.
- **Personal branch updates:** watch handoff (newest G7, driver rebuilt after adoption,
  Watch status, record start never moved), startup sync (direct-reading status, no
  stand-down after a restart, a startup sync request), D1 (re-ask a stale G7 reply),
  Nightscout reads treatments on app open and every 15 min on readings, pill next-reading
  = arrow ring only.
- **Not merged, owner's call:** `fix/wear-loss-alarm-ahead` (while both read, the loss
  alarm at reading + 320 s instead of 380 s, so a missed watch reading is fetched before
  the complication goes "No value"; also moves the phone's "No new value"/loss handling
  60 s earlier).
- Tests: full suite on the rebuilt personal's code tree: 7945 run, 96 failing = 70
  baseline + 26 machine-only (the 4 new machine-only are the Dexcom Python harnesses, which
  find no `python3` here; run by hand with Python 3.14 they pass), **0 new**. Release builds
  of 645997261 (02:16/02:18, no lint errors): `Common/build/outputs/apk/mobile/release/
  JugglucoNG-1.2.3-Alpha.apk` (arm64-v8a) and `…/wear/release/JugglucoNG-1.2.3-Alpha-wear.apk`
  (armeabi-v7a), both signed `CN=Frederic Morin`, SHA-256 2e8c026d…41c0092 (checked).
- Nothing pushed. `origin` still has the old branches; pushing the rebuilt `personal` and
  the rebuilt/new branches needs `--force-with-lease` (BUILD.md "Updating" step 6) when the
  owner asks.
- Persistent test worktrees kept for faster builds: `/c/source/ng-wt-dex` and
  `/c/source/ng-wt-conv` (detached; `git switch` a branch in one, then
  `materialize-symlinks.sh` and `test.sh`, instead of a new worktree per topic).

**Morning: install together, then test** (BUILD.md section 5; check the signer first):
1. After installing on both: toggle "Direct sensor on watch" off and on once (the watch's
   current G7 record still has the 107 s-early start; the toggle re-adopts it).
2. Watch: long-press the old G7 → Forget: it should leave the list and stay unpaired (no
   pairing prompt afterwards). `dumpsys bluetooth_manager` on the watch: C1:06 not bonded.
3. Values: at 100/109/118/127 mg/dL readings (5.6/6.1/6.6/7.0), phone, watch app and
   complications show the same number. Arrows: watch alarm screen (Test alert) matches the
   complication.
4. Island pill: portrait and both landscape rotations, slider at 0: centred on the camera
   (landscape clamps flush to the edge). The slider now reads −20..20.
5. Nightscout: enter a dose in Nightscout, open NG: it appears within seconds (or ≤ 5 min).
6. Restart the phone app with the watch reading: no "standing down" in the trace, the phone
   asks the watch for what it missed; `dumpsys bluetooth_manager`: one NG GATT client.
7. Watch battery/alarm clocks: `adb shell dumpsys alarm` on the watch shows no Dexcom alarm
   clock after a session; `Juggluco::DexcomReconnect` held ~5–7 s per session.
8. Persistent low: check the duration on the alarm page (20 min was in effect on 10-10).
9. Next sensor change (~10-20): NEXT "Sensor change, watch side" test plan, without
   touching the Direct switch.

**Device test 2026-10-11 morning** (installed c7e3318f7 08:58, phone and watch; includes
`fix/wear-loss-alarm-ahead`, owner-approved):
- Restart: the phone did not stand down; background connect armed at 08:58:28, read
  09:02:22 at :23.6 (4 s session), one GATT client, re-armed 5 s later. The watch read
  09:02:22 too.
- Values match on phone and watch. Island pill: nudge **+3 dp** centres it on the camera in
  portrait and landscape (owner's S26 value). Persistent low duration was 20 min; owner set
  30. Watch Test alert shows the real value and arrow; the phone's Test alert shows
  upstream's demo value (10.5, flat): by design, owner may want both real.
- **Bug: Forget of the old G7 on the watch unpaired the new G7 too** (09:04:37.602 C1:06,
  .681 D8:11; the new G7 stayed listed, so only its driver was freed through
  `SensorBluetooth.removeDevice`'s fuzzy `callbackMatchesSensorId`).
- **Bug (#595): an unpaired G7 is only ever background-connected** (`useAutoConnect()` =
  `directConnectUnreachable`, no bond check), so the watch never re-paired: no attempt at
  09:07:22/09:12:22, also after a Direct toggle. Our dropped 4c07e4930 had the bond guard.
  Raise in #595.
- While the watch did not read the G7, readings reached it only by its loss alarm (~320 s
  late): the phone skips the push for a sensor both read. Fix: push unless the watch
  reported reading that slot itself.
- Nightscout fetch on app open works, but the 5-min ReceiveFloor held it (read 09:18:21,
  booked read 09:23:21 just before the 09:23 dose; arrived ~09:28). Fix: a light read
  (newest ~20, ~30 s floor) on app open.
- **Second install 10:14:55/10:15:06** (personal 00ca8ea12: #595 background connect only for a
  bonded G7 + scan for an unpaired one; a G7 callback keeps the bond while another dials it;
  removal frees only the callbacks of the records it ends; push unless the watch read that
  slot; Nightscout light read on app open). Verified: the watch found the unpaired G7 by
  scan at 10:17:23 and asked to pair (prompt timed out unanswered after 25 s: the G7 drops
  the link ~35 s after connect); at 10:22 it asked again, the owner accepted at 10:22:45,
  bonded. Lesson: the consent prompt must be accepted within ~20 s of appearing.
- **Third install 14:03:44/14:03:54** (personal 5cfd47ec9: display slices 1 + 2 — one current
  reading per device, every phone and watch surface drawing it, the phone's exact-alarm stale
  clock; plus the push carrying the reading at once). Owner: at 14:07:22 every surface (watch
  complications, face, app; phone notification, pill, widget) changed at the same time with
  the same value. Phone: G7 session 14:07:22.9, `CURRENT_READING_DEADLINE` exact alarm at
  14:12:43.7 (reading + 5:20), loss alarm 14:13:43.7. To watch over a few days: same value
  and arrow everywhere, the stale look at +5:20 on a missed reading (all at once, also in
  Doze), "---" at 30 min, no flicker at screen on, alarm banner and screen arrows equal.
  (The phone's background connect to "Contour7802H8289230" is the owner's meter, normal.)
- **Review of 2026-10-11 afternoon** (3 reviewers, read-only, every finding verified in code)
  and fixes:
  - Upstream fix branches: real G7 names were in `WearSensorRemovalTests` (pushed): scrubbed
    (tip amended, `fix/wear-remove-native-sensor` 818d4d423) and in comments/tests of
    `feat/dexcom-g7-watch` (1389e9e61); notes use placeholders. An old pushed commit message on
    `feat/dexcom-g7-watch` (4bcadf40d) still names the expired G7 (left; owner may reword).
    The 0.1 fix made Low fire on a reading shown exactly at the threshold: **alarms now compare
    the reading as displayed** (owner): Low 3.9 rings at 3.8 and below, High 10.0 at 10.1 and
    above; Persistent low/high and custom alerts the same; recovery at threshold + margin as
    shown (`ShownGlucose`, on fix/glucose-display-conversion, feat/persistent-low-alarm,
    feat/alarm-routing). Pre-upstream cleanups still to do: Ukrainian "Очікування першого
    показання", comments that narrate history, the island branch's rebase around #612, the
    expiry/handover KDoc overlap, the 15-min reading-triggered Nightscout read as a light read,
    the ownership-report trailing byte: personal's layout (status byte after directAgeMs: bit 0
    charging, bit 1 answer, bit 2 charger unknown) differs from feat/alarm-routing alone (after
    lastReadingMs, bit 0 charging) and feat/dexcom-g7-watch alone (bit 0 answer): unify first.
  - Display pipeline (all fixed 2026-10-11, merged 75d34001e): alarm arrow could be the
    previous reading's; deadline wakes without goAsync/wakelock and the stale redraw behind a
    1 s debounce; text complications/ongoing activity showed a stale value with no sign (owner:
    **value + age**, e.g. "112 · 6m", counted by the watch face); unit/view-mode/smoothing
    changes did not recompute the hub; collapse smoothing on 1-min sensors looked stale; an
    alertwatch post could be lost; watch peer staleness only on main publishes; the watch's
    sensor list should use the phone's G7 label (NOT done yet: belongs on feat/dexcom-g7-watch, `Natives.dexGetDeviceName(dataptr)`).
  - Dexcom/watch: the +320 s loss alarm could cut a running session (now spared); a G7 Forgot
    on the watch came back on a Direct toggle (now stays forgotten; the switch hands the newest
    paired G7); `removedBond` never cleared after a re-pair; an unpaired G7 could direct-dial a
    stored address; `resume()` woke only one callback. Not fixed, noted: with automatic sensor
    switch on, `nudge()` redials a G7 between sessions every reconcile (a "Loss of signal" note
    each minute).
- Owner's requirement (2026-10-11): every surface (watch app, complication, pill, widget,
  phone app, notification…) shows the same value and arrow, updated at the same moment, from
  one pipeline without per-surface timers. Plan requested (agent), before any code.

**Run of 2026-10-11 15:52 → 2026-10-12 12:24 (dbd8371e3; owner: "everything appeared to run
smoothly").** Phone trace + logcat, dumpsys of both; battery in BATTERY.md run 6.
- Fine (proven): the phone read all 246 slots itself (sessions ~4 s, 16 re-asks after the
  previous reading came first, a few failed auth/descriptor writes retried in-session), the
  watch 245 (its 16:02:23 report missing right after the charger: the phone's push landed
  first, likely dropped as a duplicate; nothing went stale). Hub revisions in order, every
  publish FRESH, on the slot grid; no stale alarm, loss alarm or "---" on either device; loss
  alarm at reading + 320 s from the second reading on; all 40 pill screen-ons current, **0
  `pill behind:` lines**; no nudge "Loss of signal"; one NG GATT client per device (+ the
  phone's meter), only D8:11 bonded; no crash/ANR; no alarm clock; Nightscout 246 × 201 within
  0–5 s, a 4-hour off-Wi-Fi backlog (07:38–11:47) flushed in one burst; app-open light reads
  work.
- **Fix: app open moves the loss alarm to now + 350 s** (`SuperGlucoseAlarms.setLossAlarm`,
  `anySetToReadAlongside` branch from e41121373 on `feat/dexcom-g7-watch`): 5 app opens
  pushed it to reading + 359…587 s, and `PhoneReadingClock` was not re-planned. Harmless
  while readings come; with a missed slot the fetch from the watch would be minutes late.
  Keep the earlier of the armed time and the new one.
- **Fix: two NEW_READING publishes per reading** (202 of 246 slots): the stored time in whole
  seconds, then the local time with ms; `CurrentReadingHub.classify` counts the refinement
  as newer, so the notification redraws 3–5 times in 4 s. Treat a time within
  `SAME_READING_MS` as the same reading (`feat/current-reading-hub`).
- **Fix (upstream, privacy): `NightPost.java` ~333 logs the whole token-exchange body** (the
  Nightscout JWT) to trace.log when logging is on: 24 times in this trace. Log code and
  length only. Owner may rotate the token (traces sit in the phone's Download folder).
- Question for the owner: plain Low never started an episode in 7 days of trace (only
  Persistent low); 17:22–17:42 had 3.8/3.8/3.4/3.9/3.7. Is plain Low off on purpose?
- Known, still to do: the 15-min reading-triggered treatment read is a full read (162 items)
  and often lands every 20 min. The watch still reports ownership for the old G7's record
  (not bonded, no dialling).
- Upstream (checked 12:30): #617 and #621 merged (upstream/main 29 commits ahead of `main`);
  #595 refreshed (main merged in + a one-line setup fix, 3c16fcf3d), maintainer: "If all is
  well I'll merge".

**Later (owner, 2026-10-11): try #595's alarm-clock reconnects.** Ours holds the CPU ~5 s
after each G7 session with a timed wake lock (measured: ~18 min awake per day per device,
phone ~4–5 mAh/day, 1–2 % of the watch's drain); #595 uses `setAlarmClock` instead (CPU can
sleep those seconds, but each one takes the device out of Doze and may show as the "next
alarm"; on the watch also a next-slot alarm clock). Never measured. A/B: one day with
`fix/dexcom-g7-link`'s two commits 713d97e7a (short delay on a wake lock) and 072efbb54
(no next-slot alarm clock on the watch) reverted, compare with battery run 5/6 and look for
the alarm icon (status bar, AOD, watch face). Then tell the maintainer what we saw in #595.

## Where things stood (2026-10-08 evening)

- **Installed on both devices (17:14–17:15):** `personal` at e98dfdf4d (1.2.3-Alpha):
  upstream synced to b8f3e2e3b, every fix from the two reviews ([REVIEW.md](REVIEW.md),
  [REVIEW-COPILOT.md](REVIEW-COPILOT.md); what changed: [REVIEW-FIXES.md](REVIEW-FIXES.md),
  rounds 1 and 2), the Now Bar dropped. Unit tests: no new failures (7827 run, 92 failing
  = 70 baseline + 22 machine-only; run them only with `scripts/personal/test.sh`). After
  the install both devices read the G7 (phone again from 17:22, see item 7); the phone
  has one GATT client left (the leak fix works). Logging (trace.log) still on.
- **Friday ~2026-10-10, sensor change:** pair the new G7 on the PHONE first. The phone's
  fresh-pairing path is main's again (review #10); never tested on a device. Then check
  the new-sensor handoff to the watch (also untested at a sensor change). Bring adb and
  trace.log if anything goes wrong.
- **To try on the devices** (nothing below has been device-tested): the alarm settings
  save bar + "Unsaved alarm changes" notification; Persistent low after a dismissed Very
  low; shared quiet window start-then-cancel within seconds on either device; the insulin
  reminder's Log button and "Not logged: this insulin changed"; quick entry save errors;
  Nightscout uploads after an edit/delete of a received treatment; Health Connect import
  (no re-uploads of unchanged activities); widgets (resize, settings, screen-off); the
  pill's details card staying current while open; watch complication arrow vs phone; chart
  dots/limits/height; the pill's four next-reading styles (**pick one**; then remove the
  temporary picker).
- **Open, not started:** items 7–11 at the end of "To do" (phone stands down after its own
  restart; 0.1 value gap phone/watch; chart right edge a day ahead; dashboard page hard to
  scroll; two silence-sync ordering holes from Copilot's round 3), the gaps listed at the
  end of REVIEW-FIXES.md, and upstream preparation per PR
  (REVIEW.md section 2–3; owner's choice: later, branch by branch).
- **Battery:** run 4 in [BATTERY.md](BATTERY.md) (NG on the phone 1.7 mAh/h over a day, no
  widget sessions any more). Next run: test the watch reset hypothesis noted there.
- **Around 2026-10-14:** check trace.log for `FloatingGlucose: pill behind:` lines; none →
  remove the repair part of `FloatingPillWatchdog` (see below), then turn logging off.

## Owner's report of 2026-10-10 (two days on e98dfdf4d, sensor change)

Battery that day (watch / phone): 00:15 93/100 %, 10:00 73/93, 13:22 64/88, 16:15 56/86,
20:00 47/83, 21:45 38/79. Data captured 22:03 (phone trace + NG logcat from the phone's
Download folder, dumpsys of both devices); the watch's own trace.log is not reachable
(release build).

**Sensor change (new G7 10-day paired ~20:15, alive ~20:45; old one expired 20:53, last
value ~20:58). "Didn't feel smooth, needs to be looked at heavily."**
- Phone got the pair request. The sensor page showed "Sensor ready in x minutes" only
  briefly, then "Waiting for connection" (wanted: the countdown until the first reading),
  then "Loss of signal" when it should have come online. "Reconnect" was needed, and
  probably the checkmark to "activate" it.
- Watch: no pair request, the new G7 was never dialled; it still listed 2 sensors after the
  old one was removed on the phone. Wear OS page "Watch status" kept the old sensor ("not
  reading since 20:57"), only the watch line red.
- Toggling "Direct sensor on watch" off/on at ~21:22 updated the status and brought the pair
  request to the watch, but the 21:22 reading was missed and a value appeared at 21:20 (off
  the :x2/:x7 grid). Sensor page then: "6.9 4m", "phone: reading the sensor, last 1 min ago",
  "watch: getting values from the phone", last BLE error "Loss of signal".
- 21:27: watch got 5.7, the phone only at 21:28 (both said "reading, last 0 min ago"). 21:32
  normal on both.

**Other observations**
- Complication slow to update on a new value, maybe only when values come from the phone.
- Phone off during one reading: the watch got it, the phone did not take it from the watch
  until the next reading. Normal?
- The watch app does not start at watch boot: complication "No value" until the app is
  opened. Would the next reading have fixed it?
- Nightscout data late once by 20–30 min (no proof).
- Sensor expiry alarm (morning) used "Ends officially" instead of "Expected end" (G7: +12 h).
- Persistent low rang at 08:52 (3.9, 15 min, re-arm margin 0.5): check the timing.
- Complication value sometimes 0.1 off the watch app / phone (item 8 below).
- Pill in the landscape island: needs 4 px offset in portrait, 0 px in landscape (labels run
  across the punch hole there). Wanted: a way that works for any device.
- Questions: how the trend arrow is computed (how many readings); how much of NG is still
  Juggluco's C++; faster builds/tests; upstream news and PR ctqvva/JugglucoNG#595 overlap;
  what to drop to stay close to upstream before the next rebase.

Findings: see "Findings of 2026-10-10" below (filled in from the analysis).

## Findings of 2026-10-10 (analysis of the phone trace, dumpsys, code)

Names: old G7 `<old G7 full name>` / alias `<old G7 alias>` (BT ..:C1:06), new G7
`<new G7 full name>` / alias `<new G7 alias>` (BT ..:D8:11, session start 20:14:07).

### Sensor change, phone side: all causes are upstream code

Pairing itself was fine (14 s scan to bonded, main's path; `auth=1 bond=2`, certs, bond).
Then no connect to the new G7 from 20:16:28 until Reconnect at 21:03:07:
- **A. Warmup retry on a clock that stops in sleep (proven).** The 4095 warmup packet
  schedules one retry at start + 30 min + 15 s (20:44:22) via `connectToActiveDevice` →
  `Applic.scheduler` (`SuperGattCallback.java` ~1575, `DexGattCallback.java` ~376-386;
  main ~290-302), whose `nanoTime` clock stops while the CPU sleeps (awake only 10 of
  those 28 min). It never ran.
- **B. The connect-pending latch (proven).** `if (connectPending) return true`
  (`SuperGattCallback.java` ~1565) dropped every other connect while that task waited:
  the loss-of-signal `reconnect()`, each app start, the loss alarm. Reconnect worked only
  because `setPause(true)` clears the latch. Affects every driver.
- **C. Countdown vanished after 15 min.** `LegacySensorStatus.kt` 11-31 shows "Sensor
  ready in N minutes" only while the native status is non-blank, i.e. while the 4095
  packet's `sensorerror` lasts (15 min for Dexcom); then "Waiting for connection".
- **D. "Loss of signal" during warmup.** Each old-G7 reading runs `othersworking` →
  `reconnect()` → `noteLossOfSignal` once the new sensor's last packet is > 12 min old;
  the warmup text hides it until 20:44, then it is the status.
- **E. The old sensor stayed main.** Notifications/alarms come only from
  `Natives.lastsensorname()` (`SuperGattCallback.java` ~733-760) until the old one was
  removed (21:05:06). The checkmark is upstream's "Select display sensor": it only adds
  the sensor to the chart; nothing needs activating (G7 starts itself). Upstream's opt-in
  "Automatic sensor handover" (`SensorHandoverRuntime`) was off, and it would trigger at
  the official end (too early: the new G7 was still warming up).
- No value shown 20:57:48–21:05:08. 20:42–20:57 were backfilled at 21:03:22 (no gap).
- **PR ctqvva/JugglucoNG#595** moves delayed Dexcom connects to wakeup alarms without a
  latch: the first live reading would have come ~16 min earlier with no Reconnect. It does
  not fix C, D, E. Review point: it makes every post-session re-arm a `setAlarmClock`
  (every 5 min; Doze exits, "next alarm" icon?).
- Fixes (upstream, ranked): 1 warmup retry on a wakeup alarm (in #595; stand-alone: always
  `setalarm` when `stillwait>0`); 2 a newer/earlier connect request replaces a pending
  one (all drivers); 3 keep the countdown until the sensor was first reached, then
  "Waiting for first reading"; 4 no loss-of-signal from `othersworking` during warmup;
  5 handover waits for the new sensor's first reading while the old one still reads, then
  the owner can turn "Automatic sensor handover" on (no checkmark/removal by hand);
  6 (low) end warmup on the G7's own state (valid data at 28.2 min).

### Sensor change, watch side: two bugs in `feat/dexcom-g7-watch` + an upstream gap

- **Bug A (phone).** `ManagedSensorHandoff.followSelectedDexcom` (~180-199, run every
  minute from `SensorOwnershipRuntime.announceAndReconcile`) hands over only the
  *selected* G7 (`resolveMainSensor()`), and logs nothing when it skips. The old G7 stayed
  selected, so the new one went at 21:05:17, after the removal.
- **Bug B (watch).** At 21:03:44 a deep sync created a generic shell record for the new
  G7; the watch app restarted ~21:04 (cause unknown, 1 ANR in batterystats) and built a
  `Libre2GattCallback` for it (scan filter fde3). The handoff at 21:05:17 converted the
  record to Dexcom (`dexAdoptSensor`) but `updateDevicers` keeps a callback whose name
  matches without checking its class, so nothing dialled <new G7 BLE name>; the watch scanned 15 min
  for nothing. The Direct toggle at 21:19:55 rebuilt all drivers → febc, bond at 21:22:34.
  The claim timeout (21:07) also cleared the direct request, so readings were ignored by
  the claim until the toggle.
- **Removal (upstream gap, worse here).** `/sync2/remove` reached the watch, but
  `WearSync2.removeSensorRecord` (~127) never ends the native record, so the old G7 stays
  in the watch's list and gets a driver again. Here it also kept its bond: **the watch
  still dials the expired old G7 every 5 min** (leak, run 5). Likely the removal name
  (`<old G7 alias>`) does not match the watch driver's 6-character name (`<old G7 listing>`).
- **Watch status** follows `sensor.<node>`, written only when Direct is switched on;
  `followSelectedDexcom` updates only `dexcom.<node>`. The phone line is never red by
  design (`DirectReadingLines` colours only the watch line).
- Fixes: 1 hand over the newest active Dexcom record, not the selected one (before any
  synced reading, so no shell record exists: avoids B too); 2 after `dexAdoptSensor`,
  drop a non-Dexcom callback for that record, `updateDevices()`, re-arm the claim;
  3 `removeSensorRecord` ends the native record (upstream, own `fix/` branch from main)
  and matches the watch's short names (`fix/wear-sensor-alias`); 4 write `sensor.<node>`
  together with `dexcom.<node>`.
- Test at the next sensor change (~10-20): don't touch the Direct switch; expect within a
  minute of pairing `handed G7 <new> to watch`, watch scan filter febc only, the watch's
  pair prompt during warmup; removing the old G7 removes it from the watch list and its
  bond on the watch; Watch status switches by itself. Capture the watch's dumpsys
  bluetooth_manager before any workaround.
- **Now:** remove the old G7 on the watch (long press in its sensor list) and check
  `dumpsys bluetooth_manager` that C1:06 is unbonded; if not, unpair it in the watch's
  Bluetooth settings.

- **Watch "Forget sensor" (tried 2026-10-10 23:43).** It unbonded the old G7 (first tap
  23:43:09; the watch stopped dialling C1:06 from the 23:47 cycle), but the entry stayed in
  the list: `WearSync2.forgetSensorLocally` → `removeSensorRecord` ends only the driver and
  the managed registry entry, never a native record (same gap as the phone's removal). Fix 3
  below covers both. **Worse:** the driver rebuilt for the still-listed record then paired
  the expired G7 from scratch: consent prompts on the watch at 23:58:02 and 23:59:00 (timed
  out, status 9), 00:00:01 (accepted, bonded 00:00:16), so it reads the old G7 again every
  5 min, as before. Leave it bonded until W3 is in (forgetting it again only brings the
  prompt back about once a minute).

### Owner's decisions (2026-10-11 00:00)

- Drop `fix/dashboard-chart-bounds` and `feat/alarm-settings-save` before the rebase.
  Keep `feat/readings-as-dots` and `feat/widget-rework` for now (trim their code where it
  is appropriate).
- Pill next-reading style: **arrow ring**; remove the picker and the other three styles.
- Dexcom: rebuild `fix/dexcom-g7-link` stacked on PR #595 now (keep only what #595 does not
  cover), port `feat/dexcom-g7-watch` onto it, test on both devices.
- Order: fixes first (small branches from the current main, tests and builds), device test
  together (never install overnight: a restart costs a reading and a bad build could
  silence alarms), then the rebase after upstream #617.
- Not started yet: the owner asked to wait.

### Codebase and build speed (answers of 2026-10-11)

- **How much is still Juggluco:** of ~431k lines of own code, ~109k (25 %) are still
  Juggluco's: C++ 90k lines, 73 % original (90 % of the C++ that existed at the fork in
  2025-11 survives, and it grows, edited weekly); Java 67k, 60 % original; Kotlin 274k, 99 %
  new (49k of it two ported Sibionics algorithms). Still native at runtime: the reading
  store (every reading, the source for everything served out; the phone mirrors to Room
  for the UI), Dexcom/Libre/Accu-Chek/Air protocols and crypto, the settings block,
  Nightscout/LibreView upload, the web server, phone-to-phone sync; the old OpenGL graph
  is still compiled. Kotlin: all UI, alerts, journal, the Sibionics/AiDex/Ottai/Anytime/
  iCan/MQ drivers, phone↔watch sync. `Natives.java`: 729 native methods.
- **Build speed, main finding:** `BUILD_TIME` (`System.currentTimeMillis()` in
  `Common/build.gradle`) changes on every new configuration, so every new `--tests` filter,
  `-P` or worktree regenerates BuildConfig and recompiles everything, and the build cache
  can never hit (17 GB of dead entries). Making it stable (HEAD commit time) took a
  targeted test run from 56 s to ~12 s and a fresh worktree of a built commit from 170 s to
  51 s. 244 Gradle builds took 8.4 h in 4 days.
- Ranked: 1 stable `BUILD_TIME` (personal init script tested; also a small upstream PR);
  2 keep 2–3 persistent test worktrees and `git switch` in them instead of a new worktree per
  topic (cold 2m14–2m49 vs incremental 7–20 s); 3 Kotlin `-Xbackend-threads=0` (cold
  compile −21 %); 4 skip lint-vital on release builds (0.5–2 min each); 5 `test.sh` runs
  only the affected flavour / changed classes, full run before merging; 6 test
  `maxParallelForks` 4–6 (104 s → ~40 s, unmeasured); 7 at most 2 Gradle runs at once (six
  at once took 8–12 min each); 8 ccache for native (cold only); 9 Room `ksp` only for
  mobile (~8 s per wear build). No gain: `org.gradle.parallel`, more daemon memory,
  `--offline`, JDK 21 (only more Robolectric coverage), Defender exclusions.
- The tested init script (items 1 and 3) is `scripts/personal/juggluco-speed.gradle`.
  `test.sh` passes it with `--init-script` (this repo only, from 2026-10-11); builds take
  the same flag. Not installed in `~/.gradle/init.d/` (that would apply it to every Gradle
  build on the machine; owner's call).

### Fix list (from 2026-10-10)

**Status 2026-10-11 morning:** all implemented except A2 (owner checks the page) and D6's
optional extras; built and unit-tested, not device-tested. Branches: N1 `fix/nightscout`;
W1/W2/W4/W5 `fix/wear-dexcom-new-sensor` and W6/W7/item 7 `fix/wear-sync-startup` (both
merged into `feat/dexcom-g7-watch`); W3 `fix/wear-remove-native-sensor`; D1 and the watch
alarm clock on `fix/dexcom-g7-link`; D2 by #595; D3 `fix/connect-pending-latch`; D4
`fix/sensor-warmup-status`; D5 `fix/dexcom-warmup-signal-loss`; D6
`fix/sensor-handover-first-reading`; V1 `fix/glucose-display-conversion`; A1
`fix/sensor-expiry-expected-end`; P1 `fix/floating-island-centre`; the arrows
`fix/trend-arrow-shared` + `fix/wear-app`.

Owner-approved: **N1**. The rest were proposals, approved with the decisions below.
- **N1 Nightscout:** read treatments when the app comes to the front; optionally a
  reading-triggered read at most every ~15 min (upstream; `fix/nightscout`).
- **W1–W4 watch handoff** (`feat/dexcom-g7-watch`): hand over the newest active G7, not the
  selected one; rebuild a non-Dexcom driver after `dexAdoptSensor` and re-arm the claim;
  end the native record on removal/forget (upstream part on its own `fix/` branch; short-name
  matching on `fix/wear-sensor-alias`); write `sensor.<node>` with `dexcom.<node>`.
- **W5** `ensureSensorShellInternal`: never move a Dexcom record's start (then toggle Direct
  once on the devices).
- **W6** direct-reading status: only for an accepted new reading, with its own time; first
  direct reading reported at once.
- **W7** startup sync: request the missed slots from the other device when first heard after
  start (phone and watch); with item 7.
- **D1** G7 asked too early: re-ask once or twice within the session when the reply is not
  newer (`fix/dexcom-g7-link`).
- **D2–D6 upstream sensor-change UX:** warmup retry on a wakeup alarm (in #595); a pending
  connect no longer blocks a newer one; countdown until first reached, then "Waiting for
  first reading"; no loss-of-signal during warmup; handover waits for the new sensor's first
  reading.
- **V1** one mg/dL → mmol/L conversion for every displayed value (upstream).
- **A1** expiry alarm on the expected end for native non-Sibionics sensors (upstream).
- **A2** persistent low: owner checks the duration on the page (20 min was in effect);
  decide margin/threshold or display-precision compare.
- **P1** pill: centre on the cutout + signed nudge; label gap along the edge (upstream).

### Reading timing between phone, watch and the G7

- **21:20 value (bug, `feat/dexcom-g7-watch`, proven).** It is the 21:22:22 reading
  stamped 107 s early by the watch. After the 21:19:59 adoption, the first synced reading
  went through `WearSync2.onChunk` → `Natives.ensureSensorShell(serial, t − 3600)`
  (WearSync2.kt:615); `ensureSensorShellInternal` (g.cpp:1786-1792) moves the start
  earlier with no Dexcom guard (`fix/dexcom-stream-slots` guarded only
  `ensureDirectStreamShell`, sensoren.hpp:493). Start became 1791677541 (−107 s); the
  watch's backfill stamps start + G7 seconds, so slot 13 became 21:20:35 and every
  backfilled slot moved −107 s; the phone took 21:20:35 from the watch (ingest counts
  match). Fix: no start move for a Dexcom record with a start; optionally backfill never
  overwrites a filled slot. **After the fix the watch's current record keeps the wrong
  start until adopted again: toggle "Direct sensor on watch" once.**
- **Status text at ~21:24.** "Phone: reading, last 1 min ago": `markLocalReadingAccepted`
  runs before the stale check (SuperGattCallback.java:1169 vs 765), so the useless 21:22
  session (G7 answered with the previous reading, `age=300`) counted. "Watch: getting
  values from the phone": its report went out before its first direct reading, then the
  4-min `REPORT_MIN_INTERVAL_MS` held the next one. Fix: note a direct reading only for an
  accepted new reading, with the reading's own time; report a first direct reading at once.
- **21:27 phone 84 s late (race, `fix/dexcom-g7-link`).** The phone connected at :x1.3,
  about 1 s before the G7's new value was ready, and got the previous reading
  (`age=300`), twice in 1003 sessions (21:22, 21:27), both right after the watch joined
  (phone sessions moved from :x2.9 to :x1.8–:x2.1). It caught up by the designed loss-alarm
  sync at 21:28:42. Fix: in `DexGattCallback.getdata`, if the reply is not newer, ask again
  after ~1 s while still connected (≤ 2 times; the G7 keeps the link ~3.9 s).
- **Phone restart misses a reading (gap).** 10-09 the phone rebooted (trace stops
  09:07:09, NG back 09:09:03): stood down 09:09:05–09:09:36 (item 7), never asked the
  watch (the pre-reboot loss alarm was lost; at startup `setLossAlarm` arms only with the
  loss-of-signal alarm enabled, SuperGlucoseAlarms.java:57; the watch doesn't push a
  sensor both read). Got 09:07:41 from the G7's own backfill at 09:12:50. Fix: when the
  ownership runtime first hears the other device after start, `WearSync2.requestSync()`
  once if it reads the sensor (only missing slots); also fix item 7.
- **Watch boot.** The app does start at boot (`NumAlarm` BOOT_COMPLETED, upstream): watch
  rebooted 00:12:19, NG scanning 00:13:25, service 00:13:35. "No value" because it missed
  00:12:47 while rebooting and nobody asked the phone; opening the app synced. It would have
  recovered at its next G7 session (~00:17:54). Same startup-sync fix. Minor upstream gap:
  no `MY_PACKAGE_REPLACED`/`LOCKED_BOOT_COMPLETED`.
- **Complication slow (can't tell).** NG side is prompt: phone sends within ~1 s, Play
  services delivers in ~1 s, `WearSync2.onChunk` asks all complication sources at once.
  Suspects: Pixel display offload in ambient; the record churn that evening; and by design
  when both read, a missed watch reading shows "No value" from reading + 330 s while the
  loss-alarm sync comes at + 380 s (fire it before the stale point). Next: one evening with
  a trace-enabled watch build logging `GlucoseComplicationData.currentReading()`.
- **Nightscout latency (10-07 → 10-10, 993 live readings):** the phone's receipt to the
  server's `lastModified`: median 69 ms, p90 130 ms, p99 158 ms. The G7 hands readings over
  3–5 s after measuring them (`age=`), so sensor → Nightscout is ~4 s, almost all Bluetooth.

### Alarms and Nightscout

- **Sensor expiry alarm (upstream, proven).** `SensorExpiryAlertState.resolveSensorExpiryEndMs`
  (~38-56) uses `Natives.getSensorEndTime(dataptr, true)` = the official end, on purpose
  since f2f9cd879. It fired 07:56:42 ("expires in 1 hour") for an official end of 08:53:59;
  the real end was 20:53:59. The dashboard countdown already uses the expected end. Fix
  (upstream PR, `fix/sensor-expiry-expected-end`): native non-Sibionics sensors use the
  expected end (G7/ONE+/Stelo and Libre 2 +12 h; Libre 3/Accu-Chek/Air unchanged;
  Sibionics and managed drivers stay on the official end, their expected end is
  advisory). Pure helper + test.
- **Persistent low 08:52: correct per its spec, but the duration in effect was 20 min,
  not 15** (WAIT logged at 15:00 elapsed at 08:47:48; the 10-07 firing came at exactly
  15:00). Check the setting on the page; if it shows 15, `feat/alarm-settings-save` lost
  it. Readings: 08:17 81 mg/dL (4.5, ends the walk), 08:22/27 79 (4.389, shown 4.4),
  08:32 70 (3.889, shown 3.9: start), 08:37 76, 08:42 73, 08:47 68 (3.8), 08:52 70
  (fire). Both devices rang on the 08:52:48 reading, watch ~3 s first; dismissed 08:53:01.
  Surprise: a reading shown "3.9" counts as below 3.9, and a "4.4" does not count as
  recovered (raw mg/dL compare; upstream's Low/Very low do the same). Options: re-arm
  margin 0.2 (would not have rung), threshold 3.8, or compare at display precision in
  `PersistentLowPolicy` (and raise upstream); add "below 3.9 since 08:32" to the text.
- **Nightscout: no gap 10-08 17:00 – 10-10 22:00** (634 uploads, all 201, each within
  ~1 s of its reading). The 30-min hole was **10-08 09:20–09:50**: the phone was on
  mobile data, "Upload only on Wi-Fi" held 6 readings, all sent at 09:50:40 when Wi-Fi
  came back. As designed (the server 10.0.0.230 is home-only). Nice to have: "waiting
  for Wi-Fi (N queued)" in the status. Treatments from Nightscout are received only on a
  full pass (leaving the app, a journal change, a network change, Send now), up to 7 h
  apart; a reading could also trigger a receive (5-min floor) — upstream code,
  `fix/nightscout`.
- **Seen live 2026-10-10 23:04:** a 4 U dose entered in Nightscout (owner on Wi-Fi) did not
  appear in NG. Confirmed in code: a reading wakes the uploader with `mask=stream` only;
  treatments are read on `wakeall`/`wakenums`/`waketreatments` (`uploader.cpp` ~1194-1200),
  raised by leaving the app (`MainActivity.onPause` → `wakeuploader`), a journal change, a
  network change, Send now/Resend. **Opening the app does not read.** Fix: also wake a full
  pass when the app comes to the front (cheap; covers "open NG to look"); optionally a
  reading-triggered receive at most every ~15 min with "Receive treatments" on (each read is
  the newest 240 documents).

### Values and arrows

- **Trend arrow (upstream `logic/TrendEngine.kt`, same on main):** the last 20 min for a
  G7 (window 20 min × cbrt(interval / 5 min), 8–25 min), so 4–5 readings, no prior
  smoothing; weighted least-squares slope in mg/dL/min, weights exp(−age/25 min) (1, .82,
  .67, .55, .45); a jump > 20 mg/dL/min cuts the window. Flat ≤ 0.5 mg/dL/min, then the
  arrow turns smoothly to 45° at 1 and vertical at 2, double above 2 (≈ 0.14 / 0.28 /
  0.55 mmol/L per 5 min). The G7's own trend byte is only a fallback. Phone dashboard,
  notification, widget, AOD, pill and (personal) watch use the same rate; the alert
  engine uses smoothed data if "smooth all data" is on. Leftover: the watch alarm screen
  still draws xDrip-style text arrows with cuts at 1/2/3 (`MainScreen.kt:130`).
- **0.1 gap (item 8; upstream bug, proven).** One reading, three conversions: phone
  stored rows × 0.0555f; watch stored rows ÷ 18.0182f (`NotificationHistorySource.kt`
  94-97); the live value ÷ 18 (`SuperGattCallback.java` 1120/1161, native CONV18,
  `CurrentGlucoseSource.getFromNative`). The live value is 0.1 higher at 100, 109, 118,
  127, … mg/dL (12 % of readings); 100 mg/dL also differs phone 5.6 / watch 5.5.
  Complications show the live value when they don't find the stored row within 60 s; the
  watch main screen shows only stored rows. 10-07 23:14 was 127 mg/dL: 7.0 vs 7.1. Fix
  (upstream PR): every displayed value through `GlucoseFormatter.displayFromMgDl`
  (watch history, the live lanes, no parsing of native's ÷18 text) + a 40–400 mg/dL test.

### Floating pill in the landscape island (upstream code, b8734db87)

The offset slider (dp, 0..50, default 8, key `floating_island_vertical_offset`) is the
distance from the screen edge to the pill's near side. Centring on the camera needs
`camera centre − pill thickness / 2`; the thickness is the pill's height in portrait and
its width in landscape (118 px), so no single value fits both, nor a font or digit
change. Plan: centre the pill on the cutout's centre line (from `safeInset` and the rect
extent) and turn the slider into a signed nudge (−20..20 dp, new key, default 0; expect
about +1 dp on the S26 in both orientations). Side bug: the label gap uses the rect's
width in landscape (111 px) instead of its extent along the edge (64 px). Both are
upstream fixes; ~60 lines + tests.

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
  Built instead: the Now Bar live notification (option, off by default) (dropped 2026-10-08, not wanted) and the widget
  rework (Glance gone: this was item 5's battery cost), so a 1-row widget can go on the
  lock screen with Good Lock LockStar.
- **Quick entry:** no last-dose/last-food line on an existing entry; reminder now reads
  "Toujeo due at 21:00 / Last dose: 18 U, yesterday 21:04".

To test after the next install:
1. Widgets: existing widgets after the upgrade (may show "can't load" until the first
   render), resize both, Settings screen (long-press and Settings → Widgets), screen
   off → on, stale look, LockStar 1-row size. Battery: a run to compare with run 3.
2. Now Bar: Notification settings → Live notification. (Dropped 2026-10-08.) If nothing shows, Developer
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
9. **Dashboard chart scrolls about a day into the future** (seen 2026-10-08 on the build
   installed 17:14). Review fix #30 (8947508aa, `fix/dashboard-chart-bounds`) moved the
   right edge to 10 min past the latest journal content, which includes the nightly
   long-acting dose's activity curve (24–42 h). Fix: leave long-acting insulin out of that
   limit; keep rapid insulin and meal curve ends and future-dated entries; cap the whole
   limit at a few hours ahead. With nothing active: now + 10 min (or the prediction
   horizon), as the owner asked.
10. **Hard to scroll down the dashboard page** (owner, 2026-10-08): a vertical swipe keeps
    resizing the chart instead of scrolling the list. The swipe on the chart itself already
    passes to the page (1bcd40285); what still catches it is the list's nested-scroll
    expansion of the chart (`DashboardScreen.kt` ~1146-1243,
    `DashboardChartExpansionGestureGate.kt`): pulling at the top grows the chart, and the
    first scroll down shrinks it before the list moves. Options to discuss: resize only
    with the handle (drop the pull-to-resize gesture) so a page swipe always scrolls; or
    keep pull-to-grow but never let a downward page scroll shrink the chart.
11. **Shared snooze/dismiss/quiet window: two ordering holes left** (Copilot, round 3 on
    3343abb12; `feat/alarm-routing`; details in REVIEW-FIXES.md "Round 3"). Health Connect
    and Nightscout round-2 fixes passed; the hybrid clock is correct when both entries carry
    ids, but: (a) P1 a dismissal from the other device clears the local snooze with
    `id = null` (`AlarmSilenceSync.kt` ~479-485), so that cancellation falls back to
    arrival-time ordering (`AlarmSilenceModel.kt` ~195-201) even between matching builds;
    snooze on one device and dismiss on the other a second apart, with delayed replies,
    leaves one device snoozed and the other not. Fix: the cleared snooze keeps the
    ORIGINAL dismissal's id (also through delayed rechecks); the test simulator must clear
    the snooze too, as production does (`AlarmSilenceSyncTests.kt` ~453). (b) P2 state kept
    from before the upgrade (`_rev` entries, id-less entries) stays id-less
    (`AlarmSilenceSync.kt` ~163-165 reads only `_hlc_ms`), so a delayed startup snapshot of
    an old quiet window can override a newer cancellation. Fix: migrate retained entries to
    ids (or treat them deterministically), plus a regression test: active quiet window kept
    through the upgrade, delayed startup snapshot, new cancellation. Until fixed: a quiet
    window or snooze set before this build may not sync a quick cancel; re-check both
    devices after cancelling.

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
