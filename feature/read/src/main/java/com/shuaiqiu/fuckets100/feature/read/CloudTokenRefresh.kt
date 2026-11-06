package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import android.util.Log
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100AuthManager

private const val TAG = "CloudTokenRefresh"

/** 官方 token 过期（业务码 20004）。 */
internal fun isTokenExpired(cause: Throwable?): Boolean = isTokenExpiredMessage(cause?.message)

internal fun isTokenExpiredMessage(message: String?): Boolean {
    val text = message.orEmpty()
    return text.contains("20004") ||
        text.contains("token过期") ||
        text.contains("token 过期") ||
        text.contains("Token 已失效") ||
        text.contains("Token 已经失效")
}

/**
 * 使用本地保存的手机号 + 密码重新登录 E 听说，刷新 token 并写回本地。
 *
 * 只有密码登录才能自动重登；讯飞网页登录没有保存密码，需要用户重新走网页登录。
 */
internal suspend fun renewEts100Token(context: Context): String? {
    if (ETS100AuthManager.getLoginMethod(context) != ETS100AuthManager.LOGIN_METHOD_PASSWORD) {
        Log.w(TAG, "当前登录方式不支持自动重新登录：${ETS100AuthManager.getLoginMethod(context)}")
        return null
    }
    val phone = ETS100AuthManager.getPhone(context).orEmpty()
    val password = ETS100AuthManager.getPassword(context).orEmpty()
    if (phone.isEmpty() || password.isEmpty()) {
        Log.w(TAG, "缺少已保存的账号或密码，无法自动重新登录")
        return null
    }
    return runCatching {
        ETS100ApiClient.login(
            phone = phone,
            password = password,
            deviceCode = ETS100AuthManager.getDeviceCode(context),
        ).getOrThrow().token
    }.onFailure {
        Log.w(TAG, "自动重新登录失败：${it.message}")
    }.getOrNull()?.takeIf { it.isNotBlank() }?.also {
        ETS100AuthManager.updateToken(context, it)
        Log.i(TAG, "已使用保存的账号密码重新登录并刷新 Token")
    }
}
