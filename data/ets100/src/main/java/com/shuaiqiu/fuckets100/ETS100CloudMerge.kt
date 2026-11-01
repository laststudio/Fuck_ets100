package com.shuaiqiu.fuckets100

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端整卷合并（官方模板口径）。
 *
 * 链路：`/m/homework/struct` → 模板包 → `res.json` → 解析器 `op: parse_homework_set`。
 * 成功后拿到**官方大题顺序、卷面题号（1..N 连续）、逐题满分**（`question_score`）。
 *
 * 放在 data 层的原因：这里同时需要 ETS API（[ETS100ApiClient]）、凭据（[ETS100AuthManager]）、
 * 解析器契约（[ResolverContract]）与 UI 模型映射（[ETS100AnswerReader]）。
 * 解压由调用方注入（feature 层持有 zip4j，data 层不引该依赖）。
 *
 * **失败即返回 null，调用方保持原有逐包结果 —— 绝不降级数据。**
 */
object ETS100CloudMerge {

    private const val TAG = "ETS100CloudMerge"

    data class MergedPaper(
        val sections: List<ETS100AnswerReader.Section>,
        val totalScore: Double?,
        val logs: List<String>,
        /** 完整性对账：缺题时界面要能提示（卷面分照旧是官方值，看不出缺题） */
        val integrity: ETS100AnswerReader.PaperIntegrity? = null,
        /** 官方模板合并后的原始 paper（sections[].questions[] 含 entity_id/sub_order/point） */
        val rawPaper: JSONObject? = null,
        /** 官方模板包的 res.json（逐题满分/官方题号的权威来源） */
        val res: JSONObject? = null,
    )

    /**
     * @param contents 作业列表里的资源包（groupName 与 realId；realId 用于对官方挂载键 code_id）
     * @param packages 每包的解析器原始题目
     * @param unzip    解压模板包（未加密）的实现，由调用方注入
     */
    suspend fun merge(
        context: Context,
        homeworkId: String,
        homeworkName: String,
        resourceId: String = "",
        setType: Int = 0,
        pk: Int = 0,
        contents: List<ETS100ApiClient.HomeworkContent>,
        packages: List<ETS100CloudTemplate.PackageQuestions>,
        cacheDir: File? = null,
        unzip: (zipFile: File, destDir: File) -> Unit,
    ): MergedPaper? {
        if (packages.size < 2) return null  // 单包作业无需合并
        val logs = mutableListOf<String>()
        return try {
            val token = ETS100AuthManager.getToken(context).orEmpty()
            val parentId = ETS100AuthManager.getParentAccountId(context).orEmpty()
            if (token.isBlank()) {
                logs.add("⏭️ 无 ETS token，跳过官方模板合并")
                return null
            }

            val structBody = ETS100ApiClient
                .getHomeworkStructM(token, homeworkId, resourceId, parentId)
                .getOrNull()
            if (structBody == null) {
                logs.add("⏭️ /m/homework/struct 无响应，跳过官方模板合并")
                return null
            }
            val struct = ETS100CloudTemplate.parseStruct(structBody)
            if (struct == null) {
                logs.add("⏭️ /m/homework/struct 无 audio（该作业没有试卷集模板）")
                return null
            }
            logs.add("🧩 试卷集: ${struct.setName.ifBlank { homeworkName }}，官方大题 ${struct.contents.size} 个")

            // 把官方挂载键 code_id 补到每个包（解析器也会按 real_id/名称兜底）
            val coded = packages.mapIndexed { index, pkg ->
                val codeId = pkg.realId.takeIf { it.isNotBlank() }
                    ?.let { struct.codeIdForRealId(it) }
                    .orEmpty()
                    .ifBlank {
                        struct.contents.firstOrNull {
                            it.groupName == pkg.groupName && it.codeId.isNotBlank()
                        }?.codeId.orEmpty()
                    }
                if (codeId.isNotBlank()) logs.add("   ├─ 挂载键 ${pkg.groupName} -> $codeId")
                pkg.copy(codeId = codeId, order = if (pkg.order >= 0) pkg.order else index)
            }

            val resJson = ETS100CloudTemplate.fetchResJson(struct.templateUrl, cacheDir, unzip)
            if (resJson == null) {
                logs.add("⏭️ 未取到官方 res.json：按无模板合并（题号仍连续，逐题满分留空）")
            } else {
                logs.add(
                    "📐 官方模板: set_score=${resJson.optString("set_score")} " +
                        "大题=${resJson.optJSONArray("exam_type_list")?.length() ?: 0}",
                )
            }

            val homeworkJson = JSONObject()
                .put("id", homeworkId)
                .put("name", homeworkName)
                .put("set_type", setType)
                .put("resource_id", resourceId)
                .put("pk", pk)
                .put(
                    "contents",
                    JSONArray().apply {
                        contents.forEach { put(JSONObject().put("group_name", it.groupName)) }
                    },
                )

            val payload = JSONObject()
                .put("packages", ETS100CloudTemplate.buildPackages(coded))
                .put("homework", homeworkJson)
            if (resJson != null) payload.put("res", resJson)

            val resp = ResolverContract.call("parse_homework_set", payload)
            if (!resp.ok) {
                logs.add("⚠️ parse_homework_set 失败（${resp.errorCode} ${resp.errorMessage}），保持逐包结果")
                return null
            }
            val paper = resp.result?.optJSONObject("paper") ?: return null
            val sectionsJson = paper.optJSONArray("sections") ?: return null

            var running = 0
            val sections = mutableListOf<ETS100AnswerReader.Section>()
            for (i in 0 until sectionsJson.length()) {
                val s = sectionsJson.optJSONObject(i) ?: continue
                val native = s.optJSONArray("questions") ?: continue
                if (native.length() == 0) continue
                val caption = s.optString("caption", "大题 ${i + 1}")
                val structureType = s.optString("structure_type", "")
                val questions = ETS100AnswerReader.mapNativeQuestions(
                    native = native,
                    json = JSONObject().put("structure_type", structureType),
                    startIndex = running,
                    category = structureType,
                    typeName = caption,
                    sectionCaption = caption,
                    originalContent = null,
                    scoreOf = { idx -> native.optJSONObject(idx)?.optDouble("score")?.takeIf { v -> !v.isNaN() } },
                )
                running += questions.size
                sections.add(
                    ETS100AnswerReader.Section(
                        caption = caption,
                        category = structureType,
                        typeName = caption,
                        questions = questions,
                        originalContent = null,
                    ),
                )
                val scores = questions.mapNotNull { it.score }
                logs.add(
                    "   ├─ $caption: ${questions.size} 题" +
                        if (scores.isNotEmpty()) "，逐题满分 ${scores.joinToString("/")}" else "",
                )
            }
            if (sections.isEmpty()) return null

            val totalScore = paper.optDouble("totalScore", Double.NaN).takeIf { v -> !v.isNaN() }
            logs.add("✅ 官方模板合并完成: ${sections.size} 大题 / $running 题" + (totalScore?.let { " / 卷面分 $it" } ?: ""))

            // 完整性对账：解析器逐节与官方 res.json 比过题数了
            val integrity = ETS100AnswerReader.PaperIntegrity.fromResolverResult(
                completeness = resp.result?.optJSONObject("completeness"),
                coverage = resp.result?.optJSONObject("coverage"),
                warnings = resp.result?.optJSONArray("warnings")
                    ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } }
                    ?: emptyList(),
            )
            if (integrity != null && !integrity.isComplete) {
                logs.add("⚠️ 本卷不完整：${integrity.notice}")
                integrity.warnings.forEach { logs.add("   ├─ $it") }
            } else if (integrity != null) {
                logs.add("✅ 完整性对账通过（官方 ${integrity.templateQuestions} 题 / 实际 ${integrity.parsedQuestions} 题）")
            }
            MergedPaper(sections, totalScore, logs, integrity, rawPaper = paper, res = resJson)
        } catch (e: Exception) {
            Log.w(TAG, "官方模板合并异常（保持逐包结果）", e)
            null
        }
    }
}
