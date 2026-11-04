package com.cosmod.launcherglass.util

internal class WallpaperMotionState {
    private var signature: Long? = null
    private var lastChange = 0L
    private var watchUntil = 0L

    fun wake(now: Long) { watchUntil = maxOf(watchUntil, now + 1200L) }

    fun observe(value: Long, now: Long) {
        if (signature != value) {
            signature = value
            lastChange = now
        }
    }

    fun isSettled(now: Long): Boolean = signature != null && now >= watchUntil && now - lastChange >= 600L
}
