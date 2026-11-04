package com.cosmod.launcherglass.xposed

import android.content.Context
import de.robv.android.xposed.XposedHelpers

object FluxIconSupport {
    const val UNKNOWN=0
    const val FLUX=1
    const val OTHER=2

    fun read(context:Context):Int = try {
        val type=XposedHelpers.findClassIfExists("com.android.launcher.theme.LauncherIconConfig",context.classLoader)
        val instance=type?.let {XposedHelpers.getStaticObjectField(it,"sInstance")}
        val config=instance?.let {XposedHelpers.getObjectField(it,"mIconConfig")}
        // This is the native isThemedIconEnabled predicate (theme == 3), read
        // directly so CherryPick's own themed-icon hook cannot falsify it.
        val theme=config?.let {XposedHelpers.callMethod(it,"getTheme") as? Int}
        when(theme){null->UNKNOWN;3->FLUX;else->OTHER}
    } catch(_:Throwable){UNKNOWN}
}
