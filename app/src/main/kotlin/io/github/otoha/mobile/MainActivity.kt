package io.github.otoha.mobile

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.AsmrOneClient
import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkSummary
import io.github.otoha.core.requestHeaders
import kotlinx.coroutines.launch

private const val PANEL_DURATION_MS = 280
private const val PAGE_DURATION_MS = 220
private const val PAGE_INITIAL_ALPHA = 0.65f
private const val PLAYER_SHEET_HEIGHT_FRACTION = 0.9f
private const val WORK_GRID_COLUMNS = 2
private const val WORK_COVER_ASPECT_RATIO = 4f / 3f
private val HeaderCoverWidth = 112.dp
private val PanelSizeSpec = tween<IntSize>(PANEL_DURATION_MS, easing = FastOutSlowInEasing)
private val PanelFloatSpec = tween<Float>(PANEL_DURATION_MS, easing = FastOutSlowInEasing)
private val PageEntranceSpec = tween<Float>(PAGE_DURATION_MS, easing = FastOutSlowInEasing)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val reports = (application as OtohaApplication).crashReports
        val previousReport = reports.readLatest()
        setContent {
            var crashReport by remember { mutableStateOf(previousReport) }
            val preferences by (application as OtohaApplication).store.data.collectAsStateWithLifecycle()
            OtohaTheme(preferences.themeMode) {
                val report = crashReport
                if (report != null) CrashRecoveryScreen(report) {
                    reports.clear().also { cleared -> if (cleared) crashReport = null }
                } else OtohaApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtohaApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val personal by vm.store.data.collectAsStateWithLifecycle()
    val incognito by vm.store.incognito.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val sleepDeadline by vm.store.sleepDeadline.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var linkOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchDraft by rememberSaveable { mutableStateOf("") }
    var searchSource by rememberSaveable { mutableStateOf(SourceId.ASMR_ONE) }
    // Keep grid positions alive while the detail screen replaces the grid.
    val catalogGridState = key(state.source, state.category, state.filters, state.catalog.page) {
        rememberLazyGridState()
    }
    val searchGridStates = SourceId.entries.associateWith { source ->
        key(source, state.search.keyword, state.search.pages[source]?.page) { rememberLazyGridState() }
    }
    val favoritesGridState = rememberLazyGridState()
    val recentGridState = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    val detail = state.detail
    val inDetail = detail != null || state.detailLoading
    val active = playback.work?.takeIf(vm::visible)
    val pageMotion = remember(inDetail, searchOpen, tab) { Animatable(0f) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.message(null) }
    }
    LaunchedEffect(pageMotion) { pageMotion.animateTo(1f, PageEntranceSpec) }
    BackHandler(searchOpen && !inDetail) { searchOpen = false }
    BackHandler(inDetail) { vm.closeDetail() }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(title = {
                Column {
                    Text(when {
                        inDetail -> "作品详情"
                        searchOpen -> "全局搜索"
                        else -> "Otoha"
                    }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!inDetail && !searchOpen) Text(if (incognito) "无痕聆听中" else "为安静的片刻", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }, navigationIcon = {
                if (inDetail || searchOpen) {
                    OtohaTextButton(onClick = { if (inDetail) vm.closeDetail() else searchOpen = false }, label = "‹ 返回")
                }
            }, actions = {
                if (!inDetail && !searchOpen) {
                    OtohaTextButton(onClick = { searchOpen = true }, label = "全局搜索")
                    if (tab == 0) OtohaTextButton(onClick = { linkOpen = true }, label = "打开链接")
                }
            },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            if (!playback.connected && playback.error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("播放器连接失败", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                OtohaTextButton(onClick = vm.player::retryConnection, label = "重新连接")
            }
            }
        },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = active != null,
                    enter = expandVertically(PanelSizeSpec, expandFrom = Alignment.Bottom) +
                        fadeIn(PanelFloatSpec),
                    // Hide a newly restricted work immediately.
                    exit = ExitTransition.None,
                ) {
                    if (active != null) {
                        MiniPlayer(
                            playback,
                            onOpenDetail = { vm.open(active) },
                            onOpenPlayer = { playerOpen = true },
                            onToggle = vm.player::toggle,
                        )
                    }
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                    listOf("发现" to "◉", "收藏" to "♡", "最近" to "◷", "设置" to "☷").forEachIndexed { index, item ->
                        val press = rememberButtonPress()
                        NavigationBarItem(selected = tab == index && !inDetail && !searchOpen,
                            onClick = { vm.closeDetail(); searchOpen = false; tab = index },
                            modifier = press.modifier,
                            interactionSource = press.interactionSource,
                            icon = { Text(item.second) }, label = { ButtonLabel(item.first, maxLines = 1) })
                    }
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).clipToBounds().graphicsLayer {
                val progress = pageMotion.value
                alpha = PAGE_INITIAL_ALPHA + (1f - PAGE_INITIAL_ALPHA) * progress
                translationY = 8.dp.toPx() * (1f - progress)
            },
        ) {
            when {
                state.detailLoading -> Loading("正在读取作品与曲目…")
                detail != null -> DetailScreen(detail,
                    favorite = personal.favorites.any { it.ref.key == detail.summary.ref.key },
                    connected = playback.connected,
                    onFavorite = { vm.store.toggleFavorite(detail.summary) },
                    onFacet = { vm.selectDetailFacet(it); searchOpen = false; tab = 0 },
                    onPlay = { vm.play(detail, it); playerOpen = true })
                searchOpen -> SearchScreen(state.search, searchGridStates.getValue(searchSource), personal.showRestricted, searchDraft,
                    { searchDraft = it }, vm::search, searchSource, { searchSource = it },
                    { vm.loadSearchPage(searchSource, it) }, vm::open, { searchOpen = false; tab = 3 })
                tab == 0 -> CatalogScreen(state, catalogGridState, personal.showRestricted, vm::selectSource, vm::selectCategory,
                    vm::open, vm::loadCatalogPage,
                    { vm.openFacetPicker(it) }, vm::removeFacet, vm::clearFacets, { tab = 3 })
                tab == 1 -> LocalWorks("收藏", personal.favorites.filter(vm::visible), "把喜欢的作品留在这里",
                    favoritesGridState, vm::open)
                tab == 2 -> LocalWorks("最近收听", personal.recent.filter(vm::visible), "开始播放后会在这里留下记录",
                    recentGridState, vm::open,
                    leading = {
                        personal.snapshot?.takeIf { vm.visible(it.work) }?.let { snapshot ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            ) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("接着听", style = MaterialTheme.typography.titleMedium)
                                    Text(snapshot.work.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${snapshot.tracks.getOrNull(snapshot.index)?.title.orEmpty()} · ${time(snapshot.positionMs)}",
                                        style = MaterialTheme.typography.bodySmall)
                                    OtohaButton(
                                        enabled = playback.connected && !state.restoring,
                                        onClick = { vm.restore(snapshot); playerOpen = true },
                                        label = if (state.restoring) "正在刷新播放地址…" else "恢复播放",
                                    )
                                }
                            }
                        }
                    })
                else -> SettingsScreen(personal.showRestricted, incognito, vm::setRestricted, vm.store::setIncognito,
                    onClear = vm.store::clearHistory, currentMirror = personal.asmrOneApiBaseUrl,
                    onMirror = vm::setAsmrOneMirror, clearingCoverCache = state.clearingCoverCache,
                    onClearCoverCache = vm::clearCoverCache, themeMode = personal.themeMode,
                    onTheme = vm.store::setThemeMode)
            }
        }
    }
    if (playerOpen && active != null) {
        ModalBottomSheet(
            onDismissRequest = { playerOpen = false },
            // Keep the sheet anchor stable as the queue and chapters expand.
            modifier = Modifier.fillMaxHeight(PLAYER_SHEET_HEIGHT_FRACTION),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PlayerScreen(playback, active, vm.player, incognito, sleepDeadline, vm.store::sleepAfter,
                state.restoring, vm::retryPlayback, Modifier.padding(horizontal = 24.dp))
        }
    }
    if (linkOpen) LinkDialog(onDismiss = { linkOpen = false }, onOpen = { vm.openLink(it); linkOpen = false })
    state.facetPicker?.let { picker ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        var isClosing by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = vm::closeFacetPicker, sheetState = sheetState) {
            FacetPickerScreen(
                picker = picker,
                selected = state.filters,
                onSelect = { facet ->
                    if (!isClosing) {
                        isClosing = true
                        scope.launch {
                            try {
                                sheetState.hide()
                                if (!sheetState.isVisible) {
                                    vm.selectFacet(facet)
                                    tab = 0
                                }
                            } finally {
                                isClosing = false
                            }
                        }
                    }
                },
                onReload = { if (!isClosing) vm.openFacetPicker(picker.kind, reload = true) },
            )
        }
    }
}

@Composable
private fun CatalogScreen(
    state: MainUi, gridState: LazyGridState, restricted: Boolean,
    onSource: (SourceId) -> Unit, onCategory: (String) -> Unit,
    onOpen: (WorkSummary) -> Unit,
    onPage: (Int) -> Unit,
    onPickFacet: (FacetKind) -> Unit, onRemoveFacet: (FacetKind) -> Unit, onClearFacets: () -> Unit,
    onDisplaySettings: () -> Unit,
) {
    PagedWorkList(state.catalog, gridState, restricted, onOpen, onPage, onDisplaySettings,
        if (state.filters.isEmpty) "音源暂未返回作品" else "音源没有返回符合当前条件的作品",
        state.source.other(), onSource) {
        fullWidthItem {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("发现好声音", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("按需浏览，选择喜欢的版本开始聆听。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
        fullWidthItem {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceId.entries.forEach { source ->
                        OtohaFilterChip(
                            selected = state.source == source,
                            onClick = { onSource(source) },
                            label = source.displayName(),
                        )
                    }
                }
                OtohaTextButton(
                    onClick = { onPage(state.catalog.page.coerceAtLeast(1)) },
                    label = "刷新",
                    enabled = !state.catalog.loading,
                )
            }
        }
        fullWidthItem {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FacetKind.entries.forEach { kind ->
                    OtohaFilterChip(
                        selected = state.filters.values.any { it.kind == kind },
                        onClick = { onPickFacet(kind) },
                        label = kind.title(),
                    )
                }
            }
        }
        if (!state.filters.isEmpty) fullWidthItem {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.filters.values.forEach { value ->
                        val press = rememberButtonPress()
                        InputChip(
                            selected = true,
                            onClick = { onRemoveFacet(value.kind) },
                            modifier = Modifier.widthIn(max = 240.dp).then(press.modifier),
                            label = { ButtonLabel("${value.kind.title()}：${value.name}", maxLines = 1) },
                            trailingIcon = { Text("×") },
                            interactionSource = press.interactionSource,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.source == SourceId.ASMR18) "站点分类结果 · 新条件将替换当前条件" else "远端筛选结果 · 条件同时匹配",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    OtohaTextButton(onClick = onClearFacets, label = "清空筛选")
                }
            }
        }
        if (state.source == SourceId.ASMR18 && state.filters.isEmpty) fullWidthItem {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val categories = if (restricted) listOf("allages" to "全年龄", "boys" to "男性向", "girls" to "女性向")
                    else listOf("allages" to "全年龄")
                categories.forEach { (id, title) ->
                    OtohaFilterChip(selected = state.category == id, onClick = { onCategory(id) }, label = title)
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    search: SearchUi, gridState: LazyGridState, restricted: Boolean, draft: String, onDraft: (String) -> Unit,
    onSearch: (String) -> Unit, source: SourceId, onSource: (SourceId) -> Unit,
    onPage: (Int) -> Unit, onOpen: (WorkSummary) -> Unit, onDisplaySettings: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val submit = {
        if (draft.isNotBlank()) {
            focus.clearFocus()
            onSearch(draft)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("作品关键词") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }))
                OtohaButton(onClick = submit, label = "搜索", enabled = draft.isNotBlank())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceId.entries.forEach { option ->
                    val status = search.pages[option]?.searchStatus(restricted)
                    val press = rememberButtonPress()
                    FilterChip(
                        selected = source == option,
                        onClick = { onSource(option) },
                        modifier = Modifier.weight(1f).then(press.modifier),
                        interactionSource = press.interactionSource,
                        label = {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                ButtonLabel(option.displayName(), maxLines = 1)
                                status?.let {
                                    Text(it, Modifier.clipToBounds(), style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                    )
                }
            }
            if (search.keyword.isNotEmpty()) {
                Text("“${search.keyword}”的搜索结果", style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (search.keyword.isEmpty()) {
            Box(Modifier.padding(horizontal = 20.dp)) { EmptyState("输入关键词，同时搜索两个音源。") }
        } else {
            PagedWorkList(search.pages[source] ?: CatalogUi(), gridState, restricted, onOpen, onPage,
                onDisplaySettings, "此音源未找到相关作品", source.other(), onSource)
        }
    }
}

@Composable
private fun PagedWorkList(
    catalog: CatalogUi, gridState: LazyGridState, restricted: Boolean,
    onOpen: (WorkSummary) -> Unit, onPage: (Int) -> Unit,
    onDisplaySettings: () -> Unit, emptyMessage: String,
    otherSource: SourceId, onSource: (SourceId) -> Unit, header: LazyGridScope.() -> Unit = {},
) {
    val visibility = CatalogVisibility.from(catalog.works, restricted)
    Column(Modifier.fillMaxSize()) {
        WorkGrid(gridState, Modifier.weight(1f)) {
            header()
            if (visibility.visible.isNotEmpty() && visibility.hiddenCount > 0) fullWidthItem {
                Column {
                    Text("本页显示 ${visibility.visible.size} 件 · 按分级设置隐藏 ${visibility.hiddenCount} 件",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    OtohaTextButton(onClick = onDisplaySettings, label = "调整分级显示设置")
                }
            }
            items(visibility.visible, key = { it.ref.key }) { work -> WorkCard(work) { onOpen(work) } }
            if (catalog.page > 0 && visibility.visible.isEmpty()) fullWidthItem {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val hidden = visibility.hiddenCount
                    Text(if (hidden > 0) "本页没有可显示的作品" else emptyMessage,
                        style = MaterialTheme.typography.titleMedium)
                    if (hidden > 0) {
                        Text("分级设置隐藏了本页的 $hidden 件作品。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (catalog.hasMore) {
                        OtohaButton(
                            onClick = { onPage(catalog.page + 1) },
                            label = "查看下一页",
                            enabled = catalog.canLoad(catalog.page + 1),
                        )
                    }
                    OtohaTextButton(onClick = { onSource(otherSource) }, label = "查看 ${otherSource.displayName()}")
                    if (hidden > 0) OtohaTextButton(onClick = onDisplaySettings, label = "调整分级显示设置")
                }
            }
            if (catalog.page == 0) catalog.request?.let { request ->
                fullWidthItem { PageLoadStatus(request, onPage) }
            }
        }
        if (catalog.page > 0) {
            catalog.request?.let { PageLoadStatus(it, onPage) }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    OtohaTextButton(
                        onClick = { onPage(catalog.page - 1) },
                        label = "上一页",
                        enabled = catalog.canLoad(catalog.page - 1),
                    )
                }
                Text(
                    "第 ${catalog.page} 页",
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    OtohaTextButton(
                        onClick = { onPage(catalog.page + 1) },
                        label = "下一页",
                        enabled = catalog.canLoad(catalog.page + 1),
                    )
                }
            }
        }
    }
}

private fun SourceId.other(): SourceId = when (this) {
    SourceId.ASMR_ONE -> SourceId.ASMR18
    SourceId.ASMR18 -> SourceId.ASMR_ONE
}

private fun SourceId.displayName(): String = when (this) {
    SourceId.ASMR_ONE -> "ASMR.one"
    SourceId.ASMR18 -> "ASMR18"
}

@Composable
private fun PageLoadStatus(request: CatalogUi.Request, onPage: (Int) -> Unit) {
    if (request.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else ErrorCard("第 ${request.page} 页加载失败 · ${request.error}") { onPage(request.page) }
}

@Composable
private fun WorkCard(work: WorkSummary, onClick: () -> Unit) {
    val press = rememberButtonPress()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(press.modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
        interactionSource = press.interactionSource,
    ) {
        Cover(work, Modifier.fillMaxWidth().aspectRatio(WORK_COVER_ASPECT_RATIO))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                work.title,
                style = MaterialTheme.typography.titleSmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val credit = listOf(work.workNumber, work.circle).filter(String::isNotBlank).joinToString(" · ")
            if (credit.isNotEmpty()) {
                Text(
                    credit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (work.actors.isNotEmpty()) {
                Text(
                    work.actors.joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Cover(work: WorkSummary, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "♪",
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (work.coverUrl.isNotBlank()) {
            val context = LocalContext.current
            val request = remember(context, work.ref.source, work.coverUrl) {
                ImageRequest.Builder(context)
                    .data(work.coverUrl)
                    .apply { work.ref.source.requestHeaders().forEach { (name, value) -> addHeader(name, value) } }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

/** Pairs the work's cover with the title and metadata in detail headers. */
@Composable
internal fun WorkCoverHeader(work: WorkSummary, content: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(
            work = work,
            modifier = Modifier.width(HeaderCoverWidth).aspectRatio(WORK_COVER_ASPECT_RATIO).semantics {
                role = Role.Image
                contentDescription = if (work.coverUrl.isBlank()) "暂无作品封面" else "作品封面"
            },
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun DetailScreen(detail: WorkDetail, favorite: Boolean, connected: Boolean,
    onFavorite: () -> Unit, onFacet: (FacetValue) -> Unit, onPlay: (AudioTrack) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            WorkCoverHeader(detail.summary) {
                Text(detail.summary.workNumber, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(detail.summary.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                val circle = detail.summary.facets.firstOrNull { it.kind == FacetKind.CIRCLE }
                if (circle != null) OtohaAssistChip(onClick = { onFacet(circle) }, label = circle.name)
                else Text(detail.summary.circle, style = MaterialTheme.typography.titleSmall)
                OtohaOutlinedButton(onClick = onFavorite, label = if (favorite) "♥ 已收藏" else "♡ 收藏")
            }
        }
        item {
            val actorFacets = detail.summary.facets.filter { it.kind == FacetKind.ACTOR }
            val tagFacets = detail.summary.facets.filter { it.kind == FacetKind.TAG }
            if (actorFacets.isNotEmpty()) DetailFacetChips(actorFacets, onFacet)
            else if (detail.summary.actors.isNotEmpty()) Text(detail.summary.actors.joinToString(" / "), Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            if (tagFacets.isNotEmpty()) DetailFacetChips(tagFacets, onFacet)
            else if (detail.summary.tags.isNotEmpty()) Text(detail.summary.tags.joinToString(" · "), Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("选择版本与曲目", style = MaterialTheme.typography.titleMedium)
            Text("每次只播放所选目录中的曲目", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
        detail.tracks.groupBy { it.directory }.forEach { (directory, tracks) ->
            item(key = "folder:$directory") {
                Text(directory.joinToString(" / ").ifBlank { "作品音轨" },
                    Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            items(tracks, key = { "track:${it.id}" }) { track ->
                val press = rememberButtonPress()
                Card(
                    onClick = { onPlay(track) },
                    modifier = press.modifier,
                    enabled = connected,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    interactionSource = press.interactionSource,
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("▷", Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(track.title, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(track.durationMs.takeIf { it > 0 }?.let(::time),
                                if (track.isHls) "HLS" else null,
                                track.chapters.takeIf { it.isNotEmpty() }?.let { "${it.size} 个章节" })
                                .joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        if (detail.tracks.isEmpty()) item { EmptyState("此作品暂无可播放的音轨") }
    }
}

@Composable
private fun DetailFacetChips(values: List<FacetValue>, onFacet: (FacetValue) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            OtohaAssistChip(onClick = { onFacet(value) }, label = value.name, modifier = Modifier.widthIn(max = 240.dp))
        }
    }
}

@Composable
private fun LocalWorks(title: String, works: List<WorkSummary>, empty: String,
    gridState: LazyGridState, onOpen: (WorkSummary) -> Unit,
    leading: (@Composable () -> Unit)? = null) {
    WorkGrid(gridState) {
        fullWidthItem { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        if (leading != null) fullWidthItem { leading() }
        items(works, key = { it.ref.key }) { work -> WorkCard(work) { onOpen(work) } }
        if (works.isEmpty()) fullWidthItem { EmptyState(empty) }
    }
}

/** Places work cards in two equally wide columns. */
@Composable
internal fun WorkGrid(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(WORK_GRID_COLUMNS),
        modifier = modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Keeps headings and status rows above or below the work cards. */
internal fun LazyGridScope.fullWidthItem(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SettingsScreen(restricted: Boolean, incognito: Boolean, onRestricted: (Boolean) -> Unit,
    onIncognito: (Boolean) -> Unit, onClear: () -> Unit, currentMirror: String,
    onMirror: (String) -> Unit, clearingCoverCache: Boolean, onClearCoverCache: () -> Unit,
    themeMode: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    var clearOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }
    var mirrorDraft by rememberSaveable(currentMirror) { mutableStateOf(currentMirror) }
    val mirrorProblem = runCatching { MirrorSettings.normalize(mirrorDraft) }.exceptionOrNull()?.message
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("聆听偏好", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("外观", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (mode, title) ->
                    OtohaFilterChip(selected = themeMode == mode, onClick = { onTheme(mode) }, label = title)
                }
            }
        }
        HorizontalDivider()
        SettingSwitch("显示受限和未分级作品", "同时作用于浏览、收藏、最近收听与恢复入口", restricted, onRestricted)
        HorizontalDivider()
        SettingSwitch("无痕聆听", "不保存个人收听历史和进度；退出后重新选曲才恢复记录。封面缓存单独管理。", incognito, onIncognito)
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ASMR.one 网络镜像", style = MaterialTheme.typography.titleMedium)
            Text("当前：${currentMirror.ifBlank { "自动切换镜像" }}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OtohaFilterChip(selected = mirrorDraft.isBlank(), onClick = { mirrorDraft = "" }, label = "自动")
                AsmrOneClient.DEFAULT_MIRRORS.forEach { mirror ->
                    OtohaFilterChip(
                        selected = mirrorDraft.trimEnd('/') == mirror,
                        onClick = { mirrorDraft = mirror },
                        label = mirror.removePrefix("https://"),
                    )
                }
            }
            OutlinedTextField(value = mirrorDraft, onValueChange = { mirrorDraft = it },
                label = { Text("HTTPS API 地址") }, placeholder = { Text("留空使用自动切换") },
                isError = mirrorProblem != null, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(mirrorProblem ?: "填写 API 根地址，不要附加 /api/works 或查询参数") })
            OtohaOutlinedButton(
                onClick = { onMirror(mirrorDraft) },
                label = "应用镜像",
                enabled = mirrorProblem == null && MirrorSettings.normalize(mirrorDraft) != currentMirror,
            )
            Text("应用后重新载入 ASMR.one 目录，当前播放继续使用已取得的地址。ASMR18 直接访问固定站点。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("个人数据", style = MaterialTheme.typography.titleMedium)
            Text("收藏、最近收听和续播位置仅保存在这台设备上。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary)
            OtohaOutlinedButton(onClick = { clearOpen = true }, label = "清除历史与续播位置")
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("封面缓存", style = MaterialTheme.typography.titleMedium)
            Text("封面可缓存在内存与本机磁盘中，无痕模式也可能缓存封面。清理不会删除收藏、历史或进度；再次浏览时会重新缓存。音频仅在线缓冲，不下载到本地。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            OtohaOutlinedButton(
                onClick = onClearCoverCache,
                label = if (clearingCoverCache) "正在清理…" else "清理封面缓存",
                enabled = !clearingCoverCache,
            )
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本地诊断", style = MaterialTheme.typography.titleMedium)
            Text("复制设备、应用版本与最近的异常信息。诊断只保存在本机，不会自动上传。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            OtohaOutlinedButton(onClick = {
                val reports = (context.applicationContext as OtohaApplication).crashReports
                diagnosticMessage = if (copyDiagnostics(context, reports.diagnosticText())) "诊断信息已复制" else "复制失败，请重试"
            }, label = "复制诊断信息")
            diagnosticMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text("Otoha Mobile\n直接连接音源，在线流式聆听", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }
    if (clearOpen) AlertDialog(onDismissRequest = { clearOpen = false }, title = { Text("清除收听记录？") },
        text = { Text("最近收听与续播位置会被清除，收藏会保留。") },
        confirmButton = { OtohaTextButton(onClick = { onClear(); clearOpen = false }, label = "清除") },
        dismissButton = { OtohaTextButton(onClick = { clearOpen = false }, label = "取消") })
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MiniPlayer(state: PlaybackUi, onOpenDetail: () -> Unit, onOpenPlayer: () -> Unit, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "查看作品详情", onClick = onOpenDetail)) {
        Column {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.track?.title.orEmpty(), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.recoveryStatus.message ?: if (state.buffering) "正在缓冲…" else state.work?.circle.orEmpty(),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OtohaTextButton(onClick = onToggle, label = if (state.showPause) "暂停" else "播放")
                OtohaIconButton(onClick = onOpenPlayer) {
                    Icon(painterResource(R.drawable.ic_player_expand), contentDescription = "打开播放器",
                        modifier = Modifier.rotate(180f))
                }
            }
            LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
private fun PlayerScreen(state: PlaybackUi, work: WorkSummary, player: PlayerConnection, incognito: Boolean,
    sleepDeadline: Long, onSleep: (Int) -> Unit, retrying: Boolean, onRetry: () -> Unit,
    modifier: Modifier = Modifier) {
    var dragging by remember(work.ref, state.track?.id) { mutableStateOf(false) }
    var slider by remember(work.ref, state.track?.id) { mutableFloatStateOf(0f) }
    var queueOpen by rememberSaveable(work.ref.key) { mutableStateOf(false) }
    var chaptersOpen by rememberSaveable(work.ref.key, state.track?.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WorkCoverHeader(work) {
            Text(if (incognito) "正在播放 · 无痕" else "正在播放", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.track?.title.orEmpty(), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(work.title, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
        val manualRetry = state.recoveryStatus == RecoveryStatus.EXHAUSTED ||
            (state.recoveryStatus == RecoveryStatus.NONE && state.error != null)
        val statusText = state.recoveryStatus.message ?: state.error
        if (statusText != null) {
            Text(statusText, color = if (manualRetry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall)
        }
        if (manualRetry) {
            OtohaOutlinedButton(
                onClick = onRetry,
                label = if (retrying) "正在刷新播放地址…" else "刷新地址并重试",
                enabled = state.connected && !retrying,
            )
        }
        if (state.track?.subtitle != null) Surface(color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text(SubtitleDisplay.content(state.subtitleText, state.subtitleLoading, state.subtitleError), Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge)
        }
        Column {
            Slider(value = if (dragging) slider else if (state.durationMs > 0)
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f,
                enabled = state.durationMs > 0,
                modifier = Modifier.semantics { contentDescription = "播放进度" },
                onValueChange = { dragging = true; slider = it },
                onValueChangeFinished = { player.seek((slider * state.durationMs).toLong()); dragging = false })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time(if (dragging) (slider * state.durationMs).toLong() else state.positionMs),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(time(state.durationMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            OtohaIconButton(onClick = player::previous, enabled = state.index > 0) {
                Icon(painterResource(R.drawable.ic_player_previous), contentDescription = "上一曲")
            }
            val playPress = rememberButtonPress()
            FilledIconButton(
                onClick = player::toggle,
                modifier = Modifier.size(64.dp).then(playPress.modifier),
                interactionSource = playPress.interactionSource,
            ) {
                Icon(painterResource(if (state.showPause) R.drawable.ic_player_pause else R.drawable.ic_player_play),
                    contentDescription = if (state.showPause) "暂停" else "播放", modifier = Modifier.size(30.dp))
            }
            OtohaIconButton(onClick = player::next, enabled = state.index < state.tracks.lastIndex) {
                Icon(painterResource(R.drawable.ic_player_next), contentDescription = "下一曲")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerOptionMenu(label = "${state.speed.toString().removeSuffix(".0")}×", description = "播放速度",
                selected = state.speed, options = listOf(.75f to "0.75×", 1f to "1×", 1.25f to "1.25×", 1.5f to "1.5×", 2f to "2×"),
                onSelect = player::speed, modifier = Modifier.weight(1f))
            val remainingMinutes = if (sleepDeadline > 0)
                ((sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 59_999) / 60_000 else 0
            PlayerOptionMenu(label = if (sleepDeadline > 0) "约 $remainingMinutes 分钟" else "定时",
                description = "睡眠定时", selected = if (sleepDeadline == 0L) 0 else null,
                options = listOf(0 to "关闭定时", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟"),
                onSelect = onSleep, modifier = Modifier.weight(1f))
        }
        val chapterTrack = state.track?.takeIf { it.chapters.isNotEmpty() }
        if (chapterTrack != null) key(work.ref, chapterTrack.id) {
            val chapters = chapterTrack.chapters
            PlayerSection("章节", "${chapters.size}", chaptersOpen, { chaptersOpen = !chaptersOpen }) {
                chapters.forEach { chapter ->
                    val press = rememberButtonPress()
                    Row(
                        Modifier.fillMaxWidth().then(press.modifier).clickable(
                            enabled = chaptersOpen,
                            interactionSource = press.interactionSource,
                        ) { player.seek(chapter.startMs) }
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            chapter.title,
                            Modifier.weight(1f).padding(end = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            time(chapter.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        val queuePosition = if (state.tracks.isEmpty()) 0 else state.index + 1
        PlayerSection("播放队列", "$queuePosition / ${state.tracks.size}", queueOpen, { queueOpen = !queueOpen }) {
            state.tracks.forEachIndexed { index, track ->
                val isCurrent = index == state.index
                val colors = MaterialTheme.colorScheme
                val press = rememberButtonPress()
                Row(
                    Modifier.fillMaxWidth().then(press.modifier).clip(RoundedCornerShape(12.dp))
                        .background(if (isCurrent) colors.surfaceContainer else Color.Transparent)
                        .clickable(enabled = queueOpen, interactionSource = press.interactionSource) {
                            player.select(index)
                        }
                        .semantics { selected = isCurrent }
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(
                        track.title,
                        Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isCurrent) colors.primary else colors.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> PlayerOptionMenu(label: String, description: String, selected: T?, options: List<Pair<T, String>>,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val press = rememberButtonPress()
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "player menu arrow",
    )
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = description
                stateDescription = label
            }.then(press.modifier),
            interactionSource = press.interactionSource,
        ) {
            ButtonLabel(label, Modifier.weight(1f, fill = false), maxLines = 1)
            Icon(painterResource(R.drawable.ic_player_expand), contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(arrowRotation))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                val itemPress = rememberButtonPress()
                DropdownMenuItem(
                    text = { ButtonLabel(title) },
                    onClick = { expanded = false; onSelect(value) },
                    modifier = Modifier.semantics { this.selected = selected == value }.then(itemPress.modifier),
                    trailingIcon = { if (selected == value) Text("✓", color = MaterialTheme.colorScheme.primary) },
                    interactionSource = itemPress.interactionSource,
                )
            }
        }
    }
}

/** Keeps section content mounted until its exit transition completes. */
@Composable
internal fun PlayerSection(
    title: String,
    detail: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val press = rememberButtonPress()
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = PanelFloatSpec,
        label = "Player section chevron",
    )
    Column {
        TextButton(
            onClick = onToggle,
            interactionSource = press.interactionSource,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "已展开" else "已折叠"
            }.then(press.modifier),
        ) {
            Text(
                title,
                Modifier.weight(1f).clipToBounds(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                Modifier.weight(0.6f, fill = false).clipToBounds(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painterResource(R.drawable.ic_player_expand),
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(18.dp).graphicsLayer {
                    rotationZ = chevronRotation.value
                },
            )
        }
        val contentSemantics = if (expanded) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(PanelSizeSpec, expandFrom = Alignment.Top) + fadeIn(PanelFloatSpec),
            exit = shrinkVertically(PanelSizeSpec, shrinkTowards = Alignment.Top) + fadeOut(PanelFloatSpec),
        ) {
            Column(
                modifier = contentSemantics.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("打开 ASMR18 作品") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("作品链接") },
            placeholder = { Text("https://asmr18.fans/allages/rj…/") }, singleLine = true) },
        confirmButton = {
            OtohaTextButton(onClick = { onOpen(text) }, label = "打开", enabled = text.isNotBlank())
        },
        dismissButton = { OtohaTextButton(onClick = onDismiss, label = "取消") })
}

@Composable
private fun Loading(label: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp)
        Text(label, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun EmptyState(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 36.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            OtohaTextButton(onClick = onRetry, label = "重试")
        }
    }
}

private fun time(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
