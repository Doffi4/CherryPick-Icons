package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ImageReader
import android.os.Looper
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * App launch/return transitions of a multi-icon (ShortcutContainer) float a
 * Picture snapshot of its GridRecyclerView
 * (IconDrawableLoader -> BitmapUtils.convertViewToBitmapInMainExecutor).
 *
 * That snapshot is a composed view, not an icon. Re-extracting an "icon"
 * from it (IconDrawableView.setDrawable) produced a dark plate with the items
 * stretched, so it stays native. Recorded on the software Picture canvas, the
 * plate would also fall back to the CPU material and differ from the screen;
 * the view is instead rendered once by the GPU, exactly as displayed, and that
 * image is what the native Picture contains.
 *
 * The launcher records this snapshot several times per launch cycle on the
 * main thread. A one-shot HardwareRenderer.createHardwareBitmap builds and
 * tears down a render context and image queue for every call (the teardown
 * also drops GPU layers of the live items it drew), followed by a separate
 * readback. One small offscreen renderer is kept instead; any failure falls
 * back to the one-shot path for good.
 */
internal object TransitionCapture {
    private val captures=Collections.synchronizedMap(WeakHashMap<Bitmap,Boolean>())
    private val target=ThreadLocal<View?>()
    private val rendering=ThreadLocal<Boolean>()
    private val failures=HashSet<String>()
    private var logs=0

    fun install(loader:ClassLoader){
        val utils=XposedHelpers.findClassIfExists("com.oplus.basecommon.util.BitmapUtils",loader) ?: return
        ModuleHooks.hookAllMethods(utils,"convertViewToBitmapInMainExecutor",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                (param.result as? Bitmap)?.let {captures[it]=true}
            }
        })
        // The edge-filled transition drawable copies the snapshot first.
        ModuleHooks.hookAllMethods(utils,"convertBitmapToMutable",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                val source=param.args.firstOrNull() as? Bitmap ?: return
                val result=param.result as? Bitmap ?: return
                if(captures[source]==true)captures[result]=true
            }
        })
        // Runs on the thread that records the Picture (main executor).
        val recorder="convertViewToBitmapInMainExecutor\$lambda\$5"
        if(utils.declaredMethods.none {it.name==recorder}){
            GlassLogger.i("Transition","Snapshot recorder unavailable; multi-icon transitions keep the software snapshot")
            return
        }
        ModuleHooks.hookAllMethods(utils,recorder,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.args.lastOrNull() as? View ?: return
                param.setObjectExtra("priorTarget",target.get())
                param.setObjectExtra("captureTarget",true)
                target.set(view)
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("captureTarget")!=true)return
                target.set(param.getObjectExtra("priorTarget") as? View)
            }
        })
    }

    /** A transition snapshot of a composed view; never an icon to re-extract. */
    fun isViewCapture(drawable:Drawable):Boolean {
        val bitmap=(drawable as? BitmapDrawable)?.bitmap ?: return false
        return captures[bitmap]==true
    }

    /**
     * Draws [view] into the native snapshot [canvas] from a GPU rendering of
     * the same view. Child display lists and live samples are reused, so the
     * snapshot equals the resting view. False keeps the native software draw.
     */
    fun drawHardware(view:View,canvas:Canvas):Boolean {
        if(target.get()!==view || rendering.get()==true || canvas.isHardwareAccelerated)return false
        if(!PrefsHelper.enabled || !view.isAttachedToWindow || !view.isHardwareAccelerated)return false
        val width=view.width
        val height=view.height
        if(width<=0 || height<=0)return false
        rendering.set(true)
        android.os.Trace.beginSection("CherryPick.transitionSnapshot")
        try {
            val reused=if(Looper.myLooper()===Looper.getMainLooper())Offscreen.render(view,width,height)else null
            val pixels=reused ?: oneShot(view,width,height) ?: return false
            flattenEdges(pixels)
            canvas.drawBitmap(pixels,0f,0f,null)
            if(logs++<6)GlassLogger.i("Transition","GPU snapshot for ${view.javaClass.simpleName}: ${width}x$height${if(reused!=null)", reused renderer" else ""}")
            return true
        }catch(t:Throwable){
            if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("Transition","GPU snapshot unavailable; native snapshot kept",t)
            return false
        }finally{
            android.os.Trace.endSection()
            rendering.remove()
        }
    }

    /** Previous path: a throwaway render context per snapshot. Mutable result. */
    private fun oneShot(view:View,width:Int,height:Int):Bitmap? {
        val node=RenderNode("CherryPick.transitionSnapshot")
        try {
            node.setPosition(0,0,width,height)
            val recording=node.beginRecording(width,height)
            try {view.draw(recording)}finally{node.endRecording()}
            val hardware=try {XposedHelpers.callStaticMethod(HardwareRenderer::class.java,"createHardwareBitmap",node,width,height)}
                catch(_:Throwable){XposedHelpers.callStaticMethod(Class.forName("android.view.ThreadedRenderer"),"createHardwareBitmap",node,width,height)} as? Bitmap
                ?: return null
            // A software copy keeps the Picture playable on any canvas.
            return try {hardware.copy(Bitmap.Config.ARGB_8888,true)}finally{hardware.recycle()}
        }finally{node.discardDisplayList()}
    }

    /**
     * One reusable offscreen render context (main thread only). The view is
     * recorded into its content node exactly like the one-shot path and read
     * back from a CPU-readable image queue.
     */
    private object Offscreen {
        private var renderer:HardwareRenderer?=null
        private var reader:ImageReader?=null
        private val root=RenderNode("CherryPick.transitionSnapshot")
        private var width=0
        private var height=0
        private var misses=0
        private var broken=false

        fun render(view:View,w:Int,h:Int):Bitmap? {
            if(broken)return null
            try {
                val renderer=renderer ?: HardwareRenderer().also {
                    it.isOpaque=false
                    // As createHardwareBitmap: no light source, so no shadows.
                    it.setLightSourceGeometry(0f,0f,0f,0f)
                    it.setLightSourceAlpha(0f,0f)
                    it.setContentRoot(root)
                    renderer=it
                }
                var images=reader
                if(images==null || width!=w || height!=h){
                    val previous=images
                    images=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
                    reader=images;width=w;height=h
                    renderer.setSurface(images.surface)
                    previous?.close()
                }
                // Stale frames from an earlier miss must not be mistaken for this one.
                images.acquireLatestImage()?.close()
                root.setPosition(0,0,w,h)
                val recording=root.beginRecording(w,h)
                try {view.draw(recording)}finally{root.endRecording()}
                val sync=try {renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()}
                    finally{root.discardDisplayList()}
                val lost=HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED
                val image=if(sync and lost==0)images.acquireLatestImage()else null
                if(image==null){
                    if(++misses>=3)retire("no frame (sync=$sync)")
                    return null
                }
                try {
                    val plane=image.planes[0]
                    val buffer=plane.buffer
                    val row=w*4
                    val stride=plane.rowStride
                    if(plane.pixelStride!=4 || stride<row)return null.also {retire("pixel layout ${plane.pixelStride}/$stride")}
                    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    buffer.rewind()
                    if(stride==row && buffer.remaining()>=row*h)bitmap.copyPixelsFromBuffer(buffer)
                    else {
                        val packed=ByteBuffer.allocate(row*h)
                        for(y in 0 until h){
                            val start=y*stride
                            buffer.limit(minOf(buffer.capacity(),start+row))
                            buffer.position(start)
                            packed.put(buffer)
                        }
                        packed.rewind()
                        bitmap.copyPixelsFromBuffer(packed)
                    }
                    misses=0
                    return bitmap
                }finally{image.close()}
            }catch(t:Throwable){
                GlassLogger.e("Transition","Reusable snapshot renderer unavailable; one-shot snapshots kept",t)
                retire(null)
                return null
            }
        }

        private fun retire(reason:String?){
            broken=true
            if(reason!=null)GlassLogger.i("Transition","Reusable snapshot renderer retired: $reason")
            try {renderer?.destroy()}catch(_:Throwable){}
            try {reader?.close()}catch(_:Throwable){}
            renderer=null;reader=null
            root.discardDisplayList()
        }
    }

    /**
     * While the launch window is taller or wider than the cluster, the
     * outermost snapshot pixels are extended across the gap. The plate edge
     * carries the lens rim and the refracted backdrop, so every extended row
     * became a streak. Each side is blended into its own median plate colour
     * over a short band; the extension then continues as a flat plate.
     * Alpha is kept, transparent corners stay transparent.
     */
    private fun flattenEdges(bitmap:Bitmap){
        val width=bitmap.width
        val height=bitmap.height
        if(width<24 || height<24 || !bitmap.isMutable)return
        try {
            val pixels=IntArray(width*height)
            bitmap.getPixels(pixels,0,width,0,0,width,height)
            val band=(0.035f*minOf(width,height)).roundToInt().coerceIn(3,14)
            var changed=false
            for(side in 0 until 4)changed=flattenSide(pixels,width,height,side,band) or changed
            if(changed)bitmap.setPixels(pixels,0,width,0,0,width,height)
        }catch(t:Throwable){
            if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("Transition","Snapshot edge flattening skipped",t)
        }
    }

    /** side: 0 top, 1 bottom, 2 left, 3 right; a runs along the side, d inwards. */
    private fun flattenSide(pixels:IntArray,width:Int,height:Int,side:Int,band:Int):Boolean {
        val along=if(side<2)width else height
        val depth=if(side<2)height else width
        fun index(a:Int,d:Int)=when(side){
            0 -> d*width+a
            1 -> (height-1-d)*width+a
            2 -> a*width+d
            else -> a*width+(width-1-d)
        }
        val edges=IntArray(3)
        var found=0
        for(line in intArrayOf(along/3,along/2,along*2/3)){
            for(d in 0 until depth/4){
                if(pixels[index(line,d)].ushr(24)>=128){edges[found++]=d;break}
            }
        }
        if(found<2)return false
        edges.sort(0,found)
        val edge=edges[(found-1)/2]
        if(edge+band>=depth/2)return false
        val red=IntArray(256)
        val green=IntArray(256)
        val blue=IntArray(256)
        var count=0
        for(d in edge+1..edge+band)for(a in along/4 until along*3/4){
            val c=pixels[index(a,d)]
            if(c.ushr(24)<200)continue
            red[(c shr 16) and 255]++;green[(c shr 8) and 255]++;blue[c and 255]++
            count++
        }
        if(count<8)return false
        val r=median(red,count)
        val g=median(green,count)
        val b=median(blue,count)
        for(d in maxOf(0,edge-2)..edge+band){
            val weight=if(d<=edge)1f else {
                val t=(d-edge).toFloat()/band
                1f-t*t*(3f-2f*t)
            }
            if(weight<=0f)continue
            for(a in 0 until along){
                val i=index(a,d)
                val c=pixels[i]
                val alpha=c.ushr(24)
                if(alpha==0)continue
                pixels[i]=Color.argb(alpha,mix((c shr 16) and 255,r,weight),mix((c shr 8) and 255,g,weight),mix(c and 255,b,weight))
            }
        }
        return true
    }

    private fun median(histogram:IntArray,count:Int):Int {
        var seen=0
        for(value in 0 until 256){
            seen+=histogram[value]
            if(seen*2>=count)return value
        }
        return 255
    }

    private fun mix(from:Int,to:Int,weight:Float)=(from+(to-from)*weight).roundToInt().coerceIn(0,255)
}
