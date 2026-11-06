package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class WallpaperMotionStateTest {
    @Test fun staticUnlockCaptureStopsEvenWithIdlePauseDisabled() {
        val state = WallpaperMotionState()
        state.wake(100)
        state.observe(1, 100)
        assertFalse(state.shouldStop(1000, false, true, 1900))
        assertTrue(state.shouldStop(1900, false, true, 1900))
    }

    @Test fun liveWallpaperRemainsLiveWhenIdlePauseDisabled() {
        val state = WallpaperMotionState()
        state.wake(100)
        state.observe(1, 100)
        assertFalse(state.shouldStop(5000, false, false, 1900))
        assertTrue(state.shouldStop(5000, true, false, 1900))
    }

    @Test fun movingWallpaperDoesNotSettleEarly() {
        val state = WallpaperMotionState()
        state.wake(100)
        state.observe(1, 100)
        state.observe(2, 1200)
        assertFalse(state.shouldStop(1300, true, false, 0))
        assertTrue(state.shouldStop(1800, true, false, 0))
    }
}
