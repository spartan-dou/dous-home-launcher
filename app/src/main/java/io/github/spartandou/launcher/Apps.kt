package io.github.spartandou.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

class App(val packageName: String, val label: String, val component: ComponentName, val banner: Drawable?, val icon: Drawable) {
    fun intent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

object Apps {
    /** TV apps (the ones with a leanback entry), sorted by name, without this launcher's own home. */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .distinctBy { it.packageName }
            .map { info ->
                App(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    component = ComponentName(info.packageName, info.name),
                    banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm),
                    icon = info.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
