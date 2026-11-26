package io.github.otoha.core

import kotlinx.serialization.Serializable

@Serializable
enum class SourceId { ASMR_ONE, ASMR18 }

@Serializable
enum class ContentRating { GENERAL, RESTRICTED, UNKNOWN }

@Serializable
enum class FacetKind { TAG, ACTOR, CIRCLE }

@Serializable
data class FacetValue(
    val source: SourceId,
    val kind: FacetKind,
    val id: String,
    val name: String,
    // Original upstream name (ASMR.one) or canonical archive URL (ASMR18).
    val queryValue: String = name,
) {
    val key: String get() = "${source.name}:${kind.name}:$id"
}

data class BrowseFilters(val values: List<FacetValue> = emptyList()) {
    init {
        require(values.map { it.kind }.distinct().size == values.size) { "每种筛选条件只能选择一个值" }
        require(values.map { it.source }.distinct().size <= 1) { "不能混合不同音源的筛选条件" }
    }
    val isEmpty: Boolean get() = values.isEmpty()
    fun with(value: FacetValue): BrowseFilters = BrowseFilters(values.filterNot { it.kind == value.kind } + value)
    fun without(kind: FacetKind): BrowseFilters = BrowseFilters(values.filterNot { it.kind == kind })
}

@Serializable
data class WorkRef(val source: SourceId, val id: String, val pageUrl: String = "") {
    val key: String get() = "${source.name}:$id"
}

@Serializable
data class WorkSummary(
    val ref: WorkRef,
    val title: String,
    val workNumber: String = "",
    val coverUrl: String = "",
    val circle: String = "",
    val actors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: ContentRating = ContentRating.UNKNOWN,
    val facets: List<FacetValue> = emptyList(),
)

@Serializable
data class Chapter(val title: String, val startMs: Long)

@Serializable
data class Subtitle(val url: String, val format: String)

@Serializable
data class AudioTrack(
    val id: String,
    val title: String,
    val url: String,
    val directory: List<String> = emptyList(),
    val durationMs: Long = 0,
    val isHls: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val subtitle: Subtitle? = null,
    // Virtual tracks use an absolute start. A zero duration lets the last clip play to the stream end.
    val clipStartMs: Long? = null,
    val sourceTrackId: String? = null,
    val sourceTrackTitle: String? = null,
)

@Serializable
data class WorkDetail(val summary: WorkSummary, val tracks: List<AudioTrack>) {
    // Each immediate directory is an explicit version/queue boundary.
    fun queueFor(track: AudioTrack): List<AudioTrack> = tracks.filter { it.directory == track.directory }
}

data class CatalogPage(val works: List<WorkSummary>, val page: Int, val hasMore: Boolean)

interface SourceClient {
    val source: SourceId
    suspend fun browse(page: Int = 1, category: String = "allages"): CatalogPage
    suspend fun search(keyword: String, page: Int = 1): CatalogPage
    suspend fun detail(ref: WorkRef): WorkDetail
    suspend fun filteredBrowse(page: Int = 1, category: String = "allages", filters: BrowseFilters = BrowseFilters()): CatalogPage {
        require(filters.isEmpty) { "此音源尚未接入远端筛选" }
        return browse(page, category)
    }
    suspend fun facetOptions(kind: FacetKind): List<FacetValue> = emptyList()
}

@Serializable
data class PlaybackSnapshot(
    val work: WorkSummary,
    val tracks: List<AudioTrack>,
    val index: Int,
    val positionMs: Long,
    val speed: Float = 1f,
)
