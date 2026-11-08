package com.shuaiqiu.fuckets100.feature.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.shuaiqiu.fuckets100.ETS100AuthManager
import com.shuaiqiu.fuckets100.FeAccountManager
import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveScaffold
import com.shuaiqiu.fuckets100.core.designsystem.TopBarBackButton
import com.shuaiqiu.fuckets100.core.designsystem.expressiveTopAppBarColors
import com.shuaiqiu.fuckets100.feature.shared.AccountViewModel
import com.shuaiqiu.fuckets100.feature.shared.EtingshuoLocalLoginCard
import com.shuaiqiu.fuckets100.feature.shared.LastStudioUnavailableCard

/**
 * 云端模式登录页。
 *
 * LastStudio 账号已停用，页面只保留 E 听说账号登录，不再读取 LastStudio 绑定账号。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CloudActivationScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    createChangyanLoginIntent: (Context) -> Intent,
    @Suppress("UNUSED_PARAMETER") accountManager: FeAccountManager = hiltViewModel<AccountViewModel>().manager,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val changyanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && ETS100AuthManager.isLoggedIn(context)) {
            onLoginSuccess()
        }
    }

    // 不再在进入页面时自动判定“已登录”并返回：token 可能已失效但本地仍有记录，
    // 自动 pop 会让用户从错误提示点「前往激活」后立刻被弹回，形成无法重新登录的死循环。

    ExpressiveScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("云端模式") },
                subtitle = { Text("登录 E 听说账号后即可读取云端作业") },
                navigationIcon = { TopBarBackButton(onClick = onBack) },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(1.dp))
            LastStudioUnavailableCard(
                detail = "LastStudio 登录与绑定入口已关闭，云端模式只使用 E 听说账号。",
            )
            EtingshuoLocalLoginCard(
                modifier = Modifier.fillMaxWidth(),
                onLoggedIn = onLoginSuccess,
            )
            TextButton(
                onClick = { changyanLauncher.launch(createChangyanLoginIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("使用讯飞企业登录（网页）")
            }
            Text(
                "学校统一发放的企业账号请用讯飞网页登录，登录成功后会自动回到这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
