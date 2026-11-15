package io.github.otoha.mobile

import java.util.UUID

/** Recording permission belongs to one explicit queue selection, never to the whole player. */
internal class RecordingPolicy(private val createToken: () -> String = { UUID.randomUUID().toString() }) {
    private var incognito = false
    private var currentQueueToken: String? = null
    private var allowedQueueToken: String? = null

    @Synchronized
    fun beginPlayback(): String {
        val token = createToken()
        currentQueueToken = token
        allowedQueueToken = token.takeUnless { incognito }
        return token
    }

    /** Refreshing URLs is the same listening session, so it cannot grant recording permission. */
    @Synchronized
    fun replacePlayback(previousToken: String?): String? {
        if (previousToken == null || previousToken != currentQueueToken) return null
        val mayRecord = canRecord(previousToken)
        val token = createToken()
        currentQueueToken = token
        allowedQueueToken = token.takeIf { mayRecord }
        return token
    }

    @Synchronized
    fun setIncognito(value: Boolean) {
        incognito = value
        if (value) allowedQueueToken = null
    }

    @Synchronized
    fun revoke() { allowedQueueToken = null }

    @Synchronized
    fun canRecord(queueToken: String?): Boolean =
        !incognito && queueToken != null && queueToken == allowedQueueToken
}
