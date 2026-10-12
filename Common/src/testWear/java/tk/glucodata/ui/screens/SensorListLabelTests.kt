package tk.glucodata.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone's sensor list names a G7 by the name it advertises over Bluetooth;
 * the watch's listed its record name, a long run of digits, so the same sensor
 * read differently on the two lists.
 */
class SensorListLabelTests {
    private val record = "1111122222333331"

    @Test
    fun aG7IsListedByItsBluetoothName() {
        assertEquals("DXCMAA", sensorListLabel(record, "DXCMAA"))
    }

    @Test
    fun withoutAKnownNameTheLabelStaysAsItWas() {
        // Not a G7, or not paired here yet: no name kept with the record.
        assertEquals(record, sensorListLabel(record, null))
        assertEquals(record, sensorListLabel(record, ""))
        // SuperGattCallback.mygetDeviceName's "no device", which the phone does not show either.
        assertEquals(record, sensorListLabel(record, "?"))
    }
}
