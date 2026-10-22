# Staying close to upstream (analysis of 2026-10-10)

`main` = b8f3e2e3b, `upstream/main` = 3d5f6b1a7 (26 ahead), `personal` = 1d10dc94c.
Forecasts from `git merge-tree --write-tree`; nothing was moved. The remote ref
`upstream/pr-595` (8ba5f2fd9) was added for the checks.

**Done 2026-10-12:** synced to `main` 4f9a7e01e (#595 merged; #617 and #621 too). What
happened and the new personal-only commits: BUILD.md "Last full sync"; behaviour changes and
the device test: NEXT.md "Where things stand (2026-10-12 evening)". The forecasts below are
from before that sync.

## Upstream news (main..upstream/main)

- **Sensor/data fixes:** #599/#602 Ottai; #499 live bridge readings calibrated before
  alerts, recorded values gated by lane (AlertRuntimeManager, SuperGattCallback); #603 Libre 2
  BLE cursor protected from WearSync2 imports (**WearSync2.kt**); #560 Clone; #604 AAPS
  sender stamp no longer a note (**JournalTreatmentTransfer/JournalDao**); #600 iCan an hour
  late, ghost main sensor; #614 hidden source lines for calibrated secondary sensors.
- **UI series:** #584–#612: one top bar, 4 dp grid, M3 shapes, one NavHost
  (MainNavigation rewritten), `collectAsStateWithLifecycle`, sentence-case copy in 16
  locales, font scale capped 1.3x, **material3 1.5 / Compose 1.13 beta** (expressive
  theme), ShortNavigationBar, colour roles, Emphasized type. #612 deleted
  `FloatingSettingsSheet` and other dead sheets.
- **CI:** a UI guardrail ratchet on every PR (`scripts/ui-guardrails.py`,
  docs/ui-style-guide.md): new hex colours, off-grid dp, `collectAsState`, font-weight
  overrides, hand-built `TopAppBar` fail. On Windows the script over-counts (exclusions use
  `/`, `relpath` gives `\`; one-line fix `rel.replace(os.sep, "/")`: a friendly first PR).
- **Open, next:** #617 (91 files: expressive buttons, a material3 FAB menu for the journal
  "+", rule: every button passes `shapes =`). Also #595 (Dexcom), #616 (Nightscout copy),
  #615 (peer sensor in the pill's secondary slot), #574 (Anytime → Health Connect), #555
  (Nightscout follower scheduler), #218 (compression-hold low, **Room v33**), #398 (add
  menu), #257 (delta-matched arrows).

## Conflict forecast per branch

| Branch | vs upstream/main | vs main+#617 | Nature |
|---|---|---|---|
| fix/dexcom-g7-link | clean | clean | vs #595: DexGattCallback 5 hunks, a test |
| feat/dexcom-g7-watch | WearSync2 2 hunks (#603), ExchangeSettings 1 | same | WearSync2 semantic: #603's `mirrorNative` gate + our gap fill |
| feat/floating-glucose | Service imports, DisplaySettings 2 | same | mechanical; +#615 Overlay 83 lines |
| fix/wear-app | clean | clean | |
| feat/glucose-display | clean | JournalActionMenus 233 + FabMenuPopup | scrim made obsolete by #617 |
| fix/nightscout | JournalDao, Transfer, tests | + JournalCompose | append/append with #604 |
| fix/health-connect | clean | clean | #574 would conflict |
| feat/persistent-low-alarm | AlertSettingsScreen 1 hunk | same | small |
| fix/wear-alarm-settings-sync | clean | clean | |
| feat/alarm-routing | AlertSettingsScreen 1 hunk | same | small |
| feat/alarm-history | MainNavigation 2 hunks | | trivial: a route into `appDestinations` |
| feat/quick-treatment-entry | JournalCompose 3 hunks / 297 | 420 + Dashboard, Journal | **heavy** |
| fix/dashboard-chart-bounds | DashboardChart, DashboardScreen 206 | same | heavy, hot files |
| feat/readings-as-dots | DashboardScreen 204 | same | substance 2 lines |
| feat/alarm-settings-save | AlertSettingsScreen 6, CommonAlertSettings, QuietWindowCard | | most alert-UI conflicts |
| feat/widget-rework | MainNavigation (trivial) | + JournalActionMenus | |
| **personal** | **15 files, ~1,115 lines** | 16 files, ~1,480 | strings.xml union clean |

Not visible to merge-tree:
1. Compose 1.13 beta / material3 1.5: our code never compiled against them; build first.
2. Guardrail regressions (only matter for upstream PRs): floating +20 `collectAsState`, 3
   hex; persistent-low 4 hex; quick-treatment off-grid dp, radii, font weights;
   settings-save top bar etc.
3. **Room DB:** quick-treatment is on v33 (the owner's phone already is). If upstream lands
   any v33 (#218 defines `MIGRATION_32_33`), personal must renumber and migrate both. Check
   `HISTORY_DATABASE_VERSION` at every sync.
4. BUILD.md "Updating" step 2 ("resolve with $OLD's version") would revert upstream's
   changes where an inter-branch conflict file was also changed upstream: this time
   `AlertSettingsScreen.kt`/`CommonAlertSettings.kt` and mobile `styles.xml` (#598). Merge
   those by hand.
5. Personal-only code commits: `496268905` and `88008d003` (alarm draft wiring; the
   second is missing from BUILD.md's list) go with settings-save; `d0ba1dedc`, `c657a0c30`
   (+ Insulin/+ Food) keep; `08bf24170` (delete the + type menu) **do not replay** (modify/
   delete conflict with #585/#617); `96cd4c439` optional.

## PR #595 vs our Dexcom branches

| Our change | In #595 | Verdict |
|---|---|---|
| autoConnect for a bonded G7 (`8b059f541`, `4c07e4930`) | per-sensor "prefer background connect" after a failed dial or any data response; 45 s direct timeout | superseded |
| re-arm 5 s after a session + rearm wake lock + hooks (`7b8e9da62`, `46c7f259f`) | 5 s quiet minimum; delayed connects via wakeup alarm | superseded |
| stale-GATT checks under the monitor (`196413f4a`), `bonded()` guard | synchronized callbacks, `gatt != mBluetoothGatt` guards | superseded |
| `onCharacteristicChanged` reorder, `onConnectionUpdated` removed | – | cosmetic: drop |
| one timed wake lock (`a27aa7f1a`) | releases in close/pause, still a new untimed lock per CONNECTED | overlap: tiny follow-up |
| **close the GATT in `stop`; `release()` closes every alias's GATT** (`cbba98e50`) | not covered | keep, send |
| scan-start limit; bond receiver once; Wi-Fi gate; watch loss alarm not an alarm clock | not covered | keep, send |
| g7-watch hooks (`holdsSensor`, `readsAlongside`, `stopTransport`, slot 3, handoff/adopt) | not covered, same methods | keep (personal) |

Against #595's head: g7-link conflicts in DexGattCallback (5 hunks) and a test; g7-watch
has **two silent compile breaks**: #595 removes `maxdaysDex` (used by our `becomeDexcom`
and `dexAdoptSensor`; `becomeDexcom` also hard-codes 10 days) → derive the lifetime from
the GS1 code; #595's `connectDevice` calls `blocksLocalConnection(String)`, which g7-watch
removed → `(this)`. Bonus: #595's copy-on-write roster defuses Copilot's O-13 lock order.

Risks to raise in #595 (inferred): every delayed Dexcom connect, including the 5 s quiet
period after each session, becomes a `setAlarmClock` (wakes from Doze every 5 min, may
show as the "next alarm"; the opposite of what g7-link did for the watch loss alarm);
on Wear it unbonds after ~3 empty sessions (must not trip at a sensor change).

Recommendation: don't send g7-link as a PR. Rebuild it **stacked on `upstream/pr-595`**
with only the not-covered items, rebase g7-watch on it with the two compile fixes, test on
both devices and report in #595 (the maintainer asks for exactly that device test). When
#595 merges, send "Dexcom: close the GATT of a stopped callback" and "Bluetooth/battery
hygiene". Don't hold the rest of the sync for #595.

## Slimming

| Branch | Recommendation |
|---|---|
| fix/dexcom-g7-link | SHRINK to the not-covered items on #595, then send |
| feat/dexcom-g7-watch | KEEP (personal). Send `8e808baa7` (`requestSync` ms/s) and stream-slots `fixedStart` as fixes; propose "watch reads alongside on slot 3" in an issue. Fix the sensor-change bugs first |
| feat/floating-glucose | SHRINK: drop the next-reading style picker (pick one), the `FloatingPillWatchdog` repair (after the 10-14 check), the card-opacity setting (`5805b1f76`, 19 files), tap tracing. Send: channel fix (`d5083389f`, `093cf852e`), One UI frame clock (`67b53f445`), below-status-bar |
| fix/wear-app | KEEP, send first (issue #262) |
| feat/glucose-display | SHRINK: drop the scrim (`7da1ae713`, `6317abc88`; #617 replaces the menu); "Searching" goes with the Dexcom PRs; coloured icon personal or opt-in PR |
| fix/nightscout | KEEP, send after the device test |
| fix/health-connect | KEEP, send soon (comment in #574 first) |
| feat/persistent-low-alarm | KEEP, send after replacing hex colours (overlaps #218) |
| fix/wear-alarm-settings-sync | KEEP, send |
| feat/alarm-routing | KEEP (personal); send the persistent-high fix and the watch full-screen alarm |
| feat/alarm-history | KEEP (personal); use `AppTopBar` |
| feat/quick-treatment-entry | SHRINK: drop `08bf24170`, use upstream's FAB menu with items opening our sheet; send the DB part (step/default dose/reminders) early so upstream owns v33 |
| fix/dashboard-chart-bounds | DROP (caused items 9 and 10); at most the pan clamp as a small PR |
| feat/readings-as-dots | DROP unless used |
| feat/alarm-settings-save | DROP (most alert-UI conflicts) |
| feat/widget-rework | KEEP (personal) only if widgets are used daily; else DROP. Either way an issue with the run-3 Glance numbers |

Effect: ~6.5k fewer production lines; DashboardChart, DashboardScreen,
CommonAlertSettings, QuietWindowCard, JournalFabMenuPopup and most of AlertSettingsScreen
leave the conflict list. Left: mostly mechanical, except JournalCompose and WearSync2.

## Contributing upstream

- No CONTRIBUTING or PR template. CODEOWNERS reserves `.github/`, `*.gradle`,
  `Common/build.gradle`, dist scripts. CI: JVM suites (JDK 21, Linux), phone + wear release
  with R8, `ArchitectureGateTests`, the guardrail ratchet. Style: sentence case, all 16
  locales incl. `values-hu`, theme roles, `AppTopBar`, `collectAsStateWithLifecycle`.
- 145 of the last ~400 PRs were external and merged, median 11 h. Small, test-backed fixes
  titled "Area: what changes" go fast; big ones are held and split; feature PRs from others
  sat 3–4 weeks. A non-developer's commit link was adapted by the maintainer into #595 with
  credit.
- Approach: fixes as small squashed test-backed PRs on the latest main, passing the
  guardrails; features start as an issue with device evidence; where the maintainer is
  working (Dexcom, UI), comment on his PR instead of competing; strip serials and personal
  wording (REVIEW.md §2).
- Order (replaces REVIEW.md §3): 1 wear-app swipe-back (closes #262); 2 in #595's thread:
  device test, the alarm-clock/Doze note, the stop-branch GATT leak; 3 `requestSync` ms/s;
  4 floating: notification channel, One UI frame clock; 5 wear-app toggles / open at top /
  hide calibration, then complication freshness, then the shared trend rule; 6
  health-connect; 7 Nightscout (404/v3, backoff, refused receive; then Wi-Fi only; deletes;
  received edits; follower end); 8 range colours + watch ranges, G7 "Searching"; 9 after
  #595: Dexcom GATT close, Bluetooth/battery hygiene; 10 wear-alarm-settings-sync,
  persistent-low, persistent-high fix, watch full-screen alarm; 11 issues first: watch reads
  G7 alongside, alarm routing/shared silence, quick entry (DB PR first), widget battery.
  New from 2026-10-10 (NEXT.md findings), all upstream bugs: the warmup retry and the
  connect-pending latch, the 0.1 value gap (one conversion), the expiry alarm's end, the
  island centring, a startup sync request.

## Rebase plan for the next sync

1. Owner decides the drops (and a pill style). Tag them `dropped/<name>`.
2. Wait for #617 (ideally #615/#616). Back up the tags, `git fetch upstream`. Optionally
   `git config rerere.enabled true` for this repo (owner's call).
3. On the old base, in a worktree: remove the dropped parts; fold fixup chains into
   PR-sized commits (floating has 24 of 32 commits touching conflicted files); rebuild
   g7-link on `upstream/pr-595`.
4. Rebase: clean ones (wear-app, health-connect, wear-alarm-settings-sync, glucose-display);
   one-hunk ones (persistent-low, routing `--onto`, alarm-history and widget route line,
   floating); medium (nightscout keep both with #604; g7-watch `--onto` the new g7-link with
   the WearSync2 gate and the 2 compile fixes); heavy last (quick-treatment: JournalCompose).
5. Rebuild personal from the new main in table order. Inter-branch: g7-watch × routing
   (`SensorOwnershipRuntime.kt`, $OLD fine); widget × quick-treatment
   (`MobileVariantBootstrap.java` $OLD fine; mobile `styles.xml` by hand). Replay only
   `d0ba1dedc`, `c657a0c30`.
6. Verify: both release builds, `scripts/personal/test.sh`, no duplicate `name=`, DB
   version above upstream's, the tree comparison per kept file.
7. One device round: G7 on #595 (locked phone, both reading, forced reconnect), floating,
   Nightscout, alarms; watch deep idle vs BATTERY run 4/5.
8. BUILD.md: the table, the personal-only list (+`88008d003`), the $OLD caveat,
   `upstream/pr-595` as the Dexcom base until it merges.

Working files of this analysis (merge-tree and guardrail outputs) were in the session
scratchpad (not kept).
