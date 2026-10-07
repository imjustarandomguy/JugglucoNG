package tk.glucodata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch was served the Nightscout follower's record NSF-3073E464C8CB, which
 * native lists as 073E464C8CB, and a roster rebuild would have given it a Libre
 * callback scanning for a sensor that does not exist.
 */
class CloudSensorRecordTests {
    private val fullNames = mapOf(
        "073E464C8CB" to "NSF-3073E464C8CB",
        "12147739749" to "8958912147739749",
    )
    private val fullName: (String) -> String? = { fullNames[it] }

    @Test
    fun cloudSourcesAreRecognisedByPrefix() {
        assertTrue(CloudSensorRecord.isCloudSensorId("NSF-3073E464C8CB"))
        assertTrue(CloudSensorRecord.isCloudSensorId("nsf-3073e464c8cb"))
        assertTrue(CloudSensorRecord.isCloudSensorId("API-0123456789AB"))
        assertTrue(CloudSensorRecord.isCloudSensorId("MQF-0123456789AB"))
    }

    @Test
    fun sensorsThatTransmitAreNotCloudSources() {
        assertFalse(CloudSensorRecord.isCloudSensorId("12147739749"))
        assertFalse(CloudSensorRecord.isCloudSensorId("8958912147739749"))
        // A Glutec transmitter, not its follower.
        assertFalse(CloudSensorRecord.isCloudSensorId("MQ-0123456789AB"))
        assertFalse(CloudSensorRecord.isCloudSensorId("X-222227JR7C"))
        assertFalse(CloudSensorRecord.isCloudSensorId("NSF-"))
        assertFalse(CloudSensorRecord.isCloudSensorId(""))
        assertFalse(CloudSensorRecord.isCloudSensorId(null))
    }

    @Test
    fun nativeShortAliasResolvesToTheCloudId() {
        assertEquals("NSF-3073E464C8CB", CloudSensorRecord.cloudRecordId("073E464C8CB", fullName))
        assertEquals("NSF-3073E464C8CB", CloudSensorRecord.cloudRecordId("NSF-3073E464C8CB", fullName))
    }

    @Test
    fun aG7RecordIsNotACloudRecord() {
        assertNull(CloudSensorRecord.cloudRecordId("12147739749", fullName))
        assertNull(CloudSensorRecord.cloudRecordId("UNKNOWN0001", fullName))
        assertNull(CloudSensorRecord.cloudRecordId(" ", fullName))
    }

    @Test
    fun aFailingLookupIsNotACloudRecord() {
        assertNull(CloudSensorRecord.cloudRecordId("073E464C8CB") { throw UnsatisfiedLinkError("no natives") })
    }

    @Test
    fun watchRosterKeepsTheG7AndDropsTheFollowerRecord() {
        val skipped = ArrayList<String>()
        val kept = CloudSensorRecord.withoutCloudRecords(
            arrayOf("12147739749", "073E464C8CB"),
            fullName,
        ) { skipped += it }
        assertArrayEquals(arrayOf<String?>("12147739749"), kept)
        assertEquals(listOf("073E464C8CB"), skipped)
    }

    @Test
    fun noNamesStayNoNames() {
        assertNull(CloudSensorRecord.withoutCloudRecords(null, fullName))
        assertArrayEquals(arrayOf<String?>(), CloudSensorRecord.withoutCloudRecords(arrayOf(), fullName))
    }
}
