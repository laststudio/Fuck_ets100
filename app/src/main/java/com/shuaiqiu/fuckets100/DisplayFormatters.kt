package com.shuaiqiu.fuckets100

fun maskPhoneNumber(phone: String?): String {
    val normalized = phone?.trim().orEmpty()
    if (normalized.isEmpty()) return "未知账号"

    return when {
        normalized.length >= 11 -> "${normalized.take(3)}****${normalized.takeLast(4)}"
        normalized.length >= 7 -> "${normalized.take(3)}****${normalized.takeLast(2)}"
        normalized.length >= 4 -> "${normalized.take(1)}****${normalized.takeLast(1)}"
        else -> "****"
    }
}
