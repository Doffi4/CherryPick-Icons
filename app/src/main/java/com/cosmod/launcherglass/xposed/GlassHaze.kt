package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.util.LruCache
import kotlin.math.exp
import kotlin.math.roundToInt

/** Experimental optical veil, independently switchable from color quantization. */
internal object GlassHaze {
    private data class Size(val width:Int,val height:Int)
    private val effects=object:LruCache<Size,RenderEffect>(24){}
    @androidx.annotation.RequiresApi(33)
    @Synchronized private fun base(width:Int,height:Int):RenderEffect {
        val key=Size(width,height)
        return effects[key] ?: run {
            val shader=RuntimeShader(SOURCE)
            shader.setFloatUniform("size",width.toFloat(),height.toFloat())
            RenderEffect.createRuntimeShaderEffect(shader,"image").also {effects.put(key,it)}
        }
    }
    fun effect(input:RenderEffect?,width:Int,height:Int):RenderEffect? {
        if(android.os.Build.VERSION.SDK_INT<33 || !PrefsHelper.hazeEnabled)return input
        val haze=base(width,height)
        return if(input==null)haze else RenderEffect.createChainEffect(haze,input)
    }
    fun applyCpu(source:Bitmap):Bitmap {
        if(!PrefsHelper.hazeEnabled)return source
        val readable=if(source.config==Bitmap.Config.HARDWARE)source.copy(Bitmap.Config.ARGB_8888,false) ?: return source else source
        try {
            val pixels=IntArray(source.width*source.height)
            readable.getPixels(pixels,0,source.width,0,0,source.width,source.height)
            for(y in 0 until source.height)for(x in 0 until source.width){
                val i=y*source.width+x;val c=pixels[i]
                if(Color.alpha(c)==0)continue
                val px=(x+.5f)/source.width;val py=(y+.5f)/source.height
                val dx=px-.18f;val dy=(py-.08f)*.85f
                val veil=.025f+.09f*exp(-(dx*dx+dy*dy)*2.4f)
                val r=Color.red(c)/255f;val g=Color.green(c)/255f;val b=Color.blue(c)/255f
                val luma=.2126f*r+.7152f*g+.0722f*b
                fun channel(v:Float,tint:Float):Int {
                    val softened=((v*.88f+luma*.12f)-.5f)*.92f+.5f
                    return (((softened*(1f-veil)+tint*veil)*(1f-.06f*py)).coerceIn(0f,1f)*255f).roundToInt()
                }
                pixels[i]=Color.argb(Color.alpha(c),channel(r,.60f),channel(g,.67f),channel(b,.70f))
            }
            return Bitmap.createBitmap(pixels,source.width,source.height,Bitmap.Config.ARGB_8888)
        }finally{if(readable!==source)readable.recycle()}
    }
    private const val SOURCE="""
        uniform shader image;
        uniform float2 size;
        half4 main(float2 xy) {
            half4 c=image.eval(xy);
            float a=float(c.a);
            if(a<=.00001)return c;
            float3 rgb=clamp(float3(c.rgb)/a,0.0,1.0);
            float luma=dot(rgb,float3(.2126,.7152,.0722));
            rgb=(mix(rgb,float3(luma),.12)-.5)*.92+.5;
            float2 uv=xy/size;
            float2 delta=(uv-float2(.18,.08))*float2(1.0,.85);
            float veil=.025+.09*exp(-dot(delta,delta)*2.4);
            rgb=mix(rgb,float3(.60,.67,.70),veil)*(1.0-.06*uv.y);
            return half4(half3(clamp(rgb,0.0,1.0)*a),c.a);
        }
    """
}

/** Optical finish sits after lens/colorization and before the plate overlay. */
internal object MaterialFinish {
    fun key()=(if(PrefsHelper.hazeEnabled)32 else 0) or
        (if(PrefsHelper.colorDepthStage==ColorDepthEffect.AFTER_REFRACTION)PrefsHelper.colorDepthBits else 24)
    fun needed()=PrefsHelper.hazeEnabled ||
        (PrefsHelper.colorDepthBits<24 && PrefsHelper.colorDepthStage==ColorDepthEffect.AFTER_REFRACTION)
    fun effect(input:RenderEffect?,width:Int,height:Int)=
        ColorDepthEffect.at(GlassHaze.effect(input,width,height),ColorDepthEffect.AFTER_REFRACTION)
    fun applyCpu(source:Bitmap):Bitmap {
        val haze=GlassHaze.applyCpu(source)
        val result=ColorDepthEffect.atCpu(haze,ColorDepthEffect.AFTER_REFRACTION)
        if(haze!==source && result!==haze)haze.recycle()
        return result
    }
}
