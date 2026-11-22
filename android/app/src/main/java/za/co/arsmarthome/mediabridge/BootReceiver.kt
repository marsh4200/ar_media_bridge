package za.co.arsmarthome.mediabridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

/** Nudges Android to rebind the listener (and so restart the bridge) after boot or update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(context, MediaListenerService::class.java)
            )
        } catch (_: Exception) {
        }
    }
}
