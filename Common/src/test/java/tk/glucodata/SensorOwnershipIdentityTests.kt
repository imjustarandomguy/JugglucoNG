package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Which key an ownership report, a release or a reading is filed under
 * ([resolveOwnershipKey]): one per sensor, whichever name it arrives under.
 *
 * On 2026-10-07 a watch holding a G7 as 12147739749 reported it as "739749",
 * the name native lists that record by. The phone, whose record is
 * 8958912147739749, filed those reports apart from its own sensor: its card
 * said "Watch: no report yet" while the watch read the G7 every five minutes,
 * and its arbitration stood down from a "739749" that did not exist.
 */
class SensorOwnershipIdentityTests {
    private val full = "8958912147739749"
    private val alias = "12147739749"
    private val listing = "739749"
    private val otherFull = "8958999999739749"

    /**
     * What SensorIdentity.crossDeviceKey answers on the phone, whose record is
     * the full name: both names it can resolve key as the alias; the listing,
     * which no record here explains, as itself.
     */
    private fun phoneKey(name: String): String? = when {
        name.length >= 11 && SensorIdentity.sameNativeSensor(name, full) -> alias
        name.length >= 11 && SensorIdentity.sameNativeSensor(name, otherFull) -> "99999739749"
        else -> name.lowercase()
    }

    /** The same on the watch, whose record is named after the alias: native resolves the listing too. */
    private fun watchKey(name: String): String? =
        if (SensorIdentity.sameNativeSensor(name, alias)) alias else name.lowercase()

    private val unused: () -> Iterable<String?> = { fail("a name native resolves needs no local lookup"); emptyList() }

    @Test
    fun everyNameOfTheG7KeysAlikeOnThePhone() {
        val local = { listOf<String?>(alias, alias, full) }
        listOf(full, alias, listing).forEach { name ->
            assertEquals(name, alias, resolveOwnershipKey(name, ::phoneKey, local))
        }
    }

    @Test
    fun everyNameOfTheG7KeysAlikeOnTheWatch() {
        listOf(full, alias, listing).forEach { name ->
            assertEquals(name, alias, resolveOwnershipKey(name, ::watchKey, unused))
        }
    }

    @Test
    fun aListingTwoLocalSensorsShareStaysApart() {
        val local = { listOf<String?>(alias, full, otherFull) }
        assertEquals(listing, resolveOwnershipKey(listing, ::phoneKey, local))
    }

    @Test
    fun aShortNameOfNoLocalSensorKeysAsItself() {
        val local = { listOf<String?>(alias, full) }
        assertEquals("abc123", resolveOwnershipKey("ABC123", ::phoneKey, local))
        assertEquals(listing, resolveOwnershipKey(listing, ::phoneKey) { emptyList() })
    }

    @Test
    fun aCloudRecordIsNeverFiledUnderARealSensor() {
        val cloud = "NSF-3073E464C8CB"
        val sensorLikeCloud = "E07A3073E464C8CB"
        val local = { listOf<String?>(cloud, sensorLikeCloud) }
        val key = { name: String -> name.lowercase() }
        assertEquals(cloud.lowercase(), resolveOwnershipKey(cloud, key, local))
        // Its six-character tail is the real sensor's listing, not the cloud record's.
        assertEquals("e07a3073e464c8cb", resolveOwnershipKey("64C8CB", key, local))
        assertEquals("64c8cb", resolveOwnershipKey("64C8CB", key) { listOf(cloud) })
    }

    @Test
    fun aFullOrAliasNameNeverNeedsALocalLookup() {
        assertEquals(alias, resolveOwnershipKey(full, ::phoneKey, unused))
        assertEquals(alias, resolveOwnershipKey(" $alias ", ::phoneKey, unused))
        assertEquals("0m0008mek0u", resolveOwnershipKey("0M0008MEK0U", { it.lowercase() }, unused))
    }

    @Test
    fun aKeyUnknownToTheDeviceFallsBackToTheName() {
        assertEquals("sibi:p225043jmv", resolveOwnershipKey("SIBI:P225043JMV", { null }, unused))
    }
}
