package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.drivers.icanhealth.ICanHealthConstants

class SensorIdentityTests {
    @Before
    fun setUp() {
        ManagedCurrentSensor.clear()
    }

    @After
    fun tearDown() {
        ManagedCurrentSensor.clear()
    }

    @Test
    fun resolveAvailableMainSensor_prefersSelectedMainWhenStillActive() {
        assertEquals(
            "current-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = "current-sensor",
                preferredSensorId = "replacement-sensor",
                activeSensors = arrayOf("current-sensor", "replacement-sensor", "other-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_prefersPreferredWhenSelectedMainIsBlank() {
        assertEquals(
            "replacement-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "replacement-sensor",
                activeSensors = arrayOf("replacement-sensor", "other-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_fallsBackToFirstActiveWhenCachedSensorIsGone() {
        assertEquals(
            "replacement-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "stale-sensor",
                activeSensors = arrayOf("replacement-sensor", "other-sensor", "third-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_keepsManagedWhenStillActive() {
        ManagedCurrentSensor.set("X-2222268RXN")

        assertEquals(
            "X-2222268RXN",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "F0FD4509C7C2",
                activeSensors = arrayOf("F0FD4509C7C2", "X-2222268RXN")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_ignoresStaleManagedWhenActiveSensorExists() {
        ManagedCurrentSensor.set("X-2222268RXN")

        assertEquals(
            "F0FD4509C7C2",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = null,
                activeSensors = arrayOf("F0FD4509C7C2")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_keepsPreferredWhenNoActiveSensorsRemain() {
        assertEquals(
            "historical-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "historical-sensor",
                activeSensors = emptyArray()
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_returnsNullWhenNothingIsKnown() {
        assertNull(
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = null,
                activeSensors = emptyArray()
            )
        )
    }

    @Test
    fun placeholderIdentity_neverBecomesASensorOrMainSelection() {
        ManagedCurrentSensor.set("?")

        assertNull(SensorIdentity.resolveAppSensorId("?"))
        assertNull(
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = "?",
                preferredSensorId = null,
                activeSensors = arrayOf("?")
            )
        )
        assertEquals(
            "real-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = "?",
                preferredSensorId = null,
                activeSensors = arrayOf("?", "real-sensor")
            )
        )
    }

    @Test
    fun usableIdentity_acceptsVendorFormatsButRejectsPlaceholdersAndControls() {
        listOf(
            "SIBI:0683013AQT9",
            "ICN-8760080A2604",
            "X-222227JR7C",
            "P225043JMV",
            "46HU804EBJ4",
        ).forEach { assertTrue(SensorIdentity.isUsableSensorId(it)) }

        assertFalse(SensorIdentity.isUsableSensorId("?"))
        assertFalse(SensorIdentity.isUsableSensorId("bad\u001Didentity"))
    }

    @Test
    fun matches_recognizesAidexCanonicalAndAlias() {
        assertTrue(SensorIdentity.matches("X-222227JR7C", "222227JR7C"))
        assertTrue(SensorIdentity.matches("222227JR7C", "X-222227JR7C"))
    }

    @Test
    fun matches_recognizesIcanCanonicalAndNativeAlias() {
        val canonical = "8760080A00070000"
        val alias = ICanHealthConstants.nativeShortSensorAlias(canonical)
        assertTrue(alias != null && SensorIdentity.matches(canonical, alias))
        assertTrue(alias != null && SensorIdentity.matches(alias, canonical))
    }

    @Test
    fun distinctLogicalSensorIds_prefersCanonicalManagedIds() {
        assertEquals(
            listOf("8760080A00070000", "X-222227JR7C"),
            SensorIdentity.distinctLogicalSensorIds(
                listOf(
                    "80A00070000",
                    "8760080A00070000",
                    "222227JR7C",
                    "X-222227JR7C",
                )
            )
        )
    }

    @Test
    fun resolveRoomStorageSensorId_usesNativeShortAliasForLongNativeNames() {
        assertEquals(
            "1YL08230BFY",
            SensorIdentity.resolveRoomStorageSensorId("240601YL08230BFY")
        )
        assertEquals(
            "0671014ATR8",
            SensorIdentity.resolveRoomStorageSensorId("1P2250671014ATR8")
        )
    }

    @Test
    fun resolveRoomStorageSensorId_keepsManagedCanonicalIds() {
        assertEquals(
            "X-222227JR7C",
            SensorIdentity.resolveRoomStorageSensorId("X-222227JR7C")
        )
        assertEquals(
            "8760080A00070000",
            SensorIdentity.resolveRoomStorageSensorId("8760080A00070000")
        )
    }
    // --- matches() memoization (regex/registry work off the per-reading path) ---

    @Test
    fun matches_isStableAcrossRepeatedCalls() {
        // Distinct ids that do not resolve to each other: the answer must stay false
        // whether it came from the cache or from a fresh resolution.
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-beta"))
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-beta"))
        SensorIdentity.invalidateCaches()
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-beta"))
    }

    @Test
    fun matches_trivialEqualityStillShortCircuits() {
        // Must not depend on the cache, and must stay case-insensitive.
        assertTrue(SensorIdentity.matches("SENSOR-ALPHA", "sensor-alpha"))
        SensorIdentity.invalidateCaches()
        assertTrue(SensorIdentity.matches("SENSOR-ALPHA", "sensor-alpha"))
    }

    @Test
    fun matches_survivesCacheInvalidation() {
        assertTrue(SensorIdentity.matches("sensor-alpha", "sensor-alpha"))
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-gamma"))
        SensorIdentity.invalidateCaches()
        assertTrue(SensorIdentity.matches("sensor-alpha", "sensor-alpha"))
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-gamma"))
    }

    @Test
    fun matches_blankExpectedStillMatchesAnything() {
        assertTrue(SensorIdentity.matches("sensor-alpha", null))
        assertTrue(SensorIdentity.matches("sensor-alpha", ""))
    }

    // A G7's native name is 16 characters; native also finds its record by the
    // last 11 (the name without its five-character prefix).
    private val fullName = "1234567890123456"
    private val alias = "67890123456"

    @Test
    fun shortNamedRecord_findsTheRecordNamedAfterTheAlias() {
        val records = mapOf(alias to alias)
        assertEquals(alias, SensorIdentity.shortNamedRecord(fullName) { records[it] })
    }

    @Test
    fun shortNamedRecord_ignoresTheFullNamedRecordTheAliasAlsoFinds() {
        // Native answers the alias with the full-named record: that is the
        // sensor's own record, not a short-named one.
        val records = mapOf(alias to fullName)
        assertNull(SensorIdentity.shortNamedRecord(fullName) { records[it] })
    }

    @Test
    fun shortNamedRecord_nullWithoutAnyRecord() {
        assertNull(SensorIdentity.shortNamedRecord(fullName) { null })
    }

    @Test
    fun shortNamedRecord_leavesNamesWithoutAnAliasAlone() {
        val anything: (String) -> String? = { it }
        assertNull(SensorIdentity.shortNamedRecord(alias, anything))
        assertNull(SensorIdentity.shortNamedRecord("X-1234567890123", anything))
        assertNull(SensorIdentity.shortNamedRecord("SIBI:P225043JMV", anything))
        assertNull(SensorIdentity.shortNamedRecord(null, anything))
    }

    @Test
    fun aliasOf_dropsTheFiveCharacterPrefix() {
        assertEquals(alias, SensorIdentity.aliasOf(fullName))
        assertNull(SensorIdentity.aliasOf(alias))
        assertNull(SensorIdentity.aliasOf("X-1234567890123"))
        assertNull(SensorIdentity.aliasOf("SIBI:P225043JMV"))
    }

    // --- one G7 under its three names (2026-10-07: the watch reported "739749") ---

    /** The phone's record. */
    private val g7Full = "8958912147739749"

    /** Its alias: the watch's record, the phone's card and driver. */
    private val g7Alias = "12147739749"

    /** How native lists a record named after the alias: the watch's driver and report. */
    private val g7Listing = "739749"

    /** Another G7 whose names end in the same six characters. */
    private val otherFull = "8958999999739749"
    private val otherAlias = "99999739749"

    private val cloud = "NSF-3073E464C8CB"

    /** A sensor whose alias is the same as the cloud record's native listing. */
    private val sensorLikeCloud = "E07A3073E464C8CB"

    @Test
    fun nativeNameForms_runFromTheFullNameDownToTheWatchListing() {
        assertEquals(listOf(g7Full, g7Alias, g7Listing), SensorIdentity.nativeNameForms(g7Full))
        assertEquals(listOf(g7Alias, g7Listing), SensorIdentity.nativeNameForms(g7Alias))
        assertEquals(listOf(g7Listing), SensorIdentity.nativeNameForms(g7Listing))
    }

    @Test
    fun nativeNameForms_noneForCloudAidexOrManagedIds() {
        assertEquals(listOf(cloud), SensorIdentity.nativeNameForms(cloud))
        assertEquals(listOf("API-3073E464C8CB"), SensorIdentity.nativeNameForms("API-3073E464C8CB"))
        assertEquals(listOf("MQF-3073E464C8CB"), SensorIdentity.nativeNameForms("MQF-3073E464C8CB"))
        assertEquals(listOf("X-1234567890123"), SensorIdentity.nativeNameForms("X-1234567890123"))
        assertEquals(listOf("SIBI:P225043JMV"), SensorIdentity.nativeNameForms("SIBI:P225043JMV"))
        assertEquals(emptyList<String>(), SensorIdentity.nativeNameForms(" "))
    }

    @Test
    fun sameNativeSensor_allThreeNamesOfOneG7() {
        val names = listOf(g7Full, g7Alias, g7Listing)
        names.forEach { left ->
            names.forEach { right ->
                assertTrue("$left ~ $right", SensorIdentity.sameNativeSensor(left, right))
            }
        }
        // Libre names carry letters: case does not matter.
        assertTrue(SensorIdentity.sameNativeSensor("1P2250671014ATR8", "0671014atr8"))
        assertTrue(SensorIdentity.sameNativeSensor("0671014ATR8", "14atr8"))
    }

    @Test
    fun sameNativeSensor_aDifferentSensorSharingASuffixIsNot() {
        assertFalse(SensorIdentity.sameNativeSensor(g7Alias, otherAlias))
        assertFalse(SensorIdentity.sameNativeSensor(g7Full, otherFull))
        assertFalse(SensorIdentity.sameNativeSensor(g7Full, otherAlias))
        assertFalse(SensorIdentity.sameNativeSensor(g7Alias, otherFull))
        // Only whole five-character prefixes come off, never any suffix.
        assertFalse(SensorIdentity.sameNativeSensor(g7Full, "2147739749"))
        assertFalse(SensorIdentity.sameNativeSensor(g7Full, "47739749"))
        assertFalse(SensorIdentity.sameNativeSensor(g7Alias, "39749"))
        assertFalse(SensorIdentity.sameNativeSensor(g7Full, null))
        assertFalse(SensorIdentity.sameNativeSensor(g7Full, "?"))
    }

    @Test
    fun sameNativeSensor_aCloudRecordIsNeverARealSensor() {
        assertFalse(SensorIdentity.sameNativeSensor(cloud, sensorLikeCloud))
        assertFalse(SensorIdentity.sameNativeSensor(cloud, "073E464C8CB"))
        assertFalse(SensorIdentity.sameNativeSensor("073E464C8CB", cloud))
        assertFalse(SensorIdentity.sameNativeSensor(cloud, "64C8CB"))
        assertFalse(SensorIdentity.sameNativeSensor("API-3073E464C8CB", sensorLikeCloud))
        assertFalse(SensorIdentity.sameNativeSensor("MQF-3073E464C8CB", sensorLikeCloud))
        assertFalse(SensorIdentity.sameNativeSensor(cloud, "API-3073E464C8CB"))
        assertTrue(SensorIdentity.sameNativeSensor(cloud, cloud.lowercase()))
    }

    @Test
    fun nativeMatchesOfOne_findsTheSensorAShortNameBelongsTo() {
        assertEquals(listOf(g7Alias), SensorIdentity.nativeMatchesOfOne(g7Listing, listOf("0M0008MEK0U", g7Alias)))
        assertEquals(listOf(g7Full, g7Alias), SensorIdentity.nativeMatchesOfOne(g7Listing, listOf(g7Full, g7Alias)))
        assertEquals(listOf(g7Full), SensorIdentity.nativeMatchesOfOne(g7Alias, listOf(otherFull, g7Full)))
    }

    @Test
    fun nativeMatchesOfOne_nothingWhenTwoSensorsShareTheShortName() {
        assertEquals(emptyList<String>(), SensorIdentity.nativeMatchesOfOne(g7Listing, listOf(g7Full, otherFull)))
        assertEquals(emptyList<String>(), SensorIdentity.nativeMatchesOfOne(g7Listing, listOf(g7Alias, otherAlias)))
        assertEquals(emptyList<String>(), SensorIdentity.nativeMatchesOfOne(g7Listing, listOf("0M0008MEK0U")))
    }

    @Test
    fun nativeMatchesOfOne_anExactNameIsThatSensor() {
        assertEquals(listOf(g7Alias), SensorIdentity.nativeMatchesOfOne(g7Alias, listOf(g7Alias, otherAlias)))
    }

    @Test
    fun nativeMatchesOfOne_neverPicksARealSensorForACloudRecord() {
        assertEquals(emptyList<String>(), SensorIdentity.nativeMatchesOfOne(cloud, listOf(sensorLikeCloud)))
        assertEquals(emptyList<String>(), SensorIdentity.nativeMatchesOfOne("64C8CB", listOf(cloud)))
        assertEquals(listOf(sensorLikeCloud), SensorIdentity.nativeMatchesOfOne("64C8CB", listOf(cloud, sensorLikeCloud)))
    }

    @Test
    fun distinctNativeSensors_theWatchListsItsG7OnceUnderItsRecordName() {
        // Its driver "739749", native's listing "739749", native's record 12147739749.
        assertEquals(listOf(g7Alias), SensorIdentity.distinctNativeSensors(listOf(g7Listing, g7Listing, g7Alias)))
    }

    @Test
    fun distinctNativeSensors_prefersTheFullestName() {
        assertEquals(
            listOf(g7Full),
            SensorIdentity.distinctNativeSensors(listOf(g7Alias, g7Alias, g7Full, g7Listing)),
        )
    }

    @Test
    fun distinctNativeSensors_keepsFirstSeenOrder() {
        assertEquals(
            listOf("0M0008MEK0U", g7Full, otherAlias),
            SensorIdentity.distinctNativeSensors(listOf("0M0008MEK0U", g7Alias, otherAlias, g7Full)),
        )
    }

    @Test
    fun distinctNativeSensors_aShortFormTwoSensorsShareJoinsNeither() {
        assertEquals(
            listOf(g7Full, otherFull),
            SensorIdentity.distinctNativeSensors(listOf(g7Listing, g7Full, otherFull)),
        )
        assertEquals(
            listOf(g7Alias, otherAlias),
            SensorIdentity.distinctNativeSensors(listOf(g7Alias, g7Listing, otherAlias)),
        )
    }

    @Test
    fun distinctNativeSensors_keepsACloudRecordApartFromARealSensor() {
        assertEquals(
            listOf(sensorLikeCloud, cloud),
            SensorIdentity.distinctNativeSensors(listOf(sensorLikeCloud, cloud, "073E464C8CB")),
        )
    }

    @Test
    fun distinctNativeSensors_aManagedIdIsNotSwappedForANativeShellName() {
        val managed = "SIBI:P225043JMV"
        val shell = "ABCDE12345678901"
        val sameDriver = { a: String, b: String -> setOf(a, b) == setOf(managed, shell) }
        assertEquals(listOf(managed), SensorIdentity.distinctNativeSensors(listOf(managed, shell), sameDriver))
    }

    @Test
    fun crossDeviceName_isTheAliasBothDevicesResolve() {
        assertEquals(g7Alias, SensorIdentity.crossDeviceName(g7Full))
        assertEquals(g7Alias, SensorIdentity.crossDeviceName(g7Alias))
        assertEquals(cloud, SensorIdentity.crossDeviceName(cloud))
        assertEquals("X-1234567890123", SensorIdentity.crossDeviceName("X-1234567890123"))
        assertEquals("SIBI:P225043JMV", SensorIdentity.crossDeviceName("SIBI:P225043JMV"))
        assertNull(SensorIdentity.crossDeviceName("?"))
    }

    @Test
    fun crossDeviceKey_theFullNameAndTheAliasKeyAlike() {
        assertEquals(g7Alias, SensorIdentity.crossDeviceKey(g7Full))
        assertEquals(g7Alias, SensorIdentity.crossDeviceKey(g7Alias))
        assertEquals("0671014atr8", SensorIdentity.crossDeviceKey("1P2250671014ATR8"))
        assertEquals(cloud.lowercase(), SensorIdentity.crossDeviceKey(cloud))
    }
}
