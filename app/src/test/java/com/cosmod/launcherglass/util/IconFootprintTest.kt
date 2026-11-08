package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class IconFootprintTest {
    @Test fun enlargedDesktopUsesDisplayedSizeInsteadOfTextureResolution() {
        assertFalse(IconFootprint.isCapture(291,291,291,291))
        assertFalse(IconFootprint.isCapture(224,224,291,291))
        assertTrue(IconFootprint.isCapture(1200,1200,291,291))
    }
    @Test fun wideNativeIconsAreNotMistakenForSnapshots() {
        assertFalse(IconFootprint.isCapture(656,291,656,291))
        assertTrue(IconFootprint.isCapture(1200,600,656,291))
    }
}
