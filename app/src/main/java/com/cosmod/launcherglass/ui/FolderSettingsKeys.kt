package com.cosmod.launcherglass.ui

object FolderSettingsKeys {
    const val OWN="folder_own_settings"
    fun key(base:String)="folder_$base"
    val integers=listOf(SettingsProvider.KEY_BLUR_STYLE,SettingsProvider.KEY_BLUR_RADIUS,SettingsProvider.KEY_BLUR_QUALITY,
        SettingsProvider.KEY_BG_OPACITY,SettingsProvider.KEY_BLUR_SATURATION,SettingsProvider.KEY_BLUR_VIBRANCY,
        SettingsProvider.KEY_BLUR_LIGHTENING,SettingsProvider.KEY_LIQUID_WIDTH,SettingsProvider.KEY_LIQUID_DISTORTION)
    val booleans=listOf(SettingsProvider.KEY_BLUR_RADIUS_ENABLED,SettingsProvider.KEY_BLUR_TONE_ENABLED)
}
