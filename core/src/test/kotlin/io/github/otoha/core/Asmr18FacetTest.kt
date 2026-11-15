package io.github.otoha.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Asmr18FacetTest {
    private val actorUrl = "https://asmr18.fans/cv/%e4%b8%ad%e4%ba%95%e3%81%bf%e3%81%ae%e3%82%8b/"
    private val actor = FacetValue(SourceId.ASMR18, FacetKind.ACTOR, actorUrl, "中井みのる", actorUrl)
    private val archive = """
        <div id="main"><div class="post-list">
        <a href="/allages/rj01752865/"><div class="post-list-image"><img src="https://example.test/cover.jpg" alt="General work">
        <div class="post-list-time">2026年11月13日4時</div></div><span>General work</span></a>
        <a href="/boys/rj123456/"><div class="post-list-image"><img alt="Restricted work"></div><span>Restricted work</span></a>
        <a href="/girls/rj654321/"><span>Another restricted work</span></a>
        </div><a class="nextpostslink" rel="next" href="${actorUrl}page/3/">›</a></div>
    """.trimIndent()

    @Test fun taxonomyUrlsPreserveUpstreamEncodedSlugsAndKind() {
        assertEquals(actorUrl, Asmr18Parser.taxonomyUrl(actorUrl, FacetKind.ACTOR))
        assertEquals("${actorUrl}page/2/", Asmr18Parser.facetArchiveUrl(actor, 2))
        assertEquals("https://asmr18.fans/genre/", Asmr18Parser.facetIndexUrl(FacetKind.TAG))
        assertEquals("https://asmr18.fans/cv/", Asmr18Parser.facetIndexUrl(FacetKind.ACTOR))
        assertEquals("https://asmr18.fans/circle/", Asmr18Parser.facetIndexUrl(FacetKind.CIRCLE))
    }

    @Test fun rejectsUntrustedTaxonomyOriginsPathsAndEncodedTraversal() {
        for (url in listOf(
            "http://asmr18.fans/cv/name/", "https://evil.test/cv/name/", "https://user@asmr18.fans/cv/name/",
            "https://asmr18.fans:443/cv/name/", "https://asmr18.fans/cv/name/?page=2", "https://asmr18.fans/cv/name/#fragment",
            "https://asmr18.fans/cv/../", "https://asmr18.fans/cv/%2e%2e/", "https://asmr18.fans/cv/name%2Fextra/",
            "https://asmr18.fans/cv/name%5cextra/", "https://asmr18.fans/cv/%252f/", "https://asmr18.fans/cv/%00/",
            "https://asmr18.fans/cv/name/extra/", "https://asmr18.fans/circle/name/", "https://asmr18.fans/cv/",
        )) expectFailure<IllegalArgumentException> { Asmr18Parser.taxonomyUrl(url, FacetKind.ACTOR) }
        expectFailure<IllegalArgumentException> { Asmr18Parser.facetArchiveUrl(actor.copy(source = SourceId.ASMR_ONE)) }
        expectFailure<IllegalArgumentException> { Asmr18Parser.facetArchiveUrl(actor.copy(id = "guessed-from-name")) }
    }

    @Test fun directoryOptionsUseRealLinksAndRemoveOnlyCounts() {
        val html = """
            <aside><a href="/cv/sidebar/">Sidebar</a></aside><div id="main"><div class="tag-list">
            <a href="$actorUrl">中井みのる<span>(123)</span></a>
            <a href="/cv/nested-name/"><span class="name">Named actor</span><span>(1,234)</span></a>
            <a href="/cv/nested-name/">Named actor(1,234)</a>
            <a href="/circle/crescendo/">Wrong kind</a><a href="https://evil.test/cv/no/">External</a>
            <a>No real URL</a><a href="/cv/%2e%2e/">Traversal</a>
            </div></div>
        """.trimIndent()
        val options = Asmr18Parser.parseFacetOptions(FacetKind.ACTOR, html)
        assertEquals(listOf("中井みのる", "Named actor"), options.map { it.name })
        assertEquals(listOf(actorUrl, "https://asmr18.fans/cv/nested-name/"), options.map { it.id })
        assertTrue(options.all { it.source == SourceId.ASMR18 && it.kind == FacetKind.ACTOR && it.id == it.queryValue })
    }

    @Test fun noDirectoryLinksIsAParseFailureRatherThanAnInventedFilter() {
        expectFailure<SourceException> { Asmr18Parser.parseFacetOptions(FacetKind.TAG, "<main>No list</main>") }
        expectFailure<SourceException> { Asmr18Parser.parseFacetOptions(FacetKind.TAG, "<div id='main'>ASMR, Comfort</div>") }
        expectFailure<SourceException> { Asmr18Parser.parseFacetOptions(FacetKind.TAG, "<title>Just a moment...</title>") }
    }

    @Test fun taxonomyArchiveRetainsPerWorkRatingAndRemotePagination() {
        val page = Asmr18Parser.parseFacetCatalog(actor, 2, archive)
        assertEquals(2, page.page)
        assertEquals(3, page.works.size)
        assertEquals(listOf(ContentRating.GENERAL, ContentRating.RESTRICTED, ContentRating.RESTRICTED), page.works.map { it.rating })
        assertEquals("General work", page.works.first().title)
        assertTrue(page.hasMore)
        // An archive membership does not supply the other tags/actors/circle of its cards.
        assertTrue(page.works.all { it.facets.isEmpty() && it.tags.isEmpty() && it.actors.isEmpty() && it.circle.isEmpty() })
    }

    @Test fun workDetailsExposeOnlyActualTaxonomyLinks() {
        val work = Asmr18Parser.parseWork("https://asmr18.fans/allages/rj01752865/",
            javaClass.getResource("/asmr18-work.html")!!.readText())
        assertEquals(7, work.summary.facets.size)
        assertEquals(setOf(FacetKind.TAG, FacetKind.ACTOR, FacetKind.CIRCLE), work.summary.facets.map { it.kind }.toSet())
        assertEquals(actorUrl, work.summary.facets.single { it.kind == FacetKind.ACTOR }.id)
        assertEquals("https://asmr18.fans/circle/crescendo/", work.summary.facets.single { it.kind == FacetKind.CIRCLE }.queryValue)
        assertEquals(5, work.summary.facets.count { it.kind == FacetKind.TAG })
    }

    @Test fun clientUsesOneRemoteArchiveAndNeverFiltersItByBrowseCategory() = runBlocking {
        val requests = mutableListOf<String>()
        val client = Asmr18Client(request = { url, source ->
            assertEquals(SourceId.ASMR18, source)
            requests += url
            archive
        })
        val page = client.filteredBrowse(2, "allages", BrowseFilters(listOf(actor)))
        assertEquals(listOf("${actorUrl}page/2/"), requests)
        assertEquals(3, page.works.size)
        expectSuspendFailure<IllegalArgumentException> {
            client.filteredBrowse(1, "allages", BrowseFilters(listOf(actor,
                FacetValue(SourceId.ASMR18, FacetKind.TAG, "https://asmr18.fans/genre/asmr/", "ASMR", "https://asmr18.fans/genre/asmr/"))))
        }
        assertEquals(1, requests.size)
    }

    @Test fun emptyFiltersKeepCategoryBrowseAndOptionsFetchTaxonomyIndex() = runBlocking {
        val requests = mutableListOf<String>()
        val client = Asmr18Client(request = { url, _ ->
            requests += url
            if (url.endsWith("/circle/")) "<div id='main'><a href='/circle/crescendo/'>Crescendo<span>(24)</span></a></div>"
            else archive
        })
        val page = client.filteredBrowse(1, "allages", BrowseFilters())
        assertEquals(1, page.works.size)
        assertEquals("Crescendo", client.facetOptions(FacetKind.CIRCLE).single().name)
        assertEquals(listOf("https://asmr18.fans/allages/", "https://asmr18.fans/circle/"), requests)
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got $error", error)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }

    private suspend inline fun <reified T : Throwable> expectSuspendFailure(block: () -> Unit): T = expectFailure<T>(block)
}
