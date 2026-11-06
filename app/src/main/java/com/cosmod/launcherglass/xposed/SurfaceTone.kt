package com.cosmod.launcherglass.xposed

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.cosmod.launcherglass.util.GlassLogger
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Plate colour for surfaces without their own icon plate: folders and
 * multi-icon containers.
 *
 * With the system palette each icon keeps its own plate colour (for example
 * the grey of ColorOS themed icons), while surfaces used the palette's
 * hue/saturation/brightness tone, so they looked unrelated to the icons next
 * to them. When most visible icons share one plate colour, surfaces use it;
 * a custom palette always uses its own background.
 */
internal object SurfaceTone {
    private const val CLUSTER=18
    private const val MIN_ICONS=3
    private const val MIN_SHARE=.30f
    private const val CHANGE=6
    private const val DELAY=250L
    private val plates=WeakHashMap<Drawable,Int>()
    @Volatile private var typical:Int?=null
    private var scheduled=false
    private var logs=0
    private val main by lazy {Handler(Looper.getMainLooper())}

    fun base():Int {
        val palette=PrefsHelper.iconPalette
        return if(palette.custom)palette.background else typical ?: palette.background
    }

    /** Remembers the plate of a regular icon (not a fallback or picture icon). */
    fun record(drawable:Drawable,icon:ProcessedIcon?){
        if(icon==null || icon.origin!=IconOrigin.PLATE || Color.alpha(icon.bgColor)<128)return
        val color=icon.bgColor or (0xff shl 24)
        synchronized(this){
            if(plates.put(drawable,color)==color || scheduled)return
            scheduled=true
        }
        main.postDelayed(::recompute,DELAY)
    }

    /** Icon theme change: forget old plates; keep the tone until new icons report theirs. */
    fun clear(){
        synchronized(this){plates.clear()}
    }

    private fun recompute(){
        val colors=synchronized(this){scheduled=false;plates.values.toIntArray()}
        val next=cluster(colors)
        val old=typical
        if(next==old || (next!=null && old!=null && distance(next,old)<=CHANGE))return
        typical=next
        if(logs<6){logs++;GlassLogger.i("SurfaceTone","Surface plate ${next?.let {"#%06x".format(it and 0xffffff)} ?: "from palette"} (${colors.size} icons)")}
        // Folder previews and multi-icon containers record the plate colour.
        IconGlassHook.invalidateFolderPreviews()
        IconGlassHook.invalidateActiveIcons()
    }

    private fun distance(a:Int,b:Int)=abs(Color.red(a)-Color.red(b))+abs(Color.green(a)-Color.green(b))+abs(Color.blue(a)-Color.blue(b))

    /** Average of the largest group of near-identical plates, if it is common enough. */
    private fun cluster(colors:IntArray):Int? {
        if(colors.size<MIN_ICONS)return null
        // Seeds are the distinct colours (bounded); themed plates are identical.
        val seeds=LinkedHashMap<Int,Int>()
        for(color in colors)seeds[color]=(seeds[color] ?: 0)+1
        var best=-1;var bestCount=0
        for(seed in seeds.keys.take(64)){
            var count=0
            for(color in colors)if(distance(seed,color)<=CLUSTER)count++
            if(count>bestCount){best=seed;bestCount=count}
        }
        if(bestCount<MIN_ICONS || bestCount<colors.size*MIN_SHARE)return null
        var r=0L;var g=0L;var b=0L;var n=0
        for(color in colors)if(distance(best,color)<=CLUSTER){r+=Color.red(color);g+=Color.green(color);b+=Color.blue(color);n++}
        return Color.rgb((r/n).toInt(),(g/n).toInt(),(b/n).toInt())
    }
}
