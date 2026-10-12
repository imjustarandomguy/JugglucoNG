package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.DexcomAuthPolicy.Next

class DexcomAuthPolicyTests {

    /** The challenge-reply decision before the smartwatch channel, verbatim. */
    private fun phoneChannelReference(auth: Int, bond: Int, bonded: Boolean, newcertificates: Boolean): Next {
        val isbonded = bond == 1
        return if (!newcertificates && auth != 1) {
            Next.STOP
        } else if (auth == 1 && isbonded || (bonded && bond == 2)) {
            Next.GET_DATA
        } else {
            Next.SEND_CERTIFICATES
        }
    }

    private val auths = listOf(-1, 0, 1, 2, 3, 127)
    private val bonds = listOf(-1, 0, 1, 2, 4)
    private val flags = listOf(false, true)

    @Test
    fun phoneChannelDecidesAsBefore() {
        for (auth in auths) for (bond in bonds) for (bonded in flags) for (fresh in flags) {
            assertEquals(
                "auth=$auth bond=$bond bonded=$bonded newcertificates=$fresh",
                phoneChannelReference(auth, bond, bonded, fresh),
                DexcomAuthPolicy.afterChallengeReply(auth, bond, bonded, fresh, false),
            )
        }
    }

    @Test
    fun freshPhonePairingGoesOnToTheCertificates() {
        assertEquals(Next.SEND_CERTIFICATES, DexcomAuthPolicy.afterChallengeReply(2, 0, false, true, false))
        assertEquals(Next.SEND_CERTIFICATES, DexcomAuthPolicy.afterChallengeReply(2, 2, false, true, false))
        assertEquals(Next.GET_DATA, DexcomAuthPolicy.afterChallengeReply(2, 2, true, true, false))
    }

    @Test
    fun bondedPhoneReadsWithoutCertificates() {
        assertEquals(Next.GET_DATA, DexcomAuthPolicy.afterChallengeReply(1, 1, true, false, false))
        assertEquals(Next.GET_DATA, DexcomAuthPolicy.afterChallengeReply(1, 2, true, false, false))
        assertEquals(Next.STOP, DexcomAuthPolicy.afterChallengeReply(2, 1, true, false, false))
    }

    @Test
    fun watchChannelStopsWhenNotAuthenticated() {
        for (bond in bonds) for (bonded in flags) for (fresh in flags) {
            for (auth in auths.filter { it != 1 }) {
                assertEquals(Next.STOP, DexcomAuthPolicy.afterChallengeReply(auth, bond, bonded, fresh, true))
            }
        }
    }

    @Test
    fun freshWatchPairingExchangesCertificatesEvenWhenAndroidBonded() {
        assertEquals(Next.SEND_CERTIFICATES, DexcomAuthPolicy.afterChallengeReply(1, 2, true, true, true))
        assertEquals(Next.SEND_CERTIFICATES, DexcomAuthPolicy.afterChallengeReply(1, 0, false, true, true))
        assertEquals(Next.GET_DATA, DexcomAuthPolicy.afterChallengeReply(1, 2, true, false, true))
        assertEquals(Next.GET_DATA, DexcomAuthPolicy.afterChallengeReply(1, 1, false, true, true))
    }
}
