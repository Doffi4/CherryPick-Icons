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

    private class Material(val overlay: Bitmap, val foreground:Bitmap?,val mask: BitmapShader?,val rounding:Float) {
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
    private val cpuFrames=object:LruCache<Key,CpuFrame>(16*1024*1024){
        override fun sizeOf(key:Key,value:CpuFrame)=(value.bitmap?.allocationByteCount ?: key.width*key.height*4)+128
    }
    data class Binding(val view:View,val drawable:Drawable,val local:RectF,val screenW:Int,val screenH:Int,val mini:Boolean=false)
    private class Sampler(val node:RenderNode,val material:Material,var local:RectF,val screenW:Int,val screenH:Int,var style:GlassStyle){
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
    }
    private val samplers=WeakHashMap<View,WeakHashMap<Drawable,Sampler>>()
    private val mainHandler=Handler(Looper.getMainLooper())
    private val pendingFrames=WeakHashMap<View,Runnable>()
    @Synchronized private fun finishSampleRefresh(reference:WeakReference<View>){
        val root=reference.get() ?: return
        pendingFrames.remove(root)
        if(PrefsHelper.enabled && PrefsHelper.blurEnabled &&
            com.cosmod.launcherglass.util.LiveBlurEngine.isResumed && root.isAttachedToWindow && root.isShown){
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
        GlassLogger.i("IconMaterial","Restored discarded wallpaper sample display list")
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
                canvas.drawRenderNode(sampler.node)
                canvas.drawBitmap(sampler.material.overlay,0f,0f,outputPaint)
            }finally{plate.endRecording()}
            restored=true
        }
        return restored
    }

    @Synchronized fun clear() {
        cpuFrames.evictAll();CpuLiquidGlass.clear()
        pendingFrames.values.forEach {mainHandler.removeCallbacks(it)}
        pendingFrames.clear();cache.evictAll();samplers.clear()
    }
    @Synchronized fun hasBindings(view:View)=samplers[view]?.isNotEmpty()==true
    @Synchronized fun restoreDiscardedSamples(view:View):Boolean {
        val entries=samplers[view] ?: return false
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
    @Synchronized fun needsRecording(view:View)=samplers[view]?.values?.any {!it.node.hasDisplayList() || it.plateNode?.hasDisplayList()==false}==true
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
            val lens=sampler.liquid ?: LiquidGlassShader.create().also {sampler.liquid=it}
            lens?.update(wallpaper,sampler.material.mask!!,source,sampler.node.width,sampler.node.height,filter,sampler.material.rounding,style)
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
        wallpaper: Bitmap?, source: RectF?,binding:Binding?=null,volume:Boolean=true,style:GlassStyle=GlassStyle.icons(),buildOverlay: (Canvas, Rect, Boolean) -> Unit
    ) {
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val blurred = wallpaper != null && source != null
        val key = Key(mask, glyph, width, height, color, tintAlpha, hideGlyph, blurred,
            if(volume)PrefsHelper.gradientOpacity else 0,
            if (!hideGlyph && PrefsHelper.iconStroke) PrefsHelper.strokeOpacity else 0,
            if (PrefsHelper.glyphShadow) PrefsHelper.shadowOpacity else 0,
            if (PrefsHelper.glyphStroke) PrefsHelper.glyphStrokeOpacity else 0,
            if (PrefsHelper.glyphStroke) PrefsHelper.glyphStrokeWidth else 0,
            if (PrefsHelper.glyphStroke) PrefsHelper.glyphStrokeBrightness else 0,PrefsHelper.colorDepthBits,PrefsHelper.colorDepthStage)
        if(!PrefsHelper.gpuEffects && canvas.isHardwareAccelerated){
            var frame=cpuFrames.get(key)
            if(frame==null){frame=CpuFrame();cpuFrames.put(key,frame)}
            var footprint=source
            if(binding!=null && wallpaper!=null){
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
                    paint.shader = ComposeShader(gradient, maskShader, PorterDuff.Mode.DST_IN)
                    target.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                }
            }
            // Quantize the completed plate while preserving foreground artwork.
            val foreground=if(blurred && ColorDepthEffect.finalPlate && !hideGlyph)Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)else null
            buildOverlay(foreground?.let {Canvas(it)} ?: target, Rect(0, 0, width, height), blurred)
            material = Material(overlay,foreground,maskShader,GlassGeometry.rounding(mask))
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
                if(binding!=null && canvas.isHardwareAccelerated){
                    val entries=samplers.getOrPut(binding.view){WeakHashMap()}
                    var sampler=entries[binding.drawable]
                    if(sampler==null || sampler.material!==material || sampler.node.width!=width || sampler.node.height!=height){
                        val node=RenderNode("CherryPick.wallpaperSample")
                        node.setPosition(0,0,width,height)
                        val recording=node.beginRecording(width,height)
                        recording.drawColor(Color.BLACK)
                        node.endRecording()
                        sampler=Sampler(node,material,RectF(binding.local),binding.screenW,binding.screenH,style)
                        entries[binding.drawable]=sampler
                    }
                    if(sampler.local!=binding.local){sampler.local.set(binding.local);sampler.worldValid=false}
                    sampler.style=style;sampler.plateNode=depthNode;sampler.mini=binding.mini
                    ensureContents(sampler)
                    val rect=RectF(binding.local)
                    val world=Matrix()
                    try {XposedHelpers.callMethod(binding.view,"transformMatrixToGlobal",world);world.mapRect(rect)}catch(_:Throwable){rect.set(0f,0f,width.toFloat(),height.toFloat())}
                    // Native overscroll can re-record the icon even when the
                    // live updater skips it. Retain its existing edge sample.
                    if(sampler.source==null || !freezeEdge(binding.view,world))
                        samplerEffect(sampler,wallpaper,WallpaperFootprint.source(wallpaper,binding.screenW,binding.screenH,rect),view=binding.view)
                    plate.drawRenderNode(sampler.node)
                }else{
                    val nowNs=System.nanoTime()
                    val fps=MiniIconRefresh.fps(PrefsHelper.liveFps,false,false)
                    val hardware=canvas.isHardwareAccelerated
                    val changed=material.wallpaper!==wallpaper || material.source!=source || material.appliedStyle!=style || material.appliedHardware!=hardware
                    if(changed && (material.paint.shader==null || material.appliedStyle!=style || material.appliedHardware!=hardware || material.clock.remainingNs(nowNs,fps)==0L)){
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
