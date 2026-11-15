package io.github.spartandou.launcher

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The photo frame's API for Home Assistant, on the local network. Only listens while the frame is
 * on screen: nothing runs in the background, so Home Assistant sees the frame as unavailable the
 * rest of the time. Every request carries the key shown in the settings.
 *
 * GET  /api/state           what the frame shows
 * PUT  /api/settings        {"memories", "memories_only", "paused"}, any of them
 * POST /api/command         {"command": "next" | "previous" | "close"}
 * POST /api/notifications   {"message", "link", "duration", "tag"}
 * PUT  /api/sensors         {"sensors": [{"icon", "value", "unit"}]}
 */
class FrameServer(private val key: String, private val host: Host) {

    /** Called on the main thread. */
    interface Host {
        fun state(): JSONObject
        fun settings(changes: JSONObject)
        fun command(name: String): Boolean
        fun notification(json: JSONObject)
        fun sensors(json: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverSocket != null) return
        // Only on the local network's address, not on every interface
        val address = Http.localIpv4Address() ?: return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(address), PORT))
            }
        } catch (e: Exception) {
            // The port is taken while the other frame (screensaver or app) is still closing
            Log.w(TAG, "Frame API not started", e)
            return
        }
        serverSocket = socket
        thread(name = "frame-server", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { handle(it) }
                } catch (e: SocketException) {
                    // stop() closed the socket
                } catch (e: Exception) {
                    Log.w(TAG, "Frame request failed", e)
                }
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    private fun handle(client: Socket) {
        val request = Http.read(client, MAX_BODY_BYTES) ?: return
        val sent = request.headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
        if (!MessageDigest.isEqual(sent.toByteArray(), key.toByteArray())) {
            // One request at a time and a second lost for each wrong key: the key cannot be guessed
            Thread.sleep(WRONG_KEY_DELAY_MS)
            return Http.respond(client, 401)
        }
        val body = request.body ?: return Http.respond(client, 413)
        val json = try {
            if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            return Http.respond(client, 400)
        }
        val route = "${request.method} ${request.path}"
        // The frame's state and views belong to the main thread
        val answer = FutureTask<JSONObject?> {
            when (route) {
                "GET /api/state" -> host.state()
                "PUT /api/settings" -> { host.settings(json); host.state() }
                "POST /api/command" -> if (host.command(json.optString("command"))) DONE else null
                "POST /api/notifications" -> { host.notification(json); DONE }
                "PUT /api/sensors" -> { host.sensors(json); DONE }
                else -> NOT_FOUND
            }
        }
        main.post(answer)
        when (val result = answer.get(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) {
            null -> Http.respond(client, 400)
            NOT_FOUND -> Http.respond(client, 404)
            DONE -> Http.respond(client, 204)
            else -> Http.respond(client, 200, result.toString(), "application/json")
        }
    }

    companion object {
        private const val TAG = "FrameServer"
        // Next to the phone set-up page (47914)
        const val PORT = 47915
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val WRONG_KEY_DELAY_MS = 1_000L
        private const val ANSWER_TIMEOUT_S = 5L
        private val DONE = JSONObject()
        private val NOT_FOUND = JSONObject()
    }
}
