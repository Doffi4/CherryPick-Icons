package com.cosmod.launcherglass.util

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * One continuous, gently eased light-to-shade profile for plates and logos.
 *
 * The profile only spans a few alpha levels (about 16 white and 10 black at
 * the default strength), so an 8-bit target shows them as bands. Draw it with
 * [paint] (dithering on): the gradient is then dithered instead of stepped.
 */
object GlassVolume {
    private const val STOPS = 65

    fun shader(height: Float, opacity: Int): Shader {
        val strength = opacity.coerceIn(0, 100) / 100f
        val stops = FloatArray(STOPS) { it / (STOPS - 1f) }
        val colors = IntArray(stops.size) { index ->
            // Spread the transition over the full height with a much gentler
            // highlight and shadow. Plates and logos use the same profile.
            val position = stops[index]
            val light = .75f * (1f - 2f * position) + .25f * cos(Math.PI * position).toFloat()
            // Rounded, not truncated: truncation merged neighbouring stops
            // into flat steps of unequal length.
            if (light >= 0f) Color.argb((32f * strength * light).roundToInt(), 255, 255, 255)
            else Color.argb((-20f * strength * light).roundToInt(), 0, 0, 0)
        }
        return LinearGradient(0f, 0f, 0f, height.coerceAtLeast(1f), colors, stops, Shader.TileMode.CLAMP)
    }

    /** A dithered paint for the volume gradient. */
    fun paint(flags: Int = Paint.ANTI_ALIAS_FLAG): Paint = Paint(flags or Paint.DITHER_FLAG)
}
