package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.graphics.Color
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import kotlin.math.roundToInt

/**
 * Komiho (2026-10-01): relocated from `exh.ui.metadata.adapters.MetadataUIUtil` when the exh
 * package was removed. Returns the color resolved from the given theme attribute.
 */
@ColorInt
fun Context.getResourceColor(@AttrRes resource: Int, alphaFactor: Float = 1f): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(resource))
    val color = typedArray.getColor(0, 0)
    typedArray.recycle()

    if (alphaFactor < 1f) {
        val alpha = (color.alpha * alphaFactor).roundToInt()
        return Color.argb(alpha, color.red, color.green, color.blue)
    }

    return color
}
