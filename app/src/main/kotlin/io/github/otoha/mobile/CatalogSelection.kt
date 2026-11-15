package io.github.otoha.mobile

import io.github.otoha.core.BrowseFilters
import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkSummary

internal data class CatalogKey(val source: SourceId, val category: String, val facets: List<FacetValue>)

internal class CatalogRequestGate {
    data class Token(val generation: Long, val key: CatalogKey, val sourceRevision: Int)
    private var generation = 0L
    fun begin(key: CatalogKey, sourceRevision: Int): Token = Token(++generation, key, sourceRevision)
    fun invalidate() { generation++ }
    fun accepts(token: Token, key: CatalogKey, sourceRevision: Int): Boolean =
        token.generation == generation && token.key == key && token.sourceRevision == sourceRevision
}

internal data class CatalogSelection(
    val source: SourceId = SourceId.ASMR_ONE,
    val category: String = "allages",
    val filters: BrowseFilters = BrowseFilters(),
) {
    val key: CatalogKey get() = CatalogKey(source, category, filters.values.sortedBy { it.kind.ordinal })

    fun withFacet(value: FacetValue): CatalogSelection {
        val sameSource = source == value.source
        val next = if (value.source == SourceId.ASMR18 || !sameSource) BrowseFilters(listOf(value))
            else filters.with(value)
        return copy(source = value.source, category = if (sameSource) category else "allages", filters = next)
    }
    fun without(kind: FacetKind): CatalogSelection = copy(filters = filters.without(kind))
    fun cleared(): CatalogSelection = copy(filters = BrowseFilters())
}

internal data class CatalogVisibility(val visible: List<WorkSummary>, val loadedCount: Int, val hiddenCount: Int) {
    companion object {
        fun from(works: List<WorkSummary>, showRestricted: Boolean): CatalogVisibility {
            val visible = works.filter { showRestricted || it.rating == ContentRating.GENERAL }
            return CatalogVisibility(visible, works.size, works.size - visible.size)
        }
    }
}
