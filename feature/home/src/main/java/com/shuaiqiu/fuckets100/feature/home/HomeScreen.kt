package com.shuaiqiu.fuckets100.feature.home

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.FeAccountGateState
import com.shuaiqiu.fuckets100.core.common.EtsAppVersion
import com.shuaiqiu.fuckets100.core.designsystem.RighteousFont
import com.shuaiqiu.fuckets100.ShizukuState
import com.shuaiqiu.fuckets100.UpdateStatus
import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveScaffold
import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveSegmentedListItem
import com.shuaiqiu.fuckets100.core.designsystem.SegmentedColumn
import com.shuaiqiu.fuckets100.core.designsystem.TonalCard
import com.shuaiqiu.fuckets100.core.designsystem.expressiveTopAppBarColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 首页主屏幕
 * 显示系统状态、设备信息和激活状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    mode: ActivationMode,
    shizukuState: ShizukuState,
    accountState: FeAccountGateState,
    refreshRevision: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToActivation: () -> Unit,
    onNavigateToRemoteContent: (UpdateStatus?) -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val runtimeStatus = uiState.runtimeStatus
    val remoteStatus = uiState.remoteStatus
    val isRefreshing = uiState.isRefreshing
    val pullRefreshState = rememberPullToRefreshState()

    val onRefresh: () -> Unit = viewModel::refresh

    // 返回首页时后台刷新真实权限状态，但保留旧状态直到检测完成，避免先闪成未授权。
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPaused by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> hasPaused = true
                Lifecycle.Event.ON_RESUME -> if (hasPaused) viewModel.onResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(mode, refreshRevision, viewModel) {
        viewModel.onRuntimeInputsChanged(mode, refreshRevision)
    }

    // 检查基础权限是否全部获取
    val hasAllBasicPermissions = runtimeStatus.hasFilesPerm

    // 判断是否真正激活 - 根据不同模式判断激活条件
    val isTrulyActivated = when {
        mode == ActivationMode.SHIZUKU -> shizukuState.isRunning && shizukuState.permissionGranted && hasAllBasicPermissions
        mode == ActivationMode.ROOT -> hasAllBasicPermissions && runtimeStatus.hasRootAvailable
        mode == ActivationMode.DIRECT_READ -> hasAllBasicPermissions && runtimeStatus.hasDirectReadAvailable
        mode == ActivationMode.CLOUD -> runtimeStatus.cloudLoggedIn
        mode != ActivationMode.DEFAULT -> hasAllBasicPermissions
        else -> false
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = "Fe",
                        fontFamily = RighteousFont,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                colors = expressiveTopAppBarColors(),
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { paddingValues ->
        PullToRefreshBox(
            state = pullRefreshState,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    modifier = Modifier
                        .padding(top = paddingValues.calculateTopPadding())
                        .align(Alignment.TopCenter),
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = paddingValues.calculateTopPadding() + 4.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = paddingValues.calculateBottomPadding() + 24.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(
                    mode = mode,
                    shizukuState = shizukuState,
                    accountState = accountState,
                    isTrulyActivated = isTrulyActivated,
                    cloudLoggedIn = runtimeStatus.cloudLoggedIn,
                    hasFilesPerm = runtimeStatus.hasFilesPerm,
                    hasAllBasicPermissions = hasAllBasicPermissions,
                    hasRootAvailable = runtimeStatus.hasRootAvailable,
                    etsAppVersion = runtimeStatus.etsAppInfo?.second,
                    onNavigateToActivation = onNavigateToActivation
                )

                // 版本信息（设备、Android、管理器版本、e听说状态）
                HomeVersionInfoSection(
                    context = context,
                    appVersion = uiState.appVersion,
                    etsAppInfo = runtimeStatus.etsAppInfo,
                )

                // 公告
                HomeAnnouncementSection(
                    status = remoteStatus,
                    onNavigateToRemoteContent = onNavigateToRemoteContent,
                )

            }
        }
    }
}

/**
 * 状态卡片组件
 * 显示当前激活模式和权限状态
 */
@Composable
fun StatusCard(
    mode: ActivationMode,
    shizukuState: ShizukuState,
    accountState: FeAccountGateState,
    isTrulyActivated: Boolean,
    cloudLoggedIn: Boolean,
    hasFilesPerm: Boolean,
    hasAllBasicPermissions: Boolean,
    hasRootAvailable: Boolean,
    etsAppVersion: String?,
    onNavigateToActivation: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    // LastStudio 账号不再是使用前提：本地模式与云端模式只依赖各自的实际运行条件。
    val isConnecting = false
    val needsLogin = false
    val isOffline = false
    val isBlocked = false
    val isEightSeriesLocalMode = mode != ActivationMode.CLOUD &&
        EtsAppVersion.parseMajorVersion(etsAppVersion.orEmpty()) == 8
    val showLocalCompatibilityWarning = isEightSeriesLocalMode
    val effectiveActivated = isTrulyActivated
    val statusContainerColor = when {
        isConnecting -> MaterialTheme.colorScheme.surfaceContainerHighest
        showLocalCompatibilityWarning -> Color(0xFFFFF3CD)
        effectiveActivated -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val animatedContainerColor by animateColorAsState(
        targetValue = statusContainerColor,
        animationSpec = tween(durationMillis = 280),
        label = "homeStatusContainerColor",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColorFor(statusContainerColor),
        animationSpec = tween(durationMillis = 220),
        label = "homeStatusContentColor",
    )
    val statusContentColor = animatedContentColor

    // 根据当前状态显示不同的标题
    val displayTitle = when {
        isConnecting -> "正在连接 LastStudio"
        needsLogin -> "需要登录"
        isOffline -> "LastStudio 已离线"
        isBlocked -> "LastStudio 当前不可用"
        showLocalCompatibilityWarning -> "本地模式兼容性警告"
        mode == ActivationMode.CLOUD && cloudLoggedIn -> "云端模式已激活"
        mode == ActivationMode.CLOUD -> "云端模式未激活"
        // 基础权限未授予时显示等待授权提示
        !hasAllBasicPermissions -> "等待授权"
        mode == ActivationMode.SHIZUKU && shizukuState.isRunning && !shizukuState.permissionGranted -> "Shizuku 等待授权"
        mode == ActivationMode.SHIZUKU && !shizukuState.isRunning -> "Shizuku 未运行"
        mode == ActivationMode.ROOT && !hasRootAvailable -> "Root 未获取"
        else -> mode.title
    }

    val displayIcon = when {
        showLocalCompatibilityWarning -> Icons.Outlined.Warning
        effectiveActivated -> Icons.Outlined.CheckCircle
        mode == ActivationMode.DEFAULT -> Icons.Outlined.Block
        else -> Icons.Outlined.Warning
    }
    val summary = when {
        isConnecting -> "正在验证账号和软件授权，请稍候"
        needsLogin -> "请先登录 LastStudio 账号后继续使用"
        isOffline -> "LastStudio 已离线，部分功能受限"
        isBlocked -> "LastStudio 当前不可用"
        showLocalCompatibilityWarning -> "检测到 E 听说 ${etsAppVersion.orEmpty()}，原有 7.x 本地读取方式可能无法读取数据"
        mode == ActivationMode.CLOUD -> if (cloudLoggedIn) "云端作业与试卷解析服务已连接" else "未绑定或未配置云端凭据，点击前往配置"
        !hasAllBasicPermissions -> "需要文件访问权限才能读取题库，请授权"
        mode == ActivationMode.SHIZUKU -> when {
            !shizukuState.isRunning -> "请启动Shizuku后再返回此处"
            !shizukuState.permissionGranted -> "请授予Shizuku权限后即可使用"
            else -> {
                val uidStr = when (shizukuState.uid) {
                    0 -> "Root (0)"
                    2000 -> "ADB (2000)"
                    else -> shizukuState.uid.toString()
                }
                "${shizukuState.getRuntimeTypeName()} v${shizukuState.version} | UID: $uidStr"
            }
        }
        mode == ActivationMode.ROOT -> if (!hasRootAvailable) {
            "未检测到Root权限，请先获取Root"
        } else {
            "Root 权限已获取"
        }
        mode == ActivationMode.DIRECT_READ -> "Direct Read 模式已就绪"
        mode == ActivationMode.DEFAULT -> "请授权基础权限后选择激活模式"
        else -> "点击进入激活设置"
    }

    Card(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            onNavigateToActivation()
        },
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(280)),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = animatedContainerColor,
            contentColor = statusContentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = displayIcon,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                label = "homeStatusIcon",
            ) { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusContentColor,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                AnimatedContent(
                    targetState = displayTitle,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    },
                    label = "homeStatusTitle",
                ) { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = statusContentColor,
                        maxLines = 1,
                        minLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                AnimatedContent(
                    targetState = summary,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    },
                    label = "homeStatusSummary",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusContentColor.copy(alpha = 0.82f),
                        maxLines = 1,
                        minLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isConnecting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = statusContentColor,
                    )
                    Text("连接中", color = statusContentColor, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                StatusTag(
                    label = when {
                        isOffline -> "离线"
                        needsLogin -> "未登录"
                        effectiveActivated -> mode.shortLabel
                        else -> "未就绪"
                    },
                    containerColor = if (effectiveActivated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    contentColor = if (effectiveActivated) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}

private data class CoreStatusDisplay(
    val title: String,
    val summary: String,
    val tag: String,
    val isOk: Boolean,
)

internal object DeviceInfoHelper {
    fun getDeviceModel(): String {
        return runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val getMethod = systemProperties.getMethod("get", String::class.java, String::class.java)
            val marketNameKeys = listOf(
                "ro.product.marketname",
                "ro.vendor.oplus.market.name",
                "ro.vivo.market.name",
                "ro.config.marketing_name"
            )
            for (key in marketNameKeys) {
                val marketName = runCatching { getMethod.invoke(null, key, "") as String }.getOrDefault("")
                if (marketName.isNotBlank()) {
                    return@runCatching marketName
                }
            }
            getFallbackDeviceInfo()
        }.getOrElse { getFallbackDeviceInfo() }
    }

    private fun getFallbackDeviceInfo(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "未知"
        val model = Build.MODEL ?: ""
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
    }

    fun getAndroidVersion(): String {
        return "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    fun getAppVersion(buildVersion: String): String = buildVersion
}

internal fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    runCatching {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
