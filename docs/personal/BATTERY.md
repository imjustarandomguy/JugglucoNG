# Battery measurements

How the owner's battery runs are done, and what each one showed. Devices: Galaxy S26
(4300 mAh) and Pixel Watch 3 (291 mAh as reported by batterystats). Read
[BUILD.md](BUILD.md) for adb setup (full path to `adb.exe`, `MSYS_NO_PATHCONV=1`,
wireless serials).

## How to run one

Before (e.g. at bedtime):

1. Unplug both devices first. A full charge resets batterystats by itself, and
   charging time is not counted.
2. Reset on both and note the time and both battery levels:
   ```
   adb -s <phone> shell dumpsys batterystats --reset
   adb -s <watch> shell dumpsys batterystats --reset
   ```
3. Disconnect adb (`adb disconnect`, or turn wireless debugging off) so it does not
   keep the radios busy.
4. During the run, note anything unusual: exercise, time away from the phone, app
   or settings changes (run 1 shows how a settings change can skew a run).

After, before charging:

1. Note the time and both battery levels; reconnect adb (the watch port changes).
2. Collect, per device:
   ```
   adb -s <dev> shell dumpsys batterystats > bs-<dev>.txt
   adb -s <dev> shell dumpsys battery > batt-<dev>.txt
   adb -s <dev> shell pm list packages -U > pkgs-<dev>.txt
   adb -s <watch> shell dumpsys bluetooth_manager > btm-watch.txt
   ```
   The watch's `batterystats` prints "Computed drain: 0" (runs 3 and 4) although it
   measures its discharge (`Discharge:` line): its per-app model comes out empty. There
   is no `batteryusagestats` service on these devices; compare the watch by CPU time
   and wake locks instead. Runs 1–2 (per-app numbers present) reset the watch at 86 % and
   94 %; runs 3–4 (empty) reset it right after a full charge (100 %, 98 %). Next run: after
   unplugging the watch from a full charge, do NOT reset it (the unplug already reset its
   stats), or reset only once it has dropped a few percent, and see whether the numbers
   come back.
3. Read:
   - `Estimated power use (mAh)` and the `UID …` lines under it: totals and the
     per-app ranking. Map uids with the package list (`u0a462` = uid 10462).
     NG was `u0a462` on the phone and `u0a228` on the watch in run 1; check again
     after any uninstall.
   - NG's own section (the line that is exactly `  u0a462:`): wake locks
     (`Juggluco::Dexcom`, `Juggluco::DexcomRearm`), `Wakeup alarm` counts,
     `Bluetooth Scan`, network bytes, CPU time.
   - The battery history at the top of the file (timestamped
     `+wake_lock=u0a228:"Juggluco::Dexcom"` lines) shows each G7 session, so a gap
     shows when a device stopped reading.
   - On the watch, `btm-watch.txt` → `GATT Client Map` → `Last apps`: one
     `AppRecord` per G7 session with times. `Entries: 0` and no recent record
     means the watch is not reading the G7.
   - On the phone, `adb logcat -d | grep "onClientConnectionState()"` shows the
     last ~20 min of G7 sessions (one `status=0` then `status=19` every 5 min is
     clean).
   - Screen time: `Time on battery screen off` (the rest is screen on; the
     screen is billed to whatever app is in front).

## Run 1: 2026-10-06, 16:58 to 21:15 (4 h 18 min), daytime

Builds: `personal` at 68aef0eca (installed 16:57). Phone and watch both set to read
the G7 ("Direct sensor on watch" on). Nightscout uploader on from about 18:10; xDrip+
exchange off from about 18:10.

Events: a 20 min run with the watch, out of the phone's range about 17:00 to 17:30.
About 18:09 to 18:13 the owner changed settings in NG and xDrip+ (xDrip+ off,
Nightscout upload on, a 1 s tap on Nightscout Follow).

### Phone: 95 % to 82 % (528 mAh)

Screen on about 1 h 45 min.

| Item | mAh |
|---|---|
| Screen | 159 |
| CPU (all apps) | 155 |
| Mobile radio | 56 |
| Wake locks (all) | 17 |
| Bluetooth (all) | 13 |

By app:

| App | mAh | Notes |
|---|---|---|
| NG (`u0a462`) | 147 | 116 of it is the screen while NG was in front (about 1 h). Own use: CPU 26 (foreground 18, foreground service 8), wake locks 3.4, Bluetooth 1.4, Wi-Fi 0.3 |
| system (1000) | 63 | |
| Fitbit / Google Health | 39 | Watch sync and the run |
| work profile app (`u10a442`) | 23 | |
| kernel (0) | 19 | |
| Google Play services | 17 | |
| Thunderbird | 17 | |
| Samsung launcher | 11 | |
| **xDrip+** | **8.2** | Still running a foreground service though no longer used |
| original Juggluco | 1.5 | Foreground service still running |
| GlucoDataHandler | 0.6 | |

NG in the background (foreground service, screen off or another app in front):
**12.4 mAh in 4 h 18 min, about 2.9 mAh/h, 0.07 %/h, roughly 1.5 to 2 % a day.**

NG details: G7 wake lock 1 min 27 s and re-arm wake lock 2 min 56 s (28
acquisitions each); 4 loss-alarm wakeups; Bluetooth scan 2 times (11.5 min
actual); **47 MB received over Wi-Fi**, probably the Nightscout Follow tap's
history import (it pages through the whole server). Check that this is gone in
the next run.

G7 link: clean. 20:57 to 21:17 shows one connection every 5 minutes, open 4 s,
closed by the sensor (`status=19`), no re-links.

### Watch: 86 % to 63 % (64 mAh)

Screen on 4.5 min (34 times); always-on display the rest.

| Item | mAh |
|---|---|
| CPU (all) | 83 |
| Always-on display | 10 |

By app:

| App | mAh | Notes |
|---|---|---|
| system (1000) | 30 | Includes the run's sensors |
| Fitbit | 15 | The run |
| kernel (0) | 12 | |
| system UI | 3.8 | |
| Google Play services | 3.6 | |
| **NG (`u0a228`)** | **3.5** | Foreground service 2.3, background 1.1, foreground 0.2 |
| **GlucoDataHandler** | **2.5** | Foreground service running all the time |
| Health Services | 0.9 | |
| original Juggluco | 0.1 | |

NG on the watch: **0.8 mAh/h, about 0.3 %/h, roughly 7 % a day**, but see the
caveat. CPU 49 s user + 13 s system; G7 wake lock 42 s and re-arm 1 min 20 s (13
acquisitions); 25 loss-alarm wakeups; 32 complication stale-check wakeups; 7
LOW_POWER Bluetooth scans, 18 min in total.

**Caveat: the watch stopped reading the G7 at 18:11** and did not reconnect.
Its GATT client closed right after a message from the phone, during the settings
changes above; the Nightscout follower record created by the Follow tap reached
the watch and showed there as a second sensor (`073E464C8CB`), which the watch
then scanned for. From 18:11 the watch got its readings from the phone instead.
So this run measured "both read" for only about 1 h 13 min. Being fixed in
`fix/watch-g7-drop`.

### Takeaways

- NG's background cost on the phone is small. The screen, the mobile radio and
  other apps dominate.
- Remove what is no longer used: xDrip+ on the phone (8 mAh in this run, about as
  much as NG's background use), GlucoDataHandler on the watch if its watch face
  or complications are not used (2.5 mAh).
- The watch figure for NG has to be measured again with the G7 drop fixed.

## Run 2: overnight, started 2026-10-06 23:55:33

Builds: `personal` 8920b3e04 (installed 23:27; adds the watch G7 drop fix, the reading
status reports, alarm settings sync, alarm routing, shared snooze and dismiss, alarm
history, Persistent low, the Nightscout and Health Connect fixes). Both devices charged,
unplugged, then `batterystats --reset` on both at 23:55:33 (option 2 in "How to run
one"); adb disconnected right after. Start: phone 99 %, watch 94 %. The watch was
reading the G7 again since 23:27:48 and the card showed "Phone + watch".

Note for the comparison: the reading-status work adds one small ownership report per
reading in each direction while "Direct sensor on watch" is on (at most every 4 min).

Compare with run 1: phone NG background mAh/h, watch NG mAh/h (with the watch
reading the G7 the whole night: check `GATT Client Map` and the history for a
session every 5 min), wake lock counts, Wi-Fi bytes, and whether xDrip+ and
GlucoDataHandler were removed.

### Results: ended 2026-10-07 08:23 (8 h 28 min)

Phone **99 % to 88 %** (442 mAh; screen on about 1 h, mostly video). Watch **94 % to 69 %**
(72.5 mAh). xDrip+ and GlucoDataHandler were still installed on both.

**The watch read the G7 all night:** 103 `Juggluco::Dexcom` acquisitions in 8 h 28 min
(about 101 sessions expected). The phone's count was 89, but that is not a session
count: the phone's Bluetooth log (`dumpsys bluetooth_manager`, `stack::gatt`, which
reaches back to 05:37) shows a G7 session every 5 min from 05:42 to 08:22, 33 of 33,
none missed. The battery history's wake lock and `ACL_CONNECTED` markers also show
"gaps" at times the Bluetooth log has sessions, so they cannot be used to count
sessions either. Before 05:37 no record survives; there is no evidence of a miss.

NG:

| | Phone (`u0a462`) | Watch (`u0a228`) |
|---|---|---|
| Total | 31.6 mAh, of which screen 14.0 and foreground 4.4 (NG open about 10 min) | 2.56 mAh |
| In the background | **13.0 mAh, 1.5 mAh/h, about 0.04 %/h, under 1 % a day** | **2.4 mAh, 0.3 mAh/h, about 0.1 %/h, about 2.5 % a day** |
| Of which wake locks | 8.1 mAh: `DexcomRearm` 9 min 35 s and `Dexcom` 4 min 17 s (89 sessions) | 0.5 mAh: 10 min 15 s and 5 min 0 s (103 sessions) |
| CPU | 7.3 mAh (2 min 15 s user + 1 min 30 s system) | 2.0 mAh (1 min 14 s + 1 min 10 s) |
| Bluetooth | 2.0 mAh | (not modelled on the watch) |
| Network | Wi-Fi 0.6 MB in, 0.5 MB out (the 47 MB of run 1 is gone) | |

Compared with run 1: the phone's background cost halved (2.9 to 1.5 mAh/h; run 1 was
daytime and had the 47 MB Follow import), and the watch, now reading the G7 the whole
night, costs less than in run 1 (0.8 to 0.3 mAh/h; run 1 had 18 min of scanning for the
leftover follower record).

Other consumers:
- Phone: YouTube (Morphe) 77 mAh (video), system 51, the Google app in the second
  profile 30 (almost all mobile radio while cached), Thunderbird 14 (mobile radio),
  kernel 14, launcher 10.5, Play services 9, xDrip+ 2.5.
- Watch: system 21.7, Fitbit 9.5 (sleep tracking), kernel 8.9, always-on display 7.6,
  **GlucoDataHandler 4.7 (almost twice NG)**, Bluetooth 2.7, NG 2.6, Play services 2.3,
  system UI 2.2.

The phone's `DexcomRearm` wake lock is the largest single item in NG's background
use. It is deliberate (`fix/dex-rearm-after-session`): it keeps the CPU awake for the
5 s delay (`REARM_AFTER_SESSION_MSEC`) before the background connect is armed again,
because arming at once re-linked up to five times while the sensor still advertised.
Do not shorten it: with both devices reading, the watch's session follows the phone's by
about 3 s (phone about :45 to :49, watch about :48 to :52), so a shorter delay would
re-arm while the sensor is still busy.

## Run 3: longer run, started 2026-10-07 13:35:03

Builds: `personal` 9e70156ff (installed 12:15: the floating pill screen-on fix and
next-reading bar, watch alarm style, Nightscout edits, and the ownership-report name fix).
Both devices charged to 100 %, unplugged, `batterystats --reset` on both at 13:35:03;
adb disconnected right after. Start: phone 100 %, watch 100 %.

Changes since run 2: GlucoDataHandler archived on the phone and uninstalled on the watch;
xDrip+ and the original Juggluco set to battery Restricted on the phone; GlucoDataAuto
fed directly by NG. **NG logging is on** (trace.log, for the floating pill check), which
adds a little to NG's own numbers. Planned length 24–48 h; the owner notes runs, long
screen sessions and watch-off-wrist times.

### Results: ended early 2026-10-07 16:10 (2 h 36 min) to install a new build

Phone **100 % to 98 %** (43 mAh; screen on about 9 min). Watch **100 % to 90 %**; the
watch's batterystats computed no per-app power this time ("Computed drain: 0"), so only
CPU and wake locks are comparable.

- **Phone NG** (`u0a462`): 9.6 mAh, about 3.7 mAh/h (night run 2: 1.5 mAh/h). About half
  is the home-screen widget: Glance's `*job*r//SessionWorker` held a wake lock 5 min 39 s
  in 30 runs (max 45 s each), about one per reading. The G7 part was normal: `Dexcom`
  52 s and `DexcomRearm` 2 min 37 s (29/27 acquisitions), Bluetooth 0.6 mAh. NEXT.md has
  the follow-up.
- **Watch NG** (`u0a228`): 31 G7 sessions (as expected for 2.6 h), CPU 16.8 s user +
  4.4 s system (about 8 s/h, night run 2: about 17 s/h).
- Logging was on.

## Run 4: 2026-10-07 23:14, overnight

Builds: `personal` ed2678913 (installed 23:07: widget rework without Glance, Now Bar
option, chart, alarm save bar, watch rate fix). `batterystats --reset` on both at
23:14:06, adb disconnected right after. Start: phone 98 % per `dumpsys battery` (the
owner's screen said 99 %), watch 98 %. Both unplugged.

Main question: the phone's NG share without the Glance widget session (run 3: 9.6 mAh
in 2.6 h, about half of it the widget). Logging still on. Note for the results: whether
the Now Bar live notification was on, and which widgets were on the home screen.

### Results: ended 2026-10-08 17:13 (17 h 59 min, night + day)

Phone **98 % to 74 %** (1158 mAh measured, 1033 mAh computed; screen on 2 h 26 min, 76
times; on power about 0.5 % of the run: Android Auto in the car in the morning, which
did not reset the stats). Watch **98 % to 43 %** (157 mAh; screen on 3 min); its per-app
model was empty again ("Computed drain: 0").

- **Phone NG** (`u0a462`): **30.7 mAh, 1.7 mAh/h** over a day with 2.4 h of screen
  (run 3: 3.7 mAh/h; night run 2: 1.5 mAh/h), 7th app (YouTube 159, Firefox 67, a
  Reddit client 48). **No Glance SessionWorker wake locks any more** (the widget rework).
  `Dexcom` 9 min 5 s (187 sessions), `DexcomRearm` 18 min 44 s (185, about 6 s each:
  this build still let it run toward its 7 s cap; fixed in the next build, review #15).
- **Watch NG** (`u0a228`): 215 G7 sessions (every reading), CPU 1 min 21 s user +
  1 min 11 s system (about 8.5 s/h, as run 3); `Dexcom` 10 min 15 s, `DexcomRearm`
  21 min 52 s (same ~6 s each; same fix).
- **Both devices read every session.** trace.log shows 216 phone sessions (216 handshakes and
  service discoveries, no gap over 7 min). The wake-lock count (187) is lower because
  overlapping acquisitions count once, and gaps in the battery history were an artifact
  of how it records wake locks: count sessions from trace.log, not from batterystats.
