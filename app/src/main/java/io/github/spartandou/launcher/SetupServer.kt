package io.github.spartandou.launcher

import android.util.Log
import org.json.JSONObject
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
 * Step 1: server + API key, checked against Immich. Step 2: albums and memories. From there, two
 * more pages: the home screen's rows, and saving or restoring the whole set-up (without the keys).
 */
class SetupServer(
    private val prefs: Prefs,
    // Installed apps (package, name), for editing the rows from the phone
    private val apps: () -> List<Pair<String, String>>,
    private val onChanged: () -> Unit,
) {
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
            path == "/$token/rows" && method == "GET" -> respond(client, 200, rowsPage(null))
            path == "/$token/rows" && method == "POST" -> {
                val form = form() ?: return respond(client, 413, "Too large")
                val error = saveRows(form)
                if (error == null) onChanged()
                respond(client, 200, rowsPage(error ?: "Enregistré."))
            }
            path == "/$token/config" && method == "GET" -> respond(client, 200, configPage(null))
            path == "/$token/export" && method == "GET" ->
                Http.respond(client, 200, prefs.exportJson().toString(2), "application/json", download = "dous-home-launcher.json")
            path == "/$token/import" && method == "POST" -> {
                val form = form() ?: return respond(client, 413, "Too large")
                val message = try {
                    prefs.importJson(JSONObject(form["config"]?.firstOrNull().orEmpty()))
                    onChanged()
                    "Configuration restaurée. La clé d'API Immich n'en fait pas partie : reconnectez Immich si besoin."
                } catch (e: Exception) {
                    "Configuration refusée : ${e.message ?: "texte illisible"}. Rien n'a été modifié."
                }
                respond(client, 200, configPage(message))
            }
            path.startsWith("/$token") -> respond(client, 405, "Method not allowed")
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
            <p class="notice">Connecté à ${escape(prefs.serverUrl)}. <a href="/$token/connect">Changer de serveur ou de clé</a></p>
            $moreLinks
            """
        )
    }

    private val moreLinks: String
        get() = "<p class=\"notice\"><a href=\"/$token\">Photos</a> · <a href=\"/$token/rows\">Rangées de l'accueil</a> · " +
            "<a href=\"/$token/config\">Sauvegarde et restauration</a></p>"

    /**
     * The rows, edited in one form: each row's title, size and place, its apps in order (a number
     * each, emptied to take the app off) and the apps to add.
     */
    private fun rowsPage(message: String?): String {
        val rows = prefs.appRows.orEmpty()
        val installed = apps()
        val labels = installed.toMap()
        val sizes = listOf("très petite", "petite", "moyenne", "grande")
        val blocks = rows.mapIndexed { r, row ->
            val kind = when {
                row.continueWatching -> "<p class=\"notice\">Rangée « Reprendre la lecture » : son contenu vient des applis.</p>"
                row.inputs -> "<p class=\"notice\">Rangée « Entrées » : son contenu vient du système.</p>"
                else -> {
                    val current = row.apps.mapIndexed { i, pkg ->
                        "<label class=\"check\"><input class=\"num\" type=\"number\" min=\"1\" name=\"order_${r}_${escape(pkg)}\" value=\"${i + 1}\">" +
                            "${escape(labels[pkg] ?: pkg)}</label>"
                    }.joinToString("")
                    val others = installed.filter { it.first !in row.apps }.joinToString("") { (pkg, label) ->
                        "<label class=\"check\"><input type=\"checkbox\" name=\"add_$r\" value=\"${escape(pkg)}\">${escape(label)}</label>"
                    }
                    "$current<details><summary>Ajouter des applis</summary>$others</details>"
                }
            }
            val options = sizes.mapIndexed { i, name -> "<option value=\"$i\"${if (i == row.size) " selected" else ""}>$name</option>" }.joinToString("")
            """
            <fieldset>
              <label>Titre<input type="text" name="title_$r" value="${escape(row.title)}" placeholder="Vide : pas de titre"></label>
              <label>Taille des vignettes<select name="size_$r">$options</select></label>
              <label>Place parmi les rangées<input class="num" type="number" min="1" name="pos_$r" value="${r + 1}"></label>
              $kind
              <label class="check"><input type="checkbox" name="del_$r" value="1">Supprimer cette rangée</label>
            </fieldset>
            """
        }.joinToString("")
        return page(
            """
            <h1>Rangées de l'accueil</h1>
            ${message?.let { "<p class=\"message\">${escape(it)}</p>" } ?: ""}
            <p class="notice">Le numéro devant une appli est sa place dans la rangée ; videz-le pour la retirer.</p>
            <form method="post" action="/$token/rows">
              <input type="hidden" name="count" value="${rows.size}">
              $blocks
              <label>Nouvelle rangée<input type="text" name="new_title" placeholder="Son titre (vide : aucune nouvelle rangée)"></label>
              <button type="submit">Enregistrer</button>
            </form>
            $moreLinks
            """
        )
    }

    /** Applies the rows form. Returns what went wrong, or null. */
    private fun saveRows(form: Map<String, List<String>>): String? {
        val rows = prefs.appRows.orEmpty()
        fun field(name: String) = form[name]?.firstOrNull()?.trim()
        // The form numbers the rows as they were when the page was shown
        if (field("count")?.toIntOrNull() != rows.size) return "Les rangées ont changé entre-temps sur la TV : rien n'a été enregistré, voici leur état actuel."

        val added = rows.indices.associateWith { r -> if (rows[r].isApps) form["add_$r"].orEmpty() else emptyList() }
        // An app sits on one row only: added to one, it leaves the others
        val moved = added.values.flatten().toSet()
        val kept = rows.mapIndexedNotNull { r, row ->
            if (form["del_$r"] != null) return@mapIndexedNotNull null
            field("title_$r")?.let { row.title = it }
            field("size_$r")?.toIntOrNull()?.let { row.size = it.coerceIn(Prefs.TILE_WIDTHS.indices) }
            if (row.isApps) {
                val ordered = row.apps.filter { it !in moved }
                    .mapNotNull { pkg -> field("order_${r}_$pkg")?.toIntOrNull()?.let { it to pkg } }
                    .sortedBy { it.first }.map { it.second }
                row.apps.clear()
                row.apps += ordered + added[r].orEmpty().distinct()
            }
            (field("pos_$r")?.toIntOrNull() ?: (r + 1)) to row
        }.sortedBy { it.first }.map { it.second }.toMutableList()
        field("new_title")?.takeIf { it.isNotEmpty() }?.let { kept += AppRow(it, 1, mutableListOf()) }
        if (kept.count { it.pinned } > 1) kept.forEach { it.pinned = false }
        prefs.appRows = kept
        return null
    }

    private fun configPage(message: String?) = page(
        """
        <h1>Sauvegarde et restauration</h1>
        ${message?.let { "<p class=\"message\">${escape(it)}</p>" } ?: ""}
        <p>La configuration regroupe les rangées, la mise en page, les albums choisis et les touches de la télécommande. Elle ne contient ni la clé d'API Immich ni la clé du cadre photo.</p>
        <p><a class="button" href="/$token/export">Télécharger la configuration</a></p>
        <h2>Restaurer</h2>
        <form method="post" action="/$token/import">
          <label>Fichier exporté<input type="file" accept="application/json,.json" onchange="this.files[0].text().then(t => document.getElementById('config').value = t)"></label>
          <label>ou son contenu<textarea id="config" name="config" rows="8" required></textarea></label>
          <button type="submit">Restaurer</button>
        </form>
        $moreLinks
        """
    )

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
          button, a.button { display: block; margin-top: 24px; width: 100%; box-sizing: border-box; padding: 14px; font-size: 16px; text-align: center; text-decoration: none; border: 0; border-radius: 10px; background: #4250af; color: #fff; }
          a { color: #9fb3ff; }
          select, textarea, input[type=number], input[type=file] { display: block; width: 100%; box-sizing: border-box; margin-top: 6px; padding: 12px; font-size: 16px; color: #eee; background: #1b1e25; border: 1px solid #333; border-radius: 10px; }
          input.num { width: 72px; margin: 0; padding: 8px; }
          fieldset { margin: 20px 0; padding: 4px 14px 14px; border: 1px solid #333; border-radius: 12px; }
          details { margin-top: 12px; } summary { color: #9fb3ff; }
          code { color: #9fb3ff; }
          .error { color: #ff8a80; }
          .message { color: #ffd27f; }
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
