package io.github.spartandou.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** An Android TV, as opposed to a tablet or a phone. */
fun Context.isTv(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/**
 * Turns the home screen back on: versions up to v1.21 disabled it away from a TV, and that setting
 * outlives updates. Away from a TV, the remote buttons service stays off (useless there). Only
 * touches what differs: a change to the home component can make Android forget the default home.
 */
fun Context.restoreHomeScreen() {
    val pm = packageManager
    val home = ComponentName(this, HomeActivity::class.java)
    if (pm.getComponentEnabledSetting(home) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        pm.setComponentEnabledSetting(home, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
    val keys = ComponentName(this, KeyService::class.java)
    if (!isTv() && pm.getComponentEnabledSetting(keys) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        pm.setComponentEnabledSetting(keys, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
}

/** [category]: the launcher entry it was found by, the TV one or the tablet/phone one. */
class App(val packageName: String, val label: String, val info: ActivityInfo, val category: String) {
    fun intent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(category)
        .setComponent(ComponentName(packageName, info.name))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** The picture of a tile: a banner, or an icon when the app has none. */
class TileImage(val banner: Drawable?, val icon: Drawable?)

object Apps {
    /**
     * Launchable apps, sorted by name. Labels only: pictures are loaded per tile. On a TV, the ones
     * with a leanback entry; elsewhere (tablet) the usual launcher entries too, the TV one first
     * when an app has both, for its banner.
     */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        val categories = if (context.isTv()) listOf(Intent.CATEGORY_LEANBACK_LAUNCHER)
            else listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        return categories
            .flatMap { category ->
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).map { it.activityInfo to category }
            }
            .distinctBy { it.first.packageName }
            .map { (info, category) -> App(info.packageName, info.loadLabel(pm).toString(), info, category) }
            .sortedWith(compareBy(frenchOrder) { it.label })
    }

    /**
     * Renders the app's banner (or icon) at the exact tile size and drops the original: apps ship
     * banners up to several times the screen density, ~1 MB each, ~250 KB once at tile size.
     */
    fun tileImage(context: Context, app: App, width: Int): TileImage {
        val pm = context.packageManager
        val banner = app.info.loadBanner(pm) ?: app.info.applicationInfo.loadBanner(pm)
        if (banner != null) return TileImage(render(context, banner, width, Tiles.height(width)), null)
        val size = Tiles.dp(context, 44)
        return TileImage(null, render(context, app.info.loadIcon(pm), size, size))
    }

    private fun render(context: Context, drawable: Drawable, width: Int, height: Int): Drawable {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return BitmapDrawable(context.resources, bitmap)
    }
}

/** Alphabetical order as a French reader expects it: accents and capitals do not count. */
val frenchOrder: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.FRENCH).apply {
    strength = java.text.Collator.PRIMARY
}
