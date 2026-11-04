package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.*
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Native shortcut clusters keep their individual items, hit targets and transitions. */
internal object MultiIconMaterialHook {
    private fun enabled()=PrefsHelper.enabled && PrefsHelper.blurEnabled
    private class GridPlate(view:View,val native:Drawable):Drawable(){
        private val owner=WeakReference(view)
        private val clip=Path()
        private val previous=Rect()
        private var radius=Float.NaN
        private var spans=0
        private var geometry=-1L
        private var fingerprint=0
        override fun draw(canvas:Canvas){
            val view=owner.get() ?: return native.draw(canvas)
            if(!enabled()){native.draw(canvas);return}
            try {drawMaterial(canvas,view)}catch(t:Throwable){
                if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("MultiIcons","Normal shortcut material unavailable",t)
                native.draw(canvas)
            }
        }
        private fun drawMaterial(canvas:Canvas,view:View){
            val rect=(NativeIconAccess.field(view,"mRect") as? Rect)?.takeUnless {it.isEmpty} ?: bounds
            val nativeRadius=(NativeIconAccess.field(view,"mRadius") as? Number)?.toFloat() ?: 0f
            val container=NativeIconAccess.field(view,"attachedShortcutContainer") as? View
            val info=container?.tag ?: view.tag
            val sx=(info?.let {NativeIconAccess.call(it,"getSpanX")} as? Number)?.toInt() ?: 1
            val sy=(info?.let {NativeIconAccess.call(it,"getSpanY")} as? Number)?.toInt() ?: 1
            if(previous!=rect || radius!=nativeRadius || spans!=sx*100+sy || geometry!=SurfaceMaterial.geometryRevision){
                clip.reset()
                val manager=XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager",view.context.classLoader)
                XposedHelpers.callStaticMethod(manager,"createIconFolderClipPath",clip,RectF(rect),nativeRadius,nativeRadius,sx,sy)
                previous.set(rect);radius=nativeRadius;spans=sx*100+sy;geometry=SurfaceMaterial.geometryRevision
                fingerprint=clip.approximate(1f).contentHashCode()
            }
            if(!SurfaceMaterial.draw(canvas,rect,view,this,nativeRadius,clip,alpha=native.alpha,
                style=GlassStyle.icons().copy(extendedLens=true),pathFingerprint=fingerprint))native.draw(canvas)
        }
        override fun onBoundsChange(bounds:Rect){native.bounds=bounds}
        override fun setAlpha(alpha:Int){native.alpha=alpha;invalidateSelf()}
        override fun getAlpha()=native.alpha
        override fun setColorFilter(filter:ColorFilter?){native.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Java")override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    private val failures=HashSet<String>()
    private val hosts=WeakHashMap<View,Boolean>()
    private val grids=WeakHashMap<View,GridPlate>()
    fun onSettingsChanged(){
        if(!enabled()){
            for((view,plate) in grids.strongSnapshot()){
                if(NativeIconAccess.field(view,"mBackDrawable")===plate)XposedHelpers.setObjectField(view,"mBackDrawable",plate.native)
                view.invalidate()
            }
            grids.clear()
        }
        for(view in hosts.keys.weakSnapshot()){
            if(enabled())try {FolderMaterialHook.attachShortcut(view)}catch(t:Throwable){
                if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("MultiIcons","Native shortcut material unavailable",t)
            }
            view.invalidate()
        }
    }
    private fun prepare(view:View){
        if(!enabled())return
        // View.background is the selection highlight. GridRecyclerView owns
        // the normal plate; OplusPreviewBackground handles hover/resize only.
        hosts[view]=true
        FolderMaterialHook.attachShortcut(view)
    }
    private fun bindItems(view:View,depth:Int=0){
        if(depth>5)return
        IconGlassHook.bindSpecialView(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)bindItems(view.getChildAt(i),depth+1)
    }
    fun install(loader:ClassLoader){
        val type=XposedHelpers.findClassIfExists("com.android.launcher3.viewcontainer.ShortcutContainer",loader) ?: return
        val grid=XposedHelpers.findClassIfExists("com.android.launcher3.viewcontainer.GridRecyclerView",loader)
        if(grid!=null)ModuleHooks.hookAllMethods(grid,"draw",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!enabled())return
                val view=param.thisObject as? View ?: return
                val native=NativeIconAccess.field(view,"mBackDrawable") as? Drawable ?: return
                if(native is GridPlate)return
                val plate=GridPlate(view,native).apply {bounds=Rect(native.bounds);callback=view}
                grids[view]=plate
                XposedHelpers.setObjectField(view,"mBackDrawable",plate)
                GlassLogger.i("MultiIcons","Normal shortcut background installed: ${native.javaClass.name}")
            }
        })
        for(method in listOf("onLayout","dispatchDraw"))ModuleHooks.hookAllMethods(type,method,object:XC_MethodHook(){
            private fun update(param:MethodHookParam){
                if(!enabled())return
                val view=param.thisObject as? View ?: return
                try {prepare(view);bindItems(view)}catch(t:Throwable){
                    if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("MultiIcons","Native shortcut material unavailable",t)
                }
            }
            override fun beforeHookedMethod(param:MethodHookParam){if(method=="dispatchDraw")update(param)}
            override fun afterHookedMethod(param:MethodHookParam){if(method=="onLayout")update(param)}
        })
    }
}
