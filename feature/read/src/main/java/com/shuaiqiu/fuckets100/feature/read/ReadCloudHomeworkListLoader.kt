package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100AuthManager
import com.shuaiqiu.fuckets100.ResolverAuthorizer
import com.shuaiqiu.fuckets100.ResolverAuthorizationDeniedException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal sealed interface CloudHomeworkListResult {
    data class Success(
        val homeworks: List<ETS100ApiClient.HomeworkInfo>,
        val baseUrl: String,
    ) : CloudHomeworkListResult

    /** 讯飞企业账号 Token 失效，需要重新走网页登录。 */
    data class ChangyanExpired(val message: String) : CloudHomeworkListResult

    data class Failure(val message: String) : CloudHomeworkListResult
}

/**
 * 云端作业列表读取（纯本地链路）。
 *
 * 只依赖本机保存的 ETS100 登录态（Token / 手机号 / 密码 / 机器码）：
 * 1. 优先使用已保存 Token 拉取作业列表；
 * 2. Token 失效时，若是账号密码登录则用本地保存的密码重新登录（不经过任何后端）；
 * 3. 讯飞网页登录的账号则提示重新进行网页登录。
 *
 * 不持有任何 Compose 状态；调用方负责把结果写入 UiState 与持久化缓存。
 */
internal suspend fun loadCloudHomeworkListResult(
    context: Context,
    status: String,
    accountOnline: Boolean,
): CloudHomeworkListResult {
    if (!accountOnline) return CloudHomeworkListResult.Failure("已离线")

    try {
        ResolverAuthorizer.requireCloudAuthorized(context)
    } catch (e: ResolverAuthorizationDeniedException) {
        return CloudHomeworkListResult.Failure(e.message ?: "云端模式当前不可用")
    }

    if (!ETS100AuthManager.isLoggedIn(context)) {
        return CloudHomeworkListResult.Failure("尚未登录 ETS100 账号，请先在运行授权页登录")
    }

    val isChangyanLogin = ETS100AuthManager.isChangyanWebLogin(context)
    var savedToken = ETS100AuthManager.getToken(context).orEmpty()
    var parentId = ETS100AuthManager.getParentAccountId(context).orEmpty()
    if (savedToken.isEmpty() || parentId.isEmpty()) {
        return CloudHomeworkListResult.Failure("登录信息不完整，请重新登录 ETS100 账号")
    }

    ReadCloudLog.info("☁️ 开始加载${cloudHomeworkStatusLabel(status)}列表")

    val requestStatuses = cloudHomeworkRequestStatuses(status)
    var lastError: Throwable? = null

    suspend fun requestHomeworkLists(tokenForRequest: String): List<ETS100ApiClient.HomeworkListResponse> {
        return coroutineScope {
            requestStatuses.map { requestStatus ->
                async {
                    requestStatus to ETS100ApiClient.getHomeworkList(tokenForRequest, parentId, requestStatus)
                }
            }.awaitAll().mapNotNull { (requestStatus, result) ->
                result.onSuccess { response ->
                    ReadCloudLog.success("✓ status=${requestStatus} 获取到 ${response.homeworks.size} 个作业")
                    ReadCloudLog.info("📡 API 返回的 base_url = ${response.baseUrl}")
                    response.homeworks.forEachIndexed { index, homework ->
                        ReadCloudLog.debug(
                            "   ├─ 作业[${index}] id=${homework.id.ifBlank { "<empty>" }}, " +
                                "name=${homework.name}, contents=${homework.contents.size}"
                        )
                    }
                }.onFailure { e ->
                    lastError = e
                    val isClassError = e.message?.contains("80004") == true || e.message?.contains("有效班级") == true
                    val displayMsg = if (isClassError && status == CloudHomeworkState.STATUS_CURRENT) {
                        "当前账号无有效班级或新作业，可切换至上方「历史作业」查看已归档的作业与答案"
                    } else {
                        e.message
                    }
                    ReadCloudLog.error("✗ status=${requestStatus} 获取作业列表失败: ${displayMsg}")
                }.getOrNull()
            }
        }
    }

    ReadCloudLog.info("🔐 使用本地保存的 Token 请求作业列表")
    var responses = requestHomeworkLists(savedToken)

    if (responses.isEmpty()) {
        if (isChangyanLogin) {
            val login = ETS100AuthManager.getPhone(context).orEmpty()
            ReadCloudLog.warn("企业登录 Token 请求失败，等待重新讯飞登录；login=${login}")
            return CloudHomeworkListResult.ChangyanExpired("企业账号 Token 已失效，请重新进行讯飞登录")
        }

        // 账号密码登录：完全在本地用保存的密码重新登录，不经过任何后端。
        val phone = ETS100AuthManager.getPhone(context)
        val savedPassword = ETS100AuthManager.getPassword(context)
        val deviceCode = ETS100AuthManager.getDeviceCode(context)
        if (phone.isNullOrBlank() || savedPassword.isNullOrBlank()) {
            return CloudHomeworkListResult.Failure("登录已过期，请重新登录 ETS100 账号")
        }
        try {
            ReadCloudLog.info("🔁 本地账号密码重新登录：${phone}")
            val loginResponse = ETS100ApiClient.login(phone, savedPassword, deviceCode).getOrThrow()
            val parentAccountId = ETS100ApiClient.getEcardList(loginResponse.token).getOrThrow()
            ETS100AuthManager.saveLoginInfo(context, phone, loginResponse.token, parentAccountId)
            ETS100AuthManager.saveLoginMethod(context, ETS100AuthManager.LOGIN_METHOD_PASSWORD)
            savedToken = loginResponse.token
            parentId = parentAccountId
            ReadCloudLog.success("✓ 本地重新登录成功，Token 已更新，正在重试作业列表")
            responses = requestHomeworkLists(savedToken)
        } catch (e: ETS100ApiClient.SmsVerificationRequiredException) {
            lastError = e
            ReadCloudLog.error("✗ 账号需要短信验证，请重新登录")
        } catch (e: ETS100ApiClient.DeviceBindRequiredException) {
            lastError = e
            ReadCloudLog.error("✗ 设备需要重新绑定，请重新登录")
        } catch (e: Exception) {
            lastError = e
            ReadCloudLog.error("✗ 本地重新登录失败: ${e.message}")
        }
        if (responses.isEmpty()) {
            val isClassError = lastError?.message?.let { it.contains("80004") || it.contains("有效班级") } == true
            val message = if (isClassError && status == CloudHomeworkState.STATUS_CURRENT) {
                "当前账号无有效班级，可切换至「历史作业」查看往期归档答案"
            } else {
                lastError?.message ?: "获取作业列表失败"
            }
            return CloudHomeworkListResult.Failure(message)
        }
    }

    val mergedHomeworks = responses.flatMap { it.homeworks }
    val latestBaseUrl = responses.lastOrNull()?.baseUrl
    ReadCloudLog.success("✓ 合并得到 ${mergedHomeworks.size} 个${cloudHomeworkStatusLabel(status)}")
    mergedHomeworks.forEachIndexed { index, homework ->
        ReadCloudLog.info(
            "📚 合并作业[${index}]: id=${homework.id.ifBlank { "<empty>" }}, " +
                "name=${homework.name}, contents=${homework.contents.size}, " +
                "key=${cloudHomeworkCacheKey(status, homework)}"
        )
    }
    return CloudHomeworkListResult.Success(
        homeworks = mergedHomeworks,
        baseUrl = latestBaseUrl.orEmpty(),
    )
}
