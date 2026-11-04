package com.cosmod.launcherglass.util

import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/** Process wallpaper chroma once, before blur. No additive lift in shadows. */
object BackdropVibrancy {
    fun effect(level:Int):RenderEffect? =
        if(level<=0 || Build.VERSION.SDK_INT<33)null else Gpu.effect(level)

    @RequiresApi(33)
    private object Gpu {
        private val shader=RuntimeShader("""
            uniform shader image;
            uniform float amount;
            half4 main(float2 p) {
                half4 c=image.eval(p);
                float a=float(c.a);
                if(a<.0001)return c;
                float3 rgb=float3(c.rgb)/a;
                float high=max(rgb.r,max(rgb.g,rgb.b));
                float low=min(rgb.r,min(rgb.g,rgb.b));
                float saturation=(high-low)/max(high,.0001);
                float luma=dot(rgb,float3(.2126,.7152,.0722));
                float boost=amount*(1.0-saturation)*1.5;
                float3 vivid=clamp(luma+(rgb-luma)*(1.0+boost),0.0,1.0);
                // Preserve each pixel's value: color enhancement cannot turn
                // a dark red/blue or black into a luminous patch.
                vivid*=high/max(max(vivid.r,max(vivid.g,vivid.b)),.0001);
                return half4(half3(vivid*a),c.a);
            }
        """.trimIndent())
        fun effect(level:Int):RenderEffect {
            shader.setFloatUniform("amount",level.coerceIn(0,200)/100f)
            return RenderEffect.createRuntimeShaderEffect(shader,"image")
        }
    }

    fun applyCpu(source:Bitmap,level:Int):Bitmap {
        if(level<=0)return source
        val readable=if(source.config==Bitmap.Config.HARDWARE)source.copy(Bitmap.Config.ARGB_8888,false) ?: return source else source
        try {
            val pixels=IntArray(source.width*source.height)
            readable.getPixels(pixels,0,source.width,0,0,source.width,source.height)
            val amount=level.coerceIn(0,200)/100f
            for(i in pixels.indices){
                val c=pixels[i]
                val r=Color.red(c)/255f;val g=Color.green(c)/255f;val b=Color.blue(c)/255f
                val high=maxOf(r,g,b);val low=minOf(r,g,b)
                val luma=.2126f*r+.7152f*g+.0722f*b
                val boost=amount*(1f-(high-low)/maxOf(high,.0001f))*1.5f
                val vr=(luma+(r-luma)*(1f+boost)).coerceIn(0f,1f)
                val vg=(luma+(g-luma)*(1f+boost)).coerceIn(0f,1f)
                val vb=(luma+(b-luma)*(1f+boost)).coerceIn(0f,1f)
                val scale=high/maxOf(vr,vg,vb,.0001f)
                fun channel(v:Float)=(v*scale*255f).roundToInt().coerceIn(0,255)
                pixels[i]=Color.argb(Color.alpha(c),channel(vr),channel(vg),channel(vb))
            }
            return Bitmap.createBitmap(pixels,source.width,source.height,Bitmap.Config.ARGB_8888)
        }finally{if(readable!==source)readable.recycle()}
    }
}
