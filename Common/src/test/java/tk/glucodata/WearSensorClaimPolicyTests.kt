package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSensorClaimPolicyTests {
    private val requestedAtMs = 10_000L

    @Test
    fun syncedOrRestoredStateCannotProveLocalOwnership() {
        assertFalse(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "222227JR7C",
                hasLocallyConnectedGatt = false,
                requestedAtMs = requestedAtMs,
                localReadingSerial = "X-222227JR7C",
                localReadingAcceptedAtMs = requestedAtMs + 1L,
            )
        )
        assertFalse(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "222227JR7C",
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestedAtMs,
                localReadingSerial = null,
                localReadingAcceptedAtMs = 0L,
            )
        )
    }

    @Test
    fun readingMustArriveAfterRequestFromMatchingCallback() {
        assertFalse(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "222227JR7C",
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestedAtMs,
                localReadingSerial = "222227JR7C",
                localReadingAcceptedAtMs = requestedAtMs - 1L,
            )
        )
        assertFalse(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "OTHER",
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestedAtMs,
                localReadingSerial = "222227JR7C",
                localReadingAcceptedAtMs = requestedAtMs + 1L,
            )
        )
    }

    @Test
    fun connectedLocalGattAndPostRequestAliasReadingProveOwnership() {
        assertTrue(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "222227JR7C",
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestedAtMs,
                localReadingSerial = "X-222227JR7C",
                localReadingAcceptedAtMs = requestedAtMs + 1L,
            )
        )
    }

    private fun restores(
        directRequested: Boolean = false,
        storedRequest: Boolean = true,
        targetSensorId: String? = "4444455555666664",
        readingSerial: String? = "4444455555666664",
    ) = WearSensorClaimPolicy.restoresDirectRequest(directRequested, storedRequest, targetSensorId, readingSerial)

    @Test
    fun aClaimedSensorsReadingBringsBackARequestTheClaimWindowDropped() {
        assertTrue(restores())
    }

    @Test
    fun onlyADroppedRequestWhileDirectModeIsOnIsBroughtBack() {
        assertFalse("still requested", restores(directRequested = true))
        assertFalse("direct mode off", restores(storedRequest = false))
    }

    @Test
    fun anotherSensorsReadingBringsNothingBack() {
        assertFalse(restores(readingSerial = "1111122222333331"))
        assertFalse(restores(targetSensorId = null))
        assertFalse(restores(readingSerial = null))
    }
}
