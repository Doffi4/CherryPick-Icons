package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.GlyphShadowHelper
import de.robv.android.xposed.*
import java.util.WeakHashMap
import kotlin.math.abs

/** OxygenOS Launcher 16.6.8: Flux's plate and logo are two independently animated bitmaps. */
internal object Oxygen16IconSupport {
    private var configured=false
    private var type:Class<*>?=null
    private data class Readable(val generation:Int,val bitmap:Bitmap)
    private data class Background(val generation:Int,val parts:ProcessedIcon?)
    private val readable=WeakHashMap<Bitmap,Readable>()
    private val backgrounds=WeakHashMap<Bitmap,Background>()
    private val failures=HashSet<String>()
    fun configure(context:Context){
        if(configured)return
        configured=true
        val version=try {context.packageManager.getPackageInfo(context.packageName,0).versionName}catch(_:Throwable){null}
        // Deliberately keep all previously supported launcher versions on their existing path.
        if(version!="16.6.8")return
        val native=XposedHelpers.findClassIfExists("com.android.launcher3.icons.FastBitmapDrawable",context.classLoader) ?: return
        val method=try {
            if(native.getDeclaredField("mSubLayerBitmap").type!=Bitmap::class.java ||
                native.getDeclaredField("mPaintSubLayer").type!=Paint::class.java)return
            native.getDeclaredMethod("drawSubLayerInternal",Canvas::class.java,Rect::class.java)
        }catch(_:Throwable){return}
        type=native
        ModuleHooks.hookMethod(method,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!PrefsHelper.enabled || IconGlassHook.isRecordingNative())return
                val drawable=param.thisObject as? Drawable ?: return
                val canvas=param.args.getOrNull(0) as? Canvas ?: return
                val bounds=param.args.getOrNull(1) as? Rect ?: return
                try {
                    if(background(drawable)==null)return
                    val source=subLayer(drawable) ?: return
                    val glyph=software(source)
                    val paint=Paint(NativeIconAccess.field(drawable,"mPaintSubLayer") as? Paint ?: Paint(3))
                    val palette=PrefsHelper.iconPalette
                    if(palette.custom || palette.whiteGlyph)paint.colorFilter=palette.filter
                    // Record the logo in its native sub-layer transform, separately
                    // from the plate. Native press/launch scale and alpha stay intact.
                    val capturedBounds=Rect(bounds)
                    NativeSurfaceLive.capture(canvas,glyph.width,glyph.height) {target -> drawGlyph(target,capturedBounds,glyph,paint)}
                    drawGlyph(canvas,bounds,glyph,paint)
                    param.result=null
                }catch(t:Throwable){
                    if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("Oxygen16","Native split logo unavailable",t)
                }
            }
        })
        GlassLogger.i("Oxygen16","Launcher 16.6.8 split Flux bitmap path selected")
    }
    private fun subLayer(drawable:Drawable):Bitmap? {
        val native=type ?: return null
        if(!native.isInstance(drawable))return null
        return (NativeIconAccess.field(drawable,"mSubLayerBitmap") as? Bitmap)?.takeUnless {it.isRecycled}
    }
    fun stamp(drawable:Drawable):Long {
        val bitmap=subLayer(drawable) ?: return 0L
        return (System.identityHashCode(bitmap).toLong() shl 32) xor bitmap.generationId.toLong()
    }
    private fun software(bitmap:Bitmap):Bitmap {
        if(bitmap.config!=Bitmap.Config.HARDWARE)return bitmap
        readable[bitmap]?.takeIf {it.generation==bitmap.generationId}?.let {return it.bitmap}
        return (bitmap.copy(Bitmap.Config.ARGB_8888,false) ?: error("Cannot read native Flux layer"))
            .also {readable[bitmap]=Readable(bitmap.generationId,it)}
    }
    fun background(drawable:Drawable):ProcessedIcon? {
        if(subLayer(drawable)==null)return null
        val original=(NativeIconAccess.field(drawable,"mBitmap") as? Bitmap)?.takeUnless {it.isRecycled} ?: return null
        backgrounds[original]?.takeIf {it.generation==original.generationId}?.let {return it.parts}
        val bitmap=software(original)
        var color:Int?=null
        var flat=true
        // Only accept a genuine single-color plate. Full artwork retains the
        // old extractor even on 16.6.8; a spare sublayer alone is not sufficient.
        outer@for(y in 0 until bitmap.height step maxOf(1,bitmap.height/16))for(x in 0 until bitmap.width step maxOf(1,bitmap.width/16)){
            val pixel=bitmap.getPixel(x,y)
            if(Color.alpha(pixel)<180)continue
            val base=color
            if(base==null)color=pixel
            else if(abs(Color.red(pixel)-Color.red(base))>8 || abs(Color.green(pixel)-Color.green(base))>8 || abs(Color.blue(pixel)-Color.blue(base))>8){flat=false;break@outer}
        }
        val parts=if(!flat || color==null)null else {
            val mask=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
            Canvas(mask).drawBitmap(bitmap,0f,0f,Paint(3).apply {colorFilter=PorterDuffColorFilter(Color.WHITE,PorterDuff.Mode.SRC_IN)})
            ProcessedIcon(Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888),mask,color)
        }
        backgrounds[original]=Background(original.generationId,parts)
        return parts
    }
    fun snapshotParts(drawable:Drawable):ProcessedIcon? {
        val plate=background(drawable) ?: return null
        val source=subLayer(drawable) ?: return null
        val glyph=Bitmap.createBitmap(plate.plateMaskBitmap.width,plate.plateMaskBitmap.height,Bitmap.Config.ARGB_8888)
        val paint=Paint(NativeIconAccess.field(drawable,"mPaintSubLayer") as? Paint ?: Paint(3)).apply {alpha=255}
        val palette=PrefsHelper.iconPalette
        if(palette.custom || palette.whiteGlyph)paint.colorFilter=palette.filter
        Canvas(glyph).drawBitmap(software(source),null,Rect(0,0,glyph.width,glyph.height),paint)
        return plate.copy(glyphBitmap=glyph)
    }
    private fun drawGlyph(canvas:Canvas,bounds:Rect,glyph:Bitmap,paint:Paint){
        if(PrefsHelper.glyphShadow && PrefsHelper.shadowOpacity>0)GlyphShadowHelper.getOrCreateShadow(glyph)?.let {shadow ->
            val sx=bounds.width().toFloat()/glyph.width;val sy=bounds.height().toFloat()/glyph.height
            val x=bounds.left+shadow.offsetX*sx
            val y=bounds.top+shadow.offsetY*sy+maxOf(2f,bounds.height()*.016f)
            canvas.drawBitmap(shadow.bitmap,null,RectF(x,y,x+shadow.bitmap.width*sx,y+shadow.bitmap.height*sy),Paint(3).apply {alpha=175*PrefsHelper.shadowOpacity/100*paint.alpha/255})
        }
        GlyphMaterialRenderer.draw(canvas,bounds,glyph,paint)
    }
}
