package com.cosmod.launcherglass.util

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GlassLogger {

    const val TAG = "ColorOSGlass"
    private val dateFormat = object:ThreadLocal<SimpleDateFormat>() {
        override fun initialValue()=SimpleDateFormat("HH:mm:ss.SSS",Locale.US)
    }

    fun i(component: String, message: String) {
        val time = dateFormat.get()!!.format(Date())
        val formatted = "[$time] [$component] $message"

        // Use info-level messages so diagnostics remain visible with debug logging filtered.
        Log.i(TAG, formatted)

        // 2. LSPosed Log
        try {
            XposedBridge.log("$TAG: $formatted")
        } catch (_: Throwable) {}
    }

    fun d(component: String, message: String) {
        i(component, message)
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        val errorMsg = if (throwable != null) "$message: ${throwable.message}\n${Log.getStackTraceString(throwable)}" else message
        val time = dateFormat.get()!!.format(Date())
        val formatted = "[$time] [$component] ERROR: $errorMsg"
        Log.e(TAG, formatted)
        try {
            XposedBridge.log("$TAG: $formatted")
        } catch (_: Throwable) {}
    }

    fun readLogs(context: android.content.Context): String {
        return SuUtil.readLogcat(context)
    }

    fun clearLogs(): Boolean {
        return SuUtil.clearLogcat()
    }
}
