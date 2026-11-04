package com.cosmod.launcherglass.util

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.util.WeakHashMap

data class ShadowInfo(
    val bitmap: Bitmap,
    val offsetX: Float,
    val offsetY: Float
)

object GlyphShadowHelper {

    private val shadowCache = WeakHashMap<Bitmap, ShadowInfo>()
    private val shadowBlurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
    }

    @Synchronized
    fun getOrCreateShadow(glyph: Bitmap): ShadowInfo? {
        if (glyph.isRecycled) return null
        val cached = shadowCache[glyph]
        if (cached != null && !cached.bitmap.isRecycled) {
            return cached
        }

        try {
            val readable = if (glyph.config == Bitmap.Config.HARDWARE) {
                glyph.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else {
                glyph
            }

            if (readable.isRecycled) return null
            val offset = IntArray(2)
            val alphaMask = readable.extractAlpha(shadowBlurPaint, offset) ?: return null
            // Surface animation canvases must receive an ordinary color bitmap, not
            // an ALPHA_8 mask that the hardware renderer tints at playback time.
            val shadowBmp = try {
                Bitmap.createBitmap(alphaMask.width, alphaMask.height, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE
                    Canvas(this).drawBitmap(alphaMask, 0f, 0f, Paint().apply { color = Color.BLACK })
                }
            } finally {
                alphaMask.recycle()
                if (readable !== glyph) readable.recycle()
            }

            val info = ShadowInfo(
                bitmap = shadowBmp,
                offsetX = offset[0].toFloat(),
                offsetY = offset[1].toFloat()
            )
            shadowCache[glyph] = info
            return info
        } catch (t: Throwable) {
            GlassLogger.e("GlyphShadow", "Failed to extract alpha shadow", t)
            return null
        }
    }

    @Synchronized
    fun clearCache() {
        shadowCache.clear()
    }
}
