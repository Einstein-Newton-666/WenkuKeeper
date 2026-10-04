package io.github.lnrplugin.wenku8plus.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.lnrplugin.wenku8plus.migrate.MatchLevel
import io.github.lnrplugin.wenku8plus.migrate.MigrationBook
import io.github.lnrplugin.wenku8plus.migrate.MigrationEngine
import io.github.lnrplugin.wenku8plus.migrate.MigrationMatcher
import io.github.lnrplugin.wenku8plus.migrate.ScoredCandidate
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.ui.components.SettingsBasicEntry
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi

/** 新书架名称的默认值; 与引擎内部的默认值保持一致。 */
private const val DEFAULT_NEW_SHELF_NAME = MigrationEngine.DEFAULT_SHELF_NAME

/** 每本书默认展示的候选数量, 其余折叠起来。 */
private const val VISIBLE_CANDIDATES = 3

/**
 * 书库迁移分段。
 *
 * 说明这个功能的真实语义: **目标永远是当前激活的数据源**, 来源可以是书架里任意来源的书。
 * 用户需要先在宿主里把目标数据源设为激活源, 再依次执行导出、匹配、确认、导入。
 *
 * 页面只负责收集用户的选择并展示引擎给的候选; 所有落盘与网络都在
 * [io.github.lnrplugin.wenku8plus.migrate.MigrationEngine] 里完成。
 *
 * @param userDataRepository 宿主提供的用户数据仓库, 用于显示"当前激活的数据源"
 * @param viewModel 迁移状态持有者
 */
@Composable
fun MigrationSection(
    userDataRepository: UserDataRepositoryApi,
    viewModel: MigrationViewModel,
) {
    val state by viewModel.uiState.collectAsState()

    // 宿主把当前激活的网页数据源 id 记录在这个用户数据路径里, 用它提醒用户先切换到目标源。
    val activeSourceId by rememberStringSetting(
        userDataRepository,
        UserDataPath.Settings.Data.WebDataSourceId.path,
        ""
    )

    var dialog by remember { mutableStateOf<MigrationDialog?>(null) }
    var selectedShelfId by remember { mutableStateOf<Int?>(null) }
    var expandedBookIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(viewModel) {
        viewModel.refresh()
    }

    val running = state.running
    val plan = state.plan
    val selectedShelfName = state.shelves
        .firstOrNull { it.id == selectedShelfId }
        ?.name
        ?.takeIf { it.isNotBlank() }

    SettingsBasicEntry(
        modifier = settingsEntryModifier(),
        title = "导入目标",
        description = "目标永远是宿主里当前激活的数据源, 来源可以是书架里任意来源的书。" +
            "请先在宿主设置里切换到目标数据源, 再执行下面的步骤; 匹配靠书名与作者, 结果需要你逐条确认。" +
            "导入只会在目标数据源里新建一个书架：原来的书架和里面的书都会保留，不会被删除或改动。",
        extraBelowContent = {
            Text(
                text = if (activeSourceId.isBlank()) {
                    "当前激活的数据源：未知（请先在宿主里选择一个数据源）"
                } else {
                    "当前激活的数据源：$activeSourceId"
                },
                color = if (activeSourceId.isBlank()) {
                    colorScheme.error
                } else {
                    colorScheme.onSurfaceVariant
                },
                style = typography.bodySmall
            )
        }
    )

    // 操作结果必须留在页面上: 提示条会消失, 而"还没有迁移计划""还没有确认任何书"这类失败
    // 全靠它才能被用户看到。
    if (running || state.message.isNotBlank()) {
        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "迁移状态",
            description = if (running) state.runningLabel else state.message,
            trailingContent = {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            extraBelowContent = {
                if (running && state.progressTotal > 0) {
                    Text(
                        text = "匹配进度 ${state.progressDone}/${state.progressTotal}：" +
                            state.progressCurrent,
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
            }
        )
    }

    SettingsBasicEntry(
        modifier = settingsEntryModifier(),
        title = "导出范围",
        description = "只导出某一个书架, 或者导出全部书架里出现过的书(按 id 去重)",
        enabled = !running,
        onClick = { dialog = MigrationDialog.ShelfPicker },
        extraBelowContent = {
            Text(
                text = selectedShelfName?.let { "已选择：$it" } ?: "全部书架",
                color = colorScheme.primary,
                style = typography.bodySmall
            )
        }
    )

    SettingsBasicEntry(
        modifier = settingsEntryModifier(),
        title = "1. 导出书架",
        description = "把书目清单写到本机; 离线完成, 不需要联网。再次导出会覆盖上一份计划",
        enabled = !running,
        onClick = { viewModel.exportShelf(selectedShelfId) },
        extraBelowContent = {
            if (plan != null) {
                Text(
                    text = "当前计划：${plan.books.size} 本书",
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodySmall
                )
            }
        }
    )

    SettingsBasicEntry(
        modifier = settingsEntryModifier(),
        title = "2. 开始匹配",
        description = if (running && state.progressTotal > 0) {
            "匹配中 ${state.progressDone}/${state.progressTotal}：${state.progressCurrent}"
        } else {
            "在当前激活的数据源里逐本搜索候选; 数据源通常有限流, 会比较慢。" +
                "已经搜过的书会跳过, 所以中断之后可以继续"
        },
        enabled = !running,
        onClick = { viewModel.matchTargets() },
        trailingContent = {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    )

    if (plan != null) {
        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "迁移计划",
            description = buildString {
                append("来源 ${plan.sourceLabel.ifBlank { "未知" }}")
                if (plan.targetLabel.isNotBlank()) append(" → 目标 ${plan.targetLabel}")
                append("；共 ${plan.books.size} 本，已匹配 ${plan.matchedCount} 本，")
                append("已确认 ${plan.acceptedCount} 本")
            },
            extraBelowContent = {
                state.warnings.forEach { warning ->
                    Text(
                        text = "· $warning",
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
            }
        )

        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "3. 采纳全部高置信",
            description = "把所有高置信匹配一次性确认; 已经手动确认过的书不会被覆盖",
            enabled = !running,
            onClick = { viewModel.acceptAllHighConfidence() }
        )

        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "4. 导入到新书架",
            description = "新建一个书架, 把已确认的书放进去（原书架不动）, " +
                "并尽量搬运阅读进度（章节级进度不会复制）",
            enabled = !running,
            onClick = { dialog = MigrationDialog.NewShelfName },
            extraBelowContent = {
                Text(
                    text = "已确认 ${plan.acceptedCount} / 共 ${plan.books.size} 本",
                    color = if (plan.acceptedCount == 0) {
                        colorScheme.onSurfaceVariant
                    } else {
                        colorScheme.primary
                    },
                    style = typography.bodySmall
                )
            }
        )
    }

    state.report?.let { report ->
        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "导入结果",
            description = buildString {
                append("已加入 ${report.added} 本")
                append(report.newBookshelfId?.let { "（新书架 id $it）" } ?: "（没有成功导入任何书）")
                append("；跳过 ${report.skipped} 本；失败 ${report.failed} 本")
            },
            extraBelowContent = {
                report.failures.take(10).forEach { failure ->
                    Text(
                        text = "· $failure",
                        color = colorScheme.error,
                        style = typography.bodySmall
                    )
                }
                if (report.failures.size > 10) {
                    Text(
                        text = "· 另有 ${report.failures.size - 10} 条未显示",
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
            }
        )
    }

    plan?.books?.forEach { book ->
        MigrationBookEntry(
            book = book,
            expanded = book.sourceBook.id in expandedBookIds,
            enabled = !running,
            onToggleExpand = {
                expandedBookIds = if (book.sourceBook.id in expandedBookIds) {
                    expandedBookIds - book.sourceBook.id
                } else {
                    expandedBookIds + book.sourceBook.id
                }
            },
            onAccept = { targetId -> viewModel.accept(book.sourceBook.id, targetId) },
            onReset = { viewModel.resetSearch(listOf(book.sourceBook.id)) }
        )
    }

    if (plan != null) {
        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "清除迁移计划",
            description = "删除本机的计划文件; 已经导入到书架的书不会被移除",
            enabled = !running,
            onClick = { viewModel.clearPlan() }
        )
    }

    val openDialog = dialog
    if (openDialog != null) {
        when (openDialog) {
            MigrationDialog.ShelfPicker -> ShelfPickerDialog(
                shelves = state.shelves,
                selectedId = selectedShelfId,
                onSelect = { id ->
                    selectedShelfId = id
                    dialog = null
                },
                onDismissRequest = { dialog = null }
            )

            MigrationDialog.NewShelfName -> SimpleTextDialog(
                title = "导入到新书架",
                description = "已确认 ${plan?.acceptedCount ?: 0} 本书; " +
                    "新书架会包含这些书, 并尽量搬运阅读进度",
                initialText = DEFAULT_NEW_SHELF_NAME,
                confirmText = "开始导入",
                validate = { value -> if (value.isBlank()) "请输入书架名称" else null },
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    viewModel.apply(value.trim())
                    dialog = null
                }
            )
        }
    }
}

/** 迁移分段里会打开的对话框。 */
private enum class MigrationDialog {
    /** 选择"导出范围"里的书架。 */
    ShelfPicker,

    /** 输入新书架的名称。 */
    NewShelfName
}

/**
 * 一本书的确认条目: 书名、来源信息、以及排好序的候选。
 *
 * @param book 计划里的书
 * @param expanded 是否展开全部候选
 * @param enabled 是否允许交互(有操作正在执行时禁止)
 * @param onToggleExpand 展开/收起候选
 * @param onAccept 采纳某个候选; 传 null 表示这本书不导入
 * @param onReset 清除这本书的匹配结果, 让它可以重新搜索
 */
@Composable
private fun MigrationBookEntry(
    book: MigrationBook,
    expanded: Boolean,
    enabled: Boolean,
    onToggleExpand: () -> Unit,
    onAccept: (String?) -> Unit,
    onReset: () -> Unit,
) {
    val ranked = MigrationMatcher.rank(book.sourceBook, book.candidates)
    val acceptedId = book.acceptedCandidateId
    val acceptedTitle = ranked
        .firstOrNull { it.candidate.targetId == acceptedId }
        ?.candidate
        ?.title
    val visibleCount = if (expanded) ranked.size else minOf(VISIBLE_CANDIDATES, ranked.size)

    SettingsBasicEntry(
        modifier = settingsEntryModifier(),
        title = book.sourceBook.title.ifBlank { "（无书名）" },
        description = buildString {
            append(book.sourceBook.author.ifBlank { "作者未知" })
            if (book.sourceBook.shelves.isNotEmpty()) {
                append(" · ")
                append(book.sourceBook.shelves.joinToString(" / "))
            }
            append(" · ")
            append(
                when {
                    acceptedId != null -> "已确认：${acceptedTitle ?: acceptedId}"
                    ranked.isEmpty() && book.searched -> "没有候选"
                    ranked.isEmpty() -> "还没有搜索"
                    else -> "待确认（${ranked.size} 个候选）"
                }
            )
        },
        extraBelowContent = {
            if (ranked.isEmpty()) {
                Text(
                    text = book.note.ifBlank {
                        if (book.searched) {
                            "没有找到候选, 可以点「重新匹配」再试一次"
                        } else {
                            "点上面的「2. 开始匹配」为它搜索候选"
                        }
                    },
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodySmall
                )
            } else {
                ranked.take(visibleCount).forEach { scored ->
                    CandidateRow(
                        scored = scored,
                        selected = scored.candidate.targetId == acceptedId,
                        enabled = enabled,
                        onAccept = { onAccept(scored.candidate.targetId) }
                    )
                }
                if (ranked.size > visibleCount) {
                    TextButton(onClick = onToggleExpand, enabled = enabled) {
                        Text("还有 ${ranked.size - visibleCount} 个候选, 点此展开")
                    }
                } else if (expanded && ranked.size > VISIBLE_CANDIDATES) {
                    TextButton(onClick = onToggleExpand, enabled = enabled) {
                        Text("收起候选")
                    }
                }
                if (book.note.isNotBlank()) {
                    Text(
                        text = book.note,
                        color = colorScheme.error,
                        style = typography.bodySmall
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onAccept(null) },
                    enabled = enabled && acceptedId != null
                ) {
                    Text("跳过")
                }
                TextButton(onClick = onReset, enabled = enabled) {
                    Text("重新匹配")
                }
            }
        }
    )
}

/**
 * 一个候选的展示行: 书名、分数、档位与打分理由, 右侧是"采纳"。
 *
 * @param scored 打分结果
 * @param selected 是否是当前已采纳的候选
 * @param enabled 是否允许点击
 * @param onAccept 采纳这个候选
 */
@Composable
private fun CandidateRow(
    scored: ScoredCandidate,
    selected: Boolean,
    enabled: Boolean,
    onAccept: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = (if (selected) "✓ " else "") + scored.candidate.title.ifBlank { "（无书名）" },
                color = if (selected) colorScheme.primary else colorScheme.onSurface,
                style = typography.bodyMedium
            )
            Text(
                text = "${levelLabel(scored.level)} · ${scored.score} 分 · " +
                    scored.candidate.author.ifBlank { "作者未知" },
                color = levelColor(scored.level),
                style = typography.bodySmall
            )
            if (scored.reasons.isNotEmpty()) {
                Text(
                    text = scored.reasons.joinToString("；"),
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodySmall
                )
            }
        }
        TextButton(
            onClick = onAccept,
            enabled = enabled && !selected
        ) {
            Text(if (selected) "已选" else "采纳")
        }
    }
}

/**
 * "导出范围"选择对话框。
 *
 * @param shelves 宿主里的书架列表
 * @param selectedId 当前选中的书架 id; null 表示"全部书架"
 * @param onSelect 选中某个书架(null 表示全部)
 * @param onDismissRequest 关闭对话框
 */
@Composable
private fun ShelfPickerDialog(
    shelves: List<Bookshelf>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("导出范围") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ShelfRow(
                    name = "全部书架（按 id 去重）",
                    selected = selectedId == null,
                    onClick = { onSelect(null) }
                )
                shelves.forEach { shelf ->
                    val label = shelf.name.ifBlank { "未命名书架" } +
                        "（${shelf.allBookIds.size} 本）"
                    ShelfRow(
                        name = label,
                        selected = selectedId == shelf.id,
                        onClick = { onSelect(shelf.id) }
                    )
                }
                if (shelves.isEmpty()) {
                    Text(
                        text = "宿主里还没有书架, 只能导出全部书架",
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("关闭")
            }
        }
    )
}

/**
 * 书架选择对话框里的一行。
 *
 * @param name 显示文字
 * @param selected 是否是当前选项
 * @param onClick 点击回调
 */
@Composable
private fun ShelfRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = if (selected) "✓ $name" else name,
            color = if (selected) colorScheme.primary else colorScheme.onSurface,
            style = typography.bodyMedium
        )
    }
}

/** 匹配档位的中文说明。 */
private fun levelLabel(level: MatchLevel): String = when (level) {
    MatchLevel.HIGH -> "高置信"
    MatchLevel.MEDIUM -> "待确认"
    MatchLevel.LOW -> "低置信"
    MatchLevel.NONE -> "无匹配"
}

/** 匹配档位对应的颜色; 高置信用主题色突出显示。 */
@Composable
private fun levelColor(level: MatchLevel): Color = when (level) {
    MatchLevel.HIGH -> colorScheme.primary
    MatchLevel.MEDIUM -> colorScheme.onSurface
    MatchLevel.LOW, MatchLevel.NONE -> colorScheme.onSurfaceVariant
}
