package io.github.otoha.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPolicyTest {
    private fun policy(): RecordingPolicy {
        var generation = 0
        return RecordingPolicy { "queue-${++generation}" }
    }

    @Test fun `only an explicitly started normal queue may record`() {
        val policy = policy()
        assertFalse(policy.canRecord(null))
        assertFalse(policy.canRecord("restored-old-token"))
        val queue = policy.beginPlayback()
        assertTrue(policy.canRecord(queue))
        assertFalse(policy.canRecord("another-queue"))
    }

    @Test fun `entering and leaving incognito never backfills the existing queue`() {
        val policy = policy()
        val normalQueue = policy.beginPlayback()
        policy.setIncognito(true)
        assertFalse(policy.canRecord(normalQueue))
        policy.setIncognito(false)
        assertFalse(policy.canRecord(normalQueue))
        val newNormalQueue = policy.beginPlayback()
        assertTrue(policy.canRecord(newNormalQueue))
        assertFalse(policy.canRecord(normalQueue))
    }

    @Test fun `a queue selected while incognito stays private after exiting`() {
        val policy = policy()
        policy.setIncognito(true)
        val privateQueue = policy.beginPlayback()
        assertFalse(policy.canRecord(privateQueue))
        policy.setIncognito(false)
        assertFalse(policy.canRecord(privateQueue))
    }

    @Test fun `clearing history revokes periodic and final callbacks from the current queue`() {
        val policy = policy()
        val queue = policy.beginPlayback()
        policy.revoke()
        assertFalse(policy.canRecord(queue))
        val laterQueue = policy.beginPlayback()
        assertTrue(policy.canRecord(laterQueue))
        assertFalse(policy.canRecord(queue))
    }

    @Test fun `late callbacks from a private queue cannot use the next normal queues permission`() {
        val policy = policy()
        policy.setIncognito(true)
        val privateQueue = policy.beginPlayback()
        policy.setIncognito(false)
        val newQueue = policy.beginPlayback()
        assertFalse(policy.canRecord(privateQueue))
        assertTrue(policy.canRecord(newQueue))
    }

    @Test fun `starting another normal queue invalidates callbacks from the old queue`() {
        val policy = policy()
        val previous = policy.beginPlayback()
        val current = policy.beginPlayback()
        assertFalse(policy.canRecord(previous))
        assertTrue(policy.canRecord(current))
    }

    @Test fun `refreshing URLs cannot turn a private queue into a recorded queue`() {
        val policy = policy()
        policy.setIncognito(true)
        val privateQueue = policy.beginPlayback()
        policy.setIncognito(false)
        val refreshed = policy.replacePlayback(privateQueue)
        assertNotNull(refreshed)
        assertFalse(policy.canRecord(refreshed))
        assertFalse(policy.canRecord(privateQueue))
    }

    @Test fun `refreshing a normal queue transfers its existing permission to a new token`() {
        val policy = policy()
        val original = policy.beginPlayback()
        val refreshed = policy.replacePlayback(original)
        assertNotNull(refreshed)
        assertTrue(policy.canRecord(refreshed))
        assertFalse(policy.canRecord(original))
    }

    @Test fun `a stale URL refresh cannot replace or revoke a newer queue`() {
        val policy = policy()
        val old = policy.beginPlayback()
        val current = policy.beginPlayback()
        assertNull(policy.replacePlayback(old))
        assertTrue(policy.canRecord(current))
    }
}
