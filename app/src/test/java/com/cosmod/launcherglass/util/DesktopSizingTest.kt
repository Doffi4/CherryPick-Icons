package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class DesktopSizingTest {
    @Test fun iconsGrowWithinTheirCellAndNeverCompound() {
        assertEquals(224,DesktopSizing.iconPixels(224,100,328,32))
        assertEquals(291,DesktopSizing.iconPixels(224,130,328,32))
        assertEquals(296,DesktopSizing.iconPixels(224,160,328,32))
        assertEquals(224,DesktopSizing.iconPixels(224,160,200,32))
        assertEquals(291,DesktopSizing.iconPixels(224,130,328,32))
    }
    @Test fun paddingCanBeRemovedWithoutNegativeBounds() {
        assertEquals(24,DesktopSizing.padding(24,100))
        assertEquals(12,DesktopSizing.padding(24,50))
        assertEquals(0,DesktopSizing.padding(24,-20))
        assertEquals(24,DesktopSizing.padding(24,200))
    }
    @Test fun alignmentUsesOnlyExistingFreeSpace() {
        assertEquals(0,DesktopSizing.downwardShift(80,200,0))
        assertEquals(60,DesktopSizing.downwardShift(80,200,50))
        assertEquals(120,DesktopSizing.downwardShift(80,200,100))
        assertEquals(0,DesktopSizing.downwardShift(200,80,100))
    }
}
