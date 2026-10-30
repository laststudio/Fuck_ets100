package com.shuaiqiu.fuckets100.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveScaffold
import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveSegmentedListItem
import com.shuaiqiu.fuckets100.core.designsystem.SegmentedColumn
import com.shuaiqiu.fuckets100.core.designsystem.expressiveTopAppBarColors

/**
 * 设置页：只保留与本地读取有关的配置（运行授权 / 通用 / 主题）。
 * 账号、官网、关于、捐赠、公告等入口已全部移除。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onNavigateActivation: () -> Unit,
    onNavigateGeneralSettings: () -> Unit,
    onNavigateThemeSettings: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    ExpressiveScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("设置") },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { p ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(p)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(16.dp))

            SegmentedColumn(
                title = "本地读取",
                items = listOf(
                    {
                        SettingsSegmentedItem(
                            index = 0,
                            count = 3,
                            icon = Icons.Default.Build,
                            title = "运行授权",
                            supportingText = "Shizuku / Root / 漏洞直读",
                            onClick = onNavigateActivation,
                        )
                    },
                    {
                        SettingsSegmentedItem(
                            index = 1,
                            count = 3,
                            icon = Icons.Default.Tune,
                            title = "通用设置",
                            supportingText = "配置答案显示方式",
                            onClick = onNavigateGeneralSettings,
                        )
                    },
                    {
                        SettingsSegmentedItem(
                            index = 2,
                            count = 3,
                            icon = Icons.Default.Palette,
                            title = "主题",
                            supportingText = "彩色主题与明暗模式",
                            onClick = onNavigateThemeSettings,
                        )
                    },
                ),
            )

            Spacer(Modifier.height(88.dp))
        }
    }
}

@Composable
private fun SettingsSegmentedItem(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    supportingText: String,
    onClick: () -> Unit,
) {
    ExpressiveSegmentedListItem(
        index = index,
        count = count,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(supportingText) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
