package com.shuaiqiu.fuckets100.feature.account

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shuaiqiu.fuckets100.FeAccountGateState
import com.shuaiqiu.fuckets100.cachedSessionOrNull
import com.shuaiqiu.fuckets100.core.designsystem.SegmentedColumn
import com.shuaiqiu.fuckets100.feature.shared.ACCOUNT_CENTER_URL
import com.shuaiqiu.fuckets100.feature.shared.AccountInitialAvatar
import com.shuaiqiu.fuckets100.feature.shared.AccountViewModel
import kotlinx.coroutines.launch

@Composable
fun FeAccountScreen(
    onBack: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToBindings: (isEtingshuo: Boolean) -> Unit,
    onNavigateToChangePassword: () -> Unit,
    onLogoutNavigate: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
    bindingsViewModel: AccountBindingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val manager = viewModel.manager
    val state by manager.gateState.collectAsState()
    val session = state.cachedSessionOrNull
    val bindingsState by bindingsViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var showProfileDetails by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var isLoggingOut by remember { mutableStateOf(false) }
    var logoutError by remember { mutableStateOf<String?>(null) }

    // LastStudio 账号服务已停用：不再发起任何 LastStudio 校验，直接提示用户改用 E 听说账号。
    if (session == null) {
        FeLoginScreen(manager, onBack)
        return
    }

    LaunchedEffect(state is FeAccountGateState.Ready) {
        if (state is FeAccountGateState.Ready) bindingsViewModel.refresh()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bindingsViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && manager.gateState.value is FeAccountGateState.Ready) {
                bindingsViewModel.refresh(force = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val bindings = bindingsState.bindings
    val emailStatus = when {
        bindings == null -> if (bindingsState.error != null) "暂不可用" else if (bindingsState.isRefreshing) "加载中" else "未知"
        bindings.email == null -> "未绑定"
        bindings.email?.isVerified == true -> "已验证"
        else -> "未验证"
    }
    val etsStatus = when {
        bindings == null -> if (bindingsState.error != null) "暂不可用" else if (bindingsState.isRefreshing) "加载中" else "未知"
        bindings.etingshuo == null -> "未绑定"
        else -> "已绑定"
    }

    AccountPageScaffold(
        title = "个人中心",
        onBack = onBack,
        isRefreshing = bindingsState.isRefreshing,
        onRefresh = { bindingsViewModel.refresh(force = true) },
    ) {
        if (state is FeAccountGateState.Offline || state is FeAccountGateState.Checking) {
            AccountMessage("LastStudio 账号服务已停用，以下为本地缓存的账号信息")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "查看账号资料") { showProfileDetails = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountInitialAvatar(
                username = session.user.username,
                size = 64,
                avatarUrl = session.user.avatarUrl,
                userId = session.user.id,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(session.user.username, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${session.user.tierDisplayName} · LastStudio",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        bindingsState.error?.let { AccountMessage(it, isError = true) }
        SegmentedColumn(
            title = "账号绑定",
            items = listOf(
                { AccountActionRow(0, 2, "邮箱", Icons.Default.Email, { onNavigateToBindings(false) }, trailingText = emailStatus) },
                { AccountActionRow(1, 2, "e听说", Icons.Default.Cloud, { onNavigateToBindings(true) }, trailingText = etsStatus) },
            ),
        )
        SegmentedColumn(
            title = "账号安全",
            items = listOf(
                { AccountActionRow(0, 3, "修改密码", Icons.Default.Lock, onNavigateToChangePassword) },
                { AccountActionRow(1, 3, "我的设备", Icons.Default.Devices, onNavigateToDevices) },
                { AccountActionRow(2, 3, "登录会话", Icons.Default.History, onNavigateToSessions) },
            ),
        )
        SegmentedColumn(
            title = "其他",
            items = listOf(
                {
                    AccountActionRow(0, 2, "官网个人中心", Icons.AutoMirrored.Filled.OpenInNew, {
                        context.startActivity(Intent(Intent.ACTION_VIEW, ACCOUNT_CENTER_URL.toUri()))
                    })
                },
                {
                    AccountActionRow(1, 2, "退出登录", Icons.AutoMirrored.Filled.Logout,
                        onClick = { logoutError = null; showLogoutConfirm = true },
                        destructive = true, enabled = !isLoggingOut,
                    )
                },
            ),
        )
        logoutError?.let { AccountMessage(it, isError = true) }
    }
    if (showProfileDetails) {
        AccountProfileDialog(session.user, onDismiss = { showProfileDetails = false })
    }
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isLoggingOut) showLogoutConfirm = false },
            title = { Text("退出登录？") },
            text = { Text("退出后需要重新登录 LastStudio 账号。") },
            confirmButton = {
                TextButton(
                    enabled = !isLoggingOut,
                    onClick = {
                        isLoggingOut = true
                        scope.launch {
                            try {
                                val result = manager.logout(onBeforeGateTransition = onLogoutNavigate)
                                if (result.isFailure) {
                                    logoutError = result.exceptionOrNull()?.message ?: "退出登录失败"
                                    showLogoutConfirm = false
                                }
                            } finally {
                                isLoggingOut = false
                            }
                        }
                    },
                ) { Text(if (isLoggingOut) "正在退出…" else "退出登录", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !isLoggingOut, onClick = { showLogoutConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun AccountOfflineMessage(
    message: String,
    showProgress: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    AccountPageScaffold(title = "个人中心", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showProgress) CircularProgressIndicator()
            AccountMessage(message)
            Button(onClick = onRetry, enabled = !showProgress) { Text("重试连接") }
        }
    }
}
