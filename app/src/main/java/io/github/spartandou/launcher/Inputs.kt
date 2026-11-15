package io.github.spartandou.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager

/** Something the TV can show from elsewhere: an HDMI socket, a tuner. */
data class TvInput(val id: String, val label: String, val passthrough: Boolean) {
    /** Opens it in the system's TV app. */
    fun intent(): Intent = Intent(
        Intent.ACTION_VIEW,
        if (passthrough) TvContract.buildChannelUriForPassthroughInput(id) else TvContract.buildChannelsUriForInput(id),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

object Inputs {
    /** The inputs the system knows, by name. Empty on a device without any (most boxes). */
    fun load(context: Context): List<TvInput> = try {
        context.getSystemService(TvInputManager::class.java)?.tvInputList.orEmpty()
            .filter { !it.isHidden(context) }
            .map { TvInput(it.id, (it.loadCustomLabel(context) ?: it.loadLabel(context)).toString(), it.isPassthroughInput) }
            .sortedWith(compareBy(frenchOrder) { it.label })
    } catch (e: Exception) {
        emptyList()
    }

    fun icon(context: Context, id: String): Drawable? = try {
        context.getSystemService(TvInputManager::class.java)?.getTvInputInfo(id)?.loadIcon(context)
    } catch (e: Exception) {
        null
    }
}
