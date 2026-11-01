package com.shuaiqiu.fuckets100

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 作业分类（解析器 `op: classify_homework` 的客户端薄封装）。
 *
 * 之前 `pk == 1 || set_type == 6` 这类判断散在列表、下载、占位符三处且口径不一，
 * 现在统一收口到解析器：是否词汇 PK、是否是老师布置的多卷习题集、卷面满分口径，
 * 都由 [ResolverContract.classifyHomework] 给出。
 *
 * 解析器不可用时返回 null，调用方回退到旧判断（不影响主流程）。
 */
object ETS100HomeworkClassifier {

    /** 分类结论（只保留端侧用得到的字段）。 */
    data class Verdict(
        val category: String,
        val isVocabPk: Boolean,
        val isOnline: Boolean,
        val isExerciseSet: Boolean,
        val isMockSuite: Boolean,
        val expectedTotalScore: Double?,
    ) {
        /** 走词汇/在线接口取题（不是听说卷）。 */
        val needsOnlineFetch: Boolean get() = isVocabPk || isOnline
    }

    private val cache = ConcurrentHashMap<String, Verdict>()
    private const val CACHE_LIMIT = 256

    fun classify(homework: ETS100ApiClient.HomeworkInfo): Verdict? {
        val key = cacheKey(homework)
        cache[key]?.let { return it }

        val response = ResolverContract.classifyHomework(payloadOf(homework))
        if (!response.ok) return null
        val result = response.result ?: return null
        val verdict = verdictOf(result)
        remember(key, verdict)
        return verdict
    }

    /**
     * 批量分类（`op: classify_homework_list`）：一次调用给整页作业打标，
     * 结果写进本地缓存，后续 [classify] 直接命中，不再逐条进 JNI。
     */
    fun classifyList(homeworks: List<ETS100ApiClient.HomeworkInfo>) {
        if (homeworks.isEmpty()) return
        val response = ResolverContract.classifyHomeworkList(
            JSONArray().apply { homeworks.forEach { put(payloadOf(it)) } },
        )
        if (!response.ok) return
        val items = response.result?.optJSONArray("homeworks") ?: return
        for (index in 0 until minOf(items.length(), homeworks.size)) {
            val classification = items.optJSONObject(index)?.optJSONObject("classification")
                ?: continue
            remember(cacheKey(homeworks[index]), verdictOf(classification))
        }
    }

    private fun payloadOf(homework: ETS100ApiClient.HomeworkInfo): JSONObject = JSONObject()
        .put("id", homework.id)
        .put("name", homework.name)
        .put("set_type", homework.setType)
        .put("pk", homework.pk)
        .put("resource_id", homework.resourceId)
        .put("total_point", homework.totalScore)
        .put(
            "contents",
            JSONArray().apply {
                homework.contents.forEach { content ->
                    put(
                        JSONObject()
                            .put("group_name", content.groupName)
                            .put("real_id", content.realId)
                            .put("url", content.url),
                    )
                }
            },
        )

    private fun verdictOf(result: JSONObject): Verdict = Verdict(
        category = result.optString("category"),
        isVocabPk = result.optBoolean("is_vocab_pk", false),
        isOnline = result.optBoolean("is_online", false),
        isExerciseSet = result.optBoolean("is_exercise_set", false),
        isMockSuite = result.optBoolean("is_mock_suite", false),
        expectedTotalScore = result.optDouble("expected_total_score", Double.NaN)
            .takeIf { !it.isNaN() },
    )

    private fun remember(key: String, verdict: Verdict) {
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[key] = verdict
    }

    private fun cacheKey(homework: ETS100ApiClient.HomeworkInfo): String {
        if (homework.id.isNotBlank()) return homework.id
        return homework.name + "|" + homework.contents.joinToString("|") {
            "${it.groupName}:${it.realId}:${it.url}"
        }
    }
}
