package com.shuaiqiu.fuckets100.feature.read

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveScaffold
import com.shuaiqiu.fuckets100.core.designsystem.expressiveTopAppBarColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 自动完成：作业列表 → 目标分 → 执行进度 → 结果与历史。
 *
 * 复用云端模式已有的账号会话，真实提交到 ETS 账号。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AutoCompleteScreen(
    accountOnline: Boolean = true,
    onOpenCloudLogin: () -> Unit = {},
    viewModel: AutoCompleteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(accountOnline) {
        if (accountOnline) viewModel.loadHomeworks(true)
    }

    ExpressiveScaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("自动完成") },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadHomeworks(accountOnline) },
                        enabled = !state.isRunning && !state.isLoading,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新作业列表")
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                ),
                colors = expressiveTopAppBarColors(),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.status == CloudHomeworkState.STATUS_CURRENT,
                        onClick = { viewModel.selectStatus(CloudHomeworkState.STATUS_CURRENT, accountOnline) },
                        label = { Text("当前作业") },
                        enabled = !state.isRunning,
                    )
                    FilterChip(
                        selected = state.status == CloudHomeworkState.STATUS_HISTORY,
                        onClick = { viewModel.selectStatus(CloudHomeworkState.STATUS_HISTORY, accountOnline) },
                        label = { Text("历史作业") },
                        enabled = !state.isRunning,
                    )
                }
            }

            if (!accountOnline) {
                item {
                    ErrorCard(
                        message = "请登录E听说账号",
                        actionLabel = "去登录",
                        onAction = onOpenCloudLogin,
                    )
                }
            }

            if (state.listError != null) {
                item {
                    ErrorCard(
                        message = state.listError.orEmpty(),
                        actionLabel = if (state.tokenExpired) "重新登录" else "重试",
                        onAction = {
                            if (state.tokenExpired) onOpenCloudLogin() else viewModel.loadHomeworks(accountOnline)
                        },
                    )
                }
            }

            item { TargetCard(state, viewModel) }
            item { RunCard(state, viewModel) }

            item {
                Text(
                    "作业列表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (state.isLoading) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (state.homeworks.isEmpty() && state.listError == null) {
                item {
                    Text(
                        "暂无作业，请点击右上角刷新，或先登录 E听说账号。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.homeworks, key = { cloudHomeworkIdentity(it) }) { homework ->
                HomeworkRow(
                    homework = homework,
                    selected = cloudHomeworkIdentity(homework) == state.selectedHomeworkId,
                    enabled = !state.isRunning,
                    onClick = { viewModel.selectHomework(cloudHomeworkIdentity(homework)) },
                )
            }

            state.result?.let { result ->
                item {
                    Text(
                        "本次结果",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item { ResultCard(result) }
            }

            if (state.history.isNotEmpty()) {
                item {
                    Text(
                        "历史记录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.history) { result ->
                    HistoryRow(result)
                }
            }
        }
    }
}

@Composable
private fun TargetCard(state: AutoCompleteUiState, viewModel: AutoCompleteViewModel) {
    val selected = state.selectedHomework
    val total = selected?.totalScore?.takeIf { it > 0 }
    val estimatedTarget = total?.let { it * state.targetRatio }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "目标分：${(state.targetRatio * 100).roundToInt()}% 卷面",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    selected == null -> "请先在下方选择一个作业"
                    total == null -> "该作业未提供卷面总分，将以题目分值汇总"
                    else -> "预计目标 ${formatScore(estimatedTarget ?: 0.0)} / ${formatScore(total)} 分（上限 95%）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = state.targetRatio,
                onValueChange = viewModel::setTargetRatio,
                valueRange = 0.3f..0.95f,
                enabled = !state.isRunning,
            )
        }
    }
}

@Composable
private fun RunCard(state: AutoCompleteUiState, viewModel: AutoCompleteViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.isRunning) {
                LinearProgressIndicator(
                    progress = { (state.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    state.stageLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.currentQuestion.isNotBlank()) {
                    Text(
                        "第 ${state.currentIndex}/${state.totalQuestions} 题：${state.currentQuestion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = viewModel::stop) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("终止任务")
                }
            } else {
                Text(
                    "将按目标档位自动分配逐题得分，并通过真实接口提交到当前 ETS 账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = viewModel::start,
                    enabled = state.selectedHomework != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("开始自动完成")
                }
            }
            if (state.error != null) {
                Text(
                    state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HomeworkRow(
    homework: ETS100ApiClient.HomeworkInfo,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                homework.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                if (homework.totalScore > 0) append("卷面 ${formatScore(homework.totalScore)} 分")
                homework.point?.let {
                    if (isNotEmpty()) append(" · ")
                    append("已得 ${formatScore(it)} 分")
                }
                homework.useTime.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("用时 $it 秒")
                }
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(result: AutoRunResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${result.homeworkName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "目标 ${formatScore(result.targetTotal)} 分 · 本次 ${formatScore(result.score)} / ${formatScore(result.maxScore)} 分",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append("得分率 ${(result.scoreRate * 100).roundToInt()}%")
                    result.officialPoint?.let { append(" · 云端回读 ${formatScore(it)} 分") }
                    append(" · 用时 ${result.useTime} 秒")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            result.officialSubmitTime?.let {
                Text("提交时间 $it", style = MaterialTheme.typography.bodySmall)
            }
            Text("完成于 ${result.completedAt}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryRow(result: AutoRunResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                result.homeworkName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatScore(result.score)} / ${formatScore(result.maxScore)} 分 · ${result.completedAt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun formatScore(value: Double): String {
    val rounded = value.roundToInt().toDouble()
    return if (kotlin.math.abs(value - rounded) < 0.0001) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}
