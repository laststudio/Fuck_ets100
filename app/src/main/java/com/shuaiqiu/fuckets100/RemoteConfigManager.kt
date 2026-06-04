package com.shuaiqiu.fuckets100

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URL
import java.net.URLConnection

/**
 * 远程配置管理器
 * 单例模式，使用多线程进行网络请求
 * 宝贝如果5秒内无法获取配置或网络失败，应用会闪退喵~
 */
object RemoteConfigManager {
    private const val TAG = "RemoteConfigManager"
    private val CONFIG_URLS = listOf(
        "https://raw.giteeusercontent.com/qiuqiqiuqid/fe_config/raw/master/config.json",
        "https://raw.githubusercontent.com/laststudio/Fe_config/main/config.json"
    )
    private const val DEFAULT_CHANGELOG_URL = "https://raw.githubusercontent.com/laststudio/Fe_config/main/update.md"
    private const val TIMEOUT_MS = 5000L  // 5秒超时喵~
    
    /**
     * 网络异常 - 用于标识网络问题导致配置获取失败
     */
    class NetworkException(message: String) : Exception(message)

    private fun RemoteConfig.toUpdateStatus(
        isKillSwitch: Boolean,
        showDialog: Boolean,
        message: String,
        isForce: Boolean,
        updateUrl: String,
        noticeMessage: String
    ): UpdateStatus {
        return UpdateStatus(
            isKillSwitch = isKillSwitch,
            showDialog = showDialog,
            message = message,
            isForce = isForce,
            updateUrl = updateUrl,
            noticeMessage = noticeMessage,
            announcementTitle = announcementTitle,
            announcementMessage = announcementMessage,
            announcementUpdatedAt = announcementUpdatedAt,
            announcementUrl = announcementUrl,
            changelogUrl = changelogUrl,
            changelogTitle = changelogTitle,
            changelogSummary = changelogSummary,
            donateEnabled = donateEnabled
        )
    }
    
    private suspend fun fetchFirstAvailableConfig(): Triple<String, String, Long> {
        val errors = mutableListOf<String>()

        for (configUrl in CONFIG_URLS) {
            Log.d(TAG, "📡 尝试 URL: $configUrl")
            val startTime = System.currentTimeMillis()
            try {
                val response = withTimeoutOrNull(TIMEOUT_MS) {
                    val urlConnection = URL(configUrl).openConnection() as URLConnection
                    urlConnection.connectTimeout = TIMEOUT_MS.toInt()
                    urlConnection.readTimeout = TIMEOUT_MS.toInt()
                    urlConnection.connect()
                    urlConnection.inputStream.bufferedReader().readText()
                }

                if (response.isNullOrBlank()) {
                    errors += "$configUrl: empty or timeout"
                    Log.w(TAG, "⚠️ 配置源失败: $configUrl empty or timeout")
                    continue
                }

                return Triple(response, configUrl, System.currentTimeMillis() - startTime)
            } catch (e: Exception) {
                errors += "$configUrl: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "⚠️ 配置源失败: $configUrl ${e.message}")
            }
        }

        throw NetworkException("所有远程配置源均失败: ${errors.joinToString(" | ")}")
    }
    /**
     * 获取远程配置
     * 在 IO 线程执行，不阻塞主线程
     * 宝贝如果5秒内无法获取配置或网络失败，会抛出异常喵~
     * 
     * @param context Context
     * @return RemoteConfig
     * @throws NetworkException 网络失败或超时时抛出
     */
    suspend fun fetchConfig(): RemoteConfig {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "═══════════════════════════════════════════")
                Log.d(TAG, "🌐 开始连接远程配置服务器...")
                Log.d(TAG, "⏱️ 超时设置: ${TIMEOUT_MS}ms")

                val (response, sourceUrl, elapsedMs) = fetchFirstAvailableConfig()
                Log.d(TAG, "✅ 连接成功! 响应时间: ${elapsedMs}ms")
                Log.d(TAG, "📡 成功来源: $sourceUrl")
                Log.d(TAG, "📦 原始响应: ${response.take(200)}...")

                val json = JSONObject(response)
                
                val noticeMessage = json.optString("noticeMessage", "")
                val announcementMessage = json.optString("announcementMessage", noticeMessage)
                val changelogSummary = json.optString("changelogSummary", "")
                val changelogUrl = json.optString("changelogUrl", DEFAULT_CHANGELOG_URL)

                val config = RemoteConfig(
                    latestVersionCode = json.optInt("latestVersionCode", BuildConfig.VERSION_CODE),
                    updateUrl = json.optString("updateUrl", ""),
                    isKillSwitchOn = json.optBoolean("isKillSwitchOn", false),
                    isForce = json.optBoolean("isForce", false),
                    updateMessage = json.optString("updateMessage", ""),
                    noticeMessage = noticeMessage,
                    announcementTitle = json.optString("announcementTitle", "").ifBlank {
                        if (announcementMessage.isNotBlank()) "公告" else ""
                    },
                    announcementMessage = announcementMessage,
                    announcementUpdatedAt = json.optString("announcementUpdatedAt", ""),
                    announcementUrl = json.optString("announcementUrl", ""),
                    changelogUrl = changelogUrl,
                    changelogTitle = json.optString("changelogTitle", "").ifBlank {
                        if (changelogSummary.isNotBlank() || changelogUrl.isNotBlank()) "更新日志" else ""
                    },
                    changelogSummary = changelogSummary,
                    donateEnabled = json.optBoolean("donateEnabled", true)
                )
                
                Log.d(TAG, "📋 解析后的配置:")
                Log.d(TAG, "   - latestVersionCode: ${config.latestVersionCode}")
                Log.d(TAG, "   - updateUrl: ${config.updateUrl}")
                Log.d(TAG, "   - isKillSwitchOn: ${config.isKillSwitchOn}")
                Log.d(TAG, "   - isForce: ${config.isForce}")
                Log.d(TAG, "   - updateMessage: ${config.updateMessage.take(120)}")
                Log.d(TAG, "   - noticeMessage: ${config.noticeMessage.take(120)}")
                Log.d(TAG, "   - announcementTitle: ${config.announcementTitle}")
                Log.d(TAG, "   - announcementMessage: ${config.announcementMessage.take(120)}")
                Log.d(TAG, "   - announcementUpdatedAt: ${config.announcementUpdatedAt}")
                Log.d(TAG, "   - announcementUrl: ${config.announcementUrl}")
                Log.d(TAG, "   - changelogUrl: ${config.changelogUrl}")
                Log.d(TAG, "   - changelogTitle: ${config.changelogTitle}")
                Log.d(TAG, "   - changelogSummary: ${config.changelogSummary.take(120)}")
                Log.d(TAG, "   - donateEnabled: ${config.donateEnabled}")
                Log.d(TAG, "   - 当前版本: ${BuildConfig.VERSION_CODE}")
                Log.d(TAG, "═══════════════════════════════════════════")
                
                config
            } catch (e: Exception) {
                when (e) {
                    is NetworkException -> {
                        Log.e(TAG, "❌ 网络异常: ${e.message}")
                    }
                    else -> {
                        Log.e(TAG, "❌ 连接失败: ${e.message}")
                        Log.e(TAG, "   异常类型: ${e.javaClass.simpleName}")
                    }
                }
                // 网络失败时抛出异常，由调用者处理（闪退）
                throw NetworkException(e.message ?: "未知网络错误")
            }
        }
    }
    
    /**
     * 检查是否需要更新或锁定
     * 在 IO 线程执行，不阻塞主线程
     * 宝贝如果网络失败会抛出异常喵~
     *
     * @param context Context
     * @return UpdateStatus 更新状态
     * @throws NetworkException 网络失败或超时时抛出
     */
    suspend fun checkStatus(): UpdateStatus {
        val config = fetchConfig()
        
        return when {
            config.isKillSwitchOn -> {
                Log.w(TAG, "🚨 KillSwitch 开启! 应用即将退出")
                // KillSwitch 开启，显示"程序异常"并退出
                config.toUpdateStatus(
                    isKillSwitch = true,
                    showDialog = false,
                    message = "",
                    isForce = false,
                    updateUrl = "",
                    noticeMessage = "程序异常"
                )
            }
            config.latestVersionCode > BuildConfig.VERSION_CODE -> {
                Log.i(TAG, "📢 发现新版本: ${config.latestVersionCode} (当前: ${BuildConfig.VERSION_CODE})")
                Log.d(TAG, "📢 updateMessage: '${config.updateMessage}', updateUrl: '${config.updateUrl}'")
                // 有新版本，显示更新弹窗，消息使用云端返回的 updateMessage
                config.toUpdateStatus(
                    isKillSwitch = false,
                    showDialog = true,
                    message = config.updateMessage.ifEmpty { "发现新版本: ${config.latestVersionCode}" },
                    isForce = config.isForce,
                    updateUrl = config.updateUrl,
                    noticeMessage = ""
                )
            }
            config.noticeMessage.isNotEmpty() -> {
                Log.i(TAG, "📢 收到公告: ${config.noticeMessage}")
                // 有公告，只显示 Toast，不弹窗
                config.toUpdateStatus(
                    isKillSwitch = false,
                    showDialog = false,
                    message = "",
                    isForce = false,
                    updateUrl = "",
                    noticeMessage = config.noticeMessage
                )
            }
            else -> {
                Log.d(TAG, "✅ 已是最新版本，无需更新")
                config.toUpdateStatus(
                    isKillSwitch = false,
                    showDialog = false,
                    message = "",
                    isForce = false,
                    updateUrl = "",
                    noticeMessage = ""
                )
            }
        }
    }
}


