package com.cosmod.launcherglass.util

internal object IconFootprint {
    /** Bitmap resolution may stay unchanged when the native desktop icon grows. */
    fun isCapture(width:Int,height:Int,displayWidth:Int,displayHeight:Int)=
        width>displayWidth*1.1f || height>displayHeight*1.1f
}
