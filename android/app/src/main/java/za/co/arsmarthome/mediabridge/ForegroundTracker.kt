package za.co.arsmarthome.mediabridge

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process

/**
 * Works out which app is on screen using usage-stats events.
 * Needs "usage access" (granted via ADB on Google TV, where the settings page is hidden).
 */
class ForegroundTracker(private val context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = 0L

    var current: String? = null
        private set

    val homePackage: String? by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    /** Returns true when the foreground app changed. */
    fun poll(): Boolean {
        if (!hasAccess(context)) return false
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 24 * 60 * 60 * 1000L else lastQuery - 2_000L
        lastQuery = now

        var latest: String? = null
        try {
            val events = usm.queryEvents(from, now)
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // 1 == MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                if (event.eventType == 1 && !isIgnored(event.packageName)) {
                    latest = event.packageName
                }
            }
        } catch (_: Exception) {
            return false
        }

        if (latest != null && latest != current) {
            current = latest
            return true
        }
        return false
    }

    private fun isIgnored(pkg: String?): Boolean =
        pkg == null ||
            pkg == context.packageName ||
            pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg.startsWith("com.google.android.tvrecommendations") ||
            pkg.contains("inputmethod")

    companion object {
        @Suppress("DEPRECATION")
        fun hasAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
