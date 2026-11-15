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

    @Test fun `detail facet from another source cannot carry old source conditions`() {
        val one = CatalogSelection().withFacet(facet(FacetKind.TAG, "t"))
        val eighteen = facet(FacetKind.CIRCLE, "c", SourceId.ASMR18)
        val next = one.withFacet(eighteen)
        assertEquals(SourceId.ASMR18, next.source)
        assertEquals("allages", next.category)
        assertEquals(listOf(eighteen), next.filters.values)
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
}
