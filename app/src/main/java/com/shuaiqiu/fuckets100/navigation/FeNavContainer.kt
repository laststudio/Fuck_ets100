package com.shuaiqiu.fuckets100.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.NavigationEventState
import androidx.navigationevent.compose.rememberNavigationEventState
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.shuaiqiu.fuckets100.feature.settings.ThemeSettingsScreen
import com.shuaiqiu.fuckets100.feature.activation.ActivationSettingsScreen
import com.shuaiqiu.fuckets100.feature.account.FeAccountScreen
import com.shuaiqiu.fuckets100.feature.account.AccountBindingsScreen
import com.shuaiqiu.fuckets100.feature.account.ChangePasswordScreen
import com.shuaiqiu.fuckets100.feature.account.CloudActivationScreen
import com.shuaiqiu.fuckets100.feature.account.AccountSessionsPage
import com.shuaiqiu.fuckets100.feature.account.AccountDevicesPage
import com.shuaiqiu.fuckets100.feature.settings.SettingsScreen
import com.shuaiqiu.fuckets100.feature.settings.RemoteContent
import com.shuaiqiu.fuckets100.feature.settings.RemoteContentScreen
import com.shuaiqiu.fuckets100.feature.settings.LegalScreen
import com.shuaiqiu.fuckets100.feature.settings.GeneralSettingsScreen
import com.shuaiqiu.fuckets100.feature.settings.DonateScreen
import com.shuaiqiu.fuckets100.feature.settings.AboutScreen
import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.ChangyanWebLoginActivity
import com.shuaiqiu.fuckets100.feature.read.AnswerScreen
import com.shuaiqiu.fuckets100.PredictiveBackAnimation
import com.shuaiqiu.fuckets100.PredictiveBackExitDirection
import com.shuaiqiu.fuckets100.SettingsManager
import com.shuaiqiu.fuckets100.ShizukuState
import com.shuaiqiu.fuckets100.component.material.FeRootNavigationScaffold
import kotlinx.coroutines.launch

@Composable
fun FeNavContainer(
    navigator: FeNavigator,
    predictiveBackAnimation: PredictiveBackAnimation,
    predictiveBackExitDirection: PredictiveBackExitDirection,
    currentMode: ActivationMode,
    shizukuState: ShizukuState,
    readAccessChecking: Boolean,
    readAccessIssue: String?,
    onHomeRefreshRequested: () -> Unit,
    onActivationModeChanged: (ActivationMode) -> Unit,
    onSelectRootPage: (Int) -> Unit,
    onDarkModeChanged: (Boolean) -> Unit,
    onAutoDarkModeChanged: (Boolean) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onKeyColorChanged: (Int) -> Unit,
    onPaletteStyleChanged: (PaletteStyle) -> Unit,
    onColorSpecChanged: (ColorSpec.SpecVersion) -> Unit,
    onAmoledChanged: (Boolean) -> Unit,
    onPredictiveBackChanged: (PredictiveBackAnimation) -> Unit,
    onPredictiveBackExitDirectionChanged: (PredictiveBackExitDirection) -> Unit,
    selectedRootPage: Int,
    mainContent: @Composable () -> Unit,
) {
    val selectedRootPageState = rememberUpdatedState(selectedRootPage)
    val selectRootPageState = rememberUpdatedState(onSelectRootPage)

    val predictiveBackAnimationHandler = remember(
        predictiveBackAnimation,
        predictiveBackExitDirection,
    ) {
        when (predictiveBackAnimation) {
            PredictiveBackAnimation.None -> NoPredictiveBackAnimation()
            PredictiveBackAnimation.Scale -> ScalePredictiveBackAnimation(
                predictiveBackExitDirection,
            )
            PredictiveBackAnimation.MIUIX -> MiuixPredictiveBackAnimation()
        }
    }

    var gestureState: NavigationEventState<SceneInfo<FeRoute>>? = null
    var cloudAuthRevision by remember { mutableIntStateOf(0) }
    val navigationScope = rememberCoroutineScope()
    val onBack: (() -> Unit) -> Unit = { callBack ->
        navigationScope.launch {
            predictiveBackAnimationHandler.onBackPressed(
                transitionState = gestureState?.transitionState,
                currentPageKey = navigator.current(),
            )

            callBack()

            navigator.pop()
        }
    }

    val entries =
        rememberDecoratedNavEntries(
            backStack = navigator.backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
                NavEntryDecorator(
                    onPop = { key ->
                        predictiveBackAnimationHandler.onPagePop(
                            contentPageKey = key,
                            animationScope = navigationScope,
                        )
                    },
                ) { content ->
                    with(predictiveBackAnimationHandler) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .predictiveBackAnimationDecorator(
                                    gestureState?.transitionState,
                                    content.contentKey,
                                    navigator.current(),
                                )
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            content.Content()
                        }
                    }
                },
            ),
            entryProvider = entryProvider {
                entry<FeRoute.Main> {
                    FeRootNavigationScaffold(
                        showRootNavigation = true,
                        selectedPage = selectedRootPageState.value,
                        onNavigatePage = selectRootPageState.value,
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            mainContent()
                        }
                    }
                }
                entry<FeRoute.Activation> {
                    ActivationSettingsScreen(
                        currentMode = currentMode,
                        shizukuState = shizukuState,
                        cloudAuthRevision = cloudAuthRevision,
                        onModeSelected = { mode ->
                            SettingsManager.saveActivationMode(mode)
                            onActivationModeChanged(mode)
                        },
                        onBack = {
                            navigator.pop()
                            onHomeRefreshRequested()
                        },
                        onNavigateToCloudActivation = { },
                        onNavigateToRead = {
                            navigator.popUntil { it is FeRoute.Main }
                            onSelectRootPage(0)
                        },
                    )
                }
                                                                                                                entry<FeRoute.GeneralSettings> { GeneralSettingsScreen(onBack = navigator::pop) }
                entry<FeRoute.ThemeSettings> {
                    ThemeSettingsScreen(
                        onBack = navigator::pop,
                        onDarkModeChanged = onDarkModeChanged,
                        onAutoDarkModeChanged = onAutoDarkModeChanged,
                        onDynamicColorChanged = onDynamicColorChanged,
                        onKeyColorChanged = onKeyColorChanged,
                        onPaletteStyleChanged = onPaletteStyleChanged,
                        onColorSpecChanged = onColorSpecChanged,
                        onAmoledChanged = onAmoledChanged,
                        onPredictiveBackChanged = onPredictiveBackChanged,
                        onPredictiveBackExitDirectionChanged = onPredictiveBackExitDirectionChanged,
                    )
                }
                                                                entry<FeRoute.Answer> { route ->
                    AnswerScreen(
                        paperKey = route.paperKey,
                        onBack = navigator::pop,
                    )
                }
                            },
        )

    val sceneState =
        rememberSceneState(
            entries = entries,
            sceneStrategies = listOf(SinglePaneSceneStrategy()),
            sceneDecoratorStrategies = emptyList(),
            sharedTransitionScope = null,
            onBack = {
                onBack {}
            },
        )
    val scene = sceneState.currentScene

    // Predictive Back Handling
    val currentInfo = SceneInfo(scene)
    val previousSceneInfos = sceneState.previousScenes.map { SceneInfo(it) }
    gestureState = rememberNavigationEventState(
        currentInfo = currentInfo,
        backInfo = previousSceneInfos,
    )

    NavigationBackHandler(
        state = gestureState,
        isBackEnabled = scene.previousEntries.isNotEmpty(),
        onBackCompleted = { callBack ->
            onBack(callBack)
        },
        onBackCancelled = { callBack ->
            callBack()
        },
    )

    CompositionLocalProvider(
        LocalFeNavigator provides navigator,
    ) {
        NavDisplay(
            sceneState = sceneState,
            navigationEventState = gestureState,
            contentAlignment = Alignment.TopStart,
            sizeTransform = null,
            predictivePopTransitionSpec = { swipeEdge ->
                with(predictiveBackAnimationHandler) {
                    onPredictivePopTransitionSpec(swipeEdge = swipeEdge)
                }
            },
            popTransitionSpec = {
                with(predictiveBackAnimationHandler) {
                    onPopTransitionSpec()
                }
            },
            transitionSpec = {
                with(predictiveBackAnimationHandler) {
                    onTransitionSpec()
                }
            },
        )
    }
}
