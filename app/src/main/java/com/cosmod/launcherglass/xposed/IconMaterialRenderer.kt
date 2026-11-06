package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.util.LruCache
import android.view.View
import android.graphics.drawable.Drawable
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import com.cosmod.launcherglass.util.GlassLogger

/** UI-thread cache: moving wallpaper changes a shader matrix, not icon layers. */
object IconMaterialRenderer {
    private data class Key(
        val mask: Bitmap, val glyph: Bitmap, val width: Int, val height: Int,
        val color: Int, val opacity: Int, val hideGlyph: Boolean, val blurred: Boolean,
        val gradient: Int, val stroke: Int, val shadow: Int, val glyphStroke: Int, val glyphStrokeWidth: Int, val glyphStrokeBrightness: Int,
        val depthBits:Int,val depthStage:Int
    )

    private class Material(val overlay: Bitmap, val foreground:Bitmap?,val mask: BitmapShader?,val rounding:Float,val maskImage:Bitmap) {
        var wallpaperComposite:Shader?=null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = Matrix()
        var source: RectF? = null
        var wallpaper: Bitmap? = null
        var wallpaperShader: BitmapShader? = null
        var wallpaperShaderImage:Bitmap?=null
        var liquid:LiquidGlassShader?=null
        var appliedStyle:GlassStyle?=null
        var appliedHardware:Boolean?=null
        val clock=SampleFrameClock()
        var backgroundNode:RenderNode?=null
        var backgroundEffectKey=-1
        var shaderSerial=0L
        var backgroundSerial=-1L
    }

    // Eviction only drops Java references: recorded display lists can still own
    // textures, so never recycle a bitmap that has been submitted for drawing.
    private val cache = object : LruCache<Key, Material>(24 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Material) = value.overlay.allocationByteCount + (value.foreground?.allocationByteCount ?: 0) + 512
    }
    private class CpuFrame {
        var bitmap:Bitmap?=null
        var wallpaper:Bitmap?=null
        var source:RectF?=null
        var style:GlassStyle?=null
        var finish=-1
        val clock=SampleFrameClock()
    }
    private data class CpuFrameKey(val material:Key,val owner:CpuFrameOwner?,val source:RectF?)
    private val cpuFrames=object:LruCache<CpuFrameKey,CpuFrame>(16*1024*1024){
        override fun sizeOf(key:CpuFrameKey,value:CpuFrame)=(value.bitmap?.allocationByteCount ?: key.material.width*key.material.height*4)+128
    }
    /**
     * [lift]=false keeps the plain wallpaper sample inside a DragView (folder preview items).
     * [foreign]: an OEM decoration (edit-mode select dot) records this element
     * again into its own node; [local] is then relative to that node.
     */
    data class Binding(val view:View,val drawable:Drawable,val local:RectF,val screenW:Int,val screenH:Int,val mini:Boolean=false,val alpha:Int=255,val lift:Boolean=true,val foreign:Boolean=false)
    private class Sampler(val node:RenderNode,var material:Material,var local:RectF,var screenW:Int,var screenH:Int,var style:GlassStyle){
        val world=Matrix()
        var worldValid=false
        var scroll=Float.NaN
        var plateNode:RenderNode?=null
        var wallpaper:Bitmap?=null
        var source:RectF?=null
        var filter:ColorFilter?=null
        var liquid:LiquidGlassShader?=null
        var appliedStyle:GlassStyle?=null
        var mini=false
        var lastUpdateNs=0L
        val clock=SampleFrameClock()
        var finishKey=-1
        var lift:Lift?=null
        // Frame serials (see beginFrame) of creation and of the latest own draw.
        var created=0L
        var bound=0L
    }
    private val samplers=WeakHashMap<View,WeakHashMap<Drawable,Sampler>>()
    private var frameSerial=0L
    /** Called once per launcher pre-draw, before any restoration or recording. */
    @Synchronized fun beginFrame(){frameSerial++}
    /** Identity/value stamp for lift recordings: matrices by value, nodes by identity. */
    private class Stamp(val numbers:FloatArray,val refs:List<Any?>){
        override fun equals(other:Any?):Boolean {
            if(other !is Stamp || !numbers.contentEquals(other.numbers) || refs.size!=other.refs.size)return false
            for(i in refs.indices)if(refs[i]!==other.refs[i])return false
            return true
        }
        override fun hashCode()=numbers.contentHashCode()
    }
    // Lifted backdrop for one sampler of an element inside a DragView. The
    // element's plate draws [slot]; while [shown], the slot is empty and the
    // DragView's host draws [top] -> [wrapper] (mask/lens) -> [scene] (blur).
    private class Lift(owner:View,dragView:View,val sampler:Sampler){
        val owner=WeakReference(owner)
        val dragView=WeakReference(dragView)
        val slot=RenderNode("CherryPick.liftSlot")
        val top=RenderNode("CherryPick.liftPlate")
        val wrapper=RenderNode("CherryPick.liftBackdrop")
        val scene=RenderNode("CherryPick.liftScene")
        val toDrag=Matrix()
        var ready=false
        var shown=false
        var slotSample:Boolean?=null
        var slotW=0
        var slotH=0
        var materialAlpha=255
        var usedFrame=0L
        var topW=0
        var topH=0
        var finishKey=Int.MIN_VALUE
        var blurKey:List<Any?>?=null
        var effectKey:List<Any?>?=null
        var sceneStamp:Stamp?=null
        var wrapperStamp:Stamp?=null
        val wallpaperPaint=Paint(Paint.FILTER_BITMAP_FLAG)
        var wallpaperImage:Bitmap?=null
        var wallpaperShader:BitmapShader?=null
    }
    private class LiftHost {
        val node=RenderNode("CherryPick.liftHost").apply {setClipToBounds(false)}
        var drawn=false
        var stamp:Stamp?=null
    }
    private class Level(val layer:android.view.ViewGroup,val matrix:Matrix,val nodes:List<RenderNode>)
    private val lifted=ArrayList<Lift>()
    private val hosts=WeakHashMap<View,LiftHost>()
    private var liftFrame=0L
    private var liftLogs=0
    private val mainHandler=Handler(Looper.getMainLooper())
    private val pendingFrames=WeakHashMap<View,Runnable>()
    @Synchronized private fun finishSampleRefresh(reference:WeakReference<View>){
        val root=reference.get() ?: return
        pendingFrames.remove(root)
        if(PrefsHelper.enabled && PrefsHelper.blurEnabled &&
            com.cosmod.launcherglass.util.LiveBlurEngine.isResumed && root.isAttachedToWindow && root.isShown){
            IconGlassHook.noteDeferredSampleFrame()
            // In Live mode sample only in native pre-draw, after this frame's
            // animation transforms. A timer must not consume its FPS slot with
            // coordinates from the preceding display frame.
            if(PrefsHelper.blurMode!=com.cosmod.launcherglass.ui.SettingsProvider.BLUR_MODE_LIVE)
                IconGlassHook.invalidateActiveIcons(onlyChangedDrawerSamples=true,beforeDraw=root)
            root.postInvalidateOnAnimation()
        }
    }
    private fun scheduleSampleRefresh(view:View?,remainingNs:Long){
        val root=view?.rootView ?: return
        if(!root.isAttachedToWindow || pendingFrames.containsKey(root))return
        val reference=WeakReference(root)
        val refresh=Runnable {finishSampleRefresh(reference)}
        pendingFrames[root]=refresh
        mainHandler.postDelayed(refresh,((remainingNs+999_999L)/1_000_000L).coerceAtLeast(1L))
    }
    private val outputPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var draws = 0
    private var builds = 0
    private fun freezeEdge(view:View,world:Matrix):Boolean {
        if(PrefsHelper.disableEdgeOptimization || !view.javaClass.name.contains("BubbleTextView"))return false
        val rect=RectF(0f,0f,view.width.toFloat(),view.height.toFloat());world.mapRect(rect)
        val metrics=view.resources.displayMetrics
        return rect.left<-.5f || rect.top<-.5f || rect.right>metrics.widthPixels+.5f || rect.bottom>metrics.heightPixels+.5f
    }
    private fun ensureContents(sampler:Sampler):Boolean {
        if(sampler.node.hasDisplayList())return false
        val canvas=sampler.node.beginRecording(sampler.node.width,sampler.node.height)
        canvas.drawColor(Color.BLACK)
        sampler.node.endRecording()
        GlassLogger.limited("IconMaterial","restoredSample"){"Restored discarded wallpaper sample display list"}
        return true
    }
    private fun restoreSample(sampler:Sampler):Boolean {
        var restored=ensureContents(sampler)
        val plate=sampler.plateNode
        if(plate!=null && !plate.hasDisplayList()){
            // A cached OEM folder preview can retain its outer list. Repair
            // this same nested plate in place rather than relying on another
            // Drawable.draw() call to replace a discarded inner list.
            val canvas=plate.beginRecording(plate.width,plate.height)
            try {
                canvas.drawRenderNode(sampler.lift?.slot ?: sampler.node)
                canvas.drawBitmap(sampler.material.overlay,0f,0f,outputPaint)
            }finally{plate.endRecording()}
            restored=true
        }
        return restored
    }

    /**
     * A sampler is superseded when its view created another sampler for the
     * same material after this one was last drawn: the drawable was replaced
     * by a new instance, e.g. an OEM per-draw constantState copy. Once such a
     * sampler has also lost its display list it belongs to no tree any more.
     * Restoring it would invalidate its view, whose next draw records the next
     * copy and orphans the current one: a frame loop that never settles.
     */
    private fun prune(entries:WeakHashMap<Drawable,Sampler>){
        if(entries.size<2)return
        var stale:ArrayList<Drawable>?=null
        for((drawable,sampler) in entries){
            if(sampler.node.hasDisplayList())continue
            var superseded=false
            for(other in entries.values){
                if(other!==sampler && other.material===sampler.material && other.created>sampler.bound){superseded=true;break}
            }
            if(superseded)(stale ?: ArrayList<Drawable>().also {stale=it}).add(drawable)
        }
        val removed=stale ?: return
        for(drawable in removed){
            val sampler=entries.remove(drawable) ?: continue
            sampler.lift?.let {deactivate(it)}
        }
        GlassLogger.limited("IconMaterial","supersededSample"){"Dropped ${removed.size} superseded wallpaper sample(s) of a replaced drawable"}
    }

    @Synchronized fun clear() {
        releaseAllLifts()
        cpuFrames.evictAll();CpuLiquidGlass.clear()
        pendingFrames.values.forEach {mainHandler.removeCallbacks(it)}
        pendingFrames.clear();cache.evictAll();samplers.clear()
    }
    @Synchronized fun hasBindings(view:View)=samplers[view]?.isNotEmpty()==true
    @Synchronized fun restoreDiscardedSamples(view:View):Boolean {
        val entries=samplers[view] ?: return false
        prune(entries)
        var restored=false
        // Iterate directly: values.toList() is unsafe if a weak key is
        // collected between the collection's size and singleton fast path.
        for(sampler in entries.values){
            restored=restoreSample(sampler) || restored
        }
        return restored
    }
    // OEM snapshots/resource trimming can discard a nested RenderNode while
    // its geometry and wallpaper remain unchanged. A matching repaint stamp
    // alone therefore does not prove that the cached sample is drawable.
    @Synchronized fun needsRecording(view:View):Boolean {
        val entries=samplers[view] ?: return false
        prune(entries)
        for(sampler in entries.values)if(!sampler.node.hasDisplayList() || sampler.plateNode?.hasDisplayList()==false)return true
        return false
    }
    private fun samplerEffect(sampler:Sampler,wallpaper:Bitmap,source:RectF,nowNs:Long=System.nanoTime(),view:View?=null):Boolean {
        val style=sampler.style
        val filter=style.filter
        val finishKey=MaterialFinish.key()
        if(sampler.wallpaper===wallpaper && sampler.source==source && sampler.filter===filter && sampler.appliedStyle==style && sampler.finishKey==finishKey)return true
        // The global blur limit applies to every material. Mini previews may
        // lower that limit to 30; neither draw path bypasses the same clock.
        val fps=MiniIconRefresh.fps(PrefsHelper.liveFps,sampler.mini,PrefsHelper.optimizeMiniIcons)
        if(sampler.source!=null && sampler.appliedStyle==style && sampler.finishKey==finishKey){
            val remaining=sampler.clock.remainingNs(nowNs,fps)
            if(remaining>0L){scheduleSampleRefresh(view,remaining);return false}
        }
        val refracted=if(android.os.Build.VERSION.SDK_INT>=33 && style.liquid) {
            // createShaderEffect() snapshots the shader, so every sampler can
            // share one compiled lens instead of compiling SkSL per icon.
            LiquidGlassShader.shared()?.update(wallpaper,sampler.material.mask!!,source,sampler.node.width,sampler.node.height,filter,sampler.material.rounding,style)
        }else null
        val background=refracted ?: run {
                val shader=GlassGeometry.wallpaperShader(wallpaper)
                shader.setLocalMatrix(Matrix().apply {setRectToRect(source,RectF(0f,0f,sampler.node.width.toFloat(),sampler.node.height.toFloat()),Matrix.ScaleToFit.FILL)})
                ComposeShader(shader,sampler.material.mask!!,PorterDuff.Mode.DST_IN)
            }
        var effect=RenderEffect.createShaderEffect(background)
        if(filter!=null && refracted==null)effect=RenderEffect.createColorFilterEffect(filter,effect)
        sampler.node.setRenderEffect(MaterialFinish.effect(effect,sampler.node.width,sampler.node.height))
        sampler.wallpaper=wallpaper;sampler.source=RectF(source);sampler.filter=filter;sampler.appliedStyle=style
        sampler.lastUpdateNs=nowNs
        sampler.finishKey=finishKey
        sampler.clock.record(nowNs,fps)
        return true
    }
    @Synchronized fun updateSamplers(view:View,wallpaper:Bitmap?,transform:Matrix?=null,nowNs:Long=System.nanoTime()):Boolean {
        if(wallpaper==null)return false
        val entries=samplers[view] ?: return false
        prune(entries)
        if(entries.isEmpty())return false
        val world=transform ?: Matrix().also {
            try {XposedHelpers.callMethod(view,"transformMatrixToGlobal",it)}catch(_:Throwable){return false}
        }
        var restored=false
        val scroll=com.cosmod.launcherglass.util.LiveBlurEngine.wallpaperScrollRatio
        var folderImage:Bitmap?=null
        for(sampler in entries.values){
            restored=restoreSample(sampler) || restored
            val image=if(sampler.style.folder){
                folderImage ?: (com.cosmod.launcherglass.util.WallpaperHelper.getFolderWallpaper(view.context) ?: wallpaper).also {folderImage=it}
            }else wallpaper
            if(sampler.worldValid && sampler.world==world && sampler.scroll==scroll && sampler.wallpaper===image && sampler.appliedStyle==sampler.style && sampler.finishKey==MaterialFinish.key())continue
            val rect=RectF(sampler.local);world.mapRect(rect)
            if(samplerEffect(sampler,image,WallpaperFootprint.source(image,sampler.screenW,sampler.screenH,rect),nowNs,view)){
                sampler.world.set(world);sampler.worldValid=true;sampler.scroll=scroll
            }
        }
        // Also re-record the owner once: a native folder preview can have lost
        // its parent display list in the same resource-trimming operation.
        return !restored
    }

    @Synchronized fun draw(
        canvas: Canvas, bounds: Rect, mask: Bitmap, glyph: Bitmap,
        color: Int, tintAlpha: Int, hideGlyph: Boolean,
        sampleWallpaper: Bitmap?, sampleSource: RectF?,binding:Binding?=null,volume:Boolean=true,style:GlassStyle=GlassStyle.icons(),buildOverlay: (Canvas, Rect, Boolean) -> Unit
    ) {
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        // Blur switched off (radius 0): clear glass. No wallpaper sample at all,
        // neither the blurred Frosted copy nor the Liquid lens: the only source
        // is a low-resolution wallpaper screenshot, which itself looked blurred.
        // The overlay (plate tint, volume, rim) stays; the real wallpaper shows
        // through the translucent plate in full resolution.
        val clear = clearGlass(style)
        val wallpaper = if (clear) null else sampleWallpaper
        val source = if (clear) null else sampleSource
        val blurred = wallpaper != null && source != null
        val key = Key(mask, glyph, width, height, color, tintAlpha, hideGlyph, blurred,
            if(volume)PrefsHelper.gradientOpacity else 0,
            if (!hideGlyph && PrefsHelper.iconStroke) PrefsHelper.strokeOpacity else 0,
            if (PrefsHelper.glyphShadow) PrefsHelper.shadowOpacity else 0,
            if (PrefsHelper.glyphStroke) PrefsHelper.glyphStrokeOpacity else 0,
            if (PrefsHelper.glyphStroke) PrefsHelper.glyphStrokeWidth else 0,
            if (PrefsHelper.glyphStroke) PrefsHelper.glyphStrokeBrightness else 0,PrefsHelper.colorDepthBits,PrefsHelper.colorDepthStage)
        if(!PrefsHelper.gpuEffects && canvas.isHardwareAccelerated){
            // Identical icons can occupy different wallpaper regions and need independent FPS clocks.
            val frameKey=CpuFrameKey(key,binding?.let {CpuFrameOwner(it.view,it.drawable)},
                if(binding==null)source?.let(::RectF)else null)
            var frame=cpuFrames.get(frameKey)
            if(frame==null){frame=CpuFrame();cpuFrames.put(frameKey,frame)}
            if(binding?.foreign==true)frame.bitmap?.let {canvas.drawBitmap(it,null,bounds,outputPaint);return}
            var footprint=source
            if(binding!=null && wallpaper!=null && !binding.foreign){
                val world=Matrix()
                try {
                    XposedHelpers.callMethod(binding.view,"transformMatrixToGlobal",world)
                    val rect=RectF(binding.local);world.mapRect(rect)
                    footprint=WallpaperFootprint.source(wallpaper,binding.screenW,binding.screenH,rect)
                }catch(_:Throwable){}
            }
            val changed=frame.bitmap==null || frame.wallpaper!==wallpaper || frame.source!=footprint || frame.style!=style || frame.finish!=MaterialFinish.key()
            val now=System.nanoTime()
            val fps=MiniIconRefresh.fps(PrefsHelper.liveFps,binding?.mini==true,PrefsHelper.optimizeMiniIcons)
            val remaining=frame.clock.remainingNs(now,fps)
            if(changed && (frame.bitmap==null || remaining==0L)){
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val software=wallpaper?.let {com.cosmod.launcherglass.util.WallpaperHelper.forCanvas(it,Canvas(bitmap))}
                draw(Canvas(bitmap),Rect(0,0,width,height),mask,glyph,color,tintAlpha,hideGlyph,software,footprint,null,volume,style,buildOverlay)
                frame.bitmap=bitmap;frame.wallpaper=wallpaper;frame.source=footprint?.let(::RectF);frame.style=style;frame.finish=MaterialFinish.key();frame.clock.record(now,fps)
            }else if(changed)scheduleSampleRefresh(binding?.view,remaining)
            frame.bitmap?.let {canvas.drawBitmap(it,null,bounds,outputPaint)}
            return
        }
        var material = cache.get(key)
        if (material == null) {
            val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val maskShader = if (blurred) BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                if(android.os.Build.VERSION.SDK_INT>=33)setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                setLocalMatrix(Matrix().apply { setScale(width.toFloat() / mask.width, height.toFloat() / mask.height) })
            } else null
            val target = Canvas(overlay)
            if (maskShader != null) {
                // Bake permanent tint and shading once; the moving GPU shader
                // then samples only wallpaper and alpha mask.
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                if (tintAlpha > 0) {
                    val tint = (tintAlpha.coerceIn(0, 255) shl 24) or (color and 0x00ffffff)
                    paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    target.drawBitmap(mask, null, Rect(0, 0, width, height), paint)
                    paint.colorFilter = null
                }
                if (key.gradient > 0) {
                    val gradient = com.cosmod.launcherglass.util.GlassVolume.shader(height.toFloat(), key.gradient)
                    paint.isDither = true
                    paint.shader = ComposeShader(gradient, maskShader, PorterDuff.Mode.DST_IN)
                    target.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                }
            }
            // Quantize the completed plate while preserving foreground artwork.
            val foreground=if(blurred && ColorDepthEffect.finalPlate && !hideGlyph)Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)else null
            buildOverlay(foreground?.let {Canvas(it)} ?: target, Rect(0, 0, width, height), blurred)
            material = Material(overlay,foreground,maskShader,GlassGeometry.rounding(mask),mask)
            cache.put(key, material)
            builds++
        }
        val save = canvas.save()
        try {
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            val finalDepth=blurred && ColorDepthEffect.finalPlate
            val depthNode=if(finalDepth && canvas.isHardwareAccelerated)
                RenderNode("CherryPick.colorDepth").apply {
                    setPosition(0,0,width,height)
                    if(android.os.Build.VERSION.SDK_INT>=33)setRenderEffect(ColorDepthEffect.effect(PrefsHelper.colorDepthBits))
                } else null
            val softwarePlate=if(finalDepth && !canvas.isHardwareAccelerated)Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)else null
            val plate=depthNode?.beginRecording(width,height) ?: softwarePlate?.let(::Canvas) ?: canvas
            try {
            if (wallpaper != null && source != null && material.mask != null) {
                if(binding?.foreign==true && canvas.isHardwareAccelerated){
                    // Never create, move or re-time a sampler from an OEM
                    // decoration recording; show the element's own sample.
                    foreignSampler(binding,material,width,height)?.let {
                        ensureContents(it)
                        plate.drawRenderNode(it.node)
                    }
                }else if(binding!=null && canvas.isHardwareAccelerated){
                    val entries=samplers.getOrPut(binding.view){WeakHashMap()}
                    var sampler=entries[binding.drawable]
                    if(sampler==null){
                        val node=RenderNode("CherryPick.wallpaperSample")
                        node.setPosition(0,0,width,height)
                        val recording=node.beginRecording(width,height)
                        recording.drawColor(Color.BLACK)
                        node.endRecording()
                        sampler=Sampler(node,material,RectF(binding.local),binding.screenW,binding.screenH,style)
                        sampler.created=frameSerial
                        entries[binding.drawable]=sampler
                    }else if(sampler.material!==material || sampler.node.width!=width || sampler.node.height!=height){
                        // Reuse the node: a replacement per shape change rebuilt
                        // the effect chain and display list on every drag frame.
                        if(sampler.node.width!=width || sampler.node.height!=height){
                            sampler.node.setPosition(0,0,width,height)
                            val recording=sampler.node.beginRecording(width,height)
                            recording.drawColor(Color.BLACK)
                            sampler.node.endRecording()
                        }
                        sampler.material=material
                        // Apply the new mask now, outside the FPS clock.
                        sampler.source=null;sampler.wallpaper=null;sampler.appliedStyle=null;sampler.worldValid=false
                    }
                    if(sampler.local!=binding.local){sampler.local.set(binding.local);sampler.worldValid=false}
                    if(sampler.screenW!=binding.screenW || sampler.screenH!=binding.screenH){
                        sampler.screenW=binding.screenW;sampler.screenH=binding.screenH
                        sampler.source=null;sampler.worldValid=false
                    }
                    sampler.style=style;sampler.plateNode=depthNode;sampler.mini=binding.mini;sampler.bound=frameSerial
                    ensureContents(sampler)
                    val rect=RectF(binding.local)
                    val world=Matrix()
                    try {XposedHelpers.callMethod(binding.view,"transformMatrixToGlobal",world);world.mapRect(rect)}catch(_:Throwable){rect.set(0f,0f,width.toFloat(),height.toFloat())}
                    // Native overscroll can re-record the icon even when the
                    // live updater skips it. Retain its existing edge sample.
                    if(sampler.source==null || !freezeEdge(binding.view,world))
                        samplerEffect(sampler,wallpaper,WallpaperFootprint.source(wallpaper,binding.screenW,binding.screenH,rect),view=binding.view)
                    plate.drawRenderNode(liftNode(sampler,binding) ?: sampler.node)
                }else{
                    val nowNs=System.nanoTime()
                    val fps=MiniIconRefresh.fps(PrefsHelper.liveFps,false,false)
                    val hardware=canvas.isHardwareAccelerated
                    val changed=material.wallpaper!==wallpaper || material.source!=source || material.appliedStyle!=style || material.appliedHardware!=hardware
                    // Software canvases are often one-shot launcher snapshots:
                    // there may be no later draw on which to repair a stale
                    // crop. Apply the current source immediately. This does
                    // not turn a baked drag bitmap into a live backdrop.
                    if(changed && (!hardware || material.paint.shader==null || material.appliedStyle!=style || material.appliedHardware!=hardware || material.clock.remainingNs(nowNs,fps)==0L)){
                    val refracted=if(style.liquid && !canvas.isHardwareAccelerated){
                        CpuLiquidGlass.shader(wallpaper,mask,width,height,source,material.rounding,style)
                    }else if(android.os.Build.VERSION.SDK_INT>=33 && style.liquid && canvas.isHardwareAccelerated){
                        val lens=material.liquid ?: LiquidGlassShader.create().also {material.liquid=it}
                        lens?.update(wallpaper,material.mask!!,source,width,height,style.filter,material.rounding,style)
                    }else null
                    if(refracted!=null){
                        material.paint.shader=refracted
                        material.paint.colorFilter=if(canvas.isHardwareAccelerated)null else style.filter
                    }else{
                        if (material.wallpaperShaderImage !== wallpaper) {
                            material.wallpaperShaderImage = wallpaper
                            material.wallpaperShader = GlassGeometry.wallpaperShader(wallpaper)
                        }
                        material.matrix.setRectToRect(RectF(source), RectF(0f,0f,width.toFloat(),height.toFloat()), Matrix.ScaleToFit.FILL)
                        material.wallpaperShader!!.setLocalMatrix(material.matrix)
                        material.wallpaperComposite = ComposeShader(material.wallpaperShader!!, material.mask!!, PorterDuff.Mode.DST_IN)
                    material.paint.shader=material.wallpaperComposite
                    material.paint.colorFilter = style.filter
                    }
                    material.wallpaper=wallpaper;material.source=RectF(source);material.appliedStyle=style
                    material.appliedHardware=hardware
                    material.clock.record(nowNs,fps)
                    material.shaderSerial++
                    }
                    if(MaterialFinish.needed()){
                        if(canvas.isHardwareAccelerated){
                            val node=material.backgroundNode ?: RenderNode("CherryPick.opticalFinish").apply {
                                setPosition(0,0,width,height);material.backgroundNode=this
                            }
                            val finishKey=MaterialFinish.key()
                            if(material.backgroundEffectKey!=finishKey){
                                node.setRenderEffect(MaterialFinish.effect(null,width,height));material.backgroundEffectKey=finishKey
                            }
                            if(!node.hasDisplayList() || material.backgroundSerial!=material.shaderSerial){
                                val recording=node.beginRecording(width,height)
                                try {recording.drawRect(0f,0f,width.toFloat(),height.toFloat(),material.paint)}finally{node.endRecording()}
                                material.backgroundSerial=material.shaderSerial
                            }
                            plate.drawRenderNode(node)
                        }else{
                            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                            Canvas(bitmap).drawRect(0f,0f,width.toFloat(),height.toFloat(),material.paint)
                            val finished=MaterialFinish.applyCpu(bitmap)
                            plate.drawBitmap(finished,0f,0f,outputPaint)
                            if(finished!==bitmap)finished.recycle()
                            bitmap.recycle()
                        }
                    }else plate.drawRect(0f,0f,width.toFloat(),height.toFloat(),material.paint)
                }
            }
            plate.drawBitmap(material.overlay, 0f, 0f, outputPaint)
            } finally {depthNode?.endRecording()}
            depthNode?.let {canvas.drawRenderNode(it)}
            softwarePlate?.let {
                val processed=ColorDepthEffect.applyCpu(it,PrefsHelper.colorDepthBits)
                canvas.drawBitmap(processed,0f,0f,outputPaint)
                if(processed!==it)processed.recycle()
                it.recycle()
            }
            material.foreground?.let {canvas.drawBitmap(it,0f,0f,outputPaint)}
        } finally { canvas.restoreToCount(save) }
        draws++
        if (draws == 1 || draws % 2000 == 0) {
            GlassLogger.i("IconMaterial", "Cached icon material: draws=$draws, builds=$builds, bytes=${cache.size()}")
        }
    }

    /** Clear glass: the blur of this material is switched off. */
    fun clearGlass(style:GlassStyle)=style.radius<=0

    /** The sampler an element's own draw bound for [material], preferring its latest. */
    private fun foreignSampler(binding:Binding,material:Material,width:Int,height:Int):Sampler? {
        val entries=samplers[binding.view] ?: return null
        entries[binding.drawable]?.let {if(it.material===material)return it}
        var match:Sampler?=null
        for(sampler in entries.values){
            if(sampler.material!==material || sampler.node.width!=width || sampler.node.height!=height)continue
            if(match==null || sampler.bound>match.bound)match=sampler
        }
        return match
    }

    // ---- Lifted backdrop -------------------------------------------------

    private fun values(matrix:Matrix):FloatArray=FloatArray(9).also {matrix.getValues(it)}

    /** Slot for a plate recorded by its own view inside a DragView, or null for the plain sample. */
    private fun liftNode(sampler:Sampler,binding:Binding):RenderNode? {
        if(binding.mini || !binding.lift || !LiftedBackdrop.allowed())return null
        val owner=binding.view
        // A foreign/inline draw of the same element keeps the plain sample.
        if(!LiftedBackdrop.ownRecording(owner))return null
        var lift=sampler.lift
        val dragView=LiftedBackdrop.dragAncestor(owner)
        if(dragView==null || !LiftedBackdrop.permits(dragView)){
            lift?.let {deactivate(it)}
            return null
        }
        if(lift!=null && (lift.dragView.get()!==dragView || lift.owner.get()!==owner)){deactivate(lift);lift=null}
        if(lift==null){
            lift=Lift(owner,dragView,sampler)
            sampler.lift=lift
            lifted.add(lift)
            LiftedBackdrop.onLiftCount(lifted.size)
            if(liftLogs++<12)GlassLogger.i("LiftedBackdrop","Lift: ${owner.javaClass.simpleName} in ${dragView.javaClass.simpleName}, plate=${sampler.node.width}x${sampler.node.height}, liquid=${sampler.style.liquid}")
        }
        lift.materialAlpha=binding.alpha
        lift.usedFrame=liftFrame
        try {
            apply(lift,dragView)
        }catch(t:Throwable){
            LiftedBackdrop.fail(t)
            return null
        }
        return lift.slot
    }

    /** Refreshes [lift] and switches between the host backdrop and the plain sample. */
    private fun apply(lift:Lift,dragView:View){
        lift.ready=refreshLift(lift)
        val host=hosts[dragView]
        val show=lift.ready && host?.drawn==true
        if(host!=null)recordHost(dragView,host)
        // The host enters the DragView's display list on its next recording.
        if(lift.ready && host?.drawn!=true)dragView.postInvalidateOnAnimation()
        recordSlot(lift,!show)
        lift.shown=show
    }

    private fun recordSlot(lift:Lift,sample:Boolean){
        val node=lift.sampler.node
        if(lift.slotSample==sample && lift.slotW==node.width && lift.slotH==node.height && lift.slot.hasDisplayList())return
        lift.slot.setPosition(0,0,node.width,node.height)
        val canvas=lift.slot.beginRecording(node.width,node.height)
        try {if(sample)canvas.drawRenderNode(node)}finally{lift.slot.endRecording()}
        lift.slotSample=sample;lift.slotW=node.width;lift.slotH=node.height
    }

    private fun recordHost(dragView:View,host:LiftHost){
        val numbers=ArrayList<Float>()
        val refs=ArrayList<Any?>()
        numbers.add(dragView.width.toFloat());numbers.add(dragView.height.toFloat())
        for(lift in lifted){
            if(!lift.ready || lift.dragView.get()!==dragView)continue
            refs.add(lift.top)
            values(lift.toDrag).forEach {numbers.add(it)}
        }
        val stamp=Stamp(numbers.toFloatArray(),refs)
        if(host.stamp==stamp && host.node.hasDisplayList())return
        host.node.setPosition(0,0,dragView.width.coerceAtLeast(1),dragView.height.coerceAtLeast(1))
        val canvas=host.node.beginRecording(dragView.width.coerceAtLeast(1),dragView.height.coerceAtLeast(1))
        try {
            for(lift in lifted){
                if(!lift.ready || lift.dragView.get()!==dragView)continue
                val save=canvas.save()
                canvas.concat(lift.toDrag)
                canvas.drawRenderNode(lift.top)
                canvas.restoreToCount(save)
            }
        }finally{host.node.endRecording()}
        host.stamp=stamp
    }

    /** Called from DragView.draw() while it records its own RenderNode. */
    @Synchronized fun drawLiftHost(dragView:View,canvas:Canvas,permitted:Boolean){
        if(!permitted){
            hosts[dragView]?.drawn=false
            releaseLifts(dragView)
            return
        }
        val host=hosts.getOrPut(dragView){LiftHost()}
        for(lift in lifted.weakSnapshot())if(lift.dragView.get()===dragView)lift.ready=refreshLift(lift)
        recordHost(dragView,host)
        canvas.drawRenderNode(host.node)
        host.drawn=true
        for(lift in lifted)if(lift.dragView.get()===dragView){recordSlot(lift,!lift.ready);lift.shown=lift.ready}
    }

    private fun sceneLevels(dragView:View):List<Level>? {
        val levels=ArrayList<Level>()
        var child:View=dragView
        repeat(6){
            val layer=child.parent as? android.view.ViewGroup ?: return null
            val pathIndex=layer.indexOfChild(child)
            val pathZ=child.z
            val nodes=ArrayList<RenderNode>()
            for(i in 0 until layer.childCount){
                val sibling=layer.getChildAt(i) ?: continue
                if(sibling===child || sibling.visibility!=View.VISIBLE || LiftedBackdrop.isDragView(sibling))continue
                // Only what the parent draws before the lifted path.
                val z=sibling.z
                if(z>pathZ || (z==pathZ && i>pathIndex))continue
                val node=LiftedBackdrop.renderNode(sibling) ?: continue
                if(node.hasDisplayList())nodes.add(node)
            }
            val matrix=Matrix()
            XposedHelpers.callMethod(layer,"transformMatrixToGlobal",matrix)
            levels.add(Level(layer,matrix,nodes))
            if(LiftedBackdrop.isDragLayer(layer))return levels
            child=layer
        }
        return null
    }

    private fun refreshLift(lift:Lift):Boolean {
        if(android.os.Build.VERSION.SDK_INT<33)return false
        val owner=lift.owner.get() ?: return false
        val dragView=lift.dragView.get() ?: return false
        val sampler=lift.sampler
        val material=sampler.material
        val w=sampler.node.width
        val h=sampler.node.height
        if(w<=0 || h<=0 || sampler.local.isEmpty)return false
        // Effective alpha between the plate and its DragView, which applies its own.
        var alpha=lift.materialAlpha.coerceIn(0,255)/255f
        var current:View?=owner
        while(current!=null && current!==dragView){
            if(current.visibility!=View.VISIBLE){alpha=0f;break}
            alpha*=current.alpha*current.transitionAlpha
            current=current.parent as? View
        }
        if(alpha>0f && current!==dragView)return false
        val world=Matrix()
        val dragWorld=Matrix()
        XposedHelpers.callMethod(owner,"transformMatrixToGlobal",world)
        XposedHelpers.callMethod(dragView,"transformMatrixToGlobal",dragWorld)
        val toScreen=Matrix()
        toScreen.setRectToRect(RectF(0f,0f,w.toFloat(),h.toFloat()),sampler.local,Matrix.ScaleToFit.FILL)
        toScreen.postConcat(world)
        val toNode=Matrix()
        val fromScreen=Matrix()
        if(!toScreen.invert(toNode) || !dragWorld.invert(fromScreen))return false
        lift.toDrag.set(toScreen)
        lift.toDrag.postConcat(fromScreen)
        // DragView content is recorded after translate(-scroll).
        lift.toDrag.postTranslate(dragView.scrollX.toFloat(),dragView.scrollY.toFloat())

        val style=sampler.style
        // Clear glass never uses a lifted blur or liquid lens, even if an old
        // sampler survives the preference transition for one frame.
        if(clearGlass(style))return false
        val scale=toNode.mapRadius(1f).coerceIn(.05f,20f)
        val screenRadius=style.radius.coerceIn(0,50)*sampler.screenH/800f*(if(style.liquid).28f else 1f)
        val radius=kotlin.math.round(screenRadius*scale*4f)/4f
        val edge=minOf(w,h).toFloat()
        val liquid=style.liquid && material.mask!=null
        val lens=if(liquid)minOf(edge*.15f*maxOf(style.distortion/50f,.2f),edge*.5f)else 0f
        val margin=kotlin.math.round(1.5f*radius+lens+2f).toInt().coerceIn(4,256)
        val wrapperW=w+2*margin
        val wrapperH=h+2*margin

        val levels=sceneLevels(dragView) ?: return false
        val wallpaper=(if(style.folder)com.cosmod.launcherglass.util.WallpaperHelper.getFolderWallpaper(owner.context)
            else com.cosmod.launcherglass.util.WallpaperHelper.getBlurredWallpaper(owner.context)) ?: return false
        if(wallpaper.isRecycled || wallpaper.width<=0 || wallpaper.height<=0)return false

        // Scene: launcher layers below the DragView, mapped into plate space.
        val processing=if(style.folder && FolderSettings.own)FolderSettings.processing else null
        val vibrancy=processing?.vibrancy ?: PrefsHelper.blurVibrancy
        val lightening=processing?.lightening ?: PrefsHelper.blurLightening
        val blurKey=listOf<Any?>(radius,vibrancy,lightening,PrefsHelper.colorDepthBits,PrefsHelper.colorDepthStage)
        if(lift.blurKey!=blurKey){
            val vivid=ColorDepthEffect.at(com.cosmod.launcherglass.util.BackdropVibrancy.effect(vibrancy),ColorDepthEffect.BEFORE_BLUR)
            val blur=if(radius>0f){
                if(vivid!=null)RenderEffect.createBlurEffect(radius,radius,vivid,Shader.TileMode.DECAL)
                else RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.DECAL)
            }else vivid
            lift.scene.setRenderEffect(ColorDepthEffect.at(com.cosmod.launcherglass.util.ShadowTone.effect(blur,lightening),ColorDepthEffect.AFTER_BLUR))
            lift.blurKey=blurKey
        }
        val sceneNumbers=ArrayList<Float>()
        val sceneRefs=ArrayList<Any?>()
        values(toNode).forEach {sceneNumbers.add(it)}
        sceneNumbers.add(margin.toFloat());sceneNumbers.add(wrapperW.toFloat());sceneNumbers.add(wrapperH.toFloat())
        for(level in levels){
            values(level.matrix).forEach {sceneNumbers.add(it)}
            sceneNumbers.add(level.layer.scrollX.toFloat());sceneNumbers.add(level.layer.scrollY.toFloat())
            sceneRefs.add(level.layer);sceneRefs.addAll(level.nodes);sceneRefs.add(null)
        }
        val sceneStamp=Stamp(sceneNumbers.toFloatArray(),sceneRefs)
        if(lift.sceneStamp!=sceneStamp || !lift.scene.hasDisplayList()){
            lift.scene.setPosition(0,0,wrapperW,wrapperH)
            val canvas=lift.scene.beginRecording(wrapperW,wrapperH)
            try {
                canvas.translate(margin.toFloat(),margin.toFloat())
                canvas.concat(toNode)
                for(level in levels.asReversed()){
                    val save=canvas.save()
                    canvas.concat(level.matrix)
                    canvas.translate(-level.layer.scrollX.toFloat(),-level.layer.scrollY.toFloat())
                    for(node in level.nodes)canvas.drawRenderNode(node)
                    canvas.restoreToCount(save)
                }
            }finally{lift.scene.endRecording()}
            lift.sceneStamp=sceneStamp
        }

        // Wrapper: processed wallpaper under the blurred scene, then mask/lens.
        val wallpaperScale=maxOf(sampler.screenW.toFloat()/wallpaper.width,sampler.screenH.toFloat()/wallpaper.height)
        val offset=(wallpaper.width-sampler.screenW/wallpaperScale).coerceAtLeast(0f)*com.cosmod.launcherglass.util.LiveBlurEngine.wallpaperScrollRatio
        val wallpaperMatrix=Matrix()
        wallpaperMatrix.setTranslate(-offset,0f)
        wallpaperMatrix.postScale(wallpaperScale,wallpaperScale)
        wallpaperMatrix.postConcat(toNode)
        wallpaperMatrix.postTranslate(margin.toFloat(),margin.toFloat())
        val wrapperStamp=Stamp(values(wallpaperMatrix)+floatArrayOf(wrapperW.toFloat(),wrapperH.toFloat()),listOf(wallpaper,lift.scene))
        if(lift.wrapperStamp!=wrapperStamp || !lift.wrapper.hasDisplayList()){
            lift.wrapper.setPosition(-margin,-margin,w+margin,h+margin)
            if(lift.wallpaperImage!==wallpaper){
                lift.wallpaperShader=GlassGeometry.wallpaperShader(wallpaper)
                lift.wallpaperImage=wallpaper
            }
            val shader=lift.wallpaperShader!!
            shader.setLocalMatrix(wallpaperMatrix)
            lift.wallpaperPaint.shader=shader
            val canvas=lift.wrapper.beginRecording(wrapperW,wrapperH)
            try {
                canvas.drawRect(0f,0f,wrapperW.toFloat(),wrapperH.toFloat(),lift.wallpaperPaint)
                canvas.drawRenderNode(lift.scene)
            }finally{lift.wrapper.endRecording()}
            lift.wrapperStamp=wrapperStamp
        }
        val effectKey=listOf<Any?>(material,w,h,margin,style,liquid)
        if(lift.effectKey!=effectKey){
            val lensEffect=if(liquid)LiquidGlassShader.lifted()?.let {
                RenderEffect.createRuntimeShaderEffect(it.updateLifted(material.mask!!,w,h,margin.toFloat(),wrapperW,wrapperH,style.filter,material.rounding,style),"backdrop")
            } else null
            val effect=lensEffect ?: run {
                val image=material.maskImage
                val mask=BitmapShader(image,Shader.TileMode.DECAL,Shader.TileMode.DECAL).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    setLocalMatrix(Matrix().apply {
                        setScale(w.toFloat()/image.width,h.toFloat()/image.height)
                        postTranslate(margin.toFloat(),margin.toFloat())
                    })
                }
                val content=style.filter?.let {RenderEffect.createColorFilterEffect(it)} ?: RenderEffect.createOffsetEffect(0f,0f)
                RenderEffect.createBlendModeEffect(content,RenderEffect.createShaderEffect(mask),BlendMode.DST_IN)
            }
            lift.wrapper.setRenderEffect(effect)
            lift.effectKey=effectKey
        }

        // Top: plate-sized optical finish, alpha of the element.
        val finishKey=if(MaterialFinish.needed())MaterialFinish.key()else -1
        if(lift.finishKey!=finishKey || lift.topW!=w || lift.topH!=h){
            lift.top.setPosition(0,0,w,h)
            lift.top.setRenderEffect(if(finishKey>=0)MaterialFinish.effect(null,w,h)else null)
            lift.finishKey=finishKey
        }
        if(lift.topW!=w || lift.topH!=h || !lift.top.hasDisplayList()){
            val canvas=lift.top.beginRecording(w,h)
            try {canvas.drawRenderNode(lift.wrapper)}finally{lift.top.endRecording()}
            lift.topW=w;lift.topH=h
        }
        lift.top.setAlpha(alpha.coerceIn(0f,1f))
        return true
    }

    private fun deactivate(lift:Lift){
        lifted.remove(lift)
        if(lift.sampler.lift===lift)lift.sampler.lift=null
        try {
            // Restore the plain sample before the host drops this backdrop.
            recordSlot(lift,true)
            lift.ready=false;lift.shown=false
            lift.dragView.get()?.let {dragView -> hosts[dragView]?.let {recordHost(dragView,it)}}
            lift.top.setRenderEffect(null);lift.wrapper.setRenderEffect(null);lift.scene.setRenderEffect(null)
            lift.top.discardDisplayList();lift.wrapper.discardDisplayList();lift.scene.discardDisplayList()
        }catch(t:Throwable){
            GlassLogger.limited("LiftedBackdrop","release"){"Lift release failed: $t"}
        }
        LiftedBackdrop.onLiftCount(lifted.size)
    }

    @Synchronized fun releaseLifts(dragView:View){
        for(lift in lifted.weakSnapshot())if(lift.dragView.get().let {it==null || it===dragView})deactivate(lift)
    }
    @Synchronized fun releaseAllLifts(){
        for(lift in lifted.weakSnapshot())deactivate(lift)
    }

    /** Pre-draw: validate lifts and follow this frame's final transforms. */
    @Synchronized fun updateLifted(root:View){
        liftFrame++
        if(lifted.isEmpty())return
        if(!LiftedBackdrop.allowed()){releaseAllLifts();return}
        try {
            // A lift not drawn by its element's latest recording belongs to a
            // replaced drawable; the element no longer draws its slot.
            val latest=HashMap<View,Long>()
            for(lift in lifted){
                val owner=lift.owner.get() ?: continue
                latest[owner]=maxOf(latest[owner] ?: 0L,lift.usedFrame)
            }
            for(lift in lifted.weakSnapshot()){
                val owner=lift.owner.get()
                val dragView=lift.dragView.get()
                val valid=owner!=null && dragView!=null && owner.isAttachedToWindow && dragView.isAttachedToWindow &&
                    lift.usedFrame>=(latest[owner] ?: 0L) && samplers[owner]?.containsValue(lift.sampler)==true &&
                    LiftedBackdrop.dragAncestor(owner)===dragView && LiftedBackdrop.permits(dragView)
                if(!valid){deactivate(lift);continue}
                if(owner!!.rootView!==root)continue
                apply(lift,dragView!!)
            }
        }catch(t:Throwable){
            LiftedBackdrop.fail(t)
        }
    }
}

internal object WallpaperFootprint {
    fun source(bitmap:Bitmap,screenW:Int,screenH:Int,rect:RectF):RectF {
        val scale=maxOf(screenW.toFloat()/bitmap.width,screenH.toFloat()/bitmap.height)
        val offset=(bitmap.width-screenW/scale).coerceAtLeast(0f)*com.cosmod.launcherglass.util.LiveBlurEngine.wallpaperScrollRatio
        // Preserve the complete footprint while the View crosses the viewport.
        // Cropping the source and mapping it back onto the full View stretches
        // the remaining pixels. BitmapShader.CLAMP handles off-texture samples.
        return RectF(offset+rect.left/scale,rect.top/scale,offset+rect.right/scale,rect.bottom/scale)
    }
}
