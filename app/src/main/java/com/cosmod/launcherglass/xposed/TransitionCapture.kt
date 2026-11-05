package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

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
        val node=RenderNode("CherryPick.transitionSnapshot")
        android.os.Trace.beginSection("CherryPick.transitionSnapshot")
        try {
            node.setPosition(0,0,width,height)
            val recording=node.beginRecording(width,height)
            try {view.draw(recording)}finally{node.endRecording()}
            val hardware=try {XposedHelpers.callStaticMethod(HardwareRenderer::class.java,"createHardwareBitmap",node,width,height)}
                catch(_:Throwable){XposedHelpers.callStaticMethod(Class.forName("android.view.ThreadedRenderer"),"createHardwareBitmap",node,width,height)} as? Bitmap
                ?: return false
            // A software copy keeps the Picture playable on any canvas.
            val pixels=try {hardware.copy(Bitmap.Config.ARGB_8888,false)}finally{hardware.recycle()} ?: return false
            canvas.drawBitmap(pixels,0f,0f,null)
            if(logs++<6)GlassLogger.i("Transition","GPU snapshot for ${view.javaClass.simpleName}: ${width}x$height")
            return true
        }catch(t:Throwable){
            if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("Transition","GPU snapshot unavailable; native snapshot kept",t)
            return false
        }finally{
            android.os.Trace.endSection()
            rendering.remove()
            node.discardDisplayList()
        }
    }
}
