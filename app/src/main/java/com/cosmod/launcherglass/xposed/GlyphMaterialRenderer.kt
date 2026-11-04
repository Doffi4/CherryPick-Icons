package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.util.LruCache
import com.cosmod.launcherglass.util.GlassVolume
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Bake logo details once; floating surfaces and permanent icons share the same material. */
object GlyphMaterialRenderer {
    private data class Key(val bitmap: Bitmap, val generation: Int, val tint: ColorFilter?,
        val color: Int, val volume: Int, val outline: Int, val width: Int, val brightness: Int)
    private val cache = object : LruCache<Key, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val outputPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun clear() { cache.evictAll() }

    fun draw(canvas: Canvas, bounds: Rect, glyph: Bitmap, original: Paint) {
        val volume = PrefsHelper.gradientOpacity
        val outline = if (PrefsHelper.glyphStroke) PrefsHelper.glyphStrokeOpacity else 0
        if (volume == 0 && outline == 0) {
            canvas.drawBitmap(glyph, null, bounds, original)
            return
        }
        val key = Key(glyph, glyph.generationId, original.colorFilter, original.color or Color.BLACK, volume, outline,
            PrefsHelper.glyphStrokeWidth,PrefsHelper.glyphStrokeBrightness)
        val material = cache.get(key) ?: bake(glyph, original, volume, outline).also { cache.put(key, it) }
        // Tint is already baked. Only the live animation alpha belongs to the output paint.
        outputPaint.alpha = original.alpha
        canvas.drawBitmap(material, null, bounds, outputPaint)
    }

    private fun bake(glyph: Bitmap, original: Paint, volume: Int, outline: Int): Bitmap {
        val width = glyph.width
        val height = glyph.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        val target = Canvas(output)
        target.drawBitmap(glyph, 0f, 0f, Paint(original).apply { alpha = 255; xfermode = null })
        if (volume > 0) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = GlassVolume.shader(height.toFloat(), volume)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            }
            target.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        if (outline > 0) drawOutline(target, output, outline)
        return output
    }

    private fun drawOutline(target: Canvas, glyph: Bitmap, opacity: Int) {
        val width = glyph.width
        val height = glyph.height
        val pixels = IntArray(width * height)
        glyph.getPixels(pixels, 0, width, 0, 0, width, height)
        val edge = IntArray(pixels.size)
        val thickness = (min(width,height) * (.004f + .012f * PrefsHelper.glyphStrokeWidth / 100f)).coerceIn(.5f,8f)
        val innerRadius = floor(thickness).toInt()
        val radius = ceil(thickness).toInt()
        val fraction = thickness - innerRadius
        val brightness = PrefsHelper.glyphStrokeBrightness / 100f
        fun alphaAt(x: Int,y: Int): Int = if(x in 0 until width && y in 0 until height) pixels[y*width+x] ushr 24 else 0
        // Fractional inward erosion produces a wider antialiased rim without
        // swallowing small holes. Normals make the highlight follow each edge.
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            val alpha = pixels[index] ushr 24
            if (alpha == 0) continue
            var inner = alpha
            var outer = alpha
            var nx = 0f
            var ny = 0f
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val sample = alphaAt(x+dx*radius,y+dy*radius)
                outer = min(outer,sample)
                if(innerRadius>0)inner=min(inner,alphaAt(x+dx*innerRadius,y+dy*innerRadius))
                nx -= dx*sample*(if(dy==0)2f else 1f)
                ny -= dy*sample*(if(dx==0)2f else 1f)
            }
            val edgeAlpha = (alpha-inner*(1f-fraction)-outer*fraction).roundToInt().coerceIn(0,255)
            if(edgeAlpha==0)continue
            val length=sqrt(nx*nx+ny*ny)
            val light=if(length>.001f)((-nx-ny)/(1.41421356f*length)).coerceIn(-1f,1f) else 0f
            val highlight=(.025f+.85f*light.coerceAtLeast(0f)*light.coerceAtLeast(0f))*brightness
            val shadow=.04f+.18f*light.coerceAtMost(0f)*light.coerceAtMost(0f)
            fun shade(channel: Int): Int = (if(light>=0f)channel+(255-channel)*highlight else channel*(1f-shadow)).roundToInt().coerceIn(0,255)
            val color=pixels[index]
            edge[index]=Color.argb(edgeAlpha,shade(Color.red(color)),shade(Color.green(color)),shade(Color.blue(color)))
        }
        val ring = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(edge,0,width,0,0,width,height)
        }
        try {
            target.drawBitmap(ring, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = opacity.coerceIn(0,100) * 255 / 100 })
        } finally { ring.recycle() }
    }
}
