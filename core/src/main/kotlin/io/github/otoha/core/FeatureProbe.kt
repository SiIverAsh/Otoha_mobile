package io.github.otoha.core

import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/** Explicit live checks, never part of the offline test suite or automatic CI. */
fun verifyRemoteFilters() = withProbeClients { one, eighteen, http ->
    val oneSeed = one.detail(WorkRef(SourceId.ASMR_ONE, "1699648"))
    val oneValues = FacetKind.entries.map { kind ->
        val options = one.facetOptions(kind)
        val fromWork = oneSeed.summary.facets.first { it.kind == kind && (kind != FacetKind.TAG || it.id == "500") }
        val selected = options.single { it.id == fromWork.id }
        println("[ASMR_ONE] options kind=$kind count=${options.size}")
        val page = one.filteredBrowse(1, filters = BrowseFilters(listOf(selected)))
        check(page.works.isNotEmpty()) { "Single-facet reference query unexpectedly empty" }
        val filters = BrowseFilters(listOf(selected))
        val direct = page.works.count { work -> work.facets.any { it.key == selected.key } }
        // The site's includeTranslationWorks expands originals to fan-translation editions,
        // whose publishing circle can differ. Validate strict upstream matching separately.
        verifyStrictOneFilters(http, filters)
        println("[ASMR_ONE] filter kind=$kind count=${page.works.size} hasMore=${page.hasMore} directIdMatches=$direct translationExpansion=${page.works.size - direct} strictIdsMatch=true")
        selected
    }
    val combination = one.filteredBrowse(1, filters = BrowseFilters(oneValues))
    check(combination.works.isNotEmpty()) { "Known combination unexpectedly empty" }
    verifyStrictOneFilters(http, BrowseFilters(oneValues))
    println("[ASMR_ONE] combination count=${combination.works.size} strictIdsMatch=true includesRelatedTranslations=true")

    val eighteenSeed = eighteen.detail(Asmr18Parser.ref("https://asmr18.fans/allages/rj01752865/"))
    for (kind in FacetKind.entries) {
        val fromWork = eighteenSeed.summary.facets.first { it.kind == kind }
        val options = eighteen.facetOptions(kind)
        val selected = options.single { it.id == fromWork.id }
        println("[ASMR18] options kind=$kind count=${options.size}")
        val page = eighteen.filteredBrowse(1, filters = BrowseFilters(listOf(selected)))
        check(page.works.isNotEmpty()) { "Taxonomy reference archive unexpectedly empty" }
        // Archive cards omit taxonomy metadata: verify a real detail rather than fabricating fields.
        val sample = eighteen.detail(page.works.first().ref)
        check(sample.summary.facets.any { it.key == selected.key }) { "Archive sample detail does not contain the selected taxonomy" }
        println("[ASMR18] filter kind=$kind count=${page.works.size} hasMore=${page.hasMore} sampleDetailMatches=true ratings=${page.works.groupingBy { it.rating }.eachCount()}")
        if (kind == FacetKind.TAG && page.hasMore) {
            val next = eighteen.filteredBrowse(2, filters = BrowseFilters(listOf(selected)))
            check(next.page == 2 && next.works.isNotEmpty())
            check(next.works.none { nextWork -> page.works.any { it.ref == nextWork.ref } })
            println("[ASMR18] filtered-page2 count=${next.works.size} disjoint=true")
        }
    }
    println("[filters] PASS scope=upstream_requests_and_entity_mapping")
}

private suspend fun verifyStrictOneFilters(http: SourceHttp, filters: BrowseFilters) {
    val encoded = URLEncoder.encode(AsmrOneClient.filterKeyword(filters), "UTF-8").replace("+", "%20")
    val body = http.get("https://api.asmr.one/api/search/$encoded?order=release&sort=desc&page=1&pageSize=30&includeTranslationWorks=false", SourceId.ASMR_ONE)
    val strict = AsmrOneParser.catalog(body, 1)
    check(strict.works.isNotEmpty()) { "Known strict reference filter unexpectedly empty" }
    check(strict.works.all { work -> filters.values.all { selected -> work.facets.any { it.key == selected.key } } }) {
        "Upstream ignored a strict selected facet"
    }
}

fun verifyRemoteSubtitles() = withProbeClients { one, _, http ->
    val raw = http.get("https://api.asmr.one/api/works?order=release&sort=desc&page=1&pageSize=5&subtitle=1", SourceId.ASMR_ONE)
    val works = AsmrOneParser.catalog(raw, 1, 5)
    var verified = false
    for (summary in works.works.take(5)) {
        val work = one.detail(summary.ref)
        val track = work.tracks.firstOrNull { it.subtitle != null }
        if (track == null) {
            println("[subtitles] work=${summary.ref.id} pairedTracks=0")
            continue
        }
        val subtitle = requireNotNull(track.subtitle)
        val text = http.getSubtitle(subtitle.url, SourceId.ASMR_ONE)
        val cues = SubtitleParser.parse(text, subtitle.format)
        check(cues.isNotEmpty()) { "Remote subtitle yielded no cues" }
        val first = cues.first { it.text.isNotBlank() }
        val middle = cues[cues.size / 2]
        check(first in SubtitleParser.activeCues(cues, first.startMs))
        check(middle in SubtitleParser.activeCues(cues, middle.startMs))
        check(first !in SubtitleParser.activeCues(cues, first.endMs))
        println("[subtitles] work=${summary.ref.id} format=${subtitle.format} cues=${cues.size} firstMs=${first.startMs} middleMs=${middle.startMs} seekMapping=true endExclusive=true")
        verified = true
        break
    }
    check(verified) { "No paired subtitle found in five reference candidates" }
    println("[subtitles] PASS scope=remote_pairing_decoding_and_cue_selection_only")
}

private fun withProbeClients(block: suspend (AsmrOneClient, Asmr18Client, SourceHttp) -> Unit) {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    try {
        val http = SourceHttp(client)
        runBlocking { block(AsmrOneClient(http, listOf("https://api.asmr.one")), Asmr18Client(http), http) }
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
