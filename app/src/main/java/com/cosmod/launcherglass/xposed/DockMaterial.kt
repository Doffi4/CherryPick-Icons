package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Looper
import android.graphics.Matrix
import android.view.ViewTreeObserver
import com.cosmod.launcherglass.util.WallpaperHelper
import com.cosmod.launcherglass.util.LiveBlurEngine
import java.lang.ref.WeakReference
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Glass dock behind the hotseat icons (opt-in).
 *
 * Drawn from OplusHotseat.onDraw, below the icons, with the same live
 * wallpaper material as the icons. Paddings and corner radius are percentages
 * of the icon size, so the proportions stay the same with any icon size or
 * display density. The dock is symmetric about the hotseat centre and
 * follows the native row while an item is dragged. While it is shown, the native dock plate (if the launcher has
 * one) is skipped for that frame.
 */
internal object DockMaterial {
    private const val NATIVE="cherrypick.nativeDock"
    private class Dock {
        val renderer=DockRenderer()
        var target=Rect()
        var observing:ViewTreeObserver?=null
        var listener:ViewTreeObserver.OnPreDrawListener?=null
        var signature:List<Any?>?=null
        val rect=Rect()
        val pathRect=Rect()
        var pathRadius=Float.NaN
        var path:Path?=null
    }
    private val docks=WeakHashMap<View,Dock>()
    private val iconMethods=HashMap<Class<*>,Method?>()
    private var smoothUnavailable=false
    private var weight=Float.NaN
    private val failures=HashSet<String>()
    private var logged=false

    fun enabled()=PrefsHelper.enabled && PrefsHelper.blurEnabled && PrefsHelper.dockEnabled

    fun install(loader:ClassLoader){
        val type=XposedHelpers.findClassIfExists("com.android.launcher3.OplusHotseat",loader)
        if(type==null){GlassLogger.i("Dock","OplusHotseat not found; dock unavailable");return}
        if(type.declaredFields.none {it.name=="mDockerBackgroundDrawable"}){
            // shortcut: Android 16 draws the plate as the row background; support it before enabling this renderer there.
            GlassLogger.i("Dock","Native row-background dock retained on this launcher")
            return
        }
        ModuleHooks.hookAllMethods(type,"onDraw",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? ViewGroup ?: return
                val dock=docks.getOrPut(view){Dock()}
                if(!enabled())return
                observe(view,dock)
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                try {
                    if(!draw(view,canvas,dock))return
                    // One dock: skip the native plate for this frame.
                    val native=NativeIconAccess.field(view,"mDockerBackgroundDrawable") as? Drawable ?: return
                    XposedHelpers.setObjectField(view,"mDockerBackgroundDrawable",null)
                    param.setObjectExtra(NATIVE,native)
                }catch(t:Throwable){fail(t)}
            }
            override fun afterHookedMethod(param:MethodHookParam){
                val native=param.getObjectExtra(NATIVE) as? Drawable ?: return
                try {
                    if(NativeIconAccess.field(param.thisObject,"mDockerBackgroundDrawable")==null)
                        XposedHelpers.setObjectField(param.thisObject,"mDockerBackgroundDrawable",native)
                }catch(t:Throwable){fail(t)}
            }
        })
        // Children move without invalidating the hotseat itself: re-record the
        // dock when the row geometry changes (icon added, removed, re-centred).
        ModuleHooks.hookAllMethods(type,"onLayout",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? ViewGroup ?: return
                val dock=docks[view] ?: return
                if(!enabled())return
                try {
                    val next=geometry(view) ?: return
                    if(next!=dock.target)view.invalidate()
                }catch(t:Throwable){fail(t)}
            }
        })
    }

    fun onSettingsChanged(){
        if(Looper.myLooper()!==Looper.getMainLooper()){android.os.Handler(Looper.getMainLooper()).post {onSettingsChanged()};return}
        for(view in docks.keys.toList()){
            docks[view]?.apply {
                rect.setEmpty();target.setEmpty();renderer.reset();signature=null
                if(!enabled()){
                    listener?.let {if(observing?.isAlive==true)observing?.removeOnPreDrawListener(it)}
                    listener=null;observing=null
                }
            }
            view.invalidate()
        }
    }

    private fun rowAlpha(view:ViewGroup):Float {
        val row=NativeIconAccess.field(view,"mShortcutsAndWidgets") as? ViewGroup ?: return 1f
        var alpha=0f
        for(i in 0 until row.childCount){val c=row.getChildAt(i);if(c.visibility==View.VISIBLE)alpha=maxOf(alpha,c.alpha)}
        return (alpha*row.alpha).coerceIn(0f,1f)
    }
    private fun observe(view:ViewGroup,dock:Dock){
        val observer=view.viewTreeObserver
        if(dock.observing===observer && observer.isAlive)return
        dock.listener?.let { if(dock.observing?.isAlive==true)dock.observing?.removeOnPreDrawListener(it) }
        val weak=WeakReference(view)
        val listener=ViewTreeObserver.OnPreDrawListener {
            val v=weak.get()
            if(v!=null && enabled())try {
                val matrix=Matrix();XposedHelpers.callMethod(v,"transformMatrixToGlobal",matrix)
                val values=FloatArray(9);matrix.getValues(values)
                val wall=if(!IconMaterialRenderer.clearGlass(DockSettings.style()))WallpaperHelper.getDockWallpaper(v.context) else null
                val signature=listOf(*values.toTypedArray(),System.identityHashCode(wall),wall?.generationId,
                    LiveBlurEngine.wallpaperScrollRatio,rowAlpha(v),DockSettings.style(),geometry(v))
                if(signature!=dock.signature){dock.signature=signature;v.invalidate()}
            }catch(_:Throwable){}
            true
        }
        observer.addOnPreDrawListener(listener);dock.observing=observer;dock.listener=listener
    }
    private fun draw(hotseat:ViewGroup,canvas:Canvas,dock:Dock):Boolean {
        // A hidden horizontal row must not expose the native plate during unlock/launch.
        val next=geometry(hotseat) ?: return rowAlpha(hotseat)==0f && NativeIconAccess.call(hotseat,"hasVerticalHotseat")!=true
        // Native spring translations already animate the row; a second animator lags behind it.
        dock.target.set(next);dock.rect.set(next)
        val rect=Rect(dock.rect)
        val radius=radius(rect);val path=path(hotseat,rect,radius,dock)
        val drawn=dock.renderer.draw(canvas,hotseat,rect,path,radius,rowAlpha(hotseat))
        if(drawn && !logged){logged=true;GlassLogger.i("Dock","Direct dock: bounds=$rect, radius=$radius, smooth=${path!=null}")}
        return drawn
    }

    private var iconSize=0f
    private fun radius(rect:Rect)=(iconSize*PrefsHelper.dockRadius/100f).coerceAtMost(minOf(rect.width(),rect.height())/2f).coerceAtLeast(0f)

    /** Dock bounds in hotseat coordinates, or null without visible icons. */
    private fun geometry(hotseat:ViewGroup):Rect? {
        if(NativeIconAccess.call(hotseat,"hasVerticalHotseat")==true)return null
        val container=NativeIconAccess.field(hotseat,"mShortcutsAndWidgets") as? ViewGroup ?: return null
        if(container.width<=0 || container.height<=0)return null
        val icons=RectF()
        val icon=Rect()
        val mapped=RectF()
        var size=0
        for(index in 0 until container.childCount){
            val child=container.getChildAt(index)
            if(child.visibility!=View.VISIBLE || !iconBounds(child,icon))continue
            size=maxOf(size,icon.width(),icon.height())
            mapped.set(icon)
            mapped.offset(container.left+child.x-container.scrollX,(container.top+child.top-container.scrollY).toFloat())
            icons.union(mapped)
        }
        if(icons.isEmpty || size<=0)return null
        iconSize=size.toFloat()
        val side=size*PrefsHelper.dockSidePadding/100f
        val vertical=size*PrefsHelper.dockVerticalPadding/100f
        val center=container.left+container.width/2f
        val cell=(NativeIconAccess.call(hotseat,"getCellWidth") as? Number)?.toFloat() ?: size.toFloat()
        val half=DockGeometry.halfWidth(PrefsHelper.dockAutoLength,center,icons.left,icons.right,
            hotseat.width.toFloat(),cell,size.toFloat(),side).coerceAtMost(minOf(center,hotseat.width-center))
        // OEM unlock/drawer RenderEffects clip to the hotseat layer. Keep the entire contour inside it.
        return Rect((center-half).roundToInt(),(icons.top-vertical).roundToInt().coerceAtLeast(0),
            (center+half).roundToInt(),(icons.bottom+vertical).roundToInt().coerceAtMost(hotseat.height))
    }

    /** The visible icon square of a hotseat item, in its own coordinates. */
    private fun iconBounds(child:View,out:Rect):Boolean {
        val method=iconMethods.getOrPut(child.javaClass){
            generateSequence(child.javaClass as Class<*>?){it.superclass}.mapNotNull {type ->
                type.declaredMethods.firstOrNull {(it.name=="getIconBounds" || it.name=="getPreviewBounds") &&
                    it.parameterTypes.size==1 && it.parameterTypes[0]==Rect::class.java}
            }.firstOrNull()?.apply {isAccessible=true}
        }
        out.setEmpty()
        if(method!=null)try {method.invoke(child,out)}catch(_:Throwable){out.setEmpty()}
        if(out.isEmpty){
            // Unknown item type: a centred square at the top of the cell.
            val side=minOf(child.width-child.paddingLeft-child.paddingRight,child.height-child.paddingTop-child.paddingBottom)
            if(side<=0)return false
            val left=(child.width-side)/2
            out.set(left,child.paddingTop,left+side,child.paddingTop+side)
        }
        return !out.isEmpty
    }

    /** ColorOS continuous ("smooth") corners like the icons; a plain round rect otherwise. */
    private fun path(view:View,rect:Rect,radius:Float,dock:Dock):Path? {
        if(smoothUnavailable)return null
        if(dock.path!=null && dock.pathRect==rect && dock.pathRadius==radius)return dock.path
        return try {
            val manager=XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager",view.context.classLoader)
            if(weight.isNaN()){
                val map=manager.declaredMethods.first {it.name=="mapWeight" && it.parameterCount in 1..2 &&
                    it.parameterTypes.all {arg -> arg==Float::class.javaPrimitiveType}}
                weight=((if(map.parameterCount==1)map.invoke(null,3f)else map.invoke(null,3f,1.1f)) as Number).toFloat()
            }
            val path=Path()
            XposedHelpers.callStaticMethod(manager,"createSmoothPath",path,RectF(rect),radius,weight,Path.Direction.CW)
            if(path.isEmpty)null else path.also {dock.path=it;dock.pathRect.set(rect);dock.pathRadius=radius}
        }catch(t:Throwable){
            smoothUnavailable=true
            GlassLogger.e("Dock","Smooth corners unavailable; using rounded rectangle",t)
            null
        }
    }

    private fun fail(t:Throwable){
        if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("Dock","Dock material failed",t)
    }
}
