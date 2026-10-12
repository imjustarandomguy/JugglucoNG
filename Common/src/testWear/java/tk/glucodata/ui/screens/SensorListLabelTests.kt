package tk.glucodata.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.DexcomFollow

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
    fun theNameComesFromTheRecordAsTheDexcomHandoffExportsIt() {
        val fields = arrayOf(record, "0000003331", "DXCMAA", "1790000000")
        assertEquals("DXCMAA", sensorListLabel(record, DexcomFollow.fromFields(fields)?.deviceName))
    }

    @Test
    fun withoutAKnownNameTheLabelStaysAsItWas() {
        // Not a G7, or not paired yet: the record exports a blank name.
        assertEquals(record, sensorListLabel(record, null))
        assertEquals(record, sensorListLabel(record, ""))
        assertEquals(
            record,
            sensorListLabel(record, DexcomFollow.fromFields(arrayOf(record, "0000003331", "", "1790000000"))?.deviceName),
        )
        // SuperGattCallback.mygetDeviceName's "no device", which the phone does not show either.
        assertEquals(record, sensorListLabel(record, "?"))
    }
}
