package io.github.otoha.mobile

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val snackbar = remember { SnackbarHostState() }
    val inDetail = state.detail != null || state.detailLoading
    val active = playback.work?.takeIf(vm::visible)

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.message(null) }
    }
    BackHandler(inDetail) { vm.closeDetail() }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(title = {
                Column {
                    Text(if (inDetail) "作品详情" else "Otoha", fontWeight = FontWeight.SemiBold)
                    if (!inDetail) Text(if (incognito) "无痕聆听中" else "为安静的片刻", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }, navigationIcon = { if (inDetail) TextButton(onClick = vm::closeDetail) { Text("‹ 返回") } },
                actions = { if (!inDetail && tab == 0) TextButton(onClick = { linkOpen = true }) { Text("打开链接") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            if (!playback.connected && playback.error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("播放器连接失败", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm.player::retryConnection) { Text("重新连接") }
            }
            }
        },
        bottomBar = {
            Column {
                if (active != null) MiniPlayer(playback, onOpen = { playerOpen = true }, onToggle = vm.player::toggle)
                NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                    listOf("发现" to "◉", "收藏" to "♡", "最近" to "◷", "设置" to "☷").forEachIndexed { index, item ->
                        NavigationBarItem(selected = tab == index && !inDetail,
                            onClick = { vm.closeDetail(); tab = index }, icon = { Text(item.second) }, label = { Text(item.first) })
                    }
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.detailLoading -> Loading("正在读取作品与曲目…")
                state.detail != null -> DetailScreen(state.detail!!,
                    favorite = personal.favorites.any { it.ref.key == state.detail!!.summary.ref.key },
                    connected = playback.connected,
                    onFavorite = { vm.store.toggleFavorite(state.detail!!.summary) },
                    onFacet = { vm.selectDetailFacet(it); tab = 0 },
                    onPlay = { vm.play(state.detail!!, it); playerOpen = true })
                tab == 0 -> CatalogScreen(state, personal.showRestricted, vm::selectSource, vm::selectCategory,
                    vm::open, { vm.loadMore() }, { vm.loadMore(refresh = true) },
                    { vm.openFacetPicker(it) }, vm::removeFacet, vm::clearFacets, { tab = 3 })
                tab == 1 -> LocalWorks("收藏", personal.favorites.filter(vm::visible), "把喜欢的作品留在这里", vm::open)
                tab == 2 -> LocalWorks("最近收听", personal.recent.filter(vm::visible), "开始播放后会在这里留下记录", vm::open,
                    leading = {
                        personal.snapshot?.takeIf { vm.visible(it.work) }?.let { snapshot ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("接着听", style = MaterialTheme.typography.titleMedium)
                                    Text(snapshot.work.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${snapshot.tracks.getOrNull(snapshot.index)?.title.orEmpty()} · ${time(snapshot.positionMs)}",
                                        style = MaterialTheme.typography.bodySmall)
                                    Button(enabled = playback.connected && !state.restoring,
                                        onClick = { vm.restore(snapshot); playerOpen = true }) {
                                        Text(if (state.restoring) "正在刷新播放地址…" else "恢复播放")
                                    }
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
    if (playerOpen && active != null) ModalBottomSheet(onDismissRequest = { playerOpen = false }) {
        PlayerScreen(playback, vm.player, incognito, sleepDeadline, vm.store::sleepAfter,
            state.restoring, vm::retryPlayback, Modifier.padding(horizontal = 24.dp))
    }
    if (linkOpen) LinkDialog(onDismiss = { linkOpen = false }, onOpen = { vm.openLink(it); linkOpen = false })
    state.facetPicker?.let { picker ->
        ModalBottomSheet(onDismissRequest = vm::closeFacetPicker) {
            FacetPickerScreen(picker, state.filters, { vm.selectFacet(it); tab = 0 },
                { vm.openFacetPicker(picker.kind, reload = true) })
        }
    }
}

@Composable
private fun CatalogScreen(
    state: MainUi, restricted: Boolean,
    onSource: (SourceId) -> Unit, onCategory: (String) -> Unit,
    onOpen: (WorkSummary) -> Unit,
    onMore: () -> Unit, onRefresh: () -> Unit,
    onPickFacet: (FacetKind) -> Unit, onRemoveFacet: (FacetKind) -> Unit, onClearFacets: () -> Unit,
    onDisplaySettings: () -> Unit,
) {
    val visibility = CatalogVisibility.from(state.catalog.works, restricted)
    val works = visibility.visible
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("发现好声音", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("按需浏览，选择喜欢的版本开始聆听。", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = state.source == SourceId.ASMR_ONE,
                    onClick = { onSource(SourceId.ASMR_ONE) }, label = { Text("ASMR.one") })
                FilterChip(selected = state.source == SourceId.ASMR18,
                    onClick = { onSource(SourceId.ASMR18) }, label = { Text("ASMR18") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !state.catalog.loading) { Text("刷新") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FacetKind.entries.forEach { kind ->
                    FilterChip(selected = state.filters.values.any { it.kind == kind },
                        onClick = { onPickFacet(kind) }, label = { Text(kind.title()) })
                }
            }
        }
        if (!state.filters.isEmpty) item {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.filters.values.forEach { value ->
                        InputChip(selected = true, onClick = { onRemoveFacet(value.kind) },
                            label = { Text("${value.kind.title()}：${value.name}") }, trailingIcon = { Text("×") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.source == SourceId.ASMR18) "站点分类结果 · 新条件将替换当前条件" else "远端筛选结果 · 条件同时匹配",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    TextButton(onClick = onClearFacets) { Text("清空筛选") }
                }
            }
        }
        if (state.source == SourceId.ASMR18 && state.filters.isEmpty) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val categories = if (restricted) listOf("allages" to "全年龄", "boys" to "男性向", "girls" to "女性向")
                    else listOf("allages" to "全年龄")
                categories.forEach { (id, title) ->
                    FilterChip(selected = state.category == id, onClick = { onCategory(id) }, label = { Text(title) })
                }
            }
        }
        if (!restricted) item {
            Text("当前仅显示已标注全年龄的作品", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary)
        }
        if (visibility.loadedCount > 0) item {
            Column {
                Text("已加载 ${visibility.loadedCount} 件 · 显示 ${works.size} 件 · 按显示设置隐藏 ${visibility.hiddenCount} 件",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (visibility.hiddenCount > 0) TextButton(onClick = onDisplaySettings) { Text("调整分级显示设置") }
            }
        }
        items(works, key = { it.ref.key }) { work -> WorkCard(work) { onOpen(work) } }
        if (state.catalog.error != null) item {
            ErrorCard(state.catalog.error, onMore)
        } else if (state.catalog.loading) item {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (state.catalog.hasMore) item {
            OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) {
                Text(if (works.isEmpty() && visibility.hiddenCount > 0) "已加载作品被显示设置隐藏，继续加载" else "加载下一页")
            }
        } else if (works.isEmpty()) item {
            EmptyState(if (visibility.hiddenCount > 0) "已加载的作品被分级设置隐藏，可调整设置后查看。"
                else if (!state.filters.isEmpty) "音源没有返回符合当前条件的作品" else "音源暂未返回作品")
        }
    }
}

@Composable
private fun WorkCard(work: WorkSummary, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(work, Modifier.size(84.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(work.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOf(work.workNumber, work.circle).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (work.actors.isNotEmpty()) Text(work.actors.joinToString(" / "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Cover(work: WorkSummary, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        Text("♪", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        if (work.coverUrl.isNotBlank()) AsyncImage(model = ImageRequest.Builder(LocalContext.current)
            .data(work.coverUrl).apply { work.ref.source.requestHeaders().forEach { (key, value) -> addHeader(key, value) } }
            .build(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun DetailScreen(detail: WorkDetail, favorite: Boolean, connected: Boolean,
    onFavorite: () -> Unit, onFacet: (FacetValue) -> Unit, onPlay: (AudioTrack) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(detail.summary, Modifier.size(116.dp))
                Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(detail.summary.workNumber, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    val circle = detail.summary.facets.firstOrNull { it.kind == FacetKind.CIRCLE }
                    if (circle != null) AssistChip(onClick = { onFacet(circle) }, label = { Text(circle.name) })
                    else Text(detail.summary.circle, style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = onFavorite) { Text(if (favorite) "♥ 已收藏" else "♡ 收藏") }
                }
            }
        }
        item {
            Text(detail.summary.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                Card(onClick = { onPlay(track) }, enabled = connected,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
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
        values.forEach { value -> AssistChip(onClick = { onFacet(value) }, label = { Text(value.name) }) }
    }
}

@Composable
private fun LocalWorks(title: String, works: List<WorkSummary>, empty: String, onOpen: (WorkSummary) -> Unit,
    leading: @Composable () -> Unit = {}) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        item { leading() }
        items(works, key = { it.ref.key }) { work -> WorkCard(work) { onOpen(work) } }
        if (works.isEmpty()) item { EmptyState(empty) }
    }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (mode, title) ->
                    FilterChip(selected = themeMode == mode, onClick = { onTheme(mode) }, label = { Text(title) })
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
                FilterChip(selected = mirrorDraft.isBlank(), onClick = { mirrorDraft = "" }, label = { Text("自动") })
                AsmrOneClient.DEFAULT_MIRRORS.forEach { mirror ->
                    FilterChip(selected = mirrorDraft.trimEnd('/') == mirror, onClick = { mirrorDraft = mirror },
                        label = { Text(mirror.removePrefix("https://")) })
                }
            }
            OutlinedTextField(value = mirrorDraft, onValueChange = { mirrorDraft = it },
                label = { Text("HTTPS API 地址") }, placeholder = { Text("留空使用自动切换") },
                isError = mirrorProblem != null, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(mirrorProblem ?: "填写 API 根地址，不要附加 /api/works 或查询参数") })
            OutlinedButton(onClick = { onMirror(mirrorDraft) }, enabled = mirrorProblem == null &&
                MirrorSettings.normalize(mirrorDraft) != currentMirror) { Text("应用镜像") }
            Text("应用后重新载入 ASMR.one 目录，当前播放继续使用已取得的地址。ASMR18 直接访问固定站点。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("个人数据", style = MaterialTheme.typography.titleMedium)
            Text("收藏、最近收听和续播位置仅保存在这台设备上。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { clearOpen = true }) { Text("清除历史与续播位置") }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("封面缓存", style = MaterialTheme.typography.titleMedium)
            Text("封面可缓存在内存与本机磁盘中，无痕模式也可能缓存封面。清理不会删除收藏、历史或进度；再次浏览时会重新缓存。音频仅在线缓冲，不下载到本地。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = onClearCoverCache, enabled = !clearingCoverCache) {
                Text(if (clearingCoverCache) "正在清理…" else "清理封面缓存")
            }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本地诊断", style = MaterialTheme.typography.titleMedium)
            Text("复制设备、应用版本与最近的异常信息。诊断只保存在本机，不会自动上传。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = {
                val reports = (context.applicationContext as OtohaApplication).crashReports
                diagnosticMessage = if (copyDiagnostics(context, reports.diagnosticText())) "诊断信息已复制" else "复制失败，请重试"
            }) { Text("复制诊断信息") }
            diagnosticMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text("Otoha Mobile\n直接连接音源，在线流式聆听", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }
    if (clearOpen) AlertDialog(onDismissRequest = { clearOpen = false }, title = { Text("清除收听记录？") },
        text = { Text("最近收听与续播位置会被清除，收藏会保留。") },
        confirmButton = { TextButton(onClick = { onClear(); clearOpen = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("取消") } })
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
private fun MiniPlayer(state: PlaybackUi, onOpen: () -> Unit, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.track?.title.orEmpty(), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (state.buffering) "正在缓冲…" else state.work?.circle.orEmpty(),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                TextButton(onClick = onToggle) { Text(if (state.playing || state.buffering) "暂停" else "播放") }
            }
            LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
private fun PlayerScreen(state: PlaybackUi, player: PlayerConnection, incognito: Boolean,
    sleepDeadline: Long, onSleep: (Int) -> Unit, retrying: Boolean, onRetry: () -> Unit,
    modifier: Modifier = Modifier) {
    var dragging by remember(state.work?.ref, state.track?.id) { mutableStateOf(false) }
    var slider by remember(state.work?.ref, state.track?.id) { mutableFloatStateOf(0f) }
    var queueOpen by rememberSaveable(state.work?.ref?.key) { mutableStateOf(false) }
    var chaptersOpen by rememberSaveable(state.work?.ref?.key, state.track?.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (incognito) "正在播放 · 无痕" else "正在播放", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.track?.title.orEmpty(), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.work?.title.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error != null) {
            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onRetry, enabled = state.connected && !retrying) {
                Text(if (retrying) "正在刷新播放地址…" else "刷新地址并重试")
            }
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
            IconButton(onClick = player::previous, enabled = state.index > 0) {
                Icon(painterResource(R.drawable.ic_player_previous), contentDescription = "上一曲")
            }
            FilledIconButton(onClick = player::toggle, modifier = Modifier.size(64.dp)) {
                Icon(painterResource(if (state.playing || state.buffering) R.drawable.ic_player_pause else R.drawable.ic_player_play),
                    contentDescription = if (state.playing || state.buffering) "暂停" else "播放", modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = player::next, enabled = state.index < state.tracks.lastIndex) {
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
        state.track?.chapters?.takeIf { it.isNotEmpty() }?.let { chapters ->
            PlayerSectionHeader("章节", "${chapters.size}", chaptersOpen, { chaptersOpen = !chaptersOpen })
            if (chaptersOpen) chapters.forEach { chapter ->
                Row(Modifier.fillMaxWidth().clickable { player.seek(chapter.startMs) }
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(chapter.title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(time(chapter.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        PlayerSectionHeader("播放队列", "${if (state.tracks.isEmpty()) 0 else state.index + 1} / ${state.tracks.size}",
            queueOpen, { queueOpen = !queueOpen })
        if (queueOpen) state.tracks.forEachIndexed { index, track ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (index == state.index) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                .clickable { player.select(index) }.semantics { selected = index == state.index }
                .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(track.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (index == state.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun <T> PlayerOptionMenu(label: String, description: String, selected: T?, options: List<Pair<T, String>>,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = description
                stateDescription = label
            }) {
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_player_expand), contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(value) },
                    modifier = Modifier.semantics { this.selected = selected == value },
                    trailingIcon = { if (selected == value) Text("✓", color = MaterialTheme.colorScheme.primary) })
            }
        }
    }
}

@Composable
private fun PlayerSectionHeader(title: String, detail: String, expanded: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            stateDescription = if (expanded) "已展开" else "已折叠"
        }) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(painterResource(R.drawable.ic_player_expand), contentDescription = null,
            modifier = Modifier.padding(start = 8.dp).size(18.dp).rotate(if (expanded) 180f else 0f))
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("打开 ASMR18 作品") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("作品链接") },
            placeholder = { Text("https://asmr18.fans/allages/rj…/") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOpen(text) }, enabled = text.isNotBlank()) { Text("打开") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
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
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun time(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
