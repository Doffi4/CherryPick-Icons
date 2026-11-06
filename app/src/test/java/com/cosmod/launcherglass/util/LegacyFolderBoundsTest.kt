package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyFolderBoundsTest {
    @Test fun convertedBoundsKeepAndroid16Truncation() {
        assertEquals(LegacyFolderBounds(10,5,71,36),LegacyFolderBounds.from(10.75f,5.9f,60.5f,30.6f,100,false))
    }
    @Test fun rtlResizeMirrorsWithinFolderWidth() {
        assertEquals(LegacyFolderBounds(28,5,90,36),LegacyFolderBounds.from(10.75f,5.9f,60.5f,30.6f,100,true))
    }
    @Test fun negativeAnimationOffsetsTruncateTowardZero() {
        assertEquals(LegacyFolderBounds(-2,-1,17,8),LegacyFolderBounds.from(-2.75f,-1.5f,20.5f,10f,100,false))
    }
}
