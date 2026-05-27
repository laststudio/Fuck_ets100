package com.shuaiqiu.fuckets100

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    navController: NavHostController,
    onThemeChanged: ((AppTheme) -> Unit)? = null,
    onDarkModeChanged: ((Boolean) -> Unit)? = null,
    onAutoDarkModeChanged: ((Boolean) -> Unit)? = null,
    onDynamicColorChanged: ((Boolean) -> Unit)? = null
) {
    val currentTheme = ThemeManager.getSavedTheme()
    var selectedTheme by remember { mutableStateOf(currentTheme) }
    var isDarkMode by remember { mutableStateOf(ThemeManager.getSavedDarkMode()) }
    var isAutoDarkMode by remember { mutableStateOf(ThemeManager.getSavedAutoDarkMode()) }
    var useDynamicColor by remember { mutableStateOf(ThemeManager.getSavedDynamicColor()) }
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val systemDarkMode = isSystemInDarkTheme()
    val effectiveDarkMode = if (isAutoDarkMode) systemDarkMode else isDarkMode
    
    val colorThemes = ThemeManager.getColorThemes()
    
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("主题设置", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
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
                theme = selectedTheme,
                isDarkMode = effectiveDarkMode,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(Modifier.height(88.dp))
        }
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
fun ThemePreviewCard(theme: AppTheme, isDarkMode: Boolean, modifier: Modifier = Modifier) {
    val primary = if (isDarkMode) theme.primary else previewBlend(theme.primary, Color.Black, 0.38f)
    val primaryContainer = if (isDarkMode) {
        theme.primaryContainer
    } else {
        previewBlend(theme.primary, Color.White, 0.78f)
    }
    val background = if (isDarkMode) {
        previewBlend(Color(0xFF111014), theme.primary, 0.055f)
    } else {
        previewBlend(Color(0xFFFFFBFE), theme.primary, 0.045f)
    }
    val surface = if (isDarkMode) {
        previewBlend(Color(0xFF141218), theme.primary, 0.05f)
    } else {
        previewBlend(Color(0xFFFFFBFE), theme.primary, 0.03f)
    }
    val onSurface = if (isDarkMode) Color(0xFFE6E0E9) else Color(0xFF1C1B1F)
    val onSurfaceVariant = if (isDarkMode) Color(0xFFCAC4D0) else Color(0xFF49454F)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(surface, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            // 标题预览
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(primary)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Fe 终端",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onSurface
                    )
                    Text(
                        "应用预览",
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant
                    )
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // 按钮预览
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primary,
                        contentColor = if (isDarkMode) theme.onPrimary else Color.White
                    )
                ) {
                    Text("主要按钮")
                }
                
                OutlinedButton(onClick = { }) {
                    Text("次要按钮")
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // 状态指示器
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(primaryContainer),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 模拟进度
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.7f)
                        .background(primary)
                )
            }
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

private fun previewBlend(from: Color, to: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t
    )
}
