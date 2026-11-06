package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Build
import android.view.View
import com.cosmod.launcherglass.util.GlassVolume
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XposedHelpers

/** Dock-specific, direct-resolution path rendering; no nested sampler RenderNode. */
internal class DockRenderer {
    private val plate=RenderNode("CherryPick.dockPlate").apply {setUseCompositingLayer(true,null)}
    private var key:List<Any?>?=null
    private var lens:LiquidGlassShader?=null
    private var lastWallpaper:Bitmap?=null
    private val solid:Shader=LinearGradient(0f,0f,1f,0f,Color.WHITE,Color.WHITE,Shader.TileMode.CLAMP)
    fun reset(){lastWallpaper=null;key=null;plate.discardDisplayList()}
    fun draw(canvas:Canvas, view:View, rect:Rect, path:Path?, radius:Float, alpha:Float):Boolean {
        val style=DockSettings.style().copy(extendedLens=true)
        val w=rect.width();val h=rect.height()
        if(w<=0 || h<=0)return false
        val current=if(style.radius>0)WallpaperHelper.getDockWallpaper(view.context)?.takeUnless {it.isRecycled} else null
        val wallpaper=if(style.radius<=0)null else (current ?: lastWallpaper?.takeUnless {it.isRecycled}) ?: return false
        if(current!=null && !current.isRecycled)lastWallpaper=current
        val source=wallpaper?.let {
            val world=Matrix();XposedHelpers.callMethod(view,"transformMatrixToGlobal",world)
            val screen=RectF(rect);world.mapRect(screen)
            val dm=view.resources.displayMetrics
            WallpaperFootprint.source(it,dm.widthPixels,dm.heightPixels,screen)
        }
        val shape=path ?: Path().apply {addRoundRect(RectF(rect),radius,radius,Path.Direction.CW)}
        if(canvas.isHardwareAccelerated){
            val next=listOf(wallpaper,wallpaper?.generationId,Rect(rect),source,style,radius,
                PrefsHelper.gpuEffects,PrefsHelper.gradientOpacity,PrefsHelper.iconStroke,PrefsHelper.strokeOpacity,
                SurfaceMaterial.geometryRevision)
            if(key!=next || !plate.hasDisplayList()){
                plate.setPosition(0,0,w,h)
                val recording=plate.beginRecording(w,h)
                try {
                    recording.translate(-rect.left.toFloat(),-rect.top.toFloat())
                    drawPlate(recording,rect,shape,radius,1f,wallpaper,source,style)
                }finally{plate.endRecording()}
                key=next
            }
            plate.alpha=alpha.coerceIn(0f,1f)
            val saved=canvas.save()
            try {canvas.translate(rect.left.toFloat(),rect.top.toFloat());canvas.drawRenderNode(plate)}
            finally{canvas.restoreToCount(saved)}
        }else drawPlate(canvas,rect,shape,radius,alpha,wallpaper,source,style)
        return true
    }
    private fun drawPlate(canvas:Canvas,rect:Rect,shape:Path,radius:Float,alpha:Float,wallpaper:Bitmap?,source:RectF?,style:GlassStyle){
        val w=rect.width();val h=rect.height()
        val saved=if(alpha<.999f)canvas.saveLayerAlpha(RectF(rect),(alpha.coerceIn(0f,1f)*255).toInt()) else canvas.save()
        try {
            canvas.clipPath(shape)
            canvas.translate(rect.left.toFloat(),rect.top.toFloat())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            if(wallpaper!=null && source!=null){
                val image=WallpaperHelper.forCanvas(wallpaper,canvas)
                val localRect=RectF(0f,0f,w.toFloat(),h.toFloat())
                val gpu=if(style.liquid && PrefsHelper.gpuEffects && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT>=33){
                    val l=lens ?: LiquidGlassShader.create().also {lens=it}
                    l?.update(image,solid,source,w,h,style.filter,(radius/minOf(w,h)).coerceIn(0f,.5f),style)
                }else null
                paint.shader=gpu ?: GlassGeometry.wallpaperShader(image).apply {
                    setLocalMatrix(Matrix().apply {setRectToRect(source,localRect,Matrix.ScaleToFit.FILL)})
                }
                if(gpu==null)paint.colorFilter=style.filter
                canvas.drawRect(localRect,paint)
                paint.shader=null;paint.colorFilter=null
            }
            paint.color=(style.opacity.coerceIn(0,100)*255/100 shl 24) or ((style.plateColor ?: PrefsHelper.plateColor(SurfaceTone.base())) and 0xffffff)
            canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
            if(PrefsHelper.gradientOpacity>0){
                paint.color=Color.WHITE;paint.shader=GlassVolume.shader(h.toFloat(),PrefsHelper.gradientOpacity)
                canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint);paint.shader=null
            }
        } finally {canvas.restoreToCount(saved)}
        // Draw a directional antialiased contour directly at device resolution.
        if(PrefsHelper.iconStroke && PrefsHelper.strokeOpacity>0 && alpha>0f){
            val edge=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                this.style=Paint.Style.STROKE;strokeWidth=2f
                val a=(PrefsHelper.strokeOpacity.coerceIn(0,100)*.8f*alpha).toInt().coerceIn(0,255)
                shader=LinearGradient(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat(),
                    intArrayOf(Color.argb(a,255,255,255),Color.argb(a/5,255,255,255),Color.argb(a/2,0,0,0)),null,Shader.TileMode.CLAMP)
            }
            val save=canvas.save();canvas.clipPath(shape);canvas.drawPath(shape,edge);canvas.restoreToCount(save)
        }
    }
}
