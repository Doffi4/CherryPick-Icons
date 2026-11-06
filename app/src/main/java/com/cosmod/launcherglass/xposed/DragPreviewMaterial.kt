package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Native drag previews are Picture snapshots of the source view
 * (DragPreviewProvider.createDrawable). On that software canvas a material
 * bakes a frozen wallpaper crop, and re-extracting an "icon" from the
 * composite inside the DragView changed rim, glyph and plate; Liquid
 * composites often failed extraction and stayed frozen.
 *
 * While the OEM records the snapshot, every material punches its footprint
 * out of the Picture and is remembered with its transform. The DragView then
 * draws the same materials live underneath the native snapshot, so labels,
 * badges, dots and every other native layer stay above them. The launcher
 * still owns the preview drawable, its motion and its lifetime.
 */
internal object DragPreviewMaterial {
    class Surface(val bounds:Rect,val radius:Float,val path:Path?,val decorate:Boolean,val alpha:Int,val style:GlassStyle,
                  val nativeShape:Drawable?,val nativeMask:Bitmap?,val pathFingerprint:Long?,val identity:Drawable,val fallback:Drawable?)
    /**
     * [nested]: an icon drawn by a child of the dragged view (items of a
     * multi-icon cluster). It keeps its resting wallpaper sample: only the
     * dragged composite itself lifts the scene below the DragView, and one
     * lifted backdrop per item both brightened the items and cost a scene
     * blur each per frame.
     */
    private class Element(val matrix:Matrix,val clip:RectF?,val alpha:Float,val bounds:Rect,
                          val icon:Drawable?,val iconAlpha:Int,val mini:Boolean,val surface:Surface?,val nested:Boolean=false)
    private class Capture(val source:View){
        var canvas:Canvas?=null
        var width=0
        var height=0
        val elements=ArrayList<Element>()
    }
    private val active=ThreadLocal<Capture?>()
    // Main thread only: preview drawable -> materials it must draw live.
    private val previews=WeakHashMap<Drawable,Capture>()
    private val punch=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT)}
    private val failures=HashSet<String>()
    private var logs=0
    private val main get()=Looper.myLooper()===Looper.getMainLooper()

    fun install(loader:ClassLoader){
        val recordingHook=object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                val capture=active.get() ?: return
                // The first Picture begun by createDrawable is the preview itself.
                if(capture.canvas!=null)return
                capture.canvas=param.result as? Canvas ?: return
                capture.width=(param.args.getOrNull(0) as? Int) ?: 0
                capture.height=(param.args.getOrNull(1) as? Int) ?: 0
            }
        }
        try {
            ModuleHooks.hookMethod(XposedHelpers.findMethodExact(Picture::class.java,"beginRecording",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType),recordingHook)
        }catch(t:Throwable){
            GlassLogger.e("DragPreview","Picture recording unavailable; drag previews keep the native snapshot",t)
            return
        }
        val createHook=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!main || !PrefsHelper.enabled)return
                val view=try {XposedHelpers.getObjectField(param.thisObject,"mView") as? View}catch(_:Throwable){null} ?: return
                param.setObjectExtra("priorDragCapture",active.get())
                param.setObjectExtra("dragCapture",true)
                active.set(Capture(view))
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("dragCapture")!=true)return
                val capture=active.get()
                active.set(param.getObjectExtra("priorDragCapture") as? Capture)
                if(capture==null || capture.elements.isEmpty() || param.hasThrowable())return
                val result=param.result as? Drawable ?: return
                if(capture.width<=0 || capture.height<=0)return
                previews[result]=capture
                if(logs++<8){
                    val icons=capture.elements.count {it.icon!=null}
                    GlassLogger.i("DragPreview","Live drag preview: ${capture.source.javaClass.simpleName}, ${capture.width}x${capture.height}, icons=$icons, surfaces=${capture.elements.size-icons}")
                }
            }
        }
        var hooks=0
        for(name in listOf("com.android.launcher3.graphics.DragPreviewProvider","com.android.launcher3.shortcuts.ShortcutDragPreviewProvider")){
            val type=XposedHelpers.findClassIfExists(name,loader) ?: continue
            for(method in type.declaredMethods){
                if(method.name=="createDrawable" && method.parameterTypes.isEmpty()){ModuleHooks.hookMethod(method,createHook);hooks++}
            }
        }
        GlassLogger.i("DragPreview","Installed: createDrawable hooks=$hooks")
    }

    /** True while [canvas] records the native drag preview Picture. */
    fun captures(canvas:Canvas):Boolean {
        val capture=active.get() ?: return false
        return capture.canvas!=null && capture.canvas===canvas
    }

    fun isPreview(drawable:Drawable)=main && previews.containsKey(drawable)

    @Suppress("DEPRECATION")
    private fun matrix(canvas:Canvas)=Matrix().also {canvas.getMatrix(it)}
    private fun clip(canvas:Canvas):RectF? {
        val rect=Rect()
        return if(canvas.getClipBounds(rect))RectF(rect)else null
    }
    /** Alpha that software drawChild applied between [owner] and the snapshot root. */
    private fun chain(owner:View?,source:View):Float {
        var alpha=1f
        var current=owner
        var depth=0
        while(current!=null && current!==source && depth<32){
            alpha*=current.alpha*current.transitionAlpha
            current=current.parent as? View
            depth++
        }
        return if(current===source)alpha.coerceIn(0f,1f)else 1f
    }

    private fun descends(view:View,ancestor:View):Boolean {
        var current=view.parent
        var depth=0
        while(current!=null && depth<32){
            if(current===ancestor)return true
            current=current.parent
            depth++
        }
        return false
    }

    fun captureIcon(canvas:Canvas,icon:Drawable,bounds:Rect,owner:View?,alpha:Int,mini:Boolean,mask:Bitmap):Boolean {
        val capture=active.get() ?: return false
        if(capture.canvas!==canvas || bounds.isEmpty)return false
        val nested=owner!=null && owner!==capture.source && descends(owner,capture.source)
        capture.elements.add(Element(matrix(canvas),clip(canvas),chain(owner,capture.source),Rect(bounds),icon,alpha.coerceIn(0,255),mini,null,nested))
        // Native layers recorded earlier stay hidden under the live plate,
        // exactly as they are hidden under the resting icon.
        punch.alpha=alpha.coerceIn(0,255)
        canvas.drawBitmap(WallpaperHelper.forCanvas(mask,canvas),null,bounds,punch)
        punch.alpha=255
        return true
    }

    fun captureSurface(canvas:Canvas,owner:View,surface:Surface):Boolean {
        val capture=active.get() ?: return false
        if(capture.canvas!==canvas || surface.bounds.isEmpty)return false
        capture.elements.add(Element(matrix(canvas),clip(canvas),chain(owner,capture.source),Rect(surface.bounds),null,255,false,surface))
        punch.alpha=surface.alpha.coerceIn(0,255)
        try {
            val bounds=surface.bounds
            val path=surface.path
            val mask=surface.nativeMask
            val shape=surface.nativeShape
            when {
                mask!=null -> {
                    val bitmap=WallpaperHelper.forCanvas(mask,canvas)
                    canvas.drawBitmap(bitmap,Rect(1,1,bitmap.width-1,bitmap.height-1),RectF(bounds),punch)
                }
                path!=null && !path.isEmpty -> canvas.drawPath(path,punch)
                shape!=null -> {
                    val save=canvas.saveLayer(RectF(bounds),punch)
                    val old=Rect(shape.bounds)
                    try {shape.bounds=bounds;shape.draw(canvas)}finally{shape.bounds=old;canvas.restoreToCount(save)}
                }
                else -> canvas.drawRoundRect(RectF(bounds),surface.radius,surface.radius,punch)
            }
        }finally{punch.alpha=255}
        return true
    }

    /**
     * Draws the live materials of a drag preview below its native snapshot.
     * The caller lets the native drawable draw the snapshot afterwards.
     */
    fun replay(drawable:Drawable,canvas:Canvas,bounds:Rect,owner:View?){
        val capture=previews[drawable] ?: return
        if(bounds.isEmpty || capture.width<=0 || capture.height<=0)return
        val sx=bounds.width().toFloat()/capture.width
        val sy=bounds.height().toFloat()/capture.height
        val live=PrefsHelper.enabled && owner!=null
        val outer=drawable.alpha.coerceIn(0,255)/255f
        for(element in capture.elements){
            val save=canvas.save()
            try {
                canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
                canvas.scale(sx,sy)
                canvas.concat(element.matrix)
                element.clip?.let {canvas.clipRect(it)}
                val alpha=element.alpha*outer
                val icon=element.icon
                val surface=element.surface
                if(icon!=null){
                    val iconAlpha=(element.iconAlpha*alpha).roundToInt().coerceIn(0,255)
                    if(!live || !IconGlassHook.drawDragIcon(icon,canvas,element.bounds,owner!!,iconAlpha,element.mini,
                            lift=!element.mini && !element.nested))
                        IconGlassHook.drawNativeIcon(icon,canvas,element.bounds,alpha)
                }else if(surface!=null){
                    val surfaceAlpha=(surface.alpha*alpha).roundToInt().coerceIn(0,255)
                    @Suppress("DEPRECATION")
                    val local=RectF(surface.bounds).also {Matrix().apply {canvas.getMatrix(this)}.mapRect(it)}
                    if(!live || !SurfaceMaterial.draw(canvas,surface.bounds,owner!!,surface.identity,surface.radius,surface.path,surface.decorate,
                            surfaceAlpha,surface.style,surface.nativeShape,surface.nativeMask,surface.pathFingerprint,local=local))
                        drawNativeSurface(surface,canvas,surfaceAlpha)
                }
            }catch(t:Throwable){
                if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("DragPreview","Live drag material failed",t)
            }finally{canvas.restoreToCount(save)}
        }
    }

    private fun drawNativeSurface(surface:Surface,canvas:Canvas,alpha:Int){
        val fallback=surface.fallback ?: return
        val save=if(alpha<255)canvas.saveLayerAlpha(RectF(surface.bounds),alpha)else canvas.save()
        val old=Rect(fallback.bounds)
        try {fallback.bounds=surface.bounds;fallback.draw(canvas)}finally{fallback.bounds=old;canvas.restoreToCount(save)}
    }
}
