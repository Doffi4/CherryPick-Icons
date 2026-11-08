package com.cosmod.launcherglass.util



import android.app.WallpaperManager

import android.content.Context

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.graphics.BitmapShader

import android.graphics.Canvas

import java.util.WeakHashMap

import android.graphics.Point

import android.os.Handler

import android.os.Looper

import android.os.SystemClock

import android.os.ParcelFileDescriptor

import android.provider.Settings

import java.util.concurrent.Executors

import android.graphics.Matrix

import android.graphics.Shader

import com.cosmod.launcherglass.xposed.PrefsHelper
import com.cosmod.launcherglass.xposed.ColorDepthEffect

import java.io.File

import java.lang.ref.WeakReference

import kotlin.math.roundToInt



object WallpaperHelper {



    @Volatile

    var appContext: Context? = null



    /**
     * Wallpaper processed with the own settings of a folder/dock scope. It is
     * built next to every shared wallpaper candidate and published together
     * with it, so both always come from the same frame.
     */
    private class OwnWallpaper(val settings:com.cosmod.launcherglass.xposed.ScopedGlassSettings,val enabled:()->Boolean,val tag:String) {
        @Volatile var published:Bitmap?=null
        val candidates=java.util.WeakHashMap<Bitmap,Bitmap>()
        fun active()=settings.own && enabled() && PrefsHelper.enabled
    }
    private val folderOwn=OwnWallpaper(com.cosmod.launcherglass.xposed.FolderSettings,{PrefsHelper.folderBlurEnabled},"FolderMaterial")
    private val dockOwn=OwnWallpaper(com.cosmod.launcherglass.xposed.DockSettings,{PrefsHelper.dockEnabled},"DockMaterial")
    private fun scoped(own:OwnWallpaper,context:Context?):Bitmap? {
        val shared=getBlurredWallpaper(context)
        return if(own.settings.own)own.published?.takeUnless {it.isRecycled} ?: shared else shared
    }
    fun getFolderWallpaper(context:Context?=null):Bitmap?=scoped(folderOwn,context)
    fun getDockWallpaper(context:Context?=null):Bitmap?=scoped(dockOwn,context)
    private fun publishFolder(candidate:Bitmap){
        for(own in listOf(folderOwn,dockOwn))own.published=synchronized(own.candidates){own.candidates.remove(candidate)}
    }
    private fun withFolder(candidate:Bitmap,raw:Bitmap):Bitmap {
        for(own in listOf(folderOwn,dockOwn))if(own.active())withOwn(own,candidate,raw)
        return candidate
    }
    private fun withOwn(own:OwnWallpaper,candidate:Bitmap,raw:Bitmap) {
        val options=own.settings.processing
        val h=minOf(600+options.quality*12,raw.height)
        val w=(raw.width.toFloat()*h/raw.height).roundToInt().coerceAtLeast(1)
        val radius=LiquidOptics.blurRadius(options.radius,h,options.liquid)
        val gpuImage=if(PrefsHelper.gpuEffects)try {
            val input=if(android.os.Build.VERSION.SDK_INT<33)BackdropVibrancy.applyCpu(raw,options.vibrancy)else raw
            val gpu=try {
                if(own===dockOwn)GpuWallpaperBlur.blurDock(input,w,h,radius,options.lightening,options.vibrancy)
                else GpuWallpaperBlur.blurFolder(input,w,h,radius,options.lightening,options.vibrancy)
            }finally{if(input!==raw)input.recycle()}
            if(ShadowTone.gpuSupported)gpu else ShadowTone.applyCpu(gpu,options.lightening).also {if(it!==gpu)gpu.recycle()}
        }catch(t:Throwable){
            GlassLogger.e(own.tag,"Own GPU processing unavailable; using CPU",t)
            null
        }else null
        val image=gpuImage ?: run {
            val scaled=Bitmap.createScaledBitmap(raw,w,h,true)
            val software=if(scaled.config==Bitmap.Config.HARDWARE)scaled.copy(Bitmap.Config.ARGB_8888,false) ?: return else scaled
            val vivid=BackdropVibrancy.applyCpu(software,options.vibrancy)
            val input=ColorDepthEffect.atCpu(vivid,ColorDepthEffect.BEFORE_BLUR)
            val actualRadius=radius
            val blurred=if(actualRadius>0f)FastBlur.blur(input,actualRadius.roundToInt().coerceAtLeast(1))else input.copy(Bitmap.Config.ARGB_8888,false)
            val toned=ShadowTone.applyCpu(blurred,options.lightening)
            val output=ColorDepthEffect.atCpu(toned,ColorDepthEffect.AFTER_BLUR)
            if(toned!==blurred && output!==toned)toned.recycle()
            if(output!==blurred)blurred.recycle()
            if(input!==vivid)input.recycle()
            if(vivid!==software)vivid.recycle()
            if(software!==raw)software.recycle()
            if(scaled!==raw && scaled!==software)scaled.recycle()
            output
        }
        synchronized(own.candidates){own.candidates[candidate]=image}
    }
    @Volatile private var blurredWallpaper: Bitmap? = null

    @Volatile

    private var cachedBlurRadius: Int = -1

    private val loader = Executors.newSingleThreadExecutor { runnable ->

        Thread(runnable, "CosmodWallpaper").apply { isDaemon = true }

    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isReloading = false

    private var sourceRevision = 0L

    private var cachedRevision = -1L

    private var retryAfterMs = 0L

    private var launcherWallpaper: WeakReference<Bitmap>? = null

    @Volatile private var systemSnapshot: Bitmap? = null

    @Volatile private var snapshotTimeMs = 0L

    private var logCount = 0

    @Volatile private var liveFrameSignature: Long? = null

    private var livePublishSerial=0L

    private val softwareSnapshots = WeakHashMap<Bitmap, Bitmap>()

    private var gpuFailureLogged = false






    fun processLiveFrame(frame: Bitmap, valid:()->Boolean, finished: (Long) -> Unit) {

        loader.execute {

            var signature = 17L

            var retained = false

            try {

                if (!valid() || !PrefsHelper.enabled || !PrefsHelper.blurEnabled || (!LiveBlurEngine.isResumed && !com.cosmod.launcherglass.xposed.NativeSurfaceLive.active) ||

                    (LiveBlurEngine.isAppLaunchInProgress && !com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) return@execute

                signature = GpuWallpaperBlur.fingerprint(frame)

                val radius = PrefsHelper.blurRadius.coerceIn(0, 50)

                val revision = synchronized(this) { sourceRevision }

                if (signature != liveFrameSignature || cachedBlurRadius != radius || cachedRevision != revision) {

                    val context = appContext

                    val candidate = if (context != null) loadAndBlurWallpaper(context, radius, frame) else null

                    if (candidate != null) synchronized(this) {

                        if (valid() && revision == sourceRevision && radius == PrefsHelper.blurRadius.coerceIn(0, 50) &&

                            PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) {

                            systemSnapshot = frame

                            snapshotTimeMs = SystemClock.uptimeMillis()

                            retained = true

                            blurredWallpaper = candidate
                            publishFolder(candidate)

                            cachedBlurRadius = radius

                            cachedRevision = revision

                            liveFrameSignature = signature

                            livePublishSerial++

                            mainHandler.post {

                                if (PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) {

                                    refreshLiveFrame()

                                }

                            }

                        } else candidate.recycle()

                    }

                }

            } catch (t: Throwable) {

                GlassLogger.e("WallpaperHelper", "Failed to process live wallpaper frame", t)

            } finally {

                if (!retained && !frame.isRecycled) frame.recycle()

                finished(signature)

            }

        }

    }



    private fun refreshLiveFrame() {

        LiveBlurEngine.onWallpaperFrameReady()

    }



    fun forCanvas(bitmap: Bitmap, canvas: Canvas?): Bitmap {

        if (canvas == null || canvas.isHardwareAccelerated || bitmap.config != Bitmap.Config.HARDWARE) return bitmap

        // Only native offscreen software snapshots need this compatibility copy.

        return softwareSnapshots[bitmap]?.takeUnless { it.isRecycled }

            ?: (bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap).also { softwareSnapshots[bitmap] = it }

    }



    @Volatile private var processingActive=false
    fun onProcessingSettingsChanged() {

        val active=PrefsHelper.enabled && PrefsHelper.blurEnabled
        if(processingActive && !active)loader.execute { GpuWallpaperBlur.close() }
        processingActive=active

    }



    @Synchronized

    fun invalidate() {

        // Keep the last completed bitmap until its replacement is ready. Display

        // lists may still reference it, so never recycle a published wallpaper.

        sourceRevision++

        retryAfterMs = 0L

        appContext?.let { requestLoad(it) }

    }



    @Synchronized

    fun onSystemWallpaperChanged() {

        launcherWallpaper = null

        systemSnapshot = null

        snapshotTimeMs = 0L

        invalidate()

        mainHandler.post { LauncherWallpaperCapture.startMotionWatch() }

    }



    fun hasSystemWallpaperEffect(context: Context): Boolean = try {

        Settings.Secure.getInt(context.contentResolver, "is_desktop_wallpaper_blur_enable", 0) != 0 ||

            Settings.Secure.getInt(context.contentResolver, "com.oplus.wallpapers.isLauncherEffectWallpaper", 0) != 0 ||

            WallpaperManager.getInstance(context).wallpaperInfo != null

    } catch (_: Throwable) { false }



    // This API opens the system's wallpaper-only screenshot. It neither hides

    // launcher surfaces nor captures icons, unlike a full-screen screenshot.

    fun readSystemSnapshot(context: Context): Bitmap? {

        val cached = systemSnapshot?.takeUnless { it.isRecycled }

        if (Looper.myLooper() == Looper.getMainLooper()) {

            return cached?.copy(Bitmap.Config.ARGB_8888, false)

        }

        if (cached != null && SystemClock.uptimeMillis() - snapshotTimeMs < 500L) {

            return cached.copy(Bitmap.Config.ARGB_8888, false)

        }

        return try {

            val managerClass = Class.forName("android.app.OplusWallpaperManager")

            val manager = managerClass.getDeclaredConstructor().newInstance()

            val userClass = Class.forName("android.os.UserHandle")

            val userId = userClass.getDeclaredMethod("myUserId").invoke(null) as Int

            val descriptor = managerClass.getMethod("getWallpaperScreenShot", Int::class.javaPrimitiveType)

                .invoke(manager, userId) as? ParcelFileDescriptor

            descriptor?.use {

                BitmapFactory.decodeFileDescriptor(it.fileDescriptor)?.takeUnless { bitmap -> bitmap.isRecycled }

            } ?: LauncherWallpaperCapture.capture(context)

        } catch (t: Throwable) {

            if (logCount < 3) GlassLogger.i("WallpaperHelper", "System screenshot unavailable: ${t.javaClass.simpleName}")

            LauncherWallpaperCapture.capture(context)

        }

    }



    @Synchronized

    fun onLauncherWallpaper(bitmap: Bitmap) {

        if (bitmap.isRecycled) return

        launcherWallpaper = WeakReference(bitmap)

        invalidate()

        mainHandler.post { LauncherWallpaperCapture.startMotionWatch() }

    }



    @Synchronized

    fun onLauncherWindowReady() {

        if (blurredWallpaper == null || cachedRevision != sourceRevision ||

            cachedBlurRadius != PrefsHelper.blurRadius.coerceIn(0, 50)) {

            sourceRevision++

            retryAfterMs = 0L

            appContext?.let { requestLoad(it) }

        }

    }



    @Synchronized

    fun getBlurredWallpaper(context: Context? = null): Bitmap? {

        val ctx = context ?: appContext ?: return blurredWallpaper

        if (appContext == null) appContext = ctx.applicationContext ?: ctx

        requestLoad(ctx)

        return blurredWallpaper?.takeUnless { it.isRecycled }

    }



    private fun requestLoad(context: Context) {

        if (!PrefsHelper.enabled || !PrefsHelper.blurEnabled) return

        val radius = PrefsHelper.blurRadius.coerceIn(0, 50)

        if (isReloading || SystemClock.uptimeMillis() < retryAfterMs ||

            (cachedRevision == sourceRevision && cachedBlurRadius == radius &&

                blurredWallpaper?.isRecycled == false)) return

        isReloading = true

        val revision = sourceRevision

        val liveSerial=livePublishSerial

        val ctx = context.applicationContext ?: context

        loader.execute {

            val candidate = loadAndBlurWallpaper(ctx, radius)

            var published = false

            synchronized(this) {

                isReloading = false

                if (revision == sourceRevision && radius == PrefsHelper.blurRadius.coerceIn(0, 50) && liveSerial==livePublishSerial) {

                    if (candidate != null) {

                        blurredWallpaper = candidate
                        publishFolder(candidate)

                        cachedBlurRadius = radius

                        cachedRevision = revision

                        published = true

                    } else {

                        retryAfterMs = SystemClock.uptimeMillis() + 2000L

                    }

                } else {

                    candidate?.recycle()

                    requestLoad(ctx)

                }

            }

            if (published) mainHandler.post {

                if (PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) {

                    LiveBlurEngine.invalidate()

                }

            }

        }

    }



    fun getRawScaledWallpaper(context: Context): Bitmap? {

        val raw = loadRawWallpaper(context) ?: return null

        val targetH = 800

        val targetW = ((raw.width.toFloat() / raw.height.toFloat()) * targetH).roundToInt().coerceAtLeast(360)

        return Bitmap.createScaledBitmap(raw, targetW, targetH, true)

    }



    @Synchronized

    fun getShader(context: Context, screenWidth: Int, screenHeight: Int, screenX: Int, screenY: Int): BitmapShader? {

        val blurred = getBlurredWallpaper(context) ?: return null



        val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        val matrix = Matrix()

        val scaleX = screenWidth.toFloat() / blurred.width

        val scaleY = screenHeight.toFloat() / blurred.height

        matrix.postScale(scaleX, scaleY)

        matrix.postTranslate(-screenX.toFloat(), -screenY.toFloat())

        shader.setLocalMatrix(matrix)

        return shader

    }



    private fun loadAndBlurWallpaper(context: Context, radius: Int, frame: Bitmap? = null): Bitmap? {

        try {

            val rawBitmap = frame ?: loadRawWallpaper(context)

            if (rawBitmap == null) {

                if (logCount < 3) {

                    GlassLogger.i("WallpaperHelper", "Raw wallpaper could not be loaded")

                    logCount++

                }

                return null

            }



            // Never upscale the already downsampled live capture just to blur it.

            val targetH = minOf(600+PrefsHelper.blurQuality*12, rawBitmap.height)

            val targetW = ((rawBitmap.width.toFloat() / rawBitmap.height.toFloat()) * targetH).roundToInt().coerceAtLeast(1)

            val effectRadius = LiquidOptics.blurRadius(radius,targetH,PrefsHelper.liquidGlass)

            if(PrefsHelper.gpuEffects)try {

                val input=if(android.os.Build.VERSION.SDK_INT<33)BackdropVibrancy.applyCpu(rawBitmap,PrefsHelper.blurVibrancy)else rawBitmap
                val gpu=try {GpuWallpaperBlur.blur(input, targetW, targetH, effectRadius)}finally{if(input!==rawBitmap)input.recycle()}

                return (if(ShadowTone.gpuSupported)gpu else ShadowTone.applyCpu(gpu,PrefsHelper.blurLightening).also {

                    if(it !== gpu)gpu.recycle()

                }).let {withFolder(it,rawBitmap)}

            } catch (t: Throwable) {

                if (!gpuFailureLogged) {

                    GlassLogger.e("GpuBlur", "GPU blur failed; using compatibility CPU path", t)

                    gpuFailureLogged = true

                }

            }



            val scaled = Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)

            val software = if (scaled.config == Bitmap.Config.HARDWARE) {

                scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return null

            } else scaled

            val vibrant=BackdropVibrancy.applyCpu(software,PrefsHelper.blurVibrancy)
            val input=ColorDepthEffect.atCpu(vibrant,ColorDepthEffect.BEFORE_BLUR)
            val actualRadius=effectRadius

            val blurred = try {

                if (actualRadius <= 0f) input.copy(Bitmap.Config.ARGB_8888, false)

                    ?: error("Cannot retain unblurred wallpaper")

                else FastBlur.blur(input, actualRadius.roundToInt().coerceAtLeast(1))

            } finally {

                if(input!==vibrant)input.recycle()
                if (vibrant !== software)vibrant.recycle()
                if (software !== rawBitmap) software.recycle()

                if (scaled !== rawBitmap && scaled !== software) scaled.recycle()

            }



            if (logCount < 5) {

                GlassLogger.i("WallpaperHelper", "Blurred wallpaper ready: ${blurred.width}x${blurred.height}, radius=$radius")

                logCount++

            }

            val toned=ShadowTone.applyCpu(blurred,PrefsHelper.blurLightening)
            val processed=ColorDepthEffect.atCpu(toned,ColorDepthEffect.AFTER_BLUR)
            if(toned!==blurred && processed!==toned)toned.recycle()
            if(processed!==blurred)blurred.recycle()
            return withFolder(processed,rawBitmap)

        } catch (t: Throwable) {

            GlassLogger.e("WallpaperHelper", "Failed to blur wallpaper", t)

            return null

        }

    }



    // Wallpaper access uses the hooked launcher's permissions; denied fallback calls are caught.
    @android.annotation.SuppressLint("MissingPermission")
    private fun loadRawWallpaper(context: Context): Bitmap? {

        readSystemSnapshot(context)?.let {

            systemSnapshot = it

            snapshotTimeMs = SystemClock.uptimeMillis()

            GlassLogger.i("WallpaperHelper", "Loaded wallpaper-only system screenshot (${it.width}x${it.height})")

            return it

        }

        synchronized(this) { launcherWallpaper?.get()?.takeUnless { it.isRecycled } }?.let {

            GlassLogger.i("WallpaperHelper", "Using processed wallpaper delivered to launcher (${it.width}x${it.height})")

            return it

        }

        if (hasSystemWallpaperEffect(context)) {

            // If we already have a valid blurred wallpaper, retain it and retry the screenshot.

            // If blurredWallpaper is null, allow initial static fallback so icons don't lose blur completely!

            if (blurredWallpaper?.isRecycled == false) {

                return null

            }

        }

        // ColorOS effects are rendered by a separate wallpaper service. Its

        // processed bitmap is exposed through the same OEM API the launcher uses;

        // WallpaperManager.drawable can return an unrelated static fallback.

        try {

            val managerClass = Class.forName("android.app.OplusWallpaperManager")

            val manager = managerClass.getDeclaredConstructor().newInstance()

            val userClass = Class.forName("android.os.UserHandle")

            val userId = userClass.getDeclaredMethod("myUserId").invoke(null) as Int

            val metrics = context.resources.displayMetrics

            val sizes = arrayListOf(Point(metrics.widthPixels, metrics.heightPixels))

            val method = managerClass.getMethod("getWallpaperBitmap", Context::class.java,

                java.util.List::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

            val bitmap = method.invoke(manager, context, sizes, WallpaperManager.FLAG_SYSTEM, userId) as? Bitmap

            if (bitmap != null && !bitmap.isRecycled) {

                GlassLogger.i("WallpaperHelper", "Loaded processed system wallpaper from ColorOS (${bitmap.width}x${bitmap.height})")

                return bitmap

            }

        } catch (t: Throwable) {

            if (logCount < 3) GlassLogger.i("WallpaperHelper", "ColorOS wallpaper source unavailable: ${t.javaClass.simpleName}")

        }



        // Compatibility fallback for launchers without the ColorOS API.

        try {

            val wm = WallpaperManager.getInstance(context)

            val d = wm.drawable ?: wm.peekDrawable()

            if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {

                val bmp = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)

                val c = Canvas(bmp)

                d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)

                d.draw(c)

                GlassLogger.i("WallpaperHelper", "Loaded raw wallpaper from WallpaperManager (${bmp.width}x${bmp.height})")

                return bmp

            }

        } catch (t: Throwable) {

            GlassLogger.i("WallpaperHelper", "WallpaperManager.drawable fallback: ${t.message}")

        }



        // Source 2: Dynamic system wallpaper file / fallback cache

        try {

            val cacheFiles = listOf(

                File("/data/system/users/0/wallpaper"),

                File(context.cacheDir, "cosmod_wallpaper.png"),

                File(context.filesDir, "cosmod_wallpaper.png")

            )

            for (file in cacheFiles) {

                if (file.canRead() && file.length() > 1000) {

                    val bmp = BitmapFactory.decodeFile(file.absolutePath)

                    if (bmp != null) {

                        GlassLogger.i("WallpaperHelper", "Loaded raw wallpaper from ${file.absolutePath} (${bmp.width}x${bmp.height})")

                        return bmp

                    }

                }

            }

        } catch (_: Throwable) {}



        return null

    }

}
