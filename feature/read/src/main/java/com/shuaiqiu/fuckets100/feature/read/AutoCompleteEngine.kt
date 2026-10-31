package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100AuthManager
import com.shuaiqiu.fuckets100.ResolverAuthorizationDeniedException
import com.shuaiqiu.fuckets100.ResolverAuthorizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动完成执行引擎（真实提交）。
 *
 * 流程与网页版保持一致：会话 → 作业结构 → 分值分配 → 同步用时/上报成绩/提交 → 回读成绩。
 */
internal class AutoCompleteEngine(
    private val context: Context,
) {

    private data class Session(val token: String, val parentId: String)

    suspend fun run(
        homework: ETS100ApiClient.HomeworkInfo,
        targetRatio: Double,
        onStage: (AutoStageUpdate) -> Unit,
    ): AutoRunResult {
        val session = resolveSession()
        currentCoroutineContext().ensureActive()

        onStage(stage(0, 5))
        currentCoroutineContext().ensureActive()

        val struct = ETS100ApiClient.getHomeworkStruct(
            token = session.token,
            homeworkId = homework.id,
            resourceId = homework.resourceId,
            parentAccountId = session.parentId,
        ).getOrElse { throw AutoCompleteException("拉取作业结构失败：${it.message ?: "未知错误"}") }
        val questions = buildQuestions(struct)
        if (questions.isEmpty()) {
            throw AutoCompleteException("未能从作业结构中解析出题目，可能需先在 ETS App 打开该作业")
        }

        var paperTotal = homework.totalScore.takeIf { it > 0 } ?: questions.sumOf { it.point }
        if (paperTotal <= 0) {
            throw AutoCompleteException("该作业缺少卷面总分，无法生成目标分")
        }
        paperTotal = round2(paperTotal)

        onStage(stage(1, 15))
        currentCoroutineContext().ensureActive()

        val ratio = max(0.0, min(targetRatio, 0.95))
        val targetTotal = round2(paperTotal * ratio)
        val objectiveFlags = questions.map {
            AutoCompleteScoring.isObjective(it.category, it.typeName, it.options)
        }
        val plan = AutoCompleteScoring.planAllocations(
            fullPoints = questions.map { it.point },
            targetTotal = targetTotal,
            seed = System.nanoTime(),
            objectiveFlags = objectiveFlags,
        )
        val scores = plan.map { it.allocatedScore }
        val finalScore = round2(scores.sum())
        val useTime = estimateUseTime(questions)

        onStage(
            stage(
                2,
                25,
                totalQuestions = questions.size,
            )
        )
        currentCoroutineContext().ensureActive()

        val questionResults = mutableListOf<AutoQuestionResult>()
        questions.forEachIndexed { index, question ->
            currentCoroutineContext().ensureActive()
            val progress = max(40, min(60, 40 + 20 * (index + 1) / questions.size))
            onStage(
                AutoStageUpdate(
                    stageIndex = 4,
                    progress = progress,
                    label = "[4/8] 正在处理第 ${index + 1}/${questions.size} 题: " +
                        question.questionText.take(24).ifBlank { question.typeName },
                    currentQuestion = question.questionText,
                    currentQuestionIndex = index + 1,
                    totalQuestions = questions.size,
                )
            )
            questionResults.add(
                AutoQuestionResult(
                    order = question.order,
                    typeName = question.typeName,
                    text = question.questionText,
                    awardedScore = scores[index],
                    maxScore = question.point,
                )
            )
        }

        onStage(stage(4, 70))
        currentCoroutineContext().ensureActive()
        onStage(
            AutoStageUpdate(
                stageIndex = 6,
                progress = 75,
                label = "[6/8] 正在上报逐题成绩（${questions.size} 题）...",
                totalQuestions = questions.size,
            )
        )
        currentCoroutineContext().ensureActive()

        val official = submit(
            session = session,
            homework = homework,
            questions = questions,
            scores = scores,
            paperTotal = paperTotal,
            finalScore = finalScore,
            useTime = useTime,
            onStage = onStage,
        )
        currentCoroutineContext().ensureActive()

        val completedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        onStage(stage(7, 100, label = "作业自动完成已顺利完成并提交成功！"))

        return AutoRunResult(
            homeworkId = homework.id,
            homeworkName = homework.name,
            score = finalScore,
            maxScore = paperTotal,
            scoreRate = if (paperTotal > 0) round4(finalScore / paperTotal) else 0.0,
            targetTotal = targetTotal,
            useTime = official.officialUseTime ?: useTime,
            officialPoint = official.officialPoint,
            officialUseTime = official.officialUseTime,
            officialSubmitTime = official.officialSubmitTime,
            completedAt = completedAt,
            questionResults = questionResults,
        )
    }

    private data class OfficialInfo(
        val officialPoint: Double?,
        val officialUseTime: Int?,
        val officialSubmitTime: String?,
    )

    private suspend fun submit(
        session: Session,
        homework: ETS100ApiClient.HomeworkInfo,
        questions: List<AutoQuestion>,
        scores: List<Double>,
        paperTotal: Double,
        finalScore: Double,
        useTime: Int,
        onStage: (AutoStageUpdate) -> Unit,
    ): OfficialInfo {
        val resourceId = homework.resourceId.ifBlank { "58" }
        val homeworkIdValue: Any = homework.id.toIntOrNull() ?: homework.id

        onStage(
            AutoStageUpdate(
                stageIndex = 7,
                progress = 88,
                label = "[7/8] 同步用时 (${useTime}秒) 并提交作业终审...",
                totalQuestions = questions.size,
            )
        )
        ETS100ApiClient.setUseTime(
            token = session.token,
            params = mapOf(
                "homework_id" to homeworkIdValue,
                "resource_id" to resourceId,
                "use_time" to useTime,
                "reset" to 0,
                "end" to 1,
                "correct" to 0,
                "version" to 2,
            ),
        ).getOrElse { throw AutoCompleteException("同步用时失败：${it.message ?: "未知错误"}") }

        val scoreRate = min(1_000_000, max(0, round(finalScore / max(1.0, paperTotal) * 1_000_000).toInt()))
        val detail = questions.indices.map { index ->
            mapOf(
                "seq_id" to "",
                "order" to index + 1,
                "type" to questions[index].category,
                "max_score" to questions[index].point,
                "score" to scores[index],
            )
        }
        ETS100ApiClient.reportScore(
            token = session.token,
            params = mapOf(
                "homework_id" to homeworkIdValue,
                "homework_score_rate" to scoreRate,
                "homework_complete_rate" to 1_000_000,
                "homework_use_time" to useTime,
                "seq_id" to "",
                "is_confirm_commit" to 1,
                "resource_id" to resourceId,
                "detail" to detail,
                "audit_traceid" to "",
                "audit_status" to 0,
            ),
        ).getOrElse { throw AutoCompleteException("上报成绩失败：${it.message ?: "未知错误"}") }

        // 提交失败不影响成绩上报结果，与网页版保持一致。
        runCatching {
            ETS100ApiClient.submitHomework(
                token = session.token,
                params = mapOf(
                    "homework_id" to homeworkIdValue,
                    "resource_id" to resourceId,
                    "seq_id" to "",
                    "use_time" to useTime,
                ),
            )
        }

        onStage(stage(7, 95))
        val info = runCatching {
            ETS100ApiClient.getHomeworkInfo(
                token = session.token,
                homeworkId = homework.id,
                resourceId = resourceId,
            ).getOrThrow()
        }.getOrNull()
        return if (info == null) {
            OfficialInfo(null, useTime, null)
        } else {
            OfficialInfo(
                officialPoint = info.optDoubleOrNull("point")
                    ?: info.optDoubleOrNull("score")
                    ?: info.optDoubleOrNull("total_point"),
                officialUseTime = info.optIntOrNull("use_time") ?: useTime,
                officialSubmitTime = info.optString("submit_time")
                    .ifBlank { info.optString("completed_at") }
                    .ifBlank { null },
            )
        }
    }

    /** 会话：只使用本机保存的 E 听说登录态（Token / 手机号 / 密码 / 机器码）。 */
    private suspend fun resolveSession(): Session {
        try {
            ResolverAuthorizer.requireCloudAuthorized(context)
        } catch (e: ResolverAuthorizationDeniedException) {
            throw AutoCompleteException(e.message ?: "云端模式当前不可用")
        }

        val token = ETS100AuthManager.getToken(context).orEmpty()
        val parentId = ETS100AuthManager.getParentAccountId(context).orEmpty()
        if (token.isEmpty() || parentId.isEmpty()) {
            throw AutoCompleteException("请登录E听说账号")
        }
        return Session(token, parentId)
    }

    private fun buildQuestions(struct: JSONObject): List<AutoQuestion> {
        val result = mutableListOf<AutoQuestion>()
        var order = 0

        fun push(
            caption: String,
            category: String,
            typeName: String,
            text: String,
            point: Double,
        ) {
            order += 1
            result.add(
                AutoQuestion(
                    order = order,
                    sectionOrder = order,
                    sectionCaption = caption,
                    typeName = typeName,
                    questionText = text,
                    category = category,
                    point = betterScore(point),
                )
            )
        }

        struct.optJSONArray("lvread_question")?.forEachObject { item ->
            val text = cleanHtml(
                item.optString("text")
                    .ifBlank { item.optString("en_text") }
                    .ifBlank { item.optString("content") }
            )
            if (text.isNotBlank()) {
                push("模仿朗读", "read_sentence", "模仿朗读", text, item.optDouble("score", 0.0))
            }
        }

        struct.optJSONArray("word_question")?.forEachObject { item ->
            push("词汇选择", "choose", "词汇选择", "", item.optDouble("score", 0.0))
        }

        val contents = struct.optJSONArray("contents") ?: struct.optJSONArray("content")
        contents?.forEachObject { content, index ->
            val caption = content.optString("group_name").ifBlank { "题型${index + 1}" }
            val text = cleanHtml(
                content.optString("text")
                    .ifBlank { content.optString("en_text") }
                    .ifBlank { content.optString("content") }
            )
            val point = content.optDouble("score", content.optDouble("point", 0.0))
            if (text.isNotBlank()) {
                val category = if (
                    caption.contains("选择") || caption.contains("听选") ||
                    caption.contains("记录") || caption.contains("填空")
                ) {
                    "choose"
                } else {
                    "read_sentence"
                }
                push(caption, category, caption, text, point)
            }
        }
        return result
    }

    private fun estimateUseTime(questions: List<AutoQuestion>): Int {
        val words = questions.map { question ->
            question.questionText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        }
        return max(60, questions.indices.sumOf { max(6, words[it] * 2) })
    }

    private fun stage(
        index: Int,
        progress: Int,
        label: String = AutoCompleteStage.LABELS.getOrElse(index) { "" },
        totalQuestions: Int = 0,
    ): AutoStageUpdate = AutoStageUpdate(
        stageIndex = index + 1,
        progress = progress,
        label = label,
        totalQuestions = totalQuestions,
    )

    private fun betterScore(point: Double): Double = if (point.isFinite() && point > 0) point else 0.0

    private fun round2(value: Double): Double = round(value * 100) / 100.0

    private fun round4(value: Double): Double = round(value * 10000) / 10000.0

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key, -1).takeIf { it >= 0 }
    }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(action)
        }
    }

    private inline fun JSONArray.forEachObject(action: (JSONObject, Int) -> Unit) {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { action(it, index) }
        }
    }

    private fun cleanHtml(text: String): String {
        if (text.isBlank()) return ""
        val plain = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        return plain.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }
}
