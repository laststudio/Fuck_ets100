package com.shuaiqiu.fuckets100


internal val categoryMap = mapOf(
        "read_chapter" to "模仿朗读",
        "simple_expression_ufi" to "听说信息",
        "simple_expression_ufk" to "问答信息",
        "topic" to "信息转述",
        "simple_expression_ufj" to "询问信息"
    )


/**
 * [ETS100AnswerReader] 内部解析辅助模型与文本清理工具。
 */
internal data class ResourceScanCache(
    val mode: ActivationMode,
    val createdAtMs: Long,
    val folders: List<ETS100FileReader.FileItem>
)

internal enum class ResourceGroupTemplate(
    val titlePrefix: String,
    val regionLabel: String
) {
    GUANGDONG_HIGH("广东高中", "广东高中"),
    GUANGDONG_JUNIOR("广东初中", "广东初中"),
    BEIJING_JUNIOR("北京初中", "北京初中"),
    BEIJING_HIGH("北京高中", "北京高中"),
    GENERIC("练习", "未知")
}

internal data class ResourceGroupProfile(
    val structureTypes: Set<String>,
    val folderCount: Int,
    val structureTypeCounts: Map<String, Int>,
    val hasBeijingChooseData: Boolean,
    val hasBeijingFillData: Boolean,
    val hasBeijingDialogueData: Boolean
) {
    val hasBeijingData: Boolean
        get() = hasBeijingChooseData || hasBeijingFillData || hasBeijingDialogueData

    val roleCount: Int
        get() = structureTypeCounts[ETS100AnswerReader.StructureType.COLLECTOR_ROLE] ?: 0

    val pictureCount: Int
        get() = structureTypeCounts[ETS100AnswerReader.StructureType.COLLECTOR_PICTURE] ?: 0

    val hasGuangdongHighData: Boolean
        get() = ETS100AnswerReader.StructureType.COLLECTOR_3Q5A in structureTypes

    val hasGuangdongJuniorData: Boolean
        get() = !hasBeijingFillData &&
            !hasBeijingDialogueData &&
            roleCount > 0 &&
            pictureCount > 0 &&
            (folderCount == 7 || !hasBeijingChooseData)
}

internal data class GroupParseResult(
    val groupIndex: Int,
    val papers: List<ETS100AnswerReader.Paper>
)

internal enum class LocalPaperKind(
    val titlePrefix: String,
    val regionLabel: String,
    val sectionOrder: List<String>
) {
    GUANGDONG_HIGH("广东高中", "广东高中", listOf("模仿朗读", "3问5答")),
    GUANGDONG_JUNIOR("广东初中", "广东初中", listOf("模仿朗读", "听选信息", "回答问题", "信息转述", "询问信息")),
    BEIJING_JUNIOR("北京初中", "北京初中", listOf("听后选择", "听后回答", "听后转述", "短文朗读")),
    BEIJING_HIGH("北京高中", "北京高中", listOf("听后选择", "听后记录", "听后转述", "回答问题", "短文朗读")),
    GENERIC("通用练习", "通用", emptyList())
}

internal data class LocalSectionInfo(
    val title: String,
    val category: String
)

internal data class LocalParsedSection(
    val info: LocalSectionInfo,
    val structureType: String,
    val sourceOrder: Int,
    val questions: List<ETS100AnswerReader.Question>,
    val originalContent: String?,
    val firstIndex: Int,
    val templateQuestionNumbers: List<Int> = emptyList()
)

internal data class LocalResourceTemplateSection(
    val structureType: String,
    val title: String,
    val templateOrder: Int,
    val questionNumbers: List<Int>,
    /** 本节的来源 res.json：本地模式交给 `op: apply_res_template` 补题号/满分。 */
    val resJson: String? = null
)

internal data class LocalResourceTemplate(
    val folder: ETS100FileReader.FileItem,
    val sections: List<LocalResourceTemplateSection>,
    val profile: ResourceGroupProfile,
    /** 官方卷面总分（res.json 的 `set_score`，经 `op: res_template` 规范化）。 */
    val totalScore: Double? = null
)

/** 本地模板选择结果：题段 + 官方卷面总分。 */
internal data class LocalResourceTemplateSelection(
    val sections: List<LocalResourceTemplateSection>,
    val totalScore: Double? = null
)

internal fun cleanQuestionText(text: String): String {
    return cleanDisplayText(text, splitPipes = false)
}

internal fun cleanOriginalText(text: String): String {
    return cleanDisplayText(text, splitPipes = false)
}

internal fun cleanDisplayText(text: String, splitPipes: Boolean): String {
    if (text.isEmpty()) return ""
    val pipeNormalized = if (splitPipes) text.replace("|", "\n") else text
    return pipeNormalized
        .replace(Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<br\\s*/?>|</br>|</p>|<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")            // 移除其他 HTML 标签
        .replace("\u200B", "")                    // 移除零宽空格
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

internal fun parsePositiveInt(value: String): Int? =
    value.trim().toIntOrNull()?.takeIf { it > 0 }

internal val questionOfficialOrderComparator =
    compareBy<ETS100AnswerReader.Question> { it.displayOrder ?: it.sectionOrder }.thenBy { it.sectionOrder }

internal fun sortQuestionsByOfficialOrder(questions: List<ETS100AnswerReader.Question>): List<ETS100AnswerReader.Question> {
    return questions.sortedWith(questionOfficialOrderComparator)
}

/**
 * 检查文本是否包含中文
 * 喵~ 用于广东初中区分听选信息和提问喵！
 */
internal fun containsChinese(text: String): Boolean {
    return text.contains(Regex("[\\u4e00-\\u9fff]"))
}

internal fun getTypeName(category: String): String {
    return categoryMap[category] ?: category
}

/**
 * ========== 文档流程实现 ==========
 * Step 1: 扫描 resource/ 目录（优先）
 * Step 2: 按时间分组
 * Step 3: 根据文件夹数量路由
 * Step 4: 遍历每组的 content.json
 * Step 5: 扫描 data/ 目录获取常规习题
 * Step 6: 合并输出
 */

/**
 * 读取所有试卷 - 按文档流程实现
 */
