package com.cosmod.launcherglass.xposed

import kotlin.math.max

internal object DockGeometry {
    fun halfWidth(automatic:Boolean,center:Float,left:Float,right:Float,rowWidth:Float,cellWidth:Float,iconSize:Float,padding:Float):Float =
        if(automatic)max(center-left,right-center)+padding
        else rowWidth/2f-cellWidth/2f+iconSize/2f+padding
}
