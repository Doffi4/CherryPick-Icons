package com.cosmod.launcherglass.xposed

import android.os.Bundle
import android.os.Build
import com.cosmod.launcherglass.ui.*

internal object FolderSettings {
    data class Processing(val radius:Int,val quality:Int,val vibrancy:Int,val lightening:Int,val liquid:Boolean)
    @Volatile var own=false
        private set
    @Volatile private var selected:GlassStyle?=null
    @Volatile var processing=Processing(10,0,0,50,false)
        private set
    private var stamp:List<Any>?=null
    fun style():GlassStyle {
        if(own)return selected ?: GlassStyle.icons().copy(folder=true)
        val icons=GlassStyle.icons()
        val hsv=FloatArray(3)
        android.graphics.Color.colorToHSV(icons.plateColor ?: PrefsHelper.iconPalette.background,hsv)
        // Inherit processing while separating the folder plate from its icons.
        hsv[2]=if(hsv[2]<.5f)(hsv[2]+.28f).coerceAtMost(1f)else (hsv[2]-.28f).coerceAtLeast(0f)
        return icons.copy(folder=true,plateColor=android.graphics.Color.HSVToColor(hsv))
    }
    fun update(bundle:Bundle):Boolean {
        val signature=ArrayList<Any>()
        signature.add(bundle.getBoolean(FolderSettingsKeys.OWN,false))
        signature.add(bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false))
        signature.add(PrefsHelper.iconPalette)
        FolderSettingsKeys.integers.forEach {signature.add(bundle.getInt(FolderSettingsKeys.key(it),SettingsDefaults.integer(it)))}
        FolderSettingsKeys.booleans.forEach {signature.add(bundle.getBoolean(FolderSettingsKeys.key(it),SettingsDefaults.boolean(it)))}
        if(stamp==signature)return false
        stamp=signature
        own=bundle.getBoolean(FolderSettingsKeys.OWN,false)
        fun number(key:String,max:Int)=bundle.getInt(FolderSettingsKeys.key(key),SettingsDefaults.integer(key)).coerceIn(0,max)
        fun bool(key:String)=bundle.getBoolean(FolderSettingsKeys.key(key),SettingsDefaults.boolean(key))
        val developer=bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false)
        val tone=bool(SettingsProvider.KEY_BLUR_TONE_ENABLED)
        val radius=if(bool(SettingsProvider.KEY_BLUR_RADIUS_ENABLED))number(SettingsProvider.KEY_BLUR_RADIUS,50)else 0
        val quality=if(developer)number(SettingsProvider.KEY_BLUR_QUALITY,100)else 0
        val liquid=number(SettingsProvider.KEY_BLUR_STYLE,1)==1 && Build.VERSION.SDK_INT>=33
        val vibrancy=if(tone)number(SettingsProvider.KEY_BLUR_VIBRANCY,200)else 0
        processing=Processing(radius,quality,vibrancy,if(tone)number(SettingsProvider.KEY_BLUR_LIGHTENING,100)else 50,liquid)
        selected=GlassStyle(liquid,number(SettingsProvider.KEY_LIQUID_WIDTH,100),
            number(SettingsProvider.KEY_LIQUID_DISTORTION,300),radius,if(tone)number(SettingsProvider.KEY_BG_OPACITY,100)else 0,
            PrefsHelper.backdropFilter(vibrancy,PrefsHelper.iconPalette),true,
            PrefsHelper.plateColor(PrefsHelper.iconPalette.background,if(tone)number(SettingsProvider.KEY_BLUR_SATURATION,200)else 100))
        return true
    }
}
