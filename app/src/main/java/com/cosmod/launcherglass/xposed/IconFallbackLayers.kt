package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/** The original picture of an [IconOrigin.ARTWORK] icon, for the tinted variant. */
class ArtworkSource(val picture:Bitmap,val low:Int,val high:Int)

/** How the plate and glyph of a [ProcessedIcon] were obtained. */
enum class IconOrigin {
    /** A flat plate colour with a contrasting glyph (the regular algorithm). */
    PLATE,
    /** One colour; the glyph lives in alpha (cut-out or holder + glyph). */
    ALPHA_GLYPH,
    /** A full-bleed picture without a plate colour, kept as a translucent tinted picture. */
    ARTWORK,
}

/**
 * Layers for icons the regular plate/glyph split rejects.
 *
 * [alphaGlyph]: ColorOS shortcut container items are drawn in the theme
 * colour as a plate with a translucent cut-out glyph, or as a themed glyph on
 * an almost invisible (alpha 20) holder. All opaque pixels have one colour, so
 * the colour contrast search finds no glyph; the glyph is the alpha structure.
 *
 * [artwork]: full-bleed pictures (game icons, gradients) have no dominant
 * plate colour. The picture stays and is later tinted translucently, so the
 * glass plate shows through it.
 */
internal object IconFallbackLayers {
    private const val OUTSIDE=6
    private const val RIM=4

    private class Shape(val distance:IntArray,val area:Int,val left:Int,val top:Int,val right:Int,val bottom:Int)

    /** Distance from the transparent outside (connected to the border), up to [depth]; -1 = deeper inside. */
    private fun shape(pixels:IntArray,width:Int,height:Int,depth:Int=RIM):Shape? {
        val count=width*height
        val distance=IntArray(count){-2}
        val queue=IntArray(count)
        var head=0
        var tail=0
        fun seed(index:Int){
            if(distance[index]==-2 && pixels[index] ushr 24<OUTSIDE){distance[index]=0;queue[tail++]=index}
        }
        for(x in 0 until width){seed(x);seed((height-1)*width+x)}
        for(y in 0 until height){seed(y*width);seed(y*width+width-1)}
        // Flood the transparent outside first, then grow a short rim into the shape.
        while(head<tail){
            val index=queue[head++]
            val x=index%width
            val y=index/width
            for(next in intArrayOf(if(x>0)index-1 else -1,if(x<width-1)index+1 else -1,if(y>0)index-width else -1,if(y<height-1)index+width else -1)){
                if(next>=0 && distance[next]==-2 && pixels[next] ushr 24<OUTSIDE){distance[next]=0;queue[tail++]=next}
            }
        }
        head=0
        val outside=tail
        while(head<tail){
            val index=queue[head++]
            val current=distance[index]
            if(current>=depth)continue
            val x=index%width
            val y=index/width
            for(next in intArrayOf(if(x>0)index-1 else -1,if(x<width-1)index+1 else -1,if(y>0)index-width else -1,if(y<height-1)index+width else -1)){
                if(next>=0 && distance[next]==-2){distance[next]=current+1;queue[tail++]=next}
            }
        }
        var left=width;var top=height;var right=-1;var bottom=-1
        var area=0
        for(index in 0 until count){
            if(distance[index]==-2)distance[index]=-1
            if(distance[index]==0)continue
            area++
            val x=index%width;val y=index/width
            if(x<left)left=x;if(x>right)right=x;if(y<top)top=y;if(y>bottom)bottom=y
        }
        if(area==0 || outside==count)return null
        return Shape(distance,area,left,top,right,bottom)
    }

    /** Only plate-like silhouettes: no logo-shaped glass, no wide special hosts. */
    private fun plateLike(shape:Shape,width:Int,height:Int):Boolean {
        val w=shape.right-shape.left+1
        val h=shape.bottom-shape.top+1
        val aspect=w.toFloat()/h
        return shape.area>=width*height*.30f && aspect in .8f..1.25f && shape.area>=w*h*.70f
    }

    private fun luma(color:Int)=(.299f*Color.red(color)+.587f*Color.green(color)+.114f*Color.blue(color))/255f

    /** A legible partner tone for the native (non-recoloured) palette. */
    private fun partner(color:Int):Int {
        val hsv=FloatArray(3)
        Color.colorToHSV(color,hsv)
        return if(luma(color)>.5f)Color.HSVToColor(floatArrayOf(hsv[0],(hsv[1]*1.1f).coerceAtMost(1f),hsv[2]*.42f))
            else Color.rgb(Color.red(color)+((255-Color.red(color))*.7f).roundToInt(),
                Color.green(color)+((255-Color.green(color))*.7f).roundToInt(),Color.blue(color)+((255-Color.blue(color))*.7f).roundToInt())
    }

    private fun percentile(histogram:IntArray,total:Int,fraction:Float):Int {
        val target=(total*fraction).roundToInt().coerceIn(1,total)
        var sum=0
        for(i in histogram.indices){sum+=histogram[i];if(sum>=target)return i}
        return histogram.lastIndex
    }

    private fun output(glyph:IntArray,mask:IntArray,width:Int,height:Int,color:Int,origin:IconOrigin):ProcessedIcon {
        val glyphBitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        glyphBitmap.setPixels(glyph,0,width,0,0,width,height)
        val maskBitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        maskBitmap.setPixels(mask,0,width,0,0,width,height)
        return ProcessedIcon(glyphBitmap,maskBitmap,color or (0xff shl 24),origin)
    }

    fun alphaGlyph(pixels:IntArray,width:Int,height:Int):ProcessedIcon? {
        if(width<24 || height<24)return null
        val shape=shape(pixels,width,height) ?: return null
        if(!plateLike(shape,width,height))return null
        val distance=shape.distance
        // Plate level: the alpha just inside the silhouette's anti-aliased edge.
        val levels=IntArray(256)
        var rimCount=0
        var rimR=0L;var rimG=0L;var rimB=0L
        for(index in pixels.indices){
            val d=distance[index]
            if(d<2 || d>RIM)continue
            val p=pixels[index]
            levels[p ushr 24]++;rimCount++
            rimR+=(p ushr 16) and 0xff;rimG+=(p ushr 8) and 0xff;rimB+=p and 0xff
        }
        val level=if(rimCount>=16)percentile(levels,rimCount,.5f)else 255
        if(level<OUTSIDE)return null
        val cutOut=level>=128
        val span=if(cutOut)level else 255-level
        if(span<48)return null
        // Deviation from the plate level inside the shape (not on its edge).
        val deviation=IntArray(pixels.size)
        val histogram=IntArray(256)
        var strong=0
        for(index in pixels.indices){
            val d=distance[index]
            if(d==0 || d in 1..2)continue
            val a=pixels[index] ushr 24
            val value=(if(cutOut)level-a else a-level).coerceAtLeast(0)
            deviation[index]=value
            if(value>12){histogram[value]++;strong++}
        }
        if(strong<shape.area*.01f)return null
        val top=percentile(histogram,strong,.95f)
        if(top<24)return null
        val glyph=IntArray(pixels.size)
        val mask=IntArray(pixels.size)
        var coverage=0f
        var glyphR=0L;var glyphG=0L;var glyphB=0L;var glyphCount=0
        for(index in pixels.indices){
            val d=distance[index]
            if(d==0)continue
            val p=pixels[index]
            val a=p ushr 24
            val plate=if(d in 1..2)(a*255/level).coerceAtMost(255)else 255
            mask[index]=(plate shl 24) or 0xffffff
            val g=((deviation[index]-8).toFloat()/(top-8)).coerceIn(0f,1f)
            if(g<=0f)continue
            coverage+=g
            if(!cutOut && deviation[index]*2>=top){
                glyphR+=(p ushr 16) and 0xff;glyphG+=(p ushr 8) and 0xff;glyphB+=p and 0xff;glyphCount++
            }
            glyph[index]=(g*255f).roundToInt() shl 24
        }
        val ratio=coverage/shape.area
        if(ratio<.01f || ratio>.85f)return null
        val rimColor=if(rimCount>0)Color.rgb((rimR/rimCount).toInt(),(rimG/rimCount).toInt(),(rimB/rimCount).toInt())else Color.GRAY
        // Native (non-recoloured) palette: a cut-out shows the wallpaper, a
        // themed glyph has an invisible holder. Pick a legible partner tone.
        val plateColor:Int
        val glyphColor:Int
        if(cutOut){plateColor=rimColor;glyphColor=partner(rimColor)}
        else {
            glyphColor=if(glyphCount>0)Color.rgb((glyphR/glyphCount).toInt(),(glyphG/glyphCount).toInt(),(glyphB/glyphCount).toInt())else Color.WHITE
            plateColor=if(kotlin.math.abs(luma(rimColor)-luma(glyphColor))>=.3f)rimColor else partner(glyphColor)
        }
        val rgb=glyphColor and 0xffffff
        for(index in glyph.indices)if(glyph[index]!=0)glyph[index]=glyph[index] or rgb
        return output(glyph,mask,width,height,plateColor,IconOrigin.ALPHA_GLYPH)
    }

    fun artwork(pixels:IntArray,width:Int,height:Int):ProcessedIcon? {
        if(width<24 || height<24)return null
        val fade=(minOf(width,height)*.05f).roundToInt().coerceAtLeast(RIM)
        val shape=shape(pixels,width,height,fade) ?: return null
        if(!plateLike(shape,width,height))return null
        val histogram=IntArray(256)
        var opaque=0
        var sumR=0L;var sumG=0L;var sumB=0L
        for(p in pixels){
            if(p ushr 24<128)continue
            val r=(p ushr 16) and 0xff;val g=(p ushr 8) and 0xff;val b=p and 0xff
            histogram[((299*r+587*g+114*b)/1000).coerceIn(0,255)]++
            opaque++;sumR+=r;sumG+=g;sumB+=b
        }
        if(opaque<shape.area/2)return null
        val low=percentile(histogram,opaque,.02f)
        val high=percentile(histogram,opaque,.98f)
        if(high-low<32)return null
        val average=Color.rgb((sumR/opaque).toInt(),(sumG/opaque).toInt(),(sumB/opaque).toInt())
        // The picture itself is the glyph layer (it is tinted later). Its rim
        // fades into the plate, so the glyph contour/shadow passes stay inside
        // the silhouette.
        val picture=IntArray(pixels.size)
        val mask=IntArray(pixels.size)
        val distance=shape.distance
        for(index in pixels.indices){
            val p=pixels[index]
            val a=p ushr 24
            if(a==0)continue
            mask[index]=(a shl 24) or 0xffffff
            val d=distance[index]
            val k=if(d<0)1f else (d.toFloat()/fade).coerceIn(0f,1f)
            val edge=k*k*(3f-2f*k)
            picture[index]=((a*edge).roundToInt().coerceIn(0,255) shl 24) or (p and 0xffffff)
        }
        val icon=output(picture,mask,width,height,average,IconOrigin.ARTWORK)
        return icon.copy(artwork=ArtworkSource(icon.glyphBitmap,low,high))
    }
}
