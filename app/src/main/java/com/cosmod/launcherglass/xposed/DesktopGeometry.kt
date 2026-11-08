package com.cosmod.launcherglass.xposed

import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import com.cosmod.launcherglass.util.DesktopSizing
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Native dimensions are changed before layout; no view scaling or layout-database writes. */
internal object DesktopGeometry {
    @Volatile private var values=DesktopSizing.defaults
    @Volatile private var dimensions:Map<Int,String> = emptyMap()
    private val profileDepth=ThreadLocal<Int>()
    private val profileOwner=ThreadLocal<Any>()
    private val indicatorPadding=setOf("workspace_page_indicator_bottom_padding_offset",
        "workspace_page_indicator_vertical_padding","workspace_page_indicator_vertical_padding_breeno")
    private val indicatorHeight=setOf("workspace_page_indicator_height","workspace_page_indicator_breeno_entry_height")
    private val widgetPadding=setOf("CellLayout_widget_padding_left_right","oplus_default_app_widget_padding_left",
        "oplus_default_app_widget_padding_right","oplus_default_app_widget_padding_top","oplus_default_app_widget_padding_bottom")
    // Resource IDs belong to the installed launcher and cannot be linked into this module's R class.
    @android.annotation.SuppressLint("DiscouragedApi")
    fun load(bundle:Bundle) {
        values=DesktopSizing.defaults.mapValues {(key,fallback) -> bundle.getInt(key,fallback).coerceIn(DesktopSizing.ranges.getValue(key))}
        if(dimensions.isEmpty())WallpaperHelper.appContext?.let {context ->
            dimensions=(indicatorPadding+indicatorHeight+widgetPadding).mapNotNull {name ->
                context.resources.getIdentifier(name,"dimen",context.packageName).takeIf {it!=0}?.let {it to name}
            }.toMap()
        }
    }
    fun install(loader:ClassLoader) {
        val profile=XposedHelpers.findClass("com.android.launcher3.DeviceProfile",loader)
        XposedBridge.hookAllConstructors(profile,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                profileDepth.set((profileDepth.get() ?: 0)+1);profileOwner.set(param.thisObject)
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                val depth=(profileDepth.get() ?: 1)-1
                if(depth>0){profileDepth.set(depth);return}
                profileDepth.remove();profileOwner.remove()
                if(PrefsHelper.enabled && !param.hasThrowable())try {lowerWorkspace(param.thisObject)}
                catch(t:Throwable){GlassLogger.limited("DesktopGeometry","workspace",100){"Workspace alignment unavailable: ${t.message}"}}
            }
        })
        XposedHelpers.findAndHookMethod(profile,"updateInsets",Rect::class.java,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam) {
                if(param.args[0]==null || (profileDepth.get() ?: 0)>0 || !PrefsHelper.enabled || param.hasThrowable())return
                try {lowerWorkspace(param.thisObject)}
                catch(t:Throwable){GlassLogger.limited("DesktopGeometry","insets",100){"Workspace alignment unavailable: ${t.message}"}}
            }
        })
        val icons=XposedHelpers.findClass("com.android.launcher.theme.LauncherIconConfig",loader)
        XposedHelpers.findAndHookMethod(icons,"calculateIconSizeByUxDesign",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam) {
                if(!PrefsHelper.enabled || (profileDepth.get() ?: 0)==0 || param.hasThrowable())return
                val percent=values.getValue(DesktopSizing.ICON_SCALE)
                if(percent==100)return
                val context=WallpaperHelper.appContext ?: return
                val base=param.result as? Int ?: return
                try {
                    val config=XposedHelpers.callMethod(profileOwner.get(),"config")
                    val width=XposedHelpers.getIntField(config,"s")
                    if(width>=XposedHelpers.getIntField(config,"t"))return
                    val columns=Settings.System.getInt(context.contentResolver,"layout_cellcount_x",4).coerceAtLeast(1)
                    val result=DesktopSizing.iconPixels(base,percent,width/columns,(8*context.resources.displayMetrics.density).toInt())
                    param.result=result
                    GlassLogger.limited("DesktopGeometry","icons",100){"Native icon size $base -> $result px ($percent%)"}
                }catch(t:Throwable){GlassLogger.limited("DesktopGeometry","icons-failed",100){"Icon sizing unavailable: ${t.message}"}}
            }
        })
        for(method in listOf("getDimensionPixelSize","getDimensionPixelOffset"))
            XposedHelpers.findAndHookMethod(Resources::class.java,method,Int::class.javaPrimitiveType,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam) {
                    if(!PrefsHelper.enabled || param.hasThrowable())return
                    val id=param.args[0] as Int
                    val name=dimensions[id] ?: return
                    val resources=param.thisObject as Resources
                    if(resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)return
                    if(resources.getResourcePackageName(id)!=WallpaperHelper.appContext?.packageName)return
                    val base=param.result as Int
                    val key=if(name in widgetPadding)DesktopSizing.WIDGET_GAPS else DesktopSizing.INDICATOR_GAPS
                    val percent=values.getValue(key)
                    if(percent==100)return
                    val scaled=DesktopSizing.padding(base,percent)
                    param.result=if(name in indicatorHeight)maxOf(scaled,minOf(base,(48*resources.displayMetrics.density).toInt()))else scaled
                }
            })
    }
    private fun lowerWorkspace(profile:Any) {
        val bias=values.getValue(DesktopSizing.BOTTOM_BIAS)
        if(bias==0)return
        val config=XposedHelpers.callMethod(profile,"config")
        if(XposedHelpers.getIntField(config,"s")>=XposedHelpers.getIntField(config,"t"))return
        val workspace=XposedHelpers.callMethod(profile,"workspace")
        val hotseat=XposedHelpers.callMethod(profile,"hotseat")
        val indicator=XposedHelpers.callMethod(profile,"indicator")
        val insets=XposedHelpers.getObjectField(config,"k") as Rect
        val padding=XposedHelpers.getObjectField(XposedHelpers.getObjectField(workspace,"b"),"a") as Rect
        val total=XposedHelpers.getIntField(config,"t")-XposedHelpers.getIntField(workspace,"c")-
            XposedHelpers.getIntField(hotseat,"n")-XposedHelpers.getIntField(indicator,"d")-
            (XposedHelpers.callMethod(hotseat,"i") as Int)-insets.top
        val shift=DesktopSizing.downwardShift(padding.top-insets.top,total,bias)
            .coerceAtMost((padding.bottom-insets.bottom-XposedHelpers.getIntField(hotseat,"n")-XposedHelpers.getIntField(indicator,"d")).coerceAtLeast(0))
        padding.top+=shift;padding.bottom-=shift
        XposedHelpers.setIntField(workspace,"d",padding.bottom)
        GlassLogger.limited("DesktopGeometry","alignment",100){"Workspace moved down $shift px; navigation/dock bounds retained"}
    }
}
