package com.shuaiqiu.fuckets100.feature.onboarding

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.shuaiqiu.fuckets100.feature.onboarding.R
import com.shuaiqiu.fuckets100.LegalAgreement
import com.shuaiqiu.fuckets100.core.designsystem.RighteousFont
import com.shuaiqiu.fuckets100.feature.shared.LegalContent
import kotlinx.coroutines.launch

import com.shuaiqiu.fuckets100.SettingsManager
// ============================================================================
// 步骤 1: 欢迎与幽默标语 + 用户协议
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeStep(
    onNext: () -> Unit,
    onRefreshLegalAgreement: () -> Unit,
    currentAgreement: LegalAgreement,
) {
    val context = LocalContext.current
    var entered by remember { mutableStateOf(false) }
    var legalAccepted by rememberSaveable { mutableStateOf(SettingsManager.hasAcceptedLegal()) }
    var showLegalSheet by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        entered = true
        onRefreshLegalAgreement()
    }

    // Logo 弹性缩放与渐显
    val logoScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "logo_scale",
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(400),
        label = "logo_alpha",
    )

    // 标语文本渐显与上移
    val textAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(400, delayMillis = 160),
        label = "text_alpha",
    )
    val textOffsetY by animateFloatAsState(
        targetValue = if (entered) 0f else 24f,
        animationSpec = tween(400, delayMillis = 160),
        label = "text_offset_y",
    )

    // 底部操作区渐显与上移
    val bottomAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(400, delayMillis = 280),
        label = "bottom_alpha",
    )
    val bottomOffsetY by animateFloatAsState(
        targetValue = if (entered) 0f else 20f,
        animationSpec = tween(400, delayMillis = 280),
        label = "bottom_offset_y",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .navigationBarsPadding(),
    ) {
        // 中间主体（Logo + 标题 + 幽默标语），居中稳定布局
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 图标展示 (Spring 缩放 + 淡入)
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                        alpha = logoAlpha
                    }
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.fe_logo),
                    contentDescription = "Fuck ETS100",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(MaterialTheme.shapes.large),
                )
            }

            Spacer(Modifier.height(24.dp))

            // 标题与幽默标语 (平滑位移与淡入，不改变占位大小)
            Column(
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha
                    translationY = textOffsetY
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Fuck ETS100",
                    style = MaterialTheme.typography.headlineLarge.copy(fontFamily = RighteousFont),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "把做口语的痛苦留给算法，把大好青春还给生活喵~",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 底部协议与行动按钮，固定底部锚点
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                .graphicsLayer {
                    alpha = bottomAlpha
                    translationY = bottomOffsetY
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 协议勾选行 (带 shakeOffset 左右抖动提醒，不产生纵向位移)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = shakeOffset.value
                    }
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { legalAccepted = !legalAccepted }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = legalAccepted,
                    onCheckedChange = { legalAccepted = it },
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "已阅读并同意",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "《用户协议与全链路免责声明》",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showLegalSheet = true },
                )
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    if (legalAccepted) {
                        onNext()
                    } else {
                        Toast.makeText(context, "请先阅读并勾选同意用户协议喵~", Toast.LENGTH_SHORT).show()
                        coroutineScope.launch {
                            shakeOffset.snapTo(0f)
                            shakeOffset.animateTo(14f, tween(50))
                            shakeOffset.animateTo(-14f, tween(50))
                            shakeOffset.animateTo(10f, tween(50))
                            shakeOffset.animateTo(-10f, tween(50))
                            shakeOffset.animateTo(0f, tween(50))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text("开启旅程", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    // 用户协议查看抽屉
    if (showLegalSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLegalSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "用户协议与全链路免责声明",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = { showLegalSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    LegalContent(
                        showHeader = false,
                        agreement = currentAgreement,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        legalAccepted = true
                        showLegalSheet = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("我已阅读并知悉")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
