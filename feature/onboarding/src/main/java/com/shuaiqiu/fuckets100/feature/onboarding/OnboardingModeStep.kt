package com.shuaiqiu.fuckets100.feature.onboarding

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.shuaiqiu.fuckets100.core.designsystem.TonalCard
import com.shuaiqiu.fuckets100.core.common.EtsAppVersion
import com.shuaiqiu.fuckets100.feature.shared.EtsEightSeriesLocalModeDialog

import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.PermissionsHelper
import com.shuaiqiu.fuckets100.RootManager
import com.shuaiqiu.fuckets100.ShizukuManager
import com.shuaiqiu.fuckets100.ShizukuState
// ============================================================================
// 步骤 3: 模式先导与按需精准授权 (不索取多余权限，无悬浮窗干扰)
// ============================================================================

@Composable
internal fun ModeAndPermissionStep(
    initialMode: ActivationMode,
    shizukuState: ShizukuState,
    onNext: (ActivationMode) -> Unit,
) {
    val context = LocalContext.current
    var selectedMode by rememberSaveable {
        mutableStateOf(
            if (initialMode == ActivationMode.DEFAULT || initialMode == ActivationMode.CLOUD) {
                ActivationMode.DIRECT_READ
            } else {
                initialMode
            }
        )
    }

    // 动态权限状态
    var hasFilesPerm by remember { mutableStateOf(PermissionsHelper.hasAllFilesAccess()) }
    var isRootGranted by remember { mutableStateOf(RootManager.isRootAvailable()) }
    var showMissingPermissionDialog by remember { mutableStateOf(false) }
    var pendingMissingList by remember { mutableStateOf<List<String>>(emptyList()) }
    var etsEightSeriesVersion by remember { mutableStateOf<EtsAppVersion?>(null) }
    var showEtsCompatibilityDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        etsEightSeriesVersion = EtsAppVersion.read(context)?.takeIf { it.isEightSeries }
    }

    // 当用户从系统设置或授权软件返回时，刷新权限状态
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasFilesPerm = PermissionsHelper.hasAllFilesAccess()
        RootManager.clearCache()
        isRootGranted = RootManager.isRootAvailable()
    }

    val isGeekMode = selectedMode == ActivationMode.SHIZUKU || selectedMode == ActivationMode.ROOT

    fun selectLocalMode(mode: ActivationMode) {
        selectedMode = mode
    }

    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        when (selectedMode) {
            ActivationMode.CLOUD -> {
                // 云端模式不需要本地敏感权限
            }
            ActivationMode.SHIZUKU -> {
                if (!shizukuState.isRunning || !shizukuState.permissionGranted) {
                    missing.add("Shizuku 服务运行与授权")
                }
                if (!hasFilesPerm) {
                    missing.add("所有文件访问权限")
                }
            }
            ActivationMode.ROOT -> {
                if (!isRootGranted) {
                    missing.add("Root (SU) 超级用户权限")
                }
                if (!hasFilesPerm) {
                    missing.add("所有文件访问权限")
                }
            }
            ActivationMode.DIRECT_READ, ActivationMode.DEFAULT -> {
                if (!hasFilesPerm) {
                    missing.add("所有文件访问权限")
                }
            }
        }
        return missing
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = "选择运行模式",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "按需索取权限，避免不必要的系统授权",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "本应用只做本地读取：请根据设备情况选择 Shizuku、Root 或漏洞直读模式，三选一即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 模式 2: 极客提权模式 (Shizuku 与 Root 可选)
        ModeSelectionCard(
            title = "极客提权模式",
            subtitle = "需要 Shizuku 或 Root，适合高级用户",
            badge = "本地读取",
            icon = if (selectedMode == ActivationMode.ROOT) Icons.Default.Security else Icons.Default.CheckCircle,
            selected = isGeekMode,
            onClick = {
                if (!isGeekMode) {
                    selectLocalMode(ActivationMode.SHIZUKU)
                }
            },
            extraContent = if (isGeekMode) {
                {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Shizuku Tab
                        val isShizuku = selectedMode == ActivationMode.SHIZUKU
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (isShizuku) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { selectLocalMode(ActivationMode.SHIZUKU) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Shizuku 授权",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isShizuku) FontWeight.Bold else FontWeight.Normal,
                                color = if (isShizuku) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // Root Tab
                        val isRoot = selectedMode == ActivationMode.ROOT
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (isRoot) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { selectLocalMode(ActivationMode.ROOT) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Root 权限",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isRoot) FontWeight.Bold else FontWeight.Normal,
                                color = if (isRoot) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else null,
        )

        Spacer(Modifier.height(10.dp))

        // 模式 3: 本地漏洞直读模式
        ModeSelectionCard(
            title = "本地直读模式",
            subtitle = "读取手机已缓存的作业数据，不走云端",
            badge = "无需 Root",
            icon = Icons.Default.Bolt,
            selected = selectedMode == ActivationMode.DIRECT_READ,
            onClick = { selectLocalMode(ActivationMode.DIRECT_READ) },
        )

        Spacer(Modifier.height(14.dp))

        AnimatedContent(
            targetState = selectedMode,
            transitionSpec = {
                (fadeIn(animationSpec = tween(180)) + slideInVertically { it / 5 }) togetherWith
                    (fadeOut(animationSpec = tween(120)) + slideOutVertically { -it / 5 })
            },
            label = "mode_explanation",
        ) { mode ->
            ModeExplanation(mode = mode)
        }

        Spacer(Modifier.height(18.dp))

        // 动态权限卡片 (按选定模式展示，避免多余权限)
        Text(
            text = "所需权限与就绪状态",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        when (selectedMode) {
            ActivationMode.CLOUD -> {
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "无需额外敏感权限",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "云端模式直接通过安全通信接口工作，不读取本地文件与应用列表，纯净轻量。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            ActivationMode.SHIZUKU -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Shizuku 授权卡片
                    val shizukuReady = shizukuState.isRunning && shizukuState.permissionGranted
                    PermissionStatusCard(
                        title = "Shizuku 服务授权",
                        desc = if (shizukuReady) "Shizuku 服务已连接且授权成功" else if (shizukuState.isRunning) "Shizuku 服务运行中，点击申请授权" else "尚未检测到 Shizuku 服务运行，点击前往启动",
                        isGranted = shizukuReady,
                        actionLabel = if (shizukuState.isRunning) "申请授权" else "打开 Shizuku",
                        onAction = {
                            if (shizukuState.isRunning) {
                                if (ShizukuManager.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                                    Toast.makeText(context, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
                                } else {
                                    ShizukuManager.requestPermission(ShizukuManager.REQUEST_CODE_SHIZUKU_PERMISSION)
                                }
                            } else {
                                ShizukuManager.openShizukuApp(context)
                            }
                        },
                    )

                    // 文件访问权限
                    PermissionStatusCard(
                        title = "所有文件访问权限",
                        desc = if (hasFilesPerm) "本地存储访问已就绪" else "用于读取本地题库与音频文件",
                        isGranted = hasFilesPerm,
                        actionLabel = "去授权",
                        onAction = { PermissionsHelper.requestAllFilesAccess(context) },
                    )
                }
            }

            ActivationMode.ROOT -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Root 授权卡片
                    PermissionStatusCard(
                        title = "Root (SU) 权限授权",
                        desc = if (isRootGranted) "Root 权限已获取，享受最高提权权限" else "尚未获取 Root 权限，点击申请或检测",
                        isGranted = isRootGranted,
                        actionLabel = if (isRootGranted) "已授权" else "申请/检测",
                        onAction = {
                            RootManager.clearCache()
                            isRootGranted = RootManager.isRootAvailable()
                            if (isRootGranted) {
                                Toast.makeText(context, "Root 权限已成功获取！", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "未获取到 Root 权限，请在授权管理软件(如Magisk/KSU/APatch)中放行", Toast.LENGTH_LONG).show()
                            }
                        },
                    )

                    // 文件访问权限
                    PermissionStatusCard(
                        title = "所有文件访问权限",
                        desc = if (hasFilesPerm) "本地存储访问已就绪" else "用于读取本地题库与音频文件",
                        isGranted = hasFilesPerm,
                        actionLabel = "去授权",
                        onAction = { PermissionsHelper.requestAllFilesAccess(context) },
                    )
                }
            }

            ActivationMode.DIRECT_READ, ActivationMode.DEFAULT -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 本地直读仅需文件访问权限
                    PermissionStatusCard(
                        title = "所有文件访问权限",
                        desc = if (hasFilesPerm) "本地存储访问已就绪" else "用于读取本地试卷缓存",
                        isGranted = hasFilesPerm,
                        actionLabel = "去授权",
                        onAction = { PermissionsHelper.requestAllFilesAccess(context) },
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = {
                val missing = getMissingPermissions()
                if (missing.isEmpty()) {
                    onNext(selectedMode)
                } else {
                    pendingMissingList = missing
                    showMissingPermissionDialog = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Text("完成配置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.height(16.dp))
    }

    // E 听说 8.x 兼容性提示：只提示，不阻止继续使用本地模式
    etsEightSeriesVersion?.let { version ->
        if (showEtsCompatibilityDialog) {
            EtsEightSeriesLocalModeDialog(
                versionName = version.versionName,
                mode = selectedMode,
                onDismiss = { showEtsCompatibilityDialog = false },
                onUseCloudMode = {
                    showEtsCompatibilityDialog = false
                    selectedMode = ActivationMode.CLOUD
                },
                onContinueLocalMode = { showEtsCompatibilityDialog = false },
            )
        }
    }

    if (showMissingPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMissingPermissionDialog = false },
            icon = {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp),
                )
            },
            title = {
                Text(
                    text = "部分权限尚未授予",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    Text(
                        text = "当前选定的模式需要以下权限以确保核心功能正常运作：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    pendingMissingList.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            Text(
                                text = "• ",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "若未完全授权，对应模式可能无法成功读取答题试卷或检测应用状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showMissingPermissionDialog = false }) {
                    Text("去授权")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMissingPermissionDialog = false
                        onNext(selectedMode)
                    }
                ) {
                    Text("暂不授权，继续")
                }
            },
        )
    }
}
