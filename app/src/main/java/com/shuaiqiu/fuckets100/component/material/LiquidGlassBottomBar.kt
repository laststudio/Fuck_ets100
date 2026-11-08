package com.shuaiqiu.fuckets100.component.material

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.shuaiqiu.fuckets100.component.material.liquidglass.LiquidGlassSurface

/** 液态玻璃导航栏的单个条目。 */
internal data class LiquidNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val BarHeight = 58.dp
private val BarCorner = 29.dp

/** 胶囊上下留出的空间，给阴影用。 */
private val OverlayPadding = 10.dp

/** 玻璃块刚好盖住图标与文字，比胶囊矮一圈，宽度拉到 72dp。 */
private val ThumbWidth = 72.dp
private val ThumbHeight = 46.dp
private val ThumbCorner = 23.dp
private val ThumbCenterY = 42.dp

/** 块内内容的放大倍数：图标与文字整体被放大，保持扁平不变形。 */
private const val ThumbContentScale = 1.12f

private val NavGlyph = 21.dp
private val NavIconOffset = (-6).dp
private val NavLabelSize = 11.sp

/**
 * 扁平的液态玻璃底栏。
 *
 * 选中位置是一枚半透明白色高光，夹在磨砂胶囊与页签之间，图标保持清晰；
 * 长按底栏可左右拖动高光，松手吸附到最近的页签。
 */
@Composable
internal fun LiquidGlassBottomBar(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val selected = selectedIndex.coerceIn(0, items.lastIndex)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = 18.dp)
            .height(BarHeight + OverlayPadding * 2),
        contentAlignment = Alignment.Center,
    ) {
        val contentWidthPx = with(density) { maxWidth.toPx() }
        val slotWidthPx = contentWidthPx / items.size
        val thumbHalfWidthPx = with(density) { ThumbWidth.toPx() } / 2f
        val thumbHalfHeightPx = with(density) { ThumbHeight.toPx() } / 2f
        val thumbCenterYPx = with(density) { ThumbCenterY.toPx() }

        // 镜头位置：点击切换时用弹簧，长按拖动时直接跟随手指。
        val lensIndex = remember { Animatable(selected.toFloat()) }
        var dragX by remember { mutableFloatStateOf(Float.NaN) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(selected) {
            lensIndex.animateTo(
                targetValue = selected.toFloat(),
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }

        LiquidGlassSurface(
            lensRect = {
                val centerX = if (dragX.isFinite()) dragX else (lensIndex.value + 0.5f) * slotWidthPx
                Rect(
                    left = centerX - thumbHalfWidthPx,
                    top = thumbCenterYPx - thumbHalfHeightPx,
                    right = centerX + thumbHalfWidthPx,
                    bottom = thumbCenterYPx + thumbHalfHeightPx,
                )
            },
            cornerRadius = ThumbCorner,
            contentScale = ThumbContentScale,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items.size, slotWidthPx) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            dragX = offset.x.coerceIn(slotWidthPx / 2f, contentWidthPx - slotWidthPx / 2f)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragX = (dragX + amount.x)
                                .coerceIn(slotWidthPx / 2f, contentWidthPx - slotWidthPx / 2f)
                        },
                        onDragEnd = {
                            val target = (dragX / slotWidthPx)
                                .toInt()
                                .coerceIn(0, items.lastIndex)
                            onSelect(target)
                            scope.launch {
                                lensIndex.snapTo(target.toFloat())
                                dragX = Float.NaN
                                lensIndex.animateTo(
                                    targetValue = target.toFloat(),
                                    animationSpec = spring(),
                                )
                            }
                        },
                        onDragCancel = {
                            val target = selected
                            scope.launch {
                                lensIndex.snapTo(target.toFloat())
                                dragX = Float.NaN
                            }
                        },
                    )
                },
            background = {
                FrostedCapsule(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(BarHeight),
                )
            },
            content = {
                NavRow(
                    items = items,
                    selected = selected,
                    onSelect = onSelect,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(BarHeight),
                )
            },
        )
    }
}

/** 四个页签。 */
@Composable
private fun NavRow(
    items: List<LiquidNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selected
            val interactionSource = remember { MutableInteractionSource() }
            val itemModifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics { contentDescription = item.label }
                .selectable(
                    selected = isSelected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Tab,
                    onClick = { onSelect(index) },
                )

            Box(
                modifier = itemModifier,
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isSelected) item.selectedIcon else item.icon,
                    contentDescription = null,
                    tint = if (isSelected) {
                        colors.onSurface
                    } else {
                        colors.onSurface.copy(alpha = 0.72f)
                    },
                    modifier = Modifier
                        .offset(y = NavIconOffset)
                        .size(NavGlyph),
                )
                Text(
                    text = item.label,
                    fontSize = NavLabelSize,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    color = if (isSelected) {
                        colors.onSurface
                    } else {
                        colors.onSurface.copy(alpha = 0.66f)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 5.dp),
                )
            }
        }
    }
}

/** 底栏的磨砂胶囊底：半透明表面色 + 顶部高光 + 白色玻璃边 + 下方柔和投影。 */
@Composable
private fun FrostedCapsule(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.drawWithCache {
            val shape = RoundedCornerShape(BarCorner)
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val fill = Brush.verticalGradient(
                0f to colors.surface.copy(alpha = 0.78f),
                0.14f to colors.surface.copy(alpha = 0.66f),
                0.55f to colors.surface.copy(alpha = 0.62f),
                1f to colors.surface.copy(alpha = 0.74f),
            )
            val sheen = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.34f),
                0.20f to Color.White.copy(alpha = 0.08f),
                0.46f to Color.Transparent,
            )
            val rim = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.92f),
                0.16f to Color.White.copy(alpha = 0.18f),
                0.68f to colors.outlineVariant.copy(alpha = 0.10f),
                1f to colors.outlineVariant.copy(alpha = 0.38f),
            )
            val rimStroke = Stroke(width = 1.dp.toPx())
            val shadowBrush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.12f),
                0.62f to Color.Black.copy(alpha = 0.05f),
                1f to Color.Transparent,
                center = Offset(size.width / 2f, size.height * 1.06f),
                radius = size.width * 0.56f,
            )
            onDrawBehind {
                drawOval(
                    brush = shadowBrush,
                    topLeft = Offset(size.width * 0.10f, size.height * 0.86f),
                    size = Size(size.width * 0.80f, size.height * 0.62f),
                )
                drawPath(path, fill)
                drawPath(path, sheen)
                drawPath(path, rim, style = rimStroke)
            }
        },
    ) {}
}
