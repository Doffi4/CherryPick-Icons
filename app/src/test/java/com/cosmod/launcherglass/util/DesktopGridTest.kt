package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class DesktopGridTest {
    @Test fun rejectsInvalidDimensions() {
        assertTrue(DesktopGrid.valid(4, 6))
        assertTrue(DesktopGrid.valid(6, 8))
        for ((x, y) in listOf(0 to 6, 4 to 0, 3 to 6, 7 to 6, 4 to 9, Int.MAX_VALUE to 6)) {
            assertFalse(DesktopGrid.valid(x, y))
        }
    }
    @Test fun previewRejectsClippedOrOverlappingItems() {
        val icon=DesktopGrid.Cell(0,0,1,1)
        val widget=DesktopGrid.Cell(1,0,3,2)
        assertTrue(DesktopGrid.fits(4,6,listOf(icon,widget)))
        assertFalse(DesktopGrid.fits(4,6,listOf(icon,DesktopGrid.Cell(0,0,2,2))))
        assertFalse(DesktopGrid.fits(4,6,listOf(DesktopGrid.Cell(3,0,2,1))))
        assertFalse(DesktopGrid.fits(4,6,listOf(DesktopGrid.Cell(0,-1,1,1))))
        assertFalse(DesktopGrid.fits(4,6,listOf(DesktopGrid.Cell(0,0,0,1))))
        assertFalse(DesktopGrid.fits(4,6,listOf(DesktopGrid.Cell(Int.MAX_VALUE,0,2,1))))
    }
    @Test fun nativeMigrationMayMoveItemsButNeverDeleteThem() {
        assertTrue(DesktopGrid.allowsMutation(true,false,true,false,false))
        assertTrue(DesktopGrid.allowsMutation(true,false,false,true,false))
        assertFalse(DesktopGrid.allowsMutation(true,false,false,false,true))
        assertTrue(DesktopGrid.allowsMutation(false,true,false,false,true))
        assertTrue(DesktopGrid.allowsMutation(false,true,true,false,false))
        assertFalse(DesktopGrid.allowsMutation(false,false,true,false,false))
        assertFalse(DesktopGrid.allowsMutation(false,true,false,true,false))
        assertFalse(DesktopGrid.allowsMutation(true,false,true,false,true))
    }
}
