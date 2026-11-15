package io.github.otoha.core

import org.junit.Assert.*
import org.junit.Test

class Asmr18ParserTest {
    @Test fun parsesRealPackedPlayerWithoutExecutingJavaScript() {
        val html = javaClass.getResource("/asmr18-work.html")!!.readText()
        val work = Asmr18Parser.parseWork("https://asmr18.fans/allages/rj01752865/", html)
        assertEquals("理容室こころ", work.summary.title)
        assertEquals("RJ01752865", work.summary.workNumber)
        assertEquals("Crescendo", work.summary.circle)
        assertEquals(listOf("中井みのる"), work.summary.actors)
        assertEquals(ContentRating.GENERAL, work.summary.rating)
        assertEquals(5, work.summary.tags.size)
        val track = work.tracks.single()
        assertTrue(track.isHls)
        assertEquals("https://cdn3.cloudintech.net/file/RJ01752865/RJ01752865.m3u8", track.url)
        assertEquals(5, track.chapters.size)
        assertEquals(Chapter("02もこもこシャンプー", 1_825_000L), track.chapters[1])
        assertNull(track.subtitle)
    }

    @Test fun tabsKeepIndependentChaptersAndStableIdentityWhenCdnChanges() {
        val html = """
            <h1>Multi</h1><label for="file1">SEあり</label><label for="file2">SEなし</label>
            <div class="tab_content" id="file1_content">
            <script>const src='https://cdn3.cloudintech.net/file/RJ123456/1.m3u8';</script>
            <div id="chapter"><a data-value="0">One<span>00:00</span></a></div></div>
            <div class="tab_content" id="file2_content">
            <script>const src='https://cdn3.cloudintech.net/file/RJ123456/2.m3u8';</script>
            <div id="chapter"><a data-value="10.25">Two<span>00:10</span></a>
            <a data-value="NaN">Bad</a><a data-value="-10">Bad</a><a data-value="Infinity">Bad</a></div></div>
        """.trimIndent()
        val work = Asmr18Parser.parseWork("https://asmr18.fans/boys/rj123456/", html)
        assertEquals(ContentRating.RESTRICTED, work.summary.rating)
        assertEquals(2, work.tracks.size)
        assertEquals("SEあり", work.tracks[0].title)
        assertEquals("SEなし", work.tracks[1].title)
        assertEquals(listOf(Chapter("Two", 10_250)), work.tracks[1].chapters)
        assertEquals(1, work.queueFor(work.tracks[0]).size)
        assertNotEquals(work.tracks[0].id, work.tracks[1].id)
        val refreshed = Asmr18Parser.parseWork("https://asmr18.fans/boys/rj123456/", html.replace("cdn3.", "cdn8."))
        assertEquals(work.tracks.map { it.id }, refreshed.tracks.map { it.id })
        assertNotEquals(work.tracks.map { it.url }, refreshed.tracks.map { it.url })
    }

    @Test fun canonicalizesOnlySupportedWorkPages() {
        assertEquals("https://asmr18.fans/allages/rj01752865/",
            Asmr18Parser.pageUrl("https://asmr18.fans/ALLAGES/RJ01752865/?x=1#player"))
        assertEquals("allages/rj01752865", Asmr18Parser.ref("https://asmr18.fans/allages/rj01752865/").id)
        for (url in listOf("http://asmr18.fans/allages/rj123456/", "https://other.test/allages/rj123456/",
            "https://asmr18.fans/allages/rj1234567/", "https://asmr18.fans/allages/rj123456/extra/",
            "https://asmr18.fans:443/allages/rj123456/", "https://user@asmr18.fans/allages/rj123456/",
            "https://asmr18.fans/allages/%72j123456/")) {
            expectFailure<IllegalArgumentException> { Asmr18Parser.pageUrl(url) }
        }
    }

    @Test fun rejectsUnsupportedMediaAndMissingPlayers() {
        for (url in listOf("http://cdn3.cloudintech.net/file/a.m3u8", "https://localhost/file/a.m3u8",
            "https://cdn3.cloudintech.net.evil.test/file/a.m3u8", "https://cdn3.cloudintech.net:8443/file/a.m3u8",
            "https://user@cdn3.cloudintech.net/file/a.m3u8", "https://cdn3.cloudintech.net/other/a.m3u8")) {
            assertFalse(url, Asmr18Parser.validMediaUrl(url))
        }
        expectFailure<SourceException> {
            Asmr18Parser.parseWork("https://asmr18.fans/allages/rj123456/", "<h1>Missing</h1><script>https://evil.test/a.m3u8</script>")
        }
    }

    @Test fun catalogDeduplicatesCardsAndDoesNotFollowRelatedCategories() {
        val html = """
            <link rel="next" href="https://asmr18.fans/allages/page/2/">
            <aside><a href="/allages/rj999999/">Sidebar</a></aside><main id="main">
            <article><a href="/allages/rj123456/"><img data-src="https://example.test/cover.jpg" alt="First"></a>
            <h2><a href="/allages/rj123456/">First title</a></h2></article>
            <article><h2><a href="/allages/rj01752865/">Second title</a></h2></article>
            <a href="/boys/rj123456/">Other category</a></main>
        """.trimIndent()
        val page = Asmr18Parser.parseCatalog("allages", 1, html)
        assertEquals(2, page.works.size)
        assertEquals("First title", page.works.first().title)
        assertEquals("https://example.test/cover.jpg", page.works.first().coverUrl)
        assertTrue(page.works.all { it.rating == ContentRating.GENERAL })
        assertTrue(page.hasMore)
        assertEquals(SourceId.ASMR18, page.works.first().ref.source)
    }

    @Test fun distinguishesChallengeChangedMarkupAndExplicitEmptyCatalog() {
        val challenge = expectFailure<SourceException> {
            Asmr18Parser.parseCatalog("allages", 1, "<title>Just a moment...</title>")
        }
        assertTrue(challenge.challenge)
        expectFailure<SourceException> { Asmr18Parser.parseCatalog("allages", 1, "<main>Changed</main>") }
        expectFailure<SourceException> { Asmr18Parser.parseCatalog("allages", 1, "<main id='main'>Login</main>") }
        val empty = Asmr18Parser.parseCatalog("allages", 1, "<main id='main'><section class='no-results'>None</section></main>")
        assertTrue(empty.works.isEmpty())
        assertFalse(empty.hasMore)
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got $error", error)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }
}
