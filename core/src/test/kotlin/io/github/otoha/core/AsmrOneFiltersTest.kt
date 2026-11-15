package io.github.otoha.core

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AsmrOneFiltersTest {
    @Test fun workFacetsRetainTypedUpstreamIdsAndOriginalNames() {
        val summary = AsmrOneParser.work("""{
            "id":123456,"title":"Work","circle_id":1005704,
            "circle":{"id":1005704,"source_id":"RG01005704","name":"Circle"},
            "vas":[{"id":"c75838bb-1c4d-5850-8a93-b9a56593d511","name":"Voice"},{"id":"missing","name":"N/A"}],
            "tags":[{"id":493,"name":"ABO","i18n":{"zh-cn":{"name":"ABO世界观"}}}]
        }""")
        val circle = summary.facets.single { it.kind == FacetKind.CIRCLE }
        val actor = summary.facets.single { it.kind == FacetKind.ACTOR }
        val tag = summary.facets.single { it.kind == FacetKind.TAG }
        assertEquals("1005704", circle.id)
        assertNotEquals("RG01005704", circle.id)
        assertEquals("c75838bb-1c4d-5850-8a93-b9a56593d511", actor.id)
        assertEquals("ABO世界观", tag.name)
        assertEquals("ABO", tag.queryValue)
        assertEquals(listOf("ABO世界观"), summary.tags)
        assertTrue(summary.facets.all { it.source == SourceId.ASMR_ONE })
    }

    @Test fun optionEndpointsUseArrayShapeAndKeepTagLocalizationSeparateFromQueryName() {
        // Shapes captured from the anonymous /api/tags/, /api/vas/, /api/circles/ endpoints.
        val tag = AsmrOneParser.options(FacetKind.TAG, """[
            {"id":493,"name":"ABO","count":3,"i18n":{"zh-cn":{"name":"ABO世界观"}}},
            {"id":493,"name":"ABO","count":3,"i18n":{}}
        ]""").single()
        assertEquals("493", tag.id)
        assertEquals("ABO世界观", tag.name)
        assertEquals("ABO", tag.queryValue)
        val actor = AsmrOneParser.options(FacetKind.ACTOR, """[
            {"id":"02f5c753-1b03-5187-ab08-829b20113753","name":"^_^","count":5,"i18n":{}}
        ]""").single()
        assertEquals(FacetKind.ACTOR, actor.kind)
        assertEquals("02f5c753-1b03-5187-ab08-829b20113753", actor.id)
        val circle = AsmrOneParser.options(FacetKind.CIRCLE, """[
            {"id":37739,"name":"072LABO","count":108,"i18n":{}}
        ]""").single()
        assertEquals(FacetKind.CIRCLE, circle.kind)
        assertEquals("37739", circle.id)
    }

    @Test fun malformedOptionsRemainDifferentFromAValidEmptyList() {
        assertTrue(AsmrOneParser.options(FacetKind.TAG, "[]").isEmpty())
        for (body in listOf("""{"error":"unauthorized"}""", """[{"name":"no ID"}]""", "not JSON")) {
            try { AsmrOneParser.options(FacetKind.TAG, body); fail("Expected option parsing failure") }
            catch (_: SourceException) { /* An unavailable/changed endpoint is not a successful empty list. */ }
        }
    }

    @Test fun filterDslUsesOriginalNamesInStableOrderAndPreservesPagination() = runBlocking {
        var requested = ""
        val client = client { url -> requested = url; emptyPage(2) }
        val filters = BrowseFilters(listOf(
            value(FacetKind.CIRCLE, "42470", "Circle", "Circle (version A)"),
            value(FacetKind.ACTOR, "voice-uuid", "声优", "Voice Name"),
            value(FacetKind.TAG, "493", "掏耳", "耳かき"),
        ))
        val page = client.filteredBrowse(2, "allages", filters)
        val uri = URI(requested)
        val keyword = URLDecoder.decode(uri.rawPath.substringAfter("/api/search/"), "UTF-8")
        assertEquals("\$tag:耳かき\$ \$va:Voice Name\$ \$circle:Circle (version A)\$", keyword)
        assertFalse(keyword.contains("voice-uuid"))
        assertFalse(keyword.contains("493"))
        assertTrue(uri.rawQuery.contains("page=2"))
        assertTrue(uri.rawQuery.contains("pageSize=30"))
        assertTrue(uri.rawQuery.contains("includeTranslationWorks=true"))
        assertEquals(2, page.page)
        assertFalse(page.hasMore)
    }

    @Test fun filterPathIsEncodedExactlyOnceAndCannotChangeTheRequestQuery() = runBlocking {
        var requested = ""
        val client = client { url -> requested = url; emptyPage() }
        val original = "A/B + C%24?x=#"
        client.filteredBrowse(1, filters = BrowseFilters(listOf(value(FacetKind.TAG, "1", "Display", original))))
        val uri = URI(requested)
        val encoded = uri.rawPath.substringAfter("/api/search/")
        assertTrue(encoded.startsWith("%24tag%3A"))
        assertTrue(encoded.contains("%2F"))
        assertTrue(encoded.contains("%2B"))
        assertTrue(encoded.contains("%2524"))
        assertTrue(encoded.contains("%3F"))
        assertTrue(encoded.contains("%23"))
        assertEquals("\$tag:$original\$", URLDecoder.decode(encoded, "UTF-8"))
        assertNull(uri.rawFragment)
        assertFalse(uri.rawQuery.contains("x="))
    }

    @Test fun emptyFiltersUseTheUnfilteredCatalogEndpoint() = runBlocking {
        var requested = ""
        val client = client { url -> requested = url; emptyPage() }
        client.filteredBrowse(1, filters = BrowseFilters())
        assertTrue(requested.contains("/api/works?"))
        assertFalse(requested.contains("/api/search/"))
    }

    @Test fun optionsRequestsUseRequiredTrailingSlashes() = runBlocking {
        val requested = mutableListOf<String>()
        val client = client { url -> requested += URI(url).path; "[]" }
        FacetKind.entries.forEach { client.facetOptions(it) }
        assertEquals(listOf("/api/tags/", "/api/vas/", "/api/circles/"), requested)
    }

    @Test fun optionsHttpFailuresAreNotReportedAsSuccessfulEmptyLists() = runBlocking {
        val client = client { throw SourceException("unauthorized", 401) }
        try { client.facetOptions(FacetKind.TAG); fail("Expected HTTP failure") }
        catch (error: IOException) { assertEquals(401, (error.cause as SourceException).status) }
    }

    @Test fun tokenInjectionAndOverlongOrWrongSourceFiltersFailBeforeNetwork() = runBlocking {
        var requests = 0
        val client = client { requests++; emptyPage() }
        val invalid = listOf("name\$ \$va:injected", "name\n", "\rname", "name\tvalue", "name\u0000value", "name\u2028value", "a".repeat(501))
        for (name in invalid) {
            try {
                client.filteredBrowse(1, filters = BrowseFilters(listOf(value(FacetKind.TAG, "1", name))))
                fail("Expected invalid filter to be rejected")
            } catch (_: IllegalArgumentException) { /* Invalid data must not become DSL tokens. */ }
        }
        try {
            val foreign = FacetValue(SourceId.ASMR18, FacetKind.TAG, "1", "Tag", "https://asmr18.fans/genre/tag/")
            client.filteredBrowse(1, filters = BrowseFilters(listOf(foreign)))
            fail("Expected source mismatch")
        } catch (_: IllegalArgumentException) { /* Keep source capabilities separate. */ }
        assertEquals(0, requests)
    }

    private fun value(kind: FacetKind, id: String, name: String, query: String = name) =
        FacetValue(SourceId.ASMR_ONE, kind, id, name, query)

    private fun client(request: suspend (String) -> String) = AsmrOneClient(
        mirrors = listOf("https://api.example"), request = { url, _ -> request(url) },
    )

    private fun emptyPage(page: Int = 1) = """{"works":[],"pagination":{"currentPage":$page,"pageSize":30,"totalCount":0}}"""
}
