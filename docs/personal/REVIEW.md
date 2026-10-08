# Fork review (2026-10-08)

Six read-only reviewers went through all 17 topic branches after the sync with upstream
(`main` = b8f3e2e3b). This is the merged result, ranked. Line numbers are on each branch tip
of that day. Findings marked **(verified)** were checked by hand in the code; the rest are the
reviewers' reading and should be confirmed while fixing.

Legend: **[A]** affects the owner's own setup now · **[O]** affects other users / upstream only.

## 1. Safety and behaviour (fix before anything else)

### Alarms
1. **[A] Persistent low can stay silent for hours after a Very low** (blocker).
   `PersistentLowPolicy.kt:146-149`: `veryLowSeen` latches for the whole episode. Very low
   fires and is dismissed, glucose then sits between Very low and the Persistent-low line
   (e.g. 3.4–3.8), LOW is off at night: nothing rings until recovery to threshold + margin.
   `PersistentLowPolicyTests.kt:314` pins it. Fix: hold only while Very low is active; when
   its episode ends, restart the timer at that reading.
2. **[O] Routing: a held firing uses up the episode** (blocker for "Watch when connected").
   `AlertRuntimeManager.kt:850,885`, `AlertStateTracker.kt:109`: the phone holds a Very low
   for the watch; if the watch then leaves reach, goes on its charger or dies, the phone never
   rings for that episode. Not escalation (which the owner ruled out): fix by keeping the
   episode pending and re-checking `ringsHere` on each reading.
3. **[O] Routing: "reachable" is only the capability node list** (`AlarmRouting.kt:123-127`),
   non-empty while the watch app is dead; charging state trusted up to 31 min
   (`SensorOwnershipRuntime.kt:596-599`). Fix: require a fresh watch report (≤ 2 reading
   intervals) with a recent `lastReadingMs`, else the phone rings.
4. **[O] "Screen only" + "Watch when connected" silences a night Very low on both devices**
   (`WatchAlarmStyle.kt:61`). Fix: forbid the combination or exempt lows.
5. **[A] Alarm settings draft left unsaved by Home/recents/process death**
   (`AlertSettingsScreen.kt:196`, `LeaveGuard`): switch an alarm on, press Home, and it stays
   off with nothing saying so outside the page. Fix: a notification or dashboard banner
   "Unsaved alarm changes" while a draft is dirty (or auto-save on stop).
6. **[A] Draft Save can revert changes made elsewhere** (`AlertSettingsDraft.kt:119,156`):
   an edited alert is kept whole; a change stored meanwhile to the same alert (watch toggle,
   `setLowAlarm`, Very low/high sliders) is undone on Save. Fix: per-field three-way merge.
7. **[O] Quiet window now syncs in "Both" mode too** (`QuietWindow.kt:211,245,252`): a
   phone tile now silences the watch for existing users. Owner wants it shared; upstream: opt-in.
8. **[O] wear-alarm-settings-sync syncs only `c:` lines**; the `g:` line (same-direction
   suppression, acknowledged-high coverage) is on alarm-routing, so on its own the watch keeps
   its defaults and can suppress what the phone rings. Move `g:` (those two keys) here.

### G7 (Dexcom)
9. **[A] GATT client leak on stand-down** (the "two GATT clients" of NEXT item 7).
   `DexGattCallback.java:246` / `SensorOwnershipRuntime.release()`: with `stop` set, a
   DISCONNECTED/CONNECTED callback only releases the wake lock and returns; the BluetoothGatt is
   never closed. Exists on main; autoConnect makes it near-certain. Fix: `close()` in the stop
   branch; `release()` calls `closeGattTransport()` and sweeps `mygatts` by key.
10. **[A] `auth != 1` now aborts fresh pairings on phones too** (verified, `DexGattCallback.java:690/702`; main has `if(!newcertificates&&auth != 1)`):
    the old `!newcertificates&&` exception existed for that case. **Check before the owner's
    next sensor change (about 2026-10-10)**; limit the change to the watch / slot 3.
11. **[O] Receiver-slot fallback** (verified, `DexGattCallback.java:545,696`): a refused slot-3
    pairing on the watch switches it for good to channel 1, the receiver/pump channel, which
    can push a Dexcom receiver or an AID pump off the sensor. Remove; show a pairing error.
12. **[O] Watch-only / legacy direct-sensor users stop reading** (verified,
    `SensorOwnershipRuntime.kt:527-531`): on the watch every alongside-capable G7 is blocked
    unless `WearSensorClaim.isDirectRequested()`, default false. Fix: block only when a phone
    companion is present and reading, or migrate the legacy flag.
13. **[O] Possible ABBA deadlock**: `blocksLocalConnection` runs under the callback monitor and
    takes `gattcallbacks` via `findGatt()`/`key()`; `updateDevicers` holds `gattcallbacks` and
    locks callbacks. Pass the callback in; keep `mygatts()` out of `key()`.
14. **[O] No fallback from autoConnect** (`DexGattCallback.java:194/362/386`): phones/watches
    with low-duty background scanning can miss sessions forever; `getalarmclock()` silently
    skipped; global autoconnect users take the new paths for unbonded sensors. Gate on bonded;
    after one missed session, direct connect.
15. **[A] 7 s wake lock every 5 min never released early** (`DexGattCallback.java:222`, ~84 s/h on
    the watch). Release once the connect is issued.
16. **[A] `dexcom/java.cpp:669` wipes every slot when the handed start differs by any amount**
    (exact equality); `becomeDexcom` hard-codes a 10-day `wearduration` (wrong for 15-day G7,
    Stelo). Tolerance; one shared header helper.

### Nightscout / Health Connect / quick entry
17. **[O] Deleting or editing a treatment received from Nightscout writes to the server**
    (verified, `JournalRepository.kt:930-933`), whoever created it: AAPS/Loop/Trio boluses
    arriving through Nightscout can be invalidated (closed-loop IOB risk). Never write to
    documents from loop systems (`enteredBy`/`app`/`pumpId`/`isSMB`); make write-back opt-in.
18. **[A] A refused edit (403/422) blocks every newer upload forever**
    (`JournalTreatmentUploader.kt:487-496`, `break` + 4 h backoff). Treat 4xx except 408/429 as
    final and `continue`.
19. **[A] Any 5xx or non-JSON answer is "wait", never counted** (`:219-223`). Only timeouts and
    502/503/504 are transient.
20. **[A] Server clock vs phone clock** (`:1016`, `srvModified` vs `updatedAt`): a LAN server
    ahead of the phone silently discards the user's edit. Compare with the received
    `srvModified` or allow skew.
21. **[A] Health Connect activity import churns** (`HealthConnection.kt:211,247,410`): every
    foreground (and every process start) re-upserts 14 days, `updatedAt=now` → wakes the
    Nightscout uploader, re-uploads every activity row, overwrites edits, resurrects deletions.
    Skip already-imported records; keep the last-run time on disk.
22. **[A] Entry sheet composes five hidden copies of itself to size it**
    (`JournalCompose.kt:579`, `JournalQuickEntry.kt:83`), each with effects, a 30 s ticker and
    Room loads. Use a fixed/min height; drop `sizingOnly`.
23. **[O] Quick log buttons default on** (`QuickLogButtons.kt:38`, `Notify.java:4295`): every
    user, including pump users, gets two notification actions and launcher shortcuts after the
    update; two PendingIntents rebuilt per reading. Default off; cache.
24. **[O] Migrated presets get a 1 U step** (`HistoryDatabase.kt:794`; −/+ was 0.5 U for all).
25. **[O] Reminders dropped silently** without notification permission / exact alarms
    (`InsulinReminders.kt:362`); doses matched by title across presets with the same name.

### Display, charts, watch
26. **[A] Details card shows an old reading as fresh while left open**
    (`FloatingGlucoseOverlay.kt:753`; it no longer auto-closes). Drive it from the reading
    flow; age on the freshness clock.
27. **[A] Watch complications: the trailing throttle uses `Handler.postDelayed`**
    (`UiRefreshBus.kt:71`, uptime): a dozing watch holds the deferred update until it wakes —
    the symptom the freshness fix set out to cure. Never throttle a newer reading time.
28. **[O] App's own watch face reads 50 min of history on every frame**
    (`GlucoseComplicationData.kt:69/105`, `WatchRenderer.kt:306`). Compute the rate only in
    arrow sources, cached by (sensor, reading time). (The owner uses Google's "Active" face.)
29. **[A] Three "same reading" rules** (30 s `SAME_READING_MS`, 60 s `MATCH_WINDOW_MS`, minute
    buckets): a live reading 31–60 s from its row is still counted twice. One shared constant;
    `storedTimeOf` should check only the last rows. The `augmentHistory` change also moves the
    phone's alert and broadcast rate when timestamps differ (native fallback, Sibionics):
    needs phone tests and its own PR.
30. **[A] Chart: journal curves cut at now + 10 min** (`DashboardChartViewportBounds.kt:23`):
    insulin activity curves and future-dated entries can't be panned to. Include the latest
    marker end in the limit.
31. **[A] Chart: readings inside the padding are clipped** (12.9 on a 0–13 range).
    Widen when within `edgePadding`, or inset the clip by the dot radius.
32. **[A] Chart: scrolling the list stores "collapsed"** (`DashboardScreen.kt:1299`), losing
    the remembered height. Store only on handle release.
33. **[O] Dots merge into a band for 1-minute sensors** (`ChartReadingsStyle.kt:49`, radius by
    duration only). Cap by the pixel gap between readings.
34. **[O] Floating defaults on for existing users** (`tapShowsDetails`, `showNextReading`), and
    widgets' `rangeColors = true` changes placed widgets' colour on upgrade.
35. **[O] Accessibility service stays enabled when floating glucose is off**; Android 13+
    "restricted setting" for sideloaded APKs isn't explained.

## 2. Upstream readiness (no behaviour change)

- **Split** (from the fork-wide reviewer; commits named there): floating-glucose (8 features),
  nightscout (5 topics), quick-treatment (4: DB v33 dosing / sheet / reminders / outside-app
  logging), alarm-routing (persistent-high fix / routing / shared silence / watch style),
  g7-watch (slots+gap fill / identity / both-read handoff / reader UI), glucose-display
  (5 unrelated topics; its "Searching" fix belongs with G7), wear-app (UI vs freshness/trend;
  the shared trend-rate change its own PR), chart-bounds (clamps / no y-drag / handle).
- **Duplicates**: `EpisodeHistory` (+ CurrentDisplaySource/WearToggleSync hunks) on
  persistent-low and alarm-routing → persistent-low owns them; `TrailingThrottle` on wear-app
  and glucose-display → wear-app owns it; two `GlobalAlertSettings` (settings-save vs routing)
  → settings-save introduces it, routing extends it and takes over the personal glue commit;
  three Δ walk-backs (`FloatingDetailsSource.delta`, `WidgetDelta`, `GlucoseDelta.latest`) →
  one; `LiveGlucoseGauge` re-implements band classification.
- **Must not go upstream**: the owner's sensor serials (12147739749 / 8958912147739749) in
  g7-watch code, tests and a commit message; "owner" wording in tests and commit bodies;
  device anecdotes; the temporary "Next reading style" picker; the per-tap trace lines and a
  new `e.printStackTrace()` in FloatingGlucoseService; the "(experiment)" commits; the
  FloatingPillWatchdog (unproven); widget-rework's personal `+Insulin/+Food` glue.
- **Translations**: `values-hu` (new upstream) lacks ~150 of our strings; two orphaned hu
  entries; a dozen new strings duplicate existing ones (`%1$d h`, Phone/Watch, Both, Discard…).
- **Comments**: 10–25 % of added lines are comments; ~80 narrate history or the owner; worst
  blocks: AlarmSilenceSync.kt:16 (51 lines), PersistentLowPolicy.kt:35 (39),
  PersistentHighPolicy.kt:24 (34), AlertConfigSync.kt:16 (32), AlarmHistory.kt:15 (26).
- **Commits**: squash fixup chains (listed per branch by the fork-wide reviewer); imperative
  subjects for PR titles.
- **Tests**: thin on risky logic in g7-link (0.22 test/code), settings-save (0.27),
  widget-rework (0.27), quick-treatment reminders scheduling, floating window lifecycle.
  Several tests assert source text (brittle); replace with behaviour tests.
- **Product decisions upstream must agree to**: stale pill dimming vs #570's "---"; removing
  the chart's manual y-scaling; band colours for value text; the floating frame clock (seen
  only on One UI).

## 3. Recommended upstream order (fork-wide reviewer)

g7-link (after items 9, 14, and the generic battery/Wi-Fi/scan commits split out) → floating
notification channel fix → wear UI fixes → wear freshness/trend → chart-bounds →
health-connect → Nightscout robustness → Wi-Fi only → deletes → follower → received-treatment
edits → range colours → coloured status icon → G7 "Searching" → dots → wear-alarm-sync (with
`g:`) → persistent-low (owns EpisodeHistory) → persistent-high → settings-save (owns
GlobalAlertSettings) → routing → shared silence → watch style → alarm-history → g7-watch (4
PRs) → now-bar (gauge split off) → quick-treatment (4 PRs) → widgets (2 PRs) → floating
features one PR each → `+Insulin/+Food` in the floating card.
