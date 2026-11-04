package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object UiLanguage {
    private const val KEY="ui_language"
    fun tag(context: Context): String = context.getSharedPreferences(SettingsProvider.PREFS_NAME,Context.MODE_PRIVATE)
        .getString(KEY,null)?.takeIf { it=="ru" || it=="en" }
        ?: if(Resources.getSystem().configuration.locales[0].language=="ru")"ru" else "en"
    fun wrap(context: Context): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(tag(context)))
    })
    fun choose(context:Context,tag:String) {
        context.getSharedPreferences(SettingsProvider.PREFS_NAME,Context.MODE_PRIVATE).edit().putString(KEY,tag).apply()
    }
}
