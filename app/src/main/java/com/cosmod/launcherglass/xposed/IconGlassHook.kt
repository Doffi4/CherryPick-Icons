package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import com.cosmod.launcherglass.util.*
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Material-only integration. Native launcher owns visibility, timing and transforms. */
object IconGlassHook {
    @Volatile var appContext:Context?=null
    private val recording=ThreadLocal<Boolean>()
    internal fun isRecordingNative()=recording.get()==true
    private val drawingView=ThreadLocal<View?>()
    private val loadingView=ThreadLocal<View?>()
    private val previewTransform=ThreadLocal<Matrix?>()
    private val folderPreview=ThreadLocal<Boolean>()
    private val previewSizeLogs=HashSet<String>()
    private fun bindPreviewItem(params:Any,owner:View?,depth:Int=0){
        if(depth>3)return
        val item=NativeIconAccess.field(params,"item")
        val drawable=NativeIconAccess.field(params,"drawable") as? Drawable
        val container=drawable!=null && SpecialIconSupport.isContainer(drawable)
        if(container){
            bindPreviewContainer(drawable!!,owner,depth+1)
        }
        if(!container && item!=null && drawable!=null && SpecialIconSupport.bind(drawable,item,{recording.get()==true}) {icon,canvas -> drawMaterial(icon,canvas,icon.bounds)}){
            if(owner!=null){hosts[drawable]=WeakReference(owner);registerLiveView(owner)}
        }
        val nested=NativeIconAccess.call(params,"getCurrentStackedParams") as? Iterable<*>
        nested?.forEach {if(it!=null)bindPreviewItem(it,owner,depth+1)}
    }
    private fun bindPreviewContainer(drawable:Drawable,owner:View?,depth:Int=0) {
        if(depth>4)return
        (NativeIconAccess.field(drawable,"mItemParamsList") as? Iterable<*>)?.forEach {if(it!=null)bindPreviewItem(it,owner,depth+1)}
        for(name in listOf("mPreviewItemsPram","mLastPreviewItemsPram")) {
            NativeIconAccess.field(drawable,name)?.let {bindPreviewItem(it,owner,depth+1)}
        }
    }
    private data class FloatingMaterial(val source:Drawable,val native:Drawable,val palette:IconPalette,val revision:Long,val material:Drawable)
    private val floatingMaterials=WeakHashMap<View,FloatingMaterial>()
    private val hosts=WeakHashMap<Drawable,WeakReference<View>>()
    private val liveViews=WeakHashMap<View,Boolean>()
    private var liveSnapshot=emptyList<WeakReference<View>>()
    private var liveSnapshotDirty=true
    private fun registeredViews():List<WeakReference<View>> {
        if(liveSnapshotDirty){liveSnapshot=liveViews.keys.map(::WeakReference);liveSnapshotDirty=false}
        return liveSnapshot
    }
    private val liveRoots=WeakHashMap<View,ViewTreeObserver.OnPreDrawListener>()
    // Passive counters only: never create a timer or a frame for diagnostics.
    private var idleSince=0L
    private var idlePreDraws=0
    private var idleInvalidations=0
    private var idleRestorations=0
    private var idleFrameRequests=0
    private var idleDeferredSamples=0
    internal fun noteDeferredSampleFrame(){if(!LiveBlurEngine.isMoving)idleDeferredSamples++}
    private fun reportIdleFrame(recovered:Int) {
        if(LiveBlurEngine.isMoving || !LiveBlurEngine.isResumed){
            idleSince=0L;idlePreDraws=0;idleInvalidations=0;idleRestorations=0
            idleFrameRequests=0;idleDeferredSamples=0
            return
        }
        val now=android.os.SystemClock.uptimeMillis()
        if(idleSince==0L)idleSince=now
        idlePreDraws++
        idleRestorations+=recovered
        if(now-idleSince>=10_000L){
            GlassLogger.i("IdleFrames","${now-idleSince}ms preDraw=$idlePreDraws moduleInvalidations=$idleInvalidations restored=$idleRestorations wallpaperRequests=$idleFrameRequests deferredSamples=$idleDeferredSamples")
            idleSince=now;idlePreDraws=0;idleInvalidations=0;idleRestorations=0;idleFrameRequests=0;idleDeferredSamples=0
        }
    }
    private fun registerLiveView(view:View){
        if(Looper.myLooper()!=Looper.getMainLooper()){view.post {registerLiveView(view)};return}
        if(liveViews.put(view,true)==null)liveSnapshotDirty=true
        val root=view.rootView
        if(!root.isAttachedToWindow)return
        if(liveRoots.containsKey(root))return
        val weak=WeakReference(root)
        val listener=ViewTreeObserver.OnPreDrawListener {
            val current=weak.get()
            var recovered=0
            if(current!=null && PrefsHelper.enabled && PrefsHelper.blurEnabled) {
                IconMaterialRenderer.beginFrame()
                // OEM trim/snapshot teardown also discards nested nodes in
                // static mode, sometimes before the resume callback arrives.
                // Restore those lists without changing the crop or FPS clock.
                recovered=recoverDiscardedIcons(current)
                // Lifted backdrops follow this frame's final DragView transform.
                LiftedBackdrop.prepare()
                IconMaterialRenderer.updateLifted(current)
            }
            if(current!=null && PrefsHelper.enabled && PrefsHelper.blurEnabled &&
                PrefsHelper.blurMode==com.cosmod.launcherglass.ui.SettingsProvider.BLUR_MODE_LIVE &&
                (LiveBlurEngine.isResumed || NativeSurfaceLive.active)) {
                // Animation/scroll transforms are final now; sample the same
                // position that this frame will present, not the previous tick.
                android.os.Trace.beginSection("CherryPick.sampleBeforeDraw")
                try {invalidateActiveIcons(onlyChangedDrawerSamples=true,beforeDraw=current)}finally{android.os.Trace.endSection()}
            }
            if(current!=null && PrefsHelper.enabled && PrefsHelper.blurEnabled)reportIdleFrame(recovered)
            true
        }
        liveRoots[root]=listener;root.viewTreeObserver.addOnPreDrawListener(listener)
    }
    internal fun registerMaterialView(view:View){registerLiveView(view)}
    internal fun onModuleDisabled(){
        for((root,listener) in liveRoots.strongSnapshot()){
            if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(listener)
            root.invalidate()
        }
        liveRoots.clear()
        for(reference in registeredViews())reference.get()?.invalidate()
        liveViews.clear();liveSnapshot=emptyList();liveSnapshotDirty=true
        ForeignDraw.reset()
        IconMaterialRenderer.clear();DynamicIconForeground.clear()
    }
    internal fun recoverDiscardedIcons(root:View):Int {
        var recovered=0
        for(reference in registeredViews()){
            val view=reference.get() ?: continue
            if(view.rootView!==root || !view.isAttachedToWindow || !view.isShown)continue
            if(IconMaterialRenderer.restoreDiscardedSamples(view)){view.invalidate();recovered++}
        }
        return recovered
    }
    fun requestLiveFrame(){
        if(!LiveBlurEngine.isMoving)idleFrameRequests++
        liveRoots.keys.weakSnapshot().filter {it.isAttachedToWindow && it.isShown}.forEach {it.postInvalidateOnAnimation()}
    }
    private val anchors=WeakHashMap<Bitmap,Anchor>()
    private val viewAnchors=WeakHashMap<View,Anchor>()
    private val nativeDrawables=WeakHashMap<Drawable,Boolean>()
    private val rawFrames=WeakHashMap<Drawable,RawFrame>()
    private data class PreviewStamp(val revision:Long,val wallpaper:Bitmap?,val x:Int,val y:Int)
    private val previews=WeakHashMap<Drawable,PreviewStamp>()
    private val previewNext=WeakHashMap<Drawable,Long>()
    private var revision=0L
    private var transitionDrawLogs=0
    private var scrollDrawLogs=0
    private val preparationFailures=HashSet<String>()
    private data class Anchor(val x:Float,val y:Float,val screenW:Int,val screenH:Int,val folder:Boolean,val drawer:Boolean,val iconW:Float,val iconH:Float)
    private data class RawFrame(val bitmap:Bitmap,val tick:Long,val generation:Int,val width:Int,val height:Int)
    private fun hook(action:(XC_MethodHook.MethodHookParam)->Unit)=object:XC_MethodHook(){
        override fun beforeHookedMethod(param:MethodHookParam){
            if(PrefsHelper.enabled && recording.get()!=true)try {action(param)}
            catch(t:Throwable){if(preparationFailures.add(t.javaClass.name+":"+t.message))GlassLogger.e("MaterialEngine","Material preparation failed",t)}
        }
    }
    private fun host(drawable:Drawable):View? {
        (drawingView.get() ?: loadingView.get())?.let {hosts[drawable]=WeakReference(it);registerLiveView(it);return it}
        var callback=drawable.callback
        repeat(8){
            if(callback is View){
                val view=callback as View
                hosts[drawable]=WeakReference(view);registerLiveView(view)
                return view
            }
            callback=(callback as? Drawable)?.callback
        }
        return hosts[drawable]?.get()?.takeIf {it.isAttachedToWindow}
    }
    private fun anchor(view:View,bounds:Rect):Anchor {
        val previous=viewAnchors[view]
        var transformNode:View?=view
        var scaled=false
        repeat(16){
            val current=transformNode ?: return@repeat
            if(kotlin.math.abs(current.scaleX-1f)>.002f || kotlin.math.abs(current.scaleY-1f)>.002f)scaled=true
            transformNode=current.parent as? View
        }
        val transition=LiveBlurEngine.isAppLaunchInProgress || !LiveBlurEngine.isResumed
        // Freeze only the native surface's own footprint. Ordinary workspace
        // draws must sample their CURRENT position during page transforms.
        if(NativeSurfaceLive.materialSource===view && previous!=null)return previous
        val original=try {XposedHelpers.callMethod(view,"getIcon") as? Drawable}catch(_:Throwable){null}
        val originalBitmap=try {original?.let {XposedHelpers.getObjectField(it,"mBitmap") as? Bitmap}}catch(_:Throwable){null}
        val expected=originalBitmap?.width ?: original?.intrinsicWidth?.takeIf {it>0} ?: bounds.width()
        val iconWidth=expected.coerceAtMost((view.width-view.paddingLeft-view.paddingRight).coerceAtLeast(1))
        val iconHeight=(originalBitmap?.height ?: expected).coerceAtMost(iconWidth)
        val location=IntArray(2);view.getLocationOnScreen(location)
        var folder=false;var drawer=false;var node:View?=view
        repeat(16){
            val current=node ?: return@repeat
            val name=current.javaClass.name
            if(name.contains("folder.") || name.contains("FolderIcon"))folder=true
            if(name.contains("allapps.") || name.contains("AllApps"))drawer=true
            node=current.parent as? View
        }
        val metrics=view.resources.displayMetrics
        return Anchor((location[0]+((view.width-iconWidth)/2).coerceAtLeast(0)).toFloat(),
            (location[1]+view.paddingTop).toFloat(),metrics.widthPixels,metrics.heightPixels,folder,drawer,iconWidth.toFloat(),iconHeight.toFloat())
            .also {if(!transition && !scaled)viewAnchors[view]=it}
    }
    fun invalidateFolderPreviews(){revision++}
    fun invalidateShaders(){IconMaterialRenderer.clear();GlyphMaterialRenderer.clear();revision++}
    private data class RepaintStamp(val x:Int,val y:Int,val w:Int,val h:Int,val scroll:Int,val wallpaper:Bitmap?,val revision:Long)
    private val repaintStamps=WeakHashMap<View,RepaintStamp>()
    fun onNativeIconReturned(view:View?) {
        if(view==null)return
        val refresh=Runnable {
            repaintStamps.remove(view)
            view.invalidate()
            // Snapshot teardown can evict samples belonging to other icons,
            // including previews on the adjacent workspace page.
            invalidateActiveIcons(onlyChangedDrawerSamples=true)
        }
        if(Looper.myLooper()==Looper.getMainLooper())refresh.run()else view.post(refresh)
    }
    fun invalidateActiveIcons(onlyChangedDrawerSamples:Boolean=false,beforeDraw:View?=null){
        if(!PrefsHelper.enabled)return
        // Icon config resets may clear drawable associations while native
        // RenderNodes keep their display lists. Keep view registration separate
        // so the next live tick can force those lists to be recorded again.
        val wallpaper=if(PrefsHelper.blurEnabled)WallpaperHelper.getBlurredWallpaper(appContext)else null
        val location=IntArray(2)
        val visible=Rect()
        val world=Matrix()
        val full=RectF()
        val scroll=(LiveBlurEngine.wallpaperScrollRatio*10000).toInt()
        val roots=LinkedHashSet<View>()
        for(reference in registeredViews()){
            val view=reference.get() ?: continue
            if(beforeDraw!=null && view.rootView!==beforeDraw)continue
            if(NativeSurfaceLive.ownsSource(view) || !view.isAttachedToWindow || !view.isShown)continue
            world.reset()
            try {XposedHelpers.callMethod(view,"transformMatrixToGlobal",world)}catch(_:Throwable){continue}
            full.set(0f,0f,view.width.toFloat(),view.height.toFloat());world.mapRect(full)
            val metrics=view.resources.displayMetrics
            val offscreen=full.right<=0f || full.bottom<=0f || full.left>=metrics.widthPixels || full.top>=metrics.heightPixels
            val edge=view.javaClass.name.contains("BubbleTextView") &&
                (full.left<-.5f || full.top<-.5f || full.right>metrics.widthPixels+.5f || full.bottom>metrics.heightPixels+.5f)
            // Both update paths obey the edge optimization. The old switch only
            // removed forceEdge, leaving the ordinary sampler path running.
            if(edge && !PrefsHelper.disableEdgeOptimization && onlyChangedDrawerSamples)continue
            // Forced edge repaint is only useful while pages actually move. On
            // a stationary page the adjacent page's icons are permanently past
            // the edge: invalidating them in every pre-draw requested the next
            // frame forever and pinned LTPO displays at 60 Hz.
            val forceEdge=edge && PrefsHelper.disableEdgeOptimization && LiveBlurEngine.hasRecentMotion
            if(offscreen && !forceEdge)continue
            if(onlyChangedDrawerSamples && !forceEdge && IconMaterialRenderer.hasBindings(view)){
                if(IconMaterialRenderer.updateSamplers(view,wallpaper,world)){
                    if(beforeDraw==null)roots.add(view.rootView)
                    continue
                }
                if(beforeDraw!=null && !LiveBlurEngine.isMoving)idleInvalidations++
                view.invalidate()
                continue
            }
            if(!view.getGlobalVisibleRect(visible)){
                if(!forceEdge)continue
                visible.set(0,0,view.width,view.height)
            }
            view.getLocationOnScreen(location)
            val stamp=RepaintStamp(location[0],location[1],visible.width(),visible.height(),scroll,wallpaper,revision)
            if(forceEdge || !onlyChangedDrawerSamples || repaintStamps[view]!=stamp || IconMaterialRenderer.needsRecording(view)){
                repaintStamps[view]=stamp
                if(beforeDraw!=null && !LiveBlurEngine.isMoving)idleInvalidations++
                view.invalidate()
            }
        }
        if(beforeDraw==null)roots.forEach {it.invalidate()}
    }
    fun onIconThemeChanged(){
        FolderMaterialHook.invalidateGeometry()
        SurfaceMaterial.invalidateGeometry()
        anchors.clear();viewAnchors.clear();hosts.clear();rawFrames.clear();layerCache.clear();previews.clear();previewNext.clear();IconBitmapProcessor.clearCache();DynamicIconForeground.clear();SurfaceTone.clear()
        invalidateShaders()
    }
    fun reloadNativeIcons(){
        val context=appContext ?: return
        try {
            val type=XposedHelpers.findClass("com.android.launcher3.LauncherAppState",context.classLoader)
            val state=XposedHelpers.callStaticMethod(type,"getInstanceNoCreate") ?: return
            val cache=XposedHelpers.callMethod(state,"getIconCache")
            val worker=XposedHelpers.getObjectField(cache,"mWorkerHandler") as? Handler
            val work=Runnable {
                try {XposedHelpers.callMethod(cache,"clearIconsCacheAndDb")}catch(_:Throwable){}
                Handler(Looper.getMainLooper()).post {
                    try {XposedHelpers.callMethod(state,"refreshAndReloadLauncher")}
                    catch(_:Throwable){XposedHelpers.callMethod(XposedHelpers.callMethod(state,"getModel"),"forceReload")}
                }
            }
            if(worker!=null)worker.post(work)else work.run()
        }catch(t:Throwable){GlassLogger.e("MaterialEngine","Native refresh unavailable",t)}
    }
    private fun raw(drawable:Drawable):Bitmap? {
        if(drawable is BitmapDrawable)return drawable.bitmap.takeUnless {it.isRecycled}
        val bitmap=try {XposedHelpers.getObjectField(drawable,"mBitmap") as? Bitmap}catch(_:Throwable){null}
        val specialTick=SpecialIconSupport.tick(drawable)
        val dynamic=specialTick!=null || drawable.javaClass.name.let {it.contains("Clock") || it.contains("Calendar")}
        if(bitmap!=null && !dynamic)return bitmap.takeUnless {it.isRecycled}
        val gpuDynamic=PrefsHelper.gpuEffects && SpecialIconSupport.dynamic(drawable) && android.os.Build.VERSION.SDK_INT>=33
        val tick=if(gpuDynamic)0L else specialTick ?: if(dynamic)System.currentTimeMillis()/1000 else 0L
        val generation=bitmap?.generationId ?: 0
        rawFrames[drawable]?.takeIf {it.tick==tick && it.generation==generation && it.width==drawable.bounds.width() && it.height==drawable.bounds.height()}?.let {return it.bitmap}
        val old=Rect(drawable.bounds)
        val nativeWidth=old.width().takeIf {it>0} ?: drawable.intrinsicWidth.takeIf {it>0} ?: 210
        val nativeHeight=old.height().takeIf {it>0} ?: drawable.intrinsicHeight.takeIf {it>0} ?: nativeWidth
        val scale=minOf(1f,768f/maxOf(nativeWidth,nativeHeight))
        val width=(nativeWidth*scale).toInt().coerceAtLeast(1)
        val height=(nativeHeight*scale).toInt().coerceAtLeast(1)
        android.os.Trace.beginSection("CherryPick.captureNativeIcon")
        recording.set(true)
        return try {
            val node=RenderNode("CherryPickSource");node.setPosition(0,0,width,height)
            try {
                // Retain native bounds and Flux path. Changing bounds to a square
                // distorts wide icons and can notify the OEM animation controller.
                val canvas=node.beginRecording(width,height)
                try {canvas.scale(scale,scale);canvas.translate(-old.left.toFloat(),-old.top.toFloat());drawable.draw(canvas)}finally{node.endRecording()}
                val hardware=try {XposedHelpers.callStaticMethod(HardwareRenderer::class.java,"createHardwareBitmap",node,width,height)}
                    catch(_:Throwable){XposedHelpers.callStaticMethod(Class.forName("android.view.ThreadedRenderer"),"createHardwareBitmap",node,width,height)} as? Bitmap
                val result=hardware?.copy(Bitmap.Config.ARGB_8888,false)
                hardware?.recycle()
                result?.also {rawFrames[drawable]=RawFrame(it,tick,generation,old.width(),old.height())}
            }finally{node.discardDisplayList()}
        }catch(_:Throwable){bitmap}
        finally{recording.remove();android.os.Trace.endSection()}
    }
    private fun layers(drawable:Drawable):ProcessedIcon? {
        SpecialIconSupport.packageOf(drawable)?.let {pkg ->
            val bitmap=raw(drawable) ?: return null
            if(pkg=="com.geteit.andwobble")return SpecialIconSupport.legacyLayers(bitmap)
            IconBitmapProcessor.markLayeredSource(bitmap)
            return if(PrefsHelper.gpuEffects && android.os.Build.VERSION.SDK_INT>=33)IconBitmapProcessor.nativeLayers(bitmap,PrefsHelper.bgOpacity)
                else IconBitmapProcessor.getOrProcess(bitmap,PrefsHelper.bgOpacity)
        }
        try {
            val mono=XposedHelpers.getObjectField(drawable,"mMonoIcon") as? Bitmap
            val mask=XposedHelpers.getObjectField(drawable,"mBgBitmap") as? Bitmap
            val bg=XposedHelpers.getObjectField(drawable,"mBgPaint") as? Paint
            val foreground=XposedHelpers.getObjectField(drawable,"mMonoPaint") as? Paint
            if(mono!=null && mask!=null && !mono.isRecycled && !mask.isRecycled){
                val palette=PrefsHelper.iconPalette
                val output=Bitmap.createBitmap(mono.width,mono.height,Bitmap.Config.ARGB_8888)
                val paint=Paint(foreground ?: Paint()).apply {
                    alpha=255
                    if(palette.custom || palette.whiteGlyph)colorFilter=palette.filter
                }
                Canvas(output).drawBitmap(WallpaperHelper.forCanvas(mono,Canvas(output)),0f,0f,paint)
                return ProcessedIcon(output,WallpaperHelper.forCanvas(mask,Canvas(output)),if(palette.custom)palette.background else bg?.color ?: Color.TRANSPARENT)
            }
        }catch(_:Throwable){}
        Oxygen16IconSupport.background(drawable)?.let {return it}
        return raw(drawable)?.let {IconBitmapProcessor.getOrProcess(it,PrefsHelper.bgOpacity)}
    }
    private data class LayerCache(val revision:Long,val source:Bitmap?,val generation:Int,val palette:IconPalette,val layers:ProcessedIcon?,val width:Int,val height:Int,val compatibility:Long,val specialPackage:String?)
    private val layerCache=WeakHashMap<Drawable,LayerCache>()
    private fun cachedLayers(drawable:Drawable):ProcessedIcon? {
        val source=try {XposedHelpers.getObjectField(drawable,"mBitmap") as? Bitmap}catch(_:Throwable){(drawable as? BitmapDrawable)?.bitmap}
        val specialTick=SpecialIconSupport.tick(drawable)
        val dynamic=specialTick!=null || drawable.javaClass.name.contains("Clock") || drawable.javaClass.name.contains("Calendar")
        val gpuDynamic=PrefsHelper.gpuEffects && SpecialIconSupport.dynamic(drawable) && android.os.Build.VERSION.SDK_INT>=33
        val stamp=revision+(if(gpuDynamic)0 else specialTick ?: if(dynamic)System.currentTimeMillis()/1000 else 0)
        val palette=PrefsHelper.iconPalette
        val compatibility=Oxygen16IconSupport.stamp(drawable)
        val specialPackage=SpecialIconSupport.packageOf(drawable)
        layerCache[drawable]?.takeIf {it.revision==stamp && it.source===source && it.generation==(source?.generationId ?: 0) && it.palette==palette && it.width==drawable.bounds.width() && it.height==drawable.bounds.height() && it.compatibility==compatibility && it.specialPackage==specialPackage}?.let {return it.layers}
        val result=layers(drawable)
        if(result!=null && SpecialIconSupport.packageOf(drawable)==null)SpecialIconSupport.rememberMask(result.plateMaskBitmap)
        SurfaceTone.record(drawable,result)
        if(result!=null || !gpuDynamic)layerCache[drawable]=LayerCache(stamp,source,source?.generationId ?: 0,palette,result,drawable.bounds.width(),drawable.bounds.height(),compatibility,specialPackage)
        return result
    }
    private fun sourceRect(bitmap:Bitmap,a:Anchor?,bounds:Rect):RectF {
        if(a==null)return RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat())
        val scale=maxOf(a.screenW.toFloat()/bitmap.width,a.screenH.toFloat()/bitmap.height)
        val visibleWidth=a.screenW/scale
        val offset=(bitmap.width-visibleWidth).coerceAtLeast(0f)*LiveBlurEngine.wallpaperScrollRatio
        val x=offset+a.x/scale
        val y=a.y/scale
        return RectF(x,y,x+a.iconW/scale,y+a.iconH/scale)
    }
    private fun render(canvas:Canvas,bounds:Rect,parts:ProcessedIcon,a:Anchor?,wallpaper:Bitmap?,binding:IconMaterialRenderer.Binding?=null) {
        // The native surface can be screen-wide. Reuse the desktop material
        // texture and let the GPU scale it, instead of rebuilding all effects
        // into a 1200px CPU bitmap for every new native drawable.
        val width=parts.plateMaskBitmap.width
        val height=parts.plateMaskBitmap.height
        if(bounds.width()>width*1.1f || bounds.height()>height*1.1f){
            val save=canvas.save()
            canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat()/width,bounds.height().toFloat()/height)
            try {render(canvas,Rect(0,0,width,height),parts,a,wallpaper)}finally{canvas.restoreToCount(save)}
            return
        }
        val compatible=wallpaper?.takeUnless {it.isRecycled}?.let {WallpaperHelper.forCanvas(it,canvas)}
        val plateMask=parts.plateMaskBitmap
        // Native surface capture enlarges a 210px icon to e.g. 1200px.
        // Material geometry scales, but the sampled wallpaper footprint must
        // stay the size of the original icon, not the offscreen surface.
        val footprint=Rect(0,0,parts.plateMaskBitmap.width,parts.plateMaskBitmap.height)
        val source=compatible?.let {sourceRect(it,a,footprint)}
        val plateColor=if(compatible!=null)PrefsHelper.plateColor(parts.bgColor)else parts.bgColor
        val plateOpacity=PrefsHelper.bgOpacity
        IconMaterialRenderer.draw(canvas,bounds,plateMask,parts.glyphBitmap,plateColor,
            plateOpacity*255/100,false,compatible,source,binding,style=GlassStyle.icons().copy(extendedLens=width!=height)){target,local,blurred ->
            if(!blurred){
                val save=target.saveLayer(RectF(local),null)
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {colorFilter=PorterDuffColorFilter((plateOpacity*255/100 shl 24) or (plateColor and 0xffffff),PorterDuff.Mode.SRC_IN)}
                target.drawBitmap(WallpaperHelper.forCanvas(plateMask,target),null,local,paint)
                if(PrefsHelper.gradientOpacity>0){
                    paint.colorFilter=null;paint.isDither=true;paint.shader=GlassVolume.shader(local.height().toFloat(),PrefsHelper.gradientOpacity)
                    paint.xfermode=PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                    target.drawRect(local,paint)
                }
                target.restoreToCount(save)
            }
            if(PrefsHelper.iconStroke && PrefsHelper.strokeOpacity>0){
                target.drawBitmap(GlassGeometry.rim(plateMask),null,local,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            if(PrefsHelper.glyphShadow && PrefsHelper.shadowOpacity>0){
                GlyphShadowHelper.getOrCreateShadow(parts.glyphBitmap)?.let {shadow ->
                    val sx=local.width().toFloat()/parts.glyphBitmap.width;val sy=local.height().toFloat()/parts.glyphBitmap.height
                    val x=shadow.offsetX*sx;val y=shadow.offsetY*sy+(local.height()*.016f).coerceAtLeast(2f)
                    target.drawBitmap(shadow.bitmap,null,RectF(x,y,x+shadow.bitmap.width*sx,y+shadow.bitmap.height*sy),Paint(Paint.FILTER_BITMAP_FLAG).apply {alpha=175*PrefsHelper.shadowOpacity/100})
                }
            }
            GlyphMaterialRenderer.draw(target,local,parts.glyphBitmap,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }
    private fun wallpaper(a:Anchor?):Bitmap? {
        if(!PrefsHelper.blurEnabled)return null
        if(a?.folder==true && !PrefsHelper.folderIconsBlurEnabled)return null
        if(a?.drawer==true && !PrefsHelper.drawerBlurEnabled)return null
        return WallpaperHelper.getBlurredWallpaper(appContext)
    }
    /** Live complete material for one native animation. No global last-app state. */
    private fun snapshot(original:Drawable,backgroundOnly:Boolean=false,explicitHost:View?=null):Drawable? {
        val complete=Oxygen16IconSupport.snapshotParts(original) ?: cachedLayers(original) ?: run {
            GlassLogger.i("MaterialEngine","No material layers for animation: ${original.javaClass.name}, raw=${raw(original)?.width}")
            return null
        }
        val parts=if(backgroundOnly)complete.copy(glyphBitmap=Bitmap.createBitmap(complete.glyphBitmap.width,complete.glyphBitmap.height,Bitmap.Config.ARGB_8888))else complete
        val bitmap=raw(original)
        val owner=explicitHost ?: host(original)
        val bounds=Rect(0,0,bitmap?.width ?: 210,bitmap?.height ?: 210)
        val a=owner?.let {anchor(it,bounds)} ?: bitmap?.let {anchors[it]}
        // The native renderer may request repeated draws of this material.
        // Keep the shared wallpaper live, not baked into a snapshot.
        return object:Drawable(){
            private val paint=Paint()
            override fun draw(canvas:Canvas){
                if(!PrefsHelper.enabled){
                    val old=Rect(original.bounds)
                    try {original.bounds=this.bounds;original.draw(canvas)}finally{original.bounds=old}
                    return
                }
                val layer=if(paint.alpha<255)canvas.saveLayerAlpha(RectF(this.bounds),paint.alpha)else canvas.save()
                val materialBounds=Rect(this.bounds)
                val materialAlpha=paint.alpha
                // Legacy setDrawable uses this wrapper directly instead of a
                // FastBitmapDrawable draw hook. Keep the 17 renderer unchanged.
                if(NativeSurfaceLive.usesLegacyRenderer)NativeSurfaceLive.capture(canvas,parts.plateMaskBitmap.width,parts.plateMaskBitmap.height) {target ->
                    val saved=if(materialAlpha<255)target.saveLayerAlpha(RectF(materialBounds),materialAlpha)else target.save()
                    val rect=NativeSurfaceLive.sampleBounds(materialBounds)
                    val sample=if(rect!=null && a!=null)a.copy(x=rect.left,y=rect.top,iconW=rect.width(),iconH=rect.height())else a
                    try {render(target,materialBounds,parts,sample,wallpaper(sample))}finally{target.restoreToCount(saved)}
                }
                try {render(canvas,this.bounds,parts,a,wallpaper(a))}finally{canvas.restoreToCount(layer)}
            }
            override fun setAlpha(value:Int){paint.alpha=value}
            override fun getAlpha()=paint.alpha
            override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
            @Deprecated("Deprecated in Java")override fun getOpacity()=PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth()=bounds.width()
            override fun getIntrinsicHeight()=bounds.height()
        }.apply {setBounds(bounds)}.also {nativeDrawables[it]=true}
    }
    private class FloatingBackground(val inner:Drawable,val material:Drawable,val view:View):Drawable(){
        override fun draw(canvas:Canvas){
            val clip=try {XposedHelpers.getObjectField(view,"mClipPath") as? Path}catch(_:Throwable){null}
            val rect=RectF(inner.bounds)
            if(clip!=null && !clip.isEmpty)clip.computeBounds(rect,true)
            if(rect.isEmpty){inner.draw(canvas);return}
            val save=canvas.save()
            try {
                if(clip!=null && !clip.isEmpty)canvas.clipPath(clip)
                material.bounds=Rect(rect.left.toInt(),rect.top.toInt(),rect.right.toInt(),rect.bottom.toInt())
                material.alpha=inner.alpha
                material.draw(canvas)
            }finally{canvas.restoreToCount(save)}
        }
        override fun setAlpha(alpha:Int){inner.alpha=alpha}
        override fun getAlpha()=inner.alpha
        override fun setColorFilter(filter:ColorFilter?){inner.colorFilter=filter}
        @Deprecated("Deprecated in Java")override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    private fun drawMaterial(drawable:Drawable,canvas:Canvas,bounds:Rect):Boolean {
                // Never extract an icon from a composed drag preview snapshot.
                if(nativeDrawables.containsKey(drawable) || DragPreviewMaterial.isPreview(drawable))return false
                if(bounds.isEmpty)return false
                if(SpecialIconSupport.dynamic(drawable) && drawable.alpha<255 && rawFrames[drawable]==null)return false
                // FolderPagedView records the adjacent page during opening.
                // Reject fully clipped icons before reading pixels, extracting
                // layers or baking glyph shadows/contours for that hidden page.
                if(canvas.quickReject(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat()))return true
                // OEM surface snapshots already contain desktop glass. Process
                // the clicked icon's original layers, never extract/tint that
                // composed snapshot for a second time.
                val surfaceSource=NativeSurfaceLive.materialSource
                val owner=surfaceSource ?: host(drawable)
                // OEM edit-mode select dots record this icon again into their
                // own node, often as a fresh constantState copy per draw. Show
                // the element's own material: no per-copy layers or sampler.
                val foreign=surfaceSource==null && owner!=null && ForeignDraw.isForeign(owner)
                val identity=if(foreign)ForeignDraw.original(owner!!,drawable)else drawable
                // The OEM can record an adjacent page before it becomes
                // visible. A screen-space visibility rejection would cache an
                // empty icon until an unrelated invalidation (e.g. unlock).
                // Only the recording canvas may reject its own local content.
                val sourceIcon=surfaceSource?.let {try {XposedHelpers.callMethod(it,"getIcon") as? Drawable}catch(_:Throwable){null}} ?: identity
                if(SpecialIconSupport.isContainer(sourceIcon))return false
                val nativeParts=cachedLayers(sourceIcon) ?: return false
                // Native drag preview Picture: leave this plate's footprint
                // empty and draw the same live material inside the DragView.
                if(surfaceSource==null && DragPreviewMaterial.captures(canvas)){
                    @Suppress("DEPRECATION")
                    val local=Matrix().also {canvas.getMatrix(it)}
                    previewTransform.get()?.let {local.setConcat(it,Matrix(local))}
                    val rect=RectF(bounds);local.mapRect(rect)
                    val preview=folderPreview.get()==true || owner?.javaClass?.name?.contains("FolderIcon")==true
                    val mini=MiniIconRefresh.isMini(preview,rect.width(),rect.height(),bounds.width(),bounds.height())
                    if(DragPreviewMaterial.captureIcon(canvas,sourceIcon,bounds,owner,drawable.alpha,mini,nativeParts.plateMaskBitmap))return true
                }
                val gpuDynamic=PrefsHelper.gpuEffects && SpecialIconSupport.dynamic(sourceIcon) && android.os.Build.VERSION.SDK_INT>=33 && canvas.isHardwareAccelerated && surfaceSource==null
                val parts=if(gpuDynamic)DynamicIconForeground.plate(nativeParts)else nativeParts
                val bitmap=raw(sourceIcon)
                val saved=bitmap?.let {anchors[it]}
                val capture=bitmap!=null && (bounds.width()>bitmap.width*1.1f || bounds.height()>bitmap.height*1.1f)
                val inTransition=LiveBlurEngine.isAppLaunchInProgress || !LiveBlurEngine.isResumed
                val a=if(surfaceSource!=null)anchor(surfaceSource,Rect(0,0,bitmap?.width ?: 210,bitmap?.height ?: 210)) else if(capture && saved!=null)saved else owner?.let {
                    val nativeBounds=if(bitmap!=null)Rect(0,0,bitmap.width,bitmap.height)else bounds
                    anchor(it,nativeBounds)
                } ?: saved
                if((LiveBlurEngine.isAppLaunchInProgress || !LiveBlurEngine.isResumed) && transitionDrawLogs++<16)
                    GlassLogger.i("MaterialEngine","Transition draw: ${drawable.javaClass.simpleName}, host=${owner?.javaClass?.simpleName}, bounds=$bounds, source=${sourceIcon.javaClass.simpleName}/${bitmap?.width}, anchor=$a, blur=${wallpaper(a)?.width}, hardware=${canvas.isHardwareAccelerated}")
                if(a!=null && bitmap!=null && !capture && !inTransition)anchors[bitmap]=a
                if(LiveBlurEngine.isWorkspaceScrolling && scrollDrawLogs++<24)
                    GlassLogger.i("MaterialEngine","Scroll sample: host=${owner?.javaClass?.simpleName}, anchor=$a")
                val materialBounds=Rect(bounds)
                if(surfaceSource==null && a!=null)NativeSurfaceLive.prewarm(parts.plateMaskBitmap.width,a.screenW)
                val materialAlpha=drawable.alpha
                NativeSurfaceLive.capture(canvas,parts.plateMaskBitmap.width,parts.plateMaskBitmap.height) { target ->
                    val layer=if(materialAlpha<255)target.saveLayerAlpha(RectF(materialBounds),materialAlpha)else target.save()
                    val rect=NativeSurfaceLive.sampleBounds(materialBounds)
                    val sample=if(rect!=null && a!=null)a.copy(x=rect.left,y=rect.top,iconW=rect.width(),iconH=rect.height())else a
                    try {render(target,materialBounds,parts,sample,wallpaper(sample))}finally{target.restoreToCount(layer)}
                }
                @Suppress("DEPRECATION")
                val binding=if(surfaceSource==null && owner!=null && a!=null && canvas.isHardwareAccelerated){
                    val local=Matrix().also {canvas.getMatrix(it)}
                    previewTransform.get()?.let {local.setConcat(it,Matrix(local))}
                    val rect=RectF(bounds);local.mapRect(rect)
                    val preview=folderPreview.get()==true || owner.javaClass.name.contains("FolderIcon")
                    val mini=MiniIconRefresh.isMini(preview,rect.width(),rect.height(),bounds.width(),bounds.height())
                    if(preview && PrefsHelper.optimizeMiniIcons && previewSizeLogs.add("${owner.javaClass.name}:${owner.width}x${owner.height}:$mini:${folderPreview.get()==true}"))
                        GlassLogger.i("MaterialEngine","Folder preview pacing: host=${owner.javaClass.simpleName}/${owner.width}x${owner.height}, local=${rect.width()}x${rect.height()}, native=${bounds.width()}x${bounds.height()}, mini=$mini, limit=${MiniIconRefresh.fps(PrefsHelper.liveFps,mini,PrefsHelper.optimizeMiniIcons)} FPS")
                    IconMaterialRenderer.Binding(owner,identity,rect,a.screenW,a.screenH,mini,materialAlpha,foreign=foreign)
                }else null
                val layer=if(materialAlpha<255)canvas.saveLayerAlpha(RectF(bounds),materialAlpha)else canvas.save()
                try {render(canvas,bounds,parts,a,wallpaper(a),binding)}finally{canvas.restoreToCount(layer)}
                if(gpuDynamic) {
                    DynamicIconForeground.draw(sourceIcon,canvas,bounds,nativeParts.bgColor,bitmap ?: return true,reuse=foreign) {target ->
                        val prior=recording.get();recording.set(true)
                        try {sourceIcon.draw(target)}finally{recording.set(prior)}
                    }
                }
                return true
    }
    /**
     * One icon of a native drag preview, drawn live inside the DragView. The
     * material is the source icon's own (never extracted from the snapshot)
     * and binds to [owner], the view that draws the preview, so samples and
     * lifted backdrops follow the dragged position.
     */
    internal fun drawDragIcon(icon:Drawable,canvas:Canvas,bounds:Rect,owner:View,alpha:Int,mini:Boolean,lift:Boolean=!mini):Boolean {
        if(bounds.isEmpty)return false
        if(canvas.quickReject(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat()))return true
        if(SpecialIconSupport.isContainer(icon))return false
        val nativeParts=cachedLayers(icon) ?: return false
        val gpuDynamic=PrefsHelper.gpuEffects && SpecialIconSupport.dynamic(icon) && android.os.Build.VERSION.SDK_INT>=33 && canvas.isHardwareAccelerated
        val parts=if(gpuDynamic)DynamicIconForeground.plate(nativeParts)else nativeParts
        val bitmap=raw(icon)
        registerLiveView(owner)
        val a=anchor(owner,if(bitmap!=null)Rect(0,0,bitmap.width,bitmap.height)else bounds)
        @Suppress("DEPRECATION")
        val binding=if(canvas.isHardwareAccelerated){
            val local=Matrix().also {canvas.getMatrix(it)}
            val rect=RectF(bounds);local.mapRect(rect)
            // Folder preview items and items of a dragged cluster keep their
            // resting wallpaper sample, at the full rate: the dragged preview
            // moves every frame.
            IconMaterialRenderer.Binding(owner,icon,rect,a.screenW,a.screenH,false,alpha,lift=lift)
        }else null
        val layer=if(alpha<255)canvas.saveLayerAlpha(RectF(bounds),alpha)else canvas.save()
        try {render(canvas,bounds,parts,a,wallpaper(a),binding)}finally{canvas.restoreToCount(layer)}
        if(gpuDynamic && bitmap!=null){
            DynamicIconForeground.draw(icon,canvas,bounds,nativeParts.bgColor,bitmap) {target ->
                val prior=recording.get();recording.set(true)
                try {icon.draw(target)}finally{recording.set(prior)}
            }
        }
        return true
    }
    /** Stock icon pixels for a drag preview element when no material can be drawn. */
    internal fun drawNativeIcon(icon:Drawable,canvas:Canvas,bounds:Rect,alpha:Float){
        val prior=recording.get();recording.set(true)
        val layer=if(alpha<1f)canvas.saveLayerAlpha(RectF(bounds),(alpha*255).toInt().coerceIn(0,255))else canvas.save()
        try {
            val internal=try {XposedHelpers.findMethodBestMatch(icon.javaClass,"drawInternal",Canvas::class.java,Rect::class.java)}catch(_:Throwable){null}
            if(internal!=null)internal.invoke(icon,canvas,bounds)else icon.draw(canvas)
        }catch(_:Throwable){}
        finally{canvas.restoreToCount(layer);recording.set(prior)}
    }
    fun init(lpparam:LoadPackageParam) {
        val loader=lpparam.classLoader
        for((name,install) in listOf<Pair<String,()->Unit>>(
            "NativeSurface" to {NativeSurfaceLive.install(loader)},
            "DragPreview" to {DragPreviewMaterial.install(loader)},
            "Folders" to {FolderMaterialHook.install(loader)},
            "LiftedBackdrop" to {LiftedBackdrop.install(loader)},
            "MultiIcons" to {MultiIconMaterialHook.install(loader)},
            "Dock" to {DockMaterial.install(loader)},
            "Transition" to {TransitionCapture.install(loader)},
            "ForeignDraw" to {ForeignDraw.install(loader)},
            "PhotoWidget" to {PhotoWidgetMaterial.install(loader)}
        ))try {
            install()
            GlassLogger.i("Compat","$name setup completed")
        }catch(t:Throwable){GlassLogger.e("Compat","$name setup failed; remaining features continue",t)}
        // Carry the source view through native asynchronous icon preparation.
        // The anchor belongs to this load request, never to a global last click.
        val modernLoader=XposedHelpers.findClassIfExists("com.android.launcher3.anim.iconsurfacemanager.loader.IconDrawableLoader",loader)
        val iconLoader=modernLoader ?: XposedHelpers.findClassIfExists("com.android.launcher3.anim.iconsurfacemanager.IconSurfaceRecord",loader)
        val loadMethods=if(modernLoader!=null)listOf("getIconResultForAsync","getIconResult","loadIcon")else listOf("onIconLoaded")
        if(iconLoader!=null)for(method in loadMethods) {
            ModuleHooks.hookAllMethods(iconLoader,method,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    val source=param.args.filterIsInstance<View>().firstOrNull {view ->
                        view.tag!=null && !view.javaClass.name.contains("Floating")
                    } ?: return
                    param.setObjectExtra("previousSource",loadingView.get());param.setObjectExtra("materialSource",source)
                    loadingView.set(source)
                }
                override fun afterHookedMethod(param:MethodHookParam){
                    val source=param.getObjectExtra("materialSource") as? View ?: return
                    try {
                        val result=param.result
                        val values=ArrayList<Any?>()
                        values.add(result)
                        if(result!=null)for(field in result.javaClass.declaredFields) {
                            if(java.lang.reflect.Modifier.isStatic(field.modifiers))continue
                            if(Drawable::class.java.isAssignableFrom(field.type) || Bitmap::class.java.isAssignableFrom(field.type)) {
                                field.isAccessible=true;values.add(field.get(result))
                            }
                        }
                        for(value in values)when(value){
                            is Drawable -> {
                                hosts[value]=WeakReference(source)
                                raw(value)?.let {bitmap -> anchors[bitmap]=anchor(source,Rect(0,0,bitmap.width,bitmap.height))}
                            }
                            is Bitmap -> anchors[value]=anchor(source,Rect(0,0,value.width,value.height))
                        }
                    }catch(_:Throwable){}
                    finally {loadingView.set(param.getObjectExtra("previousSource") as? View)}
                }
            })
        }
        // Adapt PLE's floating-background principle: wrap the native ClipIconView
        // background while preserving native draw, clipping and motion.
        for(name in listOf("com.android.launcher3.views.ClipIconView","com.android.launcher3.views.OplusClipIconView")) {
            val cls=XposedHelpers.findClassIfExists(name,loader) ?: continue
            ModuleHooks.hookAllMethods(cls,"draw",object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(!PrefsHelper.enabled)return
                    val view=param.thisObject as? View ?: return
                    val background=try {XposedHelpers.getObjectField(view,"mBackground") as? Drawable}catch(_:Throwable){null} ?: return
                    if(background is FloatingBackground)return
                    var parent=view.parent as? View
                    var source:View?=null
                    repeat(8){
                        val current=parent ?: return@repeat
                        val candidate=try {XposedHelpers.getObjectField(current,"mOriginalIcon") as? View}catch(_:Throwable){null}
                        if(candidate!=null)source=candidate
                        parent=current.parent as? View
                    }
                    val owner=source ?: return
                    val original=try {XposedHelpers.callMethod(owner,"getIcon") as? Drawable}catch(_:Throwable){null} ?: return
                    val previous=floatingMaterials[view]
                    val material=if(previous!=null && previous.source===original && previous.native===background &&
                        previous.palette==PrefsHelper.iconPalette && previous.revision==revision)previous.material
                    else (snapshot(original,true,owner) ?: return).also {
                        floatingMaterials[view]=FloatingMaterial(original,background,PrefsHelper.iconPalette,revision,it)
                    }
                    param.setObjectExtra("nativeBackground",background)
                    XposedHelpers.setObjectField(view,"mBackground",FloatingBackground(background,material,view))
                }
                override fun afterHookedMethod(param:MethodHookParam){
                    val background=param.getObjectExtra("nativeBackground") as? Drawable ?: return
                    XposedHelpers.setObjectField(param.thisObject,"mBackground",background)
                }
            })
        }
        for(name in listOf("com.android.launcher3.BubbleTextView","com.android.launcher3.OplusBubbleTextView","com.android.launcher3.folder.big.BigFolderItemIcon","com.android.launcher3.folder.FolderIcon","com.android.launcher3.folder.FlexibleFolderIcon")) {
            val cls=XposedHelpers.findClassIfExists(name,loader) ?: continue
            for(method in listOf("draw","onDraw"))ModuleHooks.hookAllMethods(cls,method,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? View
                    if(view!=null){
                        registerLiveView(view)
                        bindSpecialView(view)
                    }
                    param.setObjectExtra("priorMaterialHost",drawingView.get());drawingView.set(view)
                }
                override fun afterHookedMethod(param:MethodHookParam){drawingView.set(param.getObjectExtra("priorMaterialHost") as? View)}
            })
        }
        val previewManager=XposedHelpers.findClassIfExists("com.android.launcher3.folder.PreviewItemManager",loader)
        if(previewManager!=null)ModuleHooks.hookAllMethods(previewManager,"drawPreviewItem",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!PrefsHelper.enabled || recording.get()==true)return
                val params=param.args.getOrNull(1) ?: return
                val owner=try {XposedHelpers.getObjectField(param.thisObject,"mIcon") as? View}catch(_:Throwable){null}
                bindPreviewItem(params,owner)
            }
        })
        val stacked=XposedHelpers.findClassIfExists("com.android.launcher3.folder.big.stacked.StackedDrawable",loader)
        if(stacked!=null)ModuleHooks.hookAllMethods(stacked,"draw",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!PrefsHelper.enabled || recording.get()==true)return
                val drawable=param.thisObject as? Drawable ?: return
                bindPreviewContainer(drawable,host(drawable))
            }
        })
        val preview=XposedHelpers.findClassIfExists("com.android.launcher3.folder.FolderPreviewDrawable",loader)
        if(preview!=null)ModuleHooks.hookAllMethods(preview,"draw",object:XC_MethodHook(){
            @Suppress("DEPRECATION")
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!PrefsHelper.enabled || recording.get()==true)return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                val prior=previewTransform.get()
                val matrix=Matrix().also {canvas.getMatrix(it)}
                if(prior!=null)matrix.setConcat(prior,Matrix(matrix))
                param.setObjectExtra("priorPreviewTransform",prior)
                param.setObjectExtra("previewTransformSet",true)
                param.setObjectExtra("priorFolderPreview",folderPreview.get())
                folderPreview.set(true)
                previewTransform.set(matrix)
                try {
                    val drawable=param.thisObject as? Drawable ?: return
                    val owner=host(drawable) ?: return
                    bindPreviewContainer(drawable,owner)
                    val old=previews[drawable]
                    if(old==null || old.revision!=revision){
                        XposedHelpers.setBooleanField(drawable,"mInvalid",true)
                        previews[drawable]=PreviewStamp(revision,null,0,0)
                    }
                }catch(t:Throwable){GlassLogger.e("MaterialEngine","Preview material refresh failed",t)}
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("previewTransformSet")==true){
                    previewTransform.set(param.getObjectExtra("priorPreviewTransform") as? Matrix)
                    folderPreview.set(param.getObjectExtra("priorFolderPreview") as? Boolean)
                }
            }
        })
        for(name in listOf("com.oplus.icons.OplusFastBitmapDrawable","com.android.launcher3.icons.FastBitmapDrawable","com.android.launcher3.icons.ThemedIconDrawable","com.android.launcher3.icons.OplusThemedIconDrawable")) {
            val cls=XposedHelpers.findClassIfExists(name,loader) ?: continue
            // Native drag previews draw their live materials first; the native
            // snapshot (badges, labels, dots) then draws on top, also when the
            // module was switched off after the preview had been recorded.
            ModuleHooks.hookAllMethods(cls,"drawInternal",object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    val drawable=param.thisObject as? Drawable ?: return
                    if(recording.get()==true || !DragPreviewMaterial.isPreview(drawable))return
                    val canvas=param.args.firstOrNull() as? Canvas ?: return
                    val bounds=param.args.getOrNull(1) as? Rect ?: drawable.bounds
                    try {DragPreviewMaterial.replay(drawable,canvas,bounds,if(PrefsHelper.enabled)host(drawable)else null)}
                    catch(t:Throwable){if(preparationFailures.add(t.javaClass.name+":"+t.message))GlassLogger.e("MaterialEngine","Drag preview material failed",t)}
                }
            })
            ModuleHooks.hookAllMethods(cls,"drawInternal",hook {param ->
                val drawable=param.thisObject as? Drawable ?: return@hook
                if(DragPreviewMaterial.isPreview(drawable))return@hook
                // Keep the 16.6.8 cover's native clipping/shine. Replace its
                // base bitmap below, leaving independent sub-layer motion intact.
                if(name=="com.oplus.icons.OplusFastBitmapDrawable" && Oxygen16IconSupport.background(drawable)!=null)return@hook
                val canvas=param.args.firstOrNull() as? Canvas ?: return@hook
                val bounds=param.args.getOrNull(1) as? Rect ?: drawable.bounds
                if(drawMaterial(drawable,canvas,bounds))param.result=null
            })
        }
        // Change only icon material inputs. Stock setDrawable/onDraw, corner
        // extensions, app-window morphing, visibility and completion stay native.
        val floating=XposedHelpers.findClassIfExists("com.android.launcher3.anim.floatingdrawable.IconDrawableView",loader)
        if(floating!=null)ModuleHooks.hookAllMethods(floating,"setDrawable",hook {param ->
            GlassLogger.i("MaterialEngine","Native setDrawable: ${param.args.firstOrNull()?.javaClass?.name}")
            val original=param.args.firstOrNull() as? Drawable ?: return@hook
            // A multi-icon launch/return floats a snapshot of the whole
            // composed view, already with its plate and items. Extracting an
            // icon from it stretched the items onto a dark plate.
            if(TransitionCapture.isViewCapture(original))return@hook
            val material=snapshot(original) ?: return@hook
            param.args[0]=material
            if(param.args.getOrNull(1) is Drawable)param.args[1]=ColorDrawable(Color.TRANSPARENT)
            if(param.args.getOrNull(2) is Drawable)param.args[2]=material.constantState?.newDrawable()?.also {nativeDrawables[it]=true} ?: material
        })
        GlassLogger.i("MaterialEngine","Next engine installed: native transitions, leased live icon material")
    }
    internal fun bindSpecialView(view:View){
        if(PrefsHelper.enabled && recording.get()!=true)SpecialIconSupport.bind(view,{recording.get()==true}) {icon,canvas ->
            drawMaterial(icon,canvas,icon.bounds)
        }
    }
    internal fun bindContextView(view:View){
        bindSpecialView(view)
        // App icons retain their normal extraction; shortcut buttons and empty slots use OEM circular plates.
        val type=view.tag?.let {NativeIconAccess.field(it,"itemType") as? Number}?.toInt()
        if(view.tag!=null && type!=1 && type!=6)return
        val icon=NativeIconAccess.call(view,"getIcon") as? Drawable ?: return
        hosts[icon]=WeakReference(view)
        registerLiveView(view)
        SpecialIconSupport.bindContext(icon,{recording.get()==true}) {drawable,canvas ->
            drawMaterial(drawable,canvas,drawable.bounds)
        }
    }
}
