package com.cosmod.launcherglass.xposed

import android.graphics.ColorMatrixColorFilter

data class GlassStyle(val liquid:Boolean,val width:Int,val distortion:Int,
    val radius:Int,val opacity:Int,val filter:ColorMatrixColorFilter?,val folder:Boolean=false,val plateColor:Int?=null,val extendedLens:Boolean=false){
    companion object {
        fun icons()=GlassStyle(PrefsHelper.liquidGlass,PrefsHelper.liquidWidth,PrefsHelper.liquidDistortion,
            PrefsHelper.blurRadius,PrefsHelper.bgOpacity,PrefsHelper.blurColorFilter,plateColor=PrefsHelper.plateColor())
    }
}
