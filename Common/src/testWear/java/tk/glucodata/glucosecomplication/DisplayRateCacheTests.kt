package tk.glucodata.glucosecomplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.ui.DisplayValues

class DisplayRateCacheTests {
    private val readingTime = 1_700_000_000_000L

    private fun key(
        time: Long = readingTime,
        sensor: String? = "sensor-1",
        viewMode: Int = 0,
        isMmol: Boolean = false,
        revision: Long = 7L,
    ) = DisplayRateCache.Key(sensor, time, viewMode, isMmol, 120f, Float.NaN, revision)

    private class Counter(private val rate: Float = 1.2f) : () -> Float {
        var calls = 0
        override fun invoke(): Float {
            calls++
            return rate
        }
    }

    @Test fun theSameReadingIsComputedOnce() {
        val cache = DisplayRateCache()
        val compute = Counter()
        repeat(60) { assertEquals(1.2f, cache.rate(key(), compute), 0f) }
        assertEquals(1, compute.calls)
    }

    @Test fun aNewReadingOrNewDataIsComputedAgain() {
        val cache = DisplayRateCache()
        val compute = Counter()
        cache.rate(key(), compute)
        cache.rate(key(time = readingTime + 300_000L), compute)
        cache.rate(key(revision = 8L), compute)
        cache.rate(key(viewMode = 1), compute)
        cache.rate(key(isMmol = true), compute)
        cache.rate(key(sensor = "sensor-2"), compute)
        assertEquals(6, compute.calls)
    }

    @Test fun keepsTheLastFewKeys() {
        val cache = DisplayRateCache(capacity = 2)
        val compute = Counter()
        cache.rate(key(sensor = "a"), compute)
        cache.rate(key(sensor = "b"), compute)
        cache.rate(key(sensor = "a"), compute)
        assertEquals("both held", 2, compute.calls)
        cache.rate(key(sensor = "c"), compute)
        cache.rate(key(sensor = "b"), compute)
        assertEquals("b, the least recent, made room for c", 4, compute.calls)
    }

    @Test fun aValueOnlyReadingNeverResolvesItsRate() {
        val compute = Counter()
        val reading = GlucoseComplicationData.Reading(
            value = 120f,
            text = "120",
            isMmol = false,
            timeMillis = readingTime,
            rateSource = compute,
            index = 0,
        )
        assertEquals("120", reading.text)
        assertEquals(120f, reading.value, 0f)
        assertEquals(readingTime, reading.timeMillis)
        assertEquals(0, compute.calls)
        assertEquals(1.2f, reading.rate, 0f)
        assertEquals(1, compute.calls)
    }

    private fun snapshot(viewMode: Int = 0, shown: Float = 120f) = CurrentDisplaySource.Snapshot(
        timeMillis = readingTime,
        rate = Float.NaN,
        sensorId = "sensor-1",
        sensorGen = 0,
        index = 0,
        viewMode = viewMode,
        source = "test",
        autoValue = 118f,
        rawValue = 0f,
        sharedDisplayValue = 0f,
        sharedMgdl = 0,
        isMmol = false,
        displayValues = DisplayValues(primaryValue = shown, primaryStr = shown.toString(), fullFormatted = ""),
    )

    @Test fun theKeyIsTheMeasuredReadingNotHowItIsShown() {
        assertEquals(
            "a calibration changes the value shown, not the measured trend",
            GlucoseComplicationData.rateKey(snapshot(shown = 120f), 3L),
            GlucoseComplicationData.rateKey(snapshot(shown = 131f), 3L),
        )
        assertNotEquals(
            GlucoseComplicationData.rateKey(snapshot(viewMode = 0), 3L),
            GlucoseComplicationData.rateKey(snapshot(viewMode = 1), 3L),
        )
        assertNotEquals(
            GlucoseComplicationData.rateKey(snapshot(), 3L),
            GlucoseComplicationData.rateKey(snapshot(), 4L),
        )
    }
}
