package com.cosmod.launcherglass.xposed

import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XposedHelpers

/** The native 17.x contour API, or the same OplusPath calls used by 16.x. */
internal object NativeFolderPath {
    private val logged=HashSet<String>()
    fun create(view:View,path:Path,bounds:RectF,radius:Float,sx:Int,sy:Int):Boolean = try {
        path.reset()
        val loader=view.context.classLoader
        val manager=XposedHelpers.findClassIfExists("com.android.launcher3.util.SmoothRoundedManager",loader)
        val modern=manager?.declaredMethods?.firstOrNull {it.name=="createIconFolderClipPath" &&
            it.parameterTypes.contentEquals(arrayOf(Path::class.java,RectF::class.java,Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType))}
        if(modern!=null){
            modern.invoke(null,path,bounds,radius,radius,sx,sy)
            if(logged.add("modern"))GlassLogger.i("Compat","Folder contour: SmoothRoundedManager")
        }else{
            val icons=XposedHelpers.findClass("com.android.common.util.IconUtils",loader)
            if(XposedHelpers.callStaticMethod(icons,"isIconRadiusRound")==true &&
                XposedHelpers.callStaticMethod(icons,"isMiddle",sx,sy)==true){
                path.addRoundRect(bounds,radius,radius,Path.Direction.CCW)
            }else{
                val type=XposedHelpers.findClass("com.oplus.graphics.OplusPath",loader)
                val wrapper=XposedHelpers.newInstance(type,path)
                XposedHelpers.callMethod(wrapper,"addSmoothRoundRect",bounds,radius,radius,1.1f,Path.Direction.CCW)
            }
            if(logged.add("legacy"))GlassLogger.i("Compat","Folder contour: legacy OplusPath")
        }
        !path.isEmpty
    }catch(t:Throwable){
        path.reset()
        if(logged.add(t.javaClass.name+":"+t.message))GlassLogger.e("Compat","Native folder contour unavailable; stock material kept",t)
        false
    }
}
