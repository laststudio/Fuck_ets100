package com.shuaiqiu.fuckets100.feature.onboarding

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.shuaiqiu.fuckets100.rememberShizukuState

/**
 * 冷启动引导（3 步）：1.欢迎 -> 2.运行模式与权限 -> 3.完成。
 * 已移除账号登录 / 云端绑定 / 官网协议拉取。
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uiState by viewModel.uiState.collectAsState()
    val currentStep = uiState.currentStep
    val selectedMode = uiState.selectedMode
    val shizukuState = rememberShizukuState()

    BackHandler(enabled = true) {
        if (currentStep > 0) {
            viewModel.back()
        } else {
            activity?.finish()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OnboardingStepIndicator(
                currentStep = currentStep,
                totalSteps = 3,
                onBack = {
                    if (currentStep > 0) viewModel.back() else activity?.finish()
                },
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 4 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                                (slideOutHorizontally { it / 4 } + fadeOut())
                        }
                    },
                    label = "onboarding_step",
                ) { step ->
                    when (step) {
                        0 -> WelcomeStep(onNext = viewModel::acceptWelcomeAndContinue)
                        1 -> ModeAndPermissionStep(
                            initialMode = selectedMode,
                            shizukuState = shizukuState,
                            onNext = viewModel::selectModeAndContinue,
                        )
                        else -> FinishStep(
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
}
