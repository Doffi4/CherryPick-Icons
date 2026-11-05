package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import com.cosmod.launcherglass.util.*
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Shared wallpaper material for folder plates and photo widgets. */
internal object SurfaceMaterial {
    private data class Key(val width:Int,val height:Int,val radius:Int,val shape:Long,val native:Int)
    private class Shape(val mask:Bitmap)
    // Byte-sized: a 12-entry count limit let a few panel/cluster sizes evict
    // each other, so every drag frame rasterized a new mask, rim and overlay.
    private val shapes=object:LruCache<Key,Shape>(12*1024*1024){
        override fun sizeOf(key:Key,value:Shape)=value.mask.allocationByteCount+64
    }
    // The material cache keys on glyph identity only and surfaces never draw
    // it: one shared pixel replaces a full-size empty bitmap per shape.
    private val emptyGlyph:Bitmap by lazy {Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)}
    private class Track(var key:Key,var family:Long,var shape:Shape,var builtAt:Long,var refreshAt:Long=0L)
    private val tracks=WeakHashMap<Any,Track>()
    private const val REBUILD_INTERVAL_MS=48L
    var geometryRevision=0L
        private set
    fun invalidateGeometry(){geometryRevision++;shapes.evictAll();tracks.clear()}
    /**
     * Translation- and size-independent description of how the OEM built a
     * contour. Hashing absolute path coordinates changed the key whenever a
     * plate moved or its bounds jittered by a pixel.
     */
    fun descriptor(vararg parts:Any?):Long {
        var hash=-3750763034362895579L
        for(part in parts){
            val value=when(part){
                null -> 0L
                is Float -> java.lang.Float.floatToIntBits(part).toLong()
                else -> part.hashCode().toLong()
            }
            hash=(hash xor value)*1099511628211L
        }
        return hash
    }
    private fun normalizedFingerprint(path:Path,bounds:Rect,w:Int,h:Int):Long {
        val sx=w.toFloat()/bounds.width();val sy=h.toFloat()/bounds.height()
        val points=path.approximate(.75f)
        var hash=-3750763034362895579L
        var i=0
        while(i+2<points.size){
            hash=(hash xor ((points[i+1]-bounds.left)*sx).roundToInt().toLong())*1099511628211L
            hash=(hash xor ((points[i+2]-bounds.top)*sy).roundToInt().toLong())*1099511628211L
            i+=3
        }
        return hash
    }
    private fun compatible(old:Track,key:Key,family:Long)=old.family==family && old.key.native==key.native &&
        key.width in old.key.width*4/5..old.key.width*5/4 && key.height in old.key.height*4/5..old.key.height*5/4
    private fun shape(bounds:Rect,radius:Float,path:Path?,native:android.graphics.drawable.Drawable?=null,nativeMask:Bitmap?=null,
                      pathFingerprint:Long?=null,identity:Any?=null,view:View?=null):Shape {
        val longest=maxOf(bounds.width(),bounds.height()).coerceAtLeast(1)
        val scale=minOf(1f,384f/longest)
        val rawW=bounds.width()*scale;val rawH=bounds.height()*scale
        // About 1% quantization absorbs sub-pixel layout and drag jitter; the
        // canvas scale in draw() maps the mask back onto the exact bounds.
        val step=(maxOf(rawW,rawH)/96f).roundToInt().coerceIn(1,4)
        val w=((rawW/step).roundToInt()*step).coerceAtLeast(1)
        val h=((rawH/step).roundToInt()*step).coerceAtLeast(1)
        val hasPath=path!=null && !path.isEmpty
        val family=when {
            nativeMask!=null || (!hasPath && native!=null) -> 0L
            pathFingerprint!=null -> pathFingerprint
            hasPath -> 2L
            else -> 1L
        }
        val fingerprint=if(family==2L)normalizedFingerprint(path!!,bounds,w,h)else family
        val key=Key(w,h,(radius*scale*2f).roundToInt(),fingerprint,
            if(nativeMask!=null)31*System.identityHashCode(nativeMask)+nativeMask.generationId else if(hasPath)0 else System.identityHashCode(native?.constantState ?: native))
        val now=SystemClock.uptimeMillis()
        val track=identity?.let {tracks[it]}
        shapes[key]?.let {cached ->
            if(identity!=null){
                if(track==null)tracks[identity]=Track(key,family,cached,0L)else {track.key=key;track.family=family;track.shape=cached}
            }
            return cached
        }
        if(track!=null && view!=null && now-track.builtAt<REBUILD_INTERVAL_MS && compatible(track,key,family)){
            // Animated bounds (hover, resize, folder morph): reuse the recent
            // mask for a few frames instead of rasterizing mask, rim and
            // overlay on every frame. One delayed repaint restores the exact
            // contour after the size settles; a cache hit schedules nothing.
            if(track.refreshAt<=now){
                track.refreshAt=track.builtAt+REBUILD_INTERVAL_MS+8L
                view.postInvalidateDelayed((track.refreshAt-now).coerceAtLeast(1L))
            }
            return track.shape
        }
        val built=build(bounds,radius,if(hasPath)path else null,native,nativeMask,w,h)
        shapes.put(key,built)
        if(identity!=null){
            if(track==null)tracks[identity]=Track(key,family,built,now)else {track.key=key;track.family=family;track.shape=built;track.builtAt=now}
        }
        return built
    }
    private fun build(bounds:Rect,radius:Float,path:Path?,native:android.graphics.drawable.Drawable?,nativeMask:Bitmap?,w:Int,h:Int):Shape {
        val mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(mask);canvas.scale(w.toFloat()/bounds.width(),h.toFloat()/bounds.height())
        canvas.translate(-bounds.left.toFloat(),-bounds.top.toFloat())
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE}
        if(nativeMask!=null){
            // OEM card masks include a one-pixel transparent guard border.
            // Cropping that border matches TitleCardView's bounds-1 placement.
            val bitmap=WallpaperHelper.forCanvas(nativeMask,canvas)
            canvas.drawBitmap(bitmap,Rect(1,1,bitmap.width-1,bitmap.height-1),RectF(bounds),paint)
        }else if(path!=null)canvas.drawPath(path,paint)
        else if(native!=null){
            val old=Rect(native.bounds)
            try {native.bounds=bounds;native.draw(canvas)}finally{native.bounds=old}
            val pixels=IntArray(w*h);mask.getPixels(pixels,0,w,0,0,w,h)
            val peak=pixels.maxOfOrNull {it ushr 24} ?: 0
            if(peak>0)for(i in pixels.indices)pixels[i]=(((pixels[i] ushr 24)*255/peak) shl 24) or 0x00ffffff
            mask.setPixels(pixels,0,w,0,0,w,h)
        }else canvas.drawRoundRect(RectF(bounds),radius,radius,paint)
        return Shape(mask)
    }
    /**
     * [local] overrides the [view]-local footprint of [bounds] when the canvas
     * carries an extra transform (a drag preview drawn by its DragView).
     * [fallback] is the native plate a drag preview draws if no material can.
     */
    fun draw(canvas:Canvas,bounds:Rect,view:View,identity:android.graphics.drawable.Drawable,radius:Float,path:Path?=null,decorate:Boolean=true,alpha:Int=255,style:GlassStyle=GlassStyle.icons(),nativeShape:android.graphics.drawable.Drawable?=null,nativeMask:Bitmap?=null,pathFingerprint:Long?=null,
             local:RectF?=null,fallback:android.graphics.drawable.Drawable?=null):Boolean {
        if(bounds.isEmpty)return false
        if(canvas.quickReject(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat()))return true
        val sourceWallpaper=(if(style.folder)WallpaperHelper.getFolderWallpaper(view.context)else WallpaperHelper.getBlurredWallpaper(view.context)) ?: return false
        // Native drag preview Picture: leave the footprint empty; the DragView
        // draws this material live (see DragPreviewMaterial).
        if(DragPreviewMaterial.captures(canvas) && DragPreviewMaterial.captureSurface(canvas,view,
                DragPreviewMaterial.Surface(Rect(bounds),radius,path?.let(::Path),decorate,alpha,style,nativeShape,nativeMask,pathFingerprint,identity,fallback)))return true
        IconGlassHook.registerMaterialView(view)
        // The launcher may render a drag shadow to a software Canvas. A GPU
        // wallpaper cannot be sampled there; use the shared software snapshot.
        val wallpaper=try {WallpaperHelper.forCanvas(sourceWallpaper,canvas)}catch(_:Exception){return false}
        if(!canvas.isHardwareAccelerated && wallpaper.config==Bitmap.Config.HARDWARE)return false
        val shape=shape(bounds,radius,path,nativeShape,nativeMask,pathFingerprint,identity,view)
        val world=Matrix()
        try {XposedHelpers.callMethod(view,"transformMatrixToGlobal",world)}catch(_:Throwable){return false}
        val footprint=local ?: RectF(bounds)
        val screen=RectF(footprint);world.mapRect(screen)
        val metrics=view.resources.displayMetrics
        val source=WallpaperFootprint.source(wallpaper,metrics.widthPixels,metrics.heightPixels,screen)
        val save=if(alpha<255)canvas.saveLayerAlpha(RectF(bounds),alpha)else canvas.save()
        try {
            canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat()/shape.mask.width,bounds.height().toFloat()/shape.mask.height)
            val local=Rect(0,0,shape.mask.width,shape.mask.height)
            IconMaterialRenderer.draw(canvas,local,shape.mask,emptyGlyph,style.plateColor ?: PrefsHelper.iconPalette.background,
                style.opacity*255/100,!decorate,wallpaper,source,
                IconMaterialRenderer.Binding(view,identity,RectF(footprint),metrics.widthPixels,metrics.heightPixels,alpha=alpha,
                    foreign=ForeignDraw.isForeign(view)),decorate,style) {target,rect,_ ->
                if(decorate && PrefsHelper.iconStroke && PrefsHelper.strokeOpacity>0)
                    target.drawBitmap(GlassGeometry.rim(shape.mask),null,rect,Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }finally{canvas.restoreToCount(save)}
        return true
    }
    fun contour(canvas:Canvas,bounds:Rect,radius:Float,path:Path?=null,nativeMask:Bitmap?=null){
        if(!PrefsHelper.iconStroke || PrefsHelper.strokeOpacity<=0 || bounds.isEmpty)return
        val shape=shape(bounds,radius,path,nativeMask=nativeMask)
        canvas.drawBitmap(GlassGeometry.rim(shape.mask),null,bounds,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}
