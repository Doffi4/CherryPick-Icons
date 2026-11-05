package com.cosmod.launcherglass.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.xposed.IconGlassHook
import com.cosmod.launcherglass.xposed.PrefsHelper
import java.util.concurrent.atomic.AtomicBoolean

object LiveBlurEngine {

    private const val TAG = "LiveBlurEngine"

    @Volatile
    var isMoving: Boolean = false
        private set

    @Volatile
    var isResumed: Boolean = true
        private set

    @Volatile
    var lastMotionTimeMs: Long = 0L
        private set

    @Volatile
    var activePointerCount: Int = 0
        private set

    @Volatile
    var wallpaperScrollRatio: Float = 0f
        private set

    private var lastWorkspaceScrollMs = 0L

    /**
     * Touch, scrolling, transitions or wallpaper motion during the settling
     * window. Unlike [isMoving] this does not depend on the idle-pause switch,
     * so per-frame work gated by it always stops on a stationary screen.
     */
    val hasRecentMotion: Boolean
        get() {
            val now = SystemClock.uptimeMillis()
            return activePointerCount > 0 || now - lastMotionTimeMs < 600L || now < transitionRefreshUntilMs ||
                isWallpaperMoving || transitionVisible()
        }
    val isWorkspaceScrolling: Boolean
        get() = lastWorkspaceScrollMs > 0L && SystemClock.uptimeMillis() - lastWorkspaceScrollMs < 90L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isTickerRunning = AtomicBoolean(false)
    private var transitionRefreshUntilMs = 0L

    @Volatile
    var isAppLaunchInProgress: Boolean = false
        private set

    var isWallpaperMoving: Boolean = false
        private set

    fun onWallpaperMotion(moving: Boolean) = onMainThread {
        isWallpaperMoving = moving
        if (moving) startTickerIfNeeded()
    }

    fun onWallpaperFrameReady() = onMainThread {
        if (!PrefsHelper.enabled || !PrefsHelper.blurEnabled || !isResumed || isAppLaunchInProgress) return@onMainThread
        if (PrefsHelper.blurMode == SettingsProvider.BLUR_MODE_LIVE) {
            IconGlassHook.requestLiveFrame()
            if (!isTickerRunning.get()) {
                lastMotionTimeMs = SystemClock.uptimeMillis()
                startTickerIfNeeded()
            }
        } else IconGlassHook.invalidateActiveIcons()
    }

    @Volatile
    var isWorkspaceBlurSuspended: Boolean = false
        private set

    private var drawerProgress = 1f // Native controller: 1 = closed, 0 = open.

    fun onDrawerTargetChanged(opening: Boolean) = onMainThread {
        setWorkspaceBlurSuspended(opening)
    }

    fun onDrawerProgress(progress: Float) = onMainThread {
        if (!progress.isFinite()) return@onMainThread
        val next = progress.coerceIn(0f, 1f)
        // Direction also covers user-controlled gestures, reversals and canceled
        // openings. Resume on the first closing frame, before the drawer lands.
        when {
            next < drawerProgress -> setWorkspaceBlurSuspended(true)
            next > drawerProgress -> setWorkspaceBlurSuspended(false)
        }
        drawerProgress = next
    }

    private fun setWorkspaceBlurSuspended(suspended: Boolean) {
        if (isWorkspaceBlurSuspended == suspended) return
        isWorkspaceBlurSuspended = suspended
        if (suspended) {
            if (!PrefsHelper.drawerBlurEnabled) stopFrameLoop()
        } else {
            lastMotionTimeMs = SystemClock.uptimeMillis()
            transitionRefreshUntilMs = maxOf(transitionRefreshUntilMs, lastMotionTimeMs + 600L)
            if (isResumed && !isAppLaunchInProgress) {
                IconGlassHook.invalidateActiveIcons()
                startTickerIfNeeded()
                LauncherWallpaperCapture.startMotionWatch()
            }
        }
    }

    private fun hasLiveBlurWork(): Boolean = PrefsHelper.blurEnabled &&
        (!isWorkspaceBlurSuspended || PrefsHelper.drawerBlurEnabled)

    private fun transitionVisible()=com.cosmod.launcherglass.xposed.NativeSurfaceLive.active
    private fun workspaceVisible()=transitionVisible() || (isResumed && !isAppLaunchInProgress)
    fun onNativeSurfacesChanged()=onMainThread {
        if(transitionVisible()){
            transitionRefreshUntilMs=maxOf(transitionRefreshUntilMs,SystemClock.uptimeMillis()+600L)
            startTickerIfNeeded()
        }
    }
    private fun stopFrameLoop() {
        if(transitionVisible() && PrefsHelper.enabled){
            startTickerIfNeeded()
            return
        }
        if(!com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)LauncherWallpaperCapture.stopMotionWatch()
        mainHandler.removeCallbacks(tickerRunnable)
        isTickerRunning.set(false)
        isMoving = false
        activePointerCount = 0
        transitionRefreshUntilMs = 0L
    }

    fun onAppLaunchStarted() = onMainThread {
        isAppLaunchInProgress = true
        stopFrameLoop()
    }

    fun onHomeReturnStarted() = onMainThread {
        val wasSuspended = isAppLaunchInProgress || !isResumed
        isAppLaunchInProgress = false
        isResumed = true
        lastMotionTimeMs = SystemClock.uptimeMillis()
        transitionRefreshUntilMs = maxOf(transitionRefreshUntilMs, lastMotionTimeMs + 600L)
        if (wasSuspended) IconGlassHook.invalidateActiveIcons(onlyChangedDrawerSamples=true)
        startTickerIfNeeded()
    }

    @Volatile
    var isEditing: Boolean = false
        private set

    fun onEditModeChanged(editing: Boolean) = onMainThread {
        if (isEditing != editing) {
            isEditing = editing
            lastMotionTimeMs = SystemClock.uptimeMillis()
            // Keep live material and the OEM animation alive during drag/edit.
            // The ordinary idle and FPS gates still limit background work.
            IconGlassHook.invalidateActiveIcons()
            startTickerIfNeeded()
            LauncherWallpaperCapture.startMotionWatch()
        }
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    fun onTransitionStarted(durationMs: Long = 1200L) = onMainThread {
        if (isAppLaunchInProgress || !isResumed) return@onMainThread
        // A previous app's completion must not cancel a newer return animation.
        transitionRefreshUntilMs = maxOf(transitionRefreshUntilMs, SystemClock.uptimeMillis() + durationMs)
        startTickerIfNeeded()
    }

    fun onTransitionFinished() = onMainThread {
        if (isAppLaunchInProgress || !isResumed) return@onMainThread
        onTransitionStarted(600L)
        IconGlassHook.invalidateActiveIcons()
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val isTransitioning = now < transitionRefreshUntilMs
            if (!workspaceVisible() || !PrefsHelper.enabled || !hasLiveBlurWork() ||
                PrefsHelper.blurMode != SettingsProvider.BLUR_MODE_LIVE) {
                isTickerRunning.set(false)
                isMoving = false
                return
            }

            val settlingTimeMs = 600L // 600ms after last motion/scroll event has rested
            val hasRecentMotion = (now - lastMotionTimeMs) < settlingTimeMs
            val isUnderTouch = activePointerCount > 0
            val isCurrentlyMoving = isUnderTouch || hasRecentMotion || isTransitioning || isWallpaperMoving || transitionVisible()

            if (PrefsHelper.idlePause && !isCurrentlyMoving) {
                if (isMoving) {
                    GlassLogger.i(TAG, "Screen idle -> Live blur ticker paused")
                }
                isMoving = false
                isTickerRunning.set(false)
                return
            }

            isMoving = true

            // Geometry follows native pre-draw at the display's refresh rate.
            // New wallpaper publications request their own frame. A timer must
            // not manufacture 60 redundant traversals/s for a stationary touch.

            // This runnable only checks whether activity has settled; it does
            // not render a frame. Actual sampling is driven by pre-draw and
            // wallpaper publications, so polling it at 60 Hz wastes UI-thread
            // wakeups without improving animation cadence.
            mainHandler.postDelayed(this, 100L)
        }
    }

    fun startTickerIfNeeded() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startTickerIfNeeded() }
            return
        }
        if (!workspaceVisible() ||
            !PrefsHelper.enabled || !hasLiveBlurWork() || PrefsHelper.blurMode != SettingsProvider.BLUR_MODE_LIVE) {
            return
        }

        val fps = PrefsHelper.liveFps.coerceIn(1, 60)
        if (isTickerRunning.compareAndSet(false, true)) {
            if (!isMoving) {
                GlassLogger.i(TAG, "Motion detected -> live blur activity check resumed (sampling limit ${fps} FPS)")
            }
            isMoving = true
            mainHandler.removeCallbacks(tickerRunnable)
            mainHandler.postDelayed(tickerRunnable, 100L)
        }
    }

    fun onPrefsChanged() = onMainThread {
        if(!PrefsHelper.enabled){
            LauncherWallpaperCapture.stopMotionWatch()
            mainHandler.removeCallbacks(tickerRunnable);isTickerRunning.set(false)
            isMoving=false;isWallpaperMoving=false;activePointerCount=0
            isEditing=false;isAppLaunchInProgress=false;isWorkspaceBlurSuspended=false
            transitionRefreshUntilMs=0L
            return@onMainThread
        }
        LauncherWallpaperCapture.stopMotionWatch()
        IconGlassHook.invalidateFolderPreviews()
        if (PrefsHelper.blurMode == SettingsProvider.BLUR_MODE_LIVE) {
            lastMotionTimeMs = SystemClock.uptimeMillis()
            mainHandler.removeCallbacks(tickerRunnable)
            isTickerRunning.set(false)
            isMoving = false
            startTickerIfNeeded()
        } else {
            isTickerRunning.set(false)
            isMoving = false
            mainHandler.removeCallbacks(tickerRunnable)
        }
        IconGlassHook.invalidateActiveIcons()
        LauncherWallpaperCapture.startMotionWatch()
    }

    fun invalidate() {
        IconGlassHook.invalidateActiveIcons()
    }

    fun onTouch(event: MotionEvent) {
        if (isAppLaunchInProgress) {
            onHomeReturnStarted()
        }
        // Touch delivery proves the launcher is visible, including interrupted
        // returns where ColorOS does not deliver another onResume.
        isResumed = true
        lastMotionTimeMs = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                LauncherWallpaperCapture.startMotionWatch()
                activePointerCount = 1
                startTickerIfNeeded()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount++
                startTickerIfNeeded()
            }
            MotionEvent.ACTION_MOVE -> {
                startTickerIfNeeded()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount = (activePointerCount - 1).coerceAtLeast(0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
            }
        }
    }

    fun onScroll(scrollX: Int, view: View) {
        val name = view.javaClass.name
        if (!name.contains("Workspace") || name.contains("Recents") || name.contains("Task")) {
            return
        }

        // Actual workspace scrolling is newer evidence than delayed lifecycle
        // callbacks from an application that is already being dismissed.
        isResumed=true
        isAppLaunchInProgress=false
        lastMotionTimeMs = SystemClock.uptimeMillis()
        lastWorkspaceScrollMs = lastMotionTimeMs

        val childCount = (view as? ViewGroup)?.childCount ?: 3
        val maxScroll = (maxOf(1, childCount - 1) * view.width).coerceAtLeast(1)
        wallpaperScrollRatio = (scrollX.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)

        // Wake up ticker at configured FPS rate (DO NOT invalidate directly so FPS limiter is strictly honored!)
        startTickerIfNeeded()
    }

    fun onActivityPaused() = onMainThread {
        isResumed = false
        stopFrameLoop()
    }

    fun onActivityResumed() = onMainThread {
        isAppLaunchInProgress = false
        isResumed = true
        lastMotionTimeMs = SystemClock.uptimeMillis()
        // Reset both halves of the scheduler together after rapid pause/resume.
        mainHandler.removeCallbacks(tickerRunnable)
        isTickerRunning.set(false)
        transitionRefreshUntilMs = maxOf(transitionRefreshUntilMs, lastMotionTimeMs + 600L)
        startTickerIfNeeded()
        LauncherWallpaperCapture.startMotionWatch()
        IconGlassHook.invalidateActiveIcons(onlyChangedDrawerSamples=true)
    }

    fun getLiveBlurBitmap(context: Context?, hostView: View?): Bitmap? {
        return WallpaperHelper.getBlurredWallpaper(context)
    }
}
