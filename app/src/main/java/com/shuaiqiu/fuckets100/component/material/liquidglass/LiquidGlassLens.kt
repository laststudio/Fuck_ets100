package com.shuaiqiu.fuckets100.component.material.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 扁平的液态玻璃块。
 *
 * 三层叠出来：
 * 1. 底栏正常内容，玻璃块所在位置挖空；
 * 2. 同一份内容的放大副本，裁在玻璃块形状里 —— 这就是「内部放大」；
 * 3. 半透明白色玻璃面 + 左上柔光 + 细白边 —— 扁平，不做折射与色散。
 *
 * 位置与（可拖动的）中心都由 [lensRect] 在绘制阶段读取。
 */
@Composable
internal fun LiquidGlassSurface(
    lensRect: () -> Rect,
    cornerRadius: Dp,
    contentScale: Float,
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }

    Box(modifier = modifier) {
        // 1. 正常底栏，只挖掉玻璃块那一块，避免与放大副本重影。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val rect = lensRect()
                    if (!rect.isUsable()) {
                        drawContent()
                        return@drawWithContent
                    }
                    clipPath(lensPath(rect, radiusPx), clipOp = ClipOp.Difference) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            background()
            content()
        }

        // 2. 放大后的同一份内容，裁在玻璃块里。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val rect = lensRect()
                    if (!rect.isUsable()) return@drawWithContent
                    clipPath(lensPath(rect, radiusPx)) {
                        this@drawWithContent.drawContent()
                    }
                }
                .graphicsLayer {
                    val rect = lensRect()
                    if (rect.isUsable() && size.width > 0f && size.height > 0f) {
                        transformOrigin = TransformOrigin(
                            pivotFractionX = rect.center.x / size.width,
                            pivotFractionY = rect.center.y / size.height,
                        )
                        scaleX = contentScale
                        scaleY = contentScale
                    }
                }
                .clearAndSetSemantics {},
        ) {
            background()
            content()
        }

        // 3. 扁平玻璃面。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent { drawGlassFace(lensRect(), radiusPx) },
        )
    }
}

private fun Rect.isUsable(): Boolean =
    width > 0f && height > 0f && !width.isNaN() && !height.isNaN()

private fun lensPath(rect: Rect, radiusPx: Float): Path =
    Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radiusPx))) }

private fun DrawScope.drawGlassFace(rect: Rect, radiusPx: Float) {
    if (!rect.isUsable()) return

    val path = lensPath(rect, radiusPx)

    clipPath(path) {
        // 半透明玻璃面：上部略浓，底部更透，保证里面的图标清楚。
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.44f),
                0.15f to Color.White.copy(alpha = 0.10f),
                0.55f to Color.White.copy(alpha = 0.01f),
                1f to Color.White.copy(alpha = 0.05f),
                startY = rect.top,
                endY = rect.bottom,
            ),
            topLeft = rect.topLeft,
            size = rect.size,
        )
        // 左上柔光
        drawRect(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.32f),
                0.5f to Color.White.copy(alpha = 0.05f),
                0.82f to Color.Transparent,
                center = Offset(rect.left + rect.width * 0.30f, rect.top + rect.height * 0.12f),
                radius = rect.width * 0.62f,
            ),
            topLeft = rect.topLeft,
            size = rect.size,
        )
        // 底部一丝压深，让平面有一点厚度
        drawRect(
            brush = Brush.verticalGradient(
                0.68f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.06f),
                startY = rect.top,
                endY = rect.bottom,
            ),
            topLeft = rect.topLeft,
            size = rect.size,
        )
    }

    drawRoundRect(
        color = Color.White.copy(alpha = 0.9f),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(radiusPx),
        style = Stroke(width = 1.dp.toPx()),
    )
}
