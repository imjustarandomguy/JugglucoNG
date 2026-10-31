package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.NightscoutWifiGatePolicy.Transport

/** "Upload only on Wi-Fi": which default networks the uploader may send over. */
class NightscoutWifiGatePolicyTests {
    private fun allows(vararg transports: Transport) = NightscoutWifiGatePolicy.allows(transports.toSet())

    @Test
    fun wifiAndEthernetCarryUploads() {
        assertTrue(allows(Transport.WIFI))
        assertTrue(allows(Transport.ETHERNET))
    }

    @Test
    fun mobileDataHoldsThemBack() {
        assertFalse(allows(Transport.CELLULAR))
    }

    @Test
    fun noDefaultNetworkHoldsThemBack() {
        assertFalse(NightscoutWifiGatePolicy.allows(null))
        assertFalse(allows())
    }

    /** Android lists the transports a VPN runs over beside its own. */
    @Test
    fun aVpnIsJudgedByWhatItRunsOver() {
        assertTrue(allows(Transport.VPN, Transport.WIFI))
        assertTrue(allows(Transport.VPN, Transport.ETHERNET))
        assertFalse(allows(Transport.VPN, Transport.CELLULAR))
        assertFalse("part of it may go over mobile data", allows(Transport.VPN, Transport.WIFI, Transport.CELLULAR))
    }

    @Test
    fun aVpnOverSomethingUnknownHoldsThemBack() {
        assertFalse(allows(Transport.VPN))
        assertFalse(allows(Transport.VPN, Transport.OTHER))
    }

    @Test
    fun tetheringOverBluetoothOrUsbIsNotWifi() {
        assertFalse(allows(Transport.OTHER))
    }
}
