package com.cannes94.boostervoiture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostMathTest {
    @Test fun gainAtBounds() {
        assertEquals(0, BoostMath.gainMb(100))
        assertEquals(600, BoostMath.gainMb(130))
        assertEquals(1000, BoostMath.gainMb(150))
    }

    @Test fun gainClampsOutOfRange() {
        assertEquals(0, BoostMath.gainMb(99))
        assertEquals(0, BoostMath.gainMb(-5))
        assertEquals(1000, BoostMath.gainMb(200))
    }

    @Test fun voiceBands() {
        assertEquals(0, BoostMath.voiceTargetMb(999))
        assertEquals(300, BoostMath.voiceTargetMb(1000))
        assertEquals(300, BoostMath.voiceTargetMb(4000))
        assertEquals(0, BoostMath.voiceTargetMb(4001))
    }

    @Test fun globalNeedsBothSwitches() {
        assertTrue(BoostMath.globalActive(masterOn = true, globalEnabled = true))
        assertFalse(BoostMath.globalActive(masterOn = true, globalEnabled = false))
        assertFalse(BoostMath.globalActive(masterOn = false, globalEnabled = true))
    }
}
