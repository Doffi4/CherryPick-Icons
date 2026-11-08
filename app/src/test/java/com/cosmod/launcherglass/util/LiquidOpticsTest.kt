package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class LiquidOpticsTest {
    @Test fun zeroBlurKeepsLiquidBackdropButClearsFrosted() {
        assertTrue(LiquidOptics.needsBackdrop(0,true))
        assertFalse(LiquidOptics.needsBackdrop(0,false))
        assertTrue(LiquidOptics.needsBackdrop(50,false))
    }
    @Test fun liquidHasNoBlurAtAnyRadiusOrScale() {
        for (radius in listOf(0, 10, 50)) for (height in listOf(600, 1800, 3200)) {
            assertEquals(0f, LiquidOptics.blurRadius(radius, height, true), 0f)
            assertEquals(radius * height / 800f, LiquidOptics.blurRadius(radius, height, false), .001f)
        }
    }
    @Test fun bloomOnlyExtractsHighlights() {
        assertEquals(0f, LiquidOptics.highlight(.4f, .4f, .4f), 0f)
        assertTrue(LiquidOptics.highlight(.9f, .9f, .9f) > 0f)
        assertTrue(LiquidOptics.highlight(0f, 0f, 1f) == 0f)
    }
    @Test fun finishKeepsTextureAndBounds() {
        assertTrue(LiquidOptics.channel(.6f, 0f, .05f) > LiquidOptics.channel(.4f, 0f, .05f))
        assertTrue(LiquidOptics.channel(.4f, .5f, .05f) > LiquidOptics.channel(.4f, 0f, .05f))
        assertEquals(1f, LiquidOptics.channel(1f, 1f, .08f), 0f)
    }
}
