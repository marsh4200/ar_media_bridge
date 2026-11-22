package za.co.arsmarthome.mediabridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Status screen on the TV: permissions, address, pairing code, reset. */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var pairing: Pairing
    private lateinit var statusView: TextView
    private lateinit var codeView: TextView
    private lateinit var addressView: TextView
    private lateinit var adbView: TextView

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairing = Pairing(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
            setBackgroundColor(Color.parseColor("#101418"))
        }

        root.addView(text("AR Media Bridge", 30f, bold = true, color = "#FFFFFF"))
        root.addView(text("Shows what's playing on this box in Home Assistant", 16f, color = "#9AA4AE"))

        codeView = text("", 44f, bold = true, color = "#4FC3F7").apply {
            setPadding(0, dp(20), 0, 0)
            letterSpacing = 0.15f
        }
        root.addView(text("Pairing code", 16f, color = "#9AA4AE").apply { setPadding(0, dp(24), 0, 0) })
        root.addView(codeView)

        addressView = text("", 18f, color = "#FFFFFF").apply { setPadding(0, dp(12), 0, 0) }
        root.addView(addressView)

        statusView = text("", 17f, color = "#FFFFFF").apply { setPadding(0, dp(20), 0, 0) }
        root.addView(statusView)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        buttons.addView(button("Notification access") {
            openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        })
        buttons.addView(button("Usage access") {
            openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        })
        buttons.addView(button("New code") {
            pairing.newCode(); refresh()
        })
        buttons.addView(button("Reset pairing") {
            MediaListenerService.instance?.resetPairing() ?: pairing.reset()
            refresh()
        })
        root.addView(buttons)

        adbView = text("", 14f, color = "#9AA4AE").apply {
            setPadding(0, dp(24), 0, 0)
            typeface = Typeface.MONOSPACE
        }
        root.addView(adbView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // Make sure the listener is (re)bound if access was just granted.
        try {
            NotificationListenerService.requestRebind(ComponentName(this, MediaListenerService::class.java))
        } catch (_: Exception) {
        }
        main.post(refresher)
    }

    override fun onPause() {
        main.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        codeView.text = pairing.code
        addressView.text = "Address: ${localIp() ?: "no network"} : ${MediaListenerService.PORT}"

        val notif = hasNotificationAccess()
        val usage = ForegroundTracker.hasAccess(this)
        val overlay = Settings.canDrawOverlays(this)
        val svc = MediaListenerService.instance
        statusView.text = buildString {
            appendLine(tick(notif) + "  Notification access (what's playing)")
            appendLine(tick(usage) + "  Usage access (which app is open)")
            appendLine(tick(overlay) + "  Display over other apps (launch apps from HA)")
            appendLine(tick(svc != null) + "  Bridge running")
            append("Paired Home Assistants: ${pairing.pairedCount}   Connected now: ${svc?.connectedClients ?: 0}")
        }

        val pkg = packageName
        adbView.text = if (notif && usage && overlay) "" else buildString {
            appendLine("Google TV hides some of these settings. Grant them once over ADB:")
            appendLine()
            if (!notif) appendLine("adb shell cmd notification allow_listener $pkg/$pkg.MediaListenerService")
            if (!usage) appendLine("adb shell appops set $pkg GET_USAGE_STATS allow")
            if (!overlay) appendLine("adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow")
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, MediaListenerService::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: Exception) {
            adbView.text = "This settings page doesn't exist on this box - use the ADB commands below.\n\n" + adbView.text
        }
    }

    private fun tick(ok: Boolean) = if (ok) "✔" else "✖"

    private fun text(s: String, size: Float, bold: Boolean = false, color: String) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isFocusable = true
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(16) }
        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            v.scaleX = if (has) 1.08f else 1f
            v.scaleY = if (has) 1.08f else 1f
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
