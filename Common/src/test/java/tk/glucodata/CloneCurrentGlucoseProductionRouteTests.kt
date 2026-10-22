package tk.glucodata

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.ui.util.GlucoseFormatter

/** Real getFresh/registry/freshness path; only JNI inputs are shadowed. No Room or sidecar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CloneCurrentTestApplication::class, shadows = [CloneCurrentNativeInputs::class])
class CloneCurrentGlucoseProductionRouteTests {
    private val selected = "CLONETEST01"
    private val peer = "CLONETEST02"
    private var now = 0L
    private var savedUnit = 0

    @Before
    fun setUp() {
        savedUnit = Applic.unit
        Applic.app = RuntimeEnvironment.getApplication() as CloneCurrentTestApplication
        Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        ManagedCurrentSensor.clear()
        SensorBluetooth.gattcallbacks.clear()
        SensorIdentity.invalidateCaches()
        CloneCurrentNativeInputs.reset()
        CloneCurrentNativeInputs.selected = selected
        now = System.currentTimeMillis()
        retainCallback(selected, now - 20_000L)
    }

    @After
    fun tearDown() {
        Applic.unit = savedUnit
        SuperGattCallback.previousglucose = null
        SuperGattCallback.previousglucosereading = null
        SuperGattCallback.previousglucosevalue = 0f
        SuperGattCallback.previousglucosesensorid = null
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.clear()
        SensorIdentity.invalidateCaches()
        CloneCurrentNativeInputs.reset()
    }

    private fun retainCallback(sensor: String, time: Long) {
        SuperGattCallback.previousglucose = notGlucose(time, "3.4", 0f, 4)
        SuperGattCallback.previousglucosevalue = 3.4f
        SuperGattCallback.previousglucosereading = LiveReadingLanes.stock(3.4f, 4.6f)
        SuperGattCallback.previousglucosesensorid = sensor
    }

    private fun registerClone(sensor: String) {
        assertTrue(CloneSensorRegistry.markCloneSensor(sensor, CloneTransport.LOCAL_ICE.code, "test-connection"))
        assertTrue(CloneSensorRegistry.isCloneSensor(sensor))
    }

    private fun nativeReading(sensor: String = selected, time: Long = now - 5_000L, value: String = "2.7") =
        strGlucose(time, value, sensor, -0.7f, 1, 4)

    @Test
    fun twoClonesResolveRequestedSensorWhenGlobalReadingBelongsToNewerPeer() {
        registerClone(selected)
        registerClone(peer)
        val imported = nativeReading()
        CloneCurrentNativeInputs.readings[selected] = imported
        CloneCurrentNativeInputs.readings[peer] = nativeReading(peer, now - 1_000L, "8.0")
        CloneCurrentNativeInputs.global = CloneCurrentNativeInputs.readings[peer]

        val current = requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected))

        assertEquals(selected, current.sensorId)
        assertEquals(imported.time, current.timeMillis)
        assertEquals(2.7f, current.numericValue, 0.001f)
        assertEquals("native", current.source)
        assertTrue(current.rawNumericValue.isNaN())
        assertEquals(0, CloneCurrentNativeInputs.globalReads)
        assertEquals(listOf(selected), CloneCurrentNativeInputs.namedReads)
    }

    @Test
    fun defaultGetFreshResolvesSelectedCloneThroughRegistry() {
        registerClone(selected)
        registerClone(peer)
        CloneCurrentNativeInputs.readings[selected] = nativeReading()
        CloneCurrentNativeInputs.readings[peer] = nativeReading(peer, now - 1_000L, "8.0")
        CloneCurrentNativeInputs.global = CloneCurrentNativeInputs.readings[peer]

        val current = requireNotNull(CurrentGlucoseSource.getFresh())

        assertEquals(selected, current.sensorId)
        assertEquals(2.7f, current.numericValue, 0.001f)
        assertEquals(0, CloneCurrentNativeInputs.globalReads)
    }

    @Test
    fun registeringCloneChangesProductionSourceFromRetainedCallbackToNative() {
        CloneCurrentNativeInputs.readings[selected] = nativeReading()
        CloneCurrentNativeInputs.global = nativeReading()
        val local = requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected))
        assertEquals("callback", local.source)
        assertEquals(3.4f, local.numericValue, 0.001f)
        assertTrue(CloneCurrentNativeInputs.namedReads.isEmpty())

        registerClone(selected)
        val imported = requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected))
        assertEquals("native", imported.source)
        assertEquals(2.7f, imported.numericValue, 0.001f)
        assertTrue(imported.rawNumericValue.isNaN())
    }

    @Test
    fun cloneFreshnessUsesCallerLimitInsteadOfRetainedCallback() {
        registerClone(selected)
        retainCallback(selected, now - 100L)
        CloneCurrentNativeInputs.readings[selected] = nativeReading(time = now - 10_000L)
        CloneCurrentNativeInputs.global = CloneCurrentNativeInputs.readings[selected]

        assertNull(CurrentGlucoseSource.getFresh(1_000L, selected))
        assertEquals(0, CloneCurrentNativeInputs.globalReads)
    }

    @Test
    fun cloneMissingNamedReadingDoesNotUseGlobalPeerOrRetainedCallback() {
        registerClone(selected)
        registerClone(peer)
        CloneCurrentNativeInputs.readings[peer] = nativeReading(peer)
        CloneCurrentNativeInputs.global = CloneCurrentNativeInputs.readings[peer]

        assertNull(CurrentGlucoseSource.getFresh(60_000L, selected))
        assertEquals(0, CloneCurrentNativeInputs.globalReads)
    }

    @Test
    fun cloneRejectsMismatchedIdentityEvenFromNamedLookup() {
        registerClone(selected)
        CloneCurrentNativeInputs.readings[selected] = nativeReading(peer)

        assertNull(CurrentGlucoseSource.getFresh(60_000L, selected))
    }

    @Test
    fun cloneNativeAliasAndSecondsTimestampReachProductionParser() {
        val managed = "X-TESTABC1234"
        registerClone(managed)
        val timeSeconds = (now - 5_000L) / 1000L
        CloneCurrentNativeInputs.readings["TESTABC1234"] = nativeReading("TESTABC1234", timeSeconds, "2,7")

        val current = requireNotNull(CurrentGlucoseSource.getFresh(60_000L, managed))

        assertEquals(timeSeconds * 1000L, current.timeMillis)
        assertEquals(2.7f, current.numericValue, 0.001f)
        assertTrue(CloneCurrentNativeInputs.namedReads.contains("TESTABC1234"))
    }

    @Test
    fun cloneMgdlValueIsNotConvertedAgain() {
        registerClone(selected)
        CloneCurrentNativeInputs.readings[selected] = nativeReading(value = "49")

        assertEquals(49f, requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected)).numericValue, 0.001f)
    }

    /**
     * Native's text divides mg/dL by 18 while the stored rows multiply by
     * [GlucoseFormatter.MGDL_TO_MMOL]: 127 mg/dL is "7.1" as text, 7.0 in every row.
     * A Clone reading must show what its row shows, like a local native one.
     */
    @Test
    fun cloneMmolReadingShowsWhatItsStoredRowShows() {
        Applic.unit = 1
        registerClone(selected)
        val imported = nativeReading(value = "7.1")
        CloneCurrentNativeInputs.readings[selected] = imported
        CloneCurrentNativeInputs.histories[selected] = storedRow(imported, 127)

        val current = requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected))

        assertEquals("native", current.source)
        assertEquals(GlucoseFormatter.displayFromMgDl(127f, true), current.numericValue, 0.0001f)
        assertEquals(listOf(selected), CloneCurrentNativeInputs.historyReads)
    }

    @Test
    fun cloneMmolReadingWithoutStoredRowKeepsItsText() {
        Applic.unit = 1
        registerClone(selected)
        CloneCurrentNativeInputs.readings[selected] = nativeReading(value = "7.1")

        assertEquals(7.1f, requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected)).numericValue, 0.001f)
    }

    @Test
    fun cloneMgdlReadingReadsNoStoredRow() {
        Applic.unit = 0
        registerClone(selected)
        val imported = nativeReading(value = "127")
        CloneCurrentNativeInputs.readings[selected] = imported
        CloneCurrentNativeInputs.histories[selected] = storedRow(imported, 127)

        assertEquals(127f, requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected)).numericValue, 0.001f)
        assertTrue(CloneCurrentNativeInputs.historyReads.isEmpty())
    }

    @Test
    fun localNativeMmolReadingShowsWhatItsStoredRowShows() {
        Applic.unit = 1
        SuperGattCallback.previousglucose = null
        val imported = nativeReading(value = "7.1")
        CloneCurrentNativeInputs.global = imported
        CloneCurrentNativeInputs.histories[selected] = storedRow(imported, 127)

        val current = requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected))

        assertEquals("native", current.source)
        assertEquals(GlucoseFormatter.displayFromMgDl(127f, true), current.numericValue, 0.0001f)
    }

    @Test
    fun shownCallbackReadsNoStoredNativeRow() {
        Applic.unit = 1
        val imported = nativeReading(value = "7.1")
        CloneCurrentNativeInputs.global = imported
        CloneCurrentNativeInputs.histories[selected] = storedRow(imported, 127)

        assertEquals("callback", requireNotNull(CurrentGlucoseSource.getFresh(60_000L, selected)).source)
        assertTrue(CloneCurrentNativeInputs.historyReads.isEmpty())
    }

    /** Native's `[time_sec, auto_mgdl*10, raw_mgdl*10]` row for [reading]. */
    private fun storedRow(reading: strGlucose, mgdl: Int) = longArrayOf(reading.time / 1000L, mgdl * 10L, 0L)

    @Test
    fun cloneInvalidNativeValueDoesNotResurrectCallback() {
        registerClone(selected)
        for (value in listOf("0", "NaN", "")) {
            CloneCurrentNativeInputs.readings[selected] = nativeReading(value = value)
            assertNull(CurrentGlucoseSource.getFresh(60_000L, selected))
        }
    }
}

/** Avoid starting BLE, network, or notification services in these storage-reader tests. */
class CloneCurrentTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class CloneCurrentNativeInputs {
    companion object {
        var selected: String? = null
        var global: strGlucose? = null
        val readings = linkedMapOf<String, strGlucose>()
        val namedReads = mutableListOf<String>()
        var globalReads = 0
        val histories = linkedMapOf<String, LongArray>()
        val historyReads = mutableListOf<String>()

        fun reset() {
            selected = null
            global = null
            readings.clear()
            namedReads.clear()
            globalReads = 0
            histories.clear()
            historyReads.clear()
        }

        @JvmStatic @Implementation
        fun __staticInitializer__() {}

        @JvmStatic @Implementation
        fun lastglucose(): strGlucose? {
            globalReads++
            return global
        }

        @JvmStatic @Implementation
        fun lastglucoseForSensor(sensor: String): strGlucose? {
            namedReads.add(sensor)
            return readings[sensor]
        }

        @JvmStatic @Implementation
        fun getGlucoseHistoryForSensor(sensor: String, startTime: Long): LongArray? {
            historyReads.add(sensor)
            return histories[sensor]
        }

        @JvmStatic @Implementation
        fun lastsensorname(): String? = selected

        @JvmStatic @Implementation
        fun activeSensors(): Array<String> = (readings.keys + listOfNotNull(selected)).distinct().toTypedArray()

        @JvmStatic @Implementation
        fun resolveFullSensorName(sensor: String?): String? = null
    }
}
