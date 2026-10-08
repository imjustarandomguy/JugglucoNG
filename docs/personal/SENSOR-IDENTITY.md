# Sensor identity: audit and refactor plan (2026-10-07)

Read-only audit of `personal` after the fourth bug caused by one sensor having several
spellings. Paths are under `Common/src/`; line numbers are as of `personal` 4478f3bcf and
will drift.

## The bugs so far

1. The watch kept a G7 on two records (11-char alias vs 16-char full name); readings went
   into one, the screen showed the other (`fix/wear-sensor-alias`).
2. The phone's calibration payload (full name) was not found for the watch's alias
   record (`SyncedWearCalibrationProvider.keyOf`).
3. A Nightscout follower record `NSF-3073E464C8CB` appeared as a Libre-like sensor
   `073E464C8CB` (native short name) and its leftover wasn't cleaned up.
4. The watch's ownership reports (SYNC2_OWN) carried `739749`, so the phone never matched
   them to `12147739749`: "Watch: no report yet".

## Spellings (one G7)

| Name | Example | Where it comes from |
|---|---|---|
| F16 | `8958912147739749` | the native record name (phone) |
| A11 | `12147739749` | `name+5` of an F16 record; `SensorGlucoseData::shortsensorname()` (last 11 chars) of any record; the watch's record name |
| S6 | `739749` | `sensor::shortsensorname_chars()` = `name+5` of an **11-char** record (watch): `Natives.activeSensors()`, `Natives.lastsensorname()` |
| D12 | `912147739749` | `SensorGlucoseData::othershortsensorname()` for Dexcom (last 12): `Natives.lastglucose().sensorid`; Sibionics returns its `siToken` here |
| 7-char | `BDDF969` | `name+5` of 12-char managed names (MQ, iCan have legacy shims) |
| cloud | `NSF-…` → `073E464C8CB` | `name+5` makes cloud ids look like Libre serials |

Names longer than 16 chars are truncated (`sensornamelen=16`, `cpp/sensoren.hpp:36`).

## Root problems

1. **Two native "short name" functions disagree.** `sensor::shortsensorname_chars()`
   (`cpp/sensoren.hpp:72-87`, `1269-1297`) returns `name+5`; `SensorGlucoseData::
   shortsensorname()` (`cpp/SensorGlucoseData.hpp:1157-1164`) returns the last 11 chars.
2. **Native lookup is one-directional.** `findsensorshort` / `sensorindices`
   (`cpp/sensoren.hpp:844-890`) match `fullname()==x || name+5==x`: A11 finds an F16
   record, S6 finds an A11 record, but **F16 never finds an A11 record**. So on the watch
   `resolveFullSensorName(F16)`, `getSensorIndex`, `setcurrentsensor` (silent no-op),
   `removeSensorById`, `getGlucoseHistoryForSensor`, `getSensorUiSnapshot` fail for F16,
   and on the phone S6 resolves to nothing. `SensorIdentity.matches` relies on this for
   Libre/Dexcom, so `matches(F16, A11)` is false on the watch and `matches(S6, …)` false on
   the phone; unknown names are cached as null until `invalidateCaches()`.
3. **"Canonical" depends on the device.** `canonicalSensorId` returns the fullest name the
   local native code knows (F16 on the phone, A11 on the watch); `canonicalOrRaw` (behind
   `resolveMainSensor`) never consults native, so the watch's main sensor is S6. At least
   eight keying schemes exist (canonical-lowercase, A11-lowercase, A11 Room, uppercase
   multi-alias, raw-uppercase tombstones, raw, F16, hash of raw).
4. **Native writers create a record for any unresolved name**
   (`ensureDirectStreamShell` → `addsensor`, `cpp/sensoren.hpp:430-441`; also
   `hasSensorStreamCapacity`, which looks like a query). That is the bug 1 mechanism.
5. **Callbacks are built from `activeSensors()`** (`SensorBluetooth.java:1599`, `1703`,
   …), so on the watch S6 becomes the G7 callback's `SerialNumber` and spreads into
   ownership, claims, direct readings, alarms and prefs.

## Highest-risk sites (ranked)

1. SYNC2_OWN sends the raw spelling; each device keys it differently
   (`SensorOwnershipRuntime.kt:704`, `563-564`, `318-319`, `606-614`): bug 4.
2. Phantom echo: `sensors()` adds the peer's spellings and announces them back with
   owns=false (`:1094`, `683-704`); on the watch the echo can replace the phone's report.
3. Callback `SerialNumber` from `activeSensors()` (above).
4. One-directional native lookup plus null caching (above).
5. Native writers create records for unresolved names (above).
6. D12 / `siToken` compared with `matches`: native current-value fallback, exchange trend
   and expiry matching never match a G7 (`CurrentGlucoseSource.kt:99`, `190`;
   `CurrentDisplaySource.kt:806`; `ExchangeTrend.java:196`; `cpp/watch/watchvalue.cpp:180`).
7. The watch calibration command carries no sensor id (`WearCalibrationCommand.kt:46-50`,
   `92-100`): applied to the phone's current sensor.
8. SYNC2_REMOVE and tombstones are raw (`WearSync2.kt:77-90`, `127-152`, `759-773`):
   removal not applied on the watch; a removed sensor can come back.
9. Cross-device selection/primary and the silent `setcurrentsensor` no-op
   (`WearSensorSelectionSync.kt`, `MultiSensorSelection.kt`, `SensorBluetooth.java:877-899`).
10. View-mode store keyed by raw spelling (`ManagedSensorViewModeStore.kt:40-43`).
11. Raw `SerialNumber` equality for notification/AOD/alarm-screen lanes (`Notify.java:4246`,
    `4635`; `AODOverlayService.kt:530`; `AlarmActivity.kt:330`).
12. xDrip/exchange serial flips F16↔A11 (`XInfuus.java:100-115`; `WearSync2.kt:660`, `724`).
13. Removal confirmed by `equalsIgnoreCase` (`NativeSensorTermination.kt:27-40`).
14. Raw `activeSet.contains(SerialNumber)` (`SensorHandoverRuntime.kt:124-136`;
    `SensorViewModel.kt:494-512`).
15. BLE-level keys that break if the spelling changes: Dexcom auth-slot pref
    (`DexGattCallback.java:538-548`), Libre 2 advertisement match (`Libre2GattCallback.java:
    786-791`), handover latch (`SensorHandoverRuntime.kt:59-75`).

The safest existing pattern is `SyncedWearCalibrationProvider.keyOf` (A11, lowercase,
computable as text). Room is safe (stored as A11, queried with candidate sets).

## Proposed design

- **Identity key = the 11-char alias for native-named sensors** (not the fullest name):
  1. a managed adapter's canonical id if it recognises the id (incl. `SIBI:`, `X-`,
     `ICN-`, MQ, Ottai and cloud `NSF-`/`API-`/`MQF-`, classified first);
  2. a 16-char native name → `substring(5)`;
  3. an 11-char native name as is;
  4. anything else (S6, 7-char, D12, `siToken`) resolved on the device that produced it,
     via native, to the record's full name, then rule 2/3; if that fails it is never
     stored or sent.
  The alias is computable as text on any device, never changes when caches clear, and
  already equals Room's storage key, `keyOf`, the native `shortsensorname`, Nightscout /
  LibreView / Clone ids and the Libre 2 advertisement: no Room migration.
- Carry `SensorRef(key, nativeName)`; `nativeName` is this device's record name for native
  calls (full name only where needed, e.g. the G7 handoff).
- `SensorKey`: a final class (not a Kotlin value class, since ~12 Java files consume it),
  private constructor, `value`, `of(raw, resolver)`, `toWire()`, `prefKey(prefix)`;
  `Map<SensorKey, V>` makes raw-keyed maps impossible. Deprecate the `String` overloads of
  `SensorIdentity.*` (warning, then error).
- Convert only at entry points: native lists/names (`activeSensors`, `lastsensorname`,
  `getSensorName`/`sensorptr2str`/`namefromSensorptr`, `lastglucose().sensorid`),
  native→Java callbacks, callback construction (`SuperGattCallback` gets `sensorKey` and
  `nativeName`), wire decoders, persisted stores on read (one-time key migration), UI events.
- Wrap `Natives.activeSensors()` as `NativeSensors.active(): List<SensorRef>` (resolve each
  raw name through `resolveFullSensorName` on the owning device). Later, in native code:
  return the whole name when `fulllen()!=16` (removes S6 and 7-char forms at the source),
  make lookup two-directional, stop `hasSensorStreamCapacity` creating records, refuse to
  create an A11/F16 variant of an existing record, and make `strGlucose.sensorid` use
  `shortsensorname`. Check the MQ/iCan legacy matchers first.
- **Architecture gates** (extend `ArchitectureGateTests`, which today scans only
  `src/{main,mobile,wear}/java`, not the flavour directories where `DexGattCallback`,
  `Libre3GattCallback`, `SiGattCallback` live): G1 no native name APIs outside
  `NativeSensors.kt`; G2 no raw comparisons of serial-like identifiers; G3 no maps/prefs
  keyed by raw serials; G4 wire encoders must use `SensorKey.toWire()`. Allow-lists as
  `path:count` so growth inside a listed file fails too. Plus behavioural tests with a fake
  native table for the phone (F16 record) and the watch (A11 + stale F16 record), wire
  round trips, and a two-device ownership test reproducing bug 4 (needs an injectable
  resolver in `SensorIdentity`).

## Size and phases

About 122 production files touch these APIs today; a full refactor touches ~100–120 files
plus ~30 tests.

| Phase | Work | Files |
|---|---|---|
| 0 | Fixtures, failing tests for bug 4 and S6, gates in report-only mode | ~3 |
| 1 | `SensorKey`, `SensorRef`, `NativeSensors`; `SensorIdentity` delegates | ~8 |
| 2 | Wire: ownership (send the key, normalise on receipt, no echo, temporary shim for short wire ids), `WearSync2` remove/tombstones, selection commands/prefs, calibration command + key, handoff | ~10 |
| 3 | State: `DirectSensorReadings`, `IntegratedStockBaseline`, view-mode store, handover latch, Dexcom auth slot (with migrations), `WearRoutingRequest`, `CloneSensorRegistry`, `CalibrationManager` | ~12 |
| 4 | Runtime: `SuperGattCallback.sensorKey`, roster, Notify/AOD/AlarmActivity, current/display sources, exchange trend, xDrip, termination, Libre 2 matching | ~20 |
| 5 | UI and view models (mechanical) | ~40 |
| 6 | Native fixes | 3–4 C++ |
| 7 | Gates to zero; delete `aliasOf`, `nativeAlias`, `shortNamedNativeRecord`, `keyOf`, `removalKey`, `candidateKeys`' `substring(5)`, then the drivers' legacy-alias shims | — |

Phases 1–2 (~18 files) remove the live bug class. A smaller stop-gap (~6 files): normalise
the SYNC2_OWN wire id and stop the echo; normalise the removal id and tombstone key; add the
sensor key to the calibration command; `NativeSensors.active()` on `resolveFullSensorName`;
the two small native changes (whole name for non-16-char records, two-directional lookup).
