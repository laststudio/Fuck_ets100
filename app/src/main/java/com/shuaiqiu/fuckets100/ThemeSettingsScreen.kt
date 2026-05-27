package com.shuaiqiu.fuckets100

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    onThemeChanged: ((AppTheme) -> Unit)? = null,
    onDarkModeChanged: ((Boolean) -> Unit)? = null,
    onAutoDarkModeChanged: ((Boolean) -> Unit)? = null,
    onDynamicColorChanged: ((Boolean) -> Unit)? = null,
    onPredictiveBackChanged: ((PredictiveBackMode) -> Unit)? = null
) {
    val currentTheme = ThemeManager.getSavedTheme()
    var selectedTheme by remember { mutableStateOf(currentTheme) }
    var isDarkMode by remember { mutableStateOf(ThemeManager.getSavedDarkMode()) }
    var isAutoDarkMode by remember { mutableStateOf(ThemeManager.getSavedAutoDarkMode()) }
    var useDynamicColor by remember { mutableStateOf(ThemeManager.getSavedDynamicColor()) }
    var predictiveBackMode by remember { mutableStateOf(SettingsManager.getPredictiveBackMode()) }
    var showPredictiveBackDialog by remember { mutableStateOf(false) }
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val systemDarkMode = isSystemInDarkTheme()
    val effectiveDarkMode = if (isAutoDarkMode) systemDarkMode else isDarkMode
    
    val colorThemes = ThemeManager.getColorThemes()
    
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("主题设置", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Palette, null)
                    }
                }
            )
        }
    ) { p ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(p)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(16.dp))
            
            Text(
                "颜色主题", 
                style = MaterialTheme.typography.titleMedium, 
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "选择应用主色调",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(colorThemes) { theme ->
                    ThemeCard(
                        theme = theme,
                        isSelected = selectedTheme == theme,
                        enabled = !(useDynamicColor && dynamicColorAvailable),
                        onClick = {
                            selectedTheme = theme
                            ThemeManager.saveTheme(theme)
                            onThemeChanged?.invoke(theme)
                        }
                    )
                }
            }

            ListItem(
                headlineContent = { Text("从壁纸提取主题色") },
                supportingContent = {
                    Text(
                        if (dynamicColorAvailable) {
                            "使用系统 Material You 动态配色"
                        } else {
                            "需要 Android 12 或更高版本"
                        }
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.Wallpaper, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = useDynamicColor && dynamicColorAvailable,
                        enabled = dynamicColorAvailable,
                        onCheckedChange = { checked ->
                            useDynamicColor = checked
                            ThemeManager.saveDynamicColor(checked)
                            onDynamicColorChanged?.invoke(checked)
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(Modifier.height(24.dp))
            
            Text(
                "显示模式", 
                style = MaterialTheme.typography.titleMedium, 
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "在当前颜色主题上切换日间或夜间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            ListItem(
                headlineContent = { Text("跟随系统") },
                supportingContent = { Text(if (systemDarkMode) "当前系统为夜间模式" else "当前系统为日间模式") },
                leadingContent = {
                    Icon(Icons.Default.Sync, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = isAutoDarkMode,
                        onCheckedChange = { checked ->
                            isAutoDarkMode = checked
                            ThemeManager.saveAutoDarkMode(checked)
                            onAutoDarkModeChanged?.invoke(checked)
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                SegmentedButton(
                    selected = !isDarkMode,
                    enabled = !isAutoDarkMode,
                    onClick = {
                        isDarkMode = false
                        ThemeManager.saveDarkMode(false)
                        onDarkModeChanged?.invoke(false)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = themeModeSegmentedButtonColors(),
                    icon = { Icon(Icons.Default.LightMode, contentDescription = null) },
                    label = { Text("日间") }
                )
                SegmentedButton(
                    selected = isDarkMode,
                    enabled = !isAutoDarkMode,
                    onClick = {
                        isDarkMode = true
                        ThemeManager.saveDarkMode(true)
                        onDarkModeChanged?.invoke(true)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = themeModeSegmentedButtonColors(),
                    icon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                    label = { Text("夜间") }
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "返回手势",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "切换自定义 AOSP 动画或系统默认动画",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            ListItem(
                headlineContent = { Text("预见性返回动画") },
                supportingContent = {
                    Text("${predictiveBackMode.label} · ${predictiveBackMode.description}")
                },
                leadingContent = {
                    Icon(Icons.Default.Sync, contentDescription = null)
                },
                trailingContent = {
                    Text(
                        predictiveBackMode.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { showPredictiveBackDialog = true }
            )
            
            Spacer(Modifier.height(32.dp))
            
            // 预览区域
            Text(
                "主题预览", 
                style = MaterialTheme.typography.titleMedium, 
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(Modifier.height(12.dp))
            
            ThemePreviewCard(
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(Modifier.height(88.dp))
        }
    }

    if (showPredictiveBackDialog) {
        AlertDialog(
            onDismissRequest = { showPredictiveBackDialog = false },
            title = { Text("预见性返回动画") },
            text = {
                Column {
                    PredictiveBackMode.entries.forEach { mode ->
                        ListItem(
                            headlineContent = { Text(mode.label) },
                            supportingContent = { Text(mode.description) },
                            trailingContent = {
                                if (predictiveBackMode == mode) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                predictiveBackMode = mode
                                SettingsManager.savePredictiveBackMode(mode)
                                showPredictiveBackDialog = false
                                onPredictiveBackChanged?.invoke(mode)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPredictiveBackDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun ThemeCard(
    theme: AppTheme,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(100.dp)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.45f
            }
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                theme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 主色调预览圆
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(theme.primary)
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = theme.onPrimary,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center)
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                theme.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) theme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ThemePreviewCard(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Palette, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Material 3 配色",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        "当前应用正在使用的 ColorScheme",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorRoleChip(
                        label = "Primary",
                        color = colors.primary,
                        contentColor = colors.onPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    ColorRoleChip(
                        label = "Secondary",
                        color = colors.secondary,
                        contentColor = colors.onSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorRoleChip(
                        label = "Tertiary",
                        color = colors.tertiary,
                        contentColor = colors.onTertiary,
                        modifier = Modifier.weight(1f)
                    )
                    ColorRoleChip(
                        label = "Error",
                        color = colors.errorContainer,
                        contentColor = colors.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                contentColor = colors.onSurface,
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Surface",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "背景、容器、文字和按钮都会跟随这里的实际主题色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { }) {
                            Text("Filled")
                        }
                        FilledTonalButton(onClick = { }) {
                            Text("Tonal")
                        }
                        OutlinedButton(onClick = { }) {
                            Text("Outlined")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceContainerHighest),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.38f)
                        .background(colors.primary)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.28f)
                        .background(colors.secondary)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.22f)
                        .background(colors.tertiary)
                )
            }
        }
    }
}

@Composable
private fun ColorRoleChip(
    label: String,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(14.dp),
        color = color,
        contentColor = contentColor
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun themeModeSegmentedButtonColors(): SegmentedButtonColors {
    return SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledActiveContainerColor = MaterialTheme.colorScheme.primaryContainer,
        disabledActiveContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledInactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        disabledInactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    )
}
