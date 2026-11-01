package com.shuaiqiu.fuckets100.component.material

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.shuaiqiu.fuckets100.Screen
import kotlinx.coroutines.launch

import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveScaffold
private data class RootDestination(
    val screen: Screen,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val rootDestinations = listOf(
    RootDestination(Screen.Home, Icons.Filled.Home, Icons.Outlined.Home),
    RootDestination(Screen.Read, Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    RootDestination(Screen.Auto, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    RootDestination(Screen.Settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun FeRootNavigationScaffold(
    showRootNavigation: Boolean,
    selectedPage: Int,
    onNavigatePage: (Int) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (showRootNavigation && shouldShowNavigationRail()) {
        Row {
            RootNavigationRail(selectedPage = selectedPage, onNavigatePage = onNavigatePage)
            ExpressiveScaffold(
                modifier = Modifier.weight(1f),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                content = content,
            )
        }
    } else {
        ExpressiveScaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (showRootNavigation) {
                    RootBottomBar(selectedPage = selectedPage, onNavigatePage = onNavigatePage)
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RootBottomBar(
    selectedPage: Int,
    onNavigatePage: (Int) -> Unit,
) {
    FlexibleBottomAppBar(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        rootDestinations.forEachIndexed { index, destination ->
            val selected = selectedPage == index
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onNavigatePage(index) },
                icon = {
                    Icon(
                        if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = destination.screen.label,
                    )
                },
                label = {
                    Text(
                        destination.screen.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                },
                alwaysShowLabel = false,
            )
        }
    }
}

@Composable
private fun RootNavigationRail(
    selectedPage: Int,
    onNavigatePage: (Int) -> Unit,
) {
    val state = rememberWideNavigationRailState()
    val scope = rememberCoroutineScope()
    val expanded = state.targetValue == WideNavigationRailValue.Expanded

    WideNavigationRail(
        modifier = Modifier.fillMaxHeight(),
        state = state,
        colors = WideNavigationRailDefaults.colors().copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
            WindowInsetsSides.Start + WindowInsetsSides.Vertical,
        ),
        contentPadding = PaddingValues(vertical = 20.dp),
        header = {
            IconButton(onClick = {
                scope.launch {
                    if (expanded) state.collapse() else state.expand()
                }
            }) {
                Icon(Icons.Default.Menu, if (expanded) "收起导航" else "展开导航")
            }
        },
    ) {
        rootDestinations.forEachIndexed { index, destination ->
            val selected = selectedPage == index
            WideNavigationRailItem(
                railExpanded = expanded,
                selected = selected,
                onClick = { if (!selected) onNavigatePage(index) },
                icon = {
                    Icon(
                        if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = destination.screen.label,
                    )
                },
                label = { Text(destination.screen.label) },
            )
        }
    }
}

@Composable
private fun shouldShowNavigationRail(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val density = LocalResources.current.displayMetrics.density
    val widthDp = windowInfo.containerSize.width / density
    val heightDp = windowInfo.containerSize.height / density
    return widthDp >= 840f || (widthDp >= 600f && heightDp / widthDp < 1.2f)
}
