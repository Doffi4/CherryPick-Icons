package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.HandlerThread
import android.os.Process
import android.os.Trace
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.*
import de.robv.android.xposed.*
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Own only material buffers. Never retain/replay mutable native shadow builders. */
object NativeSurfaceLive {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val submitter by lazy {
        Handler(HandlerThread("CherryPickSurface",Process.THREAD_PRIORITY_DISPLAY).apply {start()}.looper)
    }
    private val pending=AtomicBoolean(false)
    private data class Pose(val xx:Float=1f,val xy:Float=0f,val yx:Float=0f,val yy:Float=1f,
        val x:Float=0f,val y:Float=0f,val positioned:Boolean=false,val transformed:Boolean=false,val version:Long=0L)
    private data class Lease(val provider: Any, val control: SurfaceControl,
        val pose:AtomicReference<Pose> = AtomicReference(Pose()))
    private val controls=ConcurrentHashMap<SurfaceControl,Lease>()
    private data class Sample(val capture:Capture,val draw:Draw,val pose:Pose?)
    private val sampling=ThreadLocal<Sample?>()
    private data class Draw(val matrix: Matrix, val clip: Rect, val render: (Canvas) -> Unit)
    private data class Capture(val surface: Surface, val source: View?, val tag: Any?,
        val lease: Lease?, val recordId: Int?, val token: Any, val draws: MutableList<Draw> = ArrayList(),
        var canvasWidth:Int=1,var canvasHeight:Int=1,var materialWidth:Int=210,var materialHeight:Int=210)
    private data class Frame(val capture: Capture, val started: Long,
        var wallpaper: Bitmap?, var last: Long = 0L, var redraws: Int = 0, var poseVersion:Long = -1L,val clock:SampleFrameClock=SampleFrameClock())
    private val leases = WeakHashMap<Surface, Lease>()
    private val tokens = WeakHashMap<Surface, Any>()
    private val frames = WeakHashMap<Surface, Frame>()
    private val captures = WeakHashMap<Surface, Capture>()
    private val children=HashMap<SurfaceControl,NativeMaterialSurface>()
    // Only the submitter accesses the pool. Queued retirement runs after an
    // in-flight draw, so a surface cannot be reused while its writer is active.
    private val pool=ArrayList<NativeMaterialSurface>(2)
    private val warmed=AtomicBoolean(false)
    fun prewarm(materialWidth:Int,canvasSize:Int) {
        if(!PrefsHelper.enabled || !PrefsHelper.blurEnabled || !warmed.compareAndSet(false,true))return
        submitter.post {
            if(!PrefsHelper.enabled){warmed.set(false);return@post}
            try {
                val width=materialWidth.coerceIn(128,384)
                repeat((2-pool.size).coerceAtLeast(0)){
                    val output=NativeMaterialSurface.create(null,width,width,canvasSize,canvasSize)
                    try {
                        val canvas=output.surface.lockHardwareCanvas()
                        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                        output.surface.unlockCanvasAndPost(canvas)
                        pool.add(output)
                    }catch(t:Throwable){output.close();throw t}
                }
                GlassLogger.i("LiveSurface","Prewarmed ${pool.size} reusable GPU material producers")
            }catch(t:Throwable){warmed.set(false);GlassLogger.e("LiveSurface","GPU producer warmup unavailable",t)}
        }
    }
    fun onSettingsChanged(){
        if(!PrefsHelper.enabled || !PrefsHelper.blurEnabled)submitter.post {
            pool.forEach {it.close()};pool.clear();warmed.set(false)
        }
    }
    private val templates=object:LinkedHashMap<Int,Capture>(12,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Int,Capture>?)=size>12
    }
    private val recording = ThreadLocal<Capture?>()
    private val record = ThreadLocal<Int?>()
    private val legacyTransfer = ThreadLocal<XC_MethodHook.MethodHookParam?>()
    var usesLegacyRenderer = false
        private set
    @Volatile var active = false
        private set

    @Volatile private var animatedSources:Set<View> = emptySet()
    fun ownsSource(view:View)=!LiveBlurEngine.isWorkspaceScrolling && animatedSources.contains(view)
    val materialSource: View? get() = recording.get()?.source

    /** Copy geometry from the initial native draw, and retain only this icon's material. */
    @Suppress("DEPRECATION")
    fun capture(canvas: Canvas, width:Int,height:Int,render: (Canvas) -> Unit) {
        val target = recording.get() ?: return
        target.canvasWidth=canvas.width;target.canvasHeight=canvas.height
        target.materialWidth=width;target.materialHeight=height
        target.draws.add(Draw(Matrix().also { canvas.getMatrix(it) }, Rect(canvas.clipBounds), render))
    }

    private fun refreshActive() {
        // ColorOS prepares a surface on pointer down, before it knows whether
        // this is a launch or a page swipe. Preparation must not freeze the
        // desktop source or show a stationary child over the scrolling icon.
        val flying=frames.values.filter {hasNativePose(it.capture)}
        val next=flying.isNotEmpty()
        animatedSources=flying.mapNotNull {it.capture.source}.toSet()
        if(active!=next){active=next;main.post {LiveBlurEngine.onNativeSurfacesChanged()}}
    }
    private fun hasNativePose(capture:Capture)=capture.lease?.pose?.get()?.let {it.positioned && it.transformed}==true
    /** Read stock transforms; never write native positions, matrices or alpha. */
    fun sampleBounds(bounds:Rect):RectF? {
        val sample=sampling.get() ?: return null
        val draw=sample.draw
        val pose=sample.pose ?: return null
        if(!pose.positioned || !pose.transformed)return null
        val rect=RectF(bounds)
        draw.matrix.mapRect(rect)
        val matrix=Matrix().apply {setValues(floatArrayOf(pose.xx,pose.xy,pose.x,pose.yx,pose.yy,pose.y,0f,0f,1f))}
        matrix.mapRect(rect)
        return rect.takeIf {it.width()>1f && it.height()>1f && it.left.isFinite() && it.top.isFinite()}
    }
    private fun observeTransform(param:XC_MethodHook.MethodHookParam,position:Boolean){
        val control=param.args.firstOrNull() as? SurfaceControl ?: return
        val lease=controls[control] ?: return
        val wasReady=lease.pose.get().let {it.positioned && it.transformed}
        if(position){
            val x=(param.args.getOrNull(1) as? Number)?.toFloat() ?: return
            val y=(param.args.getOrNull(2) as? Number)?.toFloat() ?: return
            lease.pose.updateAndGet {old -> if(old.positioned && old.x==x && old.y==y)old else old.copy(x=x,y=y,positioned=true,version=old.version+1)}
        }else{
            val values=FloatArray(9)
            val matrix=param.args.getOrNull(1) as? Matrix
            if(matrix!=null)matrix.getValues(values) else {
                if(param.args.size<5)return
                values[0]=(param.args[1] as? Number)?.toFloat() ?: return
                values[1]=(param.args[2] as? Number)?.toFloat() ?: return
                values[3]=(param.args[3] as? Number)?.toFloat() ?: return
                values[4]=(param.args[4] as? Number)?.toFloat() ?: return
            }
            lease.pose.updateAndGet {old -> if(old.transformed && old.xx==values[0] && old.xy==values[1] && old.yx==values[3] && old.yy==values[4])old else
                old.copy(xx=values[0],xy=values[1],yx=values[3],yy=values[4],transformed=true,version=old.version+1)}
        }
        if(!wasReady && lease.pose.get().let {it.positioned && it.transformed}) {
            synchronized(lock){refreshActive()}
            schedule()
        }
    }
    private fun releaseChild(control:SurfaceControl){
        val child=children.remove(control) ?: return
        // Revoke visibility synchronously. An already-running producer may
        // still post to this child, but cannot make a retired material visible.
        try {child.detach()}catch(_:Throwable){}
        submitter.post {
            if(PrefsHelper.enabled && PrefsHelper.blurEnabled && child.control.isValid && pool.size<2)pool.add(child)
            else child.close()
        }
    }
    private fun child(capture:Capture):NativeMaterialSurface {
        val parent=capture.lease!!.control
        children[parent]?.let {existing ->
            if(existing.control.isValid && existing.canvasWidth==capture.canvasWidth && existing.canvasHeight==capture.canvasHeight)return existing
            releaseChild(parent)
        }
        val width=capture.materialWidth.coerceIn(128,384)
        val height=(width.toFloat()*capture.canvasHeight/capture.canvasWidth).toInt().coerceAtLeast(1)
        val reusable=pool.indexOfFirst {it.control.isValid && it.width==width && it.height==height && it.canvasWidth==capture.canvasWidth && it.canvasHeight==capture.canvasHeight}
        if(reusable>=0)return pool.removeAt(reusable).also {
            it.attach(parent);children[parent]=it
            GlassLogger.i("LiveSurface","Reused GPU material producer: record=${capture.recordId}")
        }
        return NativeMaterialSurface.create(parent,width,height,capture.canvasWidth,capture.canvasHeight).also {
            children[parent]=it
            GlassLogger.i("LiveSurface","Small material buffer ${width}x$height, native=${capture.canvasWidth}x${capture.canvasHeight}")
        }
    }
    private fun resume(record:Any){
        if(!PrefsHelper.enabled || !PrefsHelper.blurEnabled || PrefsHelper.blurMode!=SettingsProvider.BLUR_MODE_LIVE)return
        val id=try {XposedHelpers.callMethod(record,"getRecordId") as? Int}catch(_:Throwable){null} ?: return
        synchronized(lock){
            val old=templates[id] ?: return
            if(old.lease?.control?.isValid!=true || old.source?.tag!==old.tag)return
            if(frames[old.surface]?.capture?.recordId==id)return
            val capture=old.copy(token=Any())
            tokens[capture.surface]=capture.token;captures[capture.surface]=capture
            controls[capture.lease!!.control]=capture.lease
            frames[capture.surface]=Frame(capture,SystemClock.uptimeMillis(),null)
            refreshActive()
        }
        schedule()
    }
    private fun retire(reason: String, predicate: (Capture) -> Boolean) = synchronized(lock) {
        captures.entries.removeAll { (_, capture) ->
            val remove = predicate(capture)
            if (remove) {
                tokens.remove(capture.surface)
                val frame = frames.remove(capture.surface)
                capture.lease?.let {
                    controls.remove(it.control,it)
                    if(reason!="showOriginalIcon")releaseChild(it.control)
                }
                GlassLogger.i("LiveSurface", "Retired record=${capture.recordId}, redraws=${frame?.redraws ?: 0}, pose=${capture.lease?.pose?.get()}, reason=$reason")
            }
            remove
        }
        refreshActive()
    }
    private fun schedule() {
        if(!active || !pending.compareAndSet(false,true))return
        // A single in-flight job, aligned to display frames. The launcher UI
        // never waits for lockHardwareCanvas / GPU buffer submission here.
        main.post {
            Choreographer.getInstance().postFrameCallback {
                submitter.post {
                    try {ticker.run()}finally{
                        pending.set(false)
                        if(active)schedule()
                    }
                }
            }
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            val work=synchronized(lock) {
                if (!PrefsHelper.enabled || !PrefsHelper.blurEnabled || PrefsHelper.blurMode != SettingsProvider.BLUR_MODE_LIVE) {
                    frames.clear(); captures.clear(); tokens.clear();controls.clear()
                    children.keys.toList().forEach {releaseChild(it)}
                    refreshActive(); return
                }
                frames.entries.map {it.key to it.value}
            }
            val now = SystemClock.uptimeMillis()
            val fps=MiniIconRefresh.fps(PrefsHelper.liveFps,false,false)
            val nowNs=System.nanoTime()
            val wallpaper = WallpaperHelper.getBlurredWallpaper(IconGlassHook.appContext)
            for((surface,frame) in work) {
                    val owner = frame.capture
                    val output=synchronized(lock) {
                    if(frames[surface]!==frame) return@synchronized null
                    if (now - frame.started > 4000L || tokens[surface] !== owner.token ||
                        leases[surface] !== owner.lease || owner.lease?.control?.isValid != true ||
                        owner.source?.tag !== owner.tag) {
                        frames.remove(surface); captures.remove(surface); owner.lease?.let {controls.remove(it.control,it);releaseChild(it.control)}
                        return@synchronized null
                    }
                    if(!hasNativePose(owner))return@synchronized null
                    child(owner)
                    } ?: continue
                    val pose=owner.lease?.pose?.get()
                    val poseVersion=pose?.version ?: -1L
                    val poseChanged=poseVersion!=frame.poseVersion
                    if(frame.clock.remainingNs(nowNs,fps)>0L || (wallpaper===frame.wallpaper && !poseChanged))continue
                    var canvas: Canvas? = null
                    Trace.beginSection("CherryPick.surfaceSubmit")
                    try {
                        // Never hold the lease lock over allocation, renderer
                        // waits, draw or buffer queueing. Native UI handoffs must
                        // be able to revoke a writer while the GPU is busy.
                        canvas = output.surface.lockHardwareCanvas()
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        canvas.scale(canvas.width.toFloat()/owner.canvasWidth,canvas.height.toFloat()/owner.canvasHeight)
                        for (draw in owner.draws) {
                            val save = canvas.save()
                            canvas.concat(draw.matrix)
                            canvas.clipRect(draw.clip)
                            sampling.set(Sample(owner,draw,pose))
                            try { draw.render(canvas) } finally { sampling.remove();canvas.restoreToCount(save) }
                        }
                    } catch (t: Throwable) {
                        synchronized(lock){if(frames[surface]===frame){frames.remove(surface);captures.remove(surface);owner.lease?.let {releaseChild(it.control)}}}
                        GlassLogger.e("LiveSurface", "Material lease ended", t)
                    } finally {
                        if (canvas != null)try {
                            output.surface.unlockCanvasAndPost(canvas)
                            synchronized(lock){
                                if(frames[surface]===frame && tokens[surface]===owner.token && leases[surface]===owner.lease){
                                    output.show()
                                    frame.wallpaper=wallpaper;frame.last=now;frame.poseVersion=poseVersion;frame.redraws++
                                    frame.clock.record(nowNs,fps)
                                }
                            }
                        }catch(_:Throwable){}
                        Trace.endSection()
                    }
            }
            synchronized(lock){refreshActive()}
            if (active) LauncherWallpaperCapture.startMotionWatch()
            else if (!LiveBlurEngine.isResumed) LauncherWallpaperCapture.stopMotionWatch()
        }
    }

    private fun beginCapture(param:XC_MethodHook.MethodHookParam,surface:Surface,sourceView:View?) {
        // Retire synchronously, before the OEM can submit a new buffer.
        synchronized(lock) {
            frames.remove(surface); captures.remove(surface)
            leases[surface]?.let {releaseChild(it.control)}
            refreshActive()
            val token = Any(); tokens[surface] = token
            if (!PrefsHelper.enabled || !surface.isValid) return
            val source = sourceView
            val capture = Capture(surface, source, source?.tag, leases[surface], record.get(), token)
            captures[surface] = capture
            param.setObjectExtra("capture", capture)
            param.setObjectExtra("priorCapture", recording.get()); recording.set(capture)
        }
    }
    private fun endCapture(param:XC_MethodHook.MethodHookParam) {
        val capture = param.getObjectExtra("capture") as? Capture ?: return
        recording.set(param.getObjectExtra("priorCapture") as? Capture)
        if (param.hasThrowable() || capture.draws.isEmpty() || capture.lease == null) {
            synchronized(lock) {
                if (captures[capture.surface] === capture) {
                    captures.remove(capture.surface); tokens.remove(capture.surface)
                }
            }
            return
        }
        synchronized(lock) {
            if (tokens[capture.surface] !== capture.token || leases[capture.surface] !== capture.lease) return
            capture.recordId?.let {templates[it]=capture}
            frames[capture.surface] = Frame(capture, SystemClock.uptimeMillis(), null)
            refreshActive()
            GlassLogger.i("LiveSurface", "Leased material: record=${capture.recordId}, draws=${capture.draws.size}, source=${capture.source?.javaClass?.simpleName}")
        }
        main.postDelayed({retire("unused preparation") {it===capture && !hasNativePose(it)}},4000L)
        schedule()
    }

    fun install(loader: ClassLoader) {
        val renderer = XposedHelpers.findClassIfExists("com.android.launcher3.anim.iconsurfacemanager.surface.IconSurfaceRenderer", loader)
        usesLegacyRenderer=renderer==null
        val namespace=if(renderer!=null)"com.android.launcher3.anim.iconsurfacemanager.surface" else "com.android.launcher3.anim.iconsurfacemanager"
        val type=XposedHelpers.findClassIfExists("$namespace.IconSurfaceRecord",loader) ?: return
        GlassLogger.i("LiveSurface","Native surface integration: $namespace")
        for(method in listOf("setPosition","setMatrix"))XposedBridge.hookAllMethods(SurfaceControl.Transaction::class.java,method,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){if(!param.hasThrowable())observeTransform(param,method=="setPosition")}
        })
        // Native record IDs distinguish a late completion callback from a new
        // animation using the same SurfaceControl or clicked View.
        if(renderer!=null)for (method in listOf("transferViewToSurface", "redrawViewToSurface")) {
            XposedBridge.hookAllMethods(renderer, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("priorRecord", record.get()); record.set(param.args.firstOrNull() as? Int)
                }
                override fun afterHookedMethod(param: MethodHookParam) { record.set(param.getObjectExtra("priorRecord") as? Int) }
            })
        }
        // 16.6.13 performs the draw inside IconSurfaceRecord. Its provider
        // returns the exact buffer before lockHardwareCanvas/onDrawShadow.
        // Scope capture to that transfer; never replay the OEM shadow builder.
        if(renderer==null)XposedBridge.hookAllMethods(type,"transferViewToSurface",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                param.setObjectExtra("priorTransfer",legacyTransfer.get());legacyTransfer.set(param)
                param.setObjectExtra("priorRecord",record.get())
                record.set(XposedHelpers.callMethod(param.thisObject,"getRecordId") as? Int)
            }
            override fun afterHookedMethod(param:MethodHookParam){
                try {endCapture(param)}finally{
                    legacyTransfer.set(param.getObjectExtra("priorTransfer") as? MethodHookParam)
                    record.set(param.getObjectExtra("priorRecord") as? Int)
                }
            }
        })
        if(renderer!=null)XposedBridge.hookAllMethods(renderer, "drawViewToSurface", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val surface=param.args.filterIsInstance<Surface>().firstOrNull() ?: return
                beginCapture(param,surface,param.args.filterIsInstance<View>().lastOrNull())
            }
            override fun afterHookedMethod(param: MethodHookParam) {endCapture(param)}
        })
        val provider = XposedHelpers.findClassIfExists("$namespace.IconSurfaceProvider", loader)
        if (provider != null) {
            XposedBridge.hookAllMethods(provider, "getSurfaceControl", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val holder = param.result ?: return
                    try {
                        val surface = XposedHelpers.callMethod(holder, "getSurface") as? Surface ?: return
                        val control = XposedHelpers.callMethod(holder, "getSc") as? SurfaceControl ?: return
                        synchronized(lock) {
                            retire("new buffer") {it.lease?.control===control}
                            releaseChild(control)
                            val lease=Lease(param.thisObject, control)
                            leases[surface]=lease;controls[control]=lease
                        }
                        legacyTransfer.get()?.let {transfer ->
                            beginCapture(transfer,surface,transfer.args.getOrNull(1) as? View)
                        }
                    } catch (t: Throwable) {GlassLogger.e("LiveSurface","Native buffer lease unavailable",t)}
                }
            })
            for (method in listOf("hideSurfaceControl", "resetSurfaceControl", "releaseSurfaceControl")) {
                XposedBridge.hookAllMethods(provider, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val control = param.args.filterIsInstance<SurfaceControl>().firstOrNull() ?: return
                        retire(method) { it.lease?.control === control }
                    }
                    override fun afterHookedMethod(param:MethodHookParam){
                        val control=param.args.filterIsInstance<SurfaceControl>().firstOrNull() ?: return
                        synchronized(lock){releaseChild(control)}
                    }
                })
            }
        }
        XposedBridge.hookAllMethods(type,"reuseIconSurface",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){resume(param.thisObject)}
        })
        XposedBridge.hookAllMethods(type, "showOriginalIcon", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val id = try { XposedHelpers.callMethod(param.thisObject, "getRecordId") as? Int } catch (_: Throwable) { null } ?: return
                synchronized(lock) {
                    param.setObjectExtra("returnedSource",captures.values.firstOrNull {it.recordId==id}?.source)
                }
                retire("showOriginalIcon") { it.recordId == id }
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                if(!param.hasThrowable())IconGlassHook.onNativeIconReturned(param.getObjectExtra("returnedSource") as? View)
            }
        })
    }
}
