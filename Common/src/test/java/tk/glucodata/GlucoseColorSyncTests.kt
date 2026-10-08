package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette

/**
 * The wire format the watch mirrors the phone's palette with. The interesting
 * property is that "no override for this band" survives the trip: encoding an
 * absent override as 0 would paint the watch transparent black.
 */
class GlucoseColorSyncTests {

    @Before
    @After
    fun reset() {
        GlucoseRangeColors.setChangeListener(null)
        GlucoseRangeColors.setPalette(Palette.MUTED)
        GlucoseRangeColors.clearOverrides()
    }

    private fun scheme(
        palette: String = Palette.VIBRANT.name,
        overrides: List<Int?> = List(Band.values().size) { null },
        targetBackground: Int? = null,
        valueRangeColors: Boolean = false,
        ranges: GlucoseRanges.Ranges? = null,
    ) = GlucoseColorSync.Scheme(palette, overrides, targetBackground, valueRangeColors, ranges)

    @Test
    fun roundTripsAPlainPreset() {
        val decoded = GlucoseColorSync.decodeScheme(GlucoseColorSync.encodeScheme(scheme()))
        assertEquals(scheme(), decoded)
    }

    @Test
    fun roundTripsTheRangesBitForBit() {
        // A threshold the watch reads even a float step off would put a reading
        // that sits on it in another band than on the phone. 550/180 is the
        // native very-low default as mmol/L, which has no short decimal form.
        for (ranges in listOf(
            GlucoseRanges.Ranges(true, 3.9f, 10.0f, 550f / 180f, 13.9f),
            GlucoseRanges.Ranges(false, 70f, 180f, 54f, 250f),
        )) {
            val original = scheme(ranges = ranges)
            assertEquals(original, GlucoseColorSync.decodeScheme(GlucoseColorSync.encodeScheme(original)))
        }
    }

    @Test
    fun aPayloadWithoutRangesLeavesThemUnset() {
        // An older phone sends none; the watch then keeps its own settings.
        val legacy = "palette=${Palette.VIBRANT.name}\nvalue_range_colors=true\n".toByteArray()
        val decoded = GlucoseColorSync.decodeScheme(legacy)!!
        assertNull(decoded.ranges)
        assertTrue(decoded.valueRangeColors)
    }

    @Test
    fun incompleteOrUnusableRangesAreDroppedNotTheColours() {
        val colours = GlucoseColorSync.encodeScheme(scheme(valueRangeColors = true)).toString(Charsets.UTF_8)
        listOf(
            "range_unit=mmol\nrange_target_low=3.9\nrange_target_high=10.0\nrange_very_low=3.0\n",
            "range_unit=furlong\nrange_target_low=3.9\nrange_target_high=10.0\nrange_very_low=3.0\nrange_very_high=13.9\n",
            "range_unit=mmol\nrange_target_low=NaN\nrange_target_high=10.0\nrange_very_low=3.0\nrange_very_high=13.9\n",
            "range_unit=mmol\nrange_target_low=3.9\nrange_target_high=10.0\nrange_very_low=0\nrange_very_high=13.9\n",
            "range_unit=mmol\nrange_target_low=3,9\nrange_target_high=10.0\nrange_very_low=3.0\nrange_very_high=13.9\n",
        ).forEach { ranges ->
            val decoded = GlucoseColorSync.decodeScheme((colours + ranges).toByteArray())!!
            assertNull(ranges, decoded.ranges)
            assertEquals(Palette.VIBRANT.name, decoded.palette)
            assertTrue(decoded.valueRangeColors)
        }
    }

    @Test
    fun roundTripsOverridesAndKeepsAbsentOnesAbsent() {
        val overrides = listOf(0xFF102030.toInt(), null, null, 0xFF405060.toInt(), null)
        val original = scheme(
            palette = Palette.CUSTOM.name,
            overrides = overrides,
            targetBackground = 0xFF708090.toInt(),
            valueRangeColors = true,
        )
        val decoded = GlucoseColorSync.decodeScheme(GlucoseColorSync.encodeScheme(original))!!

        assertEquals(original, decoded)
        assertEquals(0xFF102030.toInt(), decoded.overrides[Band.VERY_LOW.ordinal])
        assertNull(decoded.overrides[Band.LOW.ordinal])
        assertNull(decoded.overrides[Band.IN_RANGE.ordinal])
        assertEquals(0xFF405060.toInt(), decoded.overrides[Band.HIGH.ordinal])
        assertNull(decoded.overrides[Band.VERY_HIGH.ordinal])
    }

    @Test
    fun negativeArgbSurvives() {
        // Every opaque colour is a negative Int; a parser that only took digits
        // would silently drop the user's whole custom palette.
        val original = scheme(overrides = List(Band.values().size) { 0xFFC7655C.toInt() })
        assertEquals(original, GlucoseColorSync.decodeScheme(GlucoseColorSync.encodeScheme(original)))
    }

    @Test
    fun rejectsPayloadsThatAreNotOurs() {
        assertNull(GlucoseColorSync.decodeScheme(null))
        assertNull(GlucoseColorSync.decodeScheme(ByteArray(0)))
        assertNull(GlucoseColorSync.decodeScheme("hello".toByteArray()))
        // No palette line: refuse rather than reset the watch to defaults.
        assertNull(GlucoseColorSync.decodeScheme("value_range_colors=true\n".toByteArray()))
    }

    @Test
    fun unknownFieldsAreIgnoredNotFatal() {
        val payload = GlucoseColorSync.encodeScheme(scheme(valueRangeColors = true))
            .toString(Charsets.UTF_8) + "some_future_key=42\n"
        val decoded = GlucoseColorSync.decodeScheme(payload.toByteArray())!!
        assertTrue(decoded.valueRangeColors)
        assertEquals(Palette.VIBRANT.name, decoded.palette)
    }

    @Test
    fun thePayloadCarriesTheProtocolVersionAndALegacyOneStillDecodes() {
        val encoded = GlucoseColorSync.encodeScheme(scheme()).toString(Charsets.UTF_8)
        assertTrue("the version line is first", encoded.startsWith("${WearProtocol.versionLine()}\n"))

        // A payload from a build before the version line is still accepted.
        val legacy = "palette=${Palette.VIBRANT.name}\n".toByteArray()
        assertEquals(Palette.VIBRANT.name, GlucoseColorSync.decodeScheme(legacy)?.palette)
    }

    @Test
    fun aSchemeFromANewerProtocolIsRefused() {
        val future = "v:${WearProtocol.VERSION + 1}\npalette=${Palette.VIBRANT.name}\n".toByteArray()
        assertNull(GlucoseColorSync.decodeScheme(future))
    }
}
