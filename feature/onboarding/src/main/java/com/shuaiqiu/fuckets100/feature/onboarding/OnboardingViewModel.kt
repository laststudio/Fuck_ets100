package com.shuaiqiu.fuckets100.feature.onboarding

import androidx.lifecycle.ViewModel
import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class OnboardingUiState(
    val currentStep: Int = 0,
    val selectedMode: ActivationMode = ActivationMode.DIRECT_READ,
)

/**
 * 三段式引导：欢迎 -> 运行模式与权限 -> 完成。
 * 本地模式无需登录；云端模式只需登录 ETS100 账号（登录信息保存在本机），
 * 不包含 LastStudio 账号/绑定等步骤。
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun back() {
        _uiState.update { state ->
            state.copy(currentStep = (state.currentStep - 1).coerceAtLeast(0))
        }
    }

    fun acceptWelcomeAndContinue() {
        SettingsManager.saveLegalAccepted(true)
        _uiState.update { it.copy(currentStep = 1) }
    }

    fun selectModeAndContinue(mode: ActivationMode) {
        val fixed = if (mode == ActivationMode.DEFAULT) {
            ActivationMode.DIRECT_READ
        } else {
            mode
        }
        _uiState.update { it.copy(selectedMode = fixed, currentStep = 2) }
    }

    fun completeOnboarding() {
        val mode = _uiState.value.selectedMode
        SettingsManager.saveActivationMode(mode)
        SettingsManager.saveLegalAccepted(true)
        SettingsManager.saveOnboardingCompleted(true)
    }
}
