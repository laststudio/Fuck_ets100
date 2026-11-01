package com.shuaiqiu.fuckets100.feature.onboarding

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.rememberShizukuState
/**
 * 软件初始化冷启动引导向导 (Material 3 Expressive 风格)
 * 4 步流程：1.欢迎与协议 -> 2.账号登录 -> 3.模式与按需权限 -> 4.完成
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    createChangyanLoginIntent: (android.content.Context) -> android.content.Intent,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uiState by viewModel.uiState.collectAsState()
    val currentStep = uiState.currentStep
    val selectedMode = uiState.selectedMode
    val accountManager = viewModel.accountManager
    val shizukuState = rememberShizukuState()

    // 处理系统返回键：第一步退出应用，后几步回退上一向导页
    BackHandler {
        if (currentStep > 0) {
            viewModel.back()
        } else {
            activity?.finish()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            OnboardingStepIndicator(
                currentStep = currentStep,
                totalSteps = 4,
                onBack = {
                    if (currentStep > 0) viewModel.back()
                    else activity?.finish()
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { width -> width / 2 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -width / 2 } + fadeOut())
                } else {
                    (slideInHorizontally { width -> -width / 2 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> width / 2 } + fadeOut())
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "onboarding_step_content",
        ) { step ->
            when (step) {
                0 -> WelcomeStep(
                    onNext = viewModel::acceptWelcomeAndContinue,
                    onRefreshLegalAgreement = viewModel::refreshLegalAgreement,
                    currentAgreement = viewModel.currentAgreement.collectAsState().value,
                )
                1 -> AccountStep(
                    accountManager = accountManager,
                    onNext = viewModel::continueFromAccount,
                    onSkip = viewModel::skipAccountAndContinue,
                )
                2 -> ModeAndPermissionStep(
                    initialMode = selectedMode,
                    shizukuState = shizukuState,
                    onNext = viewModel::selectModeAndContinue,
                )
                3 -> if (selectedMode == ActivationMode.CLOUD) {
                    CloudBindingStep(
                        accountManager = accountManager,
                        onFinish = {
                            viewModel.completeOnboarding()
                            onFinished()
                        },
                        createChangyanLoginIntent = createChangyanLoginIntent,
                    )
                } else {
                    FinishStep(
                        selectedMode = selectedMode,
                        onFinish = {
                            viewModel.completeOnboarding()
                            onFinished()
                        },
                    )
                }
            }
        }
    }
}
