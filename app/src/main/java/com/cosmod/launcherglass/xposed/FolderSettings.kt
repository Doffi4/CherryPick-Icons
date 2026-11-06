package com.cosmod.launcherglass.xposed

import android.os.Bundle
import android.os.Build
import com.cosmod.launcherglass.ui.*

/**
 * Glass settings of a surface that may override the icon glass (folders, the
 * dock). While [own] is off the surface is tuned exactly like the icons.
 */
internal open class ScopedGlassSettings(private val keys:ScopedSettingsKeys,private val folder:Boolean) {
    data class Processing(val radius:Int,val quality:Int,val vibrancy:Int,val lightening:Int,val liquid:Boolean)
    @Volatile var own=false
        private set
    @Volatile private var selected:GlassStyle?=null
    @Volatile var processing=Processing(10,0,0,50,false)
        private set
    private var stamp:List<Any>?=null
    @Volatile private var saturation=100
    private fun inherited()=GlassStyle.icons().copy(folder=folder)
    fun style():GlassStyle {
        // Without own settings the surface uses the icon processing and plate tone.
        if(!own)return inherited()
        val style=selected ?: return inherited()
        return style.copy(plateColor=PrefsHelper.plateColor(SurfaceTone.base(),saturation))
    }
    /** Blur radius actually used by the surface (0: the blur switch is off). */
    fun radius()=if(own)processing.radius else PrefsHelper.blurRadius
    fun update(bundle:Bundle):Boolean {
        val signature=ArrayList<Any>()
        signature.add(bundle.getBoolean(keys.own,false))
        signature.add(bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false))
        signature.add(PrefsHelper.iconPalette)
        keys.integers.forEach {signature.add(bundle.getInt(keys.key(it),SettingsDefaults.integer(it)))}
        keys.booleans.forEach {signature.add(bundle.getBoolean(keys.key(it),SettingsDefaults.boolean(it)))}
        if(stamp==signature)return false
        stamp=signature
        own=bundle.getBoolean(keys.own,false)
        fun number(key:String,max:Int)=bundle.getInt(keys.key(key),SettingsDefaults.integer(key)).coerceIn(0,max)
        fun bool(key:String)=bundle.getBoolean(keys.key(key),SettingsDefaults.boolean(key))
        val developer=bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false)
        val tone=bool(SettingsProvider.KEY_BLUR_TONE_ENABLED)
        val radius=if(bool(SettingsProvider.KEY_BLUR_RADIUS_ENABLED))number(SettingsProvider.KEY_BLUR_RADIUS,50)else 0
        val quality=if(developer)number(SettingsProvider.KEY_BLUR_QUALITY,100)else 0
        val liquid=number(SettingsProvider.KEY_BLUR_STYLE,1)==1 && Build.VERSION.SDK_INT>=33
        val vibrancy=if(tone)number(SettingsProvider.KEY_BLUR_VIBRANCY,200)else 0
        processing=Processing(radius,quality,vibrancy,if(tone)number(SettingsProvider.KEY_BLUR_LIGHTENING,100)else 50,liquid)
        saturation=if(tone)number(SettingsProvider.KEY_BLUR_SATURATION,200)else 100
        selected=GlassStyle(liquid,number(SettingsProvider.KEY_LIQUID_WIDTH,100),
            number(SettingsProvider.KEY_LIQUID_DISTORTION,300),radius,if(tone)number(SettingsProvider.KEY_BG_OPACITY,100)else 0,
            PrefsHelper.backdropFilter(vibrancy,PrefsHelper.iconPalette),folder,
            PrefsHelper.plateColor(PrefsHelper.iconPalette.background,saturation))
        return true
    }
}

internal object FolderSettings:ScopedGlassSettings(FolderSettingsKeys,true)

internal object DockSettings:ScopedGlassSettings(DockSettingsKeys,false)
