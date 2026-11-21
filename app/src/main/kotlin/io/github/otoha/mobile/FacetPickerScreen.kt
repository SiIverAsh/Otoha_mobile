package io.github.otoha.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.otoha.core.BrowseFilters
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.SourceId

internal fun FacetKind.title(): String = when (this) {
    FacetKind.TAG -> "标签"
    FacetKind.ACTOR -> "声优"
    FacetKind.CIRCLE -> "社团"
}

@Composable
internal fun FacetPickerScreen(picker: FacetPickerUi, selected: BrowseFilters,
    onSelect: (FacetValue) -> Unit, onReload: () -> Unit) {
    var query by remember(picker.source, picker.kind) { mutableStateOf("") }
    val matches = remember(picker.options, query) {
        picker.options.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选择${picker.kind.title()}", style = MaterialTheme.typography.titleLarge)
        Text(if (picker.source == SourceId.ASMR18) "ASMR18 每次使用一个站点分类；选择新条件会替换其他条件。"
            else "每类选择一个条件，标签、声优与社团可组合筛选；结果包含关联翻译版本。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("检索选项名称") },
            supportingText = { Text("只检索此选项列表，不是作品全站关键词搜索") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(if (picker.upstreamCount > 0) "站点选项 ${picker.upstreamCount} 个 · 已发现 ${picker.discoveredCount} 个"
            else "已发现 ${picker.discoveredCount} 个选项，来自已加载的作品与详情，可能不是完整列表。",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        if (picker.loading) CircularProgressIndicator()
        if (picker.error != null) {
            Text("站点选项暂不可用：${picker.error}。已发现的选项仍可选择。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OtohaTextButton(onClick = onReload, label = "重试加载站点选项")
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            items(matches, key = { it.key }) { value ->
                val press = rememberButtonPress()
                Row(
                    Modifier.fillMaxWidth().then(press.modifier).clickable(
                        interactionSource = press.interactionSource,
                        indication = ripple(),
                        onClick = { onSelect(value) },
                    ).padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(value.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge)
                    if (selected.values.any { it.key == value.key }) Text("已选", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (matches.isEmpty() && !picker.loading) item {
                Text(if (query.isBlank()) "暂未发现可选择的${picker.kind.title()}" else "没有匹配的选项",
                    Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
