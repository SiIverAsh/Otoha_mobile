package io.github.otoha.core

/** Public-page adapter; adapted from SiIverAsh/Otoha internal/asmr18 (GPL-3.0). */
class Asmr18Client(
    http: SourceHttp = SourceHttp(),
    private val request: suspend (String, SourceId) -> String = http::get,
) : SourceClient {
    override val source = SourceId.ASMR18

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

    override suspend fun facetOptions(kind: FacetKind): List<FacetValue> {
        val url = Asmr18Parser.facetIndexUrl(kind)
        return Asmr18Parser.parseFacetOptions(kind, request(url, source))
    }

    override suspend fun detail(ref: WorkRef): WorkDetail {
        require(ref.source == source) { "作品音源不匹配" }
        val url = Asmr18Parser.pageUrl(ref.pageUrl.ifBlank { "${Asmr18Parser.baseUrl}/${ref.id}/" })
        return Asmr18Parser.parseWork(url, request(url, source))
    }
}
