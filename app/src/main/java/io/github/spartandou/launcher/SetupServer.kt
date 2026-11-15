package io.github.spartandou.launcher

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
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
        val address = Http.localIpv4Address() ?: return null
        val socket = ServerSocket().apply {
            reuseAddress = true
            // Only where the QR code points, not on every interface
            bind(InetSocketAddress(InetAddress.getByName(address), PORT))
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
        val request = Http.read(client, MAX_BODY_BYTES) ?: return
        val method = request.method
        val path = request.path

        fun form(): Map<String, List<String>>? = request.body?.let { parseForm(String(it, Charsets.UTF_8)) }

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
                // The API key goes with every request: in clear, only towards the local network
                if (host.startsWith("http://") && !Http.isLocal(host)) {
                    return respond(client, 200, connectPage(host, "Hors du réseau local, l'adresse doit commencer par https:// : en http://, la clé d'API circulerait en clair sur Internet."))
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

    private fun respond(client: Socket, status: Int, body: String) = Http.respond(client, status, body)

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
        private const val MAX_ATTEMPTS = 10
    }
}
