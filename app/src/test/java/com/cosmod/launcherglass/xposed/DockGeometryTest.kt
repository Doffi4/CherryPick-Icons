package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class DockGeometryTest {
    @Test fun automaticWidthFollowsNativeIconMovement() {
        assertEquals(230f, DockGeometry.halfWidth(true, 500f, 300f, 700f, 1000f, 200f, 160f, 30f), .001f)
        assertEquals(180f, DockGeometry.halfWidth(true, 500f, 350f, 650f, 1000f, 200f, 160f, 30f), .001f)
    }

    @Test fun disabledAutomaticWidthDoesNotScaleWithIconCount() {
        val many = DockGeometry.halfWidth(false, 500f, 100f, 900f, 1000f, 200f, 160f, 30f)
        val one = DockGeometry.halfWidth(false, 500f, 420f, 580f, 1000f, 200f, 160f, 30f)
        assertEquals(510f, many, .001f)
        assertEquals(many, one, .001f)
    }

}
