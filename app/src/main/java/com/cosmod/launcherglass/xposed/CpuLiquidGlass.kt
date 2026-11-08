package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.util.LruCache
import java.util.WeakHashMap
import kotlin.math.*
import com.cosmod.launcherglass.util.LiquidOptics

/** Software counterpart of LiquidGlassShader: the same lens, native mask and linear sampling. */
internal object CpuLiquidGlass {
    private data class Key(val width:Int,val height:Int,val rounding:Float,val edge:Int,val strength:Int,val extend:Boolean)
    private data class Coordinates(val x:FloatArray,val y:FloatArray)
    private data class Pixels(val generation:Int,val pixels:IntArray)
    private val images=WeakHashMap<Bitmap,Pixels>()
    private val coordinates=object:LruCache<Key,Coordinates>(4*1024*1024){
        override fun sizeOf(key:Key,value:Coordinates)=value.x.size*8
    }
    fun clear(){images.clear();coordinates.evictAll()}
    private fun smooth(a:Float,b:Float,value:Float):Float {
        val t=((value-a)/(b-a)).coerceIn(0f,1f);return t*t*(3f-2f*t)
    }
    private fun coordinates(key:Key):Coordinates {
        coordinates.get(key)?.let {return it}
        val w=key.width;val h=key.height;val edge=min(w,h).toFloat()
        val padX=if(key.extend)(w-edge)*.5f else 0f
        val padY=if(key.extend)(h-edge)*.5f else 0f
        val halfX=if(key.extend)edge*.5f else w*.5f
        val halfY=if(key.extend)edge*.5f else h*.5f
        val radius=edge*max(key.rounding,.20f)
        val cx=halfX-radius;val cy=halfY-radius
        val edgeStart=(1f-(.015f+key.edge*.0014f)*4f).coerceIn(.25f,.96f)
        val result=Coordinates(FloatArray(w*h),FloatArray(w*h))
        val eta=1f/1.45f
        for(y in 0 until h)for(x in 0 until w){
            val px=x+.5f;val py=y+.5f
            val centerX=sign(px-w*.5f)*max(abs(px-w*.5f)-padX,0f)
            val centerY=sign(py-h*.5f)*max(abs(py-h*.5f)-padY,0f)
            val distance=sqrt(centerX*centerX+centerY*centerY)
            val dx=centerX/max(distance,.001f);val dy=centerY/max(distance,.001f)
            var boundary=min(halfX/max(abs(dx),.0001f),halfY/max(abs(dy),.0001f))
            if(abs(dx)*boundary>cx && abs(dy)*boundary>cy){
                val projection=abs(dx)*cx+abs(dy)*cy
                boundary=projection+sqrt(max(projection*projection-cx*cx-cy*cy+radius*radius,0f))
            }
            val radial=(distance/max(boundary,1f)).coerceIn(0f,1f)
            val shoulder=smooth(edgeStart,1f,radial)
            val slope=.18f*radial+.95f*shoulder/sqrt(max(.15f,1f-.85f*shoulder*shoulder))
            val length=sqrt(slope*slope+1f)
            val nx=dx*slope/length;val ny=dy*slope/length;val nz=1f/length
            // GLSL refract(I,N,eta), with I=(0,0,-1).
            val dot=-nz
            val k=1f-eta*eta*(1f-dot*dot)
            val factor=eta*dot+sqrt(max(k,0f))
            val rx=-factor*nx;val ry=-factor*ny;val rz=-eta-factor*nz
            val amount=edge*.22f*(key.strength/50f)/max(-rz,.25f)
            val i=y*w+x
            result.x[i]=(px+rx*amount)/w
            result.y[i]=(py+ry*amount)/h
        }
        coordinates.put(key,result);return result
    }
    fun shader(wallpaper:Bitmap,mask:Bitmap,width:Int,height:Int,source:RectF,rounding:Float,style:GlassStyle):Shader {
        val pixels=images[wallpaper]?.takeIf {it.generation==wallpaper.generationId}?.pixels ?: run {
            val readable=if(wallpaper.config==Bitmap.Config.HARDWARE)wallpaper.copy(Bitmap.Config.ARGB_8888,false)else wallpaper
            val data=IntArray(wallpaper.width*wallpaper.height)
            readable.getPixels(data,0,wallpaper.width,0,0,wallpaper.width,wallpaper.height)
            if(readable!==wallpaper)readable.recycle()
            images[wallpaper]=Pixels(wallpaper.generationId,data);data
        }
        val map=coordinates(Key(width,height,rounding,style.width,style.distortion,style.folder || style.extendedLens))
        val alpha=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        Canvas(alpha).drawBitmap(mask,null,Rect(0,0,width,height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val output=IntArray(width*height);alpha.getPixels(output,0,width,0,0,width,height)
        val ww=wallpaper.width;val wh=wallpaper.height
        for(i in output.indices){
            val a=output[i] ushr 24
            if(a==0){output[i]=0;continue}
            val px=(source.left+map.x[i]*source.width()-.5f).coerceIn(0f,(ww-1).toFloat())
            val py=(source.top+map.y[i]*source.height()-.5f).coerceIn(0f,(wh-1).toFloat())
            val x=px.toInt();val y=py.toInt();val xx=min(x+1,ww-1);val yy=min(y+1,wh-1)
            val fx=px-x;val fy=py-y
            val c00=pixels[y*ww+x];val c10=pixels[y*ww+xx];val c01=pixels[yy*ww+x];val c11=pixels[yy*ww+xx]
            var glowR=0f;var glowG=0f;var glowB=0f
            val stepX=source.width()*.035f;val stepY=source.height()*.035f
            for(oy in -1..1)for(ox in -1..1){
                if(ox==0 && oy==0)continue
                val bx=(px+ox*stepX).roundToInt().coerceIn(0,ww-1)
                val by=(py+oy*stepY).roundToInt().coerceIn(0,wh-1)
                val c=pixels[by*ww+bx]
                val r=((c ushr 16) and 255)/255f;val g=((c ushr 8) and 255)/255f;val b=(c and 255)/255f
                val bright=LiquidOptics.highlight(r,g,b)*(c ushr 24)/255f*.125f
                glowR+=r*bright;glowG+=g*bright;glowB+=b*bright
            }
            val veil=LiquidOptics.veil((i%width+.5f)/width,(i/width+.5f)/height)
            var rgb=0
            for(shift in 16 downTo 0 step 8){
                val top=((c00 ushr shift) and 255)*(1f-fx)+((c10 ushr shift) and 255)*fx
                val bottom=((c01 ushr shift) and 255)*(1f-fx)+((c11 ushr shift) and 255)*fx
                val glow=when(shift){16->glowR;8->glowG;else->glowB}
                val finished=LiquidOptics.channel((top*(1f-fy)+bottom*fy)/255f,glow,veil)
                rgb=rgb or ((finished*255f).roundToInt().coerceIn(0,255) shl shift)
            }
            output[i]=(a shl 24) or rgb
        }
        alpha.setPixels(output,0,width,0,0,width,height)
        return BitmapShader(alpha,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
    }
}
