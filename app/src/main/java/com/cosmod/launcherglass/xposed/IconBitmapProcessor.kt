package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import com.cosmod.launcherglass.util.GlassLogger
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.sqrt

data class ProcessedIcon(
    val glyphBitmap: Bitmap,
    val plateMaskBitmap: Bitmap,
    val bgColor: Int
)

object IconBitmapProcessor {

    private data class CachedIcon(val generation: Int, val icon: ProcessedIcon?,
        var palette: com.cosmod.launcherglass.util.IconPalette? = null, var colored: ProcessedIcon? = null)
    private val cache = WeakHashMap<Bitmap, CachedIcon>()
    private val layeredSources=WeakHashMap<Bitmap,Boolean>()
    @Synchronized fun markLayeredSource(bitmap:Bitmap){
        if(layeredSources.put(bitmap,true)!=true)cache.remove(bitmap)
    }
    @Synchronized fun clearTintCache() { cache.values.forEach { it.palette = null; it.colored = null } }
    private fun applyPalette(cached: CachedIcon): ProcessedIcon? {
        val icon = cached.icon ?: return null
        val palette = PrefsHelper.iconPalette
        if (!palette.custom && !palette.whiteGlyph) return icon
        if (cached.palette == palette) return cached.colored
        val glyph = Bitmap.createBitmap(icon.glyphBitmap.width, icon.glyphBitmap.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { colorFilter = palette.filter }
        android.graphics.Canvas(glyph).drawBitmap(icon.glyphBitmap, 0f, 0f, paint)
        return ProcessedIcon(glyph, icon.plateMaskBitmap, if(palette.custom)palette.background else icon.bgColor).also {
            cached.palette = palette; cached.colored = it
        }
    }
    private var cachedOpacity: Int = -1
    private var processLogCount = 0

    @Synchronized
    fun clearCache() {
        cache.clear()
        clearTintCache()
        com.cosmod.launcherglass.util.GlyphShadowHelper.clearCache()
        cachedOpacity = -1
    }

    @Synchronized
    fun nativeLayers(src: Bitmap, bgOpacity: Int): ProcessedIcon? {
        getOrProcess(src, bgOpacity)
        return cache[src]?.icon
    }

    @Synchronized
    fun getOrProcess(src: Bitmap, bgOpacity: Int): ProcessedIcon? {
        if (src.isRecycled) return null
        if (cachedOpacity != bgOpacity) {
            cache.clear()
            cachedOpacity = bgOpacity
        }

        val cached = cache[src]
        if (cached != null && cached.generation == src.generationId) {
            return applyPalette(cached)
        }

        val processed = processIcon(src, bgOpacity)
        val entry = CachedIcon(src.generationId, processed)
        cache[src] = entry
        return applyPalette(entry)
    }

    private fun processIcon(src: Bitmap, bgOpacity: Int): ProcessedIcon? {
        if (src.isRecycled) return null
        val width = src.width
        val height = src.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        val readable: Bitmap

        if (src.config == Bitmap.Config.HARDWARE) {
            try {
                val copy = src.copy(Bitmap.Config.ARGB_8888, false)
                if (copy == null || copy.isRecycled) {
                    GlassLogger.e("IconProcessor", "src.copy returned null or recycled bitmap")
                    return null
                }
                readable = copy
            } catch (t: Throwable) {
                GlassLogger.e("IconProcessor", "Failed to copy HARDWARE bitmap", t)
                return null
            }
        } else {
            readable = src
        }

        try {
            if (readable.isRecycled) return null
            readable.getPixels(pixels, 0, width, 0, 0, width, height)
        } catch (t: Throwable) {
            GlassLogger.e("IconProcessor", "Failed to getPixels", t)
            return null
        } finally {
            if (readable !== src && !readable.isRecycled) readable.recycle()
        }

        // 1. Find dominant opaque color (the background squircle plate)
        val buckets = IntArray(32768)
        var opaqueCount = 0
        for (p in pixels) {
            val a = (p ushr 24) and 0xff
            if (a < 180) continue
            opaqueCount++
            val r = (p ushr 16) and 0xff
            val g = (p ushr 8) and 0xff
            val b = p and 0xff
            val key = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
            buckets[key]++
        }
        // Only the three native layered-icon exceptions use a rim-derived
        // plate. Ordinary Flux processing remains the 0.1.64 algorithm.
        if(layeredSources[src]==true) {
            buckets.fill(0);opaqueCount=0
            for(y in height/4 until height*3/4) {
                val left=(0 until width).firstOrNull {pixels[y*width+it] ushr 24>=240} ?: continue
                val right=(width-1 downTo 0).firstOrNull {pixels[y*width+it] ushr 24>=240} ?: continue
                for(x in listOf((left+1).coerceAtMost(right),(right-1).coerceAtLeast(left))) {
                    val p=pixels[y*width+x]
                    val key=(((p ushr 19) and 31) shl 10) or (((p ushr 11) and 31) shl 5) or ((p ushr 3) and 31)
                    buckets[key]++;opaqueCount++
                }
            }
        }
        if (opaqueCount < if(layeredSources[src]==true)8 else 50) return null
        var maxBucketCount = 0
        var topKey = -1
        for (i in buckets.indices) {
            if (buckets[i] > maxBucketCount) {
                maxBucketCount = buckets[i]
                topKey = i
            }
        }
        val topRatio = maxBucketCount.toFloat() / opaqueCount
        if (topRatio < 0.10f || topKey < 0) return null
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var matchCount = 0
        for (p in pixels) {
            val a = (p ushr 24) and 0xff
            if (a < 180) continue
            val r = (p ushr 16) and 0xff
            val g = (p ushr 8) and 0xff
            val b = p and 0xff
            val key = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
            if (key == topKey) {
                sumR += r
                sumG += g
                sumB += b
                matchCount++
            }
        }
        if (matchCount == 0) return null
        val bgR = (sumR / matchCount).toInt()
        val bgG = (sumG / matchCount).toInt()
        val bgB = (sumB / matchCount).toInt()
        val bgColorInt = (0xFF shl 24) or (bgR shl 16) or (bgG shl 8) or bgB

        // 2. Find maximum contrast (glyph color)
        var maxDist = 0.0
        var fgR = 255
        var fgG = 255
        var fgB = 255

        for (p in pixels) {
            val a = (p ushr 24) and 0xff
            if (a < 180) continue
            val r = (p ushr 16) and 0xff
            val g = (p ushr 8) and 0xff
            val b = p and 0xff
            val dr = (r - bgR).toDouble()
            val dg = (g - bgG).toDouble()
            val db = (b - bgB).toDouble()
            val d = sqrt(dr * dr + dg * dg + db * db)
            if (d > maxDist) {
                maxDist = d
                fgR = r
                fgG = g
                fgB = b
            }
        }

        if (maxDist < 25.0) return null

        val bgThreshold = max(16.0, maxDist * 0.08)
        val glyphPixels = IntArray(pixels.size)
        val maskPixels = IntArray(pixels.size)

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xff
            if (a == 0) {
                glyphPixels[i] = 0
                maskPixels[i] = 0
                continue
            }

            // Squircle mask preserves edge anti-aliasing of the icon outline
            maskPixels[i] = (a shl 24) or 0x00FFFFFF

            val r = (p ushr 16) and 0xff
            val g = (p ushr 8) and 0xff
            val b = p and 0xff
            val dr = (r - bgR).toDouble()
            val dg = (g - bgG).toDouble()
            val db = (b - bgB).toDouble()
            val d = sqrt(dr * dr + dg * dg + db * db)

            if (d <= bgThreshold) {
                // Background plate pixel
                glyphPixels[i] = 0 // Transparent in glyph layer

            } else {
                // Glyph pixel or anti-aliased edge
                val t = ((d - bgThreshold) / (maxDist - bgThreshold)).coerceIn(0.0, 1.0)
                val glyphAlpha = (t * (a / 255.0) * 255).toInt().coerceIn(0, 255)
                val unblendedR = if (t > 0.15) ((r - (1 - t) * bgR) / t).toInt().coerceIn(0, 255) else fgR
                val unblendedG = if (t > 0.15) ((g - (1 - t) * bgG) / t).toInt().coerceIn(0, 255) else fgG
                val unblendedB = if (t > 0.15) ((b - (1 - t) * bgB) / t).toInt().coerceIn(0, 255) else fgB

                glyphPixels[i] = (glyphAlpha shl 24) or (unblendedR shl 16) or (unblendedG shl 8) or unblendedB

            }
        }

        try {
            val glyphBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            glyphBmp.setPixels(glyphPixels, 0, width, 0, 0, width, height)

            val maskBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            maskBmp.setPixels(maskPixels, 0, width, 0, 0, width, height)

            if (processLogCount < 10) {
                GlassLogger.i(
                    "IconProcessor",
                    "Processed frosted icon ${width}x$height: BG=($bgR,$bgG,$bgB) ratio=${String.format(java.util.Locale.ROOT, "%.2f", topRatio)}, opacity=$bgOpacity%"
                )
                processLogCount++
            }

            return ProcessedIcon(
                glyphBitmap = glyphBmp,
                plateMaskBitmap = maskBmp,
                bgColor = bgColorInt
            )
        } catch (t: Throwable) {
            GlassLogger.e("IconProcessor", "Failed to create output bitmaps", t)
            return null
        }
    }
}
