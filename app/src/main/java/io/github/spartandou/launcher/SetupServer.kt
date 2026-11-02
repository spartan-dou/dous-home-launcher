package io.github.spartandou.launcher

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Minimal HTTP server for setting up Immich from a phone on the same network (from the Immich TV
 * fork's local sign-in). Only runs while the settings screen is visible, and only answers on a
 * random, unguessable path, so the API key never goes through a third-party server.
 *
 * Step 1: server + API key, checked against Immich. Step 2: albums and memories.
 */
class SetupServer(private val prefs: Prefs, private val onChanged: () -> Unit) {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var attempts = 0

    /** The URL for the QR code, or null without a local network. */
    fun start(): String? {
        val address = localIpv4Address() ?: return null
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        serverSocket = socket
        thread(name = "setup-server", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { handle(it) }
                } catch (e: SocketException) {
                    // stop() closed the socket
                } catch (e: Exception) {
                    Log.w(TAG, "Setup request failed", e)
                }
            }
        }
        return "http://$address:$PORT/$token"
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 } }
            .associate { (name, value) -> name.trim().lowercase() to value.trim() }
        val (method, target) = requestLine.split(" ").let { it.getOrNull(0) to it.getOrNull(1).orEmpty() }
        val path = target.substringBefore('?')

        fun form(): Map<String, List<String>>? {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 0..MAX_BODY_BYTES) return null
            return parseForm(String(readBytes(input, length), Charsets.UTF_8))
        }

        when {
            // Already connected: straight to the photos and weather choices
            path == "/$token" && method == "GET" -> {
                val albums = if (prefs.apiKey.isBlank()) null else try {
                    ImmichClient(prefs.serverUrl, prefs.apiKey).albums()
                } catch (e: Exception) {
                    null
                }
                respond(client, 200, if (albums != null) albumsPage(albums, null) else connectPage(prefs.serverUrl, null))
            }
            path == "/$token/connect" && method == "GET" -> respond(client, 200, connectPage(prefs.serverUrl, null))
            path == "/$token" && method == "POST" -> {
                if (++attempts > MAX_ATTEMPTS) { respond(client, 429, page("<p>Trop d'essais, rouvrez les réglages sur la TV.</p>")); stop(); return }
                val form = form() ?: return respond(client, 413, "Too large")
                val host = form["host"]?.firstOrNull().orEmpty().trim().trimEnd('/')
                val key = form["key"]?.firstOrNull().orEmpty().trim()
                if (!host.startsWith("http://") && !host.startsWith("https://")) {
                    return respond(client, 200, connectPage(host, "L'adresse doit commencer par https:// ou http://"))
                }
                val albums = try {
                    ImmichClient(host, key).albums()
                } catch (e: Exception) {
                    return respond(client, 200, connectPage(host, "Connexion impossible : ${e.message ?: e.javaClass.simpleName}"))
                }
                prefs.serverUrl = host
                prefs.apiKey = key
                onChanged()
                respond(client, 200, albumsPage(albums, null))
            }
            path == "/$token/albums" && method == "POST" -> {
                val form = form() ?: return respond(client, 413, "Too large")
                // The town is checked first: an unknown one keeps the page open with the choices made
                val city = form["city"]?.firstOrNull().orEmpty().trim()
                val known = city.isEmpty() || try { Weather.geocode(city) != null } catch (e: Exception) { false }
                prefs.albumIds = form["album"].orEmpty().toSet()
                prefs.memories = form["memories"] != null
                if (!known) {
                    onChanged()
                    val albums = try { ImmichClient(prefs.serverUrl, prefs.apiKey).albums() } catch (e: Exception) { emptyList() }
                    return respond(client, 200, albumsPage(albums, "Ville introuvable : « $city ». Essayez avec le nom de la commune seul."))
                }
                prefs.weatherCity = city
                onChanged()
                respond(client, 200, page("<h1>C'est prêt</h1><p>Les photos arrivent sur la TV. Vous pouvez fermer cette page.</p>"))
            }
            path == "/$token" || path == "/$token/albums" || path == "/$token/connect" -> respond(client, 405, "Method not allowed")
            else -> respond(client, 404, "Not found")
        }
    }

    private fun connectPage(host: String, error: String?) = page(
        """
        <h1>Dou's Home Launcher</h1>
        ${error?.let { "<p class=\"error\">${escape(it)}</p>" } ?: ""}
        <form method="post" action="/$token">
          <label>Adresse du serveur Immich<input name="host" type="url" required value="${escape(host)}" placeholder="https://immich.example.com"></label>
          <label>Clé d'API<input name="key" type="password" required autocomplete="off"></label>
          <button type="submit">Se connecter</button>
        </form>
        <p class="notice">Créez la clé dans Immich : <b>Paramètres du compte → Clés API → Nouvelle clé</b>.
        Ne cochez que <code>album.read</code>, <code>asset.read</code>, <code>asset.view</code> et <code>memory.read</code> :
        la TV pourra seulement regarder les photos.</p>
        <p class="notice">Cette page est servie par la TV, sur votre réseau local uniquement.</p>
        """
    )

    private fun albumsPage(albums: List<Album>, error: String?): String {
        val selected = prefs.albumIds
        val items = albums.joinToString("") { album ->
            val checked = if (album.id in selected) "checked" else ""
            "<label class=\"check\"><input type=\"checkbox\" name=\"album\" value=\"${escape(album.id)}\" $checked>" +
                "${escape(album.name)} <span>${album.count}</span></label>"
        }
        val memories = if (prefs.memories) "checked" else ""
        return page(
            """
            <h1>Photos à afficher</h1>
            ${error?.let { "<p class=\"error\">${escape(it)}</p>" } ?: ""}
            <form method="post" action="/$token/albums">
              <label class="check strong"><input type="checkbox" name="memories" value="1" $memories>Souvenirs du jour (il y a X ans), mélangés aux albums</label>
              <h2>Albums</h2>
              ${items.ifEmpty { "<p>Aucun album sur ce compte.</p>" }}
              <h2>Météo</h2>
              <label>Ville<input name="city" type="text" value="${escape(prefs.weatherCity)}" placeholder="Vide : position de la box (adresse IP)"></label>
              <button type="submit">Enregistrer</button>
            </form>
            <p class="notice">Connecté à ${escape(prefs.serverUrl)}. <a href="/$token/connect" style="color:#9fb3ff">Changer de serveur ou de clé</a></p>
            """
        )
    }

    private fun page(body: String): String = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Dou's Home Launcher</title>
        <style>
          body { font-family: system-ui, sans-serif; max-width: 460px; margin: 0 auto; padding: 24px 16px; background: #0f1115; color: #eee; }
          h2 { font-size: 15px; margin-top: 28px; color: #aaa; text-transform: uppercase; letter-spacing: .05em; }
          label { display: block; margin: 16px 0 4px; font-weight: 600; }
          input[type=url], input[type=password], input[type=text] { display: block; width: 100%; box-sizing: border-box; margin-top: 6px; padding: 12px; font-size: 16px; color: #eee; background: #1b1e25; border: 1px solid #333; border-radius: 10px; }
          label.check { display: flex; gap: 10px; align-items: center; font-weight: 400; margin: 10px 0; }
          label.check span { margin-left: auto; color: #888; font-size: 13px; }
          label.strong { font-weight: 600; }
          input[type=checkbox] { width: 20px; height: 20px; }
          button { margin-top: 24px; width: 100%; padding: 14px; font-size: 16px; border: 0; border-radius: 10px; background: #4250af; color: #fff; }
          code { color: #9fb3ff; }
          .error { color: #ff8a80; }
          .notice { margin-top: 24px; font-size: 13px; color: #999; line-height: 1.5; }
        </style></head><body>$body</body></html>
    """.trimIndent()

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${STATUS_TEXT[status] ?: "OK"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (byte == '\n'.code) return buffer.toString("ISO-8859-1").trimEnd('\r')
            if (buffer.size() >= MAX_LINE_BYTES) return null
            buffer.write(byte)
        }
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count == -1) break
            read += count
        }
        return bytes.copyOf(read)
    }

    // Repeated keys (checkboxes) are kept as lists
    private fun parseForm(body: String): Map<String, List<String>> = body.split("&")
        .mapNotNull { it.split("=", limit = 2).takeIf { pair -> pair.size == 2 } }
        .groupBy({ URLDecoder.decode(it[0], "UTF-8") }, { URLDecoder.decode(it[1], "UTF-8") })

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    companion object {
        private const val TAG = "SetupServer"
        // Next to the Immich TV sign-in port (47913)
        const val PORT = 47914
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_ATTEMPTS = 10
        private val STATUS_TEXT = mapOf(200 to "OK", 404 to "Not Found", 405 to "Method Not Allowed", 413 to "Payload Too Large", 429 to "Too Many Requests")

        // Ethernet or Wi-Fi address the phone can reach on the local network
        fun localIpv4Address(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }
}
