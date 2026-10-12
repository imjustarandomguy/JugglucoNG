# One current reading per device: plan (2026-10-11)

Owner's requirement: every surface (watch app, complications, pill, widgets, phone app,
notification, …) shows the same value and arrow, updated at the same moment, from one
pipeline, without per-surface timers. Plan only; nothing implemented. Analysis read-only on
`personal` c7e3318f7 and upstream's `docs/architecture/direction.md`,
`notification-modernization.md`, `storage-ownership.md`. Rows marked † in the table were
first taken from BUILD/NEXT; the appendices below re-check every surface in code (phone,
dashboard/pill/widgets, outbound, watch) and supersede the † cells.

## What exists

- `CurrentDisplaySource.resolveCurrent` is a pull resolver (live value + stored history,
  smoothing, calibration, view mode → `Snapshot` with the smoothed "alert" rate). About 50
  call sites resolve on their own, at their own moment and thread.
- Two arrow rates: `Snapshot.rate` (smoothed, alert) and the dashboard's display rate
  (`DisplayTrendSource.resolveDisplayArrowRate`, rows); `WearSync2.onChunk` has a third
  (35-min window) for peer readings.
- `UiRefreshBus.requestDataRefresh` (`DataChanged`, 53 call sites: readings, journal,
  calibration, pens, settings…), a 20 s complication throttle, a 1 s broadcast coalescer.
- Staleness: five clocks with three deadlines (complications: exact alarm at 330 s;
  notification: Handler at 330 s; pill: 30 s recheck; widgets†: the loss alarm; AOD: periodic
  tick; dashboard: 1 s ticker). `DisplayDataState` (upstream) classifies, few use it.
- Phone→watch: `WearSync2.pushTail` (45 s throttle) skips a sensor `bothRead` (the peer said
  it holds the sensor within 31 min), so the watch may get a reading only from its loss-alarm
  sync (~320 s): the owner's "late complication" of 2026-10-11.

| Surface | Value | Arrow | Redraw trigger | Own timers | Stale rule |
|---|---|---|---|---|---|
| Phone notification + status icon | resolve (`Notify.java`) | snapshot/display | direct `normalglucose` from `dowithglucose` + 1 s coalesced refresh | Handler deadline 330 s, screen-on/clock reconcile, coalesce, retry | 330 s, value kept, no arrow |
| AOD | resolve (`AODOverlayService:544`) | snapshot | its own broadcast from `dowithglucose` + revision | periodic refresh, 100 ms lock loop | `glucosetimeout` |
| Legacy `Floating.java` | resolve | shared angle | `postInvalidate` in `dowithglucose` | its own | † |
| Pill + card | resolved in `FloatingGlucoseService` | display rate | service callback | `FloatingPillWatchdog` (30 s, 3 s after a reading), staleness recheck, frame clock, ring | † dimmed |
| Widgets | `WidgetDisplaySource` → resolve | display rate | broadcaster listener (1 s) | † loss alarm | † loss-alarm time |
| Dashboard hero | several resolves in the ViewModel | display rate | `UiRefreshBus.events` | 1 s age ticker | † |
| Watch complications (8 services) | `GlucoseComplicationData.currentReading` + some services resolve themselves | display rate (cached by revision) | throttled bus AND a direct `updateAll` for own readings; phone-pushed only the throttled path | exact alarm at reading + 330 s, 20 s throttle | 330 s → "No value" |
| Watch main screen, ongoing activity, alarm screen | own resolves | display rate | † | † | 330 s |
| Outbound (xDrip, GDH, Garmin, web server, Nightscout) | exchange resolver (smoothing "collapse" can lag an interval) | broadcast/exchange rate | once per reading | minute gate | none |

## Where they diverge

1. **Value:** a surface that resolves between the history write and the live value shows
   the previous reading; outbound may carry an older smoothed value; formatting paths still
   differ in places (`WearSync2`, the `notGlucose` text).
2. **Arrow:** alert rate (AOD, exchange, older paths) vs display rate (dashboard,
   complications, pill) vs WearSync2's own; they differ near the flat band.
3. **Timing:** notification direct, widgets after 1 s, AOD by broadcast; readings from the
   peer skip the direct paths; on the watch own readings refresh twice, pushed ones once and
   throttled; a skipped push costs ~320 s.
4. **Staleness:** five clocks, three deadlines.
5. A 6–25-min-old history row can come back as a current snapshot to surfaces that don't
   re-check its age.

## Design: `CurrentReadingHub` per device

- **`DisplayedReading`** (immutable, `src/main`): sensor, time, mg/dL, display value and
  text through one `GlucoseFormatter`, lanes per view mode, ONE display arrow rate and the
  `TrendArrowAngle` arrow, freshness (FRESH / STALE / NO_VALUE / AWAITING / NO_SENSOR from
  `DisplayDataState`), `staleAt`, `noValueAt`, `nextExpectedAt` (the pill's ring), origin
  (LOCAL / PEER / BACKFILL / FOLLOWER), monotonic revision. The alert rate stays inside the
  alert engine and is never drawn.
- **Hub:** `onReadingAccepted(sensor, time, origin)` and `onDisplayInputsChanged(reason)`;
  recomputes once on one worker (calling the existing `CurrentDisplaySource` and display
  rate), publishes to a `StateFlow` + Java listeners only when visible fields change, with a
  reason (NEW_READING, BACKFILL, STALE_TICK, SETTINGS, STARTUP); only NEW_READING drives
  alertwatch delivery and broadcasts.
- **One clock per device** (`ReadingClock`): one wake at min(staleAt, noValueAt) → republish
  STALE_TICK; reconcile at screen on, time/zone change, process start; behind a `Scheduler`
  interface (fake clock in tests). Generalises `ComplicationFreshness` (watch, exact alarm)
  and Notify's freshness deadline (phone, Handler).
- **Surfaces render, never resolve:** adapters registered at startup (notification keeps its
  1 s coalesce; widgets draw at screen on; complications `requestUpdateAll` per revision,
  throttle only to fold backfill repeats; exchange/broadcast on NEW_READING). No surface
  owns a staleness timer.
- **Watch:** the same hub, fed by its own G7 reading or by `WearSync2.onChunk` when the
  newest slot is newer; same clock (exact alarm).
- **Push rule:** the device that accepted a reading pushes the newest slot at once (no 45 s
  throttle for new slots); the receiver already drops slots it has. Either always push
  (simplest, ~1 small message per reading per direction) or skip only when the peer
  acknowledged that slot. The loss-alarm sync stays as a safety net.
- **Upstream fit:** this is `notification-modernization.md` N2/N3 ("dashboard and
  notification consume the shared resolved state"), extended to every surface; builds on
  `CurrentDisplaySource`, `DisplayDataState`, `UiRefreshBus.revision`, `TrendEngine`,
  `GlucoseFormatter`, N1's deadline and update reasons (#485, #504); P1/P4/P5/P6/P8. It is
  a structural track (D4: needs the maintainer's slot; the UI track #584–#612 is active);
  the push rule touches §8.3 ownership/handoff.

## Migration (each step shippable alone)

| # | Step | Where | Size | Risk |
|---|---|---|---|---|
| 0 | Characterization harness (fake clock and history, every surface's value/arrow/stale functions; pin today's divergences) | test-only, upstream | M | none |
| 1 | `DisplayedReading` + hub + `ReadingClock`, published from `requestDataRefresh(readingTime)`; no consumers | upstream (N2) | M (~400 lines) | low |
| 2 | Watch complications, main screen, ongoing activity, alarm screen read the hub; `ComplicationFreshness` becomes the hub clock; drop the direct `updateAll` and per-service resolves | `fix/wear-app`, then upstream | M | medium |
| 3 | Phone notification + status icon from the hub; Notify's freshness runnable → `ReadingClock` | upstream N3 | M | medium |
| 4 | AOD, legacy `Floating`, dashboard hero | upstream | S each | low |
| 5 | Pill/card and widgets from the hub; drop the pill's 30 s recheck and `FloatingPillWatchdog` (keep the frame clock), the widgets' loss-alarm stale look | personal | S–M | low |
| 6 | Single fan-out: every reading origin calls `hub.onReadingAccepted`; remove the AOD broadcast, `postInvalidate`, duplicate refreshes | upstream | M–L | medium |
| 7 | Push rule (always push newest or ack-based); no 45 s throttle for new slots | `feat/dexcom-g7-watch` | S–M | medium |
| 8 | Cleanup: complication throttle → backfill folding only, `syncedReading` fallback, optional `LossAlarmAhead`, freshness parts of `fix/wear-app`, broadcaster's widget listener | mixed | S | low |

Acceptance: for one scripted reading every adapter receives the same revision, text, arrow
and freshness; after a fake-clock advance to 330 s every adapter gets STALE in one publish.

## Owner's decisions (2026-10-11)

- **Stale = the next reading was due and did not come**, from the sensor's own cadence, not a
  fixed 330 s (which lets a 1-min Libre miss five readings and still look fresh):
  - deadline: last reading + the sensor's interval (declared by the driver: G7 5 min,
    Libre 1 min; else the median of recent gaps) + ~20 s grace (measured jitter: phone
    session 1–3 s after the slot, re-ask +1–2 s, background connect up to 13 s, the peer's
    push a few s after its session; a 6 s grace would flicker);
  - event: a session that ends without a new reading (G7 answered with the previous one
    after the re-ask) marks the slot missed at once, after a few seconds' wait for the
    peer's push;
  - sensor states (warmup, ended, error) win over a dimmed number;
  - **"---" at 30 min**;
  - one deadline drives every surface; the pill's next-reading ring turns late at the same
    moment.
- **Push rule:** push unless the watch already reported reading that slot (push whenever in
  doubt) — `fix/wear-sync-push-unless-read`, being built 2026-10-11.
- **Order:** slice 1 = the watch (shared reading + one clock for complications, watch face,
  main screen, ongoing activity; the push rule), after the 2026-10-11 watch fixes are
  installed and confirmed; slice 2 = the phone surfaces.
- Effort: about 1–2 days of agent work in total; the real cost is device testing.
- Slice 2 (phone), started 2026-10-11, tested together with slice 1: the phone's stale tick is
  an **exact wake-up alarm** (`setExactAndAllowWhileIdle`, not an alarm clock); **outbound
  partners (xDrip/GDH/AAPS/Garmin/web server/Nightscout) left alone** (their payload and
  trend cut-offs stay). Branches: `feat/phone-current-reading` (from
  `feat/current-reading-hub`: notification, status icon, AOD, legacy Floating, alarm screen,
  dashboard hero, single fan-out, phone clock), `feat/floating-current-reading` and
  `feat/widget-current-reading` (personal topics + the hub).

Remaining open questions (from the plan):

1. "Same time": proposed ≤ 1–2 s on the device that read, ≤ 5 s on the other; the
   notification keeps 1 s coalesce; widgets draw at screen on; an ambient watch face may
   redraw only on its minute tick.
2. One stale look: at 330 s value dimmed, no arrow, age shown (upstream N1); "---" later
   (15? 30 min?). Today complications say "No value" at 330 s, the pill dims.
3. One arrow rate for outbound partners too (xDrip/GDH/AAPS)? Maintainer territory.
4. Push rule: always push, or ack-based skip. (A first fix, "push unless the watch reported
   reading that slot", is being made on 2026-10-11 for the late-complication case.)
5. Stale tick on the phone: exact wake-up alarm, or Handler + reconciliation (upstream's
   Doze stance).
6. Upstream: propose as N2/N3 (needs the maintainer's slot), or keep steps 2, 5, 7 personal
   until then.

## Appendix: phone inventory, checked in code (2026-10-11)

Paths: `M/` = Common/src/main/java/tk/glucodata, `Mo/` = Common/src/mobile/java/tk/glucodata.

- **Hubs:** `CurrentGlucoseSource.getFresh` (managed → callback lanes → native);
  `CurrentDisplaySource.resolveCurrent` — `maxAge` bounds only the live source: with no fresh
  live point the snapshot comes from history up to 25 min old (83-86, 251-273);
  `DisplayTrendSource` (25 min window; `resolveArrowRate` falls back to `Snapshot.rate`);
  `TrendWindows` 35 min for list rows; `UiRefreshBus` has no debounce of its own;
  `TrailingThrottle` only in the complication throttle (20 s) and the broadcaster (1 s).
- **Notification:** value from `resolveCurrent`; primary arrow display rate, **peer arrows
  `Snapshot.rate`** (`NotificationMultiSensorSource:65`); triggers: direct `normalglucose`,
  then +750 ms and +1 s refreshes; Handler freshness deadline at reading + 330 s; stale →
  `makeStaleReadingNotification` (value kept, no arrow). **A second stale writer,
  `oldnotification` (loss alarm), drops the value: whichever fires last wins.**
- **Status bar icon:** text from `getglstring` with `util.getlocale()` and clamps computed
  ÷ 18.0 ("27.8<", "500<" even for 400-max sensors); no arrow; novalue icon when stale.
- **AOD:** `resolveCurrent` for `previousglucosesensorid`/`lastsensorname` (other surfaces use
  `resolveMainSensor`); arrow from 3 h rows not cut by `resolveTrendPoints`; **no staleness at
  all** (falls back to the last chart point of 3 h); 60 s Handler + TIME_TICK, 750 ms
  follow-up, 100 ms × 20 lock checks.
- **Alarms:** banner arrow = display rate over 50 min; **AlarmActivity arrow = `Snapshot.rate`,
  or `Natives.lastglucose().rate` first** when none is passed → banner and screen can differ;
  value re-parsed and re-formatted with `DecimalFormat`; time shown is "now"; 15 s alert poll
  for non-BLE sources.
- **Legacy `Floating.java`:** stale at < 330 s (strict); redrawn by `dowithglucose`,
  status refresh, loss alarm, screen on — **not by `requestDataRefresh`**, so a reading stored
  only in Room does not redraw it.
- **Pill:** own freshness clock + `FloatingPillWatchdog` (30 s / 3 s).
- **Loss alarm:** exact RTC_WAKEUP at reading + 330 s (300 s while both read), drives
  `oldnotification`, Floating, `GlucoseWidget.oldvalue`.
- **More divergences:** view mode — `Notify.resolveSensorViewMode` skips the
  `ManagedSensorViewModeStore` step `CurrentDisplaySource` uses, so arrow lane and value lane
  can differ; conversion constants still mixed (÷18.0 native `gl` and icon clamps, ×18.0182
  `sharedMgdl`/TrendEngine, ×18.016 forecast colouring); text formatting: icon
  `util.getlocale()`, notification `Locale.getDefault()`, alarm `DecimalFormat`.

## Appendix: dashboard, pill, widgets, checked in code (2026-10-11)

- **Dashboard hero:** re-resolves in composition on `UiRefreshBus.revision`; arrow =
  TrendEngine over the **merged cross-sensor history with a null serial** (no sensor filter);
  peer chips and the VM's `_currentRate` use `Snapshot.rate`; timers: VM 1 s coalesce, 300 ms
  history coalesce, **15 s freshness tick** (stale flip 330–345 s), 30 s chart heartbeat; stale →
  value and arrow replaced by "no data … ago". No chart-head value label exists.
- **Pill/card:** the service resolves itself and **prefers a stored Room row newer than the
  live value by > 60 s** (only surface that does); fallback to the unsmoothed Room point;
  freshness clock (≤ 15 s) + watchdog (30 s, 3 s after readings) + 50 ms frame fallback + 5 s
  ring steps; stale → value and arrow kept, dimmed 50 %.
- **Widgets:** resolve with a **12 h lookback** (others 330 s → history fallback); listen only
  to `GlucoseUpdateBroadcaster` (1 s); freshness evaluated only at render time; **the stale
  look comes only from the loss alarm** (reading + showtime + 20 s: 350 s, 400 s for a G7 with
  380 s showtime) because the notification's stale repost sends no broadcast; in the both-read
  case it can look fresh until ~670 s or the next screen on; stale → strikethrough, no arrow,
  "No new value since HH:MM".
- **Quick-settings tiles/shortcuts:** no value shown.
- **Four stale looks** (hero hides, pill dims, widget strikes through, notification status
  title) and **four flip moments** (330, ~330, 330–345, 350–670 s) for one reading.

## Appendix: outbound consumers, checked in code (2026-10-11)

- One full fan-out, `SuperGattCallback.dowithglucose` (~746-898: main-sensor check, high-water
  mark, loss alarm, alert engine, AOD broadcast, Floating, notification, custom alerts,
  `UiRefreshBus`, speech, watch complications, then `emitExchangeOutputs`), and one
  exchange-only fan-out, `emitExchangeOutputs` (~918-1010: GDH, Outbound API, WearSync2 push
  (45 s throttle), Garmin, web-server IOB, xInfuus, xDrip, WearInt, Gadgetbridge). Room writes,
  Health Connect and the Nightscout wake are done by each ingest path, not the fan-outs.
- Entry points: `handleGlucoseResult` (classic BLE and some managed drivers; the only path that
  exports to Health Connect), `processExternalCurrentReading` (Ottai, Sibionics, Anytime,
  AiDex catch-up, virtual sources), the alert engine's 15 s poll (native-only readings),
  `WearSync2.onChunk` (phone: exchange fan-out only, own 25 min TrendAccess rate; watch: only
  `UiRefreshBus`). `Applic.doglucose` is dead code.
- Shared payload `ExchangeGlucosePayload.resolve`: `resolveCurrentForExchange` (exchange
  smoothing), mg/dL = display × 18.0182, direction via `ExchangeTrend` (AiDex byte, native
  trend, then cuts 1/2/3 mg/dL/min), 330 s staleness.
- Per consumer: GDH has no minute gate; xDrip/xInfuus/WearInt/Gadgetbridge share a 55 s gate;
  **Garmin gets the caller's raw `gl` and rate, not the payload**; xInfuus sends no trend;
  web server and Nightscout read native polls only (managed/virtual readings not mirrored to
  native never reach them); Health Connect only on the `handleGlucoseResult` path.
- **To check (possible bug):** a reading synced from the watch to the phone advances neither
  the alert engine's last-delivered time nor the realtime high-water mark, so the next 15 s
  poll may push the same reading again through `dowithglucose` once it is in native: alarms
  (which the sync path avoids on purpose) and a second GDH send. Not confirmed at runtime.
- Also: Sibionics passes its rate in mmol/min where `ExchangeTrend` expects mg/dL/min; stale
  comments (`SGC:901-904` "through numdata"; BroadcastTrendRate "20 min" vs 25).

## Appendix: watch, checked in code (2026-10-11)

- No Wear tile; no ambient support in the app (only the watch face); no watch receiver for
  the phone's update broadcasts. Text arrows come from `TrendArrowText` (shared angle).
- **Complications** share `GlucoseComplicationData.currentReading()` (resolve for
  `WearSensorSelection` + `syncedReading` fallback; display rate cached per revision);
  triggers: the bus (20 s throttle, skipped for a newer reading), `ComplicationFreshness`
  exact alarm at reading + 330 s, the loss alarm. Outliers: **IconArrow** resolves with no
  sensor and no synced fallback and never arms `ComplicationFreshness`; **MultiSensor** uses its
  own 300 s update period and a validity window. **IconValue LONG_TEXT is declared but returns
  null.** "No value" looks differ per type (text, blank bitmap, legacy bitmap, NaN arrow, trace
  without value, "—").
- **Main screen hero** uses `WearGlucoseStore` history with **graph smoothing** (complications
  use the local smoothing mode → can differ with "smooth graph only"); stale only dims, up to
  6 h, checked every 30 s (boundary ≤ 330 vs complications < 330); missing rate → flat arrow
  (complications: none). Peer chips: 35 min window over smoothed points.
- **Watch face:** 60 s frames; the one-shot `Applic.updatescreen` updater is cleared after its
  first run and is called only from BLE paths, so **a phone-pushed reading does not invalidate
  the face directly**.
- **Ongoing activity:** native main sensor, no arrow, refreshed by the data refresh and the loss
  alarm, **not by `ComplicationFreshness`**: a stale value can stay up to ~340 s longer.
- **Alarm surfaces** freeze the value at alert time (`AlarmActivity` never refreshed; the
  full-screen text uses ÷ 18.0).
- **Phone→watch:** push only for the main sensor's realtime reading; **calls within 45 s of the
  last push are dropped, not deferred**; `bothRead` skip trusts an `owns` report up to 31 min
  old; synced readings never re-arm the watch's loss alarm (its `requestSync` runs on a fixed
  ~350 s cadence).
