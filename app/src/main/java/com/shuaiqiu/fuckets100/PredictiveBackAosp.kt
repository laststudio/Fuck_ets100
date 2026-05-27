package com.shuaiqiu.fuckets100

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.math.max

internal enum class PredictiveBackExitDirection {
    FOLLOW_GESTURE,
    ALWAYS_RIGHT,
    ALWAYS_LEFT
}

internal class AospPredictiveBackState(
    private val exitDirection: PredictiveBackExitDirection = PredictiveBackExitDirection.FOLLOW_GESTURE
) {
    var latestBackEvent by mutableStateOf<BackEventCompat?>(null)
        private set

    var isGestureActive by mutableStateOf(false)
        private set

    val exitAnimatable = Animatable(0f)

    val progress: Float
        get() = max(latestBackEvent?.progress ?: 0f, exitAnimatable.value).coerceIn(0f, 1f)

    val isActive: Boolean
        get() = isGestureActive || exitAnimatable.value > 0f

    val directionMultiplier: Float
        get() {
            val edge = latestBackEvent?.swipeEdge ?: BackEventCompat.EDGE_LEFT
            return when (exitDirection) {
                PredictiveBackExitDirection.FOLLOW_GESTURE -> {
                    if (edge == BackEventCompat.EDGE_LEFT) 1f else -1f
                }
                PredictiveBackExitDirection.ALWAYS_RIGHT -> 1f
                PredictiveBackExitDirection.ALWAYS_LEFT -> -1f
            }
        }

    suspend fun handleBackGesture(
        progressEvents: Flow<BackEventCompat>,
        retainCompletedState: Boolean,
        onBack: () -> Unit
    ) {
        var completed = false
        try {
            isGestureActive = true
            exitAnimatable.snapTo(0f)

            progressEvents.collect { event ->
                latestBackEvent = event
            }

            exitAnimatable.snapTo(0f)
            exitAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
            )
            completed = true
            onBack()
        } catch (_: CancellationException) {
            exitAnimatable.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = LinearEasing)
            )
        } finally {
            if (!completed || !retainCompletedState) {
                latestBackEvent = null
                isGestureActive = false
                exitAnimatable.snapTo(0f)
            }
        }
    }
}

@Composable
internal fun rememberAospPredictiveBackState(): AospPredictiveBackState {
    return remember { AospPredictiveBackState() }
}

@Composable
internal fun AospPredictiveBackHandler(
    state: AospPredictiveBackState,
    enabled: Boolean,
    retainCompletedState: Boolean = false,
    onBack: () -> Unit
) {
    PredictiveBackHandler(enabled = enabled) { progress ->
        state.handleBackGesture(
            progressEvents = progress,
            retainCompletedState = retainCompletedState,
            onBack = onBack
        )
    }
}

@Composable
internal fun AospPredictiveBackContent(
    enabled: Boolean = true,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberAospPredictiveBackState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val predictiveBackOffsetPx = with(density) { 96.dp.toPx() }
        val deviceCornerRadius = rememberDeviceCornerRadius()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .aospPredictiveBackAnimation(
                    state = state,
                    containerHeightPx = containerHeightPx,
                    exitingOffsetPx = predictiveBackOffsetPx,
                    deviceCornerRadius = deviceCornerRadius
                )
        ) {
            content()
        }
    }

    AospPredictiveBackHandler(
        state = state,
        enabled = enabled,
        retainCompletedState = true,
        onBack = onBack
    )
}

internal fun Modifier.aospPredictiveBackAnimation(
    state: AospPredictiveBackState,
    containerHeightPx: Float,
    exitingOffsetPx: Float,
    deviceCornerRadius: Dp
): Modifier {
    if (!state.isActive) {
        return this
    }

    val backEvent = state.latestBackEvent
    val edge = backEvent?.swipeEdge ?: BackEventCompat.EDGE_LEFT
    val gestureProgress = state.progress
    val emphasizedExitProgress = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        .transform(state.exitAnimatable.value.coerceIn(0f, 1f))
    val maxScale = 0.85f
    val dragScale = 1f - (1f - maxScale) * gestureProgress
    val pivotX = if (edge == BackEventCompat.EDGE_LEFT) 0.8f else 0.2f
    val pivotY = if (backEvent != null && containerHeightPx > 0f) {
        (backEvent.touchY / containerHeightPx).coerceIn(0.1f, 0.9f)
    } else {
        0.5f
    }

    return this
        .graphicsLayer {
            transformOrigin = TransformOrigin(pivotX, pivotY)
            scaleX = dragScale
            scaleY = dragScale
            translationX = exitingOffsetPx * state.directionMultiplier * emphasizedExitProgress
            alpha = (1f - ((emphasizedExitProgress - 0.7f) / 0.3f)).coerceIn(0f, 1f)
        }
        .clip(RoundedCornerShape(deviceCornerRadius))
}

@Composable
internal fun rememberDeviceCornerRadius(defaultRadius: Dp = 16.dp): Dp {
    val view = LocalView.current
    val density = LocalDensity.current

    return remember(view, density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            if (insets != null) {
                val corner = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                    ?: insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                    ?: insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                    ?: insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)

                if (corner != null) {
                    with(density) {
                        return@remember corner.radius.toDp()
                    }
                }
            }
        }

        defaultRadius
    }
}
