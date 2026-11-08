package com.shuaiqiu.fuckets100.component.material

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 液态玻璃导航栏的单个条目。
 */
internal data class LiquidNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val BarHeight = 58.dp
private val LensHeight = 72.dp
private val LensSize = 68.dp
private val BarCorner = 30.dp

/**
 * 液态玻璃底栏：内部半透明放大 + 外圈折射高光。
 *
 * 选中项用一枚圆形「镜头」表现：中心是放大的图标，镜片本身是透明白色径向渐变，
 * 外圈用带色散的 sweep 渐变描边模拟折射，再用左上角的镜面高光收口。
 */
@Composable
internal fun LiquidGlassBottomBar(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(BarCorner)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(LensHeight),
        contentAlignment = Alignment.Center,
    ) {
        // 胶囊玻璃底
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .drawBehind { drawGlassCapsule(shape, colors.surface, colors.primary) }
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                LiquidGlassNavItem(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LiquidGlassNavItem(
    item: LiquidNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val lensScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow),
        label = "lensScale",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.16f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
        label = "iconScale",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (lensScale > 0.01f) {
            Box(
                modifier = Modifier
                    .size(LensSize)
                    .graphicsLayer {
                        scaleX = 0.72f + 0.28f * lensScale
                        scaleY = 0.72f + 0.28f * lensScale
                        alpha = lensScale
                    }
                    .drawBehind { drawLens(colors.primary) }
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = if (selected) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.78f),
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                color = if (selected) {
                    colors.onSurface
                } else {
                    colors.onSurfaceVariant.copy(alpha = 0.72f)
                },
            )
        }
    }
}

/** 胶囊玻璃：竖向渐变底色 + 顶部高光 + 一圈带色散的折射描边。 */
private fun DrawScope.drawGlassCapsule(shape: Shape, surface: Color, accent: Color) {
    val radius = BarCorner.toPx()
    val corner = CornerRadius(radius, radius)
    val rect = Rect(Offset.Zero, size)
    val outline = shape.createOutline(size, layoutDirection, this)
    val round = (outline as? androidx.compose.ui.graphics.Outline.Rounded)?.roundRect
        ?: RoundRect(rect, corner, corner)

    drawPath(
        path = androidx.compose.ui.graphics.Path().apply { addRoundRect(round) },
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to surface.copy(alpha = 0.86f),
                0.55f to surface.copy(alpha = 0.68f),
                1f to surface.copy(alpha = 0.80f),
            ),
        ),
    )
    drawPath(
        path = androidx.compose.ui.graphics.Path().apply { addRoundRect(round) },
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.34f),
                0.42f to Color.White.copy(alpha = 0.05f),
                1f to Color.White.copy(alpha = 0f),
            ),
        ),
    )
    drawPath(
        path = androidx.compose.ui.graphics.Path().apply { addRoundRect(round) },
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.62f),
                Color.White.copy(alpha = 0.08f),
                accent.copy(alpha = 0.20f),
                Color.White.copy(alpha = 0.46f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** 镜头：透明径向渐变本体 + 色散折射外圈 + 镜面高光。 */
private fun DrawScope.drawLens(accent: Color) {
    val radius = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val bodyRadius = radius - 1.5.dp.toPx()

    // 外圈柔光
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to accent.copy(alpha = 0.20f),
                0.72f to accent.copy(alpha = 0.06f),
                1f to Color.Transparent,
            ),
            center = center,
            radius = radius * 1.08f,
        ),
        radius = radius * 1.08f,
        center = center,
    )
    // 透明白色镜片本体（内部放大感来自图标层级缩放）
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.52f),
                0.58f to Color.White.copy(alpha = 0.24f),
                1f to Color.White.copy(alpha = 0.10f),
            ),
            center = Offset(center.x, center.y * 0.82f),
            radius = bodyRadius,
        ),
        radius = bodyRadius,
        center = center,
    )
    // 色散折射外圈
    drawCircle(
        brush = Brush.sweepGradient(
            0f to Color.White.copy(alpha = 0.55f),
            0.14f to Color(0x33A8E6FF),
            0.28f to Color.White.copy(alpha = 0.12f),
            0.42f to Color(0x3AFF9DE2),
            0.56f to Color.White.copy(alpha = 0.50f),
            0.70f to Color(0x2E9DFFE0),
            0.86f to Color.White.copy(alpha = 0.16f),
            1f to Color.White.copy(alpha = 0.55f),
            center = center,
        ),
        radius = bodyRadius,
        center = center,
        style = Stroke(width = 1.6.dp.toPx()),
    )
    // 左上镜面高光
    val specularCenter = Offset(size.width * 0.33f, size.height * 0.24f)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.85f),
                1f to Color.Transparent,
            ),
            center = specularCenter,
            radius = radius * 0.40f,
        ),
        radius = radius * 0.40f,
        center = specularCenter,
    )
}
