package com.cosmod.launcherglass.util

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter

/** Immutable snapshot shared by all icon render paths. Constructed on preference changes only. */
data class IconPalette(val custom: Boolean, val background: Int, val foreground: Int, val clear: Boolean = false,
    val tintHue: Int = 0, val tintSaturation: Int = 0, val whiteGlyph: Boolean = false) {
    val filter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
    companion object {
        fun isSystemDark(context: android.content.Context): Boolean =
            context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        fun from(mode: Int, hue: Int, saturation: Int, brightness: Int, dark: Boolean = true, clear: Boolean = false): IconPalette {
            val h = hue.coerceIn(0, 360).toFloat()
            val s = saturation.coerceIn(0, 100) / 100f
            val v = brightness.coerceIn(0, 100) / 100f
            if (clear) {
                val plate = if (dark) .06f + .52f * v else .44f + .54f * v
                val logo = .65f + .25f * v
                return IconPalette(mode == 1, Color.HSVToColor(floatArrayOf(0f,0f,plate)),
                    if (dark) Color.HSVToColor(floatArrayOf(0f,0f,logo)) else Color.WHITE,
                    mode == 1, whiteGlyph = !dark)
            }
            // Both layers share the selected hue. Let saturation affect both
            // layers directly; brightness supplies the theme-specific separation.
            val background: Int
            val foreground: Int
            if (dark) {
                background = Color.HSVToColor(floatArrayOf(h, s, .06f + .52f * v))
                foreground = Color.HSVToColor(floatArrayOf(h, s * .55f, .25f + .65f * v))
            } else {
                background = Color.HSVToColor(floatArrayOf(h, s, .44f + .54f * v))
                foreground = Color.WHITE
            }
            // Do not counteract these user-controlled tones with automatic
            // contrast correction: it made dark logos bright and light ones dark.
            return IconPalette(mode == 1, background, foreground, false, h.toInt(), saturation.coerceIn(0,100), !dark)
        }
    }
}
