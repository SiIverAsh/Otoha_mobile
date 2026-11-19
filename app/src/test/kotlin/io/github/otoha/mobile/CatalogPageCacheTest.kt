package io.github.otoha.mobile

import io.github.otoha.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogPageCacheTest {
    @Test fun recentlyReopenedPagesSurviveEvictionAndBecomeTheLatestPage() {
        val cache = CatalogPageCache<SourceId>(capacity = 2)
        val source = SourceId.ASMR_ONE
        val first = CatalogUi(page = 1, hasMore = true)
        cache.put(source, first)
        cache.put(source, CatalogUi(page = 2, hasMore = true))
        assertEquals(2, cache.latest(source)?.page)
        assertEquals(first, cache.get(source, 1))
        assertEquals(first, cache.latest(source))

        cache.put(source, CatalogUi(page = 3))

        assertEquals(first, cache.get(source, 1))
        assertNull(cache.get(source, 2))
        assertEquals(3, cache.get(source, 3)?.page)
    }

    @Test fun invalidatingAMirrorOrASearchLeavesTheExpectedPages() {
        val cache = CatalogPageCache<CatalogKey>(capacity = 5)
        val asmrOne = CatalogSelection(SourceId.ASMR_ONE).key
        val asmr18 = CatalogSelection(SourceId.ASMR18).key
        val category = CatalogSelection(SourceId.ASMR18, category = "boys").key
        cache.put(asmrOne, CatalogUi(page = 1))
        cache.put(asmrOne, CatalogUi(page = 2))
        cache.put(asmr18, CatalogUi(page = 1))
        cache.put(category, CatalogUi(page = 2))
        assertNull(cache.get(asmr18, 2))

        cache.invalidate { it.source == SourceId.ASMR_ONE }
        assertNull(cache.get(asmrOne, 1))
        assertNull(cache.latest(asmrOne))
        assertEquals(1, cache.latest(asmr18)?.page)
        assertEquals(2, cache.latest(category)?.page)

        cache.clear()
        assertNull(cache.latest(asmr18))
        assertNull(cache.latest(category))
    }
}
