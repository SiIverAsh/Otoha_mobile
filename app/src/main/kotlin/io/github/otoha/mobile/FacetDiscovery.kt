package io.github.otoha.mobile

import io.github.otoha.core.WorkSummary

internal object FacetDiscovery {
    /** A sparse list response must not erase taxonomy links learned from the same work's detail. */
    fun merge(previous: WorkSummary?, incoming: WorkSummary): WorkSummary =
        if (incoming.facets.isEmpty() && previous != null && previous.ref.key == incoming.ref.key && previous.facets.isNotEmpty())
            incoming.copy(facets = previous.facets) else incoming
}
