package com.cosmod.launcherglass.xposed

import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * OEM edit mode re-records launcher elements into its own select-dot nodes:
 * SwitchStateRenderer draws a fresh constantState.newDrawable() copy of the
 * icon, or the whole view again while R.id.key_edit_mode_view_canvas_tag is
 * set, into a blurred node positioned at the dot. Such a recording belongs to
 * the OEM, not to the element: it must show the element's current wallpaper
 * sample and never create, move or re-time one. A sampler per copy was
 * orphaned on the next draw (restored -> invalidated -> next copy: a frame
 * loop on every icon), and re-binding the same drawable with the dot's
 * offset alternated a clock's crop between two positions.
 */
internal object ForeignDraw {
    private val scopes=listOf(
        "com.android.launcher3.iconrender.impl.SelectStateIconRenderer" to listOf("drawSelectIconIfNecessary","drawRemoveIconInBigShortcutIfNecessary"),
        "com.android.launcher3.iconrender.impl.SelectStateIconLargeDeviceRenderer" to listOf("drawSelectIconIfNecessary","drawRemoveIconInBigShortcutIfNecessary"),
        "com.android.launcher3.card.LauncherCardView" to listOf("drawDeleteIconIfNecessary"),
        "com.android.launcher3.widget.CustomLauncherAppWidgetHostView" to listOf("drawSelectIcon"))
    // Main thread only: hosts whose select/delete decoration is being drawn.
    private val hosts=ArrayList<View>()
    @Volatile private var canvasTag=0
    private var logs=0

    fun install(loader:ClassLoader){
        canvasTag=try {
            XposedHelpers.getStaticIntField(XposedHelpers.findClass("com.android.launcher3.R\$id",loader),"key_edit_mode_view_canvas_tag")
        }catch(_:Throwable){0}
        var hooked=0
        val scope=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(Looper.myLooper()!==Looper.getMainLooper())return
                val host=param.thisObject as? View ?: NativeIconAccess.field(param.thisObject,"mHostView") as? View ?: return
                hosts.add(host)
                param.setObjectExtra("foreignHost",host)
            }
            override fun afterHookedMethod(param:MethodHookParam){
                val host=param.getObjectExtra("foreignHost") as? View ?: return
                val index=hosts.lastIndexOf(host)
                if(index>=0)hosts.removeAt(index)
            }
        }
        for((name,methods) in scopes){
            val type=XposedHelpers.findClassIfExists(name,loader) ?: continue
            for(method in type.declaredMethods){
                if(method.name !in methods)continue
                ModuleHooks.hookMethod(method,scope);hooked++
            }
        }
        GlassLogger.i("ForeignDraw","Edit-mode decoration scopes=$hooked, canvasTag=${canvasTag!=0}")
    }

    /** True while the OEM records [owner] into a node that is not its own. */
    fun isForeign(owner:View):Boolean {
        if(Looper.myLooper()!==Looper.getMainLooper())return false
        val tag=canvasTag
        val foreign=(tag!=0 && owner.getTag(tag)!=null) || hosts.any {it===owner}
        if(foreign && logs<6){
            logs++
            GlassLogger.i("ForeignDraw","Reusing the element's own sample for an OEM decoration draw: ${owner.javaClass.simpleName}")
        }
        return foreign
    }

    /**
     * The element's own drawable for a constantState copy of it: copies share
     * the native bitmap, so layers, material and sample stay the element's.
     */
    fun original(owner:View,drawable:Drawable):Drawable {
        val icon=(NativeIconAccess.call(owner,"getCompoundDrawable") ?: NativeIconAccess.call(owner,"getIcon")) as? Drawable ?: return drawable
        if(icon===drawable || icon.javaClass!==drawable.javaClass)return drawable
        for(name in listOf("mBitmap","mMonoIcon")){
            val own=NativeIconAccess.field(icon,name) ?: continue
            if(own===NativeIconAccess.field(drawable,name))return icon
        }
        return drawable
    }

    fun reset(){hosts.clear()}
}
