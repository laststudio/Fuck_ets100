package com.shuaiqiu.fuckets100

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/**
 * Fe 应用 Application：只初始化本地能力（Shizuku/Sui/设置）。
 * 不再做远程配置、公告、更新检查、法务协议云端同步或 Resolver 授权初始化。
 */
@HiltAndroidApp
class FeApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "FeApplication"
    }

    override fun onCreate() {
        super.onCreate()

        cleanupShareCache()

        SettingsManager.init(this)

        ShizukuRuntimeState.isSui = Sui.init(BuildConfig.APPLICATION_ID)
        Log.d(TAG, "Sui initialized: " + ShizukuRuntimeState.isSui)

        setupShizukuListeners()

        Log.d(TAG, "FeApplication onCreate | isSui: " + ShizukuRuntimeState.isSui)
    }

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
                Log.d(TAG, "Cleaned up " + count + " share cache files")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup share cache", e)
        }
    }

    private fun setupShizukuListeners() {
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
            Log.d(TAG, "Shizuku connected! Version: " + ShizukuRuntimeState.version + ", UID: " + ShizukuRuntimeState.uid)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Shizuku info", e)
        }
    }

    private fun onShizukuBinderDead() {
        ShizukuRuntimeState.isBinderReceived = false
        ShizukuRuntimeState.uid = -1
        Log.d(TAG, "Shizuku disconnected")
    }

    fun staticCheckShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun staticGetRuntimeType(): String {
        return when {
            ShizukuRuntimeState.isSui -> "Sui (Magisk)"
            ShizukuRuntimeState.isBinderReceived -> when (ShizukuRuntimeState.uid) {
                0 -> "Shizuku (Root)"
                2000 -> "Shizuku (ADB)"
                else -> "Shizuku (UID: " + ShizukuRuntimeState.uid + ")"
            }
            else -> "Not Available"
        }
    }
}
