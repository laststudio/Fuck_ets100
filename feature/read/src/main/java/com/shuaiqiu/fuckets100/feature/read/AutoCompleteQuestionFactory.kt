package com.shuaiqiu.fuckets100.feature.read

import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100CloudTemplate
import java.util.Locale
import org.json.JSONObject

/**
 * 题目展开：官方合并 paper → 解析器原始包 → 已解析 sections。
 * 字段语义对齐 `homework.py` 的 `_sections_from_official_paper` /
 * `resolve_content_with_resolver` 与 `runner.py` 的 `_flatten_questions`。
 */
internal fun buildAutoQuestions(success: CloudPaperResult.Success): List<AutoQuestion> {
    success.officialPaper?.let { paper ->
        val built = fromOfficialPaper(paper)
        if (built.isNotEmpty()) return built
    }
    if (success.nativePackages.isNotEmpty()) {
        val built = fromNativePackages(success.nativePackages)
        if (built.isNotEmpty()) return built
    }
    return fromPaperSections(success.paper.sections)
}

private fun fromOfficialPaper(paper: JSONObject): List<AutoQuestion> {
    val result = mutableListOf<AutoQuestion>()
    val sections = paper.optJSONArray("sections") ?: return result
    var order = 0
    for (sIdx in 0 until sections.length()) {
        val section = sections.optJSONObject(sIdx) ?: continue
        val structureType = section.optString("structure_type", "")
        val caption = section.optString("caption", "").ifBlank { "大题 ${sIdx + 1}" }
        val sectionEntity = section.optJSONArray("real_ids")
            ?.let { jsonText(it.opt(0)) }
            .orEmpty()
        val questions = section.optJSONArray("questions") ?: continue
        for (qIdx in 0 until questions.length()) {
            val q = questions.optJSONObject(qIdx) ?: continue
            order += 1
            val type = jsonText(q.opt("type")).ifBlank { structureType }
            // 后端 `format_parsed_paper`：typeName 由 structure_type 查表，不用 raw type
            val typeName = (STRUCTURE_TYPE_META[type] ?: STRUCTURE_TYPE_META[structureType])
                ?.second
                ?.takeIf { it.isNotBlank() }
                ?: caption
            val answers = q.optJSONArray("answers").toStringList()
            var text = jsonText(q.opt("question"))
            if (text.isBlank() && answers.isNotEmpty()) text = answers.joinToString(" ")
            result.add(
                makeQuestion(
                    order = order,
                    sectionOrder = sIdx,
                    sectionCaption = caption,
                    typeName = typeName,
                    category = type,
                    text = text,
                    answers = answers,
                    options = q.optJSONArray("options").toStringList(),
                    chooseRaw = jsonText(q.opt("correct_option")),
                    chooseId = jsonText(q.opt("choose_id")),
                    entityId = jsonText(q.opt("stid")).ifBlank { sectionEntity },
                    subOrder = jsonInt(q.opt("no")) ?: qIdx,
                    point = jsonDouble(q.opt("score")) ?: 0.0,
                )
            )
        }
    }
    return result
}

private fun fromNativePackages(
    packages: List<ETS100CloudTemplate.PackageQuestions>,
): List<AutoQuestion> {
    val result = mutableListOf<AutoQuestion>()
    var order = 0
    packages.forEachIndexed { packageIndex, pkg ->
        val meta = STRUCTURE_TYPE_META[pkg.structureType]
        val category = meta?.first.orEmpty()
        val typeName = meta?.second?.takeIf { it.isNotBlank() }
            ?: pkg.groupName.takeIf { it.isNotBlank() }
            ?: pkg.structureType.ifBlank { "题目" }
        val entityId = pkg.stid.ifBlank { pkg.realId }
        val questions = pkg.questions
        for (qIdx in 0 until questions.length()) {
            val q = questions.optJSONObject(qIdx) ?: continue
            order += 1
            val answers = q.optJSONArray("answers").toStringList()
            val options = q.optJSONArray("options").toStringList()
            var text = jsonText(q.opt("question"))
            if (text.isBlank()) {
                text = if (pkg.structureType == "collector.fill") {
                    "第 ${qIdx + 1} 题"
                } else {
                    "题目 ${qIdx + 1}"
                }
            }
            result.add(
                makeQuestion(
                    order = order,
                    sectionOrder = packageIndex,
                    sectionCaption = pkg.groupName.ifBlank { typeName },
                    typeName = typeName,
                    category = if (options.isNotEmpty() && category.isBlank()) "choose" else {
                        category.ifBlank { "choose" }
                    },
                    text = text,
                    answers = answers,
                    options = options,
                    chooseRaw = jsonText(q.opt("correct_option")),
                    chooseId = jsonText(q.opt("choose_id")),
                    entityId = entityId,
                    subOrder = qIdx,
                    point = jsonDouble(q.opt("score")) ?: 0.0,
                )
            )
        }
    }
    return result
}

private fun fromPaperSections(
    sections: List<ETS100AnswerReader.Section>,
): List<AutoQuestion> {
    val result = mutableListOf<AutoQuestion>()
    var order = 0
    sections.forEachIndexed { sIdx, section ->
        section.questions.forEachIndexed { qIdx, q ->
            order += 1
            result.add(
                makeQuestion(
                    order = order,
                    sectionOrder = sIdx,
                    sectionCaption = section.caption,
                    typeName = q.typeName.ifBlank { section.typeName },
                    category = q.category.ifBlank { section.category },
                    text = q.questionText,
                    answers = q.answers,
                    options = emptyList(),
                    chooseRaw = "",
                    chooseId = "",
                    entityId = "${sIdx}_${qIdx}",
                    subOrder = qIdx,
                    point = q.score ?: 0.0,
                )
            )
        }
    }
    return result
}

/** `_flatten_questions`：补齐 tts_text / choose / 题型判定。 */
private fun makeQuestion(
    order: Int,
    sectionOrder: Int,
    sectionCaption: String,
    typeName: String,
    category: String,
    text: String,
    answers: List<String>,
    options: List<String>,
    chooseRaw: String,
    chooseId: String,
    entityId: String,
    subOrder: Int,
    point: Double,
): AutoQuestion {
    var plainText = cleanHtml(text)
    if (plainText.isBlank() && answers.isNotEmpty()) plainText = answers.joinToString(" ")
    var cat = category
    if ("choose" in cat.lowercase()) cat = "choose"
    val ttsText = if (cat == "choose") {
        ""
    } else {
        answers.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: plainText.trim()
    }
    var choose = chooseRaw.trim()
    if (choose.isEmpty() && options.isNotEmpty() && answers.isNotEmpty()) {
        for (option in options) {
            if (option in answers || answers.any { it in option }) {
                choose = option.take(1)
                break
            }
        }
    }
    if (choose.isEmpty() && answers.isNotEmpty()) {
        val first = answers[0].trim()
        val upper = first.uppercase(Locale.ROOT)
        if (first.length == 1 && upper in "ABCDEFG") {
            choose = upper
        } else if (first.take(2) in listOf("A.", "B.", "C.", "D.", "E.", "F.", "A、", "B、", "C、", "D、")) {
            choose = first.take(1).uppercase(Locale.ROOT)
        }
    }
    val (isOral, isChoose) = AutoCompleteScoring.classifyQuestionType(cat, typeName, options)
    return AutoQuestion(
        order = order,
        sectionOrder = sectionOrder,
        sectionCaption = sectionCaption,
        typeName = typeName,
        questionText = plainText,
        category = cat,
        point = point.takeIf { it.isFinite() && it > 0 } ?: 0.0,
        options = options,
        entityId = entityId,
        subOrder = subOrder,
        ttsText = ttsText,
        answers = answers,
        choose = choose,
        chooseId = chooseId,
        isOral = isOral,
        isChoose = isChoose,
    )
}
