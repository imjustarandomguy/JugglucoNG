package tk.glucodata

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.util.inDisplayUnit

/**
 * One reading must read the same wherever it is shown: as the live value before
 * it reaches history, as the watch's native row and as the phone's Room row.
 * That holds only if all of them convert mg/dL to mmol/L the same way: dividing
 * by 18 or 18.0182 disagrees with the stored rows' factor on some readings
 * (127 mg/dL is 7.1 by / 18 but 7.0 stored, 100 mg/dL is 5.5 by / 18.0182 but
 * 5.6 stored).
 */
class GlucoseDisplayConversionTests {
    private val timeMillis = 1_700_000_040_000L
    private lateinit var savedLocale: Locale

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun liveAutoReadingShowsWhatItsStoredRowsShow() {
        for (isMmol in listOf(true, false)) {
            for (mgdl in MGDL_RANGE) {
                val live = LiveReadingLanes.stockFromMgdl(mgdl.toFloat(), Float.NaN, isMmol)
                val liveOnly = shown(live.stockAuto, emptyList(), AUTO, isMmol)
                val label = "$mgdl mg/dL, mmol=$isMmol"

                assertEquals(label, GlucoseFormatter.formatFromMgDl(mgdl.toFloat(), isMmol), liveOnly)
                assertEquals(label, liveOnly, shown(Float.NaN, watchRow(mgdl, 0, isMmol), AUTO, isMmol))
                assertEquals(label, liveOnly, shown(Float.NaN, phoneRow(mgdl, 0, isMmol), AUTO, isMmol))
                if (!isMmol) {
                    assertEquals(label, mgdl.toString(), liveOnly)
                }
            }
        }
    }

    @Test
    fun liveRawReadingShowsWhatItsStoredRowsShow() {
        for (isMmol in listOf(true, false)) {
            for (mgdl in MGDL_RANGE) {
                // The warm-up path: no auto value yet, the raw lane is primary.
                val live = LiveReadingLanes.stockFromMgdl(Float.NaN, mgdl.toFloat(), isMmol)
                val liveOnly = shown(live.stockRaw, emptyList(), RAW, isMmol)
                val label = "$mgdl mg/dL raw, mmol=$isMmol"

                assertEquals(label, GlucoseFormatter.formatFromMgDl(mgdl.toFloat(), isMmol), liveOnly)
                assertEquals(label, liveOnly, shown(Float.NaN, watchRow(0, mgdl, isMmol), RAW, isMmol))
                assertEquals(label, liveOnly, shown(Float.NaN, phoneRow(0, mgdl, isMmol), RAW, isMmol))
            }
        }
    }

    @Test
    fun nativeLatestReadingShowsWhatItsStoredRowShows() {
        for (isMmol in listOf(true, false)) {
            for (mgdl in MGDL_RANGE) {
                val parsed = requireNotNull(GlucoseValueParser.parseFirst(nativeText(mgdl, isMmol)))
                val value = CurrentGlucoseSource.nativeDisplayValue(parsed, mgdl.toFloat(), isMmol)
                val label = "$mgdl mg/dL native, mmol=$isMmol"

                val stored = shown(Float.NaN, watchRow(mgdl, 0, isMmol), AUTO, isMmol)
                assertEquals(label, stored, shown(value, emptyList(), AUTO, isMmol))
                assertEquals(label, GlucoseFormatter.formatFromMgDl(mgdl.toFloat(), isMmol), stored)
            }
        }
    }

    @Test
    fun nativeLatestReadingKeepsItsTextWithoutAStoredRow() {
        assertEquals(7.1f, CurrentGlucoseSource.nativeDisplayValue(7.1f, Float.NaN, true), 0f)
        assertEquals(127f, CurrentGlucoseSource.nativeDisplayValue(127f, 128f, false), 0f)
    }

    @Test
    fun readingsWhereTheDivisorsDisagreeReadAsOnThePhone() {
        // 127 mg/dL: 7.1 by / 18, 7.0 stored. 100 mg/dL: 5.5 by / 18.0182, 5.6 stored.
        assertEquals("7.0", shown(LiveReadingLanes.stockFromMgdl(127f, Float.NaN, true).stockAuto, emptyList(), AUTO, true))
        assertEquals("7.0", shown(Float.NaN, watchRow(127, 0, true), AUTO, true))
        assertEquals("5.6", shown(Float.NaN, watchRow(100, 0, true), AUTO, true))
        assertEquals("5.6", shown(LiveReadingLanes.stockFromMgdl(100f, Float.NaN, true).stockAuto, emptyList(), AUTO, true))
    }

    private fun shown(liveValue: Float, rows: List<GlucosePoint>, viewMode: Int, isMmol: Boolean): String =
        requireNotNull(
            CurrentDisplaySource.resolveFromLive(
                liveValueText = null,
                liveNumericValue = liveValue,
                rate = 0f,
                targetTimeMillis = timeMillis,
                sensorId = "conversion-test",
                sensorGen = 0,
                index = 0,
                source = "test",
                recentPoints = rows,
                viewMode = viewMode,
                isMmol = isMmol
            )
        ).primaryStr

    /** A row as the watch reads it from native storage. */
    private fun watchRow(autoMgdl: Int, rawMgdl: Int, isMmol: Boolean): List<GlucosePoint> =
        NotificationHistorySource.pointsFromNativeHistory(
            longArrayOf(timeMillis / 1000L, autoMgdl * 10L, rawMgdl * 10L),
            isMmol
        )

    /** A row as the phone hands Room history to the shared display code. */
    private fun phoneRow(autoMgdl: Int, rawMgdl: Int, isMmol: Boolean): List<GlucosePoint> {
        val point = tk.glucodata.ui.GlucosePoint(
            value = autoMgdl.toFloat(),
            time = "",
            timestamp = timeMillis,
            rawValue = rawMgdl.toFloat()
        ).inDisplayUnit(isMmol)
        return listOf(GlucosePoint(point.timestamp, point.value, point.rawValue))
    }

    /** What native's latest reading says: mg/dL / 18 to one decimal, or whole mg/dL. */
    private fun nativeText(mgdl: Int, isMmol: Boolean): String =
        if (isMmol) String.format(Locale.US, "%.1f", ((mgdl * 10f).toDouble() * (1.0 / 180.0)).toFloat())
        else String.format(Locale.US, "%.0f", mgdl.toFloat())

    private companion object {
        val MGDL_RANGE = 40..400
        const val AUTO = 0
        const val RAW = 1
    }
}
