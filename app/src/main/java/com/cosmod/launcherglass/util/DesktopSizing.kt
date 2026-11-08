package com.cosmod.launcherglass.util

import kotlin.math.roundToInt

internal object DesktopSizing {
    const val ICON_SCALE="desktop_icon_scale"
    const val INDICATOR_GAPS="desktop_indicator_gaps"
    const val WIDGET_GAPS="desktop_widget_gaps"
    const val BOTTOM_BIAS="desktop_bottom_bias"
    val defaults=mapOf(ICON_SCALE to 100,INDICATOR_GAPS to 100,WIDGET_GAPS to 100,BOTTOM_BIAS to 0)
    val ranges=defaults.mapValues {(key,_) -> if(key==ICON_SCALE)100..160 else 0..100}
    fun iconPixels(base:Int,percent:Int,cellWidth:Int,gap:Int)=
        (base*percent.coerceIn(100,160)/100f).roundToInt().coerceAtMost(maxOf(base,cellWidth-gap))
    fun padding(base:Int,percent:Int)=(base.coerceAtLeast(0)*percent.coerceIn(0,100)/100f).roundToInt()
    fun downwardShift(topFree:Int,totalFree:Int,percent:Int)=
        ((totalFree-topFree).coerceAtLeast(0)*percent.coerceIn(0,100)/100f).roundToInt()
}
