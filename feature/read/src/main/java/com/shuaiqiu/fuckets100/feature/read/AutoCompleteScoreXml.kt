package com.shuaiqiu.fuckets100.feature.read

import java.util.Random
import kotlin.math.max
import kotlin.math.min

/** 口语题的四个可见维度（0–5 分制），与后端 `ScoreProfile` 一致。 */
data class ScoreProfile(
    val total: Double,
    val accuracy: Double,
    val fluency: Double,
    val integrity: Double,
    val standard: Double,
) {
    fun clamped(): ScoreProfile {
        fun clamp(value: Double) = max(0.0, min(5.0, value))
        return ScoreProfile(
            total = clamp(total),
            accuracy = clamp(accuracy),
            fluency = clamp(fluency),
            integrity = clamp(integrity),
            standard = clamp(standard),
        )
    }
}

/** `derived_dimension_scores`：未给维度时的机械派生（后端逐字口径）。 */
fun profileForIse(ise: Double): ScoreProfile {
    val total = ise.coerceIn(0.0, 5.0)
    return ScoreProfile(
        total = total,
        accuracy = min(5.0, total * 0.99),
        fluency = min(5.0, total * 0.98),
        integrity = min(5.0, total * 0.99),
        standard = min(5.0, total * 0.97),
    )
}

/**
 * 官方评分 XML（后端 `generate_score_xml` 的 Kotlin 版）。
 *
 * 结构：`xml_result > {read_sentence|read_chapter} > rec_paper > sentence > word > syll`，
 * 分数全部为 0–5 分制，`is_rejected=false`。
 */
fun generateScoreXml(
    text: String,
    profile: ScoreProfile,
    category: String,
    seed: Long,
    clamp: Boolean = true,
): String {
    val effective = if (clamp) profile.clamped() else profile
    val randomizer = Random(seed)
    fun uniform(min: Double, max: Double) = min + (max - min) * randomizer.nextDouble()
    fun bounded(value: Double): Double =
        if (clamp) max(0.0, min(5.0, value)) else max(0.0, value)

    val categoryName = category.takeIf {
        it in setOf("read_sentence", "read_chapter", "talk_recite", "talk_free")
    } ?: "read_chapter"

    val builder = StringBuilder()
    builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    builder.append("<xml_result>")
    builder.append("<").append(categoryName).append(">")

    val standard = bounded(effective.total + uniform(-0.1, 0.1))
    builder.append("<rec_paper")
        .append(" total_score=\"").append(format2(effective.total)).append("\"")
        .append(" accuracy_score=\"").append(format2(effective.accuracy)).append("\"")
        .append(" fluency_score=\"").append(format2(effective.fluency)).append("\"")
        .append(" integrity_score=\"").append(format2(effective.integrity)).append("\"")
        .append(" standard_score=\"").append(format2(standard)).append("\"")
        .append(" stress_score=\"").append(format2(effective.standard)).append("\"")
        .append(" is_rejected=\"false\"")
        .append(" except_info=\"\"")
        .append(" content=\"").append(escapeXml(text)).append("\">")

    val sentences = text.split(Regex("[.!?]+")).map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf(text) }
    for (sentenceText in sentences) {
        val sentenceScore = bounded(effective.total + uniform(-0.15, 0.15))
        builder.append("<sentence")
            .append(" content=\"").append(escapeXml(sentenceText)).append("\"")
            .append(" total_score=\"").append(format2(sentenceScore)).append("\">")
        for (wordText in sentenceText.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            val wordScore = bounded(sentenceScore + uniform(-0.3, 0.3))
            builder.append("<word")
                .append(" content=\"").append(escapeXml(wordText)).append("\"")
                .append(" total_score=\"").append(format2(wordScore)).append("\"")
                .append(" dp_message=\"0\">")
            for (symbol in phonemes(wordText)) {
                builder.append("<syll")
                    .append(" content=\"").append(escapeXml(symbol)).append("\"")
                    .append(" score=\"").append(format2(bounded(wordScore + uniform(-0.2, 0.2))))
                    .append("\"/>")
            }
            builder.append("</word>")
        }
        builder.append("</sentence>")
    }
    builder.append("</rec_paper></").append(categoryName).append("></xml_result>")
    return builder.toString()
}

/** 粗粒度音素切分（与后端 `_phonemes` 一致）。 */
private fun phonemes(word: String): List<String> {
    val cleaned = word.replace(Regex("[^a-zA-Z]"), "").lowercase()
    if (cleaned.isEmpty()) return listOf("*")
    val pairs = mapOf(
        "th" to "θ", "sh" to "ʃ", "ch" to "tʃ", "wh" to "w", "ph" to "f", "ck" to "k",
        "ng" to "ŋ", "oo" to "uː", "ee" to "iː", "ea" to "iː", "ou" to "aʊ",
        "ow" to "aʊ", "ai" to "eɪ", "ay" to "eɪ", "oi" to "ɔɪ", "oy" to "ɔɪ",
    )
    val result = mutableListOf<String>()
    var index = 0
    while (index < cleaned.length) {
        val pair = cleaned.substring(index, min(cleaned.length, index + 2))
        val mapped = pairs[pair]
        if (pair.length == 2 && mapped != null) {
            result.add(mapped)
            index += 2
        } else {
            result.add(cleaned[index].toString())
            index += 1
        }
    }
    return result
}

/** XML 属性转义（对齐 ElementTree 对 `\n`/`\r`/`\t` 的实体化）。 */
private fun escapeXml(value: String): String = buildString {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            '\t' -> append("&#9;")
            else -> append(ch)
        }
    }
}
