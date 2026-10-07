package tk.glucodata.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.R

class StopDirectLabelTests {
    /** A G7: the phone never stopped reading it, so the watch only stops. */
    @Test fun sensorReadAlongsideStopsReadingOnTheWatch() {
        assertEquals(R.string.wear_stop_reading_on_watch, stopDirectLabel(readsAlongside = true))
    }

    /** Libre and the rest: one device reads at a time, so the sensor goes back. */
    @Test fun anyOtherSensorIsReturnedToThePhone() {
        assertEquals(R.string.wear_return_sensor_to_phone, stopDirectLabel(readsAlongside = false))
    }
}
