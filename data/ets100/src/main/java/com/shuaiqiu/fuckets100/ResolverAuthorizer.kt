package com.shuaiqiu.fuckets100

import android.content.Context
import com.shuaiqiu.fuckets100.core.common.AccountAccess
import com.shuaiqiu.fuckets100.core.common.AppBuildInfo
import com.shuaiqiu.fuckets100.core.common.AuthCacheInvalidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 答案解析 (answer_parser) 功能的内部授权门控。
 *
 * 调用 libresolver.so 内部联网授权: 后端 /client-auth/features/authorize
 * 返回 Ed25519 签名 grant, Native 层验签通过才放行。后台关闭该功能后,
 * 下一次授权请求立即被拒绝, 答案解析随之阻断。
 *
 * 服务器公钥需与生产后端一致 (可在后台 `软件认证 -> 签名公钥` 查看)。
 */
object ResolverAuthorizer : AuthCacheInvalidator {

    /** 由 Application 注入，避免 data 层反向依赖 app 层 BuildConfig。 */
    @Volatile private var buildInfo: AppBuildInfo? = null
    @Volatile private var accountAccess: AccountAccess? = null

    fun init(
        buildInfo: AppBuildInfo,
        accountAccess: AccountAccess,
    ) {
        this.buildInfo = buildInfo
        this.accountAccess = accountAccess
    }

    private fun account(): AccountAccess = accountAccess
        ?: error("AccountAccess 尚未初始化")

    const val SOFTWARE_KEY = "fe"
    const val FEATURE_KEY = "answer_parser"

    /**
     * 离线授权令牌：只作为 `Authorization: SoftwareAccess` 的占位值，
     * 由本地授权服务忽略，不再依赖 LastStudio 账号。
     */
    private const val LOCAL_ACCESS_TOKEN = "local-device"

    /**
     * 本地授权服务的 Ed25519 公钥 (urlsafe base64 no-pad)，
     * 与 [LocalResolverAuthService] 内置私钥配对，签名口径与后端 `sign_grant` 一致。
     */
    const val PUBLIC_KEY_B64 = LocalResolverAuthService.PUBLIC_KEY_B64

    @Volatile private var lastCloudAuthorization: FeatureAuthorizationCache? = null
    @Volatile private var lastResolverAuthorization: FeatureAuthorizationCache? = null
    private const val AUTH_CACHE_TTL_MS = 3 * 60 * 1000L // 3 分钟内存缓存，避免频繁点击重复请求网络

    override fun invalidateAuthCache() {
        lastCloudAuthorization = null
        lastResolverAuthorization = null
    }

    /**
     * 内部授权门控。
     * @throws ResolverAuthorizationDeniedException 未登录/功能关闭/网络失败等
     */
    suspend fun requireAuthorized(context: Context) {
        val reason = ensureAuthorized(context)
        if (reason != null) throw ResolverAuthorizationDeniedException(reason)
    }

    /** 配置 SO 共用的后端、签名公钥和 access token，不提前指定实际功能权限。 */
    suspend fun requireConfigured(context: Context) {
        val reason = configureCloud(context)
        if (reason != null) throw ResolverAuthorizationDeniedException(reason)
    }

    suspend fun requireCloudAuthorized(context: Context) {
        val reason = ensureCloudAuthorized(context)
        if (reason != null) throw ResolverAuthorizationDeniedException(reason)
    }

    private suspend fun ensureCloudAuthorized(context: Context): String? = withContext(Dispatchers.IO) {
        val manager = account()
        val (available, unavailableReason) = manager.isFeatureAvailable("cloud_mode")
        if (!available) {
            invalidateAuthCache()
            return@withContext unavailableReason ?: "云端模式当前版本不可用"
        }
        // 不再依赖 LastStudio 账号：统一使用本地授权服务签发 grant。
        val token = LOCAL_ACCESS_TOKEN
        val now = System.currentTimeMillis()
        val integrityEnabled = manager.isIntegrityCheckEnabled()
        if (lastCloudAuthorization?.isValid(token, now, integrityEnabled, AUTH_CACHE_TTL_MS) == true) {
            return@withContext null
        }
        configureCloud(context, integrityEnabled)?.let {
            invalidateAuthCache()
            return@withContext it
        }
        try {
            val result = JSONObject(CloudNative.cloudAuthorizeFeature())
            if (result.optBoolean("ok", false) &&
                result.optJSONObject("result")?.optBoolean("allowed", false) == true
            ) {
                lastCloudAuthorization = FeatureAuthorizationCache(
                    token, System.currentTimeMillis(), integrityEnabled,
                )
                null
            } else {
                invalidateAuthCache()
                val errObj = result.optJSONObject("error")
                val reasonCode = errObj?.optString("reason") ?: result.optString("reason")
                val msg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: result.optString("message").takeIf { it.isNotBlank() }
                if (reasonCode == "membership_required" && (msg == null || msg.contains("停用") || msg.contains("不可用"))) {
                    "该功能需要开通会员"
                } else {
                    msg ?: "云端模式当前不可用"
                }
            }
        } catch (e: Exception) {
            "无法连接云端授权服务，请检查网络后重试"
        }
    }

    /**
     * @return null 表示授权通过; 否则返回阻断原因
     */
    suspend fun ensureAuthorized(context: Context): String? = withContext(Dispatchers.IO) {
        val manager = account()
        val (available, unavailableReason) = manager.isFeatureAvailable(FEATURE_KEY)
        if (!available) {
            invalidateAuthCache()
            return@withContext unavailableReason ?: "答案解析功能当前版本不可用"
        }
        // 不再依赖 LastStudio 账号：统一使用本地授权服务签发 grant。
        val token = LOCAL_ACCESS_TOKEN
        val now = System.currentTimeMillis()
        val integrityEnabled = manager.isIntegrityCheckEnabled()
        if (lastResolverAuthorization?.isValid(token, now, integrityEnabled, AUTH_CACHE_TTL_MS) == true) {
            return@withContext null
        }
        configureResolver(context, integrityEnabled)?.let {
            invalidateAuthCache()
            return@withContext it
        }
        try {
            val json = ResolverNative.authorizeFeature()
            val obj = JSONObject(json)
            if (obj.optBoolean("allowed", false)) {
                lastResolverAuthorization = FeatureAuthorizationCache(
                    token, System.currentTimeMillis(), integrityEnabled,
                )
                null
            } else {
                invalidateAuthCache()
                val errObj = obj.optJSONObject("error")
                val reasonCode = errObj?.optString("reason") ?: obj.optString("reason")
                val msg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: obj.optString("message").takeIf { it.isNotBlank() }
                if (reasonCode == "membership_required" && (msg == null || msg.contains("停用") || msg.contains("不可用"))) {
                    "该功能需要开通会员"
                } else {
                    msg ?: "该功能当前不可用"
                }
            }
        } catch (e: Exception) {
            "无法连接授权服务，请检查网络后重试"
        }
    }

    private suspend fun configureResolver(context: Context, integrityEnabled: Boolean? = null): String? = withContext(Dispatchers.IO) {
        try {
            val manager = account()
            val shouldCheck = integrityEnabled ?: manager.isIntegrityCheckEnabled()
            // 新版解析器不再提供 setIntegrityCheckEnabled：完整性策略固定在 so 内，
            // 客户端只通过 selfIntegrityCheck 自校验并按开关决定是否拦截。
            AppIntegrityGuard.ensureResolver(context, buildInfo ?: return@withContext "授权信息未初始化", enabled = shouldCheck)
            val rc = ResolverNative.setAuthConfig(
                baseUrl = LocalResolverAuthService.baseUrl(),
                softwareKey = SOFTWARE_KEY,
                featureKey = FEATURE_KEY,
                publicKeyB64 = PUBLIC_KEY_B64,
                appVersionCode = (buildInfo?.versionCode ?: 0).toLong(),
            )
            if (rc != 0) {
                return@withContext "解析器内部授权配置失败 (code=$rc)"
            }
            ResolverNative.setAccessToken(LOCAL_ACCESS_TOKEN)
            ResolverNative.setAuthEnabled(true)
            null
        } catch (e: AppIntegrityGuard.IntegrityFailure) {
            e.message
        } catch (e: Exception) {
            "本地授权服务初始化失败: ${e.message}"
        }
    }

    private suspend fun configureCloud(context: Context, integrityEnabled: Boolean? = null): String? = withContext(Dispatchers.IO) {
        try {
            val manager = account()
            val shouldCheck = integrityEnabled ?: manager.isIntegrityCheckEnabled()
            CloudNative.setIntegrityCheckEnabled(shouldCheck)
            AppIntegrityGuard.ensureCloud(context, buildInfo ?: return@withContext "授权信息未初始化", enabled = shouldCheck)
            val rc = CloudNative.setAuthConfig(
                baseUrl = LocalResolverAuthService.baseUrl(),
                softwareKey = SOFTWARE_KEY,
                featureKey = "cloud_mode",
                publicKeyB64 = PUBLIC_KEY_B64,
                appVersionCode = (buildInfo?.versionCode ?: 0).toLong(),
            )
            if (rc != 0) return@withContext "云端库授权配置失败 (code=$rc)"
            CloudNative.setAccessToken(LOCAL_ACCESS_TOKEN)
            CloudNative.setAuthEnabled(true)
            null
        } catch (e: AppIntegrityGuard.IntegrityFailure) {
            e.message
        } catch (e: Exception) {
            "本地云端授权服务初始化失败: ${e.message}"
        }
    }
}

/** 答案解析功能被后端阻断时抛出。 */
class ResolverAuthorizationDeniedException(message: String) : Exception(message)

