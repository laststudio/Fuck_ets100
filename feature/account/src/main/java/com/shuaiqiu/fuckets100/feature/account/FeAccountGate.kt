package com.shuaiqiu.fuckets100.feature.account

import androidx.compose.runtime.Composable

/**
 * 账号门禁。
 *
 * LastStudio 账号服务已停用，不再发起 LastStudio 的联网校验；这里只保留可扩展的
 * 门禁入口，直接放行内容，避免在云端模式切换界面时触发已停用的接口。
 */
@Composable
fun FeAccountGate(content: @Composable () -> Unit) {
    content()
}
