package tk.glucodata

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.Keep
import tk.glucodata.NightscoutWifiGatePolicy.Transport

/**
 * "Upload only on Wi-Fi" for the Nightscout uploader.
 *
 * With this on, the uploader sends nothing at all while the phone's default network is mobile
 * data (or anything else that is neither Wi-Fi nor Ethernet): no request, so no failure and no
 * backoff either. The native uploader asks [uploadAllowed] at the start of each pass; when Wi-Fi
 * becomes the default network again, this wakes it, and it catches up from where it stopped.
 *
 * Only the kind of network is looked at, never its name, which Android gives only to apps with
 * location access: ACCESS_NETWORK_STATE is all it takes.
 */
@Keep
object NightscoutWifiGate {
    private const val LOG_ID = "NightscoutWifiGate"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_ENABLED = "nightscout_upload_wifi_only"

    @Volatile private var enabled: Boolean? = null
    private var monitor: ConnectivityManager.NetworkCallback? = null
    private var lastAllowed = true

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        enabled ?: prefs(context).getBoolean(PREF_ENABLED, false).also { enabled = it }

    @JvmStatic
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(PREF_ENABLED, on).apply()
        enabled = on
        if (on) {
            startMonitoring(context)
        } else {
            stopMonitoring(context)
            // What was held back may go now.
            wakeUploader()
        }
    }

    /**
     * Whether the uploader may send anything now. Called by the native uploader once per pass.
     * Asks Android rather than trusting the callback, whose news may arrive after the network
     * change has already woken the uploader: on the way off Wi-Fi that pass would still send.
     */
    @JvmStatic
    fun uploadAllowed(): Boolean {
        val context = Applic.app ?: return true
        if (!isEnabled(context)) return true
        return try {
            startMonitoring(context)
            NightscoutWifiGatePolicy.allows(defaultNetworkTransports(context))
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "uploadAllowed", th)
            false
        }
    }

    /** True while the option holds uploads back, for the settings screen. */
    @JvmStatic
    fun isWaiting(context: Context): Boolean =
        isEnabled(context) && !NightscoutWifiGatePolicy.allows(defaultNetworkTransports(context))

    private fun defaultNetworkTransports(context: Context): Set<Transport>? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        return connectivity.getNetworkCapabilities(network)?.let(::transportsOf)
    }

    /**
     * A VPN's capabilities carry the transports of the networks it runs over beside its own, so
     * the policy can judge it by those. Bluetooth and USB tethering count as neither Wi-Fi nor
     * Ethernet.
     */
    private fun transportsOf(capabilities: NetworkCapabilities): Set<Transport> = buildSet {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(Transport.WIFI)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(Transport.ETHERNET)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(Transport.CELLULAR)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(Transport.VPN)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB))
        ) {
            add(Transport.OTHER)
        }
    }

    private fun wakeUploader() {
        if (!Applic.Nativesloaded) return
        runCatching { Natives.wakeuploadernow() }
            .onFailure { Log.stack(LOG_ID, "wake", it) }
    }

    /**
     * Follows the default network so the uploader is woken as soon as it is Wi-Fi again. The
     * network change wakes it too, but may come before this hears of it, and then finds the
     * uploader still held back.
     */
    @Synchronized
    private fun startMonitoring(context: Context) {
        if (monitor != null) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            // A switch reports the new default first; the old one's loss must not undo it.
            private var current: Network? = null

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                current = network
                defaultNetworkChanged(transportsOf(capabilities))
            }

            override fun onLost(network: Network) {
                if (network != current) return
                current = null
                defaultNetworkChanged(null)
            }
        }
        // Nothing is known until Android reports the default network, which it does right away.
        lastAllowed = false
        connectivity.registerDefaultNetworkCallback(callback)
        monitor = callback
    }

    @Synchronized
    private fun stopMonitoring(context: Context) {
        val callback = monitor ?: return
        monitor = null
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }.onFailure { Log.stack(LOG_ID, "unregister", it) }
    }

    private fun defaultNetworkChanged(transports: Set<Transport>?) {
        val allowed = NightscoutWifiGatePolicy.allows(transports)
        val opened = synchronized(this) {
            val opened = allowed && !lastAllowed
            lastAllowed = allowed
            opened
        }
        if (opened) wakeUploader()
    }
}

/** The decision of [NightscoutWifiGate], apart from Android. */
internal object NightscoutWifiGatePolicy {
    enum class Transport { WIFI, ETHERNET, CELLULAR, VPN, OTHER }

    /**
     * Whether uploads may go out over the default network, given by its transports (null when
     * there is none): Wi-Fi or Ethernet only.
     *
     * A VPN is judged by what it runs over. Android lists the transports of a VPN's underlying
     * networks with its own; one that lists none leaves it unknown, and one that lists any other
     * may be carried by mobile data. Either way it is held back.
     */
    fun allows(transports: Set<Transport>?): Boolean {
        val beneath = transports?.minus(Transport.VPN) ?: return false
        return beneath.isNotEmpty() && beneath.all { it == Transport.WIFI || it == Transport.ETHERNET }
    }
}
