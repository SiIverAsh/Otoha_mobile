package io.github.otoha.mobile

import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FacetDiscoveryTest {
    private val tag = FacetValue(SourceId.ASMR18, FacetKind.TAG, "tag-1", "已知标签", "https://asmr18.fans/tag/tag-1/")
    private val detail = WorkSummary(WorkRef(SourceId.ASMR18, "allages/rj123456"), "详情标题",
        coverUrl = "https://example.org/old.jpg", rating = ContentRating.GENERAL, facets = listOf(tag))

    @Test fun `sparse catalog refresh retains detail facets while updating all other summary fields`() {
        val incoming = detail.copy(title = "新目录标题", coverUrl = "https://example.org/new.jpg",
            rating = ContentRating.UNKNOWN, facets = emptyList())
        val merged = FacetDiscovery.merge(detail, incoming)
        assertEquals(listOf(tag), merged.facets)
        assertEquals(incoming.title, merged.title)
        assertEquals(incoming.coverUrl, merged.coverUrl)
        assertEquals(ContentRating.UNKNOWN, merged.rating)
        assertEquals(incoming, merged.copy(facets = emptyList()))
    }

    @Test fun `new nonempty facets replace obsolete discovered taxonomy links`() {
        val replacement = tag.copy(id = "tag-2", queryValue = "https://asmr18.fans/tag/tag-2/")
        val incoming = detail.copy(facets = listOf(replacement))
        assertSame(incoming, FacetDiscovery.merge(detail, incoming))
        assertEquals(listOf(replacement), FacetDiscovery.merge(detail, incoming).facets)
    }

    @Test fun `discovered facets never cross work or source identity boundaries`() {
        val anotherWork = detail.copy(ref = detail.ref.copy(id = "allages/rj654321"), facets = emptyList())
        val anotherSource = detail.copy(ref = detail.ref.copy(source = SourceId.ASMR_ONE), facets = emptyList())
        assertTrue(FacetDiscovery.merge(detail, anotherWork).facets.isEmpty())
        assertTrue(FacetDiscovery.merge(detail, anotherSource).facets.isEmpty())
    }

    @Test fun `unseen sparse work does not acquire invented facets`() {
        val incoming = detail.copy(facets = emptyList())
        assertSame(incoming, FacetDiscovery.merge(null, incoming))
        assertTrue(FacetDiscovery.merge(null, incoming).facets.isEmpty())
    }
}
