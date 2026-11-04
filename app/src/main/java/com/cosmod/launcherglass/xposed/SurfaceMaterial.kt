package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.util.LruCache
import android.view.View
import com.cosmod.launcherglass.util.*
import de.robv.android.xposed.XposedHelpers

/** Shared wallpaper material for folder plates and photo widgets. */
internal object SurfaceMaterial {
    private data class Key(val width:Int,val height:Int,val radius:Int,val shape:Int,val native:Int)
    private data class Shape(val mask:Bitmap,val empty:Bitmap)
    private val shapes=object:LruCache<Key,Shape>(12){}
    private fun shape(bounds:Rect,radius:Float,path:Path?,native:android.graphics.drawable.Drawable?=null,nativeMask:Bitmap?=null,pathFingerprint:Int?=null):Shape {
        val scale=minOf(1f,384f/maxOf(bounds.width(),bounds.height()))
        val w=(bounds.width()*scale).toInt().coerceAtLeast(1)
        val h=(bounds.height()*scale).toInt().coerceAtLeast(1)
        val key=Key(w,h,(radius*scale*16).toInt(),pathFingerprint ?: path?.approximate(1f)?.contentHashCode() ?: 0,
            if(nativeMask!=null)31*System.identityHashCode(nativeMask)+nativeMask.generationId else System.identityHashCode(native?.constantState ?: native))
        return shapes[key] ?: run {
            val mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(mask);canvas.scale(w.toFloat()/bounds.width(),h.toFloat()/bounds.height())
            canvas.translate(-bounds.left.toFloat(),-bounds.top.toFloat())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE}
            if(nativeMask!=null){
                // OEM card masks include a one-pixel transparent guard border.
                // Cropping that border matches TitleCardView's bounds-1 placement.
                val bitmap=WallpaperHelper.forCanvas(nativeMask,canvas)
                canvas.drawBitmap(bitmap,Rect(1,1,bitmap.width-1,bitmap.height-1),RectF(bounds),paint)
            }else if(path!=null && !path.isEmpty)canvas.drawPath(path,paint)
            else if(native!=null){
                val old=Rect(native.bounds)
                try {native.bounds=bounds;native.draw(canvas)}finally{native.bounds=old}
                val pixels=IntArray(w*h);mask.getPixels(pixels,0,w,0,0,w,h)
                val peak=pixels.maxOfOrNull {it ushr 24} ?: 0
                if(peak>0)for(i in pixels.indices)pixels[i]=(((pixels[i] ushr 24)*255/peak) shl 24) or 0x00ffffff
                mask.setPixels(pixels,0,w,0,0,w,h)
            }else canvas.drawRoundRect(RectF(bounds),radius,radius,paint)
            Shape(mask,Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)).also {shapes.put(key,it)}
        }
    }
    fun draw(canvas:Canvas,bounds:Rect,view:View,identity:android.graphics.drawable.Drawable,radius:Float,path:Path?=null,decorate:Boolean=true,alpha:Int=255,style:GlassStyle=GlassStyle.icons(),nativeShape:android.graphics.drawable.Drawable?=null,nativeMask:Bitmap?=null,pathFingerprint:Int?=null):Boolean {
        if(bounds.isEmpty)return false
        if(canvas.quickReject(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat()))return true
        IconGlassHook.registerMaterialView(view)
        val wallpaper=(if(style.folder)WallpaperHelper.getFolderWallpaper(view.context)else WallpaperHelper.getBlurredWallpaper(view.context)) ?: return false
        val shape=shape(bounds,radius,path,nativeShape,nativeMask,pathFingerprint)
        val world=Matrix()
        try {XposedHelpers.callMethod(view,"transformMatrixToGlobal",world)}catch(_:Throwable){return false}
        val screen=RectF(bounds);world.mapRect(screen)
        val metrics=view.resources.displayMetrics
        val source=WallpaperFootprint.source(wallpaper,metrics.widthPixels,metrics.heightPixels,screen)
        val save=if(alpha<255)canvas.saveLayerAlpha(RectF(bounds),alpha)else canvas.save()
        try {
            canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat()/shape.mask.width,bounds.height().toFloat()/shape.mask.height)
            val local=Rect(0,0,shape.mask.width,shape.mask.height)
            IconMaterialRenderer.draw(canvas,local,shape.mask,shape.empty,style.plateColor ?: PrefsHelper.iconPalette.background,
                style.opacity*255/100,!decorate,wallpaper,source,
                IconMaterialRenderer.Binding(view,identity,RectF(bounds),metrics.widthPixels,metrics.heightPixels),decorate,style) {target,rect,_ ->
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
