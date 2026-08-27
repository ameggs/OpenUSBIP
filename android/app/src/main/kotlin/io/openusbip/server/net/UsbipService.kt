package io.openusbip.server.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.openusbip.server.MainActivity
import io.openusbip.server.protocol.Protocol
import io.openusbip.server.storage.FileBlockDevice
import io.openusbip.server.mtp.MtpBackend
import io.openusbip.server.storage.MassStorageBackend
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Runs the USB/IP server for as long as devices are being shared.
 *
 * A foreground service rather than a background one is not optional here: a
 * client holds a device across an arbitrarily long session, and Android will
 * happily kill a backgrounded process mid-transfer, which to the client looks
 * like the device being yanked out.
 */
class UsbipService : Service() {

    private var server: UsbipServer? = null
    private val registry = DeviceRegistry()
    private val mdns by lazy { MdnsAdvertiser(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        startSharing()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy -- service is going away, sharing stops")
        stopSharing()
        super.onDestroy()
    }

    // --- sharing ----------------------------------------------------------

    private fun startSharing() {
        if (server != null) return

        try {
            // The virtual disk lives in app-specific external storage, so it
            // needs no runtime permission and is still visible over MTP.
            val image = FileBlockDevice.createIfAbsent(diskImageFile(this), defaultImageSize(this))
            val backend = MassStorageBackend(
                block = FileBlockDevice(image),
                busid = VIRTUAL_DISK_BUSID,
            )
            registry.add(backend)

            // Second device: MTP over the same server. This is the one that
            // exposes real files, because MTP is file-level and so does not
            // need exclusive ownership of the filesystem the way a block
            // device does.
            val sharedDir = sharedFilesDir(this)
            sharedDir.mkdirs()
            registry.add(MtpBackend(sharedDir, busid = MTP_BUSID, deviceName = Build.MODEL ?: "Android"))

            val instance = UsbipServer(
                registry = registry,
                port = Protocol.DEFAULT_PORT,
                listener = object : UsbipServer.Listener {
                    override fun onClientConnected(client: String) = publish()
                    override fun onClientDisconnected(client: String) = publish()
                    override fun onDeviceImported(client: String, busid: String) = publish()
                    override fun onDeviceReleased(client: String, busid: String) = publish()
                    override fun onError(context: String, error: Throwable) {
                        Log.w(TAG, "server error in $context", error)
                        lastError = "$context: ${error.message}"
                        publish()
                    }
                },
            )
            instance.start()
            server = instance
            isRunning = true
            lastError = null
            Log.i(TAG, "listening on ${localAddresses().joinToString { "${it.iface}=${it.address}" }} port ${Protocol.DEFAULT_PORT}, image=${image.absolutePath} (${image.length()} bytes)")

            // Announce over mDNS so discovery-based clients find the phone
            // without being told its address.
            mdns.start(Protocol.DEFAULT_PORT)
            publish()
        } catch (e: Exception) {
            // Log as well as record: a service that swallows its own startup
            // failure leaves nothing to diagnose from.
            Log.e(TAG, "failed to start sharing", e)
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            isRunning = false
            publish()
            updateNotification("Failed to start")
        }
    }

    private fun stopSharing() {
        if (server != null) Log.i(TAG, "stopping server")
        mdns.stop()
        server?.stop()
        server = null
        registry.clear()
        isRunning = false
        publish()
    }

    private fun publish() {
        sharedDevices = registry.all().map { (device, holder) ->
            SharedDevice(
                busid = device.busid,
                label = "${device.busid}  ${"%04x:%04x".format(device.idVendor, device.idProduct)}",
                heldBy = holder,
            )
        }
        onStateChanged?.invoke()
        updateNotification(
            when {
                !isRunning -> "Stopped"
                sharedDevices.any { it.heldBy != null } ->
                    "In use by ${sharedDevices.firstNotNullOf { it.heldBy }}"
                else -> "Sharing ${sharedDevices.size} device(s) on ${primaryAddress() ?: "?"}"
            },
        )
    }

    // --- notification -----------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "USB sharing",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while USB devices are shared over the network" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("OpenUSBIP Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    companion object {
        private const val TAG = "UsbipService"
        private const val CHANNEL_ID = "usbip_sharing"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "io.openusbip.server.STOP"

        /** Virtual devices sit on bus 99 so they cannot collide with OTG. */
        const val VIRTUAL_DISK_BUSID = "99-1"
        const val MTP_BUSID = "99-2"

        /** One shared device, as the UI needs to see it. */
        data class SharedDevice(val busid: String, val label: String, val heldBy: String?)

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var sharedDevices: List<SharedDevice> = emptyList()
            private set

        @Volatile
        var lastError: String? = null
            private set

        /** Set by the activity so it can redraw when something changes. */
        @Volatile
        var onStateChanged: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, UsbipService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, UsbipService::class.java).setAction(ACTION_STOP),
            )
        }

        /**
         * Directory served over MTP.
         *
         * App-specific external storage needs no runtime permission and is
         * visible to the user over a cable, so it works out of the box.
         * Serving the whole of shared storage would need
         * MANAGE_EXTERNAL_STORAGE, granted by hand in Settings.
         */
        fun sharedFilesDir(context: Context): File =
            File(context.getExternalFilesDir(null), "shared")

        /** Where the virtual disk image lives. */
        fun diskImageFile(context: Context): File =
            File(context.getExternalFilesDir(null), "openusbip/disk.img")

        /**
         * Default image size. Sparse, so it costs nothing until written -- but
         * still bounded, because a client that fills it would otherwise fill
         * the phone.
         */
        fun defaultImageSize(context: Context): Long = 2L * 1024 * 1024 * 1024

        /** One reachable address, tagged with the interface it belongs to. */
        data class LocalAddress(val iface: String, val address: String) {
            /** Carrier-grade NAT, which on a phone means a VPN like Tailscale. */
            val isCgnat: Boolean get() = address.startsWith("100.") &&
                (address.split('.').getOrNull(1)?.toIntOrNull() ?: 0) in 64..127

            val isMobile: Boolean get() = iface.startsWith("ccmni") ||
                iface.startsWith("rmnet") || iface.startsWith("radio")
        }

        /**
         * Every non-loopback IPv4 address, with its interface.
         *
         * Reporting just "the first one" was actively misleading: on a phone
         * with a VPN up, the first interface enumerated was tun0, so the app
         * advertised a Tailscale address while clients on the LAN needed the
         * wlan0 one. Which address a client should use depends on where the
         * client is, so show them all and let the user pick.
         */
        fun localAddresses(): List<LocalAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { addr ->
                            addr.hostAddress?.let { LocalAddress(nif.name, it) }
                        }
                }
        }.getOrDefault(emptyList())

        /**
         * The address most likely to be what a user wants, for the
         * notification line. Prefers Wi-Fi, then wired, then anything that is
         * neither VPN nor mobile data.
         */
        fun primaryAddress(): String? {
            val all = localAddresses()
            return (
                all.firstOrNull { it.iface.startsWith("wlan") }
                    ?: all.firstOrNull { it.iface.startsWith("eth") }
                    ?: all.firstOrNull { !it.isCgnat && !it.isMobile }
                    ?: all.firstOrNull()
                )?.address
        }

        @Deprecated("Ambiguous with more than one interface", ReplaceWith("primaryAddress()"))
        fun localIpv4(): String? = primaryAddress()
    }
}
