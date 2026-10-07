package tk.glucodata

import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorIdentityRegistry
import tk.glucodata.drivers.ManagedSensorRuntime

object SensorIdentity {
    private const val NULL_SENTINEL = "\u0000"
    private const val UNKNOWN_SENSOR_SENTINEL = "?"
    private const val NATIVE_PREFIX_LENGTH = 5
    private val nativeCanonicalCache = ConcurrentHashMap<String, String>()
    // raw id -> resolved app (canonical) sensor id. resolveAppSensorId iterates every driver's
    // identity adapter (each hitting SharedPreferences + regex), so it is far too costly to run
    // per glucose point per frame — which the calibrated-chart draw path does. The mapping only
    // changes when the sensor set / auth material changes, so memoize it and clear on those events.
    private val appSensorIdCache = ConcurrentHashMap<String, String>()
    // normalized "candidate\u0000expected" -> matches() result. The same reasoning as
    // appSensorIdCache above, for the one path it did not cover: managedMatches asks
    // every driver adapter to match live, and those adapters run regexes and registry
    // lookups. matches() is called per reading per sensor, from resolveAvailableMainSensor,
    // from dowithglucose, from resolveUiSnapshot and from the notification build, so the
    // matcher itself has to be memoized, not just the canonical-id lookup underneath it.
    private val matchCache = ConcurrentHashMap<String, Boolean>()
    // Ids arrive from many sources (native short names, MACs, advertised names, aliases),
    // so bound the key space rather than assume it stays as small as the sensor count.
    private const val MATCH_CACHE_MAX_ENTRIES = 4096

    private fun normalized(sensorId: String?): String? {
        return sensorId
            ?.trim()
            ?.takeIf { isUsableSensorId(it) }
    }

    /** Reject native/UI placeholders before they can participate in sensor selection. */
    @JvmStatic
    fun isUsableSensorId(sensorId: String?): Boolean {
        val value = sensorId?.trim() ?: return false
        return value.isNotEmpty() &&
            value != UNKNOWN_SENSOR_SENTINEL &&
            value.none { it.isISOControl() }
    }

    private fun managedMatches(left: String?, right: String?): Boolean {
        val normalizedLeft = normalized(left) ?: return false
        val normalizedRight = normalized(right) ?: return false
        return ManagedSensorIdentityRegistry.all.any { adapter ->
            adapter.matchesCallbackId(normalizedLeft, normalizedRight) ||
                adapter.matchesCallbackId(normalizedRight, normalizedLeft)
        }
    }

    private fun canonicalOrRaw(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return resolveAppSensorId(raw) ?: raw
    }

    private fun isManagedCanonicalSensorId(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return false
        return ManagedSensorIdentityRegistry.all.any { adapter ->
            val canonical = adapter.resolveCanonicalSensorId(raw)
            !canonical.isNullOrBlank() && canonical.equals(raw, ignoreCase = true)
        }
    }

    private fun resolveNativeBackedCanonicalSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return nativeCanonicalCache.getOrPut(raw) {
            runCatching { Natives.resolveFullSensorName(raw) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: NULL_SENTINEL
        }.takeIf { it != NULL_SENTINEL }
    }

    private fun nativeShortAlias(sensorId: String?): String? {
        val canonical = resolveNativeBackedCanonicalSensorId(sensorId) ?: normalized(sensorId) ?: return null
        if (isManagedCanonicalSensorId(canonical) || canonical.length <= 11) {
            return null
        }
        return canonical.takeLast(11)
    }

    /**
     * [sensorId]'s short alias (the name minus its five-character prefix, see
     * sensoren.hpp shortsensorname_view), or null when it has none. Native
     * treats both names as the same sensor.
     */
    @JvmStatic
    fun nativeAlias(sensorId: String?): String? {
        if (runCatching { isManagedCanonicalSensorId(sensorId) }.getOrDefault(false)) return null
        return aliasOf(sensorId)
    }

    /** [nativeAlias] without the managed-driver check. */
    internal fun aliasOf(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        // "X-" names have no alias; "SIBI:" and the like are managed ids.
        if (raw.length <= 11 || raw.startsWith("X-") || raw.contains(':')) return null
        return raw.substring(NATIVE_PREFIX_LENGTH)
    }

    /** How long the alias of a sensor's full name is: a G7's or a Libre's, 16 characters less 5. */
    private const val NATIVE_ALIAS_LENGTH = 11

    /**
     * Every name [sensorId]'s sensor can arrive under from the other device,
     * fullest first, worked out from the name alone: the name itself, its
     * [aliasOf], and the alias less its own five-character prefix.
     *
     * That last one is how native lists a record named after the alias
     * (Natives.activeSensors, sensoren.hpp shortsensorname_chars). A watch
     * holds a G7 handed over from the phone under its alias, "12147739749" for
     * 8958912147739749, so it lists — and once reported — the G7 as "739749".
     *
     * A cloud record (NSF-, API-, MQF-), an "X-" name and a managed id
     * ("SIBI:") have no other names here.
     */
    internal fun nativeNameForms(sensorId: String?): List<String> {
        val raw = normalized(sensorId) ?: return emptyList()
        if (raw.startsWith("X-") || raw.contains(':') || CloudSensorRecord.isCloudSensorId(raw)) {
            return listOf(raw)
        }
        val forms = ArrayList<String>(3)
        forms.add(raw)
        aliasOf(raw)?.let(forms::add)
        forms.lastOrNull { it.length == NATIVE_ALIAS_LENGTH }
            ?.let { forms.add(it.substring(NATIVE_PREFIX_LENGTH)) }
        return forms
    }

    /**
     * Whether [a] and [b] name one sensor by native's naming alone: equal, or
     * one is among the other's [nativeNameForms]. No record is needed, which is
     * the point: each device can only resolve the spellings of records it holds,
     * and the phone holds no record named "739749".
     *
     * A sensor that merely shares a suffix is not the same one: only whole
     * five-character prefixes come off. A cloud record matches only itself.
     */
    @JvmStatic
    fun sameNativeSensor(a: String?, b: String?): Boolean {
        val left = normalized(a) ?: return false
        val right = normalized(b) ?: return false
        if (left.equals(right, ignoreCase = true)) return true
        if (CloudSensorRecord.isCloudSensorId(left) || CloudSensorRecord.isCloudSensorId(right)) return false
        return nativeNameForms(left).any { it.equals(right, ignoreCase = true) } ||
            nativeNameForms(right).any { it.equals(left, ignoreCase = true) }
    }

    /**
     * The names in [candidates] that are [reported]'s sensor, when they are all
     * one sensor ([sameNativeSensor]): empty when none is, and when [reported] is
     * a short form two different sensors share — it is one of them, and which
     * cannot be told. A candidate spelled exactly as [reported] is that sensor.
     */
    internal fun nativeMatchesOfOne(reported: String?, candidates: Iterable<String?>): List<String> {
        val target = normalized(reported) ?: return emptyList()
        val hits = candidates
            .mapNotNull(::normalized)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filter { sameNativeSensor(it, target) }
        val exact = hits.firstOrNull { it.equals(target, ignoreCase = true) }
        if (exact != null) return hits.filter { sameNativeSensor(it, exact) }
        val oneSensor = hits.all { left -> hits.all { right -> sameNativeSensor(left, right) } }
        return if (oneSensor) hits else emptyList()
    }

    /** The longest of [names], the first of those as long; null for none. */
    internal fun fullestName(names: Iterable<String>): String? =
        names.fold(null as String?) { best, name -> if (best == null || name.length > best.length) name else best }

    /**
     * [names] with each sensor once, under the fullest name it is given.
     *
     * Two names are one sensor when [sameNativeSensor] says so or [alsoSame]
     * does — the device's own knowledge: its native records and managed drivers.
     * Each sensor keeps its first-seen name unless a longer one is a native form
     * of it ([nativeNameForms]), so a managed id is never swapped for a native
     * shell's name. A short form that several of the sensors share is left out:
     * it is already listed under a fuller name, and which one cannot be told.
     */
    internal fun distinctNativeSensors(
        names: Iterable<String?>,
        alsoSame: (String, String) -> Boolean = { _, _ -> false },
    ): List<String> {
        val ordered = names
            .mapNotNull(::normalized)
            .distinctBy { it.lowercase(Locale.ROOT) }
        val same = { left: String, right: String -> sameNativeSensor(left, right) || alsoSame(left, right) }
        // Fuller names first, so a short form cannot join two sensors into one
        // before both have been seen.
        val groups = ArrayList<MutableList<IndexedValue<String>>>()
        ordered.withIndex().sortedByDescending { it.value.length }.forEach { entry ->
            val hits = groups.filter { group -> group.any { same(it.value, entry.value) } }
            when (hits.size) {
                0 -> groups.add(mutableListOf(entry))
                1 -> hits[0].add(entry)
                else -> Unit
            }
        }
        return groups
            .sortedBy { group -> group.minOf { it.index } }
            .map { group ->
                val first = group.minByOrNull { it.index }!!.value
                fullestName(group.map { it.value }.filter { it == first || sameNativeSensor(it, first) }) ?: first
            }
    }

    /**
     * The name a sensor goes by between the two devices: its native alias when
     * it has one, as calibrations are keyed (SyncedWearCalibrationProvider), or
     * itself. Every build on either device resolves it — the phone finds its
     * full-named record by it, a watch the record it named after it — which
     * neither the full name (no such record on the watch) nor the watch's
     * six-character listing (none on the phone) is. A cloud record keeps its id,
     * and so does a managed driver's sensor, by whichever of its ids it came.
     */
    @JvmStatic
    fun crossDeviceName(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        if (CloudSensorRecord.isCloudSensorId(raw)) return raw
        val app = runCatching { resolveAppSensorId(raw) }.getOrNull()
        if (app != null && !app.equals(raw, ignoreCase = true)) return raw
        return runCatching { nativeAlias(raw) }.getOrNull() ?: raw
    }

    /**
     * One key per sensor for state shared with the other device, whichever of
     * its names arrives: the canonical id ([canonicalSensorId]) as its
     * [crossDeviceName], lowercased. 8958912147739749 and 12147739749 key alike
     * on both devices, record or not, and so does "739749" where native holds a
     * record named 12147739749 — on the watch. On the phone nothing here
     * explains "739749", and it keys as itself.
     */
    @JvmStatic
    fun crossDeviceKey(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        if (CloudSensorRecord.isCloudSensorId(raw)) return raw.lowercase(Locale.ROOT)
        val canonical = runCatching { canonicalSensorId(raw) }.getOrNull() ?: raw
        return (crossDeviceName(canonical) ?: canonical).lowercase(Locale.ROOT)
    }

    /**
     * The native record named after [sensorId]'s [nativeAlias], or null.
     * Native resolves an alias to a full-named record but not the reverse, so a
     * record created under the alias has to be looked up explicitly.
     */
    @JvmStatic
    fun shortNamedNativeRecord(sensorId: String?): String? {
        if (runCatching { isManagedCanonicalSensorId(sensorId) }.getOrDefault(false)) return null
        return shortNamedRecord(sensorId) { alias ->
            runCatching { Natives.resolveFullSensorName(alias) }.getOrNull()
        }
    }

    /** [shortNamedNativeRecord] with the native lookup passed in. */
    internal fun shortNamedRecord(sensorId: String?, fullNameOf: (String) -> String?): String? {
        val alias = aliasOf(sensorId) ?: return null
        // Only a record named after the alias itself: one named by the full
        // name answers to the alias too, and that one is [raw]'s own.
        return fullNameOf(alias)?.trim()?.takeIf { it.equals(alias, ignoreCase = true) }
    }

    @JvmStatic
    fun invalidateCaches() {
        nativeCanonicalCache.clear()
        appSensorIdCache.clear()
        matchCache.clear()
        ManagedSensorRuntime.clearCaches()
    }

    @JvmStatic
    fun resolveAppSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        appSensorIdCache[raw]?.let { return it }
        val resolved = ManagedSensorIdentityRegistry.all
            .asSequence()
            .mapNotNull { it.resolveCanonicalSensorId(raw) }
            .firstOrNull { it.isNotBlank() }
            ?: raw
        appSensorIdCache[raw] = resolved
        return resolved
    }

    /**
     * One logical id for protocol/state keys.
     *
     * Managed records resolve vendor aliases, while native storage resolves its
     * short names back to the full sensor name. Running both here prevents a
     * handoff from changing identity merely because one side learned more about
     * the sensor than the other.
     */
    @JvmStatic
    fun canonicalSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        val managed = resolveAppSensorId(raw) ?: raw
        val native = resolveNativeBackedCanonicalSensorId(managed)
            ?: resolveNativeBackedCanonicalSensorId(raw)
        return native?.let { resolveAppSensorId(it) ?: it } ?: managed
    }

    @JvmStatic
    fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return ManagedSensorIdentityRegistry.all
            .asSequence()
            .mapNotNull { it.resolveNativeSensorName(raw) }
            .firstOrNull { it.isNotBlank() }
            ?: raw
    }

    @JvmStatic
    fun resolveNativeHistorySensorNames(sensorId: String?): List<String> {
        val raw = normalized(sensorId) ?: return emptyList()
        val resolved = LinkedHashSet<String>()

        fun addCandidate(candidate: String?) {
            candidate
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(resolved::add)
        }

        addCandidate(raw)
        addCandidate(resolveAppSensorId(raw))
        addCandidate(resolveNativeSensorName(raw))
        ManagedSensorIdentityRegistry.all.forEach { adapter ->
            adapter.resolveNativeHistorySensorNames(raw).forEach(::addCandidate)
        }

        val snapshot = resolved.toList()
        snapshot.forEach { candidate ->
            addCandidate(resolveNativeBackedCanonicalSensorId(candidate))
            addCandidate(nativeShortAlias(candidate))
        }

        return resolved.toList()
    }

    @JvmStatic
    fun resolveRoomQuerySensorIds(sensorId: String?): List<String> {
        val raw = normalized(sensorId) ?: return emptyList()
        val resolved = LinkedHashSet<String>()
        fun addCandidate(candidate: String?) {
            candidate
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(resolved::add)
        }

        addCandidate(raw)
        addCandidate(resolveAppSensorId(raw))
        addCandidate(resolveNativeSensorName(raw))
        addCandidate(resolveRoomStorageSensorId(raw))
        resolveNativeHistorySensorNames(raw).forEach(::addCandidate)
        return resolved.toList()
    }

    @JvmStatic
    fun resolveRoomStorageSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        val managed = ManagedSensorIdentityRegistry.all
            .asSequence()
            .mapNotNull { it.resolveStableStorageSensorId(raw) }
            .firstOrNull { it.isNotBlank() }
        if (!managed.isNullOrBlank()) {
            return managed
        }
        return nativeShortAlias(raw) ?: raw
    }

    @JvmStatic
    fun shouldUseNativeHistorySync(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return true
        // The watch has no Room at all — HistoryRepository is mobile-only — so
        // native storage is its only history, whether readings arrive over
        // WearSync2 or from a driver connected here after a handoff. Letting a
        // managed driver answer "Room owns this" left both sources empty and the
        // screen showing "No data" over a full store.
        if (Applic.isWearable) return true
        managedDriverHistorySync(raw)?.let { return it }
        val canonical = canonicalOrRaw(raw) ?: raw
        if (!canonical.equals(raw, ignoreCase = true)) {
            managedDriverHistorySync(canonical)?.let { return it }
        }
        return ManagedSensorIdentityRegistry.shouldUseNativeHistorySync(canonical)
            ?: ManagedSensorIdentityRegistry.shouldUseNativeHistorySync(raw)
            ?: true
    }

    private fun managedDriverHistorySync(sensorId: String?): Boolean? {
        val raw = normalized(sensorId) ?: return null
        return runCatching {
            SensorBluetooth.mygatts()
                .asSequence()
                .mapNotNull { it as? ManagedBluetoothSensorDriver }
                .mapNotNull { driver ->
                    val matches = runCatching { driver.matchesManagedSensorId(raw) }.getOrDefault(false)
                    if (matches) runCatching { driver.shouldUseNativeHistorySync() }.getOrNull() else null
                }
                .firstOrNull()
        }.getOrNull()
    }

    @JvmStatic
    fun hasNativeSensorBacking(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return true
        val canonical = canonicalOrRaw(raw) ?: raw
        return ManagedSensorIdentityRegistry.hasNativeSensorBacking(canonical)
            ?: ManagedSensorIdentityRegistry.hasNativeSensorBacking(raw)
            ?: true
    }

    @JvmStatic
    fun usesNativeDirectStreamShell(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return false
        val canonical = canonicalOrRaw(raw) ?: raw
        return ManagedSensorIdentityRegistry.usesNativeDirectStreamShell(canonical) ||
            ManagedSensorIdentityRegistry.usesNativeDirectStreamShell(raw)
    }

    @JvmStatic
    fun resolveMainSensor(): String? {
        return resolveAvailableMainSensor(
            selectedMain = Natives.lastsensorname(),
            preferredSensorId = null,
            activeSensors = availableSensorCandidates()
        )
    }

    @JvmStatic
    fun resolveLiveMainSensor(preferredSensorId: String?): String? {
        val activeSensors = availableSensorCandidates()
        if (activeSensors.isNullOrEmpty()) {
            return resolveMainSensor()
        }
        return resolveAvailableMainSensor(
            selectedMain = Natives.lastsensorname(),
            preferredSensorId = preferredSensorId,
            activeSensors = activeSensors
        ) ?: resolveMainSensor()
    }

    private fun availableSensorCandidates(): Array<String?>? {
        val resolved = LinkedHashSet<String?>()

        runCatching {
            Natives.activeSensors()?.forEach { sensorId ->
                normalized(sensorId)?.let(resolved::add)
            }
        }

        runCatching {
            SensorBluetooth.mygatts().forEach { callback ->
                normalized(callback.SerialNumber)?.let(resolved::add)
            }
        }

        return resolved.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    @JvmStatic
    fun resolveAvailableMainSensor(
        selectedMain: String?,
        preferredSensorId: String?,
        activeSensors: Array<String?>?
    ): String? {
        val managed = canonicalOrRaw(ManagedCurrentSensor.get())
        val active = activeSensors
            ?.mapNotNull(::canonicalOrRaw)
            ?.distinct()
            .orEmpty()
        val canonicalSelected = canonicalOrRaw(selectedMain)
        val canonicalPreferred = canonicalOrRaw(preferredSensorId)

        if (active.isEmpty()) {
            return managed ?: canonicalSelected ?: canonicalPreferred
        }

        if (managed != null && active.any { matches(it, managed) }) {
            return managed
        }

        if (canonicalSelected != null && active.any { matches(it, canonicalSelected) }) {
            return canonicalSelected
        }

        if (canonicalPreferred != null && active.any { matches(it, canonicalPreferred) }) {
            return canonicalPreferred
        }

        return active.firstOrNull()
    }

    @JvmStatic
    fun matches(candidate: String?, expected: String?): Boolean {
        if (expected.isNullOrBlank()) {
            return true
        }
        val normalizedCandidate = normalized(candidate) ?: return false
        val normalizedExpected = normalized(expected) ?: return false
        if (normalizedCandidate.equals(normalizedExpected, ignoreCase = true)) {
            return true
        }
        val cacheKey = normalizedCandidate + NULL_SENTINEL + normalizedExpected
        matchCache[cacheKey]?.let { return it }
        val result = matchesUncached(normalizedCandidate, normalizedExpected)
        if (matchCache.size >= MATCH_CACHE_MAX_ENTRIES) matchCache.clear()
        matchCache[cacheKey] = result
        return result
    }

    /**
     * The identity resolution behind [matches], minus the trivial-equality fast path.
     * Everything here is a registry or regex lookup whose answer only changes when
     * [invalidateCaches] is called, which every driver registry already does on a
     * sensor-set change.
     */
    private fun matchesUncached(normalizedCandidate: String, normalizedExpected: String): Boolean {
        if (managedMatches(normalizedCandidate, normalizedExpected)) {
            return true
        }
        val left = resolveAppSensorId(normalizedCandidate)
        val right = resolveAppSensorId(normalizedExpected)
        if (!left.isNullOrBlank() && !right.isNullOrBlank() &&
            left.equals(right, ignoreCase = true)
        ) {
            return true
        }
        val candidateNative = resolveNativeBackedCanonicalSensorId(normalizedCandidate)
        val expectedNative = resolveNativeBackedCanonicalSensorId(normalizedExpected)
        if (!candidateNative.isNullOrBlank() && !expectedNative.isNullOrBlank() &&
            candidateNative.equals(expectedNative, ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun prefersLogicalCandidate(candidate: String, existing: String): Boolean {
        val candidateResolved = resolveAppSensorId(candidate)
        val existingResolved = resolveAppSensorId(existing)
        val candidateScore = (if (!candidateResolved.isNullOrBlank() && candidateResolved.equals(candidate, ignoreCase = true)) 2 else 0) +
            candidate.length
        val existingScore = (if (!existingResolved.isNullOrBlank() && existingResolved.equals(existing, ignoreCase = true)) 2 else 0) +
            existing.length
        return candidateScore > existingScore
    }

    @JvmStatic
    fun distinctLogicalSensorIds(sensorIds: Iterable<String?>): List<String> {
        val distinct = ArrayList<String>()
        sensorIds.forEach { sensorId ->
            val normalized = canonicalOrRaw(sensorId) ?: return@forEach
            val existingIndex = distinct.indexOfFirst { matches(it, normalized) }
            if (existingIndex < 0) {
                distinct.add(normalized)
            } else if (prefersLogicalCandidate(normalized, distinct[existingIndex])) {
                distinct[existingIndex] = normalized
            }
        }
        return distinct
    }
}
