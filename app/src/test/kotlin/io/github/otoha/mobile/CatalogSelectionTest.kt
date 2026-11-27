package io.github.otoha.mobile

import io.github.otoha.core.BrowseFilters
import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSelectionTest {
    private fun facet(kind: FacetKind, id: String, source: SourceId = SourceId.ASMR_ONE) =
        FacetValue(source, kind, id, "选项$id", "query-$id")

    @Test fun `ASMRone replaces one category while retaining the other two AND conditions`() {
        val tag = facet(FacetKind.TAG, "t1")
        val actor = facet(FacetKind.ACTOR, "a1")
        val circle = facet(FacetKind.CIRCLE, "c1")
        val replacement = facet(FacetKind.TAG, "t2")
        val selection = CatalogSelection().withFacet(tag).withFacet(actor).withFacet(circle).withFacet(replacement)
        assertEquals(setOf(replacement, actor, circle), selection.filters.values.toSet())
        assertFalse(selection.filters.values.contains(tag))
    }

    @Test fun `ASMR18 selecting a new taxonomy replaces every prior condition`() {
        val tag = facet(FacetKind.TAG, "t", SourceId.ASMR18)
        val actor = facet(FacetKind.ACTOR, "a", SourceId.ASMR18)
        val selection = CatalogSelection(SourceId.ASMR18).withFacet(tag).withFacet(actor)
        assertEquals(listOf(actor), selection.filters.values)
    }

    @Test fun `a facet cannot change the catalog source`() {
        val one = CatalogSelection().withFacet(facet(FacetKind.TAG, "t"))
        val eighteen = facet(FacetKind.CIRCLE, "c", SourceId.ASMR18)
        assertThrows(IllegalArgumentException::class.java) { one.withFacet(eighteen) }
    }

    @Test fun `remove and clear retain source scope without retaining removed conditions`() {
        val tag = facet(FacetKind.TAG, "t")
        val actor = facet(FacetKind.ACTOR, "a")
        val original = CatalogSelection().withFacet(tag).withFacet(actor)
        val removed = original.without(FacetKind.TAG)
        assertEquals(listOf(actor), removed.filters.values)
        assertTrue(removed.cleared().filters.isEmpty)
        assertEquals(original.source, removed.cleared().source)
        assertEquals(2, original.filters.values.size)
    }

    @Test fun `catalog keys distinguish source category and upstream query but ignore selection order`() {
        val tag = facet(FacetKind.TAG, "t")
        val actor = facet(FacetKind.ACTOR, "a")
        val first = CatalogSelection(filters = BrowseFilters(listOf(tag, actor)))
        val reordered = CatalogSelection(filters = BrowseFilters(listOf(actor, tag)))
        assertEquals(first.key, reordered.key)
        assertNotEquals(first.key, first.copy(category = "boys").key)
        assertNotEquals(first.key, CatalogSelection(SourceId.ASMR18).key)
        val changedQuery = first.copy(filters = BrowseFilters(listOf(tag.copy(queryValue = "changed"), actor)))
        assertNotEquals(first.key, changedQuery.key)
    }

    @Test fun `late A response is rejected after changing to B and back to A`() {
        val gate = CatalogRequestGate()
        val a = CatalogSelection().key
        val b = CatalogSelection().withFacet(facet(FacetKind.TAG, "b")).key
        val oldA = gate.begin(a, 0)
        gate.invalidate()
        gate.begin(b, 0)
        gate.invalidate()
        val newA = gate.begin(a, 0)
        assertFalse(gate.accepts(oldA, a, 0))
        assertTrue(gate.accepts(newA, a, 0))
        assertFalse(gate.accepts(newA, b, 0))
        assertFalse(gate.accepts(newA, a, 1))
    }

    @Test fun `safe display counts hidden adult and unknown items without misreporting no upstream data`() {
        val works = ContentRating.entries.mapIndexed { index, rating ->
            WorkSummary(WorkRef(SourceId.ASMR18, "$index"), "作品$index", rating = rating)
        }
        val safe = CatalogVisibility.from(works, false)
        assertEquals(3, safe.loadedCount)
        assertEquals(2, safe.hiddenCount)
        assertEquals(listOf(ContentRating.GENERAL), safe.visible.map { it.rating })
        val all = CatalogVisibility.from(works, true)
        assertEquals(works, all.visible)
        assertEquals(0, all.hiddenCount)
    }

    @Test fun `page navigation respects boundaries and allows the failed request to be retried`() {
        val initial = CatalogUi()
        assertTrue(initial.canLoad(1))
        assertFalse(initial.canLoad(2))
        val first = CatalogUi(page = 1, hasMore = true)
        assertFalse(first.canLoad(0))
        assertTrue(first.canLoad(2))
        assertFalse(first.copy(request = CatalogUi.Request(2)).canLoad(2))
        assertTrue(first.copy(request = CatalogUi.Request(2, "timeout")).canLoad(2))
        val last = CatalogUi(page = 2)
        assertTrue(last.canLoad(1))
        assertFalse(last.canLoad(3))
    }

    @Test fun `search source reports loading failed empty and filtered pages distinctly`() {
        val hiddenWork = WorkSummary(WorkRef(SourceId.ASMR_ONE, "1"), "作品", rating = ContentRating.RESTRICTED)
        val firstPage = CatalogUi(page = 1)
        assertEquals(null, CatalogUi().searchStatus(false))
        assertEquals("搜索中", CatalogUi(request = CatalogUi.Request(1)).searchStatus(false))
        assertEquals("无结果", firstPage.searchStatus(false))
        assertEquals("本页已隐藏", firstPage.copy(works = listOf(hiddenWork)).searchStatus(false))
        assertEquals("有结果", firstPage.copy(works = listOf(hiddenWork)).searchStatus(true))
        assertEquals("有结果", firstPage.copy(works = listOf(hiddenWork.copy(rating = ContentRating.GENERAL))).searchStatus(false))
        assertEquals("翻页中", firstPage.copy(request = CatalogUi.Request(2)).searchStatus(false))
        assertEquals("加载失败", firstPage.copy(request = CatalogUi.Request(2, "timeout")).searchStatus(false))
    }
}
