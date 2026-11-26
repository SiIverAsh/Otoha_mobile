package io.github.otoha.core

import java.io.IOException
import java.net.URLEncoder
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Public-page adapter; adapted from SiIverAsh/Otoha internal/asmr18 (GPL-3.0). */
class Asmr18Client(
    http: SourceHttp = SourceHttp(),
    private val request: suspend (String, SourceId) -> String = http::get,
) : SourceClient {
    override val source = SourceId.ASMR18
    private val requestTrackPage = http::getDlsiteTrackPage

    override suspend fun browse(page: Int, category: String): CatalogPage {
        require(page >= 1) { "页码必须大于 0" }
        require(category in Asmr18Parser.categories) { "未知 ASMR18 分类" }
        val url = "${Asmr18Parser.baseUrl}/$category/" + if (page == 1) "" else "page/$page/"
        return Asmr18Parser.parseCatalog(category, page, request(url, source))
    }

    override suspend fun filteredBrowse(page: Int, category: String, filters: BrowseFilters): CatalogPage {
        if (filters.isEmpty) return browse(page, category)
        require(filters.values.size == 1) { "ASMR18 暂支持一个标签、声优或社团筛选，不能组合多个条件" }
        val facet = filters.values.single()
        val url = Asmr18Parser.facetArchiveUrl(facet, page)
        // Taxonomy archives can mix age categories. Preserve every work's source category;
        // the app's rating policy filters display independently of archive pagination.
        return Asmr18Parser.parseFacetCatalog(facet, page, request(url, source))
    }

    override suspend fun search(keyword: String, page: Int): CatalogPage {
        require(page >= 1) { "页码必须大于 0" }
        val query = keyword.trim()
        require(query.isNotEmpty()) { "请输入有效的搜索关键词" }
        val path = if (page == 1) "/" else "/page/$page/"
        val url = "${Asmr18Parser.baseUrl}$path?s=${URLEncoder.encode(query, "UTF-8")}"
        return Asmr18Parser.parseSearch(page, request(url, source), url)
    }

    override suspend fun facetOptions(kind: FacetKind): List<FacetValue> {
        val url = Asmr18Parser.facetIndexUrl(kind)
        return Asmr18Parser.parseFacetOptions(kind, request(url, source))
    }

    override suspend fun detail(ref: WorkRef): WorkDetail {
        require(ref.source == source) { "作品音源不匹配" }
        val url = Asmr18Parser.pageUrl(ref.pageUrl.ifBlank { "${Asmr18Parser.baseUrl}/${ref.id}/" })
        val detail = Asmr18Parser.parseWork(url, request(url, source))
        val candidates = detail.tracks.filter { it.clipStartMs == null && it.chapters.isEmpty() }
        if (candidates.isEmpty()) return detail

        val workNumber = detail.summary.workNumber
        val entries = loadDlsiteTrackList(workNumber, requestTrackPage)
        if (entries.isEmpty()) return detail

        val splitTracks = coroutineScope {
            candidates.map { track ->
                async {
                    val playlist = loadPlaylist(track.url, workNumber)
                    val replacements = playlist?.let {
                        withContext(Dispatchers.Default) { splitAsmr18FromPublishedTracks(track, entries, it) }
                    }
                    track.id to replacements
                }
            }.awaitAll().toMap()
        }
        return detail.copy(tracks = detail.tracks.flatMap { splitTracks[it.id] ?: listOf(it) })
    }

    private suspend fun loadPlaylist(url: String, workNumber: String): String? = try {
        val playlist = withTimeoutOrNull(PLAYLIST_TIMEOUT_MS) { request(url, source) }
        if (playlist == null) {
            logger.log(Level.WARNING, "ASMR18 playlist timing request timed out for {0}", workNumber)
        }
        playlist
    } catch (error: IOException) {
        logger.log(Level.WARNING, "ASMR18 playlist timing could not be loaded for {0}: {1}",
            arrayOf(workNumber, error.javaClass.simpleName))
        null
    }

    private companion object {
        const val PLAYLIST_TIMEOUT_MS = 6_000L
        val logger: Logger = Logger.getLogger(Asmr18Client::class.java.name)
    }
}
