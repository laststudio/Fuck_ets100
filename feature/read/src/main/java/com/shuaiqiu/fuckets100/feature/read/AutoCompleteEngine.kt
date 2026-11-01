package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import android.util.Log
import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100AuthManager
import com.shuaiqiu.fuckets100.ETS100CloudTemplate
import com.shuaiqiu.fuckets100.FeAccountManager
import com.shuaiqiu.fuckets100.ResolverAuthorizationDeniedException
import com.shuaiqiu.fuckets100.ResolverAuthorizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动完成执行引擎（逐字对齐 lastudio_backend `auto_complete/runner.py`）。
 *
 * 流程：
 *   下载资源包 + 解析器出题 → checkstart → 官方 res.json 探针 → `/m/homework/info` 预读
 *   → score-drop → 逐题满分归一 → 目标 ISE 分配 + 配平 → ppr-res → 逐题 sync-v2
 *   → set-use-time → report-score → submit → 回读（必要时裸重试一次 report-score）。
 */
internal class AutoCompleteEngine(
    private val context: Context,
    private val accountManager: FeAccountManager,
) {

    private data class Session(val token: String, val parentId: String)

    private data class OfficialSnapshot(
        val totalPoint: Double,
        val scoreRows: List<JSONObject>,
        val pointBefore: Double?,
    )

    private data class Readback(
        val useTime: Int?,
        val submitTime: String?,
        val point: Double?,
    )

    private data class Ledger(
        val total: Double,
        val iseContributions: List<Double>,
        val recordedContributions: List<Double>,
    )

    /** 逐题上传产物：sync-v2 的 `upload_file_*` 与 `detail_file_*`。 */
    private data class UploadArtifacts(
        val uploadFileId: String = "",
        val uploadFileUrl: String = "",
        val detailFileId: String = "",
        val detailFileUrl: String = "",
    )

    suspend fun run(
        homework: ETS100ApiClient.HomeworkInfo,
        targetRatio: Double,
        cloudBaseUrl: String,
        onStage: (AutoStageUpdate) -> Unit,
    ): AutoRunResult {
        val session = resolveSession()
        currentCoroutineContext().ensureActive()

        onStage(stage(0, 5))
        currentCoroutineContext().ensureActive()

        // 阶段 2：下载资源包 + 解析器出题（与网页/后端同一条链路）
        onStage(stage(1, 15, totalQuestions = 0))
        currentCoroutineContext().ensureActive()
        val success = downloadPaper(homework, cloudBaseUrl)
        val questions = buildAutoQuestions(success)
        if (questions.isEmpty()) {
            throw AutoCompleteException("作业解析后没有任何题目，请确认该作业包含可作答的题型")
        }

        var resourceId = homework.resourceId.ifBlank { "58" }
        val setType = homework.setType
        val statusCode = homework.status.ifBlank { CloudHomeworkState.STATUS_CURRENT }
        val isPkOrVocab = homework.pk == 1 || setType == 6 ||
            listOf("词汇", "单词", "默写", "pk", "word").any { it in homework.name.lowercase() }
        val isBeijing = "北京" in homework.name

        // checkstart：官方会下发最新的 resource_id
        runCatching {
            ETS100ApiClient.checkStart(session.token, homework.id).getOrThrow()
        }.getOrNull()?.let { started ->
            val next = started.optString("resource_id").ifBlank { started.optString("id") }
            if (next.isNotBlank() && next != "0") resourceId = next
        }

        // 官方模板包 res.json：逐题满分的权威来源
        val officialRes = probeOfficialRes(success, homework, session)
        var officialOrders = officialRes?.orders?.toList().orEmpty()

        // 官方作业信息预读：total_point 是官方唯一权威分母，score[] 是官方逐题记录
        val snapshot = readOfficialSnapshot(session, homework, resourceId)

        // score-drop：重新提交前先清掉官方旧分（resource_id 置空、不带 parent_account_id）
        runCatching {
            ETS100ApiClient.scoreDrop(session.token, homework.id).getOrThrow()
        }

        onStage(stage(2, 25, totalQuestions = questions.size))
        currentCoroutineContext().ensureActive()

        // ── 逐题满分归一（官方 res.json > 官方 score[] 权重 > 题目自带分）──
        val rawPaperPoint = success.paper.totalScore ?: 0.0
        val fullPoints = normalizeFullPoints(
            questions = questions,
            officialRes = officialRes,
            snapshot = snapshot,
            rawPaperPoint = rawPaperPoint,
            isPkOrVocab = isPkOrVocab,
            isBeijing = isBeijing,
        ).also { officialOrders = it.orders }

        val paperTotal = fullPoints.total
        if (paperTotal <= 0) {
            throw AutoCompleteException("该作业缺少卷面总分，无法生成目标分")
        }

        val ratio = max(0.0, min(targetRatio, 0.95))
        val targetTotal = round2(paperTotal * ratio)
        val autoTargetRate = if (paperTotal > 0) targetTotal / paperTotal * 100.0 else 0.0

        // ── 目标 ISE + 配平（客观题只给 0 / 满分）──
        val allOrNothing = questions.map { q ->
            AutoCompleteScoring.classifyQuestionType(q.category, q.typeName, q.options).second
        }
        val seeded = AutoCompleteScoring.calcTargetIseScoresByFull(
            targetRatePercent = autoTargetRate,
            fullPoints = fullPoints.points,
            allOrNothing = allOrNothing,
            seed = System.nanoTime(),
        )
        val reconciled = AutoCompleteScoring.reconcileIseScoresToTarget(
            iseScores = seeded.ise,
            awardedScores = seeded.awarded,
            fullPoints = fullPoints.points,
            targetTotal = targetTotal,
            objectiveFlags = allOrNothing,
        )
        var iseScores = reconciled.iseScores
        var awardedScores = reconciled.awardedScores
        if (iseScores.size != questions.size || awardedScores.size != questions.size) {
            iseScores = seeded.ise
            awardedScores = seeded.awarded
        }

        // ppr-res：非 PK / 非模拟卷才有
        if (!isPkOrVocab && questions.isNotEmpty()) {
            val batch = questions.mapIndexed { index, q ->
                mapOf<String, Any>("entity_id" to q.entityId, "order" to index)
            }
            runCatching { ETS100ApiClient.pprResources(session.token, batch).getOrThrow() }
        }

        // ── 逐题 sync-v2 ──
        val actualSetId = homework.setId.ifBlank { homework.id }
        val homeworkIdValue: Any = homework.id.toIntOrNull() ?: homework.id
        val sessionSeqId = "${System.currentTimeMillis()}${String.format(Locale.US, "%04d", (0..9999).random())}"
        val sectionWiseOrders = sectionWiseOrdersOf(questions)

        var cumulativeUseTime = 0
        var accumulatedScore = 0.0
        val questionResults = mutableListOf<AutoQuestionResult>()

        questions.forEachIndexed { index, question ->
            currentCoroutineContext().ensureActive()
            val qPoint = fullPoints.points.getOrElse(index) { 0.0 }
            val qScore = iseScores.getOrElse(index) { 5.0 }
            val awarded = awardedScores.getOrElse(index) { round2(qPoint) }
            val (_, isChoose) = AutoCompleteScoring.classifyQuestionType(
                question.category, question.typeName, question.options,
            )
            val qTime = if (isChoose) 10 else 15
            cumulativeUseTime += qTime

            val clientTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val ansVal = if (isChoose) {
                question.choose.ifBlank {
                    question.answers.firstOrNull()?.trim()?.take(1)
                        ?: "A"
                }
            } else {
                question.ttsText.ifBlank { question.questionText }
            }
            val chooseId = if (isChoose) question.chooseId else ""
            val categoryForDetail = if (isChoose) "choose" else question.category
            val detailJson = AutoCompleteScoring.buildScoreDetail(
                ise = qScore,
                category = categoryForDetail,
                choose = if (isChoose) ansVal else "",
                chooseId = chooseId,
            )

            // 作答音频 + 评分 XML 上传（官方必须在 OSS 上看到文件，否则口语题记 0 分）
            val artifacts = uploadAnswerArtifacts(
                token = session.token,
                question = question,
                text = if (isChoose) {
                    question.questionText.ifBlank { ansVal }
                } else {
                    question.ttsText.ifBlank { question.questionText }
                },
                ise = qScore,
                isChoose = isChoose,
                index = index,
            )

            val syncOrder = resolveSyncOrder(
                index = index,
                question = question,
                officialOrders = officialOrders,
                sectionWiseOrders = sectionWiseOrders,
            )

            val syncParams = mutableMapOf<String, Any>(
                "homework_id" to homeworkIdValue,
                "resource_id" to resourceId,
                "set_id" to actualSetId,
                "entity_id" to question.entityId,
                "order" to syncOrder,
                "score" to format2(qScore),
                "correct" to 1,
                "auto_submit" to 1,
                "use_time" to cumulativeUseTime,
                "graduation" to 0.125,
                "upload_file_id" to artifacts.uploadFileId,
                "upload_file_url" to artifacts.uploadFileUrl,
                "detail_file_id" to artifacts.detailFileId,
                "detail_file_url" to artifacts.detailFileUrl,
                "score_detail" to detailJson,
                "serial_id" to "0",
                "mistake_id" to "",
                "client_time" to clientTime,
                "version" to "2",
                "mark_service" to 1,
                "seq_id" to sessionSeqId,
                "spx_file_url" to "",
                "mark_trace_id" to "{\"engine\": \"cloud\"}",
            )
            val syncResult = ETS100ApiClient.syncQuestion(session.token, syncParams)
            if (syncResult.isFailure) {
                val error = syncResult.exceptionOrNull()?.message ?: "未知错误"
                throw AutoCompleteException("第 ${index + 1} 题云端答题同步失败：$error")
            }

            accumulatedScore += awarded
            questionResults.add(
                AutoQuestionResult(
                    order = index + 1,
                    typeName = question.typeName,
                    text = question.questionText.take(80),
                    awardedScore = round2(awarded),
                    maxScore = round2(qPoint),
                    iseScore = round2(qScore),
                )
            )

            onStage(
                AutoStageUpdate(
                    stageIndex = 4,
                    progress = 25 + 55 * (index + 1) / max(1, questions.size),
                    label = "[4/8] 正在处理第 ${index + 1}/${questions.size} 题: " +
                        question.questionText.take(24).ifBlank { question.typeName },
                    currentQuestion = question.questionText,
                    currentQuestionIndex = index + 1,
                    totalQuestions = questions.size,
                )
            )
        }

        // ── 上报总分 = Σ 逐题官方口径账（构造保证）──
        val ledger = buildLedger(questionResults, fullPoints.points)
        val recordedTotal = round2(ledger.recordedContributions.sum())
        val ledgerFieldDrift = ledger.iseContributions.indices.maxOfOrNull { index ->
            abs(ledger.iseContributions[index] - ledger.recordedContributions[index])
        } ?: 0.0
        var finalScore = ledger.total
        if (ledgerFieldDrift > 0.011) {
            finalScore = min(finalScore, recordedTotal)
        }
        val finalUseTime = cumulativeUseTime
        val reportedScore = min(paperTotal, finalScore)

        onStage(
            AutoStageUpdate(
                stageIndex = 6,
                progress = 88,
                label = "[7/8] 同步用时 (${finalUseTime}秒) 并提交作业终审...",
                totalQuestions = questions.size,
            )
        )
        currentCoroutineContext().ensureActive()

        // set-use-time：失败仅告警（官方口径）
        runCatching {
            ETS100ApiClient.setUseTime(
                session.token,
                mapOf(
                    "homework_id" to homeworkIdValue,
                    "resource_id" to resourceId,
                    "use_time" to finalUseTime,
                    "reset" to 0,
                    "end" to 1,
                    "correct" to 0,
                    "version" to 2,
                ),
            ).getOrThrow()
        }

        val scoreRate = min(
            1_000_000,
            max(0, round(finalScore / max(1.0, paperTotal) * 1_000_000.0).toInt()),
        )
        val reportParams = mutableMapOf<String, Any>(
            "homework_id" to homeworkIdValue,
            "homework_score_rate" to scoreRate,
            "homework_complete_rate" to 1_000_000,
            "homework_use_time" to finalUseTime,
            "seq_id" to "",
            "is_confirm_commit" to 1,
            "resource_id" to resourceId,
            "parent_account_id" to session.parentId,
        )
        ETS100ApiClient.reportScore(session.token, reportParams).getOrElse {
            throw AutoCompleteException("上报成绩失败：${it.message ?: "未知错误"}")
        }

        // 辅助提交（忽略失败）
        runCatching {
            ETS100ApiClient.submitHomework(
                session.token,
                mapOf(
                    "homework_id" to homeworkIdValue,
                    "resource_id" to resourceId,
                    "seq_id" to "",
                    "use_time" to finalUseTime,
                ),
            )
        }

        onStage(stage(7, 95, totalQuestions = questions.size))
        currentCoroutineContext().ensureActive()
        delay(500)

        var readback = readHomeworkInfo(session, homework, resourceId)
        var officialPoint = readback.point
        val scoreRatePct = round(reportedScore / max(1.0, paperTotal) * 1000.0) / 10.0
        var classification = classifyOfficialReadback(officialPoint, reportedScore, scoreRatePct, paperTotal)
        var delta = officialMinusReported(
            classification.first, officialPoint, classification.second, reportedScore, paperTotal,
        )

        // 官方账明显低于我们上报的分 → 同一份载荷裸重试一次 report-score 并回读
        if (shareableRetry(classification.first, delta, paperTotal)) {
            runCatching { ETS100ApiClient.reportScore(session.token, reportParams).getOrThrow() }
            delay(500)
            readback = readHomeworkInfo(session, homework, resourceId)
            officialPoint = readback.point
            classification = classifyOfficialReadback(officialPoint, reportedScore, scoreRatePct, paperTotal)
            delta = officialMinusReported(
                classification.first, officialPoint, classification.second, reportedScore, paperTotal,
            )
        }

        val completedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        onStage(stage(7, 100, label = "作业自动完成已顺利完成并提交成功！", totalQuestions = questions.size))

        return AutoRunResult(
            homeworkId = homework.id,
            homeworkName = homework.name,
            score = reportedScore,
            maxScore = paperTotal,
            scoreRate = if (paperTotal > 0) round4(reportedScore / paperTotal) else 0.0,
            targetTotal = targetTotal,
            useTime = readback.useTime ?: finalUseTime,
            officialPoint = officialPoint,
            officialUseTime = readback.useTime ?: finalUseTime,
            officialSubmitTime = readback.submitTime,
            completedAt = completedAt,
            questionResults = questionResults,
        )
    }

    // ==================== 作答文件上传（对齐 runner.py pre_upload/oss_config/upload） ====================

    /**
     * 逐题上传作答文件：
     * * 任何题型都先 `pre-upload` 拿 `upload_file_id`；
     * * 口语题把音频（TTS 未接入时用后端同款静音 mp3 占位）传到 OSS，回填 `upload_file_url`；
     * * 所有题都把评分 XML 传到 OSS，回填 `detail_file_id` / `detail_file_url`。
     *
     * 上传失败只告警、不中断整卷（与后端 `_raise_if_session_failed + warning` 同口径）。
     */
    private suspend fun uploadAnswerArtifacts(
        token: String,
        question: AutoQuestion,
        text: String,
        ise: Double,
        isChoose: Boolean,
        index: Int,
    ): UploadArtifacts {
        var uploadFileId = ""
        var uploadFileUrl = ""
        var detailFileId = ""
        var detailFileUrl = ""

        // 1) 预上传：拿到官方文件 id（文件名固定为 `{id}.mp3` / `{id}.xml`）
        uploadFileId = runCatching {
            ETS100ApiClient.preUpload(token).getOrThrow().optString("id")
        }.getOrElse { error ->
            Log.w(TAG, "第 ${index + 1} 题预上传作答文件失败: ${error.message}")
            ""
        }

        // 2) 口语题：音频直传 OSS
        if (!isChoose && uploadFileId.isNotBlank()) {
            uploadFileUrl = runCatching {
                val config = ETS100ApiClient.ossConfig(token, "audio", uploadFileId).getOrThrow()
                ETS100ApiClient.uploadOssForm(
                    host = config.optString("host"),
                    dirName = config.optString("dir"),
                    fileName = "$uploadFileId.mp3",
                    accessId = config.optString("accessid"),
                    policy = config.optString("policy"),
                    signature = config.optString("signature"),
                    callback = config.optString("callback"),
                    content = SILENT_MP3_STUB,
                )
            }.getOrElse { error ->
                Log.w(TAG, "第 ${index + 1} 题上传作答音频失败: ${error.message}")
                ""
            }
        }

        // 3) 评分 XML：所有题型都上传
        val xml = AutoCompleteScoring.generateScoreXml(
            text = text,
            profile = AutoCompleteScoring.profileForIse(ise),
            category = question.category,
            seed = index.toLong(),
        )
        detailFileId = runCatching {
            ETS100ApiClient.preUpload(token).getOrThrow().optString("id")
        }.getOrElse { error ->
            Log.w(TAG, "第 ${index + 1} 题预上传评分 XML 失败: ${error.message}")
            ""
        }
        if (detailFileId.isNotBlank()) {
            detailFileUrl = runCatching {
                val config = ETS100ApiClient.ossConfig(token, "xml", detailFileId).getOrThrow()
                ETS100ApiClient.uploadOssForm(
                    host = config.optString("host"),
                    dirName = config.optString("dir"),
                    fileName = "$detailFileId.xml",
                    accessId = config.optString("accessid"),
                    policy = config.optString("policy"),
                    signature = config.optString("signature"),
                    callback = config.optString("callback"),
                    content = xml.toByteArray(Charsets.UTF_8),
                )
            }.getOrElse { error ->
                Log.w(TAG, "第 ${index + 1} 题上传评分 XML 失败: ${error.message}")
                ""
            }
        }

        return UploadArtifacts(
            uploadFileId = uploadFileId,
            uploadFileUrl = uploadFileUrl,
            detailFileId = detailFileId,
            detailFileUrl = detailFileUrl,
        )
    }

    // ==================== 会话 ====================

    /** 会话：只复用已保存的 E 听说登录，不再访问 LastStudio 绑定会话。 */
    private suspend fun resolveSession(): Session {
        try {
            ResolverAuthorizer.requireCloudAuthorized(context)
        } catch (e: ResolverAuthorizationDeniedException) {
            throw AutoCompleteException(e.message ?: "云端模式当前不可用")
        }

        val loginMethod = ETS100AuthManager.getLoginMethod(context)
        if (loginMethod == ETS100AuthManager.LOGIN_METHOD_PASSWORD ||
            loginMethod == ETS100AuthManager.LOGIN_METHOD_CHANGYAN_WEB
        ) {
            val token = ETS100AuthManager.getToken(context).orEmpty()
            val parentId = ETS100AuthManager.getParentAccountId(context).orEmpty()
            if (token.isEmpty() || parentId.isEmpty()) {
                throw AutoCompleteException("请登录E听说账号")
            }
            return Session(token, parentId)
        }

        // LastStudio 账号服务已停用：不再请求绑定会话，统一要求登录 E 听说账号。
        throw AutoCompleteException("LastStudio 账号已停用，请登录 E 听说账号")
    }

    // ==================== 题目来源 ====================

    private suspend fun downloadPaper(
        homework: ETS100ApiClient.HomeworkInfo,
        cloudBaseUrl: String,
    ): CloudPaperResult.Success {
        val status = homework.status.ifBlank { CloudHomeworkState.STATUS_CURRENT }
        return when (
            val result = downloadAndParseCloudPaper(
                context = context,
                homeworkInfo = homework,
                status = status,
                cloudBaseUrl = cloudBaseUrl,
                onProgress = {},
            )
        ) {
            is CloudPaperResult.Success -> result
            is CloudPaperResult.AuthDenied -> throw AutoCompleteException(result.message)
            is CloudPaperResult.Failure -> throw AutoCompleteException(
                "下载/解析作业失败：${result.message}"
            )
        }
    }

    /**
     * 题目展开：官方合并 paper → 解析器原始包 → 已解析 sections。
     * 字段语义对齐 `homework.py` 的 `_sections_from_official_paper` /
     * `resolve_content_with_resolver` 与 `runner.py` 的 `_flatten_questions`。
     */
    private fun buildAutoQuestions(success: CloudPaperResult.Success): List<AutoQuestion> {
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

    // ==================== 分值归一 ====================

    private data class FullPoints(
        val total: Double,
        val points: List<Double>,
        val orders: List<Int>,
    )

    /**
     * `runner.py` 1692–1867 的逐题满分归一。
     *
     * 1) 逐题初值：≤0 或约等于 100 → 权重 1.0；
     * 2) 卷面分母：官方 total_point > 题目自带分之和 > 原卷面分 > 30/40；
     * 3) 官方 res.json 可用 → 直接采用官方逐题分布（与卷面分不一致时保留卷面分作分母）；
     * 4) 否则按官方 score[] 权重（无则按题目自带分）把卷面总分摊满。
     */
    private fun normalizeFullPoints(
        questions: List<AutoQuestion>,
        officialRes: OfficialRes?,
        snapshot: OfficialSnapshot,
        rawPaperPoint: Double,
        isPkOrVocab: Boolean,
        isBeijing: Boolean,
    ): FullPoints {
        val count = questions.size
        var points: MutableList<Double> = questions.map { q ->
            var value = q.point
            if (value <= 0.0 || abs(value - 100.0) < 0.01) value = 1.0
            round2(value)
        }.toMutableList()
        val sumQuestionsPoint = round2(points.sum())
        var paperTotal = when {
            isPkOrVocab -> 100.0
            snapshot.totalPoint > 0 -> round2(snapshot.totalPoint)
            sumQuestionsPoint > 0 -> sumQuestionsPoint
            rawPaperPoint > 0 && abs(rawPaperPoint - 100.0) >= 0.01 -> rawPaperPoint
            else -> if (isBeijing) 40.0 else 30.0
        }

        val mapping = AutoCompleteOfficialRes.mapToQuestions(officialRes, questions)
        var orders = mapping.orders
        if (mapping.mode == "section_scaled" && orders.isNotEmpty()) {
            orders = emptyList()
        }
        if (mapping.available && mapping.points.isNotEmpty()) {
            points = mapping.points.map { round2(it) }.toMutableList()
            val officialSum = round2(points.sum())
            if (officialSum > 0) {
                paperTotal = if (
                    snapshot.totalPoint <= 0 ||
                    abs(officialSum - round2(snapshot.totalPoint)) <= 0.01 ||
                    mapping.legacyMatch
                ) {
                    officialSum
                } else {
                    round2(snapshot.totalPoint)
                }
            }
        } else if (count > 0 && paperTotal > 0) {
            val weights = if (snapshot.scoreRows.isNotEmpty()) {
                officialWeights(questions, snapshot.scoreRows)
            } else {
                points.map { it.takeIf { v -> v > 0.0 } ?: 1.0 }
            }
            val sumWeight = weights.sum().takeIf { it > 0 } ?: count.toDouble()
            val scale = paperTotal / sumWeight
            points = weights.map { round2(it * scale) }.toMutableList()
            val diff = round2(paperTotal - points.sum())
            if (diff != 0.0 && points.isNotEmpty()) {
                points[points.lastIndex] = round2(points.last() + diff)
            }
        }
        if (points.size != count) {
            points = questions.map { round2(it.point.takeIf { v -> v > 0.0 } ?: 1.0) }.toMutableList()
        }
        return FullPoints(
            total = round2(paperTotal),
            points = points,
            orders = orders.takeIf { it.size == count } ?: emptyList(),
        )
    }

    /** 官方 `score[]` 的节权重 → 逐题权重（组内按题数均分）。 */
    private fun officialWeights(
        questions: List<AutoQuestion>,
        rows: List<JSONObject>,
    ): List<Double> {
        val byEntity = rows.groupBy { it.optString("entity_id", "") }
        fun sectionWeight(entityId: String): Double {
            val sectionRows = byEntity[entityId].orEmpty()
            val values = sectionRows.mapNotNull { row ->
                for (key in listOf("standard_score", "real_score")) {
                    val value = jsonDouble(row.opt(key)) ?: continue
                    if (value > 0) return@mapNotNull value
                }
                null
            }
            if (values.isEmpty()) return 0.0
            return values.average() * sectionRows.size
        }
        val sectionOf = questions.map { q ->
            q.entityId.ifBlank { q.sectionCaption }.ifBlank { "sec${q.sectionOrder}" }
        }
        val counts = sectionOf.groupingBy { it }.eachCount()
        val weightsBySection = counts.mapValues { (key, value) ->
            val weight = sectionWeight(key)
            if (weight > 0) weight / value else 1.0
        }
        return sectionOf.map { weightsBySection[it] ?: 1.0 }
    }

    // ==================== 官方数据探针 ====================

    private suspend fun probeOfficialRes(
        success: CloudPaperResult.Success,
        homework: ETS100ApiClient.HomeworkInfo,
        session: Session,
    ): OfficialRes? {
        success.resJson?.let { json ->
            AutoCompleteOfficialRes.parse(json)?.let { return it }
        }
        return runCatching {
            val body = ETS100ApiClient.getHomeworkStructM(
                token = session.token,
                homeworkId = homework.id,
                resourceId = homework.resourceId,
                parentAccountId = session.parentId,
            ).getOrThrow()
            val struct = ETS100CloudTemplate.parseStruct(body) ?: return@runCatching null
            val cacheDir = File(context.cacheDir, "auto_complete_templates")
            val resJson = ETS100CloudTemplate.fetchResJson(struct.templateUrl, cacheDir) { zipFile, destDir ->
                net.lingala.zip4j.ZipFile(zipFile).extractAll(destDir.absolutePath)
            } ?: return@runCatching null
            AutoCompleteOfficialRes.parse(resJson)
        }.getOrNull()
    }

    private suspend fun readOfficialSnapshot(
        session: Session,
        homework: ETS100ApiClient.HomeworkInfo,
        resourceId: String,
    ): OfficialSnapshot {
        return runCatching {
            val body = ETS100ApiClient.getHomeworkInfo(
                token = session.token,
                homeworkId = homework.id,
                resourceId = resourceId.ifBlank { "58" },
                parentAccountId = session.parentId,
            ).getOrThrow()
            val totalPoint = jsonDouble(body.opt("total_point")) ?: 0.0
            val rows = mutableListOf<JSONObject>()
            body.optJSONArray("score")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { rows.add(it) }
                }
            }
            OfficialSnapshot(
                totalPoint = totalPoint,
                scoreRows = rows,
                pointBefore = jsonDouble(body.opt("point")),
            )
        }.getOrElse { OfficialSnapshot(0.0, emptyList(), null) }
    }

    private suspend fun readHomeworkInfo(
        session: Session,
        homework: ETS100ApiClient.HomeworkInfo,
        resourceId: String,
    ): Readback {
        return runCatching {
            val body = ETS100ApiClient.getHomeworkInfo(
                token = session.token,
                homeworkId = homework.id,
                resourceId = resourceId,
                parentAccountId = session.parentId,
            ).getOrThrow()
            val useTime = jsonInt(body.opt("use_time"))
                ?: jsonInt(body.opt("submit_use_time"))
                ?: jsonInt(body.opt("homework_use_time"))
            Readback(
                useTime = useTime,
                submitTime = body.optString("submit_time").ifBlank { null },
                point = jsonDouble(body.opt("point")),
            )
        }.getOrElse { Readback(null, null, null) }
    }

    // ==================== 账本 / 回读校验 ====================

    private fun buildLedger(
        questionResults: List<AutoQuestionResult>,
        points: List<Double>,
    ): Ledger {
        val iseContribs = mutableListOf<Double>()
        val recordedContribs = mutableListOf<Double>()
        questionResults.forEachIndexed { index, result ->
            val point = points.getOrElse(index) { result.maxScore }
            iseContribs.add(AutoCompleteScoring.officialContribution(result.iseScore, point))
            recordedContribs.add(min(max(0.0, point), max(0.0, result.awardedScore)))
        }
        return Ledger(
            total = round2(iseContribs.sum()),
            iseContributions = iseContribs,
            recordedContributions = recordedContribs,
        )
    }

    /** `classify_official_readback` → (unit, absolute)。 */
    private fun classifyOfficialReadback(
        point: Double?,
        reported: Double,
        scoreRatePct: Double,
        paperTotal: Double,
    ): Pair<String?, Double?> {
        if (point == null) return null to null
        if (abs(point - reported) <= OFFICIAL_ABS_TOLERANCE) return "absolute" to point
        if (point in 0.0..100.0 && abs(point - scoreRatePct) <= OFFICIAL_PERCENT_TOLERANCE) {
            val absolute = if (paperTotal > 0) round(point * paperTotal / 100.0 * 1000.0) / 1000.0 else null
            return "percent" to absolute
        }
        return "unknown" to null
    }

    private fun officialMinusReported(
        unit: String?,
        point: Double?,
        absolutePoint: Double?,
        reported: Double,
        paperTotal: Double,
    ): Double? {
        if (point == null) return null
        if ((unit == "absolute" || unit == "percent") && absolutePoint != null) {
            return round((absolutePoint - reported) * 1000.0) / 1000.0
        }
        if (unit == "unknown") {
            val ceiling = max(paperTotal, reported)
            if (point in 0.0..ceiling) return round((point - reported) * 1000.0) / 1000.0
        }
        return null
    }

    private fun shareableRetry(unit: String?, delta: Double?, paperTotal: Double): Boolean {
        if (delta == null || unit == "percent") return false
        val material = max(OFFICIAL_MISMATCH_MATERIAL, 0.01 * paperTotal)
        return delta < -material
    }

    // ==================== sync order ====================

    /** `section_wise_orders`：每节内部从 1 开始的题号。 */
    private fun sectionWiseOrdersOf(questions: List<AutoQuestion>): List<Int> {
        val counter = mutableMapOf<Int, Int>()
        return questions.map { question ->
            val next = (counter[question.sectionOrder] ?: 0) + 1
            counter[question.sectionOrder] = next
            next
        }
    }

    /**
     * 官方按 `entity_id + order` 定位某题；order = 节内 1-based 题号。
     * 优先级：官方 res.json 的 question_order → 节内 1-based → sub_order + 1。
     */
    private fun resolveSyncOrder(
        index: Int,
        question: AutoQuestion,
        officialOrders: List<Int>,
        sectionWiseOrders: List<Int>,
    ): Int {
        if (officialOrders.isNotEmpty() && index < officialOrders.size) {
            return officialOrders[index]
        }
        return sectionWiseOrders.getOrElse(index) { question.subOrder + 1 }
    }

    // ==================== 小工具 ====================

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

    private fun round2(value: Double): Double = round(value * 100) / 100.0

    private fun round4(value: Double): Double = round(value * 10000) / 10000.0

    private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun jsonText(value: Any?): String = when (value) {
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

    private fun jsonDouble(value: Any?): Double? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    private fun jsonInt(value: Any?): Int? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toInt()
        is String -> value.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val result = mutableListOf<String>()
        for (index in 0 until length()) {
            val text = jsonText(opt(index))
            if (text.isNotBlank()) result.add(text)
        }
        return result
    }

    private fun cleanHtml(text: String): String {
        if (text.isBlank()) return ""
        val plain = android.text.Html.fromHtml(
            text,
            android.text.Html.FROM_HTML_MODE_LEGACY,
        ).toString()
        return plain.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private companion object {
        const val TAG = "AutoComplete"
        const val OFFICIAL_ABS_TOLERANCE = 0.05
        const val OFFICIAL_PERCENT_TOLERANCE = 2.0
        const val OFFICIAL_MISMATCH_MATERIAL = 0.5

        /** 后端 `voice_mode=silent` 同款静音 mp3 占位（TTS 未接入阶段使用）。 */
        val SILENT_MP3_STUB: ByteArray =
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(32)

        /** `homework.py` STRUCTURE_TYPE_META（structure_type → (category, typeName)）。 */
        val STRUCTURE_TYPE_META: Map<String, Pair<String, String>> = mapOf(
            "collector.read" to ("read_sentence" to "模仿朗读"),
            "collector.word" to ("read_sentence" to "词汇练习"),
            "collector.role" to ("role" to "听选信息"),
            "collector.choose" to ("choose" to "听后选择"),
            "collector.picture" to ("read_sentence" to "信息转述"),
            "collector.fill" to ("fill" to "听后记录"),
            "collector.3q5a" to ("read_sentence" to "三问五答"),
            "collector.repeat_essay" to ("read_sentence" to "短文复述"),
            "collector.repeat_dialogue" to ("read_sentence" to "短文复述"),
            "collector.dialogue" to ("dialogue" to "回答问题"),
        )
    }
}
