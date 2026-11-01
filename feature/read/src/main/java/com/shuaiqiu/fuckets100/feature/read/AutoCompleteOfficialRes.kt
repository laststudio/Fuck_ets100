package com.shuaiqiu.fuckets100.feature.read

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/**
 * 官方模板包 `res.json` 的逐题满分 / 官方题号（移植自 lastudio_backend）。
 *
 * 链路：`m/homework/struct` → `audio[0].template.url` → 下载模板包 → `res.json`
 *       → `exam_type_list[].exam_list[].question_list[]`
 * 关键语义：`question_list[].question_score` 是**该入口整组**的分值，
 * `question_count` 表示该入口代表几道小题 → 按 count 展开成逐题满分；
 * `question_order` 是节内 1-based 题号 → 展开成 `order + offset`。
 */
internal data class OfficialRes(
    val total: Double,
    val perQuestion: List<Double>,
    val orders: List<Int>,
    val examTypes: List<ExamType>,
    val entryCount: Int,
    val entryCounts: List<Int>,
) {
    data class ExamType(
        val name: String,
        val collector: String,
        val examTypeScore: Double,
        val points: List<Double>,
        val orders: List<Int>,
        val entryCounts: List<Int>,
    ) {
        val count: Int get() = points.size
        val score: Double
            get() = round(points.sum()).let { if (it > 0) it else examTypeScore }
    }
}

internal object AutoCompleteOfficialRes {

    data class Mapping(
        val available: Boolean,
        val mode: String?,
        val points: List<Double>,
        val orders: List<Int>,
        val legacyMatch: Boolean,
        val reason: String = "",
    )

    /** 解析 `res.json`；没有逐题满分/卷面分时返回 null。 */
    fun parse(json: JSONObject): OfficialRes? {
        var rawTotal = json.optDouble("set_score", 0.0).takeIf { it.isFinite() } ?: 0.0
        val examTypes = mutableListOf<OfficialRes.ExamType>()
        val typeArray = json.optJSONArray("exam_type_list") ?: JSONArray()
        for (index in 0 until typeArray.length()) {
            val examType = typeArray.optJSONObject(index) ?: continue
            val points = mutableListOf<Double>()
            val orders = mutableListOf<Int>()
            val entryCounts = mutableListOf<Int>()
            val examList = examType.optJSONArray("exam_list") ?: JSONArray()
            for (exIndex in 0 until examList.length()) {
                val exam = examList.optJSONObject(exIndex) ?: continue
                val questionList = exam.optJSONArray("question_list") ?: JSONArray()
                var size = 0
                for (qIndex in 0 until questionList.length()) {
                    val question = questionList.optJSONObject(qIndex) ?: continue
                    size += 1
                    val score = question.optDouble("question_score", 0.0).takeIf { it.isFinite() } ?: 0.0
                    val count = officialQuestionCount(question.opt("question_count"))
                    val order = question.opt("question_order")?.let { raw ->
                        runCatching { (raw as? Number)?.toInt() ?: raw.toString().toDouble().toInt() }
                            .getOrNull()
                    } ?: (orders.size + 1)
                    points.addAll(splitEntryScore(score, count))
                    for (offset in 0 until count) orders.add(order + offset)
                }
                entryCounts.add(size)
            }
            examTypes.add(
                OfficialRes.ExamType(
                    name = examType.optString("exam_type_name", ""),
                    collector = examType.optString("exam_type_collect", ""),
                    examTypeScore = examType.optDouble("exam_type_score", 0.0)
                        .takeIf { it.isFinite() } ?: 0.0,
                    points = points,
                    orders = orders,
                    entryCounts = entryCounts,
                ),
            )
        }
        val flatPoints = examTypes.flatMap { it.points }
        val flatOrders = examTypes.flatMap { it.orders }
        val flatCounts = examTypes.flatMap { it.entryCounts }
        if (flatPoints.isEmpty() && rawTotal <= 0) return null
        if (rawTotal <= 0) rawTotal = round(flatPoints.sum())
        return OfficialRes(
            total = round2(rawTotal),
            perQuestion = flatPoints,
            orders = flatOrders,
            examTypes = examTypes,
            entryCount = flatCounts.size,
            entryCounts = flatCounts,
        )
    }

    /**
     * 把官方逐题满分映射到我方题目（不要求题数相等）。
     *
     * 逐位套用 → 按节展开 → 按节摊分；都对不上则显式不可用（不硬套）。
     */
    fun mapToQuestions(res: OfficialRes?, questions: List<AutoQuestion>): Mapping {
        if (res == null || questions.isEmpty() || res.perQuestion.isEmpty()) {
            return Mapping(false, null, emptyList(), emptyList(), false, "官方 res.json 没有逐题满分")
        }
        val legacyMatch = res.entryCount == questions.size
        if (res.perQuestion.size == questions.size) {
            return Mapping(
                available = true,
                mode = "positional",
                points = res.perQuestion,
                orders = res.orders.takeIf { it.size == res.perQuestion.size } ?: emptyList(),
                legacyMatch = legacyMatch,
            )
        }

        val groups = groupQuestions(questions)
        val officialSections = res.examTypes
            .filter { it.points.isNotEmpty() || it.examTypeScore > 0 }
        if (groups.isEmpty() || officialSections.isEmpty()) {
            return Mapping(
                false, null, emptyList(), emptyList(), legacyMatch,
                "官方 res.json 题数 ${res.perQuestion.size} != 我方题数 ${questions.size}，且官方未给出可映射的大题结构",
            )
        }
        val pairs = alignSections(groups, officialSections) ?: return Mapping(
            false, null, emptyList(), emptyList(), legacyMatch,
            "官方 res.json 题数 ${res.perQuestion.size} != 我方题数 ${questions.size}，且节结构无法对应",
        )

        val points = mutableListOf<Double>()
        val orders = mutableListOf<Int>()
        var mode = "section_expanded"
        for ((groupIndex, officialIndex) in pairs) {
            val group = groups[groupIndex]
            val section = officialSections[officialIndex]
            val ourCount = group.questions.size
            val sectionPoints: List<Double>
            val sectionOrders: List<Int>
            if (section.points.size == ourCount) {
                sectionPoints = section.points
                sectionOrders = section.orders
            } else if (section.score > 0) {
                sectionPoints = distributeSectionScore(section.score, group.questions)
                sectionOrders = emptyList()
                mode = "section_scaled"
            } else {
                return Mapping(
                    false, null, emptyList(), emptyList(), legacyMatch,
                    "官方第 ${officialIndex + 1} 节（${section.name.ifBlank { section.collector }}）没有可用分值，无法映射",
                )
            }
            if (sectionPoints.size != ourCount) {
                return Mapping(
                    false, null, emptyList(), emptyList(), legacyMatch,
                    "官方 res.json 按节映射后题数仍不一致，无法套用",
                )
            }
            points.addAll(sectionPoints)
            orders.addAll(sectionOrders)
        }
        if (points.size != questions.size) {
            return Mapping(
                false, null, emptyList(), emptyList(), legacyMatch,
                "官方 res.json 按节映射后题数仍不一致（${points.size} != ${questions.size}），无法套用",
            )
        }
        return Mapping(
            available = true,
            mode = mode,
            points = points,
            orders = orders.takeIf { it.size == points.size } ?: emptyList(),
            legacyMatch = legacyMatch,
        )
    }

    private data class Group(
        val caption: String,
        val category: String,
        val questions: List<AutoQuestion>,
    )

    private fun groupQuestions(questions: List<AutoQuestion>): List<Group> =
        questions.groupBy { it.sectionOrder }.entries
            .sortedBy { it.key }
            .map { (_, list) ->
                Group(
                    caption = list.first().sectionCaption,
                    category = list.first().category,
                    questions = list,
                )
            }

    private fun collectorKey(value: String): String =
        value.trim().lowercase().removePrefix("collector.")

    private fun sectionMatches(group: Group, section: OfficialRes.ExamType): Boolean {
        val ourName = group.caption.trim()
        val offName = section.name.trim()
        if (ourName.isNotEmpty() && offName.isNotEmpty() && ourName == offName) return true
        val ourKey = collectorKey(group.category)
        val offKey = collectorKey(section.collector)
        return ourKey.isNotEmpty() && offKey.isNotEmpty() && ourKey == offKey
    }

    /** 我方节 ↔ 官方大题（保序）：节数相等且逐对相容则按位，否则按名称保序贪心。 */
    private fun alignSections(
        groups: List<Group>,
        sections: List<OfficialRes.ExamType>,
    ): List<Pair<Int, Int>>? {
        if (groups.size == sections.size &&
            groups.indices.all { sectionMatches(groups[it], sections[it]) }
        ) {
            return groups.indices.map { it to it }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        for (groupIndex in groups.indices) {
            var found = -1
            for (officialIndex in cursor until sections.size) {
                if (sectionMatches(groups[groupIndex], sections[officialIndex])) {
                    found = officialIndex
                    break
                }
            }
            if (found < 0) return null
            pairs.add(groupIndex to found)
            cursor = found + 1
        }
        return pairs
    }

    /** 官方某节总分按我方逐题满分权重摊到每道题（Σ 严格等于该节总分）。 */
    private fun distributeSectionScore(score: Double, questions: List<AutoQuestion>): List<Double> {
        if (questions.isEmpty()) return emptyList()
        val weights = questions.map { q -> q.point.takeIf { it > 0 } ?: 1.0 }
        val totalWeight = weights.sum().takeIf { it > 0 } ?: weights.size.toDouble()
        val points = weights.map { round2(it / totalWeight * score) }.toMutableList()
        val diff = round2(score - points.sum())
        if (diff != 0.0 && points.isNotEmpty()) points[points.lastIndex] = round2(points.last() + diff)
        return points
    }

    private fun officialQuestionCount(value: Any?): Int {
        val count = when (value) {
            is Number -> value.toInt()
            is String -> value.toDoubleOrNull()?.toInt()
            else -> null
        }
        return if (count != null && count > 0) count else 1
    }

    /** 一个入口的分值摊到它代表的 `count` 道小题（两位小数，末位吸收舍入差）。 */
    private fun splitEntryScore(score: Double, count: Int): List<Double> {
        if (count <= 1) return listOf(round2(score))
        val each = round2(score / count)
        val shares = MutableList(count) { each }
        val diff = round2(score - shares.sum())
        if (diff != 0.0) shares[count - 1] = round2(shares[count - 1] + diff)
        return shares
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
