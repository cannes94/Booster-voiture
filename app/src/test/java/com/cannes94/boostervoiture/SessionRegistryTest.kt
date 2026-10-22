package com.cannes94.boostervoiture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryTest {
    @Test fun addUnderLimitDoesNotEvict() {
        val r = SessionRegistry(maxSessions = 2)
        assertEquals(emptyList<Int>(), r.add(1, "a"))
        assertEquals(emptyList<Int>(), r.add(2, "b"))
        assertEquals(2, r.size())
    }

    @Test fun addExistingIsIdempotent() {
        val r = SessionRegistry(maxSessions = 2)
        r.add(1, "a")
        assertEquals(emptyList<Int>(), r.add(1, "a"))
        assertEquals(1, r.size())
        assertTrue(r.has(1))
    }

    @Test fun ninthSessionEvictsOldest() {
        val r = SessionRegistry(maxSessions = 2)
        r.add(1, "a")
        r.add(2, "b")
        assertEquals(listOf(1), r.add(3, "c"))
        assertFalse(r.has(1))
        assertTrue(r.has(3))
    }

    @Test fun removeReportsChange() {
        val r = SessionRegistry()
        r.add(7, null)
        assertTrue(r.remove(7))
        assertFalse(r.remove(7))
    }

    @Test fun packagesUseIdWhenNull() {
        val r = SessionRegistry()
        r.add(4, "com.spotify.music")
        r.add(9, null)
        assertEquals(listOf("com.spotify.music", "#9"), r.packages())
    }

    @Test fun failedSessionIsTrackedUntilClose() {
        val r = SessionRegistry()
        assertFalse(r.isFailed(5))
        r.markFailed(5)
        assertTrue(r.isFailed(5))
        assertFalse(r.remove(5)) // pas attachée : remove ne signale aucun changement
        assertFalse(r.isFailed(5)) // le CLOSE efface l'échec, un futur OPEN réessaiera
    }
}
