package com.shuaiqiu.fuckets100

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import android.util.Log
import com.shuaiqiu.fuckets100.core.common.AccountAccess
import com.shuaiqiu.fuckets100.core.common.AppBuildInfo
import android.widget.Toast
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/**
 * Fe 应用 Application 类
 * 负责初始化 Sui (Magisk 模块) 和 Shizuku
 */
@HiltAndroidApp
class FeApplication : Application() {
    @Inject lateinit var buildInfo: AppBuildInfo
    @Inject lateinit var accountAccess: AccountAccess
    @Inject lateinit var remoteConfigManager: RemoteConfigManager
    @Inject lateinit var legalAgreementRepository: LegalAgreementRepository
    @Inject lateinit var updateStatusRepository: AppUpdateStatusRepository

    // 应用范围的协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "FeApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // 清理分享缓存文件
        cleanupShareCache()

        // 初始化设置管理器
        SettingsManager.init(this)

        // 答案解析授权改为设备内本地签发（见 LocalResolverAuthService），
        // 不再依赖 LastStudio 账号或远端 signingPublicKey。
        ResolverAuthorizer.init(
            buildInfo = buildInfo,
            accountAccess = accountAccess,
        )
        updateStatusRepository.remoteRefreshRequest = { ignoreVerification ->
            checkRemoteConfig(ignoreVerification)
        }

        // 法务协议仓库由 Hilt 创建并已载入本地缓存，这里静默同步云端版本。
        applicationScope.launch {
            legalAgreementRepository.refresh()
        }

        // 初始化Sui (Magisk 模块)
        ShizukuRuntimeState.isSui = Sui.init(BuildConfig.APPLICATION_ID)
        Log.d(TAG, "Sui initialized: ${ShizukuRuntimeState.isSui}")

        // 设置Shizuku监听器
        setupShizukuListeners()

        // 检查远程配置
        checkRemoteConfig()

        Log.d(TAG, "FeApplication onCreate | isSui: ${ShizukuRuntimeState.isSui}")
    }


    /**
     * 清理分享产生的缓存文件（PNG图片）
     * 每次启动时自动清理，避免缓存无限增长
     */
    private fun cleanupShareCache() {
        try {
            val cacheDir = cacheDir
            val files = cacheDir.listFiles() ?: return
            var count = 0
            for (file in files) {
                if (file.name.startsWith("Fe_Answer_") && file.name.endsWith(".png")) {
                    if (file.delete()) count++
                }
            }
            if (count > 0) {
                Log.d(TAG, "Cleaned up $count share cache files")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup share cache", e)
        }
    }

    /**
     * 检查远程配置
     * 包含KillSwitch检查和提示消息显示,控制应用是否显示"程序异常"提示
     * 宝贝如果网络失败或超时会闪退喵~
     */
    private fun checkRemoteConfig(ignoreVerification: Boolean = false) {
        applicationScope.launch {
            try {
                val status = if (ignoreVerification) {
                    remoteConfigManager.checkStatusIgnoringVerification()
                } else {
                    remoteConfigManager.checkStatus()
                }
                updateStatusRepository.setRemoteStatus(status)

                when {
                    status.isKillSwitch -> {
                        // Account gate owns recoverable blocking UI; never terminate the process.
                        Log.w(TAG, "KillSwitch activated")
                    }
                    status.showDialog -> {
                        // 有新版本，显示更新弹窗，通过 Flow 通知
                        updateStatusRepository.setUpdateStatus(status)
                        Log.i(TAG, "保存更新状态: showDialog=${status.showDialog}, message='${status.message}', isForce=${status.isForce}")
                    }
                    status.requiresVerification -> {
                        Log.w(TAG, "启动验证未通过，等待用户输入验证码")
                    }
                    status.noticeMessage.isNotEmpty() -> {
                        // 有公告，只显示 Toast
                        Log.i(TAG, "显示公告 Toast: ${status.noticeMessage}")
                        Toast.makeText(this@FeApplication, status.noticeMessage, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: RemoteConfigManager.NetworkException) {
                // The account gate presents a retryable network error and preserves credentials.
                Log.e(TAG, "远程配置获取失败: ${e.message}")
            }
        }
    }

    private fun setupShizukuListeners() {
        // 监听Binder接收事件 - 收到binder后调用onShizukuBinderReceived
        Shizuku.addBinderReceivedListenerSticky {
            onShizukuBinderReceived()
        }

        Shizuku.addBinderDeadListener {
            onShizukuBinderDead()
        }
    }

    private fun onShizukuBinderReceived() {
        ShizukuRuntimeState.isBinderReceived = true
        try {
            ShizukuRuntimeState.version = Shizuku.getVersion()
            ShizukuRuntimeState.uid = Shizuku.getUid()
            Log.d(TAG, "Shizuku connected! Version: ${ShizukuRuntimeState.version}, UID: ${ShizukuRuntimeState.uid}")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Shizuku info", e)
        }
    }

    private fun onShizukuBinderDead() {
        ShizukuRuntimeState.isBinderReceived = false
        ShizukuRuntimeState.uid = -1
        Log.d(TAG, "Shizuku disconnected")
    }

    /**
     * 检查Shizuku是否可用
     */
    fun staticCheckShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取Shizuku运行时类型
     */
    fun staticGetRuntimeType(): String {
        return when {
            ShizukuRuntimeState.isSui -> "Sui (Magisk)"
            ShizukuRuntimeState.isBinderReceived -> when (ShizukuRuntimeState.uid) {
                0 -> "Shizuku (Root)"
                2000 -> "Shizuku (ADB)"
                else -> "Shizuku (UID: $ShizukuRuntimeState.uid)"
            }
            else -> "Not Available"
        }
    }
}
