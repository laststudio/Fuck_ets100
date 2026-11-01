package com.shuaiqiu.fuckets100

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shuaiqiu.fuckets100.core.designsystem.rememberFeMainPagerState
import com.shuaiqiu.fuckets100.core.common.EtsAppVersion
import com.shuaiqiu.fuckets100.feature.onboarding.OnboardingScreen
import com.shuaiqiu.fuckets100.feature.account.FeAccountGate
import com.shuaiqiu.fuckets100.feature.settings.AnnouncementDialog
import com.shuaiqiu.fuckets100.feature.settings.VerificationDialog
import com.shuaiqiu.fuckets100.feature.settings.UpdateDialog
import com.shuaiqiu.fuckets100.feature.settings.SettingsScreen
import com.shuaiqiu.fuckets100.feature.settings.RemoteContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.shuaiqiu.fuckets100.ChangyanWebLoginActivity
import com.shuaiqiu.fuckets100.feature.home.HomeScreen
import com.shuaiqiu.fuckets100.feature.shared.AccountInitialAvatar
import com.shuaiqiu.fuckets100.feature.shared.EtsEightSeriesLocalModeDialog
import com.shuaiqiu.fuckets100.feature.read.ReadScreen
import com.shuaiqiu.fuckets100.feature.read.AutoCompleteScreen
import com.shuaiqiu.fuckets100.navigation.FeNavContainer
import com.shuaiqiu.fuckets100.navigation.FeNavigator
import com.shuaiqiu.fuckets100.navigation.FeRoute
import com.shuaiqiu.fuckets100.navigation.rememberFeNavigator

private val rootTabRoutes =
    listOf(Screen.Home.route, Screen.Read.route, Screen.Auto.route, Screen.Settings.route)

private fun rootPageForRoute(route: String?): Int? = rootTabRoutes.indexOf(route).takeIf { it >= 0 }

private fun UpdateStatus?.toRemoteContentRoute(): FeRoute.RemoteContent = FeRoute.RemoteContent(
    announcementTitle = this?.announcementTitle?.takeIf { it.isNotBlank() } ?: "公告",
    announcementMessage = this?.announcementMessage.orEmpty().ifBlank { "暂无公告" },
    announcementUpdatedAt = this?.announcementUpdatedAt.orEmpty(),
    announcementUrl = this?.announcementUrl.orEmpty(),
    changelogTitle = this?.changelogTitle?.takeIf { it.isNotBlank() } ?: "更新日志",
    changelogSummary = this?.changelogSummary.orEmpty()
        .ifBlank { this?.message.orEmpty().ifBlank { "暂无更新内容" } },
    changelogContent = this?.changelogContent.orEmpty(),
)

private fun handleTargetRoute(
    route: String,
    navigator: FeNavigator,
    pagerState: com.shuaiqiu.fuckets100.core.designsystem.FeMainPagerState,
) {
    rootPageForRoute(route)?.let {
        navigator.popUntil { current -> current is FeRoute.Main }
        pagerState.handlePageChange(it)
    }
}

/**
 * 搴旂敤涓荤晫闈㈢粍鍚堝嚱鏁? */
@Composable
fun FeAppMain(
    accountManager: FeAccountManager,
    updateStatusRepository: AppUpdateStatusRepository,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val feAccountState by accountManager.gateState.collectAsState()
    // 跳过 LastStudio 登录后，云端能力直接依赖 E听说账号登录状态。
    val etsLoggedIn = ETS100AuthManager.isLoggedIn(context)
    val accountOnline = etsLoggedIn
    val navigator = rememberFeNavigator(FeRoute.Main)
    // 答题页被弹出时释放对应 Paper，避免 navigation 层直接依赖 PaperStore。
    LaunchedEffect(navigator) {
        navigator.onRouteRemoved = { route ->
            if (route is FeRoute.Answer) PaperStore.remove(route.paperKey)
        }
    }
    val initialRootPage = remember { rootPageForRoute(MainActivity.pendingTargetRoute) ?: 0 }
    val pagerState = rememberPagerState(initialPage = initialRootPage, pageCount = { rootTabRoutes.size })
    val mainPagerState = rememberFeMainPagerState(pagerState)
    var predictiveBackAnimation by remember {
        mutableStateOf(SettingsManager.getPredictiveBackAnimation())
    }
    var predictiveBackExitDirection by remember {
        mutableStateOf(SettingsManager.getPredictiveBackExitDirection())
    }
    var compactAnswerDisplay by remember { mutableStateOf(SettingsManager.getCompactAnswerDisplay()) }

    val shizukuState = rememberShizukuState()

    var currentTheme by remember { mutableStateOf(ThemeManager.getSavedTheme()) }
    var isDarkMode by remember { mutableStateOf(ThemeManager.getSavedDarkMode()) }
    var isAutoDarkMode by remember { mutableStateOf(ThemeManager.getSavedAutoDarkMode()) }
    var useDynamicColor by remember { mutableStateOf(ThemeManager.getSavedDynamicColor()) }
    var keyColor by remember { mutableStateOf(ThemeManager.getSavedKeyColor()) }
    var paletteStyle by remember { mutableStateOf(ThemeManager.getSavedPaletteStyle()) }
    var colorSpec by remember { mutableStateOf(ThemeManager.getSavedColorSpec()) }
    var isAmoled by remember { mutableStateOf(ThemeManager.getSavedAmoled()) }
    val systemDarkMode = isSystemInDarkTheme()
    val effectiveDarkMode = if (isAutoDarkMode) systemDarkMode else isDarkMode

    // 鏇存柊寮圭獥鐘舵€?- 浣跨敤 snapshotFlow 鐩戝惉 FeApplication.updateStatus 鐨勫彉鍖栧柕~
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateDialogStatus by remember { mutableStateOf<com.shuaiqiu.fuckets100.UpdateStatus?>(null) }
    var verificationDialogStatus by remember { mutableStateOf<com.shuaiqiu.fuckets100.UpdateStatus?>(null) }
    var remoteStatus by remember { mutableStateOf(updateStatusRepository.remoteStatus) }
    var showOnboarding by remember { mutableStateOf(!SettingsManager.hasCompletedOnboarding()) }
    var showAnnouncementDialog by remember { mutableStateOf(false) }

    // 鐩戝惉鏇存柊鐘舵€?Flow锛岀‘淇濇瘡娆￠兘鑳芥敹鍒伴€氱煡鍠祣
    LaunchedEffect(Unit) {
        updateStatusRepository.updateStatusFlow.collect { status ->
            Log.d("FeAppMain", "updateStatusFlow 鏀跺埌: $status")
            if (status != null && status.showDialog) {
                updateDialogStatus = status
                showUpdateDialog = true
            }
        }
    }

    LaunchedEffect(Unit) {
        updateStatusRepository.remoteStatusFlow.collect { status ->
            remoteStatus = status
            if (status != null && status.requiresVerification) {
                verificationDialogStatus = status
            }
            if (status != null &&
                status.announcementMessage.isNotBlank() &&
                status.announcementUpdatedAt != SettingsManager.getLastSeenAnnouncementUpdatedAt()
            ) {
                showAnnouncementDialog = true
            }
        }
    }

    var currentMode by remember {
        mutableStateOf(
            SettingsManager.getSavedActivationMode() ?: ShizukuManager.getCurrentActivationMode()
        )
    }
    var etsEightSeriesVersion by remember { mutableStateOf<EtsAppVersion?>(null) }
    var showEtsCompatibilityDialog by remember { mutableStateOf(false) }
    var hasShownEtsCompatibilityWarning by remember { mutableStateOf(false) }
    var homeStatusRevision by remember { mutableIntStateOf(0) }

    var readAccessChecking by remember { mutableStateOf(true) }
    var readAccessIssue by remember { mutableStateOf<String?>(null) }
    // Shizuku/Root 在 App 外手动授权不会产生任何回调，ON_RESUME 时递增以触发预检重算
    var readAccessRefreshRevision by remember { mutableIntStateOf(0) }
    var computedReadAccessRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (currentMode != ActivationMode.CLOUD && !hasShownEtsCompatibilityWarning) {
            etsEightSeriesVersion = withContext(Dispatchers.IO) {
                EtsAppVersion.read(context)?.takeIf { it.isEightSeries }
            }
            if (etsEightSeriesVersion != null) {
                showEtsCompatibilityDialog = true
                hasShownEtsCompatibilityWarning = true
            }
        }
    }
    suspend fun computeReadAccessIssue(): String? = withContext(Dispatchers.IO) {
        when {
            currentMode == ActivationMode.DEFAULT -> "尚未选择运行模式，请先完成激活配置"
            currentMode == ActivationMode.CLOUD && !ETS100AuthManager.isLoggedIn(context) ->
                "请登录E听说账号"
            currentMode != ActivationMode.CLOUD && !PermissionsHelper.hasAllFilesAccess() ->
                "缺少文件访问权限，无法读取本地答题数据"
            currentMode == ActivationMode.SHIZUKU && !shizukuState.isRunning ->
                "Shizuku 服务未运行，暂时无法读取本地答题数据"
            currentMode == ActivationMode.SHIZUKU && !shizukuState.permissionGranted ->
                "Shizuku 尚未授权，请先完成 Shizuku 授权"
            currentMode == ActivationMode.ROOT && !RootManager.isRootAvailable(refresh = true) ->
                "Root 权限尚未获取，暂时无法读取本地答题数据"
            currentMode == ActivationMode.DIRECT_READ && !ZWCHelper.isDirectReadAvailable() ->
                "漏洞直读尚未就绪，请先在激活页面测试可用性"
            else -> null
        }
    }

    // 进入读取页前先做一次统一预检，避免把未激活/未授权状态转化成底层异常。
    LaunchedEffect(
        currentMode,
        accountOnline,
        shizukuState.isRunning,
        shizukuState.permissionGranted,
        readAccessRefreshRevision,
    ) {
        // 仅由 ON_RESUME 触发的重算不进入 checking 态，保留旧结果直到新结果落定，避免读取页闪烁
        val resumedOnlyRefresh = readAccessRefreshRevision != computedReadAccessRevision
        computedReadAccessRevision = readAccessRefreshRevision
        if (!resumedOnlyRefresh) {
            readAccessChecking = true
        }
        readAccessIssue = computeReadAccessIssue()
        readAccessChecking = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 从 Magisk/Shizuku 等外部完成授权返回时没有任何回调，强制重算读取预检
                readAccessRefreshRevision++
                currentMode = SettingsManager.getSavedActivationMode() ?: ShizukuManager.getCurrentActivationMode()
                currentTheme = ThemeManager.getSavedTheme()
                isDarkMode = ThemeManager.getSavedDarkMode()
                isAutoDarkMode = ThemeManager.getSavedAutoDarkMode()
                useDynamicColor = ThemeManager.getSavedDynamicColor()
                keyColor = ThemeManager.getSavedKeyColor()
                paletteStyle = ThemeManager.getSavedPaletteStyle()
                colorSpec = ThemeManager.getSavedColorSpec()
                isAmoled = ThemeManager.getSavedAmoled()
                predictiveBackAnimation = SettingsManager.getPredictiveBackAnimation()
                predictiveBackExitDirection = SettingsManager.getPredictiveBackExitDirection()
                compactAnswerDisplay = SettingsManager.getCompactAnswerDisplay()
                MainActivity.pendingTargetRoute?.let { route ->
                    MainActivity.pendingTargetRoute = null
                    handleTargetRoute(route, navigator, mainPagerState)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        MainActivity.pendingTargetRoute?.let { route ->
            MainActivity.pendingTargetRoute = null
            handleTargetRoute(route, navigator, mainPagerState)
        }
    }

    LaunchedEffect(Unit) {
        MainActivity.targetRouteEvents.collect { route ->
            handleTargetRoute(route, navigator, mainPagerState)
        }
    }

    LaunchedEffect(shizukuState.isRunning, shizukuState.isSui) {
        if (!SettingsManager.hasUserSelectedMode()) {
            currentMode = ShizukuManager.getCurrentActivationMode()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    FeThemeWrapper(
        theme = currentTheme,
        isDarkMode = effectiveDarkMode,
        useDynamicColor = useDynamicColor,
        keyColor = keyColor,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec,
        isAmoled = isAmoled,
    ) {
        if (showOnboarding) {
            OnboardingScreen(
                onFinished = {
                    showOnboarding = false
                    currentMode = SettingsManager.getSavedActivationMode() ?: ShizukuManager.getCurrentActivationMode()
                },
                createChangyanLoginIntent = { ctx ->
                    ChangyanWebLoginActivity.createIntent(ctx)
                },
            )
        } else {
            FeAccountGate {
            FeNavContainer(
            navigator = navigator,
            predictiveBackAnimation = predictiveBackAnimation,
            predictiveBackExitDirection = predictiveBackExitDirection,
            currentMode = currentMode,
            shizukuState = shizukuState,
            readAccessChecking = readAccessChecking,
            readAccessIssue = readAccessIssue,
            onHomeRefreshRequested = { homeStatusRevision++ },
            onActivationModeChanged = { currentMode = it },
            onSelectRootPage = mainPagerState::handlePageChange,
            onDarkModeChanged = { isDarkMode = it },
            onAutoDarkModeChanged = { isAutoDarkMode = it },
            onDynamicColorChanged = { useDynamicColor = it },
            onKeyColorChanged = { keyColor = it },
            onPaletteStyleChanged = { paletteStyle = it },
            onColorSpecChanged = { colorSpec = it },
            onAmoledChanged = { isAmoled = it },
            onPredictiveBackChanged = { predictiveBackAnimation = it },
            onPredictiveBackExitDirectionChanged = { predictiveBackExitDirection = it },
            selectedRootPage = mainPagerState.selectedPage,
            mainContent = {
                BackHandler(pagerState.currentPage != 0) {
                    mainPagerState.handlePageChange(0)
                }
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = mainPagerState.userScrollEnabled,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> HomeScreen(
                            mode = currentMode,
                            shizukuState = shizukuState,
                            accountState = feAccountState,
                            refreshRevision = homeStatusRevision,
                            onNavigateToActivation = {
                                navigator.push(FeRoute.Activation)
                            },
                            onNavigateToRemoteContent = { status ->
                                navigator.push(status.toRemoteContentRoute())
                            },
                        )

                        1 -> ReadScreen(
                            currentMode = currentMode,
                            accountOnline = accountOnline,
                            readAccessChecking = readAccessChecking,
                            readAccessIssue = readAccessIssue,
                            onNavigateToActivation = {
                                if (currentMode == ActivationMode.CLOUD) {
                                    navigator.push(FeRoute.CloudActivation)
                                } else {
                                    navigator.push(FeRoute.Activation)
                                }
                            },
                            onOpenPaperDetail = { paper ->
                                navigator.push(FeRoute.Answer(PaperStore.put(paper)))
                            },
                            onOpenChangyanLogin = {
                                context.startActivity(ChangyanWebLoginActivity.createIntent(context))
                            },
                            compactAnswerDisplay = compactAnswerDisplay,
                        )

                        2 -> AutoCompleteScreen(
                            accountOnline = accountOnline,
                            onOpenCloudLogin = { navigator.push(FeRoute.CloudActivation) },
                        )

                        3 -> SettingsScreen(
                            onNavigateActivation = { navigator.push(FeRoute.Activation) },
                            onNavigateGeneralSettings = { navigator.push(FeRoute.GeneralSettings) },
                            onNavigateThemeSettings = { navigator.push(FeRoute.ThemeSettings) },
                            onNavigateAbout = { navigator.push(FeRoute.About) },
                            onNavigateAccount = { navigator.push(FeRoute.Account) },
                            accountSession = feAccountState.cachedSessionOrNull,
                            avatar = {
                                AccountInitialAvatar(
                                    username = feAccountState.cachedSessionOrNull?.user?.username.orEmpty(),
                                    size = 48,
                                    avatarUrl = feAccountState.cachedSessionOrNull?.user?.avatarUrl,
                                    userId = feAccountState.cachedSessionOrNull?.user?.id,
                                )
                            },
                        )
                    }
                }
            },
        )
        // 应用级模态弹窗统一在这里仲裁，避免多个 AlertDialog 同时抢占焦点。
        when {
            verificationDialogStatus != null -> {
                VerificationDialog(
                    status = verificationDialogStatus!!,
                    onVerified = {
                        val status = verificationDialogStatus!!
                        verificationDialogStatus = null
                        updateStatusRepository.setRemoteStatus(status.copy(requiresVerification = false))
                        updateStatusRepository.refreshAfterVerification()
                    },
                )
            }

            showUpdateDialog && updateDialogStatus != null -> {
                val status = updateDialogStatus!!
                Log.d("FeAppMain", "显示更新弹窗: ${status.message}")
                UpdateDialog(
                    status = status,
                    onDismiss = {
                        showUpdateDialog = false
                        updateDialogStatus = null
                        updateStatusRepository.setUpdateStatus(null)
                    },
                )
            }

            showEtsCompatibilityDialog -> {
                etsEightSeriesVersion?.let { version ->
                    EtsEightSeriesLocalModeDialog(
                        versionName = version.versionName,
                        mode = currentMode,
                        onDismiss = { showEtsCompatibilityDialog = false },
                        onUseCloudMode = {
                            showEtsCompatibilityDialog = false
                            SettingsManager.saveActivationMode(ActivationMode.CLOUD)
                            currentMode = ActivationMode.CLOUD
                        },
                        onContinueLocalMode = { showEtsCompatibilityDialog = false },
                    )
                }
            }

            showAnnouncementDialog && remoteStatus != null -> {
                val status = remoteStatus!!
                AnnouncementDialog(
                    title = status.announcementTitle.ifBlank { "公告" },
                    message = status.announcementMessage,
                    updatedAt = status.announcementUpdatedAt,
                    onViewDetail = {
                        showAnnouncementDialog = false
                        SettingsManager.saveLastSeenAnnouncementUpdatedAt(status.announcementUpdatedAt)
                        navigator.push(status.toRemoteContentRoute())
                    },
                    onDismiss = {
                        showAnnouncementDialog = false
                        SettingsManager.saveLastSeenAnnouncementUpdatedAt(status.announcementUpdatedAt)
                    },
                )
            }
        }
        }
        }
    }
}

// ============================================================================
// UI 缁勪欢瀹氫箟 - 椤堕儴搴旂敤鏍忓拰璁剧疆椤?// ============================================================================
