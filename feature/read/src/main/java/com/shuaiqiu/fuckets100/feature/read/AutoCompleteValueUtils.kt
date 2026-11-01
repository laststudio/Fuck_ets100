package com.shuaiqiu.fuckets100.feature.read

import java.util.Locale
import kotlin.math.round
import org.json.JSONArray
import org.json.JSONObject

internal fun round2(value: Double): Double = round(value * 100) / 100.0

internal fun round4(value: Double): Double = round(value * 10000) / 10000.0

internal fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)

/** 数字安全转文本：整数值不带 `.0`，与官方 JSON 口径一致。 */
internal fun jsonText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is Number -> {
        val doubleValue = value.toDouble()
        if (doubleValue.isFinite() && doubleValue == doubleValue.toLong().toDouble()) {
            doubleValue.toLong().toString()
        } else {
            value.toString()
        }
    }
    else -> value.toString()
}

internal fun jsonDouble(value: Any?): Double? = when (value) {
    null, JSONObject.NULL -> null
    is Number -> value.toDouble().takeIf { it.isFinite() }
    is String -> value.trim().toDoubleOrNull()
    else -> null
}

internal fun jsonInt(value: Any?): Int? = when (value) {
    null, JSONObject.NULL -> null
    is Number -> value.toInt()
    is String -> value.trim().toDoubleOrNull()?.toInt()
    else -> null
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val result = mutableListOf<String>()
    for (index in 0 until length()) {
        val text = jsonText(opt(index))
        if (text.isNotBlank()) result.add(text)
    }
    return result
}

/** 题干去 HTML：与后端 `_flatten_questions` 的纯文本口径一致。 */
internal fun cleanHtml(text: String): String {
    if (text.isBlank()) return ""
    val plain = android.text.Html.fromHtml(
        text,
        android.text.Html.FROM_HTML_MODE_LEGACY,
    ).toString()
    return plain.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
}
