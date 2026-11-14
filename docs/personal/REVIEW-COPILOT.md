# JugglucoNG independent fork review

Date: 2026-10-08  
Reviewer: GitHub Copilot, powered by GPT-6.1 Sol  
Companion report: Opus's `docs\personal\REVIEW.md`

## Outcome and scope

The combined fork is not yet ready for upstream submission. Successful operation on the owner's current phone, watch, and bonded Dexcom G7 is useful evidence, but does not exercise fresh pairing, rejected authentication slots, restricted Nightscout permissions, rapid silence changes, persistence failures, or other devices' background Bluetooth behavior.

The review covered all documents in `docs\personal`, the history and integration of all 17 active topic branches, and changed production paths in alarms, G7 transport and ownership, native storage and synchronization, Nightscout, Health Connect, journal entry, reminders, widgets, floating display, notifications, watch display, and charts. It was a risk-focused static review, not an exhaustive line-by-line certification of all 345 changed files.

The review itself was read-only. No repository files, branches, commits, builds, tests, dependencies, or devices were changed. An exported scratch snapshot was used to avoid being affected by concurrent edits or branch switches, and was removed after the audit.

### Immutable baseline

| Reference | Commit |
|---|---|
| Upstream/local `main` baseline and merge base | `b8f3e2e3b1c882b17d828ae4042cf3b153c99a2a` |
| Reviewed `personal` snapshot | `970c21afa18773c357ae908abb981994aafbedff` |
| Published source-equivalent parent | `2b397ca9704dc840ae1cc7e24925ada915be2a86` |

The reviewed snapshot differs from the published parent only by the addition of `docs\personal\REVIEW.md`. Production-code links in this document therefore use the published parent. All source paths and line numbers refer to this pinned code, not a subsequently checked-out branch.

The complete fork diff against the baseline contained 345 files, 37,645 insertions, and 2,827 deletions. All 17 active topic tips were ancestors of the reviewed `personal` commit. At the end of the audit, local branch tips were unchanged and the working tree was clean. These findings have not been reassessed against any later fixes.

### Interpretation

- **P1:** fix before relying on the affected feature or submitting it upstream.
- **P2:** substantive correctness, performance, or compatibility issue.
- **Confirmed:** the relevant code path and failure mechanism were established by static inspection; this does not mean a runtime failure was reproduced.
- **Qualified:** a code change or risk is real, but the claimed runtime consequence or proposed correction needs more evidence.
- **Product/compatibility:** deliberate fork behavior that needs an upstream decision or migration policy, rather than automatic classification as a bug.

## 1. Additional findings beyond Opus's report

### C-01. P1: A reminder can record a different dose from its button label

**Evidence:** [InsulinReminders.kt:204-209](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/mobile/java/tk/glucodata/journal/InsulinReminders.kt#L204-L209) and [300-308](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/mobile/java/tk/glucodata/journal/InsulinReminders.kt#L300-L308).

**Status:** confirmed, high static confidence.

The notification labels its Log action using the preset's default dose when the notification is posted. The PendingIntent carries the preset, slot, and due time, but no dose snapshot. When tapped, `onLog()` reads the current default from the database.

**Failure scenario:** a reminder says "Log 25 U"; the preset default changes to 30 U while that notification remains visible; tapping the old action records 30 U. Dose-only changes do not refresh the notification because preset observation is reduced to reminder slots before `distinctUntilChanged()`.

This does not administer insulin, but it records and can export a different treatment amount from the one the user selected.

**Correction:** bind the action to the displayed dose and a relevant preset revision, or require reconfirmation if the preset changed. Do not silently substitute a newer amount.

### C-02. P1: External quick entry reports success before persistence succeeds

**Evidence:** [JournalQuickEntryActivity.kt:193-203](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/mobile/java/tk/glucodata/ui/journal/JournalQuickEntryActivity.kt#L193-L203) and [JournalQuickEntrySession.kt:148-164](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/mobile/java/tk/glucodata/ui/journal/JournalQuickEntrySession.kt#L148-L164).

**Status:** confirmed, high static confidence.

The standalone sheet displays "Saved..." immediately after launching asynchronous persistence. A write failure completes its returned Deferred exceptionally, but the normal success UI never awaits that Deferred; only Undo does. Failure is logged instead of being surfaced in the save flow.

`InsulinReminders.onEntriesSaved()` is also called before successful persistence, cancelling a basal reminder even if the entry is not stored. Multi-input food/insulin saves perform separate repository writes, so failure can leave a partial save.

**Failure scenario:** a database write fails, but the sheet claims success and closes after its Undo interval; the reminder has disappeared without a committed dose. A combined save can persist one entry and fail on the other.

**Correction:** show success and cancel reminders only after successful persistence. Save related entries atomically and present a recoverable error on failure. Ensure Undo corresponds to the actual committed result.

### C-03. P1: Rapid snooze or quiet-window cancellation does not synchronize

**Evidence:** [AlarmSilenceModel.kt:110-128](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/main/java/tk/glucodata/alerts/AlarmSilenceModel.kt#L110-L128) and [179-188](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/main/java/tk/glucodata/alerts/AlarmSilenceModel.kt#L179-L188).

**Status:** confirmed, high static confidence.

`isNewer()` treats changes within five seconds as the same event. Reconciliation rejects the incoming event on that basis before inspecting whether its actual snooze or quiet-window contents differ.

**Failure scenario:** start a quiet window on the phone, let the watch receive it, then cancel it two seconds later. The watch rejects the cancellation as insufficiently newer and can remain silent until the original expiry. Changing or cancelling snoozes has the same problem.

The relative-age wire format and timestamp tolerance do not provide a stable identity for distinguishing a retransmission from a new user action.

**Correction:** use stable event or revision ordering. Transit tolerance must not collapse distinct user actions. Cover rapid start/cancel and duration changes, not just retransmission and widely separated changes.

### C-04. P2: A truncated Nightscout read can remove deletion protection

**Evidence:** [JournalTreatmentUploader.kt:724-746](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/mobile/java/tk/glucodata/data/journal/JournalTreatmentUploader.kt#L724-L746), its `TREATMENT_FETCH_COUNT = 240`, and `NightscoutJournalFollowerImporter.kt`'s pending-delete checks.

**Status:** confirmed, high static confidence.

With sending disabled, tombstones are dropped when their documents are absent from the treatments response. That response contains only the latest 240 treatments, rather than an authoritative lookup of each deleted document.

**Failure scenario:** an older treatment is deleted locally on a receive-only setup. It is outside the next recent page, so its tombstone is removed even though the treatment remains on the server. A later historical follower import can restore it.

This conclusion applies to the receive-only settlement path. The sending-enabled settlement path only considers confirmed deletes; it should not be described as indiscriminately clearing all unsent tombstones.

**Correction:** retain local deletion protection unless an authoritative lookup confirms absence. Separate local suppression from remote-delete retry and abandonment state.

### C-05. P2: Widget chart caching omits rendering settings

**Evidence:** [WidgetChartCache.kt:9-21](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/mobile/java/tk/glucodata/widget/WidgetChartCache.kt#L9-L21), [GlucoseWidgets.kt:80-88](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/Common/src/mobile/java/tk/glucodata/widget/GlucoseWidgets.kt#L80-L88), and the settings read inside `NotificationChartDrawer.drawChartInternal()`.

**Status:** confirmed, high static confidence.

The bitmap cache key omits the line/dots style, chart range-colour preference, and band thresholds that the chart renderer reads separately. These inputs can change without changing the reading or history fingerprint.

Even a forced widget redraw discards `lastModels` but retains the chart bitmap cache.

**Failure scenario:** the settings preview uses the new rendering style, while applying settings or forcing a redraw reuses the actual widget's old chart. It remains old until a reading, history, or another key component changes.

**Correction:** include all relevant rendering inputs in the cache key or explicitly invalidate the chart cache when those inputs change. Do not assume that clearing the RemoteViews model also invalidates its bitmap.

### C-06. P2: The documented rebuild omits personal-only integration code

**Evidence:** [BUILD.md:275-280](https://github.com/imjustarandomguy/JugglucoNG/blob/2b397ca9704dc840ae1cc7e24925ada915be2a86/docs/personal/BUILD.md#L275-L280) and the personal-only commit set.

**Status:** confirmed, high static confidence.

The reset/remerge procedure restores only documentation, scripts, and `CLAUDE.md`. It does not replay personal-only application commits, including:

| Commit | Integration |
|---|---|
| `496268905` | Alarm draft integration with personal routing/watch-style settings |
| `d0ba1dedc` | Floating details-card quick-entry buttons |
| `c657a0c30` | Those buttons following the Quick log buttons setting |
| `08bf24170` | Removal of the obsolete journal type menu |

Re-merging topic branches alone does not preserve these changes. The guide also names an outdated integration commit and lists dependencies under deleted predecessor branch names.

This is a reproducibility and behavior-loss issue, not a claim that the old commits become irretrievably lost.

**Correction:** make required integration an explicit dependency or documented replay step. The rebuilt personal branch should preserve its intended behavior without undocumented conflict-resolution knowledge.

## 2. High-priority Opus findings independently confirmed

### O-01. P1: Persistent-low remains suppressed after Very-low

`Common\src\main\java\tk\glucodata\alerts\PersistentLowPolicy.kt:146-151` latches `veryLowSeen` for the entire low episode. The urgent alarm can be dismissed and glucose can remain between the Very-low and Persistent-low thresholds, yet Persistent-low never fires until recovery to its rearm line.

The implementation and a test deliberately pin that latch. That establishes the mechanism, but does not make the intended protective behavior satisfactory.

**Correction direction:** define when urgent-alarm coverage ends and rearm Persistent-low from the appropriate reading. Do not hold it forever solely because an earlier reading entered Very-low.

### O-02. P1: A held routed alarm consumes its local episode

`AlertRuntimeManager.kt:976-996` records a routed-away alarm through `AlertStateTracker.onAlertHeld()` and returns success-shaped delivery bookkeeping.

**Failure scenario:** the phone gives a Very-low episode to the watch, then the watch disconnects, loses power, or goes on its charger. The phone has already consumed the episode and does not provide local delivery for it.

This is unavailable-watch fallback, not unanswered-alarm escalation, which the owner explicitly declined.

**Correction direction:** retain routed-away pending delivery and reconsider current routing availability without falsely recording local delivery.

### O-13. P1: Callback and roster code have reverse lock ordering

Relevant paths include:

- `SuperGattCallback.java:1423-1434`: the connection runnable holds the callback monitor while consulting `blocksLocalConnection()`.
- `SensorOwnershipRuntime.kt`: ownership-key lookup for short aliases or `findGatt()` can call `SensorBluetooth.mygatts()`.
- `SensorBluetooth.java:1598-1658`: roster rebuilding holds `gattcallbacks` and calls synchronized callback `free()`.

The reverse lock order exists. The key cache and direct-request/routing state affect whether a particular lookup takes the roster lock; not every connection or cache miss does.

**Consequence:** concurrent connection and roster teardown can deadlock acquisition/recovery. No runtime deadlock was reproduced in this review.

**Correction direction:** establish one lock order and avoid roster lookup while holding a callback monitor. Passing the relevant callback or a suitable snapshot is preferable to relying on a cache to avoid the lock.

### O-12. P1: Legacy or standalone watch G7 connections are blocked

`SensorOwnershipRuntime.kt:558-563` blocks alongside-capable watch callbacks unless `WearSensorClaim.isDirectRequested()` is true. The new persisted preference defaults to false.

No migration or standalone exemption was evident for a watch already configured to read its sensor without the new phone-side request.

**Correction direction:** preserve legitimate legacy/standalone direct operation while retaining deliberate companion-mode restrictions.

### O-11. P1 compatibility: Rejected watch authentication silently reuses the receiver slot

`DexGattCallback.java:691-704` persists receiver-slot fallback when fresh watch-slot authentication is rejected.

The fallback code is confirmed. Displacement of an existing receiver or pump pairing is a potential hardware consequence, not a reproduced result from this audit.

**Correction direction:** do not silently reuse another display's channel as an upstream default. Require an explicit compatible policy or report the pairing failure.

### O-18. P1: A refused received-treatment edit blocks unrelated uploads

`JournalTreatmentUploader.kt:479-496` breaks the shared pending queue on an edit failure or that entry's backoff.

**Failure scenario:** a token permits creating treatments but denies editing an existing received document. Its pending edit prevents later manual treatments from uploading; retries and native backoff can keep the queue stalled.

**Correction direction:** isolate the failed operation and let unrelated entries proceed. Preserve the user's unsent edit and expose its failure; do not mark it successfully uploaded merely to unblock the queue.

## 3. Assessment of all 35 Opus items

Numbers below refer to findings in Opus's `REVIEW.md`, not GitHub issue numbers. Its line numbers were taken from topic tips; this document's main findings refer to the pinned merged snapshot.

| # | Assessment | Independent conclusion and correction caveat |
|---|---|---|
| 1 | Confirmed, P1 | The Very-low latch can suppress Persistent-low for the remainder of the low episode. See O-01. |
| 2 | Confirmed, P1 | Held routing consumes the episode and defeats later unavailable-watch fallback. See O-02. |
| 3 | Qualified routing concern | Capability discovery and charging reports trusted for up to about 31 minutes are weak alarm-readiness evidence. A recent direct-sensor reading is not by itself the right replacement: a companion watch may legitimately alarm using mirrored readings. |
| 4 | Product/compatibility | Screen-only plus watch-preferred routing is intentionally silent in sound/vibration terms. It requires explicit upstream agreement and clear UX, rather than automatic classification as an unintended implementation bug. |
| 5 | Product/UX | Home or process death not applying a dirty draft follows explicit Save semantics; the draft is persisted. An unsaved-changes indicator can help, but auto-save on stop would contradict the feature contract. |
| 6 | Confirmed, P2 | `AlertSettingsDraft.kt:119,154-164` rebases an edited alert/global object as a whole. Saving can overwrite unrelated concurrent changes within that object. Prefer a per-field three-way merge. |
| 7 | Product/compatibility | Shared quiet windows in Both mode were requested for the fork. Existing upstream users need an explicit compatibility/migration decision. |
| 8 | Confirmed branch-isolation issue | The standalone `fix/wear-alarm-settings-sync` tip carries alert `c:` lines but not the global `g:` line supplied by routing. The current BUILD description overstates what that branch provides alone. |
| 9 | Confirmed cleanup hole | Stopped GATT state callbacks return without closing the current GATT, while ownership release disconnects rather than closing the transport. Partly pre-existing, but tightly coupled to the new ownership/background-connect paths. Cleanup must respect GATT identity and callback lifecycle. |
| 10 | Qualified, fresh-pairing risk | Removing `!newcertificates &&` changes the phone's fresh-pairing path too. The change is real; an actual fresh-phone-pairing failure was not demonstrated. Current bonded operation is not coverage of that path. |
| 11 | Confirmed fallback; qualified hardware consequence | Reusing receiver slot 1 is real. Receiver/pump displacement remains a potential compatibility consequence requiring targeted verification. See O-11. |
| 12 | Confirmed legacy/standalone gate | Default-false direct intent can block previously valid watch-only operation. See O-12. |
| 13 | Confirmed lock-order risk | Reverse callback/roster lock order exists; execution depends on state and lookup path. No deadlock was reproduced. See O-13. |
| 14 | Qualified portability concern | Automatic background reconnection lacks an evident mode-changing recovery policy, and the global autoConnect option can affect unbonded paths. "Miss forever" and a blanket one-missed-session direct fallback require device evidence, not assumption. |
| 15 | Confirmed bounded inefficiency | The rearm lock runs to its seven-second timeout rather than releasing on completed or cancelled rearm. Keep it through the delayed attempt; releasing immediately after scheduling would revive the sleep problem. |
| 16 | Confirmed destructive branch; lifetime qualification | `dexcom/java.cpp:669` reinitializes when handed start time differs exactly; `becomeDexcom()` rebases/clears storage and writes ten-day lifetime metadata. Preserve/migrate history and validate variant lifetime behavior. An arbitrary tolerance alone is not a complete solution. |
| 17 | Product/compatibility with provenance risk | Editing/deleting received Nightscout documents was requested behavior. Upstream needs explicit consent and provenance protection, especially for loop-system documents; do not call the entire feature accidental. |
| 18 | Confirmed, P1 | A refused received edit blocks independent uploads. Separate failure state from queue progress, without discarding user intent. See O-18. |
| 19 | Proposed fix rejected | Other 5xx responses and non-Nightscout pages do not prove a deletion should be abandoned. Preserve deletion protection and distinguish retry/dead-letter state from local suppression. |
| 20 | Confirmed, P2 | `srvModified` is compared directly with local `updatedAt` in send planning and receive replacement. Clock skew can make an unchanged server copy defeat a pending local edit. Track the received server revision/base rather than ordering unrelated clocks. |
| 21 | Confirmed, P2 | Health Connect repeatedly upserts a 14-day window, setting new update times and causing outbound work. It can overwrite local edits and recreate locally deleted source rows. A persisted last-run time alone is insufficient; use source-change/revision and deletion policy. |
| 22 | Confirmed performance concern | Five unplaced sizing sheets still execute Compose effects, tickers, and Room loads. Separate sizing from interactive behavior; simply making a form unplaced does not stop its effects. Device cost was not measured. |
| 23 | Product choice; minor optimization | Quick buttons and shortcuts default on as requested for the fork. Upstream defaults require agreement. Rebuilding two PendingIntents per reading is lower priority than history/DB work; cache only where lifecycle and intent semantics permit it. |
| 24 | Product/migration choice | The owner explicitly chose a 1 U step. Migrating upstream users from an existing 0.5 U behavior needs a compatibility decision; it is not a wrong owner requirement. |
| 25 | Qualified reminder behavior | Notification permission denial silently prevents reminders. Exact-alarm denial already uses an inexact fallback; explain capability and handle excessive lateness. Title-based legacy dose matching can be ambiguous, but not every title match is erroneous. |
| 26 | Confirmed, P2 | The details window retains its opening request; data loading is keyed to that request and age has no freshness ticker. A card left open can present old data and age while the pill advances. Use live state or clearly identify a snapshot. |
| 27 | Qualified sleep/timing concern | Handler-delayed trailing delivery uses uptime and can be postponed while the CPU sleeps. The elapsed-realtime throttle calculation does not change Handler scheduling. Exact visible impact requires lifecycle/device evidence. |
| 28 | Confirmed performance concern | `WatchRenderer.kt:306` calls `currentReading()` during rendering, which now calculates display rate using a history load even for value-only use. Cache by reading identity and relevant display/configuration revisions. |
| 29 | Qualified shared-trend concern | The helper changes affect the phone as well as the watch. Different 30-second, 60-second, and bucket rules need cadence/source-aware reasoning. A universal 60-second merge can collapse legitimate one-minute readings. |
| 30 | Confirmed chart behavior regression, P2 | The right-edge clamp ignores future journal marker/activity ends. The intended no-empty-time rule needs reconciliation with existing insulin curves and future-dated entries, not just a glucose/prediction bound. |
| 31 | Confirmed edge-clipping concern; lower priority | Auto-range expands only for values outside its baseline, while strokes/dots near the clip edge can be cut. Describe partial stroke/dot clipping accurately, not necessarily complete disappearance of a reading. |
| 32 | Confirmed remembered-state behavior, P2 | List-driven chart collapse feeds the same resting-anchor persistence path, overwriting a previously chosen height. Distinguish a deliberate height choice from temporary list-driven collapse. |
| 33 | Qualified density/design concern | Radius depends on visible duration, not pixel spacing/cadence. One-minute charts can form a band sooner than the owner's G7 charts. Validate width, cadence, and density before imposing a universal cap. |
| 34 | Product/migration choice | Detail/next-reading defaults affect existing overlay users; they do not enable the master overlay for everyone. Range-colour defaults change placed widgets on upgrade. Upstream needs an explicit migration policy. |
| 35 | Confirmed lifecycle/guidance concern; lower priority | Availability follows overlay options rather than the master disabled state, and sideloaded restricted-settings guidance is absent. The service requests no events/window content, so avoid overstating its observed cost. |

## 4. Supporting behavior and performance conclusions

### Alarm draft merging

Editing one property retains an entire `AlertConfig`. Restoring/rebasing overlays that entire edited object on current persisted values. Whole-object edits to global settings have the same risk.

Preserve fields the user actually changed, retain unrelated newer values, and define conflict handling when both sources edit the same field. Do not solve the concurrency issue by removing explicit Save semantics.

### Nightscout conflict and failure handling

`receivedEditPlan()` and `receivedCopyMayReplace()` use server modification time against a phone-created update time. Correct conflict detection needs a remote base/revision, not an assumption of synchronized clocks.

For retries, distinguish:

- a confirmed remote deletion or authoritative absence;
- an explicitly refused operation;
- an unavailable server, generic server failure, or non-Nightscout page;
- local suppression of a treatment the user deleted.

Opus's proposed broad final treatment of 4xx edits and narrow transient classification of 5xx deletes should not become silent-success or silent-drop behavior. A failed operation can stop retrying automatically while remaining visible and preserving local user intent.

### Health Connect import

The importer repeatedly reads all pages in the 14-day exercise/step window and upserts unchanged records. Those writes change `updatedAt` and can wake outbound synchronization.

Persisting an import timestamp may reduce restart churn but does not establish whether a source record changed, preserve a local edit, or remember a local deletion. Import idempotence, source updates, and user overrides/deletions need a coherent policy.

### Display computation

The strongest structural performance concerns are actual repeated work, not speculative micro-optimizations:

- hidden sizing-only forms execute interactive effects and queries;
- watch-face rendering requests history and trend calculation repeatedly;
- unchanged Health Connect imports generate database and outbound work.

The widget rework's screen-off deferral, shared data load, serialized rendering, and unchanged-model suppression are sensible. Fix cache completeness rather than abandoning that design.

### Floating details and chart state

The details card should either follow current reading/history/journal state or clearly state that it is an opening-time snapshot, with truthful age. Opacity recomposition is not a freshness policy.

Chart viewport limits should account for meaningful future journal content. A temporary collapse caused by list scrolling should not automatically overwrite a user-selected remembered height. Dot sizing and edge clipping need coverage beyond a five-minute G7 cadence.

### Investigative note: persistent episode continuity

Running Persistent-low/high state has no explicit reading-gap or selected-sensor boundary, while history reconstruction uses a gap cutoff. This may produce different episode starts before and after restart or after source changes.

This was not promoted to a confirmed unintended bug: retaining a low episode across a missing sample is explicitly documented, and sensor/source continuity requires a product decision. Validate long gaps and source transitions without treating missing data as evidence of physiological recovery.

## 5. Branch and upstream readiness

### Reviewed active topic tips

| Branch | Pinned tip | Main review area |
|---|---|---|
| `feat/alarm-history` | `93b2f25b3` | Event persistence, watch transfer, active snooze history |
| `feat/alarm-routing` | `242210928` | Held delivery, availability, shared silence, high episodes, watch style |
| `feat/alarm-settings-save` | `7e3be27f9` | Draft persistence, explicit Save, concurrent changes, integration |
| `feat/dexcom-g7-watch` | `a994de829` | Slots, ownership, handoff, aliases, native adoption, gap fill |
| `feat/floating-glucose` | `e500103a5` | Window lifecycle, freshness, frame clock, details, accessibility |
| `feat/glucose-display` | `3b08c149b` | Shared colours/ranges, status display, refresh behavior |
| `feat/persistent-low-alarm` | `ed8c48482` | Low episode timing, Very-low coverage, suppression |
| `feat/quick-treatment-entry` | `cb8719979` | Journal sheet, dosing migration, async writes, reminders |
| `feat/readings-as-dots` | `6e27ab6df` | Shared chart style, cadence/density, rendering surfaces |
| `feat/samsung-now-bar` | `c91fb9694` | Live notification, API gating, stale presentation, gauge |
| `feat/widget-rework` | `8ff35fc61` | RemoteViews refresh, screen gating, cache, configuration |
| `fix/dashboard-chart-bounds` | `69bf9cf02` | Viewport clamp, clipping, gestures, remembered height |
| `fix/dexcom-g7-link` | `196413f4a` | GATT lifetime, reconnect, wake locks, fresh pairing |
| `fix/health-connect` | `0dbc5ec9a` | Import idempotence, paging, permissions, export cursor behavior |
| `fix/nightscout` | `4904f123f` | Upload queue, deletes, follower import, conflicts, Wi-Fi policy |
| `fix/wear-alarm-settings-sync` | `f73748b8b` | Complete configuration transfer and standalone branch behavior |
| `fix/wear-app` | `8802bee7d` | Watch UI refresh, freshness, shared trend, complication/render cost |

The removed xDrip broadcast, coloured-complication, and hidden-notification experiments are historical/shelved work, not additional active features approved by this review.

### Integration and splitting

The strongest directions already present are shared display helpers, explicit alarm policies, missing-only gap fill, and bounded RemoteViews work. The main issues are state ordering, failure isolation, lifecycle ownership, and reproducible integration, not a need for a broad rewrite.

The large topic branches should be split into independently reviewable changes. Important dependencies include:

- complete shared alarm settings and synchronization before routing extensions;
- a clear owner for shared episode-history policy;
- G7 transport/storage prerequisites before alongside ownership and gap fill;
- shared display/trend helpers before their consumers;
- explicit integration for alarm drafts/routing and floating quick entry.

Duplicated helpers and colliding independently introduced settings types should have a clear owner or dependency. Examples include `EpisodeHistory`, `TrailingThrottle`, global alarm settings, delta walk-backs, and range classification. Keep extraction focused on established shared behavior rather than forcing all timestamp tolerances into one constant.

### Cleanup before submission

Replace identifying sensor fixtures and personal identifiers with synthetic examples. Remove temporary trace/experiment surfaces, abandoned style-picker options, and personal-only wording where it obscures general behavior. Preserve useful comments about invariants; comment percentage alone is not a defect.

Update `BUILD.md` to describe current branches and reproducible integration. Its claim that every topic is already one production-ready upstream change is not supported by this audit.

Complete directly related localization, including Hungarian coverage, and reuse existing strings where appropriate. Translation/style cleanup should follow the alarm, persistence, synchronization, and transport corrections.

### Compatibility decisions to retain explicitly

The owner deliberately selected several behaviors:

- no unanswered-alarm escalation and no special Very-low routing exception;
- Screen-only watch alarms without sound or vibration;
- shared quiet windows;
- explicit Save rather than automatic application of alarm drafts;
- received-treatment write-back;
- a personal default insulin step of 1 U;
- no broad sensor-identity rewrite.

An upstream proposal may choose different defaults or require opt-in/migration, but should not misrepresent these as accidental owner requirements.

## 6. Follow-up evidence needed

No tests or device experiments were run for this review. Suggested acceptance scenarios are derived from the findings, not completed validation:

| Area | Cases that would address the review gaps |
|---|---|
| Alarm delivery | Very-low followed by continued low; routed-away episode followed by watch disconnect, charging, or power loss |
| Shared silence | Rapid start/cancel and duration changes; retransmission and delayed/out-of-order delivery |
| Journal persistence | Write failure; partial multi-entry failure; Undo during pending save; reminder cancellation only after commit |
| Reminder actions | Default dose changed while notification remains visible; deleted/archived preset; permission and late-inexact-alarm behavior |
| Nightscout | Creates permitted but edits denied; an unrelated entry after a refused edit; skewed clocks; deleted document outside the newest 240 |
| G7 transport | Fresh phone/watch pairing; rejected watch slot with existing receiver/pump; legacy standalone upgrade; concurrent roster teardown and reconnect |
| Native adoption | Existing history with corrected start metadata; appropriate lifetime behavior for supported sensor variants |
| Health Connect | Unchanged repeated import; genuine source change; local edit; local deletion; process restart |
| Display/performance | Value-only watch-face renders without repeated history work; sizing forms without interactive effects; forced widget redraw after rendering settings change |
| Charts/overlay | Details card left open; future journal curves; temporary list collapse; one-minute cadence and varying chart widths |

Existing source-text assertions and test/code ratios are not substitutes for behavioral coverage of these paths. Battery observations from the owner's devices also do not establish performance across other controllers, Wear OS versions, or sensor cadences.

## Final disposition

Prioritize alarm delivery and silence ordering, truthful and atomic journal persistence, reminder action consistency, failure-isolated uploads, and transport lifecycle/compatibility.

After those corrections, address idempotent imports, repeated rendering work, cache invalidation, chart behavior, branch integration, and migration/default decisions. Localization and presentation cleanup remain necessary for upstream quality, but are not a substitute for resolving the confirmed failure paths.

This report documents the pinned audit. It does not claim later fixes have been reviewed, that predicted hardware failures were reproduced, or that the fork is bug-free.
