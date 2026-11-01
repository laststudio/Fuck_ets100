package com.shuaiqiu.fuckets100.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.FeAccountManager
import com.shuaiqiu.fuckets100.LegalAgreement
import com.shuaiqiu.fuckets100.LegalAgreementRepository
import com.shuaiqiu.fuckets100.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val currentStep: Int = 0,
    val selectedMode: ActivationMode = ActivationMode.CLOUD,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    val accountManager: FeAccountManager,
    private val legalAgreementRepository: LegalAgreementRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()
    val currentAgreement: StateFlow<LegalAgreement> = legalAgreementRepository.currentAgreement

    fun refreshLegalAgreement() {
        viewModelScope.launch { legalAgreementRepository.refresh() }
    }

    fun back() {
        _uiState.update { state ->
            state.copy(currentStep = (state.currentStep - 1).coerceAtLeast(0))
        }
    }

    fun acceptWelcomeAndContinue() {
        SettingsManager.saveLegalAccepted(true)
        _uiState.update { it.copy(currentStep = 1) }
    }

    fun continueFromAccount() {
        SettingsManager.saveLastStudioSkipped(false)
        _uiState.update { it.copy(currentStep = 2) }
    }

    /** 跳过 LastStudio 登录，仅使用 E听说账号继续。 */
    fun skipAccountAndContinue() {
        SettingsManager.saveLastStudioSkipped(true)
        _uiState.update { it.copy(currentStep = 2) }
    }

    fun selectModeAndContinue(mode: ActivationMode) {
        _uiState.update { it.copy(selectedMode = mode, currentStep = 3) }
    }

    fun completeOnboarding() {
        val mode = _uiState.value.selectedMode
        SettingsManager.saveActivationMode(mode)
        SettingsManager.saveLegalAccepted(true)
        SettingsManager.saveOnboardingCompleted(true)
    }
}
