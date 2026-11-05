package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import java.util.WeakHashMap

/** Keep native clock/calendar artwork live on the GPU, without per-tick GPU readbacks. */
internal object DynamicIconForeground {
    private data class Entry(val node: RenderNode, val shader: RuntimeShader, var key: List<Any> = emptyList(), var source: Bitmap? = null)
    private val entries = WeakHashMap<Drawable, Entry>()
    private val plates = WeakHashMap<ProcessedIcon, ProcessedIcon>()
    fun clear() { entries.values.forEach { it.node.discardDisplayList() }; entries.clear(); plates.clear() }
    fun plate(parts: ProcessedIcon): ProcessedIcon = plates.getOrPut(parts) {
        ProcessedIcon(Bitmap.createBitmap(parts.glyphBitmap.width, parts.glyphBitmap.height, Bitmap.Config.ARGB_8888),
            parts.plateMaskBitmap, parts.bgColor)
    }

    /** [reuse]: an OEM decoration re-records the icon; show the current artwork node as is. */
    fun draw(icon: Drawable, canvas: Canvas, bounds: Rect, background: Int, source: Bitmap, reuse: Boolean = false, nativeDraw: (Canvas) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated) return false
        if (reuse) entries[icon]?.let { entry ->
            if (entry.node.hasDisplayList()) { canvas.drawRenderNode(entry.node); return true }
        }
        val entry = entries.getOrPut(icon) { Entry(RenderNode("CherryPick.nativeDynamicArtwork"), RuntimeShader(SOURCE)) }
        if (entry.source !== source) {
            val pixels = IntArray(source.width*source.height)
            source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
            var contrast = .1f
            for (pixel in pixels) if (Color.alpha(pixel) >= 180) {
                val r=(Color.red(pixel)-Color.red(background))/255f
                val g=(Color.green(pixel)-Color.green(background))/255f
                val b=(Color.blue(pixel)-Color.blue(background))/255f
                contrast=maxOf(contrast,kotlin.math.sqrt(r*r+g*g+b*b))
            }
            entry.shader.setFloatUniform("contrast",contrast)
            entry.source=source
        }
        val palette = PrefsHelper.iconPalette
        val key = listOf(background, palette, PrefsHelper.gradientOpacity, PrefsHelper.glyphStroke,
            PrefsHelper.glyphStrokeOpacity, PrefsHelper.glyphStrokeWidth, PrefsHelper.glyphStrokeBrightness,
            PrefsHelper.glyphShadow, PrefsHelper.shadowOpacity, bounds.width(), bounds.height())
        if (entry.key != key) {
            val shader = entry.shader
            shader.setFloatUniform("background", Color.red(background)/255f, Color.green(background)/255f, Color.blue(background)/255f)
            shader.setFloatUniform("foreground", Color.red(palette.foreground)/255f, Color.green(palette.foreground)/255f, Color.blue(palette.foreground)/255f)
            shader.setFloatUniform("recolor", if (palette.custom || palette.whiteGlyph) 1f else 0f)
            shader.setFloatUniform("height", bounds.height().toFloat())
            shader.setFloatUniform("volume", PrefsHelper.gradientOpacity / 100f)
            shader.setFloatUniform("stroke", if (PrefsHelper.glyphStroke) PrefsHelper.glyphStrokeOpacity/100f else 0f)
            shader.setFloatUniform("strokeWidth", .4f + PrefsHelper.glyphStrokeWidth*.018f)
            shader.setFloatUniform("strokeTone", PrefsHelper.glyphStrokeBrightness/100f)
            val foreground = RenderEffect.createRuntimeShaderEffect(shader, "content")
            val effect = if (PrefsHelper.glyphShadow && PrefsHelper.shadowOpacity > 0) {
                val shadow = RenderEffect.createColorFilterEffect(PorterDuffColorFilter(
                    Color.argb(175*PrefsHelper.shadowOpacity/100,0,0,0), PorterDuff.Mode.SRC_IN),
                    RenderEffect.createOffsetEffect(0f, maxOf(2f, bounds.height()*.016f),
                        RenderEffect.createBlurEffect(2f,2f,foreground,Shader.TileMode.CLAMP)))
                RenderEffect.createBlendModeEffect(shadow, foreground, BlendMode.SRC_OVER)
            } else foreground
            entry.node.setRenderEffect(effect)
            entry.key = key
        }
        entry.node.setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val recording = entry.node.beginRecording(bounds.width(), bounds.height())
        try {
            recording.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            nativeDraw(recording)
        } finally { entry.node.endRecording() }
        canvas.drawRenderNode(entry.node)
        return true
    }

    private const val SOURCE = """
        uniform shader content;
        uniform float3 background;
        uniform float3 foreground;
        uniform float recolor;
        uniform float height;
        uniform float volume;
        uniform float stroke;
        uniform float strokeWidth;
        uniform float strokeTone;
        uniform float contrast;
        half4 artwork(float2 p) {
            half4 pixel = content.eval(p);
            float3 rgb = float3(pixel.rgb) / max(float(pixel.a), .00001);
            float distance = length(rgb - background);
            // Unmix antialiased glyph edges from the solid native plate.
            float threshold=max(.063,contrast*.08);
            float coverage=clamp((distance-threshold)/max(contrast-threshold,.001),0.0,1.0);
            float3 original = clamp((rgb-background*(1.0-coverage))/max(coverage,.00001),0.0,1.0);
            float3 color = mix(original,foreground,recolor);
            color *= 1.0 - volume*.16*clamp(p.y/max(height,1.0),0.0,1.0);
            float alpha = coverage*float(pixel.a);
            return half4(color*alpha,alpha);
        }
        half4 main(float2 p) {
            half4 glyph = artwork(p);
            if (stroke > 0.0) {
                float around = max(max(float(artwork(p+float2(strokeWidth,0)).a),float(artwork(p-float2(strokeWidth,0)).a)),
                    max(float(artwork(p+float2(0,strokeWidth)).a),float(artwork(p-float2(0,strokeWidth)).a)));
                float alpha = max(around-float(glyph.a),0.0)*stroke;
                glyph = glyph + half4(float3(strokeTone)*alpha,alpha)*(1.0-glyph.a);
            }
            return glyph;
        }
    """
}
