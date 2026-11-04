package com.cosmod.launcherglass.xposed

import android.content.Context
import android.content.res.Configuration
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/** Read the same resolved icon appearance as Flux, including its independent light/dark setting. */
object NativeIconAppearance {
    private var attempted = false
    private var configClass: Class<*>? = null
    private var darkMethod: Method? = null

    fun read(context: Context, configuration: Configuration): Boolean? {
        if (!attempted) {
            attempted = true
            configClass = XposedHelpers.findClassIfExists("com.android.launcher.theme.LauncherIconConfig", context.classLoader)
            darkMethod = try {
                configClass?.getDeclaredMethod("isDarkIconEnable", Configuration::class.java)?.apply { isAccessible = true }
            } catch (_: Throwable) { null }
        }
        return try {
            // Do not initialize native icon configuration before the launcher itself is ready.
            val type = configClass ?: return null
            val instance = XposedHelpers.getStaticObjectField(type, "sInstance") ?: return null
            XposedHelpers.getObjectField(instance, "mIconConfig") ?: return null
            darkMethod?.invoke(null, configuration) as? Boolean
        } catch (_: Throwable) { null }
    }
}
