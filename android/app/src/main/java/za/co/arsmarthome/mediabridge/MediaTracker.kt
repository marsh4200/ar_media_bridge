package za.co.arsmarthome.mediabridge

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.util.Log

/**
 * Follows every active media session on the box (Netflix, YouTube, Plex...)
 * and calls [onChange] whenever playback state or metadata changes.
 * Needs notification-listener access, which is what makes getActiveSessions legal.
 */
class MediaTracker(
    context: Context,
    private val listenerComponent: ComponentName,
    private val handler: Handler,
    private val onChange: () -> Unit,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val callbacks = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var ordered: List<MediaController> = emptyList()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> update(list ?: emptyList()) }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            update(msm.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification access yet - media sessions unavailable", e)
        }
    }

    fun stop() {
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        callbacks.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        ordered = emptyList()
    }

    private fun refresh() {
        try {
            update(msm.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            update(emptyList())
        }
    }

    private fun update(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        callbacks.keys.filter { it !in tokens }.forEach { token ->
            callbacks.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        for (controller in list) {
            if (controller.sessionToken in callbacks) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    onChange()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    onChange()
                }

                override fun onSessionDestroyed() {
                    handler.post { refresh() }
                }
            }
            controller.registerCallback(cb, handler)
            callbacks[controller.sessionToken] = Pair(controller, cb)
        }
        // Keep the system's priority order (most recently active first).
        ordered = list.mapNotNull { callbacks[it.sessionToken]?.first }
        onChange()
    }

    /** The session Home Assistant should show: playing beats paused beats anything. */
    fun primary(): MediaController? {
        val playing = ordered.firstOrNull { isActive(it.playbackState?.state, playingOnly = true) }
        return playing
            ?: ordered.firstOrNull { isActive(it.playbackState?.state, playingOnly = false) }
            ?: ordered.firstOrNull()
    }

    private fun isActive(state: Int?, playingOnly: Boolean): Boolean = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> true
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING -> !playingOnly
        else -> false
    }

    companion object {
        private const val TAG = "ARMediaBridge"
    }
}
