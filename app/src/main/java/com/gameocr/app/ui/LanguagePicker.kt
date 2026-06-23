package com.gameocr.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.data.Language
import com.gameocr.app.data.Languages
import kotlinx.coroutines.launch

/**
 * 检索式语言选择器。视觉模仿 Google 翻译网页的语言面板：
 * - 收起状态：一个 OutlinedButton 风格的行，左侧 label，右侧当前语言名 + 下拉箭头
 * - 展开状态：ModalBottomSheet，顶部搜索框（自动聚焦），下方 LazyColumn 列出 [Languages.ALL]
 *   按 name/code 模糊匹配的子集；当前选中项左侧打勾。
 *
 * 单选语义：点击任意项 → [onSelect] + 关闭 sheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePicker(
    label: String,
    currentCode: String,
    onSelect: (String) -> Unit,
    pinned: List<String> = emptyList(),
    onTogglePin: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentName = remember(currentCode) { Languages.nameOf(context, currentCode) }

    // 收起状态：触发按钮
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 1.dp)
            .clickable { expanded = true }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                currentName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            currentCode,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (expanded) {
        LanguagePickerSheet(
            currentCode = currentCode,
            pinned = pinned,
            onSelect = { code ->
                onSelect(code)
                expanded = false
            },
            onTogglePin = onTogglePin,
            onDismiss = { expanded = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSheet(
    currentCode: String,
    pinned: List<String>,
    onSelect: (String) -> Unit,
    onTogglePin: ((String) -> Unit)?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // 搜索后拆成两段：pinned 命中的按收藏顺序排前；其余按 Languages.ALL 顺序排后。
    val (pinnedResults, otherResults) = remember(query, pinned) {
        val matched = Languages.search(context, query)
        val matchedCodes = matched.map { it.code }.toSet()
        val pinnedHit = pinned
            .filter { it in matchedCodes }
            .mapNotNull { code -> matched.firstOrNull { it.code == code } }
        val others = matched.filter { it.code !in pinned }
        pinnedHit to others
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 600.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.lang_picker_search_placeholder)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            HorizontalDivider()
            if (pinnedResults.isEmpty() && otherResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.lang_picker_no_match),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(state = listState) {
                    // 收藏区：按收藏顺序排，置顶
                    if (pinnedResults.isNotEmpty()) {
                        items(pinnedResults, key = { "p_${it.code}" }) { lang ->
                            LanguageRow(
                                lang = lang,
                                isPinned = true,
                                isSelected = lang.code.equals(currentCode, ignoreCase = true),
                                onClick = {
                                    scope.launch {
                                        sheetState.hide()
                                        onSelect(lang.code)
                                    }
                                },
                                onTogglePin = onTogglePin?.let { cb -> { cb(lang.code) } }
                            )
                        }
                        item(key = "divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                    items(otherResults, key = { it.code }) { lang ->
                        LanguageRow(
                            lang = lang,
                            isPinned = false,
                            isSelected = lang.code.equals(currentCode, ignoreCase = true),
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    onSelect(lang.code)
                                }
                            },
                            onTogglePin = onTogglePin?.let { cb -> { cb(lang.code) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    lang: Language,
    isPinned: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTogglePin: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.lang_picker_selected),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            stringResource(lang.nameRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
        Text(
            lang.code,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
        if (onTogglePin != null) {
            IconButton(onClick = onTogglePin) {
                Icon(
                    if (isPinned) Icons.Default.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(
                        if (isPinned) R.string.lang_picker_unpin else R.string.lang_picker_pin
                    ),
                    tint = if (isPinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
