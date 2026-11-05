package com.cosmod.launcherglass.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.os.SystemClock
import android.app.WallpaperManager
import com.cosmod.launcherglass.xposed.PrefsHelper
import com.cosmod.launcherglass.ui.SettingsProvider
import java.lang.reflect.Method

/** One-shot use of the launcher's native behind-window bitmap factory. */
object LauncherWallpaperCapture {
    private data class CaptureApi(val type: Class<*>,val callback: Class<*>,val frameMethod: String,
        val start: Method,val stop: Method)
    private var captureApi: CaptureApi? = null
    private var apiResolved = false

    private fun isValidCaptureClass(type: Class<*>?): Boolean {
        if (type == null) return false
        val hasConstructor = type.declaredConstructors.any { c ->
            c.parameterTypes.size == 2 &&
                Context::class.java.isAssignableFrom(c.parameterTypes[0]) &&
                c.parameterTypes[1].name.contains("SurfaceControl")
        }
        if (!hasConstructor) return false

        val hasStartMethod = type.declaredMethods.any { method ->
            method.parameterTypes.size == 1 && method.parameterTypes[0].isInterface &&
                method.parameterTypes[0].methods.any { callback -> callback.parameterTypes.any { it == Bitmap::class.java } }
        }
        if (!hasStartMethod) return false

        val hasStopMethod = type.declaredMethods.any { method ->
            method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                method.returnType == java.lang.Void.TYPE
        }
        return hasStopMethod
    }

    private fun findCaptureClass(context: Context): Class<*>? {
        val loader = context.classLoader

        // 1. Resolve from MaterialBlurAnimHelper factory field
        try {
            val helperClass = XposedHelpers.findClassIfExists(
                "com.android.launcher3.uioverrides.states.blurdrawable.MaterialBlurAnimHelper", loader
            )
            if (helperClass != null) {
                for (field in helperClass.declaredFields) {
                    if (field.name == "mStaticWallpaperBlurFactory" || field.name.endsWith("Factory", ignoreCase = true)) {
                        val cls = field.type
                        if (isValidCaptureClass(cls)) return cls
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Resolve from ClockWallpaperObserver factory field
        try {
            val clockClass = XposedHelpers.findClassIfExists(
                "com.android.launcher3.inspirationclock.ClockWallpaperObserver", loader
            )
            if (clockClass != null) {
                for (field in clockClass.declaredFields) {
                    if (field.name.contains("Factory", ignoreCase = true)) {
                        val cls = field.type
                        if (isValidCaptureClass(cls)) return cls
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Known obfuscated candidates across ColorOS launcher builds (e.g. un.c on 17.3.12, tn.c on 17.3.9)
        for (name in listOf("un.c", "tn.c", "vn.c", "wn.c", "sn.c", "rn.c")) {
            try {
                val cls = XposedHelpers.findClassIfExists(name, loader) ?: continue
                if (isValidCaptureClass(cls)) return cls
            } catch (_: Throwable) {}
        }
        return null
    }

    @Synchronized private fun api(context: Context): CaptureApi? {
        if(apiResolved)return captureApi
        apiResolved=true
        return try {
            val type = findCaptureClass(context) ?: error("Wallpaper factory class unavailable")
            val start=type.declaredMethods.firstOrNull { method ->
                method.parameterTypes.size==1 && method.parameterTypes[0].isInterface &&
                    method.parameterTypes[0].methods.any { callback -> callback.parameterTypes.any { it==Bitmap::class.java } }
            } ?: error("Wallpaper bitmap callback unavailable")
            val callback=start.parameterTypes[0]
            val frame=callback.methods.first { method -> method.parameterTypes.any { it==Bitmap::class.java } }
            val stop=type.declaredMethods.first { method ->
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                    method.returnType==Void.TYPE
            }
            start.isAccessible=true;stop.isAccessible=true
            CaptureApi(type,callback,frame.name,start,stop).also {
                captureApi=it
                GlassLogger.i("WallpaperCapture","Resolved native capture callback: ${callback.name} in ${type.name}")
            }
        } catch(t:Throwable) {
            GlassLogger.i("WallpaperCapture","Native capture API unavailable on this launcher: ${t.message}")
            null
        }
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var window: WeakReference<View>? = null
    private var focusListener:android.view.ViewTreeObserver.OnWindowFocusChangeListener?=null
    @Volatile private var motionFactory: Any? = null
    private var motionState = WallpaperMotionState()
    @Volatile private var motionEpoch = 0L
    private val frameBusy = AtomicBoolean(false)
    @Volatile private var lastFrameMs = 0L
    private var watchStartedMs = 0L
    private var surfaceRetries = 0
    private var awaitingUnlock=false
    private var staticMotionUntilMs=0L

    fun onScreenOff() {
        GlassLogger.i("WallpaperCapture","Screen off -> pause capture")
        awaitingUnlock=true
        staticMotionUntilMs=0L
        stopMotionWatch()
    }
    fun prepareUnlock() {
        GlassLogger.i("WallpaperCapture","Wake/unlock -> prepare live wallpaper sampling")
        staticMotionUntilMs=SystemClock.uptimeMillis()+1800L
        surfaceRetries=0
        startMotionWatch()
    }
    fun onUserPresent() {
        GlassLogger.i("WallpaperCapture","User present -> resume live wallpaper sampling")
        awaitingUnlock=false
        prepareUnlock()
        val root=window?.get()
        if(root?.hasWindowFocus()==true || LiveBlurEngine.isResumed) {
            LiveBlurEngine.onHomeReturnStarted()
            startMotionWatch()
        }
    }
    private val settleCheck = object : Runnable {
        override fun run() {
            if (motionFactory == null) return
            val now = SystemClock.uptimeMillis()
            if ((PrefsHelper.idlePause && motionState.isSettled(now)) ||
                (now - watchStartedMs > 2500L && lastFrameMs < watchStartedMs)) {
                stopMotionWatch()
            } else main.postDelayed(this, 100L)
        }
    }

    fun setWindow(view: View) {
        if(window?.get()!==view || focusListener==null) {
            window?.get()?.viewTreeObserver?.takeIf {it.isAlive}?.let {old -> focusListener?.let {old.removeOnWindowFocusChangeListener(it)} }
            val weak=WeakReference(view)
            focusListener=android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if(focused && weak.get()!=null)LiveBlurEngine.onActivityResumed()
            }.also {view.viewTreeObserver.addOnWindowFocusChangeListener(it)}
        }
        window = WeakReference(view)
        surfaceRetries = 0
        if(awaitingUnlock)staticMotionUntilMs=SystemClock.uptimeMillis()+1800L
        startMotionWatch()
    }
    fun onModuleDisabled(){
        stopMotionWatch()
        window?.get()?.viewTreeObserver?.takeIf {it.isAlive}?.let {observer -> focusListener?.let {observer.removeOnWindowFocusChangeListener(it)}}
        focusListener=null
    }
    fun onModuleEnabled(){if(focusListener==null)window?.get()?.let {setWindow(it)}}

    private fun isReadySurface(surface: Any, context: Context): Boolean {
        if (XposedHelpers.callMethod(surface, "isValid") != true) return false
        // The OEM factory parses this name without checking substring bounds.
        // A transition can expose a valid placeholder surface with no delimiter.
        val delimiter = try {
            val manager = XposedHelpers.findClass("com.oplus.posteffect.manager.BlurDrawableManager", context.classLoader)
            if (XposedHelpers.getStaticIntField(manager, "o") >= 40) '#' else ')'
        } catch (_:Throwable) { null }
        val name=surface.toString()
        return if(delimiter!=null) name.indexOf(delimiter)>=13 else
            name.indexOf('#')>=13 || name.indexOf(')')>=13
    }

    fun stopMotionWatch() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stopMotionWatch() }
            return
        }
        motionEpoch++
        main.removeCallbacks(settleCheck)
        motionFactory?.let {
            try { captureApi?.stop?.invoke(it,true) } catch (_: Throwable) {}
            GlassLogger.i("WallpaperCapture", "Live wallpaper stopped -> frame capture paused")
        }
        motionFactory = null
        LiveBlurEngine.onWallpaperMotion(false)
    }

    fun startMotionWatch() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { startMotionWatch() }
            return
        }
        val context = WallpaperHelper.appContext ?: return
        if (!PrefsHelper.enabled || !PrefsHelper.blurEnabled ||
            (!LiveBlurEngine.isResumed && !com.cosmod.launcherglass.xposed.NativeSurfaceLive.active) ||
            (LiveBlurEngine.isAppLaunchInProgress && !com.cosmod.launcherglass.xposed.NativeSurfaceLive.active) ||
            (LiveBlurEngine.isWorkspaceBlurSuspended && !PrefsHelper.drawerBlurEnabled) ||
            PrefsHelper.blurMode != SettingsProvider.BLUR_MODE_LIVE) return
        val now = SystemClock.uptimeMillis()
        // Static ImageWallpaper also moves during ColorOS unlock. Capture that
        // short transition, without running a permanent static-wallpaper watch.
        if(now>=staticMotionUntilMs && try { WallpaperManager.getInstance(context).wallpaperInfo == null } catch (_: Throwable) { true }) return
        val api=api(context) ?: return
        if (motionFactory != null) {
            motionState.wake(now)
            return
        }
        val root = window?.get() ?: return
        if (!root.isAttachedToWindow) return
        try {
            val impl = XposedHelpers.callMethod(root, "getViewRootImpl") ?: return
            val surface = XposedHelpers.callMethod(impl, "getSurfaceControl") ?: return
            if (!isReadySurface(surface, context)) {
                if (surfaceRetries++ < 5) main.postDelayed({ startMotionWatch() }, 100L)
                return
            }
            surfaceRetries = 0
            val type = api.type
            val callback = api.callback
            val epoch = ++motionEpoch
            watchStartedMs = now
            lastFrameMs = 0L
            motionState = WallpaperMotionState().apply { wake(now) }
            WallpaperHelper.resetLiveFrameSignature()
            val listener = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "CosmodWallpaperMotion"
                    api.frameMethod -> {
                        val frame = args?.firstOrNull { it is Bitmap } as? Bitmap
                        val time = SystemClock.uptimeMillis()
                        val interval = 1000L / PrefsHelper.liveFps.coerceIn(1, 30)
                        if (epoch == motionEpoch && frame != null && !frame.isRecycled &&
                            time - lastFrameMs >= interval && frameBusy.compareAndSet(false, true)) {
                            if(lastFrameMs==0L)GlassLogger.i("WallpaperCapture","First motion frame received in ${time-watchStartedMs}ms")
                            lastFrameMs = time
                            try {
                                val copy = frame.copy(Bitmap.Config.HARDWARE, false)
                                if (copy == null) frameBusy.set(false)
                                else WallpaperHelper.processLiveFrame(copy,{epoch==motionEpoch}) { signature ->
                                    main.post {
                                        frameBusy.set(false)
                                        if (epoch == motionEpoch && motionFactory != null) {
                                            motionState.observe(signature, SystemClock.uptimeMillis())
                                            if (PrefsHelper.idlePause && motionState.isSettled(SystemClock.uptimeMillis())) {
                                                stopMotionWatch()
                                            }
                                        }
                                    }
                                }
                            } catch (_: Throwable) { frameBusy.set(false) }
                        }
                        null
                    }
                    else -> null
                }
            }
            val instance = XposedHelpers.newInstance(type, context, surface)
            motionFactory = instance
            api.start.invoke(instance,listener)
            LiveBlurEngine.onWallpaperMotion(true)
            GlassLogger.i("WallpaperCapture", "Live wallpaper frame capture active")
            main.postDelayed(settleCheck, 100L)
        } catch (t: Throwable) {
            stopMotionWatch()
            GlassLogger.e("WallpaperCapture", "Failed to watch live wallpaper", t)
        }
    }

    fun capture(context: Context): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper() || !LiveBlurEngine.isResumed ||
            LiveBlurEngine.isAppLaunchInProgress) return null
        val root = window?.get() ?: return null
        val api=api(context) ?: return null
        val finished = AtomicBoolean(false)
        val bitmap = AtomicReference<Bitmap?>()
        val factory = AtomicReference<Any?>()
        val ready = CountDownLatch(1)
        main.post {
            if (finished.get() || !root.isAttachedToWindow) {
                ready.countDown()
                return@post
            }
            try {
                val impl = XposedHelpers.callMethod(root, "getViewRootImpl") ?: error("No launcher root")
                val surface = XposedHelpers.callMethod(impl, "getSurfaceControl") ?: error("No launcher surface")
                if (!isReadySurface(surface, context)) {
                    ready.countDown()
                    return@post
                }
                val type = api.type
                val callback = api.callback
                val listener = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "CosmodWallpaperCapture"
                        api.frameMethod -> {
                            if (!finished.get() && bitmap.get() == null) {
                                val source = args?.firstOrNull { it is Bitmap } as? Bitmap
                                if (source != null && !source.isRecycled) {
                                    val copy = source.copy(Bitmap.Config.HARDWARE, false)
                                    if (copy != null && !bitmap.compareAndSet(null, copy)) copy.recycle()
                                    ready.countDown()
                                }
                            }
                            null
                        }
                        else -> null
                    }
                }
                val instance = XposedHelpers.newInstance(type, context, surface)
                factory.set(instance)
                api.start.invoke(instance,listener)
            } catch (t: Throwable) {
                GlassLogger.e("WallpaperCapture", "Native wallpaper capture failed", t)
                ready.countDown()
            }
        }
        try {
            ready.await(1200L, TimeUnit.MILLISECONDS)
        } finally {
            finished.set(true)
            main.post {
                factory.get()?.let {
                    try { api.stop.invoke(it,true) } catch (_: Throwable) {}
                }
            }
        }
        return bitmap.get()
    }
}
