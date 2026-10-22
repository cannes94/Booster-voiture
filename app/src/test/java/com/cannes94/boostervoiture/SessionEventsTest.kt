package com.cannes94.boostervoiture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventsTest {
    @Test fun attachOrDetachAlwaysWrites() {
        assertTrue(SessionEvents.shouldWriteDebug(true, null, null))
    }

    @Test fun unchangedHeartbeatDoesNotWrite() {
        assertFalse(SessionEvents.shouldWriteDebug(false, null, null))
        assertFalse(SessionEvents.shouldWriteDebug(false, "refus", "refus"))
    }

    @Test fun newErrorWrites() {
        assertTrue(SessionEvents.shouldWriteDebug(false, null, "Session 42 refusée"))
    }

    @Test fun clearedErrorWrites() {
        assertTrue(SessionEvents.shouldWriteDebug(false, "refus", null))
    }
}
