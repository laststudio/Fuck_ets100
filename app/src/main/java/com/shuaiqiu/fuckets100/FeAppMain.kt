package com.shuaiqiu.fuckets100

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.shuaiqiu.fuckets100.core.designsystem.rememberFeMainPagerState
import com.shuaiqiu.fuckets100.feature.onboarding.OnboardingScreen
import com.shuaiqiu.fuckets100.feature.read.ReadScreen
import com.shuaiqiu.fuckets100.feature.settings.SettingsScreen
import com.shuaiqiu.fuckets100.navigation.FeNavContainer
import com.shuaiqiu.fuckets100.navigation.FeRoute
import com.shuaiqiu.fuckets100.navigation.rememberFeNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用主界面：只保留"读取"与"设置"两个入口。
 * 本地三种模式（Shizuku / Root / 漏洞直读）无需任何登录；
 * 云端模式仅需登录 ETS100 账号，登录信息全部保存在本机。
 */
@Composable
fun FeAppMain() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navigator = rememberFeNavigator(FeRoute.Main)
    LaunchedEffect(navigator) {
        navigator.onRouteRemoved = { route ->
            if (route is FeRoute.Answer) PaperStore.remove(route.paperKey)
        }
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
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

    var showOnboarding by remember { mutableStateOf(!SettingsManager.hasCompletedOnboarding()) }

    var currentMode by remember {
        mutableStateOf(
            SettingsManager.getSavedActivationMode() ?: ShizukuManager.getCurrentActivationMode()
        )
    }

    var readAccessChecking by remember { mutableStateOf(true) }
    var readAccessIssue by remember { mutableStateOf<String?>(null) }
    var readAccessRefreshRevision by remember { mutableIntStateOf(0) }
    var computedReadAccessRevision by remember { mutableIntStateOf(0) }

    suspend fun computeReadAccessIssue(): String? = withContext(Dispatchers.IO) {
        when {
            currentMode == ActivationMode.DEFAULT -> "尚未选择运行模式，请先完成激活配置"
            currentMode == ActivationMode.CLOUD && !ETS100AuthManager.isLoggedIn(context) ->
                "云端模式尚未登录，请先在运行授权页登录 ETS100 账号"
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

    LaunchedEffect(
        currentMode,
        shizukuState.isRunning,
        shizukuState.permissionGranted,
        readAccessRefreshRevision,
    ) {
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
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    currentMode = SettingsManager.getSavedActivationMode()
                        ?: ShizukuManager.getCurrentActivationMode()
                },
            )
        } else {
            FeNavContainer(
                navigator = navigator,
                predictiveBackAnimation = predictiveBackAnimation,
                predictiveBackExitDirection = predictiveBackExitDirection,
                currentMode = currentMode,
                shizukuState = shizukuState,
                readAccessChecking = readAccessChecking,
                readAccessIssue = readAccessIssue,
                onHomeRefreshRequested = {},
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
                            0 -> ReadScreen(
                                currentMode = currentMode,
                                accountOnline = true,
                                readAccessChecking = readAccessChecking,
                                readAccessIssue = readAccessIssue,
                                onNavigateToActivation = {
                                    navigator.push(FeRoute.Activation)
                                },
                                onOpenPaperDetail = { paper ->
                                    navigator.push(FeRoute.Answer(PaperStore.put(paper)))
                                },
                                onOpenChangyanLogin = {
                                    context.startActivity(ChangyanWebLoginActivity.createIntent(context))
                                },
                                compactAnswerDisplay = compactAnswerDisplay,
                            )

                            else -> SettingsScreen(
                                onNavigateActivation = { navigator.push(FeRoute.Activation) },
                                onNavigateGeneralSettings = { navigator.push(FeRoute.GeneralSettings) },
                                onNavigateThemeSettings = { navigator.push(FeRoute.ThemeSettings) },
                            )
                        }
                    }
                },
            )
        }
    }
}
