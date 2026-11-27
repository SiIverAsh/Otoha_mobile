package io.github.otoha.mobile

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.BrowseFilters
import io.github.otoha.core.CatalogPage
import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.SourceClient
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainViewModelSourceTest {
    private val one = TestSource(SourceId.ASMR_ONE, "123456", "https://one.test/audio.mp3")
    private val eighteen = TestSource(SourceId.ASMR18, "allages/rj123456", "https://eighteen.test/audio.m3u8")
    private val modelStore = ViewModelStore()
    private val viewModel by lazy {
        val application = checkNotNull(RuntimeEnvironment.getApplication() as? OtohaApplication)
        val sources = listOf(one, eighteen).associateBy { it.source }
        val factory = viewModelFactory {
            initializer { MainViewModel(application, sources::getValue) }
        }
        ViewModelProvider.create(modelStore, factory)[MainViewModel::class]
    }

    @After
    fun clearViewModel() {
        modelStore.clear()
    }

    @Test
    fun asmr18PickerResultCannotChangeTheAsmrOneCatalogAfterSwitching() {
        viewModel.selectSource(SourceId.ASMR18)
        viewModel.openFacetPicker(SourceId.ASMR18, FacetKind.TAG)
        val selected = requireNotNull(viewModel.state.value.facetPicker).options.single()
        viewModel.selectSource(SourceId.ASMR_ONE)

        viewModel.selectFacet(selected)

        assertEquals(SourceId.ASMR_ONE, viewModel.state.value.source)
        assertEquals(listOf(one.work), viewModel.state.value.catalog.works)
        assertEquals(emptyList<BrowseFilters>(), eighteen.filteredRequests)
    }

    @Test
    fun workTagsFollowTheirWorkAcrossBothSourceTabs() {
        viewModel.selectSource(SourceId.ASMR18)
        viewModel.selectDetailFacet(one.work, one.tag)
        assertEquals(SourceId.ASMR_ONE, viewModel.state.value.source)
        assertEquals(listOf(one.work), viewModel.state.value.catalog.works)

        viewModel.selectDetailFacet(eighteen.work, eighteen.tag)
        assertEquals(SourceId.ASMR18, viewModel.state.value.source)
        assertEquals(listOf(eighteen.work), viewModel.state.value.catalog.works)
        assertEquals(listOf(BrowseFilters(listOf(one.tag))), one.filteredRequests)
        assertEquals(listOf(BrowseFilters(listOf(eighteen.tag))), eighteen.filteredRequests)
    }

    @Test
    fun thePreviousCatalogsNextPageCannotLoadOnTheNewSource() {
        val oneKey = CatalogSelection(SourceId.ASMR_ONE).key
        val eighteenKey = CatalogSelection(SourceId.ASMR18).key
        viewModel.selectSource(SourceId.ASMR18)

        viewModel.loadCatalogPage(oneKey, 2)
        assertEquals(listOf(1), eighteen.pagesRequested)
        assertEquals(1, viewModel.state.value.catalog.page)

        viewModel.loadCatalogPage(eighteenKey, 2)
        assertEquals(listOf(1, 2), eighteen.pagesRequested)
        assertEquals(2, viewModel.state.value.catalog.page)
    }

    @Test
    fun detailLoadsAudioFromTheWorksSourceEvenWhenTheOtherTabIsSelected() {
        viewModel.selectSource(SourceId.ASMR18)
        viewModel.open(one.work)
        assertEquals(one.detail, viewModel.state.value.detail)

        viewModel.selectSource(SourceId.ASMR_ONE)
        viewModel.open(eighteen.work)
        assertEquals(eighteen.detail, viewModel.state.value.detail)
        assertEquals(listOf(one.work.ref), one.detailRequests)
        assertEquals(listOf(eighteen.work.ref), eighteen.detailRequests)
    }

    @Test
    fun cancelledDetailResponseCannotReplaceTheOtherSourcesAudio() {
        var pending: Continuation<WorkDetail>? = null
        one.loadDetail = { suspendCoroutine { pending = it } }

        viewModel.open(one.work)
        viewModel.selectSource(SourceId.ASMR18)
        viewModel.open(eighteen.work)
        requireNotNull(pending).resume(one.detail)

        assertEquals(eighteen.detail, viewModel.state.value.detail)
    }

    private class TestSource(override val source: SourceId, id: String, audioUrl: String) : SourceClient {
        val work = WorkSummary(WorkRef(source, id), "Work", workNumber = "RJ123456", rating = ContentRating.GENERAL)
        val detail = WorkDetail(work, listOf(AudioTrack("audio", "Track", audioUrl)))
        val tag = FacetValue(source, FacetKind.TAG, "1", "Tag")
        val pagesRequested = mutableListOf<Int>()
        val filteredRequests = mutableListOf<BrowseFilters>()
        val detailRequests = mutableListOf<WorkRef>()
        var loadDetail: suspend () -> WorkDetail = { detail }

        override suspend fun browse(page: Int, category: String): CatalogPage {
            pagesRequested += page
            return CatalogPage(listOf(work), page, hasMore = page == 1)
        }

        override suspend fun filteredBrowse(page: Int, category: String, filters: BrowseFilters): CatalogPage {
            if (!filters.isEmpty) filteredRequests += filters
            return browse(page, category)
        }

        override suspend fun search(keyword: String, page: Int): CatalogPage = browse(page)

        override suspend fun facetOptions(kind: FacetKind): List<FacetValue> = listOf(tag.copy(kind = kind))

        override suspend fun detail(ref: WorkRef): WorkDetail {
            detailRequests += ref
            return loadDetail()
        }
    }
}
