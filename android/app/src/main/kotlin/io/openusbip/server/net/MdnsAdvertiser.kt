package io.openusbip.server.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Announces this phone as a USB/IP server over mDNS.
 *
 * Without this the server is invisible to any client that relies on
 * auto-discovery. The port is open and `usbip list -r <ip>` works, but the
 * OpenUSBIP GUI client browses `_usbip._tcp` and nothing else, so a phone that
 * does not advertise simply never appears -- which reads as "not connected"
 * even though the server is running perfectly.
 *
 * Uses the framework's NsdManager rather than a bundled zeroconf library: it is
 * already present, and Android manages the multicast socket lifecycle across
 * network changes and doze.
 */
class MdnsAdvertiser(private val context: Context) {

    /** Must match what the client browses for. */
    private val serviceType = SERVICE_TYPE

    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    var registeredName: String? = null
        private set

    fun start(port: Int) {
        if (listener != null) return

        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            Log.w(TAG, "no NSD service available; clients must add this phone by IP")
            return
        }

        val info = NsdServiceInfo().apply {
            // Shown verbatim in the client's server list.
            serviceName = defaultServiceName()
            serviceType = this@MdnsAdvertiser.serviceType
            this.port = port

            // The client reads these to pick an icon and show a version. Match
            // the keys the Python server already publishes so both look the
            // same in the list.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("type", "android")
                setAttribute("version", "1.0")
            }
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Android may rename on collision, so report what stuck.
                registeredName = info.serviceName
                Log.i(TAG, "advertising as '${info.serviceName}' $serviceType:$port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS registration failed ($errorCode); clients must add this phone by IP")
                registeredName = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "stopped advertising")
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed ($errorCode)")
                registeredName = null
            }
        }

        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            nsdManager = manager
            listener = registration
        } catch (e: Exception) {
            // Advertising is a convenience: a failure here must not stop the
            // server, since clients can still add the phone by address.
            Log.e(TAG, "could not start mDNS advertising", e)
        }
    }

    fun stop() {
        val manager = nsdManager
        val current = listener
        listener = null
        nsdManager = null
        registeredName = null

        if (manager != null && current != null) {
            runCatching { manager.unregisterService(current) }
                .onFailure { Log.w(TAG, "unregister threw", it) }
        }
    }

    /** e.g. "moto g - 2025 (OpenUSBIP)". */
    private fun defaultServiceName(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return "$model (OpenUSBIP)"
    }

    companion object {
        private const val TAG = "UsbipMdns"

        /**
         * NsdManager wants the type without the trailing ".local." that the
         * Python side uses; both resolve to the same DNS-SD service.
         */
        const val SERVICE_TYPE = "_usbip._tcp"
    }
}
