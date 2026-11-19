package io.github.otoha.core

import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

/** Direct, on-demand public API access; mirror behavior follows Otoha (GPL-3.0). */
class AsmrOneClient(
    http: SourceHttp = SourceHttp(),
    private val mirrors: List<String> = DEFAULT_MIRRORS,
    private val request: suspend (String, SourceId) -> String = http::get,
) : SourceClient {
    override val source = SourceId.ASMR_ONE

    init { require(mirrors.isNotEmpty()) { "至少需要一个 ASMR.one 镜像" } }

    override suspend fun browse(page: Int, category: String): CatalogPage {
        require(page > 0) { "页码必须大于 0" }
        // General-only filtering is also enforced locally by the app: the API parameter is not trusted as an age guarantee.
        val path = "/api/works?order=release&sort=desc&page=$page&pageSize=30"
        return acrossMirrors(path) { AsmrOneParser.catalog(it, page, 30) }
    }

    override suspend fun filteredBrowse(page: Int, category: String, filters: BrowseFilters): CatalogPage {
        if (filters.isEmpty) return browse(page, category)
        return search(filterKeyword(filters), page)
    }

    override suspend fun search(keyword: String, page: Int): CatalogPage {
        require(page > 0) { "页码必须大于 0" }
        val query = keyword.trim()
        require(query.isNotEmpty() && query != "." && query != "..") { "请输入有效的搜索关键词" }
        // Encode once as a single path segment. The charset-name overload is available on Android 26.
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val path = "/api/search/$encoded?order=release&sort=desc&page=$page&pageSize=30&includeTranslationWorks=true"
        return acrossMirrors(path) { AsmrOneParser.catalog(it, page, 30) }
    }

    override suspend fun facetOptions(kind: FacetKind): List<FacetValue> {
        val endpoint = when (kind) {
            FacetKind.TAG -> "tags"
            FacetKind.ACTOR -> "vas"
            FacetKind.CIRCLE -> "circles"
        }
        // The trailing slash is required by the public ASMR.one deployment.
        return acrossMirrors("/api/$endpoint/") { AsmrOneParser.options(kind, it) }
    }

    override suspend fun detail(ref: WorkRef): WorkDetail {
        require(ref.source == source) { "作品不属于 ASMR.one" }
        val id = ref.id.trim().let { AsmrOneParser.canonicalWorkNumber(it).ifBlank { it } }
        require(id.toIntOrNull()?.let { it > 0 } == true || AsmrOneParser.canonicalWorkNumber(id).isNotBlank()) { "无效作品编号" }
        val summary = acrossMirrors("/api/workInfo/$id") { AsmrOneParser.work(it, id) }
        val numericId = summary.ref.id.toIntOrNull()?.takeIf { it > 0 }
            ?: summary.workNumber.drop(2).toIntOrNull()?.takeIf { it > 0 }
            ?: throw SourceException("ASMR.one 作品缺少曲目查询标识")
        val tracks = acrossMirrors("/api/tracks/$numericId") { AsmrOneParser.tracks(it, summary.ref) }
        return WorkDetail(summary, tracks)
    }

    private suspend fun <T> acrossMirrors(path: String, parse: (String) -> T): T {
        var lastError: IOException? = null
        for (mirror in mirrors) {
            try {
                return parse(request(mirror.trimEnd('/') + path, source))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException) {
                if (e.status in setOf(400, 404, 410)) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw IOException("ASMR.one 所有镜像暂时不可用：${lastError?.message}", lastError)
    }

    companion object {
        val DEFAULT_MIRRORS = listOf("https://api.asmr-200.com", "https://api.asmr.one", "https://api.asmr-100.com", "https://api.asmr-300.com")

        internal fun filterKeyword(filters: BrowseFilters): String {
            require(filters.values.all { it.source == SourceId.ASMR_ONE }) { "筛选条件不属于 ASMR.one" }
            val keyword = FacetKind.entries.mapNotNull { kind -> filters.values.firstOrNull { it.kind == kind } }
                .joinToString(" ") { value ->
                    val original = value.queryValue.trim()
                    require(value.id.isNotBlank() && original.isNotEmpty()) { "筛选条件缺少名称或标识" }
                    require(value.queryValue.none { it == '$' || it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
                        "筛选名称包含不支持的字符，请选择其他项"
                    }
                    val token = when (value.kind) {
                        FacetKind.TAG -> "tag"
                        FacetKind.ACTOR -> "va"
                        FacetKind.CIRCLE -> "circle"
                    }
                    buildString { append('$'); append(token); append(':'); append(original); append('$') }
                }
            require(keyword.length <= 500) { "筛选条件过长，请选择名称较短的选项" }
            return keyword
        }
    }
}
