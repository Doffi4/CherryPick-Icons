package com.cosmod.launcherglass.util

/** Android 16 truncates converted bounds; newer launchers supply their own rounded helper. */
internal data class LegacyFolderBounds(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    companion object {
        fun from(x:Float,y:Float,width:Float,height:Float,hostWidth:Int,mirrored:Boolean)=
            LegacyFolderBounds(if(mirrored)(hostWidth-width-x).toInt()else x.toInt(),y.toInt(),
                if(mirrored)hostWidth-x.toInt()else (x+width).toInt(),(y+height).toInt())
    }
}
