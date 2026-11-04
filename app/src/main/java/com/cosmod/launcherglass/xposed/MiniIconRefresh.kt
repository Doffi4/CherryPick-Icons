package com.cosmod.launcherglass.xposed

/** Mini previews may lower the global material FPS limit to 30; other icons retain the global limit. */
internal object MiniIconRefresh {
    fun fps(globalFps:Int,mini:Boolean,enabled:Boolean)=
        minOf(globalFps.coerceIn(1,60),if(mini && enabled)30 else 60)
    fun isMini(preview:Boolean,width:Float,height:Float,nativeWidth:Int,nativeHeight:Int):Boolean =
        preview && nativeWidth>0 && nativeHeight>0 && width>0f && height>0f &&
            width<=nativeWidth*.5f && height<=nativeHeight*.5f
}

/** Keep the requested average cadence when display and material FPS differ. */
internal class SampleFrameClock {
    private var nextNs=0L
    private var rate=0
    fun remainingNs(nowNs:Long,fps:Int):Long =
        if(rate!=fps || nextNs==0L)0L else (nextNs-nowNs-500_000L).coerceAtLeast(0L)
    fun record(nowNs:Long,fps:Int){
        val period=1_000_000_000L/fps
        nextNs=if(rate!=fps || nextNs==0L)nowNs+period
            else nextNs+maxOf(1L,(nowNs-nextNs)/period+1L)*period
        rate=fps
    }
}
