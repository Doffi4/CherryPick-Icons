package com.cosmod.launcherglass.xposed

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/** Total RGB depth, not depth per channel. Alpha remains continuous. */
internal object ColorDepthEffect {
    const val BEFORE_BLUR=0
    const val AFTER_BLUR=1
    const val AFTER_REFRACTION=2
    const val FINISHED_PLATE=3
    val finalPlate get()=PrefsHelper.colorDepthBits<24 && PrefsHelper.colorDepthStage==FINISHED_PLATE
    fun at(input:RenderEffect?,stage:Int):RenderEffect? {
        if(android.os.Build.VERSION.SDK_INT<33 || PrefsHelper.colorDepthBits>=24 || PrefsHelper.colorDepthStage!=stage)return input
        val depth=effect(PrefsHelper.colorDepthBits)
        return if(input==null)depth else RenderEffect.createChainEffect(depth,input)
    }
    fun atCpu(source:Bitmap,stage:Int):Bitmap =
        if(PrefsHelper.colorDepthStage==stage)applyCpu(source,PrefsHelper.colorDepthBits)else source
    fun applyCpu(source:Bitmap,bits:Int):Bitmap {
        val depth=bits.coerceIn(8,24)
        if(depth==24)return source
        val readable=if(source.config==Bitmap.Config.HARDWARE)source.copy(Bitmap.Config.ARGB_8888,false) ?: return source else source
        try {
            val levels=intArrayOf((1 shl ((depth+1)/3))-1,(1 shl ((depth+2)/3))-1,(1 shl (depth/3))-1)
            val pixels=IntArray(source.width*source.height)
            readable.getPixels(pixels,0,source.width,0,0,source.width,source.height)
            fun channel(value:Int,index:Int)=((value*levels[index]/255f).roundToInt()*255f/levels[index]).roundToInt().coerceIn(0,255)
            for(i in pixels.indices){val c=pixels[i];pixels[i]=Color.argb(Color.alpha(c),channel(Color.red(c),0),channel(Color.green(c),1),channel(Color.blue(c),2))}
            return Bitmap.createBitmap(pixels,source.width,source.height,Bitmap.Config.ARGB_8888)
        }finally{if(readable!==source)readable.recycle()}
    }
    private val effects=HashMap<Int,RenderEffect>()
    @androidx.annotation.RequiresApi(33)
    @Synchronized fun effect(bits:Int):RenderEffect=effects.getOrPut(bits.coerceIn(8,24)) {
        val depth=bits.coerceIn(8,24)
        val shader=RuntimeShader(SOURCE)
        shader.setFloatUniform("levels",((1 shl ((depth+1)/3))-1).toFloat(),
            ((1 shl ((depth+2)/3))-1).toFloat(),((1 shl (depth/3))-1).toFloat())
        RenderEffect.createRuntimeShaderEffect(shader,"image")
    }
    private const val SOURCE="""
        uniform shader image;
        uniform float3 levels;
        half4 main(float2 xy) {
            half4 c=image.eval(xy);
            float a=float(c.a);
            if(a<=0.00001)return c;
            float3 rgb=clamp(float3(c.rgb)/a,0.0,1.0);
            rgb=floor(rgb*levels+0.5)/levels;
            return half4(half3(rgb*a),c.a);
        }
    """
}
