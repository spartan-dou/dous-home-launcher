package io.github.spartandou.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update from the GitHub releases: each release is tagged v1.<n>, n being the app's versionCode,
 * and carries the signed APK. A newer one is downloaded and handed to the system installer.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/spartan-dou/dous-home-launcher/releases/latest"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val ACTION = "io.github.spartandou.launcher.UPDATE_RESULT"
    private val worker = Executors.newSingleThreadExecutor()

    sealed class Result {
        class Available(val name: String) : Result()
        object UpToDate : Result()
        object Failed : Result()
    }

    /** A release tag and the APK to download. */
    class Release(val code: Int, val name: String, val apkUrl: String)

    /** "v1.42" gives 42; null for any other tag. */
    fun parseTag(tag: String): Int? = Regex("""^v?\d+\.(\d+)$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun parseRelease(json: String): Release? {
        val release = JSONObject(json)
        val tag = release.optString("tag_name")
        val code = parseTag(tag) ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) {
                return Release(code, tag, asset.getString("browser_download_url"))
            }
        }
        return null
    }

    fun installedName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    @Suppress("DEPRECATION")
    private fun installedCode(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Looks at the latest release; installs it when newer. [force]: ignores the once-a-day limit. */
    fun check(context: Context, force: Boolean, done: (Result) -> Unit) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!force && System.currentTimeMillis() - prefs.lastUpdateCheck < DAY_MS) return
        worker.execute {
            val result = try {
                val release = parseRelease(get(LATEST).readBytes().toString(Charsets.UTF_8))
                prefs.lastUpdateCheck = System.currentTimeMillis()
                when {
                    release == null -> Result.Failed
                    release.code <= installedCode(app) -> Result.UpToDate
                    else -> { install(app, release); Result.Available(release.name) }
                }
            } catch (e: Exception) {
                Result.Failed
            }
            done(result)
        }
    }

    private fun get(url: String): java.io.InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
        return connection.inputStream
    }

    private fun install(context: Context, release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Android 12+: no confirmation when the system allows it (an update of an app it installed)
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            get(release.apkUrl).use { input ->
                session.openWrite("update.apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ACTION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}
