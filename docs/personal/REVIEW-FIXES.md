# Fixes after the 2026-10-08 reviews

What was changed in answer to [REVIEW.md](REVIEW.md) (Opus) and
[REVIEW-COPILOT.md](REVIEW-COPILOT.md) (Copilot), for a second review of the fixes
themselves. Item numbers are REVIEW.md's (#n) and Copilot's (C-nn, O-nn).

## How to review

- Each fix is one commit on its topic branch; the commit body says what and why.
  Review `git log --reverse <reviewed tip>..<branch>` and its diff per branch (table
  below). `feat/dexcom-g7-watch` was rebased onto the fixed `fix/dexcom-g7-link`, so
  review its last six commits (`git log fix/dexcom-g7-link..feat/dexcom-g7-watch`).
- `personal` integrates them (merge commits plus the personal-only commits listed
  under "Integration"). The reviewed snapshot was `personal` 970c21afa; the fixes
  were merged on top of it.
- Tests: `scripts/personal/test.sh` on each branch and on `personal` (88008d003: 7804 tests,
  92 failed = 70 baseline + 22 machine-only signatures, NEW 0) reports no new
  failures (about 70 tests always fail on this Windows machine for machine reasons;
  they are listed in `scripts/personal/test-baseline.txt`). Nothing below was run on
  a device yet; native C++ changes were only compiled.
- Where a fix differs from what a review proposed, the reason is given.

| Branch | Reviewed tip | Fixed tip |
|---|---|---|
| fix/dexcom-g7-link | 196413f4a | 4c07e4930 |
| feat/dexcom-g7-watch | a994de829 | 2791b364f (rebased) |
| feat/persistent-low-alarm | ed8c48482 | e8ab0333f |
| feat/alarm-routing | 242210928 | 665b2d88d |
| feat/alarm-settings-save | 7e3be27f9 | 07317acd2 |
| fix/nightscout | 4904f123f | 74a84d4eb |
| fix/health-connect | 0dbc5ec9a | f4d356d48 |
| feat/quick-treatment-entry | cb8719979 | c6375f56d |
| feat/floating-glucose | e500103a5 | 913d9ba18 |
| feat/widget-rework | 8ff35fc61 | a3cf5b2dc |
| fix/wear-app | 8802bee7d | c6d5aa46c |
| fix/dashboard-chart-bounds | 69bf9cf02 | 2f9196b86 |
| feat/readings-as-dots | 6e27ab6df | 3574769f9 |
| feat/samsung-now-bar | c91fb9694 | dropped (owner: not wanted) |

Unchanged: fix/wear-alarm-settings-sync, feat/alarm-history, feat/glucose-display.

## Fixed

### Alarms
| Item | Commit | Change |
|---|---|---|
| #1 / O-01 Persistent low silent after Very low | e8ab0333f | `veryLowSeen` latch replaced by `heldByVeryLow`: held only while Very low's episode is active (condition holds, or fired and not reset). When it ends, the count restarts at that reading (or the next reading below the threshold if that one is in the band). The history-based start replays the same rule with Very low's entry/exit lines (only when Very low is enabled and in its hours), so live, history and restart agree. The test that pinned the latch is replaced. |
| #2 / O-02 held alarm consumed the episode | e2fb475ec | A firing held for the watch is no longer a delivery (no trigger time, no cooldown). `AlarmRouting.offer` re-asks `ringsHere` on every reading and 15 s check; standard alerts stay pending, persistent high/low and missed reading are re-offered while their condition holds, delta alarms re-arm; the phone's signal-loss path does the same via `Notify.sayLossAlarm`. A dismissal on the watch ends the hold. No escalation: an unanswered watch alarm never rings the phone. |
| #3 (Copilot caveat applied) watch availability | ef8dfc059 | New watch→phone report `/sync2/watchstatus` (`WatchAlarmReadiness`), only while the mode is "Watch when connected": with each reading (own or mirrored), at most every 4 min, and at once on a charger change or when the phone returns. The phone counts the watch reachable only when discovery finds it and a report arrived within 11 min about a reading no older than 11 min; charging from the newest of the status and ownership reports. Unknown → the phone rings; an older watch (no report) counts as away. |
| C-03 rapid silence changes | 665b2d88d | Each snooze/dismiss/quiet-window change carries a `ChangeId` (random device origin + Lamport revision) in a new wire section that older builds skip. One device's changes order by revision; across devices time decides, within 5 s the higher revision, then origin. Entries without ids keep the old 5 s rule. Tests: rapid start/cancel, duration change then cancel, two dismissals 4 s apart, resend, out-of-order, older peer, wire layout. |
| #6 Save reverted changes made elsewhere | fcfd292a5 (+ personal 88008d003) | Three-way per-field merge (`mergeFields`) of AlertConfig, GlobalAlertSettings and the quiet-window settings; a field changed on both sides keeps the draft (documented); active-hours start/end merge as units. Runs on return to the page, after process death (bases kept on disk) and at Save. A field the merge doesn't name keeps the draft's whole part (safe default). On `personal`, the fields other branches add (`riseRateSuppress`, `alarmRouting`, `watchAlarmStyle`) are named by the personal commit 88008d003. |
| #5 (owner chose a reminder) unsaved draft | 07317acd2 | On the activity's ON_STOP (not on configuration change), a dirty draft posts a low-importance ongoing notification "Unsaved alarm changes" (channel `ALERT_SETTINGS_DRAFT`); tap opens the Alarms page; Save, Discard or editing back to clean cancels it; after process death the draft on disk decides. Explicit Save is kept (Copilot: auto-save would contradict it). |

### G7
| Item | Commit | Change |
|---|---|---|
| #10 phone fresh pairing | 06cec54dc | `DexcomAuthPolicy.afterChallengeReply()`: every channel except the smartwatch one uses main's two conditions unchanged (`!newcertificates&&auth != 1` to stop; `auth==1&&isbonded||(bonded&&bond==2)` for data); only the watch slot keeps the branch's behaviour. `phoneChannelDecidesAsBefore` compares against main's expression for every distinguishable auth × bond × bonded × newcertificates. The phone's auth request bytes equal main's (slot 2). |
| #11 receiver-slot fallback | f702492e1 | Removed. Slot fixed at 3 on a watch, 2 on a phone; a stored `dexcom_auth_slots` value is ignored. A refused watch pairing shows as handshake "auth != 1", like other handshake failures. |
| #9 GATT never closed | cbba98e50 | A stopped callback's CONNECTED/DISCONNECTED closes its GATT only if it is still the callback's current one (checked under the callback's lock); `SensorOwnershipRuntime.release()` pauses every callback filed under the key and calls `closeGattTransport()`. |
| #13 / O-13 lock order | 09d16748c | `blocksLocalConnection(SuperGattCallback)` takes the callback and asks it `readsAlongside()` — no roster lookup under the callback monitor; `key()` lists only native records, not `mygatts()`; keying is skipped while nothing is released. Remaining: `SensorIdentity`'s managed-driver lookups can still call `mygatts()` on a cache miss (pre-existing on main), now reached only while a sensor is released. |
| #12 / O-12 watch-only / legacy G7 | 62fb7b3ae | The watch stays off an alongside G7 only when not asked to read it AND the phone's ownership reports say the phone reads it (recorded per driver serial at each reconciliation). No migration of the native "use Bluetooth" flag: /netinfo rewrites it on the watch, so it doesn't reflect the user's choice. Gap: after a watch restart, until the phone's next report (≤ 15 min, 5 with auto-switch), a watch not asked to read can still dial. |
| #15 (Copilot caveat applied) re-arm wake lock | 46c7f259f | `SuperGattCallback.pendingConnectEnded()` is called once per scheduled connect when issued, declined or cancelled; Dexcom releases the re-arm lock there (and in `close()`), at once if nothing was scheduled. The +2000 ms stays only as an upper bound. |
| #14 (qualified) background re-arm gate | 4c07e4930 | The two re-arm paths require `reconnectsInBackground()` (known and bonded) instead of `useAutoConnect()`. Connect mode unchanged. No "missed session → direct" policy (Copilot: needs device evidence). |
| #16 (Copilot caveat applied) slot wipe | 2791b364f | Only a Dexcom record with a different scanned code is wiped; otherwise readings are kept and re-slotted from the handed start, and an existing Dexcom record keeps its header (lifetime included). A record newly turned into a Dexcom one still gets the 10-day default because the handoff carries no lifetime (commented). Native code compiled only. |
| ownership key (found while fixing #9/#13) | e52573df5 | One key per sensor whichever name the other device uses. |

### Nightscout and Health Connect
| Item | Commit | Change |
|---|---|---|
| #17 loop-system documents | 98baa88eb | Never PATCH/PUT/DELETE a document with pumpId/pumpSerial/pumpType/isSMB, or whose enteredBy/app/device names aaps/androidaps/openaps/loop/trio/iaps/freeaps/pump as a whole word ("LoopFollow", "xDrip+" don't count). Decided on the document as the server holds it, read just before any write (edits already read first; a queued delete of a non-own document now reads first). Edits stay local (nsUploadedAt 0, still unconfirmed so a receive won't overwrite); deletes are local only (tombstone kept, never sent). |
| #18 / O-18 refused edit held the queue | 01219f48a | A 4xx refusal (not 404/410/408/429) on an edit's read or write no longer blocks the queue: the edit stays local, never marked uploaded, logged with the server's message, and counts as that pass's failure. The journal has no per-entry upload status, so the log line is the per-entry signal. |
| #19 (Copilot rejected the first fix) failing operations | 7565c7841 | Each failing operation backs off on its own (5xx except 502–504, delete refusals: 1 min doubling to 6 h; the rest of the queue continues; a wake is booked for the earliest retry). After 20 attempts a delete's tombstone is kept but no longer sent, an edit stays local — nothing dropped. No answer, 401/408/429, 502–504 or a non-Nightscout page: the whole pass waits, uncounted. Delete attempts live in the tombstone, edit attempts in memory keyed by updatedAt. |
| #20 clock skew | 2dba0b2cc | A received row stores its document's revision (srvModified, else updated_at) in `lvUploadedAt`, a column received rows never used; receive and uploader compare server revision with server revision; edit state comes from the phone's own times. Unknown base revision → the edit is kept. Tests with the server 1 h ahead and behind. **Reviewer note:** this reuses a column rather than adding one (no schema change). |
| C-04 truncated read dropped tombstones | 74a84d4eb | A tombstone that isn't sent (sending off, kept local, or after 20 attempts) is dropped only when a read of that document finds it gone (404/410, empty v1 find) or invalid — at most once a day per tombstone, 20 per pass. Receiving off: kept indefinitely. Undated own identifiers use an all-time per-identifier v1 lookup. |
| #21 (Copilot caveat applied) HC import churn | f4d356d48 | A record is written only if new or if what the import would write differs from the row (content compared, not HC lastModifiedTime, which moves for changes the row doesn't show): no upsert, no updatedAt bump, no Nightscout wake otherwise. A user edit keeps the row's sourceRecordId and turns the row MANUAL, so the import leaves it. Imported ids and end times are remembered 31 days so a locally deleted row stays deleted. Last-run time persisted. **Reviewer note:** an edited imported row becoming MANUAL is a design choice. |

### Journal and reminders
| Item | Commit | Change |
|---|---|---|
| #22 hidden sizing copies | c97b2f415 | The visible sheet keeps its ticker, loads and effects; the sizing copies are an effect-free form that only lays out (`sizingOnly` gone; the food picker's search/keyboard effects skipped there). Chips and last-dose lines are always reserved in the sizing copies, so the sheet keeps one fixed height. Verified by reading only (no UI test can compose on this machine). |
| C-01 reminder logged a different dose | 4cc869b3d | The Log action carries the dose and insulin name it showed. Same insulin (preset in use, same name): logs the dose on the button even if the default changed (the tap confirms that amount; the "Logged" notice names it). Renamed/archived/deleted insulin or an old notification without a dose: nothing logged; the reminder turns into "Not logged: this insulin changed…" and opens the sheet prefilled. |
| C-02 success before persistence | 8723988a8, c6375f56d | A dose and its meal are written in one transaction (`upsertEntries`), all or none; the basal reminder is cancelled only after that succeeds. Outside the app the sheet stays open (Save disabled) until stored, then "Saved…" with an Undo that deletes exactly those rows; failure keeps the sheet and inputs ("Not saved…"). In the app, failure shows "Not saved: …" with Try again. Tests: failure, partial failure rolls back both, reminder not cancelled on failure, Undo ids match. |
| #25 (qualified) reminder delivery and matching | e19463196 | The insulin library warns when notifications or the reminder channel are off, or that reminders may be late without exact alarms (re-checked on return). Dose matching prefers the preset id; name matching only for rows from Nightscout, AAPS, the API or Clone. |
| #24 (owner changed the earlier choice) dose step | e7c8cd849 | New presets, built-ins, the library form and the v33 migration use 0.5 U. **Deviation:** the schema's column DEFAULT stays 1 (changing it changes Room's schema identity, so a database already at v33 would fail to open); the migration adds the column then sets 0.5. For upstream it can become `DEFAULT 0.5` when squashed. |

### Display, charts, watch
| Item | Commit | Change |
|---|---|---|
| #26 details card stale while open | a579a617a | The card reads the service's reading StateFlow (time, age, Δ, chart, IOB/COB update; chart and Δ reload on a new reading). The freshness clock moved from the pill's composition into the service (`freshnessNow` StateFlow) shared by pill and card — no new timer; with the card open it keeps ticking past the stale timeout so the age advances; stale is dimmed like the pill. |
| #35 accessibility service | 913d9ba18 | One rule (`FloatingAccessibilityAvailability.wanted`: floating on, "Details on tap" on, "Over the status bar" on) applied by the master switch, both options, the service and at app start (which also withdraws one an earlier version left). On Android 13+ for a non-store install the panel explains "Allow restricted settings" with an App info button. |
| C-05 widget chart cache | a3cf5b2dc | The cache key includes every input the chart drawer reads (range-coloured line preference, band thresholds and colours light/dark, sensor line colour, smoothing, collapse, hide-initial-when-calibrated, calibration revision, plus theme, unit, range, size, history). A forced redraw (`renderNow`, settings Apply, `GlucoseWidgets.redrawAll()` from the app's chart/colour/threshold settings, a configuration change) drops the chart cache too. The line/dots setting is on another branch; on `personal` its change already calls the same refresh. |
| #27 complication throttle while asleep | aceb87e23 | A refresh can carry its reading time; a newer reading time runs immediately and cancels a pending deferred run; the throttle only coalesces repeats or untimed refreshes (backfill chunks still coalesce). |
| #28 watch face history per frame | 15b67f04e | `Reading.rate` is lazy and cached (`DisplayRateCache`: sensor, reading time, lanes, view mode, unit, data revision); value-only sources never load history; the watch face's per-frame read hits the cache. |
| #29 (Copilot caveat applied) "same reading" rules | c6d5aa46c | `mergeLivePoint` and `augmentHistory` share `DisplayTrendSource.storedRowIndex`: only the 2 newest rows, window 4/5 of the median recent gap (≤ 60 s, 48 s when unknown), so 1-minute readings never merge and the G7 keeps 60 s. A live reading that must stay replaces its row instead of sitting beside it. One existing assertion changed (5-minute series: 30 s from its row is now the same reading). Tests include the 1-minute cases and an unchanged phone alert rate when Room timestamps match. |
| #30 journal content past the right edge | 8947508aa | Right edge = later of now + 10 min (or the prediction horizon) and 10 min past the latest journal content (insulin curve end, activity end, future entry). |
| #31 edge clipping | d9ae43bdd | A visible value within the edge padding widens that end of the axis. |
| #32 remembered height | 2f9196b86 | `DashboardChartHeightChoice`: only a handle drag or a pull at the top marks a choice, stored once when it settles; a rest away from the top drops it, so list scrolling never overwrites the height. |
| #33 (Copilot caveat applied) dot size | 3574769f9 | `dotRadius` also takes plot width and the typical spacing between drawn dots (median of the newest gaps): readings ≥ 4 min apart keep the current size; faster series shrink until neighbours touch, never below duration size × interval/4 min. Phone chart, notification/widget drawer, watch chart and chart complication. |

### Docs
| Item | Commit | Change |
|---|---|---|
| C-06 rebuild procedure | 1ba33b539 (personal) | BUILD.md section 6 lists the personal-only code commits to replay, the verification merge, backup tags and the local strings.xml union merge; dependencies use current branch names. |

## Not changed, and why

- **Owner decisions kept** (Copilot's list): no escalation of unanswered alarms; nothing special for Very low in routing; "Screen only" watch style (#4) allowed; shared quiet windows in Both mode (#7); explicit Save (#5 only adds a reminder); write-back of the user's own received treatments (#17 only adds loop-system protection); Quick log buttons on by default (#23); detail/next-reading defaults (#34). For upstream these need defaults or opt-ins; not done here.
- **#8** (`g:` line on wear-alarm-settings-sync), branch splitting, squashing, serial scrubbing, comment trimming, Hungarian for strings added earlier, duplicated helpers/strings: upstream preparation, done per PR later (owner's choice).
- **Now Bar** (feat/samsung-now-bar): dropped from `personal` (eb8ea6efc); its review items are moot.
- **Copilot's "persistent episode continuity" note**: not acted on (product question, not promoted to a bug by Copilot either).

## Integration on `personal`

- Merges of each fixed branch (see `git log --first-parent personal`).
- `feat/dexcom-g7-watch` was rebased, so `personal` held its earlier copy (a994de829). Merge aa845e4ab merged every file the branch touches with a994de829 as the base, bringing in only the fix delta; its file set matched the old→new branch delta.
- Quick entry × Nightscout (merge 71c06aa20, `JournalRepository.kt`): new entries go through `upsertEntries` (one transaction, `writeEntry(…, received = null)` must not be null), a received copy goes through `upsertReceivedNightscoutEntry` (writeEntry may return null when a pending edit is kept), and `afterEntryWritten` takes the incoming source for the "edit of a received treatment" wake-up. `JournalCompose.kt` merged ignoring whitespace (the form body was re-indented) and the moved form computes its own `timeEditable`.
- 88008d003: the settings draft's per-field merge names personal's extra settings.
- eb8ea6efc: Now Bar dropped.

## Known gaps (reported by the fixers)

- #12: after a watch restart, until the phone's next ownership report, a watch not asked to read an alongside G7 can still dial it.
- #13: `SensorIdentity` managed-driver lookups can reach `mygatts()` on a cache miss (pre-existing; now only while a sensor is released).
- #9: if `release()` closes a Dexcom GATT mid-session, the session wake lock runs to its 2-minute timeout.
- #16: a record newly converted to Dexcom gets a 10-day lifetime (no lifetime in the handoff).
- #18: no per-entry upload status in the journal UI; a refused edit is only visible in the log.
- #22: verified by reading; no UI test.
- Device testing pending for all of the above.
