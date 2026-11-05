package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.util.LruCache
import java.util.WeakHashMap
import kotlin.math.*

/** Native Flux alpha geometry, shared and baked once, never on live frames. */
internal object GlassGeometry {
    private data class ShapeKey(val w:Int,val h:Int,val hash:Long)
    private class Geometry(val key:ShapeKey,val alpha:ByteArray,val rounding:Float){
        // Only the contour rim needs the distance field; material creation
        // with the contour switched off reads alpha and rounding only.
        var depth:FloatArray?=null
    }
    private class NativeMask(val generation:Int,val geometry:Geometry)
    private data class RimKey(val shape:ShapeKey,val opacity:Int)
    private val nativeMasks=WeakHashMap<Bitmap,NativeMask>()
    // Sized for all plate, cluster and panel masks in use plus a history:
    // a 2 MiB limit held two large masks, so alternating shapes recomputed
    // their distance fields on the UI thread during drag and animations.
    private val shapes=object:LruCache<ShapeKey,Geometry>(16*1024*1024){
        override fun sizeOf(key:ShapeKey,value:Geometry)=value.alpha.size*5
    }
    private val rims=object:LruCache<RimKey,Bitmap>(12*1024*1024){
        override fun sizeOf(key:RimKey,value:Bitmap)=value.allocationByteCount
    }
    @Synchronized private fun geometry(mask:Bitmap):Geometry {
        nativeMasks[mask]?.takeIf {it.generation==mask.generationId}?.let {return it.geometry}
        val w=mask.width;val h=mask.height
        val pixels=IntArray(w*h)
        val readable=if(mask.config==Bitmap.Config.HARDWARE)mask.copy(Bitmap.Config.ARGB_8888,false)else mask
        readable.getPixels(pixels,0,w,0,0,w,h)
        if(readable!==mask)readable.recycle()
        val alpha=ByteArray(pixels.size)
        var hash=-3750763034362895579L
        for(i in pixels.indices){
            val a=pixels[i] ushr 24
            alpha[i]=a.toByte();hash=(hash xor a.toLong())*1099511628211L
        }
        val key=ShapeKey(w,h,hash)
        shapes.get(key)?.let {nativeMasks[mask]=NativeMask(mask.generationId,it);return it}
        // Curvature affects the lens; exact clipping uses the native alpha.
        var left=w;var top=h;var right=0;var bottom=0
        for(y in 0 until h){
            val row=y*w
            for(x in 0 until w)if((alpha[row+x].toInt() and 255)>=128){
                if(x<left)left=x;if(x>right)right=x
                if(y<top)top=y;if(y>bottom)bottom=y
            }
        }
        val edge=min(right-left+1,bottom-top+1).coerceAtLeast(1)
        var inset=0
        while(inset<edge/2 && (alpha[(top+inset).coerceIn(0,h-1)*w+(left+inset).coerceIn(0,w-1)].toInt() and 255)<128)inset++
        val rounding=(inset/(.29289322f*edge)).coerceIn(0f,.5f)
        com.cosmod.launcherglass.util.GlassLogger.limited("GlassGeometry","nativeMask"){"Native mask ${w}x$h, bounds=$left,$top,$right,$bottom, opticalCorner=$rounding"}
        return Geometry(key,alpha,rounding).also {
            shapes.put(key,it);nativeMasks[mask]=NativeMask(mask.generationId,it)
        }
    }
    private fun depth(shape:Geometry):FloatArray {
        shape.depth?.let {return it}
        val w=shape.key.w;val h=shape.key.h;val alpha=shape.alpha
        // The distance field follows native smooth corners, including padding.
        val depth=FloatArray(alpha.size) {i ->
            if((alpha[i].toInt() and 255)<128)0f
            else if(i%w==0 || i%w==w-1 || i/w==0 || i/w==h-1)1f else 1e6f
        }
        val diagonal=1.41421356f
        for(y in 0 until h)for(x in 0 until w){
            val i=y*w+x
            if(x>0)depth[i]=min(depth[i],depth[i-1]+1f)
            if(y>0){
                depth[i]=min(depth[i],depth[i-w]+1f)
                if(x>0)depth[i]=min(depth[i],depth[i-w-1]+diagonal)
                if(x<w-1)depth[i]=min(depth[i],depth[i-w+1]+diagonal)
            }
        }
        for(y in h-1 downTo 0)for(x in w-1 downTo 0){
            val i=y*w+x
            if(x<w-1)depth[i]=min(depth[i],depth[i+1]+1f)
            if(y<h-1){
                depth[i]=min(depth[i],depth[i+w]+1f)
                if(x>0)depth[i]=min(depth[i],depth[i+w-1]+diagonal)
                if(x<w-1)depth[i]=min(depth[i],depth[i+w+1]+diagonal)
            }
        }
        shape.depth=depth
        return depth
    }
    @Synchronized fun rounding(mask:Bitmap)=geometry(mask).rounding
    @Synchronized fun rim(mask:Bitmap):Bitmap {
        val shape=geometry(mask)
        val key=RimKey(shape.key,if(PrefsHelper.iconStroke)PrefsHelper.strokeOpacity.coerceIn(0,100)else 0)
        rims.get(key)?.let {return it}
        val width=mask.width;val height=mask.height
        val distance=depth(shape)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(width*height)
        val edge=min(width,height).toFloat()
        val strength=key.opacity*.016f
        // Tabulate both exponential falloffs at 1/8 px; evaluating exp() twice
        // for every pixel dominated rim creation for large plates.
        val ringScale=max(.7f,edge*.010f);val bevelScale=max(1f,edge*.085f)
        val steps=(edge*4f+16f).toInt()
        val ringTable=FloatArray(steps) {exp(-(it/8f)/ringScale)}
        val bevelTable=FloatArray(steps) {exp(-(it/8f)/bevelScale)}
        for(y in 0 until height)for(x in 0 until width){
            val i=y*width+x
            val coverage=(shape.alpha[i].toInt() and 255)/255f
            if(coverage==0f)continue
            val cx=x+.5f-width*.5f;val cy=y+.5f-height*.5f
            val d=max(distance[i]-.5f,0f)
            val index=(d*8f).toInt()
            val ring=if(index<steps)ringTable[index]else exp(-d/ringScale)
            val bevel=if(index<steps)bevelTable[index]else exp(-d/bevelScale)
            // Fade diagonally into the dark lower-right reflection.
            val diagonal=(.5f+.5f*(cx/(width*.5f)*.65f+cy/(height*.5f)*.76f)/1.41f)
            val fade=((diagonal-.12f)/.76f).coerceIn(0f,1f)
            val shade=fade*fade*(3f-2f*fade)
            val white=((ring*(.32f-.285f*shade)+bevel*.018f*(1f-shade))*strength*coverage).coerceIn(0f,.95f)
            val dark=(ring*(.01f+.14f*shade)*strength*coverage).coerceIn(0f,.8f)
            val alpha=white+dark*(1-white)
            if(alpha<.002f)continue
            val c=(white/alpha*255).roundToInt().coerceIn(0,255)
            pixels[y*width+x]=Color.argb((alpha*255).roundToInt(),c,c,c)
        }
        bitmap.setPixels(pixels,0,width,0,0,width,height);rims.put(key,bitmap);return bitmap
    }
    fun wallpaperShader(bitmap:Bitmap)=BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
        if(android.os.Build.VERSION.SDK_INT>=33)setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
    }
}
