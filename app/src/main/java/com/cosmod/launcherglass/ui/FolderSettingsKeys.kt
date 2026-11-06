package com.cosmod.launcherglass.ui

/**
 * A group of glass keys that a surface (folders, dock) may override.  The
 * scoped key is "<prefix><base>"; while [own] is off the surface inherits the
 * icon values of the base keys.
 */
open class ScopedSettingsKeys(val prefix:String,val own:String) {
    fun key(base:String)="$prefix$base"
    val integers get()=INTEGERS
    val booleans get()=BOOLEANS
    val keys get()=INTEGERS.map(::key)+BOOLEANS.map(::key)
    /** The icon key this scoped key falls back to, or null when it is not one of ours. */
    fun base(key:String):String?=if(key.startsWith(prefix))key.removePrefix(prefix).takeIf {it in INTEGERS || it in BOOLEANS} else null
    companion object {
        val INTEGERS=listOf(SettingsProvider.KEY_BLUR_STYLE,SettingsProvider.KEY_BLUR_RADIUS,SettingsProvider.KEY_BLUR_QUALITY,
            SettingsProvider.KEY_BG_OPACITY,SettingsProvider.KEY_BLUR_SATURATION,SettingsProvider.KEY_BLUR_VIBRANCY,
            SettingsProvider.KEY_BLUR_LIGHTENING,SettingsProvider.KEY_LIQUID_WIDTH,SettingsProvider.KEY_LIQUID_DISTORTION)
        val BOOLEANS=listOf(SettingsProvider.KEY_BLUR_RADIUS_ENABLED,SettingsProvider.KEY_BLUR_TONE_ENABLED)
        val all:List<ScopedSettingsKeys> get()=listOf(FolderSettingsKeys,DockSettingsKeys)
        fun scopeOf(key:String):ScopedSettingsKeys?=all.firstOrNull {it.base(key)!=null}
        /** The base icon key of a scoped key, or null for a non-scoped key. */
        fun baseOf(key:String):String?=all.firstNotNullOfOrNull {it.base(key)}
        fun isOwnKey(key:String)=all.any {it.own==key}
    }
}

object FolderSettingsKeys:ScopedSettingsKeys("folder_","folder_own_settings") {
    const val OWN="folder_own_settings"
}

object DockSettingsKeys:ScopedSettingsKeys("dock_","dock_own_settings") {
    const val OWN="dock_own_settings"
}
