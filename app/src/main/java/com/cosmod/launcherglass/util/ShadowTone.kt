package com.cosmod.launcherglass.util

import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/** Applied once to the shared wallpaper texture, never once per icon. */
object ShadowTone {
    val gpuSupported get() = Build.VERSION.SDK_INT >= 33
    fun effect(input: RenderEffect?, level: Int): RenderEffect? {
        if (level == 50 || !gpuSupported) return input
        return Gpu.effect(input, level)
    }
    @RequiresApi(33)
    private object Gpu {
        private val shader = RuntimeShader("""
            uniform shader image;
            uniform float strength;
            half4 main(float2 p) {
                half4 c = image.eval(p);
                float a = float(c.a);
                if (a < 0.0001) return c;
                float3 rgb = float3(c.rgb) / a;
                if (strength < 0.0) {
                    rgb *= 1.0 + strength;
                } else {
                    float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                    float shadow = 1.0 - smoothstep(0.06, 0.35, luma);
                    rgb += (float3(1.0) - rgb) * strength * shadow * 0.65;
                }
                return half4(half3(clamp(rgb, 0.0, 1.0) * a), c.a);
            }
        """.trimIndent())
        fun effect(input: RenderEffect?, level: Int): RenderEffect {
            shader.setFloatUniform("strength", (level.coerceIn(0,100)-50)/50f)
            val tone = RenderEffect.createRuntimeShaderEffect(shader,"image")
            return if(input == null) tone else RenderEffect.createChainEffect(tone,input)
        }
    }
    fun applyCpu(source: Bitmap, level: Int): Bitmap {
        if(level == 50) return source
        val readable = if(source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888,false)
            ?: return source else source
        try {
            val pixels=IntArray(source.width*source.height)
            readable.getPixels(pixels,0,source.width,0,0,source.width,source.height)
            val strength=(level.coerceIn(0,100)-50)/50f
            for(i in pixels.indices) {
                val c=pixels[i]
                val r=Color.red(c)/255f;val g=Color.green(c)/255f;val b=Color.blue(c)/255f
                val luma=.2126f*r+.7152f*g+.0722f*b
                val t=((luma-.06f)/.29f).coerceIn(0f,1f)
                val lift=strength*(1f-t*t*(3f-2f*t))*.65f
                fun channel(v:Float)=((if(strength<0)v*(1f+strength) else v+(1f-v)*lift)*255f).roundToInt().coerceIn(0,255)
                pixels[i]=Color.argb(Color.alpha(c),channel(r),channel(g),channel(b))
            }
            return Bitmap.createBitmap(pixels,source.width,source.height,Bitmap.Config.ARGB_8888)
        } finally { if(readable !== source)readable.recycle() }
    }
}
