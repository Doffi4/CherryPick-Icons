package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class CpuFrameOwnerTest {
    @Test fun repeatedDrawOfSameIconReusesItsFrame() {
        val view=Any();val icon=Any()
        val first=CpuFrameOwner(view,icon)
        val second=CpuFrameOwner(view,icon)
        assertEquals(first,second)
        assertEquals(first.hashCode(),second.hashCode())
        assertEquals("own crop",mapOf(first to "own crop")[second])
    }

    @Test fun sameIconInDifferentViewsKeepsSeparateFrames() {
        val icon=Any()
        val first=CpuFrameOwner(Any(),icon)
        val second=CpuFrameOwner(Any(),icon)
        assertNotEquals(first,second)
        val frames=mapOf(first to "desktop crop",second to "folder crop")
        assertEquals("desktop crop",frames[first])
        assertEquals("folder crop",frames[second])
    }

    @Test fun differentIconsInSameViewDoNotShareFrameOrClock() {
        val view=Any()
        assertNotEquals(CpuFrameOwner(view,Any()),CpuFrameOwner(view,Any()))
    }

    @Test fun ownersUseIdentityEvenIfObjectsCompareEqual() {
        data class Owner(val id:Int)
        val icon=Any()
        assertNotEquals(CpuFrameOwner(Owner(1),icon),CpuFrameOwner(Owner(1),icon))
    }
}
