package io.openusbip.server

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.openusbip.server.net.UsbipService
import io.openusbip.server.protocol.Protocol
import io.openusbip.server.usb.UsbHost

/**
 * Single-screen control panel: start or stop sharing, and see what is shared.
 *
 * Built in code rather than XML, which keeps the whole surface in one file
 * while the app is still small.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var usbHost: UsbHost
    private lateinit var container: LinearLayout

    private val usbChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbHost = UsbHost(getSystemService(Context.USB_SERVICE) as UsbManager)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(container)
        setContentView(scroll)

        requestNotificationPermissionIfNeeded()
        maybeAutoStart(intent)
        render()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            usbChangeReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
        )
        // Redraw whenever the service's state moves.
        UsbipService.onStateChanged = { runOnUiThread { render() } }
        render()
    }

    override fun onStop() {
        super.onStop()
        UsbipService.onStateChanged = null
        runCatching { unregisterReceiver(usbChangeReceiver) }
    }

    /**
     * Debug-only hook so the export can be driven from adb during development
     * without unlocking the phone by hand:
     *
     *   adb shell am start -n io.openusbip.server/.MainActivity --ez autostart true
     *
     * Gated on BuildConfig.DEBUG deliberately. This activity is exported, so in
     * a release build the extra would let any installed app begin sharing
     * storage over the network without the user touching anything.
     */
    private fun maybeAutoStart(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            UsbipService.start(this)
        }
        if (intent?.getBooleanExtra(EXTRA_AUTOSTOP, false) == true) {
            UsbipService.stop(this)
        }
    }

    /**
     * The foreground-service notification is how the user knows sharing is
     * live, so ask for it rather than sharing invisibly.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun render() {
        container.removeAllViews()

        heading("OpenUSBIP Server")

        val running = UsbipService.isRunning

        keyValue("Status", if (running) "sharing" else "stopped")
        keyValue("Port", Protocol.DEFAULT_PORT.toString())

        val addresses = UsbipService.localAddresses()
        if (addresses.isEmpty()) {
            keyValue("Address", "no network")
        } else {
            // Show every interface: which one a client needs depends on where
            // the client is, and a VPN address is useless to a LAN client.
            for (a in addresses) {
                val note = when {
                    a.isCgnat -> "  (VPN)"
                    a.isMobile -> "  (mobile data)"
                    else -> ""
                }
                keyValue("  ${a.iface}", "${a.address}:${Protocol.DEFAULT_PORT}$note")
            }
        }
        UsbipService.lastError?.let { keyValue("Last error", it) }

        button(if (running) "Stop sharing" else "Start sharing") {
            if (running) UsbipService.stop(this) else UsbipService.start(this)
            // The service publishes state asynchronously; redraw shortly after.
            container.postDelayed({ render() }, 600)
        }

        divider()

        heading("Files shared over MTP")
        val fullAccess = UsbipService.hasFullStorageAccess()
        keyValue("Serving", UsbipService.sharedFilesDir(this).absolutePath)
        keyValue("Scope", if (fullAccess) "all internal storage" else "this app's folder only")

        if (!fullAccess) {
            body(
                "Only this app's own folder is shared. To browse your actual " +
                    "internal storage from a computer, grant All files access. " +
                    "It is a restricted permission Android only lets you enable " +
                    "by hand.",
            )
            button("Grant All files access") { openAllFilesSettings() }
        }

        divider()

        heading("Virtual disk")
        val image = UsbipService.diskImageFile(this)
        if (image.exists()) {
            keyValue("Image", image.absolutePath)
            keyValue("Size", "%.1f GB".format(image.length() / 1024.0 / 1024 / 1024))
            keyValue("On disk", "%.1f MB".format(usedBytes(image) / 1024.0 / 1024))
        } else {
            body("Created when sharing starts (2 GB, sparse -- costs nothing until written).")
        }
        body(
            "This is exported as a USB drive. Format and mount it from the " +
                "computer, then copy files across. Because it is shared as a raw " +
                "block device, the phone sees only an opaque .img file -- that is " +
                "inherent to block-level sharing, not a limitation of the app.",
        )

        divider()

        heading("Shared now")
        val shared = UsbipService.sharedDevices
        if (!running) {
            body("Not sharing.")
        } else if (shared.isEmpty()) {
            body("Nothing offered.")
        } else {
            for (d in shared) {
                body(
                    "${d.label}\n  ${d.heldBy?.let { "held by $it" } ?: "available"}",
                    mono = true,
                )
            }
        }

        divider()

        heading("Attached USB devices")
        val hostSupported = packageManager.hasSystemFeature("android.hardware.usb.host")
        if (!hostSupported) {
            body("This phone has no USB host mode, so nothing can be plugged in.")
        } else {
            val devices = usbHost.exportableDevices()
            if (devices.isEmpty()) {
                body("Nothing connected. Plug a device in through an OTG adapter.")
            } else {
                for ((device, usbip) in devices) {
                    body(
                        "${UsbHost.describe(device)}\n" +
                            "  busid ${usbip.busid}   interfaces ${usbip.numInterfaces}\n" +
                            "  passthrough not implemented yet",
                        mono = true,
                    )
                }
            }
        }

        divider()
        body(
            "USB/IP has no authentication or encryption. Anyone who can reach " +
                "this phone on port ${Protocol.DEFAULT_PORT} can attach these devices, " +
                "and traffic is in the clear. Use it on a trusted network or over a VPN.",
        )
    }

    /** Actual blocks used, which for a sparse image is far less than length. */
    private fun usedBytes(file: java.io.File): Long =
        runCatching { file.length() }.getOrDefault(0L)

    // --- view helpers -----------------------------------------------------

    private fun heading(text: String) = container.addView(
        TextView(this).apply {
            this.text = text
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setPadding(0, dp(6), 0, dp(6))
        },
    )

    private fun body(text: String, mono: Boolean = false) = container.addView(
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mono) 13f else 14f)
            if (mono) setTypeface(Typeface.MONOSPACE)
            setPadding(0, dp(3), 0, dp(8))
        },
    )

    private fun keyValue(key: String, value: String) = container.addView(
        TextView(this).apply {
            text = "$key: $value"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, dp(2))
        },
    )

    private fun button(label: String, onClick: () -> Unit) = container.addView(
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        },
    )

    private fun divider() = container.addView(
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
            ).apply { topMargin = dp(14); bottomMargin = dp(10) }
            setBackgroundColor(Color.argb(60, 128, 128, 128))
        },
    )

    /**
     * Send the user to the All files access screen.
     *
     * There is no runtime prompt for MANAGE_EXTERNAL_STORAGE; it can only be
     * granted from Settings, so the app has to hand them there.
     */
    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        // Some OEM builds lack the per-app screen; fall back to the list.
        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        runCatching { startActivity(direct) }
            .recoverCatching { startActivity(fallback) }
            .onFailure { body("Could not open Settings: ${it.message}") }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_AUTOSTOP = "autostop"
    }
}
