package io.github.otoha.core

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSearchTest {
    @Test fun asmrOneEncodesKeywordAsOnePathSegmentAndUsesRemotePagination() = runBlocking {
        var requested = ""
        val client = AsmrOneClient(mirrors = listOf("https://example.test"), request = { url, _ ->
            requested = url
            """{"works":[{"id":123456,"title":"Test work","nsfw":false}],"pagination":{"currentPage":2,"pageSize":30,"totalCount":61}}"""
        })
        val keyword = "耳かき A/B + C?x=#"
        val page = client.search("  $keyword  ", 2)
        val uri = URI(requested)
        val encoded = uri.rawPath.removePrefix("/api/search/")
        assertEquals(keyword, URLDecoder.decode(encoded, "UTF-8"))
        assertFalse(encoded.contains("/"))
        assertTrue(uri.rawQuery.contains("page=2"))
        assertEquals(2, page.page)
        assertEquals("Test work", page.works.single().title)
        assertTrue(page.hasMore)
    }

    @Test fun asmr18SearchUsesSiteSearchAcrossCategoriesAndFollowsPageNumbers() = runBlocking {
        val requests = mutableListOf<URI>()
        val client = Asmr18Client(request = { url, _ ->
            val uri = URI(url)
            requests += uri
            if (uri.path == "/") """
                <body class="search search-results">
                    <div id="main"><div class="post-list">
                        <a href="/allages/rj123456/"><span>General work</span></a>
                        <a href="/boys/rj654321/"><span>Restricted work</span></a>
                    </div>
                    <a class="nextpostslink" rel="next" href="/page/2/?s=ASMR">Next</a>
                    <a href="/allages/rj111111/">Outside results</a></div>
                </body>
            """ else """
                <body class="search search-results paged">
                    <div id="main"><div class="post-list">
                        <a href="/girls/rj777777/"><span>Different work</span></a>
                    </div></div>
                </body>
            """
        })
        val keyword = "耳かき + ASMR & more"
        val first = client.search(keyword)
        val second = client.search(keyword, 2)
        assertEquals(listOf("/", "/page/2/"), requests.map { it.path })
        assertTrue(requests.all { URLDecoder.decode(it.rawQuery, "UTF-8") == "s=$keyword" })
        assertEquals(listOf(ContentRating.GENERAL, ContentRating.RESTRICTED), first.works.map { it.rating })
        assertTrue(first.hasMore)
        assertEquals(2, second.page)
        assertEquals("RJ777777", second.works.single().workNumber)
        assertFalse(second.hasMore)
    }

    @Test fun asmr18DistinguishesNoMatchesFromAnUnexpectedPage() {
        val empty = Asmr18Client(request = { _, _ ->
            """<body class="search search-no-results"><div id="main"><div class="post-list"><p>0 results</p></div></div></body>"""
        })
        val unexpected = Asmr18Client(request = { _, _ ->
            """<body class="home"><div id="main"><a href="/allages/rj123456/">Catalog entry</a></div></body>"""
        })
        assertEquals(CatalogPage(emptyList(), 1, false), runBlocking { empty.search("unmatched") })
        assertThrows(SourceException::class.java) { runBlocking { unexpected.search("unexpected") } }
    }
}
