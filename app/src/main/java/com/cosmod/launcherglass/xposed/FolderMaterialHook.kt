package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.function.BiConsumer

/** Replace the folder material producer, not just the pixels above OEM blur. */
internal object FolderMaterialHook {
    private data class State(val context:WeakReference<Context>,val view:WeakReference<View>,val activity:WeakReference<Any>)
    private val backgrounds=WeakHashMap<Any,State>()
    private val panels=WeakHashMap<ImageView,Drawable?>()
    private val main=Handler(Looper.getMainLooper())
    private val converted=WeakHashMap<Any,Boolean>()
    private val nativeShapes=android.util.LruCache<String,Path>(24)
    private val geometryLogs=HashSet<String>()
    private var geometryRevision=0L
    fun invalidateGeometry(){geometryRevision++;nativeShapes.evictAll()}
    private fun enabled()=PrefsHelper.enabled && PrefsHelper.blurEnabled && PrefsHelper.folderBlurEnabled
    private fun shortcut(view:View?)=view?.javaClass?.name=="com.android.launcher3.viewcontainer.ShortcutContainer"
    private fun enabled(view:View?)=PrefsHelper.enabled && PrefsHelper.blurEnabled && (shortcut(view) || PrefsHelper.folderBlurEnabled)
    private fun style(view:View)=if(shortcut(view))GlassStyle.icons().copy(extendedLens=true)else FolderSettings.style()
    private fun field(owner:Any,name:String):Any?=try {XposedHelpers.getObjectField(owner,name)}catch(_:Throwable){null}
    private fun nativePath(view:View,bounds:Rect,radius:Float,info:Any?,sx:Int,sy:Int):Path?=try {
        val loader=view.context.classLoader
        val type=when {sx==1 && sy==2 -> 15;sx==2 && sy==1 -> 16;sx==2 && sy==2 -> 1;else -> 2}
        val resolver=XposedHelpers.findClass("com.android.launcher3.uioverrides.states.blurdrawable.BlurTransitionDrawable",loader)
        val pair=XposedHelpers.callStaticMethod(resolver,"resolveCornerTypeAndWeight",type,info)!!
        val corner=XposedHelpers.callMethod(pair,"a")!!
        val weight=(XposedHelpers.callMethod(pair,"b") as Number).toFloat()
        val key="$type:${bounds.width()}:${bounds.height()}:$radius:$corner:$weight"
        val cached=nativeShapes[key] ?: run {
            // Only the OEM geometry helper is constructed: no capture producer,
            // render effect, animation subscription or native blur allocation.
            val wrapper=XposedHelpers.newInstance(XposedHelpers.findClass("com.android.launcher3.uioverrides.states.blurdrawable.GradientDrawableWrapper",loader)) as Drawable
            XposedHelpers.setIntField(wrapper,"mHostType",type)
            val utils=XposedHelpers.findClass("com.android.launcher3.uioverrides.states.blurdrawable.BlurUtils",loader)
            XposedHelpers.callMethod(wrapper,"setCornerType",XposedHelpers.callStaticMethod(utils,"toGradientStrokeCornerType",corner))
            XposedHelpers.callMethod(wrapper,"setConerWeight",weight)
            XposedHelpers.callMethod(wrapper,"setCornerRadius",radius)
            wrapper.setBounds(0,0,bounds.width(),bounds.height())
            if(geometryLogs.add("type:$type"))GlassLogger.i("FolderMaterial","OEM geometry: type=$type, inputRadius=$radius, outlineRadius=${XposedHelpers.getFloatField(wrapper,"mStrokeRadius")}, weight=$weight")
            Path(XposedHelpers.getObjectField(wrapper,"mStrokePath") as Path).also {if(!it.isEmpty)nativeShapes.put(key,it)}
        }
        Path(cached).apply {offset(bounds.left.toFloat(),bounds.top.toFloat())}.takeUnless {it.isEmpty}
    }catch(t:Throwable){
        if(geometryLogs.add(t.javaClass.name+":"+t.message))GlassLogger.e("FolderMaterial","OEM geometry unavailable; using native fallback clip",t)
        null
    }
    private class Material(val source:WeakReference<View>,val owner:WeakReference<Any>?,val fallback:Drawable?):Drawable(){
        private var opacity=255
        private var logged=false
        private val cachedBounds=Rect()
        private val cachedPath=Path()
        private var cachedRadius=Float.NaN
        private var cachedInfo:Any?=null
        private var cachedSpanX=0
        private var cachedSpanY=0
        private var cachedRevision=-1L
        private var pathFingerprint=0
        override fun draw(canvas:Canvas){
            val view=(callback as? View) ?: source.get() ?: return
            if(!enabled(source.get())) {fallback?.bounds=bounds;fallback?.draw(canvas);return}
            val native=owner?.get()
            val old=fallback?.bounds?.let(::Rect)
            val outline=Outline()
            try {fallback?.bounds=bounds;fallback?.getOutline(outline)}finally{if(old!=null)fallback?.bounds=old}
            val radius=try {native?.let {XposedHelpers.getFloatField(it,"mRadius")}}catch(_:Throwable){null}
                ?: outline.radius.coerceAtLeast(0f)
            val span=if(native!=null)field(native,"mCreateSpanXY") as? IntArray else null
            val info=source.get()?.tag
            val sx=(try {info?.let {XposedHelpers.callMethod(it,"getSpanX")} as? Int}catch(_:Throwable){null}) ?: span?.getOrNull(0) ?: 1
            val sy=(try {info?.let {XposedHelpers.callMethod(it,"getSpanY")} as? Int}catch(_:Throwable){null}) ?: span?.getOrNull(1) ?: 1
            val path=cachedPath
            if(cachedBounds!=bounds || cachedRadius!=radius || cachedInfo!==info || cachedSpanX!=sx || cachedSpanY!=sy || cachedRevision!=geometryRevision){
            path.reset()
            if(native!=null){
                nativePath(view,bounds,radius,info,sx,sy)?.let {path.set(it)}
            }
            @Suppress("UNCHECKED_CAST")
            val clip=field(view,"mSmoothClipPathFunction") as? BiConsumer<Path,RectF>
            if(path.isEmpty)try {clip?.accept(path,RectF(bounds))}catch(_:Throwable){}
            if(path.isEmpty && native!=null)try {
                val manager=XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager",view.context.classLoader)
                XposedHelpers.callStaticMethod(manager,"createIconFolderClipPath",path,RectF(bounds),radius,radius,sx,sy)
            }catch(_:Throwable){}
            cachedBounds.set(bounds);cachedRadius=radius;cachedInfo=info;cachedSpanX=sx;cachedSpanY=sy;cachedRevision=geometryRevision
            pathFingerprint=path.approximate(1f).contentHashCode()
            }
            if(!logged){logged=true;GlassLogger.i("FolderMaterial","Native shape: radius=$radius, path=${!path.isEmpty}, bounds=$bounds, callback=${callback?.javaClass?.simpleName}")}
            if(!SurfaceMaterial.draw(canvas,bounds,view,this,radius,path.takeUnless {it.isEmpty},alpha=opacity,style=style(source.get() ?: view),nativeShape=if(path.isEmpty)fallback else null,pathFingerprint=pathFingerprint)){
                fallback?.bounds=bounds;fallback?.draw(canvas)
            }
        }
        override fun setAlpha(alpha:Int){opacity=alpha;invalidateSelf()}
        override fun getAlpha()=opacity
        override fun setColorFilter(filter:ColorFilter?){}
        @Deprecated("Deprecated in Java")override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    private fun replace(owner:Any,view:View):Boolean {
        if(field(owner,"mBgDrawable") is Material)return true
        val controller=field(owner,"mBlurProp")
        // Retire the producer and animation subscription before dropping its
        // drawable. A hidden native blur would still spend GPU/capture work.
        if(controller!=null){
            XposedHelpers.callMethod(controller,"unRegisterBlurTransitionAnim")
            XposedHelpers.callMethod(controller,"recycle")
            XposedHelpers.setObjectField(owner,"mBlurProp",null)
        }
        val fallback=try {XposedHelpers.callMethod(owner,"getNormalDrawable",view.context) as? Drawable}catch(_:Throwable){null}
        XposedHelpers.setObjectField(owner,"mBgDrawable",Material(WeakReference(view),WeakReference(owner),fallback))
        GlassLogger.i("FolderMaterial","Custom plate installed; native controller retired=${controller!=null}")
        return true
    }
    fun attachShortcut(view:View){
        if(!enabled(view))return
        val background=NativeIconAccess.field(view,"previewBackground") ?: NativeIconAccess.call(view,"getPreviewBackground") ?: return
        if(field(background,"mBgDrawable") is Material)return
        val activity=NativeIconAccess.call(view,"getLauncher") ?: return
        backgrounds[background]=State(WeakReference(view.context),WeakReference(view),WeakReference(activity))
        if(replace(background,view))XposedHelpers.callMethod(background,"setBackground",view)
    }
    fun onSettingsChanged(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post {onSettingsChanged()};return}
        invalidateGeometry()
        for((background,state) in backgrounds.strongSnapshot()){
            val view=state.view.get() ?: continue
            val custom=field(background,"mBgDrawable") is Material
            if(custom==enabled(view))continue
            try {
                val context=state.context.get() ?: continue
                val activity=state.activity.get() ?: continue
                XposedHelpers.callMethod(background,"initBgDrawable",context,view,activity)
                view.invalidate()
                GlassLogger.i("FolderMaterial",if(enabled())"Native folder blur disabled" else "Native folder blur restored")
            }catch(t:Throwable){GlassLogger.e("FolderMaterial","Folder producer switch failed",t)}
        }
        for((view,original) in panels.strongSnapshot()){
            if(enabled() && view.background !is Material)view.background=Material(WeakReference(view),null,original)
            else if(!enabled() && view.background is Material)view.background=original
            view.invalidate()
        }
    }
    fun install(loader:ClassLoader){
        val type=XposedHelpers.findClassIfExists("com.android.launcher3.folder.OplusPreviewBackground",loader) ?: return
        XposedHelpers.findClassIfExists("com.android.launcher3.folder.FolderRoundImageView",loader)?.let {image ->
            ModuleHooks.hookAllMethods(image,"onDraw",object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? ImageView ?: return
                    val material=view.drawable as? Material ?: return
                    val canvas=param.args.firstOrNull() as? Canvas ?: return
                    if(!enabled(material.source.get()))return
                    // The non-blur ImageView branch would clip our exact native
                    // contour a second time using the older fallback shape.
                    material.setBounds(0,0,view.width,view.height)
                    if(field(view,"mDrawCoverDrawable")==true){
                        (field(view,"mCoverDrawable") as? Drawable)?.let {cover ->
                            cover.bounds=material.bounds;cover.draw(canvas);material.alpha=255-cover.alpha
                        }
                    }
                    material.draw(canvas)
                    param.result=null
                }
            })
        }
        ModuleHooks.hookAllMethods(type,"drawBackground",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(param.args.size<2)return
                val material=field(param.thisObject,"mBgDrawable") as? Material ?: return
                if(!enabled(material.source.get()))return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                val params=param.args.getOrNull(1) ?: return
                val view=field(param.thisObject,"mInvalidateDelegate") as? View ?: return
                try {
                    // Native hover/resize hides mBgView and enters this overload.
                    // Its stock new-blur branch accepts only BlurTransitionDrawable.
                    val bounds=XposedHelpers.callStaticMethod(type,"toConvertBgBounds",params,view) as? Rect ?: return
                    val radius=(XposedHelpers.callMethod(params,"getRadius") as? Number)?.toFloat() ?: return
                    val info=param.args.getOrNull(2) ?: view.tag
                    val sx=(info?.let {XposedHelpers.callMethod(it,"getSpanX")} as? Int) ?: 1
                    val sy=(info?.let {XposedHelpers.callMethod(it,"getSpanY")} as? Int) ?: 1
                    val path=nativePath(view,bounds,radius,info,sx,sy) ?: Path()
                    val manager=XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager",loader)
                    if(path.isEmpty)XposedHelpers.callStaticMethod(manager,"createIconFolderClipPath",path,RectF(bounds),radius,radius,sx,sy)
                    if(SurfaceMaterial.draw(canvas,bounds,view,material,radius,path,alpha=material.alpha,style=style(view))){
                        if(converted.put(param.thisObject,true)==null)GlassLogger.i("FolderMaterial","Native hover/resize material: bounds=$bounds, radius=$radius")
                        param.result=null
                    }
                }catch(t:Throwable){GlassLogger.e("FolderMaterial","Converted native material failed",t)}
            }
        })
        ModuleHooks.hookAllMethods(type,"initBgDrawable",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val context=param.args.getOrNull(0) as? Context ?: return
                val view=param.args.getOrNull(1) as? View ?: return
                val activity=param.args.getOrNull(2) ?: return
                backgrounds[param.thisObject]=State(WeakReference(context),WeakReference(view),WeakReference(activity))
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.hasThrowable())return
                val view=param.args.getOrNull(1) as? View ?: return
                if(!enabled(view))return
                try {
                    if(field(param.thisObject,"mBgDrawable") !is Material){
                        replace(param.thisObject,view)
                        XposedHelpers.callMethod(param.thisObject,"setBackground",view)
                    }
                }catch(t:Throwable){GlassLogger.e("FolderMaterial","Folder attachment failed",t)}
            }
        })
        ModuleHooks.hookAllMethods(type,"initBlurDrawable",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.args.getOrNull(1) as? View ?: return
                if(!enabled(view))return
                try {if(replace(param.thisObject,view))param.result=null}
                catch(t:Throwable){GlassLogger.e("FolderMaterial","Native producer retirement failed",t)}
            }
        })
        val folder=XposedHelpers.findClassIfExists("com.android.launcher3.folder.OplusFolder",loader)
        if(folder!=null)ModuleHooks.hookAllMethods(folder,"onFinishInflate",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                for(name in listOf("mContentBackground","mContentAnimationBackground")){
                    val view=field(param.thisObject,name) as? ImageView ?: continue
                    panels[view]=view.background
                    if(enabled())view.background=Material(WeakReference(view),null,view.background)
                }
            }
        })
        // Full-workspace blur belongs to the launcher transition controller.
        // Only folder plate producers above are replaced by our material.
    }
}
