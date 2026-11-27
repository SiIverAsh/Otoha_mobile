package io.github.otoha.mobile

import io.github.otoha.core.BrowseFilters
import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkSummary

internal data class CatalogKey(val source: SourceId, val category: String, val facets: List<FacetValue>)

internal class CatalogPageCache<Scope>(private val capacity: Int) {
    private data class Key<Scope>(val scope: Scope, val page: Int)
    private val pages = LinkedHashMap<Key<Scope>, CatalogUi>(capacity, 0.75f, true)

    fun get(scope: Scope, page: Int): CatalogUi? = pages[Key(scope, page)]

    fun latest(scope: Scope): CatalogUi? {
        val key = pages.keys.lastOrNull { it.scope == scope } ?: return null
        return pages[key]
    }

    fun put(scope: Scope, page: CatalogUi) {
        pages[Key(scope, page.page)] = page
        if (pages.size > capacity) pages.remove(pages.keys.first())
    }

    fun invalidate(matches: (Scope) -> Boolean) {
        pages.keys.removeAll { matches(it.scope) }
    }

    fun clear() = pages.clear()
}

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
        require(value.source == source) { "筛选条件不属于当前音源" }
        val next = if (source == SourceId.ASMR18) BrowseFilters(listOf(value)) else filters.with(value)
        return copy(filters = next)
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
