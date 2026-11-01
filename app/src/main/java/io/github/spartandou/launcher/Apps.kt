package io.github.spartandou.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

class App(val packageName: String, val label: String, val info: ActivityInfo) {
    fun intent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setComponent(ComponentName(packageName, info.name))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** The picture of a tile: a banner, or an icon when the app has none. */
class TileImage(val banner: Drawable?, val icon: Drawable?)

object Apps {
    /** TV apps (the ones with a leanback entry), sorted by name. Labels only: pictures are loaded per tile. */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .distinctBy { it.packageName }
            .map { App(it.packageName, it.loadLabel(pm).toString(), it) }
            .sortedBy { it.label.lowercase() }
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
