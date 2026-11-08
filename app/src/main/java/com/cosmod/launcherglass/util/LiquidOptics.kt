package com.cosmod.launcherglass.util

/** The base stays sharp; only extracted highlights contribute to the halo. */
internal object LiquidOptics {
    fun needsBackdrop(radius:Int,liquid:Boolean)=liquid || radius>0
    fun blurRadius(radius:Int,height:Int,liquid:Boolean)=if(liquid)0f else radius*height/800f
    fun highlight(r:Float,g:Float,b:Float)=((.2126f*r+.7152f*g+.0722f*b-.55f)/.45f).coerceIn(0f,1f)
    fun veil(x:Float,y:Float):Float {
        val dx=x-.18f;val dy=(y-.08f)*.85f
        return .025f+.045f*kotlin.math.exp(-(dx*dx+dy*dy)*2.4f)
    }
    fun channel(value:Float,bloom:Float,veil:Float)=
        (value+(1f-value)*veil+bloom*.18f).coerceIn(0f,1f)
}
