package com.shuaiqiu.fuckets100

import android.content.Context
import android.util.Log
import com.shuaiqiu.fuckets100.core.common.AccountAccess
import com.shuaiqiu.fuckets100.core.common.AccountAccessException
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

    /**
     * 动态验签公钥提供者（由 app 层注入，通常是 RemoteConfigManager.signingPublicKey()）。
     *
     * 后端 `/software/fe/config` 会下发 `signingPublicKey`，客户端把它传给解析器，
     * 于是**换钥只需服务端改配置，无需发版**。
     *
     * 安全说明：这里只决定"用哪把公钥去验签"，不参与"是否放行"——
     * 解析器内部仍然强制 Ed25519 验签；服务端若下发了错误的公钥，结果只是验签失败，
     * 绝不会变成"免验签放行"。
     */
    @Volatile private var signingPublicKeyProvider: (() -> String?)? = null

    fun init(
        buildInfo: AppBuildInfo,
        accountAccess: AccountAccess,
        signingPublicKeyProvider: (() -> String?)? = null,
    ) {
        this.buildInfo = buildInfo
        this.accountAccess = accountAccess
        this.signingPublicKeyProvider = signingPublicKeyProvider
    }

    /** 当前应使用的验签公钥：优先后端下发值，回退编译期内置常量。 */
    private fun currentSigningPublicKey(): String =
        signingPublicKeyProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() } ?: PUBLIC_KEY_B64

    private fun account(): AccountAccess = accountAccess
        ?: error("AccountAccess 尚未初始化")

    const val BASE_URL = "https://api.lastudio.cc"
    const val SOFTWARE_KEY = "fe"
    const val FEATURE_KEY = "answer_parser"

    // 生产后端 Ed25519 公钥 (urlsafe base64 no-pad)。换钥时需同步更新。
    const val PUBLIC_KEY_B64 = "8i06qnGdq6lm6wj8NDm22Nh0_nP6_k38Kg_Lz0xHIiY"

    @Volatile private var lastCloudAuthorization: FeatureAuthorizationCache? = null
    @Volatile private var lastResolverAuthorization: FeatureAuthorizationCache? = null
    private const val AUTH_CACHE_TTL_MS = 3 * 60 * 1000L // 3 分钟内存缓存，避免频繁点击重复请求网络

    private const val TAG = "ResolverAuthorizer"

    @Volatile private var localNativeConfigured = false

    /**
     * 解析器离线本地授权初始化（本项目已移除作者后端）。
     *
     * 只做本地配置：写入 base_url / software_key / feature_key / 公钥 / 版本号 +
     * 本地占位令牌，然后 setAuthEnabled(true)。不会发起任何网络请求；
     * libresolver.so 已打补丁跳过服务端 grant 校验，因此本地读取与云端解析都可直接使用。
     *
     * 幂等、线程安全；重复调用无副作用。
     */
    fun ensureLocalNativeConfigured(context: Context) {
        if (localNativeConfigured) return
        synchronized(this) {
            if (localNativeConfigured) return
            val versionCode = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            }.getOrDefault(0L)
            runCatching {
                ResolverNative.setIntegrityCheckEnabled(false)
                val rc = ResolverNative.setAuthConfig(
                    baseUrl = BASE_URL,
                    softwareKey = SOFTWARE_KEY,
                    featureKey = FEATURE_KEY,
                    publicKeyB64 = PUBLIC_KEY_B64,
                    appVersionCode = versionCode,
                )
                if (rc != 0) {
                    Log.w(TAG, "setAuthConfig 失败: code=" + rc + "（解析器可能无法工作）")
                    return@synchronized
                }
                // 本地占位令牌：只满足解析器的非空校验，不会上传到任何服务器。
                ResolverNative.setAccessToken("offline-local-read-only")
                ResolverNative.setAuthEnabled(true)
                localNativeConfigured = true
                Log.i(TAG, "解析器本地授权已初始化（离线模式，versionCode=" + versionCode + "）")
            }.onFailure { Log.e(TAG, "解析器本地授权初始化异常", it) }
        }
    }

    /** 诊断信息（用于错误提示/日志，不包含隐私数据）。 */
    fun localAuthStatus(): String = runCatching {
        "authEnabled=" + ResolverNative.isAuthEnabled() + ", configured=" + localNativeConfigured
    }.getOrDefault("authEnabled=unknown")

    override fun invalidateAuthCache() {
        lastCloudAuthorization = null
        lastResolverAuthorization = null
    }

    /**
     * 内部授权门控。
     * @throws ResolverAuthorizationDeniedException 未登录/功能关闭/网络失败等
     */
    suspend fun requireAuthorized(context: Context) {
        // 离线改版：本地解析不再需要 LastStudio 授权；这里只确保解析器已在本地完成配置。
        ensureLocalNativeConfigured(context)
    }

    /** 配置 SO 共用的后端、签名公钥和 access token，不提前指定实际功能权限。 */
    suspend fun requireConfigured(context: Context) {
        // 离线改版：不再向作者后端下发任何授权配置，只做本地离线初始化。
        ensureLocalNativeConfigured(context)
    }

    suspend fun requireCloudAuthorized(context: Context) {
        // 离线改版：云端模式不再需要作者后端授权；解析器本地初始化即可。
        ensureLocalNativeConfigured(context)
    }

    private suspend fun ensureCloudAuthorized(context: Context): String? = withContext(Dispatchers.IO) {
        val manager = account()
        val (available, unavailableReason) = manager.isFeatureAvailable("cloud_mode")
        if (!available) {
            invalidateAuthCache()
            return@withContext unavailableReason ?: "云端模式当前版本不可用"
        }
        val token = runCatching { manager.requireAccessToken() }.getOrNull()
        if (token.isNullOrBlank()) {
            invalidateAuthCache()
            return@withContext "请先登录 LastStudio 账号"
        }
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
    suspend fun ensureAuthorized(context: Context): String? {
        return null
    }

    @Suppress("unused")
    private suspend fun legacyEnsureAuthorized(context: Context): String? = withContext(Dispatchers.IO) {
        val manager = account()
        val (available, unavailableReason) = manager.isFeatureAvailable(FEATURE_KEY)
        if (!available) {
            invalidateAuthCache()
            return@withContext unavailableReason ?: "答案解析功能当前版本不可用"
        }
        val token = runCatching { manager.requireAccessToken() }.getOrNull()
        if (token.isNullOrBlank()) {
            invalidateAuthCache()
            return@withContext "请先登录 LastStudio 账号"
        }
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
            if (manager.isGateChecking) {
                manager.checkGate()
            }
            val token = manager.requireAccessToken()
            // 先验证/刷新会话，再同步策略，避免使用刷新前的旧开关。
            val shouldCheck = integrityEnabled ?: manager.isIntegrityCheckEnabled()
            ResolverNative.setIntegrityCheckEnabled(shouldCheck)
            AppIntegrityGuard.ensureResolver(context, buildInfo ?: return@withContext "授权信息未初始化", enabled = shouldCheck)
            val rc = ResolverNative.setAuthConfig(
                baseUrl = BASE_URL,
                softwareKey = SOFTWARE_KEY,
                featureKey = FEATURE_KEY,
                publicKeyB64 = currentSigningPublicKey(),
                appVersionCode = (buildInfo?.versionCode ?: 0).toLong(),
            )
            if (rc != 0) {
                return@withContext "解析器内部授权配置失败 (code=$rc)"
            }
            ResolverNative.setAccessToken(token)
            ResolverNative.setAuthEnabled(true)
            null
        } catch (e: AppIntegrityGuard.IntegrityFailure) {
            e.message
        } catch (e: AccountAccessException) {
            if (e.notAuthenticated) "请先登录 LastStudio 账号" else e.message
        } catch (e: Exception) {
            "无法连接授权服务，请检查网络后重试"
        }
    }

    private suspend fun configureCloud(context: Context, integrityEnabled: Boolean? = null): String? = withContext(Dispatchers.IO) {
        try {
            val manager = account()
            if (manager.isGateChecking) {
                manager.checkGate()
            }
            val token = manager.requireAccessToken()
            // 先验证/刷新会话，再同步策略，避免使用刷新前的旧开关。
            val shouldCheck = integrityEnabled ?: manager.isIntegrityCheckEnabled()
            CloudNative.setIntegrityCheckEnabled(shouldCheck)
            AppIntegrityGuard.ensureCloud(context, buildInfo ?: return@withContext "授权信息未初始化", enabled = shouldCheck)
            val rc = CloudNative.setAuthConfig(
                baseUrl = BASE_URL,
                softwareKey = SOFTWARE_KEY,
                featureKey = "cloud_mode",
                publicKeyB64 = currentSigningPublicKey(),
                appVersionCode = (buildInfo?.versionCode ?: 0).toLong(),
            )
            if (rc != 0) return@withContext "云端库授权配置失败 (code=$rc)"
            CloudNative.setAccessToken(token)
            CloudNative.setAuthEnabled(true)
            null
        } catch (e: AppIntegrityGuard.IntegrityFailure) {
            e.message
        } catch (e: AccountAccessException) {
            if (e.notAuthenticated) "请先登录 LastStudio 账号" else e.message
        } catch (e: Exception) {
            "无法连接云端授权服务，请检查网络后重试"
        }
    }
}

/** 答案解析功能被后端阻断时抛出。 */
class ResolverAuthorizationDeniedException(message: String) : Exception(message)

