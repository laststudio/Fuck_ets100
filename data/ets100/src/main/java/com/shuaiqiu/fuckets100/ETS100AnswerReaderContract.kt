package com.shuaiqiu.fuckets100

import android.util.Log

/**
 * 解析契约调用的离线容错与错误详情构造。
 *
 * 从 ETS100AnswerReader 拆出，避免单个文件超过架构检查的行数预算（800 行）。
 */
internal fun parseTaskWithAuthRetry(
    content: String,
    info: String,
    content2: String,
): ResolverContract.Response {
    var contract = ResolverContract.parseTask(content = content, info = info, content2 = content2)
    if (!contract.ok && (contract.errorCode == "auth_disabled" || contract.errorCode == "auth_not_configured")) {
        // 离线版容错：解析器可能尚未完成本地授权初始化。
        // 补丁版 so 的 authorizeFeature 不会真正联网；失败也不影响后续错误提示。
        val authRaw = runCatching { ResolverNative.authorizeFeature() }.getOrNull()
        Log.i("ETS100AnswerReader", "解析器授权重试: " + (authRaw?.take(240) ?: "authorizeFeature 调用异常"))
        contract = ResolverContract.parseTask(content = content, info = info, content2 = content2)
    }
    return contract
}

/** 构造解析失败详情：包含 native 错误码 / hint 与解析器本地授权状态。 */
internal fun buildParseTaskError(contract: ResolverContract.Response): String {
    val baseMessage = contract.errorMessage?.takeIf { it.isNotBlank() } ?: "答案解析功能当前不可用"
    return buildString {
        append(baseMessage)
        if (!contract.errorCode.isNullOrBlank()) {
            append("（code=").append(contract.errorCode).append("）")
        }
        if (!contract.errorHint.isNullOrBlank()) {
            append("；提示：").append(contract.errorHint)
        }
        if (contract.errorCode == "auth_disabled" || contract.errorCode == "auth_not_configured") {
            append("；解析器本地授权状态：").append(ResolverAuthorizer.localAuthStatus())
            append("（离线版应自动初始化，无需登录 LastStudio 账号）")
        }
    }
}
