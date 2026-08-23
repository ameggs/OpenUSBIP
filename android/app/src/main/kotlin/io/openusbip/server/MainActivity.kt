package io.openusbip.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.openusbip.server.protocol.Protocol
import io.openusbip.server.usb.UsbHost
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Minimal first screen: what this phone can export, and on what address.
 *
 * Deliberately built in code rather than XML -- fewer moving parts while the
 * USB and network layers are still being brought up.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var usbHost: UsbHost
    private lateinit var container: LinearLayout

    private val usbChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A device came or went; redraw.
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbHost = UsbHost(getSystemService(Context.USB_SERVICE) as UsbManager)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(container)
        setContentView(scroll)

        render()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbChangeReceiver, filter)
        render()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(usbChangeReceiver) }
    }

    private fun render() {
        container.removeAllViews()

        heading("OpenUSBIP Server")
        body("Shares USB devices plugged into this phone with computers on your network.")

        divider()

        val hostSupported = packageManager.hasSystemFeature("android.hardware.usb.host")
        heading("This device")
        keyValue("USB host mode", if (hostSupported) "supported" else "NOT SUPPORTED")
        keyValue("Listen address", "${localIpv4() ?: "no network"}:${Protocol.DEFAULT_PORT}")

        if (!hostSupported) {
            body(
                "Without USB host mode this phone cannot talk to USB peripherals, " +
                    "so there is nothing to export.",
            )
            return
        }

        divider()

        val exportable = usbHost.exportableDevices()
        heading("USB devices (${exportable.size})")

        if (exportable.isEmpty()) {
            body(
                "Nothing connected. Plug a USB device in through an OTG adapter. " +
                    "Hubs are ignored, and USB audio and webcams cannot be shared " +
                    "because Android provides no isochronous transfer API.",
            )
            return
        }

        for ((device, usbip) in exportable) {
            val permission = if (usbHost.hasPermission(device)) "granted" else "not granted yet"
            body(
                buildString {
                    appendLine(UsbHost.describe(device))
                    appendLine("  busid        ${usbip.busid}")
                    appendLine("  bus/device   ${usbip.busnum}/${usbip.devnum}")
                    appendLine("  class        ${hex2(usbip.deviceClass)}")
                    appendLine("  interfaces   ${usbip.numInterfaces}")
                    appendLine("  permission   $permission")
                    append("  path         ${usbip.path}")
                },
                mono = true,
            )
        }
    }

    // --- tiny view helpers ----------------------------------------------

    private fun heading(text: String) = container.addView(
        TextView(this).apply {
            this.text = text
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(8), 0, dp(6))
        },
    )

    private fun body(text: String, mono: Boolean = false) = container.addView(
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mono) 13f else 15f)
            if (mono) setTypeface(Typeface.MONOSPACE)
            setPadding(0, dp(4), 0, dp(10))
        },
    )

    private fun keyValue(key: String, value: String) = container.addView(
        TextView(this).apply {
            text = "$key: $value"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, dp(2))
        },
    )

    private fun divider() = container.addView(
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
            ).apply { topMargin = dp(12); bottomMargin = dp(12) }
            setBackgroundColor(Color.argb(60, 128, 128, 128))
        },
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hex2(value: Int) = String.format("0x%02x", value)

    /** First non-loopback IPv4 address, for showing where clients should connect. */
    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }.getOrNull()
}
