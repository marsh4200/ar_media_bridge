package za.co.arsmarthome.mediabridge

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import org.java_websocket.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The heart of the bridge. Android keeps a notification listener bound (and
 * rebinds it after reboot), so this service hosts everything: the media
 * session tracker, foreground-app tracker, WebSocket server and mDNS advert.
 */
class MediaListenerService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var pairing: Pairing
    private lateinit var media: MediaTracker
    private lateinit var foreground: ForegroundTracker
    private lateinit var nsd: NsdAdvertiser
    private lateinit var audio: AudioManager
    private var server: BridgeServer? = null
    private var running = false

    private var lastStateJson: String? = null
    private var lastArtworkKey: Any? = null
    private var artworkHash: String? = null
    private var artworkData: String? = null
    private var pushPending = false

    private val component by lazy { ComponentName(this, MediaListenerService::class.java) }

    // ---------------------------------------------------------------- lifecycle

    override fun onListenerConnected() {
        super.onListenerConnected()
        startBridge()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        stopBridge()
        NotificationListenerService.requestRebind(component)
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    private fun startBridge() {
        if (running) return
        running = true
        instance = this
        pairing = Pairing(this)
        audio = getSystemService(AudioManager::class.java)
        foreground = ForegroundTracker(this)
        media = MediaTracker(this, component, main) { schedulePush() }
        nsd = NsdAdvertiser(this)

        server = BridgeServer(PORT, main, ::onClientOpen, ::onClientMessage).also { it.start() }
        nsd.register(deviceName(), PORT, pairing.deviceId)
        media.start()
        main.post(pollRunnable)
        Log.i(TAG, "Bridge started")
    }

    private fun stopBridge() {
        if (!running) return
        running = false
        main.removeCallbacks(pollRunnable)
        try {
            media.stop()
        } catch (_: Exception) {
        }
        nsd.unregister()
        try {
            server?.stop(1000)
        } catch (_: Exception) {
        }
        server = null
        if (instance === this) instance = null
        Log.i(TAG, "Bridge stopped")
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (foreground.poll()) schedulePush()
            else if (volumeChanged()) schedulePush()
            main.postDelayed(this, POLL_MS)
        }
    }

    private var lastVolume = -1
    private var lastMuted = false
    private fun volumeChanged(): Boolean {
        val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val m = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val changed = v != lastVolume || m != lastMuted
        lastVolume = v
        lastMuted = m
        return changed
    }

    // ---------------------------------------------------------------- protocol

    private fun onClientOpen(conn: WebSocket) {
        conn.send(hello().toString())
    }

    private fun onClientMessage(conn: WebSocket, raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            conn.send(error("bad_json").toString())
            return
        }
        val id = msg.opt("id")
        when (msg.optString("type")) {
            "ping" -> conn.send(JSONObject().put("type", "pong").toString())

            "request_pin" -> {
                showPinToast()
                conn.send(JSONObject().put("type", "pin_shown").toString())
            }

            "pair" -> when (val r = pairing.tryPair(msg.optString("code"))) {
                is Pairing.Result.Ok -> {
                    server?.markAuthed(conn)
                    conn.send(
                        JSONObject().put("type", "paired").put("token", r.token)
                            .put("device", deviceInfo()).toString()
                    )
                    Toast.makeText(this, "Paired with ${msg.optString("client_name", "Home Assistant")}", Toast.LENGTH_SHORT).show()
                    sendInitial(conn)
                }
                Pairing.Result.BadCode -> conn.send(error("invalid_code").toString())
                Pairing.Result.Locked -> conn.send(error("locked").toString())
            }

            "auth" -> if (pairing.isValid(msg.optString("token"))) {
                server?.markAuthed(conn)
                conn.send(JSONObject().put("type", "auth_ok").put("device", deviceInfo()).toString())
                sendInitial(conn)
            } else {
                conn.send(JSONObject().put("type", "auth_failed").toString())
            }

            else -> {
                if (server?.isAuthed(conn) != true) {
                    conn.send(error("not_authenticated").toString())
                    return
                }
                handleAuthed(conn, msg, id)
            }
        }
    }

    private fun handleAuthed(conn: WebSocket, msg: JSONObject, id: Any?) {
        when (msg.optString("type")) {
            "get_state" -> sendInitial(conn)
            "get_apps" -> conn.send(appsMessage().toString())
            "command" -> {
                val result = runCommand(msg)
                val reply = JSONObject().put("type", "result").put("success", result == null)
                if (id != null) reply.put("id", id)
                if (result != null) reply.put("error", result)
                conn.send(reply.toString())
                schedulePush(fast = true)
            }
            else -> conn.send(error("unknown_type").toString())
        }
    }

    private fun sendInitial(conn: WebSocket) {
        conn.send(appsMessage().toString())
        val state = buildState()
        conn.send(state.toString())
        artworkData?.let {
            conn.send(JSONObject().put("type", "artwork").put("hash", artworkHash).put("data", it).toString())
        }
    }

    private fun error(code: String) = JSONObject().put("type", "error").put("error", code)

    private fun hello() = JSONObject()
        .put("type", "hello")
        .put("protocol", PROTOCOL)
        .put("device", deviceInfo())

    private fun deviceInfo() = JSONObject()
        .put("id", pairing.deviceId)
        .put("name", deviceName())
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("android", Build.VERSION.RELEASE)
        .put("version", BuildConfig.VERSION_NAME)

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun showPinToast() {
        val text = "Home Assistant pairing code: ${pairing.code}"
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        // Show it a second time so it stays up ~7s.
        main.postDelayed({ Toast.makeText(this, text, Toast.LENGTH_LONG).show() }, 3_500)
    }

    // ---------------------------------------------------------------- state

    private fun schedulePush(fast: Boolean = false) {
        if (pushPending) return
        pushPending = true
        main.postDelayed({
            pushPending = false
            pushState()
        }, if (fast) 50 else 150)
    }

    private fun pushState() {
        val srv = server ?: return
        val state = buildState()
        // Compare without position stamps so we only push real changes.
        val compare = JSONObject(state.toString()).apply {
            remove("position_ms"); remove("position_updated_at")
        }.toString()
        val changed = compare != lastStateJson
        lastStateJson = compare
        if (changed || artworkJustChanged) {
            srv.sendToAuthed(state.toString())
        }
        if (artworkJustChanged) {
            artworkJustChanged = false
            srv.sendToAuthed(
                JSONObject().put("type", "artwork").put("hash", artworkHash ?: JSONObject.NULL)
                    .put("data", artworkData ?: JSONObject.NULL).toString()
            )
        }
    }

    private var artworkJustChanged = false

    private fun buildState(): JSONObject {
        val controller = media.primary()
        val ps = controller?.playbackState
        val md = controller?.metadata
        val mediaPkg = controller?.packageName
        val fgPkg = foreground.current

        val mediaState = when (ps?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> "buffering"
            else -> "idle"
        }

        // If something is playing, that app wins. If media is paused but the user
        // has moved to another app, report the app on screen as idle.
        val mediaInFront = fgPkg == null || fgPkg == mediaPkg
        val effectiveState = when {
            mediaState == "playing" || mediaState == "buffering" -> mediaState
            mediaState == "paused" && mediaInFront -> "paused"
            else -> "idle"
        }
        val showMedia = effectiveState != "idle"
        val appPkg = if (showMedia) mediaPkg else (fgPkg ?: mediaPkg)

        val out = JSONObject()
            .put("type", "state")
            .put("state", effectiveState)
            .put("app_package", appPkg ?: JSONObject.NULL)
            .put("app_name", appLabel(appPkg) ?: JSONObject.NULL)
            .put("is_home", appPkg != null && appPkg == foreground.homePackage)
            .put("foreground_package", fgPkg ?: JSONObject.NULL)
            .put("media_package", mediaPkg ?: JSONObject.NULL)
            .put("media_state", mediaState)

        if (showMedia && md != null) {
            out.put("title", md.text(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: JSONObject.NULL)
            out.put("artist", md.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: JSONObject.NULL)
            out.put("album", md.text(MediaMetadata.METADATA_KEY_ALBUM) ?: JSONObject.NULL)
            val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            out.put("duration_ms", if (dur > 0) dur else JSONObject.NULL)
        }

        if (showMedia && ps != null) {
            var pos = ps.position
            if (ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
                pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
            }
            out.put("position_ms", if (pos >= 0) pos else JSONObject.NULL)
            // Raw session position: changes on seeks, so it forces a push.
            out.put("position_base_ms", ps.position)
            out.put("position_updated_at", System.currentTimeMillis())
            out.put("actions", actionsOf(ps.actions))
        }

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        out.put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volume_max", max)
            .put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
            .put("volume_fixed", audio.isVolumeFixed)

        updateArtwork(if (showMedia) md else null, appPkg)
        out.put("artwork_hash", artworkHash ?: JSONObject.NULL)
        return out
    }

    private fun MediaMetadata.text(key: String): String? =
        getText(key)?.toString()?.takeIf { it.isNotBlank() }

    private fun actionsOf(a: Long): JSONArray {
        val list = JSONArray()
        if (a and PlaybackState.ACTION_PLAY != 0L) list.put("play")
        if (a and PlaybackState.ACTION_PAUSE != 0L) list.put("pause")
        if (a and PlaybackState.ACTION_PLAY_PAUSE != 0L) list.put("play_pause")
        if (a and PlaybackState.ACTION_STOP != 0L) list.put("stop")
        if (a and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) list.put("next")
        if (a and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) list.put("previous")
        if (a and PlaybackState.ACTION_SEEK_TO != 0L) list.put("seek")
        return list
    }

    /** Artwork from the media session, falling back to the app's TV banner/icon. */
    private fun updateArtwork(md: MediaMetadata?, appPkg: String?) {
        val art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val key: Any? = art ?: appPkg
        if (key == lastArtworkKey) return
        lastArtworkKey = key

        val bitmap = art ?: appPkg?.let { appGraphic(it) }
        if (bitmap == null) {
            artworkHash = null
            artworkData = null
        } else {
            val bytes = encode(bitmap)
            artworkHash = MessageDigest.getInstance("MD5").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            artworkData = Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        artworkJustChanged = true
    }

    private fun appGraphic(pkg: String): Bitmap? = try {
        val d = packageManager.getApplicationBanner(pkg) ?: packageManager.getApplicationIcon(pkg)
        drawableToBitmap(d)
    } catch (_: Exception) {
        null
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 320
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 180
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }

    private fun encode(src: Bitmap): ByteArray {
        val maxSide = 480
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        val out = ByteArrayOutputStream()
        // PNG keeps transparent app banners clean; photos go out as JPEG.
        if (bmp.hasAlpha()) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        else bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return out.toByteArray()
    }

    private fun appLabel(pkg: String?): String? {
        if (pkg == null) return null
        if (pkg == foreground.homePackage) return "Home"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun appsMessage(): JSONObject {
        val pm = packageManager
        val seen = LinkedHashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val p = ri.activityInfo.packageName
                if (p == packageName || seen.containsKey(p)) continue
                seen[p] = ri.loadLabel(pm).toString()
            }
        }
        val arr = JSONArray()
        seen.entries.sortedBy { it.value.lowercase() }.forEach {
            arr.put(JSONObject().put("package", it.key).put("name", it.value))
        }
        return JSONObject().put("type", "apps").put("apps", arr)
    }

    // ---------------------------------------------------------------- commands

    /** Returns null on success or an error code. */
    private fun runCommand(msg: JSONObject): String? {
        val controller = media.primary()
        val tc = controller?.transportControls
        return when (val cmd = msg.optString("command")) {
            "play" -> transport(tc, KeyEvent.KEYCODE_MEDIA_PLAY) { it.play() }
            "pause" -> transport(tc, KeyEvent.KEYCODE_MEDIA_PAUSE) { it.pause() }
            "play_pause" -> {
                val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
                transport(tc, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { if (playing) it.pause() else it.play() }
            }
            "stop" -> transport(tc, KeyEvent.KEYCODE_MEDIA_STOP) { it.stop() }
            "next" -> transport(tc, KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }
            "previous" -> transport(tc, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }
            "seek" -> {
                if (tc == null) "no_session" else {
                    tc.seekTo(msg.optLong("position_ms", 0L)); null
                }
            }
            "volume_up" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); null }
            "volume_down" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); null }
            "volume_set" -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val level = (msg.optDouble("level", 0.0).coerceIn(0.0, 1.0) * max).toInt()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI); null
            }
            "mute" -> {
                val dir = when {
                    !msg.has("mute") -> AudioManager.ADJUST_TOGGLE_MUTE
                    msg.optBoolean("mute") -> AudioManager.ADJUST_MUTE
                    else -> AudioManager.ADJUST_UNMUTE
                }
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI); null
            }
            "launch" -> launch(msg.optString("package"))
            else -> {
                Log.w(TAG, "Unknown command $cmd")
                "unknown_command"
            }
        }
    }

    /** Use the session's transport controls, or fall back to a global media key. */
    private inline fun transport(
        tc: MediaController.TransportControls?,
        keyCode: Int,
        action: (MediaController.TransportControls) -> Unit,
    ): String? {
        if (tc != null) action(tc) else mediaKey(keyCode)
        return null
    }

    private fun mediaKey(code: Int): String? {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return null
    }

    private fun launch(pkg: String): String? {
        if (pkg.isBlank()) return "missing_package"
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
            ?: return "not_installed"
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Launching without overlay permission - Android may block it")
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed", e)
            "launch_blocked"
        }
    }

    // ---------------------------------------------------------------- for the UI

    fun resetPairing() {
        pairing.reset()
        server?.dropAll()
    }

    val connectedClients: Int
        get() = server?.clientCount ?: 0

    companion object {
        const val PORT = 8765
        const val PROTOCOL = 1
        private const val POLL_MS = 1_500L
        private const val TAG = "ARMediaBridge"

        @Volatile
        var instance: MediaListenerService? = null
            private set
    }
}
