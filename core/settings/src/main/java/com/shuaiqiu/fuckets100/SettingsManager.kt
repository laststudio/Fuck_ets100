package com.shuaiqiu.fuckets100

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 设置管理器
 * 负责保存和读取应用的各种设置选项喵~
 */
object SettingsManager {
    
    private const val PREFS_NAME = "fe_settings"
    private const val KEY_ACTIVATION_MODE = "activation_mode"
    private const val KEY_LEGAL_ACCEPTED = "legal_accepted_v2"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed_v1"
    private const val KEY_AOSP_PREDICTIVE_BACK = "aosp_predictive_back"
    private const val KEY_PREDICTIVE_BACK_MODE = "predictive_back_mode"
    private const val KEY_PREDICTIVE_BACK_ANIMATION = "predictive_back_animation"
    private const val KEY_PREDICTIVE_BACK_EXIT_DIRECTION = "predictive_back_exit_direction"
    private const val KEY_LOCAL_VERIFICATION_CODE = "local_verification_code"
    private const val KEY_COMPACT_ANSWER_DISPLAY = "compact_answer_display"
    private const val KEY_SKIP_CLOUD_READ_WARNING = "skip_cloud_read_warning"
    private const val KEY_LAST_SEEN_ANNOUNCEMENT = "last_seen_announcement_updated_at"
    private const val KEY_LAST_STUDIO_SKIPPED = "last_studio_skipped"
    
    private lateinit var prefs: SharedPreferences
    
    /**
     * 初始化设置管理器，在 Application 启动时调用
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 保存激活模式设置
     */
    fun saveActivationMode(mode: ActivationMode) {
        prefs.edit {
            putString(KEY_ACTIVATION_MODE, mode.name)
        }
    }
    
    /**
     * 读取保存的激活模式设置
     * 返回 null 表示用户尚未选择激活模式
     */
    fun getSavedActivationMode(): ActivationMode? {
        val modeName = prefs.getString(KEY_ACTIVATION_MODE, null) ?: return null
        return try {
            ActivationMode.valueOf(modeName)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    
    /**
     * 检查用户是否已经选择了激活模式
     */
    fun hasUserSelectedMode(): Boolean {
        return prefs.contains(KEY_ACTIVATION_MODE)
    }
    
    /**
     * 清除保存的激活模式设置
     */
    fun clearActivationMode() {
        prefs.edit {
            remove(KEY_ACTIVATION_MODE)
        }
    }
    
    fun saveCompactAnswerDisplay(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_COMPACT_ANSWER_DISPLAY, enabled)
        }
    }

    fun getCompactAnswerDisplay(): Boolean {
        return prefs.getBoolean(KEY_COMPACT_ANSWER_DISPLAY, false)
    }

    fun saveSkipCloudReadWarning(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_SKIP_CLOUD_READ_WARNING, enabled)
        }
    }

    fun getSkipCloudReadWarning(): Boolean {
        return prefs.getBoolean(KEY_SKIP_CLOUD_READ_WARNING, false)
    }

    fun saveLegalAccepted(accepted: Boolean) {
        prefs.edit {
            putBoolean(KEY_LEGAL_ACCEPTED, accepted)
        }
    }

    fun hasAcceptedLegal(): Boolean {
        return prefs.getBoolean(KEY_LEGAL_ACCEPTED, false)
    }

    fun saveOnboardingCompleted(completed: Boolean) {
        prefs.edit {
            putBoolean(KEY_ONBOARDING_COMPLETED, completed)
        }
    }

    fun hasCompletedOnboarding(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    /**
     * 记录用户是否选择跳过 LastStudio 账号登录（仅使用 E听说账号）。
     */
    fun saveLastStudioSkipped(skipped: Boolean) {
        prefs.edit {
            putBoolean(KEY_LAST_STUDIO_SKIPPED, skipped)
        }
    }

    fun isLastStudioSkipped(): Boolean {
        return prefs.getBoolean(KEY_LAST_STUDIO_SKIPPED, false)
    }

    fun saveLocalVerificationCode(code: String) {
        prefs.edit {
            putString(KEY_LOCAL_VERIFICATION_CODE, code.trim())
        }
    }

    fun getLocalVerificationCode(): String {
        return prefs.getString(KEY_LOCAL_VERIFICATION_CODE, "").orEmpty()
    }

    fun getLastSeenAnnouncementUpdatedAt(): String {
        return prefs.getString(KEY_LAST_SEEN_ANNOUNCEMENT, "").orEmpty()
    }

    fun saveLastSeenAnnouncementUpdatedAt(value: String) {
        prefs.edit { putString(KEY_LAST_SEEN_ANNOUNCEMENT, value) }
    }

    fun savePredictiveBackAnimation(animation: PredictiveBackAnimation) {
        prefs.edit {
            putString(KEY_PREDICTIVE_BACK_ANIMATION, animation.value)
        }
    }

    fun getPredictiveBackAnimation(): PredictiveBackAnimation {
        prefs.getString(KEY_PREDICTIVE_BACK_ANIMATION, null)?.let {
            val animation = PredictiveBackAnimation.fromValueOrDefault(it)
            if (animation != PredictiveBackAnimation.Scale || it == animation.value) {
                return animation
            }
            savePredictiveBackAnimation(animation)
            return animation
        }

        val legacyAnimation = when (prefs.getString(KEY_PREDICTIVE_BACK_MODE, null)) {
            "AOSP", "KERNELSU_CLASSIC" -> PredictiveBackAnimation.Scale
            "SLIDE" -> PredictiveBackAnimation.MIUIX
            "NONE" -> PredictiveBackAnimation.None
            else -> null
        }

        if (legacyAnimation != null) {
            savePredictiveBackAnimation(legacyAnimation)
            return legacyAnimation
        }

        if (prefs.contains(KEY_AOSP_PREDICTIVE_BACK)) {
            val legacyAnimation = if (prefs.getBoolean(KEY_AOSP_PREDICTIVE_BACK, false)) {
                PredictiveBackAnimation.Scale
            } else PredictiveBackAnimation.MIUIX
            savePredictiveBackAnimation(legacyAnimation)
            return legacyAnimation
        }

        if (prefs.all.isNotEmpty()) {
            savePredictiveBackAnimation(PredictiveBackAnimation.Scale)
            return PredictiveBackAnimation.Scale
        }

        savePredictiveBackAnimation(PredictiveBackAnimation.Scale)
        return PredictiveBackAnimation.Scale
    }

    fun savePredictiveBackExitDirection(direction: PredictiveBackExitDirection) {
        prefs.edit {
            putString(KEY_PREDICTIVE_BACK_EXIT_DIRECTION, direction.value)
        }
    }

    fun getPredictiveBackExitDirection(): PredictiveBackExitDirection {
        return PredictiveBackExitDirection.fromValueOrDefault(
            prefs.getString(KEY_PREDICTIVE_BACK_EXIT_DIRECTION, "").orEmpty()
        )
    }
}

enum class PredictiveBackAnimation(val value: String) {
    None("none"),
    MIUIX("miuix"),
    Scale("scale");

    companion object {
        fun fromValueOrDefault(value: String) =
            entries.find { it.value == value } ?: Scale
    }
}

enum class PredictiveBackExitDirection(val value: String) {
    FOLLOW_GESTURE("follow_gesture"),
    ALWAYS_RIGHT("always_right"),
    ALWAYS_LEFT("always_left");

    companion object {
        fun fromValueOrDefault(value: String) =
            entries.find { it.value == value } ?: FOLLOW_GESTURE
    }
}

val PredictiveBackAnimation.label: String
    get() = when (this) {
        PredictiveBackAnimation.None -> "无"
        PredictiveBackAnimation.MIUIX -> "Miuix"
        PredictiveBackAnimation.Scale -> "缩放"
    }

val PredictiveBackExitDirection.label: String
    get() = when (this) {
        PredictiveBackExitDirection.FOLLOW_GESTURE -> "跟随手势"
        PredictiveBackExitDirection.ALWAYS_RIGHT -> "始终向右"
        PredictiveBackExitDirection.ALWAYS_LEFT -> "始终向左"
    }
