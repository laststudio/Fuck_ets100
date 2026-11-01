package com.shuaiqiu.fuckets100.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shuaiqiu.fuckets100.FeAccountManager
import com.shuaiqiu.fuckets100.feature.shared.LastStudioUnavailableCard

/**
 * 步骤 2：账号登录。
 *
 * LastStudio 账号服务已停用，这里不再提供登录与注册入口，只提示后续在云端模式
 * 中登录 E 听说账号。
 */
@Composable
internal fun AccountStep(
    @Suppress("UNUSED_PARAMETER") accountManager: FeAccountManager,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "LastStudio 登录已停用",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        LastStudioUnavailableCard()
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("继续使用 E 听说账号")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("跳过，稍后登录 E 听说账号")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "云端模式与自动完成只需要 E 听说账号，LastStudio 登录与注册已关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
    }
}
