package com.shuaiqiu.fuckets100.feature.shared

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shuaiqiu.fuckets100.core.designsystem.TonalCard

/** 统一的 LastStudio 停用提示，所有登录/注册入口都复用它。 */
const val LAST_STUDIO_UNAVAILABLE_MESSAGE =
    "LastStudio 账号服务已不可用，请不要登录；若需云端模式，请登录 E 听说账号。"

const val LAST_STUDIO_UNAVAILABLE_DETAIL =
    "LastStudio 登录与注册入口已关闭。云端模式与自动完成只需要登录 E 听说账号。"

fun showLastStudioUnavailable(context: Context) {
    Toast.makeText(context, LAST_STUDIO_UNAVAILABLE_MESSAGE, Toast.LENGTH_LONG).show()
}

/**
 * 登录页/引导页使用的停用提示卡片。
 *
 * 只提示，不提供任何 LastStudio 登录或注册入口。
 */
@Composable
fun LastStudioUnavailableCard(
    modifier: Modifier = Modifier,
    detail: String = LAST_STUDIO_UNAVAILABLE_DETAIL,
) {
    TonalCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = LAST_STUDIO_UNAVAILABLE_MESSAGE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
