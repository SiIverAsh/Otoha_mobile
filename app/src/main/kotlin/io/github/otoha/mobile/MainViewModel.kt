package io.github.otoha.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.BrowseFilters
import io.github.otoha.core.CatalogPage
import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.PlaybackSnapshot
import io.github.otoha.core.SourceClient
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogUi(
    val works: List<WorkSummary> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val request: Request? = null,
) {
    data class Request(val page: Int, val error: String? = null)

    val loading: Boolean get() = request != null && request.error == null

    internal fun canLoad(target: Int): Boolean = !loading && target > 0 &&
        (target == page || target == page - 1 || target == request?.page ||
            (target == page + 1 && (page == 0 || hasMore)))

    internal fun searchStatus(showRestricted: Boolean): String? = when {
        request?.error != null -> "加载失败"
        loading -> if (page == 0) "搜索中" else "翻页中"
        page == 0 -> null
        works.isEmpty() -> "无结果"
        works.any { showRestricted || it.rating == ContentRating.GENERAL } -> "有结果"
        else -> "本页已隐藏"
    }
}

data class SearchUi(
    val keyword: String = "",
    val pages: Map<SourceId, CatalogUi> = emptyMap(),
)

data class MainUi(
    val source: SourceId = SourceId.ASMR_ONE,
    val category: String = "allages",
    val filters: BrowseFilters = BrowseFilters(),
    val facetPicker: FacetPickerUi? = null,
    val catalog: CatalogUi = CatalogUi(),
    val search: SearchUi = SearchUi(),
    val detail: WorkDetail? = null,
    val detailLoading: Boolean = false,
    val message: String? = null,
    val restoring: Boolean = false,
    val clearingCoverCache: Boolean = false,
)

data class FacetPickerUi(
    val source: SourceId,
    val kind: FacetKind,
    val options: List<FacetValue> = emptyList(),
    val discoveredCount: Int = 0,
    val upstreamCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

class MainViewModel internal constructor(
    private val app: OtohaApplication,
    private val sourceFor: (SourceId) -> SourceClient,
) : AndroidViewModel(app) {
    constructor(application: Application) : this(application as OtohaApplication, application::source)

    val store = app.store
    val player = PlayerConnection(app, store)
    private val mutableState = MutableStateFlow(MainUi())
    val state = mutableState.asStateFlow()
    private val catalogs = CatalogPageCache<CatalogKey>(capacity = 12)
    private val searchPages = CatalogPageCache<SourceId>(capacity = 8)
    private val knownWorks = mutableMapOf<String, WorkSummary>()
    private val facetCache = mutableMapOf<Pair<SourceId, FacetKind>, List<FacetValue>>()
    private var facetJob: Job? = null
    private var facetGeneration = 0
    private val browseRequests = CatalogRequestGate()
    private var browseJob: Job? = null
    private val searchJobs = mutableMapOf<SourceId, Job>()
    private var detailJob: Job? = null
    private var detailRef: WorkRef? = null
    private var restoreJob: Job? = null
    private var restoreSource: SourceId? = null
    private var asmrOneRevision = 0
    private var restoreGeneration = 0

    private fun revision(source: SourceId): Int = if (source == SourceId.ASMR_ONE) asmrOneRevision else 0

    init {
        rememberWorks(store.data.value.favorites + store.data.value.recent)
        loadCatalogPage(1)
    }

    private fun selection(): CatalogSelection = CatalogSelection(state.value.source, state.value.category, state.value.filters)

    fun selectSource(source: SourceId) {
        if (source == state.value.source) return
        switchCatalog(CatalogSelection(source))
    }
    fun selectCategory(category: String) {
        if (state.value.source != SourceId.ASMR18) return
        if (category !in listOf("allages", "boys", "girls")) return
        if (category != "allages" && !store.data.value.showRestricted) return
        if (category == state.value.category) return
        switchCatalog(CatalogSelection(SourceId.ASMR18, category))
    }
    private fun switchCatalog(next: CatalogSelection, resetPage: Boolean = false) {
        browseJob?.cancel()
        browseRequests.invalidate()
        closeFacetPicker()
        val key = next.key
        if (resetPage) catalogs.invalidate { it == key }
        mutableState.value = state.value.copy(source = next.source, category = next.category, filters = next.filters,
            catalog = catalogs.latest(key) ?: CatalogUi(), message = null)
        if (state.value.catalog.page == 0) loadCatalogPage(1)
    }
    fun selectFacet(value: FacetValue) {
        val picker = state.value.facetPicker ?: return
        if (value.source != picker.source || value.kind != picker.kind) return
        closeDetail()
        switchCatalog(selection().withFacet(value), resetPage = true)
    }
    /** Browse a detail or player tag using the source that owns the displayed work. */
    internal fun selectDetailFacet(work: WorkSummary, value: FacetValue) {
        val source = work.ref.source
        if (value.source != source) return
        closeDetail()
        switchCatalog(CatalogSelection(source).withFacet(value), resetPage = true)
    }
    fun removeFacet(kind: FacetKind) = switchCatalog(selection().without(kind), resetPage = true)
    fun clearFacets() = switchCatalog(selection().cleared(), resetPage = true)

    /** Drop pagination and retry actions from a catalog that is no longer shown. */
    internal fun loadCatalogPage(renderedKey: CatalogKey, page: Int) {
        if (renderedKey == selection().key) loadCatalogPage(page)
    }

    private fun loadCatalogPage(page: Int) {
        val old = state.value.catalog
        if (!old.canLoad(page)) return
        val requestedSelection = selection()
        val key = requestedSelection.key
        val refresh = page == old.page
        if (!refresh) catalogs.get(key, page)?.let { cached ->
            mutableState.value = state.value.copy(catalog = cached)
            return
        }
        val source = requestedSelection.source
        val requestToken = browseRequests.begin(key, revision(source))
        val client = sourceFor(source)
        mutableState.value = state.value.copy(catalog = old.copy(request = CatalogUi.Request(page)))
        browseJob = viewModelScope.launch {
            val result = fetchPage(old, page) {
                client.filteredBrowse(page, requestedSelection.category, requestedSelection.filters)
            }
            if (!browseRequests.accepts(requestToken, selection().key, revision(source))) return@launch
            if (result.request == null) {
                if (refresh) catalogs.invalidate { it == key }
                catalogs.put(key, result)
                rememberWorks(result.works)
            }
            mutableState.value = state.value.copy(catalog = result)
        }
    }

    fun search(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        searchJobs.values.forEach { it.cancel() }
        searchPages.clear()
        mutableState.value = state.value.copy(search = SearchUi(keyword))
        SourceId.entries.forEach { loadSearchPage(it, 1) }
    }

    fun loadSearchPage(source: SourceId, page: Int) {
        val search = state.value.search
        val old = search.pages[source] ?: CatalogUi()
        if (search.keyword.isEmpty() || !old.canLoad(page)) return
        searchPages.get(source, page)?.let { cached ->
            updateSearchPage(source, cached)
            return
        }
        val client = sourceFor(source)
        updateSearchPage(source, old.copy(request = CatalogUi.Request(page)))
        searchJobs[source] = viewModelScope.launch {
            val result = fetchPage(old, page) { client.search(search.keyword, page) }
            // The previous query or mirror can be cancelled while the network response is arriving.
            currentCoroutineContext().ensureActive()
            if (result.request == null) {
                searchPages.put(source, result)
                rememberWorks(result.works)
            }
            updateSearchPage(source, result)
        }
    }

    private fun updateSearchPage(source: SourceId, page: CatalogUi) {
        val current = state.value
        mutableState.value = current.copy(search = current.search.copy(pages = current.search.pages + (source to page)))
    }

    private suspend fun fetchPage(old: CatalogUi, page: Int, fetch: suspend () -> CatalogPage): CatalogUi =
        try {
            val result = fetch()
            check(result.page == page) { "音源返回的页码有误，请重试" }
            CatalogUi(result.works.distinctBy { it.ref.key }, page, result.hasMore)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            old.copy(request = CatalogUi.Request(page, error.message ?: "加载失败，请重试"))
        }
    private fun rememberWorks(works: List<WorkSummary>) {
        works.forEach { incoming ->
            knownWorks[incoming.ref.key] = FacetDiscovery.merge(knownWorks[incoming.ref.key], incoming)
        }
        state.value.facetPicker?.let { picker ->
            mutableState.value = state.value.copy(facetPicker = pickerState(picker.source, picker.kind,
                picker.loading, picker.error))
        }
    }
    private fun discovered(source: SourceId, kind: FacetKind): List<FacetValue> =
        knownWorks.values.filter { it.ref.source == source && visible(it) }.flatMap { it.facets }
            .filter { it.source == source && it.kind == kind && it.queryValue.isNotBlank() }.distinctBy { it.key }

    private fun pickerState(source: SourceId, kind: FacetKind, loading: Boolean, error: String? = null): FacetPickerUi {
        val found = discovered(source, kind)
        val remote = facetCache[source to kind].orEmpty()
        val selected = state.value.filters.values.filter { it.source == source && it.kind == kind }
        return FacetPickerUi(source, kind, (remote + found + selected).distinctBy { it.key }
            .sortedBy { it.name.lowercase() }, found.size, remote.size, loading, error)
    }
    internal fun openFacetPicker(source: SourceId, kind: FacetKind, reload: Boolean = false) {
        if (state.value.source != source) return
        if (reload && state.value.facetPicker?.kind != kind) return
        facetJob?.cancel()
        val generation = ++facetGeneration
        val requestRevision = revision(source)
        val cached = facetCache.containsKey(source to kind)
        mutableState.value = state.value.copy(facetPicker = pickerState(source, kind, !cached || reload))
        if (cached && !reload) return
        val client = sourceFor(source)
        facetJob = viewModelScope.launch {
            try {
                val options = client.facetOptions(kind).filter {
                    it.source == source && it.kind == kind && it.queryValue.isNotBlank()
                }
                if (generation != facetGeneration || revision(source) != requestRevision || state.value.source != source) return@launch
                facetCache[source to kind] = options
                mutableState.value = state.value.copy(facetPicker = pickerState(source, kind, false))
            } catch (cancel: CancellationException) { throw cancel
            } catch (error: Exception) {
                if (generation == facetGeneration && revision(source) == requestRevision && state.value.source == source)
                    mutableState.value = state.value.copy(facetPicker = pickerState(source, kind, false,
                        error.message ?: "暂时无法加载站点选项"))
            }
        }
    }
    fun closeFacetPicker() {
        facetGeneration++
        facetJob?.cancel()
        mutableState.value = state.value.copy(facetPicker = null)
    }
    fun open(work: WorkSummary) {
        if (!visible(work)) { message("请先在设置中启用分级内容显示"); return }
        open(work.ref, knownWork = work)
    }
    private fun open(ref: WorkRef, knownWork: WorkSummary? = null) {
        if (detailRef?.key == ref.key && (state.value.detailLoading || state.value.detail != null)) return
        detailJob?.cancel()
        detailRef = ref
        val requestRevision = revision(ref.source)
        val client = sourceFor(ref.source)
        mutableState.value = state.value.copy(detail = null, detailLoading = true)
        detailJob = viewModelScope.launch {
            try {
                val received = client.detail(ref)
                currentCoroutineContext().ensureActive()
                if (revision(ref.source) != requestRevision) return@launch
                check(received.summary.ref.source == ref.source) { "作品音源不匹配" }
                val result = received.withKnownCover(knownWork)
                rememberWorks(listOf(result.summary))
                if (!visible(result.summary)) {
                    mutableState.value = state.value.copy(detailLoading = false)
                    message("此作品的分级信息不在当前显示范围内")
                } else mutableState.value = state.value.copy(detail = result, detailLoading = false)
            } catch (cancel: CancellationException) { throw cancel
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (revision(ref.source) == requestRevision)
                    mutableState.value = state.value.copy(detailLoading = false,
                        message = error.message ?: "作品加载失败，请重试")
            }
        }
    }
    fun openLink(raw: String) {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
        val path = uri?.path?.lowercase().orEmpty()
        if (uri?.scheme != "https" || uri.host != "asmr18.fans" || uri.userInfo != null || uri.port != -1 ||
            !Regex("^/(allages|boys|girls)/rj(?:[0-9]{6}|[0-9]{8})/$").matches(path)) {
            message("请输入 https://asmr18.fans/分类/rj作品号/ 格式的作品链接"); return
        }
        if (!path.startsWith("/allages/") && !store.data.value.showRestricted) {
            message("请先在设置中启用分级内容显示"); return
        }
        open(WorkRef(SourceId.ASMR18, path.trim('/'), "https://asmr18.fans$path"))
    }
    fun closeDetail() {
        detailJob?.cancel()
        detailRef = null
        mutableState.value = state.value.copy(detail = null, detailLoading = false)
    }
    fun play(detail: WorkDetail, track: AudioTrack) {
        if (visible(detail.summary)) {
            restoreGeneration++
            restoreJob?.cancel()
            restoreSource = null
            mutableState.value = state.value.copy(restoring = false)
            player.play(detail, track)
        }
    }
    fun setRestricted(enabled: Boolean) {
        store.setRestricted(enabled)
        if (!enabled) {
            if (state.value.detail?.summary?.let { !visible(it) } == true) closeDetail()
            if (player.state.value.work?.let { !visible(it) } == true) player.clear()
            if (state.value.category != "allages") switchCatalog(CatalogSelection(state.value.source, "allages", state.value.filters))
        }
    }
    fun restore(snapshot: PlaybackSnapshot) = restore(snapshot, null)

    private fun restore(snapshot: PlaybackSnapshot, retry: ManualPlaybackRetry?) {
        if (!visible(snapshot.work) || state.value.restoring || !player.state.value.connected) return
        mutableState.value = state.value.copy(restoring = true)
        val requestRevision = revision(snapshot.work.ref.source)
        val client = sourceFor(snapshot.work.ref.source)
        val generation = ++restoreGeneration
        restoreSource = snapshot.work.ref.source
        restoreJob = viewModelScope.launch {
            try {
                val refreshed = client.detail(snapshot.work.ref)
                if (revision(snapshot.work.ref.source) != requestRevision) return@launch
                val current = if (retry == null) snapshot else player.currentRetrySnapshot(retry) ?: return@launch
                if (!visible(refreshed.summary)) error("此作品不在当前分级显示范围内")
                val playback = refreshedPlayback(current, refreshed)
                player.play(playback.work, playback.tracks, playback.index, playback.positionMs, playback.speed,
                    continuationToken = retry?.token, isContinuation = retry != null)
            } catch (cancel: CancellationException) { throw cancel
            } catch (error: Exception) {
                if (revision(snapshot.work.ref.source) == requestRevision &&
                    (retry == null || player.currentRetrySnapshot(retry) != null)) {
                    message(error.message ?: "恢复播放失败，请打开作品重试")
                }
            } finally {
                if (generation == restoreGeneration) {
                    restoreSource = null
                    mutableState.value = state.value.copy(restoring = false)
                }
            }
        }
    }
    fun retryPlayback() {
        val retry = player.captureRetry() ?: return
        restore(retry.snapshot, retry)
    }
    fun setAsmrOneMirror(raw: String) {
        try {
            val normalized = MirrorSettings.normalize(raw)
            if (normalized == store.data.value.asmrOneApiBaseUrl) return
            store.setAsmrOneApiBaseUrl(normalized)
            asmrOneRevision++
            catalogs.invalidate { it.source == SourceId.ASMR_ONE }
            searchPages.invalidate { it == SourceId.ASMR_ONE }
            facetCache.keys.filter { it.first == SourceId.ASMR_ONE }.toList().forEach { facetCache.remove(it) }
            if (state.value.facetPicker?.source == SourceId.ASMR_ONE) closeFacetPicker()
            if (detailRef?.source == SourceId.ASMR_ONE) closeDetail()
            if (restoreSource == SourceId.ASMR_ONE) {
                restoreGeneration++
                restoreJob?.cancel()
                restoreSource = null
                mutableState.value = state.value.copy(restoring = false)
            }
            if (state.value.source == SourceId.ASMR_ONE) {
                browseRequests.invalidate()
                browseJob?.cancel()
                mutableState.value = state.value.copy(catalog = CatalogUi())
                loadCatalogPage(1)
            }
            if (state.value.search.keyword.isNotEmpty()) {
                searchJobs[SourceId.ASMR_ONE]?.cancel()
                updateSearchPage(SourceId.ASMR_ONE, CatalogUi())
                loadSearchPage(SourceId.ASMR_ONE, 1)
            }
            message("ASMR.one 镜像设置已应用")
        } catch (error: Exception) { message(error.message ?: "无法保存镜像设置") }
    }
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCoverCache() {
        if (state.value.clearingCoverCache) return
        mutableState.value = state.value.copy(clearingCoverCache = true)
        viewModelScope.launch {
            try {
                val loader = app.imageLoader
                loader.memoryCache?.clear()
                withContext(Dispatchers.IO) { loader.diskCache?.clear() }
                message("封面缓存已清理")
            } catch (cancel: CancellationException) { throw cancel
            } catch (error: Exception) { message("清理失败：${error.message ?: "请稍后重试"}")
            } finally { mutableState.value = state.value.copy(clearingCoverCache = false) }
        }
    }
    fun visible(work: WorkSummary): Boolean = store.data.value.showRestricted || work.rating == ContentRating.GENERAL
    fun message(text: String?) { mutableState.value = state.value.copy(message = text) }
    override fun onCleared() { player.close(); super.onCleared() }
}

/** Retains the selected work's cover when its detail response omits it. */
internal fun WorkDetail.withKnownCover(knownWork: WorkSummary?): WorkDetail {
    if (summary.coverUrl.isNotBlank() || knownWork == null) return this
    if (knownWork.ref.key != summary.ref.key || knownWork.coverUrl.isBlank()) return this
    return copy(summary = summary.copy(coverUrl = knownWork.coverUrl))
}
