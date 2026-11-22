package za.co.arsmarthome.mediabridge

import android.os.Handler
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Local WebSocket server Home Assistant connects to.
 * All messages are handed to [onMessage] on the main thread so the media APIs
 * are only ever touched from one thread.
 */
class BridgeServer(
    port: Int,
    private val main: Handler,
    private val openHandler: (WebSocket) -> Unit,
    private val messageHandler: (WebSocket, String) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {

    private val authed: MutableSet<WebSocket> = ConcurrentHashMap.newKeySet()

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    fun markAuthed(conn: WebSocket) {
        authed.add(conn)
    }

    fun isAuthed(conn: WebSocket): Boolean = conn in authed

    fun sendToAuthed(text: String) {
        for (conn in authed) {
            if (conn.isOpen) {
                try {
                    conn.send(text)
                } catch (e: Exception) {
                    Log.w(TAG, "send failed", e)
                }
            }
        }
    }

    fun dropAll() {
        authed.clear()
        for (conn in connections) {
            conn.close(4001, "pairing reset")
        }
    }

    val clientCount: Int
        get() = authed.count { it.isOpen }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        main.post { openHandler(conn) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        authed.remove(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        main.post { messageHandler(conn, message) }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.i(TAG, "Bridge listening on port $port")
    }

    companion object {
        private const val TAG = "ARMediaBridge"
    }
}
