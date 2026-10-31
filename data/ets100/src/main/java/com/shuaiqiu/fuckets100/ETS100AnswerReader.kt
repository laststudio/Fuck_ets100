package com.shuaiqiu.fuckets100

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * ETS100 答案读取器
 * 负责从ETS100FileReader获取的阅读器读取和解析ETS100数据
 *
 * 使用示例:
 * val papers = ETS100AnswerReader.readPapers(context, currentMode)
 * for (paper in papers) {
 *     for (section in paper.sections) {
 *         for (question in section.questions) {
 *             println("${question.typeName}: ${question.questionText}")
 *         }
 *     }
 * }
 *
 * 喵~ 根据文档完善了解析逻辑，现在支持所有 5 种题目类型和 3 种 structure_type 喵！
 */
object ETS100AnswerReader {

    private const val TAG = "ETS100AnswerReader"
    private const val DIRECT_READ_PARALLEL_PARSE_LIMIT = 4
    private const val PRIVILEGED_PARALLEL_PARSE_LIMIT = 4
    private const val RESOURCE_SCAN_CACHE_TTL_MS = 15_000L
    private const val VERBOSE_PARSE_LOGS = false

    internal fun localPaperDisplayNumber(groupIndex: Int, totalGroups: Int): Int {
        return if (totalGroups > 0) {
            totalGroups - groupIndex
        } else {
            groupIndex + 1
        }.coerceAtLeast(1)
    }

    private fun logVerbose(message: String) {
        if (VERBOSE_PARSE_LOGS) {
            Log.d(TAG, message)
        }
    }

    fun clearResourceScanCache(mode: ActivationMode? = null) = ETS100AnswerResourceScanner.clearCache(mode)

    /**
     * structure_type 常量
     * 决定了 content.json 的解析方式
     */
    object StructureType {
        const val COLLECTOR_ROLE = "collector.role"        // 问答题：听说/问答/询问
        const val COLLECTOR_PICTURE = "collector.picture"  // 信息转述
        const val COLLECTOR_READ = "collector.read"        // 模仿朗读
        const val COLLECTOR_3Q5A = "collector.3q5a"        // 广东高中：3问5答
        const val COLLECTOR_CHOOSE = "collector.choose"    // 北京：听后选择
        const val COLLECTOR_FILL = "collector.fill"        // 北京高中：听后记录/填空
        const val COLLECTOR_DIALOGUE = "collector.dialogue" // 北京高中：回答问题
        const val COLLECTOR_REPEAT_ESSAY = "collector.repeat_essay" // 短文复述/逐句跟读
        const val WORD_CHOICE = "word.choice"              // 词汇选择
        const val WORD_WRITE = "word.write"                // 单词拼写
        const val WORD_READ = "word.read"                  // 单词跟读
        const val WORD_DICTATION = "word.dictation"        // 词汇听写
        const val WORD_TRANSLATE_EN_CN = "word.translate_en_cn" // 英译中
        const val WORD_TRANSLATE_CN_EN = "word.translate_cn_en" // 中译英
    }

    /**
     * 试卷数据类
     */
    data class Paper(
        val paperId: Long,
        val title: String,
        val dataFileName: String,
        val fileSize: Long,
        val sections: List<Section>,
        val downloadTime: Long = 0L,  // 宝贝添加了下载时间喵~
        val regionLabel: String = "未知",  // 喵~ 添加地区标签：初中/高中/未知
        val paperName: String? = null,  // 喵~ 添加试卷名称（来自resource索引）
        val source: PaperSource? = null,
        /** 官方卷面总分（来自 res.json 的 set_score）；本地模式或旧路径为 null */
        val totalScore: Double? = null,
        /** 完整性对账（解析器逐节与官方 res.json 比对）；旧路径/单包模式为 null */
        val integrity: PaperIntegrity? = null
    )

    /** 卷面完整性对账结果，见 [PaperIntegrity]。 */
    typealias PaperIntegrity = com.shuaiqiu.fuckets100.PaperIntegrity

    /**
     * 分区数据类
     *
     * 注意: 每个 section 可能包含多个不同类型的题目
     */
    data class Section(
        val caption: String,           // 标题：练习/章节的阅读理解名称
        val category: String,           // 类别：read_chapter 或 simple_expression_ufi
        val typeName: String,           // 题目类型名称
        val questions: List<Question>,   // 该 section 下的所有题目
        val originalContent: String?    // 原始内容用于后续解析
    ) {
        val title: String get() = caption
    }

    /**
     * 题目数据类
     */
    data class Question(
        val order: Int,                // 题目序号从1开始计数
        val sectionOrder: Int,          // 当前 section 下的题目序号
        val sectionCaption: String,     // 当前 section 的标题
        val typeName: String,           // 题目类型名称
        val questionText: String,       // 题目文本
        val answers: List<String>,      // 答案列表
        val originalText: String?,      // 原始文本内容
        val category: String = "",       // category 分类
        val displayOrder: Int? = null,
        val content: AnswerContent = AnswerContent.Reading(""),  // 用于UI显示
        /** 官方逐题满分（来自 res.json 的 question_score）；本地模式或旧路径为 null */
        val score: Double? = null
    ) {
        // 兼容性别名 - ReadScreen 使用 question.question
        val question: String get() = cleanQuestionText(questionText)
        val answer: String get() = answers.firstOrNull() ?: ""
        val answerList: List<String> get() = answers
        val formattedAnswer: String get() = answers
            .flatMap { ETS100AnswerReader.cleanAnswerText(it).lines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val formattedOriginalText: String get() = originalText
            ?.let { cleanOriginalText(it) }
            .orEmpty()
    }

    /**
     * 答案内容类型
     * 用于区分不同类型的答案展示
     */
    sealed class AnswerContent {
        data class Reading(val text: String) : AnswerContent()
        data class Choice(val items: List<ChoiceItem>) : AnswerContent()
        data class QATuple(val pairs: List<QAPair>) : AnswerContent()
    }

    data class ChoiceItem(
        val question: String,
        val options: List<String>,
        val correctAnswer: Int,
        val standardAnswer: String = ""
    )

    data class QAPair(
        val question: String,
        val answer: String
    )

    // 分类映射表 - 将API返回的category映射为可读的中文类型名
    // 喵~ 根据文档这个映射是正确的喵！

    // category 到 structure_type 的映射
    // 用于当 content.json 中没有 structure_type 时 fallback
    // 习题组 code_id 到 group_name 的映射
    // 喵~ 根据文档，习题组有 7 种题型（st1-st7）喵！
    internal val exerciseGroupCodeIdMap = mapOf(
        "st1" to "模仿朗读",
        "st2" to "听选信息",
        "st3" to "听选信息",
        "st4" to "听选信息",
        "st5" to "回答问题",
        "st6" to "信息转述",
        "st7" to "询问信息"
    )

    // 习题组 code_id 到 category 的映射
    internal val exerciseGroupCodeIdToCategory = mapOf(
        "st1" to "read_chapter",
        "st2" to "simple_expression_ufi",
        "st3" to "simple_expression_ufi",
        "st4" to "simple_expression_ufi",
        "st5" to "simple_expression_ufk",
        "st6" to "topic",
        "st7" to "simple_expression_ufj"
    )

    fun cleanAnswerText(text: String): String {
        return cleanDisplayText(text, splitPipes = true)
    }

    fun readPapers(context: Context, mode: ActivationMode): List<Paper> {
        if (!ETS100FileReader.isModeAvailable(mode, context)) {
            Log.w(TAG, "Mode $mode is not available")
            return emptyList()
        }

        kotlinx.coroutines.runBlocking { ResolverAuthorizer.requireAuthorized(context) }
        val reader = try {
            ETS100FileReader.getReader(mode, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get reader", e)
            return emptyList()
        }

        val resourceFolders = ETS100AnswerResourceScanner.scanResourceFolders(reader)
        val resourceOrderMap = ETS100AnswerResourceScanner.buildResourceOrderMap(reader)
        val groupedFolders = ETS100AnswerResourceScanner.groupResourceFolders(reader, resourceFolders)
        Log.i(TAG, "readPapers: 得到 ${groupedFolders.size} 组试卷")

        val exerciseGroupPapers = mutableListOf<Paper>()

        // Step 3 & 4: 根据文件夹数量路由，遍历每组的 content.json
        for ((groupIndex, folderGroup) in groupedFolders.withIndex()) {
            val orderedFolderGroup = ETS100AnswerResourceScanner.orderResourceFoldersByPaperStructure(folderGroup, resourceOrderMap)
            exerciseGroupPapers.addAll(parseResourceGroup(reader, orderedFolderGroup, groupIndex, groupedFolders.size))
        }

        Log.i(TAG, "readPapers: 完成，共 ${exerciseGroupPapers.size} 份试卷")

        return exerciseGroupPapers
    }

    fun readPaperSummaries(context: Context, mode: ActivationMode): List<Paper> {
        if (!ETS100FileReader.isModeAvailable(mode, context)) {
            Log.w(TAG, "Mode $mode is not available")
            return emptyList()
        }

        val reader = try {
            ETS100FileReader.getReader(mode, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get reader", e)
            return emptyList()
        }

        val resourceFolders = ETS100AnswerResourceScanner.scanResourceFolders(reader)
        val resourceOrderMap = emptyMap<String, Int>()
        val isPrivilegedMode = mode == ActivationMode.ROOT || mode == ActivationMode.SHIZUKU
        val groupedFolders = if (isPrivilegedMode) {
            ETS100AnswerResourceScanner.groupResourceFoldersFast(resourceFolders)
        } else {
            ETS100AnswerResourceScanner.groupResourceFolders(reader, resourceFolders)
        }

        return groupedFolders.mapIndexed { groupIndex, folderGroup ->
            val orderedFolderGroup = ETS100AnswerResourceScanner.orderResourceFoldersByPaperStructure(folderGroup, resourceOrderMap)
            if (isPrivilegedMode) {
                createFastPaperSummary(orderedFolderGroup, groupIndex, groupedFolders.size, mode)
            } else {
                createPaperSummary(reader, orderedFolderGroup, groupIndex, groupedFolders.size, mode)
            }
        }
    }

    suspend fun readPapersParallel(
        context: Context,
        mode: ActivationMode,
        onGroupStarted: (suspend (Int, Int) -> Unit)? = null,
        onGroupParsed: (suspend (Int, List<Paper>) -> Unit)? = null
    ): List<Paper> = coroutineScope {
        if (!ETS100FileReader.isModeAvailable(mode, context)) {
            Log.w(TAG, "Mode $mode is not available")
            return@coroutineScope emptyList()
        }

        ResolverAuthorizer.requireAuthorized(context)
        val reader = try {
            ETS100FileReader.getReader(mode, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get reader", e)
            return@coroutineScope emptyList()
        }

        val resourceFolders = ETS100AnswerResourceScanner.scanResourceFolders(reader)
        val resourceOrderMap = ETS100AnswerResourceScanner.buildResourceOrderMap(reader)
        val groupedFolders = ETS100AnswerResourceScanner.groupResourceFolders(reader, resourceFolders)
        Log.i(TAG, "readPapersParallel: 得到 ${groupedFolders.size} 组试卷")

        val parallelLimit = when (mode) {
            ActivationMode.DIRECT_READ -> DIRECT_READ_PARALLEL_PARSE_LIMIT
            ActivationMode.ROOT,
            ActivationMode.SHIZUKU -> PRIVILEGED_PARALLEL_PARSE_LIMIT
            else -> 1
        }
        Log.i(TAG, "readPapersParallel: $mode 使用 $parallelLimit 路解析")

        val semaphore = Semaphore(parallelLimit)
        groupedFolders.mapIndexed { groupIndex, folderGroup ->
            val orderedFolderGroup = ETS100AnswerResourceScanner.orderResourceFoldersByPaperStructure(folderGroup, resourceOrderMap)
            async(kotlinx.coroutines.Dispatchers.IO) {
                semaphore.withPermit {
                    onGroupStarted?.invoke(groupIndex, groupedFolders.size)
                    val startMs = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "readPapersParallel: START group=${groupIndex + 1}/${groupedFolders.size}, " +
                            "folders=${orderedFolderGroup.size}, mode=$mode, thread=${Thread.currentThread().name}"
                    )
                    val localReader = ETS100FileReader.getReader(mode, context)
                    val papers = parseResourceGroup(localReader, orderedFolderGroup, groupIndex, groupedFolders.size)
                    onGroupParsed?.invoke(groupIndex, papers)
                    Log.i(
                        TAG,
                        "readPapersParallel: END group=${groupIndex + 1}/${groupedFolders.size}, " +
                            "papers=${papers.size}, cost=${System.currentTimeMillis() - startMs}ms, " +
                            "thread=${Thread.currentThread().name}"
                    )
                    GroupParseResult(groupIndex = groupIndex, papers = papers)
                }
            }
        }.awaitAll()
            .sortedBy { it.groupIndex }
            .flatMap { it.papers }
            .also { Log.i(TAG, "readPapersParallel: 完成，共 ${it.size} 份试卷") }
    }

    internal fun detectResourceGroupTemplate(
        reader: ETS100FileReader.Reader,
        folders: List<ETS100FileReader.FileItem>
    ): ResourceGroupTemplate {
        val profile = readResourceGroupProfile(reader, folders)
        return detectResourceGroupTemplate(profile)
    }

    internal fun detectResourceGroupTemplate(
        profile: ResourceGroupProfile
    ): ResourceGroupTemplate {
        return when {
            profile.hasBeijingFillData || profile.hasBeijingDialogueData -> ResourceGroupTemplate.BEIJING_HIGH
            profile.hasGuangdongHighData -> ResourceGroupTemplate.GUANGDONG_HIGH
            profile.hasGuangdongJuniorData -> ResourceGroupTemplate.GUANGDONG_JUNIOR
            profile.hasBeijingChooseData -> ResourceGroupTemplate.BEIJING_JUNIOR
            else -> ResourceGroupTemplate.GENERIC
        }
    }

    internal fun scoreResourceGroupCandidate(
        reader: ETS100FileReader.Reader,
        folders: List<ETS100FileReader.FileItem>
    ): Int {
        val profile = readResourceGroupProfile(reader, folders)
        val template = detectResourceGroupTemplate(profile)
        val baseScore = when (template) {
            ResourceGroupTemplate.BEIJING_HIGH -> 100
            ResourceGroupTemplate.BEIJING_JUNIOR -> 96
            ResourceGroupTemplate.GUANGDONG_JUNIOR -> 92
            ResourceGroupTemplate.GUANGDONG_HIGH -> 90
            ResourceGroupTemplate.GENERIC -> 0
        }
        val exactSizeBonus = when (template) {
            ResourceGroupTemplate.BEIJING_HIGH -> if (folders.size == 13) 20 else 0
            ResourceGroupTemplate.BEIJING_JUNIOR -> if (folders.size == 10) 20 else 0
            ResourceGroupTemplate.GUANGDONG_JUNIOR -> if (folders.size == 7) 20 else 0
            ResourceGroupTemplate.GUANGDONG_HIGH -> if (folders.size == 3) 20 else 0
            ResourceGroupTemplate.GENERIC -> 0
        }
        return baseScore + exactSizeBonus
    }

    internal fun readResourceGroupProfile(
        reader: ETS100FileReader.Reader,
        folders: List<ETS100FileReader.FileItem>
    ): ResourceGroupProfile {
        val structureTypes = mutableSetOf<String>()
        val structureTypeCounts = mutableMapOf<String, Int>()
        var hasBeijingChooseData = false
        var hasBeijingFillData = false
        var hasBeijingDialogueData = false

        for (folder in folders) {
            val contentPath = "${ETS100FileReader.Path.getResourceDir()}/${folder.name}/content.json"
            val json = runCatching {
                val content = reader.readFile(contentPath) ?: return@runCatching null
                JSONObject(content)
            }.getOrNull() ?: continue

            val structureType = json.optString("structure_type", "")
            if (structureType.isNotEmpty()) {
                structureTypes.add(structureType)
                structureTypeCounts[structureType] = (structureTypeCounts[structureType] ?: 0) + 1
            }

            val infoObj = json.optJSONObject("info") ?: continue
            when (structureType) {
                StructureType.COLLECTOR_CHOOSE -> {
                    hasBeijingChooseData = hasBeijingChooseData ||
                        ((infoObj.optJSONArray("xtlist")?.length() ?: 0) > 0)
                }
                StructureType.COLLECTOR_FILL -> {
                    hasBeijingFillData = hasBeijingFillData ||
                        ((infoObj.optJSONArray("std")?.length() ?: 0) > 0)
                }
                StructureType.COLLECTOR_DIALOGUE -> {
                    hasBeijingDialogueData = hasBeijingDialogueData ||
                        ((infoObj.optJSONArray("question")?.length() ?: 0) > 0)
                }
            }
        }

        return ResourceGroupProfile(
            structureTypes = structureTypes,
            folderCount = folders.size,
            structureTypeCounts = structureTypeCounts,
            hasBeijingChooseData = hasBeijingChooseData,
            hasBeijingFillData = hasBeijingFillData,
            hasBeijingDialogueData = hasBeijingDialogueData
        )
    }

    internal fun createPaperSummary(
        reader: ETS100FileReader.Reader,
        folders: List<ETS100FileReader.FileItem>,
        groupIndex: Int,
        totalGroups: Int,
        mode: ActivationMode
    ): Paper {
        val template = detectResourceGroupTemplate(reader, folders)
        val firstFolder = folders.firstOrNull()
        val displayNumber = localPaperDisplayNumber(groupIndex, totalGroups)
        return Paper(
            paperId = buildLocalPaperId(folders, groupIndex),
            title = "${template.titlePrefix} #$displayNumber",
            dataFileName = firstFolder?.name.orEmpty(),
            fileSize = folders.sumOf { it.size },
            sections = listOf(
                Section(
                    caption = "答案解析中",
                    category = "local_loading",
                    typeName = "解析中",
                    questions = emptyList(),
                    originalContent = null
                )
            ),
            downloadTime = firstFolder?.lastModified ?: 0L,
            regionLabel = template.regionLabel,
            paperName = null,
            source = localPaperSource(mode, folders)
        )
    }

    internal fun createFastPaperSummary(
        folders: List<ETS100FileReader.FileItem>,
        groupIndex: Int,
        totalGroups: Int,
        mode: ActivationMode
    ): Paper {
        val firstFolder = folders.firstOrNull()
        val displayNumber = localPaperDisplayNumber(groupIndex, totalGroups)
        return Paper(
            paperId = buildLocalPaperId(folders, groupIndex),
            title = "试卷 #$displayNumber",
            dataFileName = firstFolder?.name.orEmpty(),
            fileSize = folders.sumOf { it.size },
            sections = listOf(
                Section(
                    caption = "答案解析中",
                    category = "local_loading",
                    typeName = "解析中",
                    questions = emptyList(),
                    originalContent = null
                )
            ),
            downloadTime = firstFolder?.lastModified ?: 0L,
            regionLabel = "解析中",
            paperName = null,
            source = localPaperSource(mode, folders)
        )
    }

    internal fun localPaperSource(
        mode: ActivationMode,
        folders: List<ETS100FileReader.FileItem>
    ): PaperSource.Local = PaperSource.Local(
        mode = mode,
        resourceDirectoryNames = folders.map { it.name }.distinct(),
        resourceModifiedTimes = folders.associate { it.name to it.lastModified }
    )

    internal fun buildLocalPaperId(
        folders: List<ETS100FileReader.FileItem>,
        groupIndex: Int
    ): Long {
        val identity = folders
            .map { it.name }
            .sorted()
            .joinToString(separator = "|")
            .ifEmpty { "paper_$groupIndex" }
        return identity.hashCode().toLong()
    }

    private fun parseResourceGroup(
        reader: ETS100FileReader.Reader,
        orderedFolderGroup: List<ETS100FileReader.FileItem>,
        groupIndex: Int,
        totalGroups: Int
    ): List<Paper> {
        val processedContentPaths = mutableSetOf<String>()
        val folderCount = orderedFolderGroup.size
        val folderNames = orderedFolderGroup.joinToString(", ") { "${it.name}(${it.lastModified})" }
        logVerbose("╔═══ Step3&4 第 ${groupIndex + 1} 组路由日志 ═══")
        logVerbose("║ 文件夹数量: $folderCount")
        logVerbose("║ 文件夹列表: $folderNames")

        val template = detectResourceGroupTemplate(reader, orderedFolderGroup)
        Log.i(
            TAG,
            "parseResourceGroup: group=${groupIndex + 1}, folders=$folderCount, template=$template"
        )

        val papers = when (template) {
            ResourceGroupTemplate.GUANGDONG_HIGH -> {
                logVerbose("║ 路由: 广东高中解析器 (${folderCount}个文件夹)")
                ETS100AnswerPaperParser.parseGuangdongHighPapers(reader, orderedFolderGroup, groupIndex, totalGroups, processedContentPaths)
            }
            ResourceGroupTemplate.GUANGDONG_JUNIOR -> {
                logVerbose("║ 路由: 广东初中解析器 (${folderCount}个文件夹)")
                ETS100AnswerPaperParser.parseGuangdongJuniorPapers(reader, orderedFolderGroup, groupIndex, totalGroups, processedContentPaths)
            }
            ResourceGroupTemplate.BEIJING_JUNIOR -> {
                logVerbose("║ 路由: 北京初中解析器 (${folderCount}个文件夹)")
                ETS100AnswerPaperParser.parseBeijingJuniorPapers(reader, orderedFolderGroup, groupIndex, totalGroups, processedContentPaths)
            }
            ResourceGroupTemplate.BEIJING_HIGH -> {
                logVerbose("║ 路由: 北京高中解析器 (${folderCount}个文件夹)")
                ETS100AnswerPaperParser.parseBeijingHighPapers(reader, orderedFolderGroup, groupIndex, totalGroups, processedContentPaths)
            }
            ResourceGroupTemplate.GENERIC -> {
                logVerbose("║ 路由: 通用解析器 (${folderCount}个文件夹，不匹配预设)")
                ETS100AnswerPaperParser.parseGenericPapers(reader, orderedFolderGroup, groupIndex, totalGroups, processedContentPaths)
            }
        }
        logVerbose("║ 解析得到 ${papers.size} 份试卷")
        logVerbose("╚═══ Step3&4 路由日志结束 ═══")
        return papers
    }

    /**
     * 解析单个试卷的JSON数据
     * @param processedPaths 用于去重，跳过Step4中已处理的content.json路径
     */
    fun parseContentJson(
        json: JSONObject,
        startIndex: Int,
        category: String,
        typeName: String,
        sectionCaption: String,
        infoJson: String = "",
        content2Json: String = "",
    ): Pair<List<Question>, String?> {
        val originalContent = nativeOriginalContent(json)
        val native = parseContentNative(json, infoJson, content2Json)
        val questions = mapNativeQuestions(
            native = native,
            json = json,
            startIndex = startIndex,
            category = category,
            typeName = typeName,
            sectionCaption = sectionCaption,
            originalContent = originalContent,
        )
        return Pair(questions, originalContent)
    }

    /** 从 content.json 抽"朗读原文"（info.value，去标签）。 */
    fun nativeOriginalContent(json: JSONObject): String? = json.optJSONObject("info")
        ?.optString("value", "")
        ?.takeIf { it.isNotBlank() }
        ?.replace(Regex("<[^>]*>"), "")
        ?.trim()

    /**
     * 只调用解析器（不映射为 UI 模型），返回**解析器原始题目数组**。
     *
     * 用途：云端整卷合并要把各包的原始题目交给 `op: parse_homework_set`，
     * 由它按官方 `res.json` 排大题、定题号、盖逐题满分；映射到 UI 模型留到合并之后。
     */
    fun parseContentNative(
        json: JSONObject,
        infoJson: String = "",
        content2Json: String = "",
    ): JSONArray {
        // 走解析器的跨平台固定契约（与后端/Fe_web 同一条 op 通道）：
        //   ① 响应里多了 meta（layer / family / shape / spec_known），便于诊断与上报；
        //   ② 解析器新增能力只加 op，客户端不需要再改 JNI 声明；
        //   ③ 旧接口 parseTaskAuthorized 仍保留，但业务层统一走契约。
        var contract = ResolverContract.parseTask(
            content = json.toString(),
            info = infoJson,
            content2 = content2Json,
        )
        if (!contract.ok && (contract.errorCode == "auth_disabled" || contract.errorCode == "auth_not_configured")) {
            // 离线版容错：解析器可能尚未完成本地授权初始化。
            // 补丁版 so 的 authorizeFeature 不会真正联网；失败也不影响后续错误提示。
            val authRaw = runCatching { ResolverNative.authorizeFeature() }.getOrNull()
            Log.i(TAG, "解析器授权重试: " + (authRaw?.take(240) ?: "authorizeFeature 调用异常"))
            contract = ResolverContract.parseTask(
                content = json.toString(),
                info = infoJson,
                content2 = content2Json,
            )
        }
        if (!contract.ok) {
            // 解析为空/失败时把结构指纹记下来：官方出了新题型时这是唯一的发现渠道
            ResolverContract.reportUnparsed(
                content = json.toString(),
                info = infoJson,
                content2 = content2Json,
                tag = "ETS100AnswerReader.parseContentNative",
            )
            val baseMessage = contract.errorMessage?.takeIf { it.isNotBlank() }
                ?: "答案解析功能当前不可用"
            val detail = buildString {
                append(baseMessage)
                if (!contract.errorCode.isNullOrBlank()) {
                    append("（code=").append(contract.errorCode).append("）")
                }
                if (!contract.errorHint.isNullOrBlank()) {
                    append("；提示：").append(contract.errorHint)
                }
                if (contract.errorCode == "auth_disabled" || contract.errorCode == "auth_not_configured") {
                    append("；解析器本地授权状态：").append(ResolverAuthorizer.localAuthStatus())
                    append("（离线版应自动初始化，无需登录 LastStudio 账号）")
                }
            }
            throw ResolverAuthorizationDeniedException(detail)
        }
        return contract.resultArray ?: run {
            ResolverContract.reportUnparsed(
                content = json.toString(),
                info = infoJson,
                content2 = content2Json,
                tag = "ETS100AnswerReader.parseContentNative(no-result)",
            )
            throw ResolverAuthorizationDeniedException("解析器未返回题目数据")
        }
    }

    /**
     * 把解析器原始题目映射为 UI 模型（原 `parseContentJson` 的映射逻辑，行为不变）。
     *
     * @param scoreOf 可选：逐题满分（来自官方 res.json 的 `question_score`）
     */
    fun mapNativeQuestions(
        native: JSONArray,
        json: JSONObject,
        startIndex: Int,
        category: String,
        typeName: String,
        sectionCaption: String,
        originalContent: String?,
        scoreOf: ((Int) -> Double?)? = null,
    ): List<Question> {
        val questions = (0 until native.length()).mapNotNull { index ->
            val item = native.optJSONObject(index) ?: return@mapNotNull null
            val collectorType = item.optString("type", json.optString("structure_type", ""))
            val rawNumber = item.opt("no")
            val nativeNumber = (rawNumber as? Number)?.toInt()
                ?: (rawNumber as? String)?.toIntOrNull()
            val displayOrder = nativeNumber ?: (startIndex + index + 1)
            val nativeQuestion = item.optString("question", "").trim()
            val nativeAnswers = item.optJSONArray("answers").toStringList()
            val options = item.optJSONArray("options").toStringList()
            val standardAnswer = nativeAnswers.firstOrNull().orEmpty()

            val isChooseLike = collectorType.contains("choose") || collectorType.contains("choice")
            val questionText = when {
                collectorType == StructureType.COLLECTOR_READ -> "模仿朗读原文"
                collectorType.contains("fill") && nativeQuestion.isBlank() -> "第${displayOrder}题"
                else -> nativeQuestion
            }
            val answers = if (isChooseLike) {
                val lines = buildList {
                    addAll(options)
                    if (standardAnswer.isNotBlank()) add("正确答案: $standardAnswer")
                }
                if (lines.isEmpty()) emptyList() else listOf(lines.joinToString("\n"))
            } else {
                nativeAnswers
            }
            val questionOriginalText = when {
                collectorType.contains("fill") -> null
                collectorType.contains("role") && category == "simple_expression_ufj" -> null
                else -> originalContent
            }
            val answerContent = if (isChooseLike && options.isNotEmpty()) {
                AnswerContent.Choice(
                    listOf(
                        ChoiceItem(
                            question = questionText,
                            options = options,
                            correctAnswer = standardAnswer.firstOrNull()
                                ?.uppercaseChar()
                                ?.let { it.code - 'A'.code }
                                ?.takeIf { it in options.indices }
                                ?: -1,
                            standardAnswer = standardAnswer,
                        )
                    )
                )
            } else {
                AnswerContent.Reading(questionOriginalText.orEmpty())
            }

            Question(
                order = startIndex + index + 1,
                sectionOrder = index + 1,
                sectionCaption = sectionCaption,
                typeName = typeName,
                questionText = questionText,
                answers = answers,
                originalText = questionOriginalText,
                category = category,
                displayOrder = displayOrder,
                content = answerContent,
                score = scoreOf?.invoke(index),
            )
        }
        return sortQuestionsByOfficialOrder(questions)
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .map { optString(it, "").trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 读取单个试卷
     */
    fun readSinglePaper(context: Context, mode: ActivationMode, dataFileName: String): Paper? {
        if (!ETS100FileReader.isModeAvailable(mode, context)) {
            return null
        }

        kotlinx.coroutines.runBlocking { ResolverAuthorizer.requireAuthorized(context) }

        val reader = try {
            ETS100FileReader.getReader(mode, context)
        } catch (e: Exception) {
            return null
        }

        val file = ETS100FileReader.FileItem(
            name = dataFileName,
            path = "${ETS100FileReader.Path.getDataDir()}/$dataFileName",
            isDirectory = false,
            size = reader.getFileSize("${ETS100FileReader.Path.getDataDir()}/$dataFileName"),
            lastModified = 0L
        )

        return ETS100AnswerPaperParser.parsePaper(reader, file, mutableSetOf())
    }

    /**
     * 检查是否有可用的试卷
     */
    fun hasAvailablePapers(context: Context, mode: ActivationMode): Boolean {
        if (!ETS100FileReader.isModeAvailable(mode, context)) {
            return false
        }

        val reader = try {
            ETS100FileReader.getReader(mode, context)
        } catch (e: Exception) {
            return false
        }

        val dataDirPath = ETS100FileReader.Path.getDataDir()
        val files = reader.listFiles(dataDirPath)
            .filter { !it.isDirectory && it.size > ETS100FileReader.Path.MIN_FILE_SIZE }

        return files.isNotEmpty()
    }

    /**
     * 获取试卷数量
     */
    fun getPaperCount(context: Context, mode: ActivationMode): Int {
        if (!ETS100FileReader.isModeAvailable(mode, context)) {
            return 0
        }

        val reader = try {
            ETS100FileReader.getReader(mode, context)
        } catch (e: Exception) {
            return 0
        }

        val dataDirPath = ETS100FileReader.Path.getDataDir()
        return reader.listFiles(dataDirPath)
            .filter { !it.isDirectory && it.size > ETS100FileReader.Path.MIN_FILE_SIZE }
            .size
    }

    /**
     * 将Paper对象转换为Question列表
     * 用于兼容旧的Question数据结构
     */
    fun Paper.toLegacyQuestions(): List<Question> {
        val result = mutableListOf<Question>()
        for (section in sections) {
            result.addAll(section.questions)
        }
        return result
    }
}
