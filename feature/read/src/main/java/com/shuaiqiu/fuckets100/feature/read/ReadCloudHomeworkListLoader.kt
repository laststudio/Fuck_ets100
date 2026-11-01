package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100AuthManager
import com.shuaiqiu.fuckets100.FeAccountApi
import com.shuaiqiu.fuckets100.FeAccountManager
import com.shuaiqiu.fuckets100.ResolverAuthorizer
import com.shuaiqiu.fuckets100.ResolverAuthorizationDeniedException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

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
 * 云端作业列表读取（授权 → 账号会话 → 拉取 → 候选班级回退）。
 *
 * 只负责网络与凭据交互并返回结果，不持有任何 Compose 状态；调用方负责
 * 把结果写入 UiState 与持久化缓存。
 */
internal suspend fun loadCloudHomeworkListResult(
    context: Context,
    status: String,
    accountOnline: Boolean,
    accountManager: FeAccountManager,
): CloudHomeworkListResult {
    if (!accountOnline && !ETS100AuthManager.isLoggedIn(context)) {
        return CloudHomeworkListResult.Failure("请登录E听说账号")
    }

    try {
        ResolverAuthorizer.requireCloudAuthorized(context)
    } catch (e: ResolverAuthorizationDeniedException) {
        return CloudHomeworkListResult.Failure(e.message ?: "云端模式当前不可用")
    }

    val loginMethod = ETS100AuthManager.getLoginMethod(context)
    val isLocalLogin = loginMethod == ETS100AuthManager.LOGIN_METHOD_PASSWORD ||
        loginMethod == ETS100AuthManager.LOGIN_METHOD_CHANGYAN_WEB
    val isChangyanLogin = loginMethod == ETS100AuthManager.LOGIN_METHOD_CHANGYAN_WEB
    val savedToken: String
    var parentId: String
    var candidateAccounts: List<JSONObject> = emptyList()
    if (isLocalLogin) {
        savedToken = ETS100AuthManager.getToken(context).orEmpty()
        parentId = ETS100AuthManager.getParentAccountId(context).orEmpty()
        if (savedToken.isEmpty() || parentId.isEmpty()) {
            ReadCloudLog.error("✗ E听说账号登录信息不完整")
            return CloudHomeworkListResult.Failure("请登录E听说账号")
        }
    } else {
        try {
            val cloudSession = accountManager.cloudSession(
                ETS100AuthManager.getSelectedEcardId(context).orEmpty()
            )
            persistBoundSession(context, cloudSession)
            savedToken = cloudSession.token
            parentId = cloudSession.parentAccountId
            candidateAccounts = cloudSession.validAccounts
        } catch (e: Exception) {
            ReadCloudLog.error("✗ 后端绑定账号不可用: ${e.message}")
            return CloudHomeworkListResult.Failure(e.message ?: "无法读取 LastStudio 绑定账号")
        }
    }

    ReadCloudLog.info("☁️ 开始加载${cloudHomeworkStatusLabel(status)}列表")

    val requestStatuses = cloudHomeworkRequestStatuses(status)
    var lastError: Throwable? = null

    suspend fun requestHomeworkLists(tokenForRequest: String): List<ETS100ApiClient.HomeworkListResponse> {
        return coroutineScope {
            requestStatuses.map { requestStatus ->
                async {
                    var res = ETS100ApiClient.getHomeworkList(tokenForRequest, parentId, requestStatus)
                    val errMsg = res.exceptionOrNull()?.message.orEmpty()
                    val isClassError = errMsg.contains("80004") || errMsg.contains("有效班级")

                    // 若当前 parent_id 报 80004 (没有找到有效班级)，自动轮询候选账号 (与网页端 homework.py 保持一致)
                    if (res.isFailure && isClassError && candidateAccounts.isNotEmpty()) {
                        for (candidate in candidateAccounts) {
                            val candidatePid = candidate.optString("parent_id")
                            if (candidatePid.isBlank() || candidatePid == parentId) continue
                            val fallbackRes = ETS100ApiClient.getHomeworkList(tokenForRequest, candidatePid, requestStatus)
                            if (fallbackRes.isSuccess) {
                                val candidateLabel = candidate.optString("name").ifBlank { candidate.optString("grade") }
                                ReadCloudLog.info(
                                    "✓ 当前班级已失效，已自动回退至候选账号 ($candidateLabel, parent_id=$candidatePid) 读取成功"
                                )
                                parentId = candidatePid
                                ETS100AuthManager.saveParentAccountId(context, candidatePid)
                                ETS100AuthManager.saveSelectedEcard(
                                    context,
                                    candidate.optString("id"),
                                    candidate.optString("name"),
                                    candidate.optString("grade"),
                                    candidate.optString("class_id"),
                                )
                                res = fallbackRes
                                break
                            }
                        }
                    }
                    requestStatus to res
                }
            }.awaitAll().mapNotNull { (requestStatus, result) ->
                result.onSuccess { response ->
                    ReadCloudLog.success("✓ status=$requestStatus 获取到 ${response.homeworks.size} 个作业")
                    ReadCloudLog.info("📡 API 返回的 base_url = ${response.baseUrl}")
                    response.homeworks.forEachIndexed { index, homework ->
                        ReadCloudLog.debug(
                            "   ├─ 作业[$index] id=${homework.id.ifBlank { "<empty>" }}, " +
                                "name=${homework.name}, contents=${homework.contents.size}"
                        )
                        homework.contents.forEachIndexed { contentIndex, content ->
                            ReadCloudLog.debug(
                                "   │  ├─ content[$contentIndex] group=${content.groupName}, url=${content.url}"
                            )
                        }
                    }
                }.onFailure { e ->
                    lastError = e
                    val isClassError = e.message?.contains("80004") == true || e.message?.contains("有效班级") == true
                    val displayMsg = if (isClassError && status == CloudHomeworkState.STATUS_CURRENT) {
                        "当前账号无有效班级或新作业，可切换至上方「历史作业」查看已归档的作业与答案"
                    } else {
                        e.message
                    }
                    ReadCloudLog.error("✗ status=$requestStatus 获取作业列表失败: $displayMsg")
                }.getOrNull()
            }
        }
    }

    ReadCloudLog.info("🔐 使用已保存 Token 请求作业列表")
    var responses = requestHomeworkLists(savedToken)

    if (responses.isEmpty()) {
        if (!isLocalLogin) {
            try {
                val cloudSession = accountManager.cloudSession(
                    ETS100AuthManager.getSelectedEcardId(context).orEmpty()
                )
                persistBoundSession(context, cloudSession)
                parentId = cloudSession.parentAccountId
                candidateAccounts = cloudSession.validAccounts
                ReadCloudLog.info("✓ 已从 LastStudio 后端刷新绑定账号会话")
                responses = requestHomeworkLists(cloudSession.token)
            } catch (e: Exception) {
                lastError = e
                ReadCloudLog.error("✗ 后端绑定账号会话刷新失败: ${e.message}")
            }
            if (responses.isEmpty()) {
                val isClassError = lastError?.message?.let { it.contains("80004") || it.contains("有效班级") } == true
                val message = if (isClassError && status == CloudHomeworkState.STATUS_CURRENT) {
                    "当前账号无有效班级，可切换至「历史作业」查看往期归档答案喵~"
                } else {
                    lastError?.message ?: "后端绑定账号不可用"
                }
                return CloudHomeworkListResult.Failure(message)
            }
        } else if (isChangyanLogin) {
            val login = ETS100AuthManager.getPhone(context).orEmpty()
            ReadCloudLog.warn(
                "企业登录 Token 请求失败，不执行账号密码重登；login=$login，保留已选账号上下文，等待重新讯飞登录"
            )
            return CloudHomeworkListResult.ChangyanExpired("企业账号 Token 已失效，请重新进行讯飞登录")
        } else {
            ReadCloudLog.warn("E听说账号 Token 已失效，需要用户重新输入手机号和密码")
            return CloudHomeworkListResult.Failure("E听说账号登录状态已失效，请重新登录")
        }
    }

    val mergedHomeworks = responses.flatMap { it.homeworks }
    val latestBaseUrl = responses.lastOrNull()?.baseUrl
    ReadCloudLog.success("✓ 合并得到 ${mergedHomeworks.size} 个${cloudHomeworkStatusLabel(status)}")
    mergedHomeworks.forEachIndexed { index, homework ->
        ReadCloudLog.info(
            "📚 合并作业[$index]: id=${homework.id.ifBlank { "<empty>" }}, " +
                "name=${homework.name}, contents=${homework.contents.size}, " +
                "key=${cloudHomeworkCacheKey(status, homework)}"
        )
    }
    return CloudHomeworkListResult.Success(
        homeworks = mergedHomeworks,
        baseUrl = latestBaseUrl.orEmpty(),
    )
}

internal fun persistBoundSession(
    context: Context,
    cloudSession: FeAccountApi.CloudSession,
) {
    ETS100AuthManager.saveDeviceCode(context, cloudSession.deviceCode)
    ETS100AuthManager.saveLoginInfo(
        context,
        cloudSession.accountDisplay,
        cloudSession.token,
        cloudSession.parentAccountId,
    )
    ETS100AuthManager.saveLoginMethod(context, ETS100AuthManager.LOGIN_METHOD_BACKEND_BOUND)
    ETS100AuthManager.clearPassword(context)
    val account = cloudSession.selectedAccount
    ETS100AuthManager.saveSelectedEcard(
        context,
        account.optString("id"),
        account.optString("name"),
        account.optString("grade"),
        account.optString("class_id"),
    )
}
