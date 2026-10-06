package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone re-sends the calibration payload with a new revision ahead of every
 * reading. Only a change in what it says may refresh the watch's surfaces;
 * otherwise that refresh lands just before the reading is stored.
 */
class SyncedWearCalibrationContentTests {
    private val base = WearCalibrationPayload(
        sensorId = "SENSOR-A",
        revision = 1_000L,
        valuesPrecalibrated = false,
        hideInitialWhenCalibrated = true,
        auto = WearCalibrationMode(doubleArrayOf(100.0, 110.0, 1_700_000_000_000.0)),
        raw = WearCalibrationMode(DoubleArray(0)),
        sourceUnitMgdlPerUnit = 18.0182,
    )

    private fun same(next: WearCalibrationPayload) =
        SyncedWearCalibrationProvider.sameContent(base, next)

    @Test
    fun aNewRevisionAloneIsNotAChange() {
        assertTrue(same(base.copy(revision = 2_000L)))
        assertTrue(
            "anchors are compared by value, not by array identity",
            same(base.copy(revision = 2_000L, auto = WearCalibrationMode(base.auto.anchorsMgdl.copyOf()))),
        )
    }

    @Test
    fun anchorsAreAChange() {
        assertFalse(same(base.copy(auto = WearCalibrationMode(doubleArrayOf(100.0, 112.0, 1_700_000_000_000.0)))))
        assertFalse(same(base.copy(raw = WearCalibrationMode(doubleArrayOf(95.0, 110.0, 1_700_000_000_000.0)))))
        assertFalse(same(base.copy(autoIntegration = WearCalibrationMode(doubleArrayOf(90.0, 110.0, 1_700_000_000_000.0)))))
    }

    @Test
    fun flagsSettingsAndUnitAreAChange() {
        assertFalse(same(base.copy(hideInitialWhenCalibrated = false)))
        assertFalse(same(base.copy(overwriteSensorValues = true)))
        assertFalse(same(base.copy(rawIntegratedByDriver = true)))
        assertFalse(same(base.copy(rawTuning = base.tuning.copy(applyToPast = true))))
        assertFalse(same(base.copy(sourceUnitMgdlPerUnit = 1.0)))
    }

    @Test
    fun anotherSensorIsAChange() {
        assertFalse(same(base.copy(sensorId = "SENSOR-B")))
    }
}
