package io.github.otoha.core

import java.net.URI
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Static HTML/packer parsing ported from SiIverAsh/Otoha internal/asmr18 (GPL-3.0).
 * No JavaScript is evaluated. Supported CDN hosts are deliberately explicit.
 */
object Asmr18Parser {
    const val baseUrl = "https://asmr18.fans"
    private const val MAX_CHAPTER_START_MS = Long.MAX_VALUE / 1_000
    val categories = setOf("allages", "boys", "girls")
    private val workPath = Regex("^/(boys|girls|allages)/(rj(?:[0-9]{8}|[0-9]{6}))/$")
    private val cdnHost = Regex("^cdn[0-9]+\\.cloudintech\\.net$")
    private val packed = Regex("\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\),0,\\{\\}\\)\\)", RegexOption.DOT_MATCHES_ALL)
    private val word = Regex("\\b[0-9a-zA-Z]+\\b")
    private val mediaUrl = Regex("https?://[^\\s'\"\\\\<>]+\\.m3u8(?:\\?[^\\s'\"\\\\<>]*)?")

    fun pageUrl(raw: String): String {
        val uri = try { URI(raw.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("请输入有效的 ASMR18 作品链接")
        }
        val path = uri.rawPath.orEmpty().lowercase(Locale.ROOT)
        require(uri.scheme == "https" && uri.host == "asmr18.fans" &&
            uri.rawUserInfo == null && uri.port == -1 && workPath.matches(path)) {
            "请输入 https://asmr18.fans/{allages,boys,girls}/rj…/ 作品链接"
        }
        return "$baseUrl$path"
    }

    fun ref(raw: String): WorkRef {
        val url = pageUrl(raw)
        return WorkRef(SourceId.ASMR18, URI(url).path.trim('/'), url)
    }

    fun validMediaUrl(raw: String): Boolean = try {
        val uri = URI(raw)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
            cdnHost.matches(uri.host.orEmpty()) && uri.path.startsWith("/file/") &&
            uri.path.endsWith(".m3u8")
    } catch (_: Exception) { false }

    private fun taxonomy(kind: FacetKind): String = when (kind) {
        FacetKind.TAG -> "genre"
        FacetKind.ACTOR -> "cv"
        FacetKind.CIRCLE -> "circle"
    }

    fun facetIndexUrl(kind: FacetKind): String = "$baseUrl/${taxonomy(kind)}/"

    /** Keeps the original percent encoding; names are never converted into guessed slugs. */
    fun taxonomyUrl(raw: String, kind: FacetKind): String {
        val uri = try { URI(raw.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("无效 ASMR18 筛选链接")
        }
        require(uri.scheme == "https" && uri.host == "asmr18.fans" && uri.rawUserInfo == null &&
            uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null) { "无效 ASMR18 筛选链接" }
        val parts = uri.rawPath.orEmpty().split('/')
        require(parts.size == 4 && parts[0].isEmpty() && parts[1] == taxonomy(kind) && parts[3].isEmpty()) {
            "ASMR18 筛选链接分类不匹配"
        }
        val slug = parts[2]
        require(slug.isNotEmpty() && Regex("[A-Za-z0-9._~!$&'()*+,;=:@%\\-]+").matches(slug)) {
            "无效 ASMR18 筛选标识"
        }
        val decoded = uri.path.split('/').getOrNull(2).orEmpty()
        // Reject encoded delimiters, dot segments, control bytes and double escapes.
        require(decoded.isNotBlank() && decoded !in setOf(".", "..") &&
            decoded.none { it == '/' || it == '\\' || it == '%' || it.code < 32 || it.code == 127 } &&
            !slug.contains(Regex("%(?:2[fF]|5[cC])"))) { "无效 ASMR18 筛选路径" }
        return "$baseUrl/${taxonomy(kind)}/$slug/"
    }

    fun facetArchiveUrl(facet: FacetValue, page: Int = 1): String {
        require(page >= 1) { "页码必须大于 0" }
        require(facet.source == SourceId.ASMR18) { "筛选条件不属于 ASMR18" }
        val url = taxonomyUrl(facet.queryValue, facet.kind)
        require(facet.id == url) { "ASMR18 筛选标识与链接不一致" }
        return url + if (page == 1) "" else "page/$page/"
    }

    fun parseFacetOptions(kind: FacetKind, html: String): List<FacetValue> {
        rejectChallenge(html)
        val document = Jsoup.parse(html, facetIndexUrl(kind))
        val main = document.selectFirst("#main") ?: throw SourceException("ASMR18 筛选选项页结构已变化")
        val facets = linkedMapOf<String, FacetValue>()
        for (anchor in main.select("a[href]")) {
            val facet = facetValue(anchor, kind) ?: continue
            facets[facet.key] = facet
        }
        if (facets.isEmpty()) throw SourceException("ASMR18 未提供可识别的筛选选项，请稍后重试或从作品详情选择")
        return facets.values.toList()
    }

    private fun facetValue(anchor: Element, kind: FacetKind): FacetValue? {
        val url = runCatching { taxonomyUrl(anchor.absUrl("href"), kind) }.getOrNull() ?: return null
        val label = anchor.clone().apply {
            select("span").filter { Regex("\\(?[0-9,]+\\)?").matches(it.text().trim()) }.forEach { it.remove() }
        }.text().trim().replace(Regex("\\s*\\([0-9,]+\\)\\s*$"), "").trim()
        if (label.isBlank()) return null
        return FacetValue(SourceId.ASMR18, kind, url, label, url)
    }

    fun parseCatalog(category: String, page: Int, html: String): CatalogPage {
        require(category in categories && page >= 1)
        return parseCatalog(category, page, html, "$baseUrl/$category/")
    }

    fun parseFacetCatalog(facet: FacetValue, page: Int, html: String): CatalogPage =
        parseCatalog(null, page, html, facetArchiveUrl(facet, page))

    internal fun parseSearch(page: Int, html: String, documentUrl: String): CatalogPage =
        parseCatalog(null, page, html, documentUrl, search = true)

    private fun parseCatalog(
        category: String?, page: Int, html: String, documentUrl: String, search: Boolean = false,
    ): CatalogPage {
        rejectChallenge(html)
        val document = Jsoup.parse(html, documentUrl)
        val main = document.selectFirst("#main") ?: throw SourceException("ASMR18 分类页结构已变化，请稍后重试")
        val results = if (search) {
            if (document.body().hasClass("search-no-results")) return CatalogPage(emptyList(), page, false)
            if (!document.body().hasClass("search-results"))
                throw SourceException("ASMR18 未返回搜索结果页，请稍后重试")
            main.selectFirst(".post-list") ?: throw SourceException("ASMR18 搜索结果页结构已变化，请稍后重试")
        } else main
        val works = linkedMapOf<String, WorkSummary>()
        for (anchor in results.select("a[href]")) {
            val url = runCatching { pageUrl(anchor.absUrl("href")) }.getOrNull() ?: continue
            val workRef = ref(url)
            val workCategory = workRef.id.substringBefore('/')
            if (category != null && workCategory != category) continue
            val number = workRef.id.substringAfter('/').uppercase(Locale.ROOT)
            val card = anchor.closest("article, .post, .item, .entry, li") ?: anchor.parent() ?: anchor
            val image = anchor.selectFirst("img") ?: card.selectFirst("img")
            val title = anchor.children().firstOrNull { it.tagName() == "span" }?.text().orEmpty().trim()
                .ifBlank { anchor.clone().apply { select(".post-list-time").remove() }.text().trim() }
                .ifBlank { image?.attr("alt").orEmpty().trim() }
                .ifBlank { anchor.attr("title").trim() }
                .ifBlank { card.selectFirst("h2, h3, .entry-title")?.text().orEmpty().trim() }
                .ifBlank { number }
            val cover = image?.let { imageUrl(it) }.orEmpty()
            val previous = works[workRef.key]
            works[workRef.key] = WorkSummary(
                ref = workRef,
                title = if (title == number && previous != null) previous.title else title,
                workNumber = number,
                coverUrl = cover.ifBlank { previous?.coverUrl.orEmpty() },
                rating = rating(workCategory),
            )
        }
        if (works.isEmpty() && (search || main.select(".no-results, .not-found").isEmpty())) {
            val pageName = if (search) "搜索结果页" else "分类页"
            throw SourceException("ASMR18 ${pageName}没有可识别作品，页面结构可能已变化")
        }
        val next = document.select("link[rel=next], a.nextpostslink, a.next.page-numbers").isNotEmpty()
        return CatalogPage(works.values.toList(), page, next)
    }

    fun parseWork(raw: String, html: String): WorkDetail {
        val workRef = ref(raw)
        rejectChallenge(html)
        val document = Jsoup.parse(html, workRef.pageUrl)
        val title = document.selectFirst("h1")?.text().orEmpty().trim()
        if (title.isBlank()) throw SourceException("ASMR18 作品缺少标题，页面结构可能已变化")
        val category = workRef.id.substringBefore('/')
        val number = workRef.id.substringAfter('/').uppercase(Locale.ROOT)
        val actors = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val facets = linkedMapOf<String, FacetValue>()
        var circle = ""
        for (anchor in document.select("#post-tag a[href]")) {
            val name = anchor.text().trim().takeIf { it.isNotEmpty() } ?: continue
            FacetKind.entries.mapNotNull { kind -> facetValue(anchor, kind) }.forEach { facets[it.key] = it }
            val path = runCatching { URI(anchor.absUrl("href")).path.trim('/').substringBefore('/') }.getOrNull()
            when (path) {
                "cv" -> actors += name
                "genre" -> tags += name
                "circle" -> circle = name
            }
        }
        val cover = document.selectFirst("video[poster]")?.absUrl("poster").orEmpty()
            .ifBlank { document.selectFirst("meta[property=og:image]")?.absUrl("content").orEmpty() }
        val summary = WorkSummary(workRef, title, number, cover, circle,
            actors.distinct(), tags.distinct(), rating(category), facets.values.toList())
        val selectedTabs = document.select(".tab_content")
        val tabs: List<Element> = if (selectedTabs.isEmpty()) listOf(document) else selectedTabs.toList()
        val seen = mutableSetOf<String>()
        val tracks = mutableListOf<AudioTrack>()
        tabs.forEachIndexed { index, tab ->
            val decoded = unpack(tab.html())
            val urls = mediaUrl.findAll(decoded).map { it.value }.filter(::validMediaUrl)
                .filter { seen.add(it) }.toList()
            var chapters = chapters(tab)
            if (tabs.size == 1 && chapters.isEmpty()) chapters = chapters(document)
            val slot = tab.id().ifBlank { "file${index + 1}" }
            val labelFor = slot.removeSuffix("_content")
            val label = document.select("label[for]").firstOrNull { it.attr("for") == labelFor }
                ?.text().orEmpty().trim().takeUnless { it == number || it.isBlank() } ?: title
            urls.forEachIndexed { urlIndex, url ->
                val stableId = "$slot-${urlIndex + 1}"
                tracks += AudioTrack(
                    id = stableId,
                    title = if (urls.size > 1) "$label (${urlIndex + 1})" else label,
                    url = url,
                    // Tabs (and alternative URLs within a tab) remain separate versions.
                    directory = listOf(stableId),
                    isHls = true,
                    chapters = chapters,
                )
            }
        }
        if (tracks.isEmpty()) throw SourceException("ASMR18 未找到受支持的 HLS 播放源，页面或 CDN 可能已变化")
        val splitTracks = tracks.singleOrNull()?.let(::splitChapters)
        val playableTracks = splitTracks ?: tracks.map { track ->
            track.copy(chapters = track.chapters.distinctBy { it.startMs }.sortedBy { it.startMs })
        }
        return WorkDetail(summary, playableTracks)
    }

    // Follow Otoha's ASMR18SegmentsFromChapters: source cues must cover the
    // entire stream in order. The player can determine the final end.
    private fun splitChapters(track: AudioTrack): List<AudioTrack>? {
        val cues = track.chapters.filterIndexed { index, chapter ->
            track.chapters.getOrNull(index - 1) != chapter
        }
        if (cues.size < 2 || cues.first().startMs != 0L || cues.any { it.title.isBlank() }) return null
        if (cues.zipWithNext().any { (chapter, next) -> chapter.startMs >= next.startMs }) return null
        return cues.mapIndexed { index, chapter ->
            val endMs = cues.getOrNull(index + 1)?.startMs
            track.copy(
                id = "${track.id}:chapter:${chapter.startMs}",
                title = chapter.title,
                durationMs = endMs?.minus(chapter.startMs) ?: 0,
                chapters = emptyList(),
                clipStartMs = chapter.startMs,
                sourceTrackId = track.id,
            )
        }
    }

    private fun chapters(element: Element): List<Chapter> = element.select("#chapter a[data-value]")
        .mapNotNull { anchor ->
            val seconds = anchor.attr("data-value").toDoubleOrNull()
            if (seconds == null || !seconds.isFinite() || seconds < 0) return@mapNotNull null
            // Media3 converts the millisecond offset to microseconds.
            val startMs = (seconds * 1_000).toLong()
            if (startMs > MAX_CHAPTER_START_MS) return@mapNotNull null
            val title = anchor.clone().apply { select("span").remove() }.text().trim()
            Chapter(title, startMs)
        }

    private fun rating(category: String) = if (category == "allages") ContentRating.GENERAL else ContentRating.RESTRICTED

    private fun imageUrl(image: Element): String = sequenceOf("data-src", "data-lazy-src", "src")
        .map { image.absUrl(it) }.firstOrNull { it.startsWith("https://") }.orEmpty()

    private fun rejectChallenge(html: String) {
        val preview = html.take(8192).lowercase(Locale.ROOT)
        if ("<title>just a moment" in preview || "/cdn-cgi/challenge-platform/" in preview) {
            throw SourceException("ASMR18 返回访问验证，请稍后重试", challenge = true)
        }
    }

    /** Decodes the specific Dean Edwards string-table wrapper, never executing it. */
    private fun unpack(script: String): String = packed.replace(script) { match ->
        val base = match.groupValues[2].toIntOrNull() ?: return@replace ""
        if (base !in 2..62) return@replace ""
        val words = match.groupValues[4].split('|')
        // Bound attacker-controlled table sizes and work; input itself is size-limited by SourceHttp.
        if (words.size > 100_000) return@replace ""
        val lookup = words.mapIndexedNotNull { index, value ->
            if (value.isBlank()) null else encode(index, base) to value
        }.toMap()
        word.replace(match.groupValues[1]) { lookup[it.value] ?: it.value }
    }

    private fun encode(value: Int, base: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return if (value < base) alphabet[value].toString() else encode(value / base, base) + alphabet[value % base]
    }
}
