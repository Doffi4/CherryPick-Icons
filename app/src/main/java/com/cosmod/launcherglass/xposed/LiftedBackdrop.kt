package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.View
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * Experimental live backdrop for a lifted (dragged) launcher element.
 *
 * The element's own display list never references launcher layers: its
 * wallpaper sample sits behind an indirection node that is emptied while a
 * host node, recorded only into the DragView's own display list, draws the
 * blurred layers below that DragView. A RenderNode cycle would therefore need
 * a layer below the DragView to reference the DragView itself, so a foreign
 * hardware draw of a DragView or of any of its ancestors disables the effect
 * for that drag. A launcher process that ends while a lift is active disables
 * the effect until the next reboot or module update.
 */
internal object LiftedBackdrop {
    private const val MARKER="cherrypick-lifted-backdrop.active"
    private const val LOCKOUT="cherrypick-lifted-backdrop.lockout"
    private val dragClassNames=listOf(
        "com.android.launcher3.dragndrop.DragView",
        "com.android.launcher3.dragndrop.LauncherDragView",
        "com.android.launcher3.dragndrop.OplusDragView")
    private val releaseMethods=listOf("detachContentView","reattachToPreviousParent","replaceContentWithDrawable",
        "remove","removeWithAnim","onDetachedFromWindow")
    private var dragViewClass:Class<*>?=null
    private var dragLayerClass:Class<*>?=null
    private var viewNode:Field?=null
    private var recordingCanvas:Field?=null
    private var canvasNode:Field?=null
    private var loader:ClassLoader?=null
    private var registered=false
    @Volatile private var installed=false
    @Volatile private var broken=false
    @Volatile private var lockedOut=false
    @Volatile private var prepared=false
    private var markerFile:File?=null
    private var markerActive=false
    // Main thread only. DragView -> ModuleHooks epoch at construction: a view
    // created before an observation gap cannot prove it was never drawn foreign.
    private val dragViews=WeakHashMap<View,Int>()
    private val poisoned=WeakHashMap<View,Boolean>()
    private var drawDepth=0
    private val main get()=Looper.myLooper()===Looper.getMainLooper()

    /** Remembers the launcher loader; hooks are registered only once the toggle is enabled. */
    fun install(loader:ClassLoader){
        if(Build.VERSION.SDK_INT>=33)this.loader=loader
    }
    /** Called after preferences load: registers the observers on first opt-in. */
    fun onSettingsChanged(){
        if(!PrefsHelper.enabled || !PrefsHelper.liftedBackdrop || registered || Looper.myLooper()!==Looper.getMainLooper())return
        val loader=loader ?: return
        registered=true
        register(loader)
    }
    private fun register(loader:ClassLoader){
        try {
            val drag=XposedHelpers.findClassIfExists(dragClassNames[0],loader)
            val layer=XposedHelpers.findClassIfExists("com.android.launcher3.views.BaseDragLayer",loader)
            if(drag==null || layer==null){
                GlassLogger.i("LiftedBackdrop","DragView/BaseDragLayer not found; lifted backdrop unavailable")
                return
            }
            viewNode=XposedHelpers.findField(View::class.java,"mRenderNode")
            recordingCanvas=XposedHelpers.findField(RenderNode::class.java,"mCurrentRecordingCanvas")
            canvasNode=XposedHelpers.findField(RecordingCanvas::class.java,"mNode")
            dragViewClass=drag;dragLayerClass=layer
            ModuleHooks.hookAllConstructors(drag,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? View ?: return
                    if(main && PrefsHelper.liftedBackdrop)dragViews[view]=ModuleHooks.epoch
                }
            })
            val hostHook=object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(!main)return
                    // OplusDragView.draw() calls DragView.draw(): place one host
                    // per recording, before the content and spring drawables.
                    if(drawDepth++!=0 || !PrefsHelper.liftedBackdrop)return
                    val view=param.thisObject as? View ?: return
                    val canvas=param.args.getOrNull(0) as? Canvas ?: return
                    try {
                        if(!canvas.isHardwareAccelerated || nodeOf(canvas)!==renderNode(view))return
                        IconMaterialRenderer.drawLiftHost(view,canvas,allowed() && permits(view))
                    }catch(t:Throwable){fail(t)}
                }
                override fun afterHookedMethod(param:MethodHookParam){
                    if(main && drawDepth>0)drawDepth--
                }
            }
            val releaseHook=object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? View ?: return
                    if(main)IconMaterialRenderer.releaseLifts(view)
                }
            }
            var hosts=0
            for(name in dragClassNames){
                val type=XposedHelpers.findClassIfExists(name,loader) ?: continue
                for(method in type.declaredMethods){
                    if(method.name=="draw" && method.parameterTypes.size==1 && method.parameterTypes[0]==Canvas::class.java){
                        ModuleHooks.hookMethod(method,hostHook);hosts++
                    }
                }
                for(method in releaseMethods)ModuleHooks.hookAllMethods(type,method,releaseHook)
            }
            if(hosts==0){
                GlassLogger.i("LiftedBackdrop","DragView.draw(Canvas) not found; lifted backdrop unavailable")
                return
            }
            // Observer only: a launcher layer that records a DragView (or one of
            // its ancestors) into a foreign display list could close a cycle.
            ModuleHooks.hookMethod(XposedHelpers.findMethodExact(View::class.java,"draw",Canvas::class.java),object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(!main || dragViews.isEmpty())return
                    val canvas=param.args.getOrNull(0) as? Canvas ?: return
                    if(!canvas.isHardwareAccelerated)return
                    try {
                        val view=param.thisObject as View
                        val target=nodeOf(canvas) ?: return
                        if(target===renderNode(view))return
                        onForeignDraw(view)
                    }catch(t:Throwable){fail(t)}
                }
            })
            installed=true
            GlassLogger.i("LiftedBackdrop","Installed: hosts=$hosts")
        }catch(t:Throwable){
            broken=true
            GlassLogger.e("LiftedBackdrop","Lifted backdrop unavailable",t)
        }
    }

    /** Reads the crash marker once, from the first pre-draw that has a context. */
    fun prepare(){
        if(prepared)return
        val context=IconGlassHook.appContext ?: return
        prepared=true
        try {
            val storage=context.createDeviceProtectedStorageContext()
            val dir=storage.noBackupFilesDir
            val marker=File(dir,MARKER)
            val lockout=File(dir,LOCKOUT)
            markerFile=marker
            val stamp=bootStamp(context)
            if(marker.exists()){
                marker.delete()
                lockout.writeText(stamp)
                GlassLogger.i("LiftedBackdrop","Previous launcher process ended during a lifted backdrop; disabled until reboot or module update")
            }
            if(lockout.exists()){
                lockedOut=try {lockout.readText()==stamp}catch(_:Throwable){false}
                if(!lockedOut)lockout.delete()
                else GlassLogger.i("LiftedBackdrop","Disabled for this boot after an earlier failure ($stamp)")
            }
        }catch(t:Throwable){
            GlassLogger.e("LiftedBackdrop","Crash marker unavailable",t)
        }
    }
    private fun bootStamp(context:Context)=
        "${Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)}:${BuildConfig.VERSION_CODE}"

    fun allowed()=installed && prepared && !broken && !lockedOut && Build.VERSION.SDK_INT>=33 &&
        PrefsHelper.enabled && PrefsHelper.blurEnabled && PrefsHelper.gpuEffects && PrefsHelper.liftedBackdrop &&
        ModuleHooks.isActive && !ColorDepthEffect.finalPlate

    /** The DragView was observed from construction without a hook gap and never drawn foreign. */
    fun permits(dragView:View)=main && dragViews[dragView]==ModuleHooks.epoch && !poisoned.containsKey(dragView)

    fun isDragView(view:View)=dragViewClass?.isInstance(view)==true
    fun isDragLayer(view:View)=dragLayerClass?.isInstance(view)==true

    fun dragAncestor(view:View):View? {
        val type=dragViewClass ?: return null
        var current:View?=view
        var depth=0
        while(current!=null && depth<40){
            if(type.isInstance(current))return current
            current=current.parent as? View
            depth++
        }
        return null
    }

    fun renderNode(view:View)=viewNode?.get(view) as? RenderNode
    private fun nodeOf(canvas:Canvas)=(canvas as? RecordingCanvas)?.let {canvasNode?.get(it) as? RenderNode}

    /** True while [view] records its own RenderNode, not a foreign snapshot. */
    fun ownRecording(view:View):Boolean {
        val node=renderNode(view) ?: return false
        return recordingCanvas?.get(node)!=null
    }

    private fun onForeignDraw(view:View){
        for(dragView in dragViews.keys.weakSnapshot()){
            if(poisoned.containsKey(dragView))continue
            var current:View?=dragView
            var depth=0
            while(current!=null && depth<64){
                if(current===view){
                    poisoned[dragView]=true
                    GlassLogger.limited("LiftedBackdrop","foreign"){"Foreign draw of ${view.javaClass.name}; lifted backdrop off for this drag"}
                    IconMaterialRenderer.releaseLifts(dragView)
                    break
                }
                current=current.parent as? View
                depth++
            }
        }
    }

    fun onLiftCount(count:Int){
        val active=count>0
        if(active==markerActive)return
        markerActive=active
        val marker=markerFile ?: return
        try {if(active)marker.createNewFile()else marker.delete()}catch(_:Throwable){}
    }

    fun fail(t:Throwable){
        if(broken)return
        broken=true
        GlassLogger.e("LiftedBackdrop","Lifted backdrop disabled for this session",t)
        try {IconMaterialRenderer.releaseAllLifts()}catch(_:Throwable){}
    }
}
