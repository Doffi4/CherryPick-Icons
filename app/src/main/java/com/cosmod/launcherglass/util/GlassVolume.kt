package com.cosmod.launcherglass.util

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import kotlin.math.cos

/** One continuous, gently eased light-to-shade profile for plates and logos. */
object GlassVolume {
    fun shader(height: Float, opacity: Int): Shader {
        val strength = opacity.coerceIn(0, 100) / 100f
        val stops = FloatArray(33) { it / 32f }
        val colors = IntArray(stops.size) { index ->
            // Spread the transition over the full height with a much gentler
            // highlight and shadow. Plates and logos use the same profile.
            val position = stops[index]
            val light = .75f * (1f - 2f * position) + .25f * cos(Math.PI * position).toFloat()
            if (light >= 0f) Color.argb((32f * strength * light).toInt(), 255, 255, 255)
            else Color.argb((-20f * strength * light).toInt(), 0, 0, 0)
        }
        return LinearGradient(0f, 0f, 0f, height.coerceAtLeast(1f), colors, stops, Shader.TileMode.CLAMP)
    }
}
