package com.shuaiqiu.fuckets100.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shuaiqiu.fuckets100.FeAccountManager
import com.shuaiqiu.fuckets100.feature.shared.LastStudioUnavailableCard

/**
 * LastStudio 账号已停用：这里不再提供登录表单，只给出提示。
 *
 * 云端模式所需的账号统一改为在云端模式页面登录 E 听说账号。
 */
@Composable
internal fun FeLoginScreen(
    @Suppress("UNUSED_PARAMETER") manager: FeAccountManager,
    onBack: () -> Unit = {},
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "LastStudio 登录已停用",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            LastStudioUnavailableCard()
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }
    }
}
