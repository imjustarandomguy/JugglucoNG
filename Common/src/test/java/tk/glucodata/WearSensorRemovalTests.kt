package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSensorRemovalTests {
    private val removedFull = "5000012345678901"
    private val removedAlias = "12345678901"
    private val otherAlias = "98765432109"

    /** Native's full name for each spelling it holds a record under. */
    private val fullNames = mapOf(
        removedFull to removedFull,
        removedAlias to removedFull,
        "5000098765432109" to "5000098765432109",
        otherAlias to "5000098765432109",
    )

    private val hasRecord: (String) -> Boolean = { name -> fullNames.containsKey(name.uppercase()) }

    private fun sameSensorIn(names: Map<String, String>): (String, String) -> Boolean = { a, b ->
        a.equals(b, ignoreCase = true) ||
            names[a.uppercase()]?.let { it == names[b.uppercase()] } == true
    }

    private val sameSensor = sameSensorIn(fullNames)

    private fun recordsToEnd(
        removed: String,
        spellings: List<String?>,
        active: Array<String?>?,
        managedOwned: Boolean = false,
        hasRecord: (String) -> Boolean = this.hasRecord,
        sameSensor: (String, String) -> Boolean = this.sameSensor,
    ): List<String> = WearSensorRemoval.nativeRecordsToEnd(
        removed = removed,
        managedOwned = managedOwned,
        spellings = spellings,
        active = active,
        hasRecord = hasRecord,
        sameSensor = sameSensor,
    )

    @Test
    fun aSensorRemovedByItsShortAliasEndsTheRecordNativeListsIt() {
        assertEquals(
            listOf(removedAlias, removedFull),
            recordsToEnd(removedAlias, listOf(removedAlias, removedFull), arrayOf(removedAlias, otherAlias)),
        )
    }

    @Test
    fun aSensorRemovedByItsFullNameEndsItsActiveAlias() {
        assertEquals(
            listOf(removedFull, removedAlias),
            recordsToEnd(removedFull, listOf(removedFull, removedAlias), arrayOf(otherAlias, removedAlias)),
        )
        // Even when only native's identity relates the listed alias to it.
        assertEquals(
            listOf(removedFull, removedAlias),
            recordsToEnd(removedFull, listOf(removedFull), arrayOf(otherAlias, removedAlias)),
        )
    }

    @Test
    fun aSecondRecordMadeUnderTheAliasIsEndedToo() {
        // A record created under the 11-character alias lists by the alias
        // minus its first five characters.
        val aliasRecordListing = "678901"
        val names = fullNames + mapOf(removedAlias to removedAlias, aliasRecordListing to removedAlias)
        assertEquals(
            listOf(removedAlias, aliasRecordListing),
            recordsToEnd(
                removedAlias,
                listOf(removedAlias),
                arrayOf(aliasRecordListing, otherAlias),
                hasRecord = { names.containsKey(it.uppercase()) },
                sameSensor = sameSensorIn(names),
            ),
        )
    }

    @Test
    fun anotherSensorIsNeverEnded() {
        assertFalse(
            recordsToEnd(removedFull, listOf(removedFull, removedAlias), arrayOf(otherAlias, removedAlias))
                .contains(otherAlias),
        )
        // A sensor this device holds no record of ends nothing here.
        assertEquals(
            emptyList<String>(),
            recordsToEnd("5000055555555555", listOf("5000055555555555", "55555555555"), arrayOf(otherAlias)),
        )
    }

    @Test
    fun aSensorAManagedDriverOwnsIsLeftToThatDriver() {
        assertEquals(
            emptyList<String>(),
            recordsToEnd(
                "SIBI:P2250000000",
                listOf("SIBI:P2250000000", "P2250000000"),
                arrayOf("P2250000000"),
                managedOwned = true,
                hasRecord = { true },
                sameSensor = { _, _ -> true },
            ),
        )
    }

    @Test
    fun aRecordNoLongerActiveIsStillEnded() {
        // So a chunk written to it later cannot make it active again.
        assertEquals(
            listOf(removedFull, removedAlias),
            recordsToEnd(removedFull, listOf(removedFull, removedAlias), arrayOf(otherAlias)),
        )
        assertEquals(
            listOf(removedAlias, removedFull),
            recordsToEnd(removedAlias, listOf(removedAlias, removedFull), null),
        )
    }

    @Test
    fun spellingsNativeHoldsNoRecordUnderAreSkipped() {
        // An app-level id is never handed to native, which knows only its own names.
        assertEquals(
            listOf(removedAlias),
            recordsToEnd("APP-$removedAlias", listOf("APP-$removedAlias", removedAlias), arrayOf(otherAlias)),
        )
        assertEquals(emptyList<String>(), recordsToEnd("APP-0000", listOf("APP-0000"), arrayOf(otherAlias)))
    }

    /**
     * The removal itself runs on JNI and cannot be reached from a JVM test, so
     * its order is pinned by reading the source, as the other wiring tests do.
     */
    @Test
    fun theWatchEndsTheRecordsAfterTheCallbackAndBeforeTheRebuild() {
        val moduleRoot = File("").absoluteFile.let { working ->
            generateSequence(working) { it.parentFile }
                .firstOrNull { File(it, "src/main/java/tk/glucodata/WearSync2.kt").exists() }
                ?: working
        }
        val text = File(moduleRoot, "src/main/java/tk/glucodata/WearSync2.kt").readText().replace("\r\n", "\n")
        val removal = text.substringAfter("private fun removeSensorRecord(").substringBefore("\n    }\n")
        val callbackStopped = removal.indexOf("nativeRecords.forEach(SensorBluetooth::sensorEnded)")
        val recordsEnded = removal.indexOf("nativeRecords.forEach(::removeNativeRecord)")
        val rebuilt = removal.indexOf("SensorBluetooth.updateDevices()")
        assertTrue("a live callback is stopped before its record ends", callbackStopped in 0 until recordsEnded)
        assertTrue(
            "a record still active at the rebuild gets a callback again",
            recordsEnded in 0 until rebuilt,
        )
        assertTrue(
            "the record ends as the phone ends a sensor the user removed",
            text.substringAfter("private fun removeNativeRecord(")
                .contains("NativeSensorTermination.removeAndConfirm(name)"),
        )
    }

    @Test
    fun eachRecordIsNamedOnceAndPlaceholdersAreIgnored() {
        val serial = "3MH00ABCDEF"
        assertEquals(
            listOf(serial),
            recordsToEnd(
                serial,
                listOf(serial, " $serial ", "?", "", null),
                arrayOf(null, "?", serial.lowercase(), serial),
                // A placeholder is never passed on, even where native would answer to it.
                hasRecord = { it.equals(serial, ignoreCase = true) || it == "?" },
                sameSensor = { a, b -> a.equals(b, ignoreCase = true) || a == "?" },
            ),
        )
    }
}
