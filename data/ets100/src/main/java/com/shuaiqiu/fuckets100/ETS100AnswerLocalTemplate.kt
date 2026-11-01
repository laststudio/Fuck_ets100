package com.shuaiqiu.fuckets100

import android.util.Log
import org.json.JSONObject


/**
 * 本地资源试卷的模板读取与分组归并实现。
 *
 * 从 [ETS100AnswerReader] 中抽出，保持既有行为不变。
 */
internal object ETS100AnswerLocalTemplate {
    private const val TAG = "ETS100AnswerReader"

    internal fun parseLocalResourcePapers(
        reader: ETS100FileReader.Reader,
        folders: List<ETS100FileReader.FileItem>,
        groupIndex: Int,
        totalGroups: Int,
        processedPaths: MutableSet<String>,
        kind: LocalPaperKind
    ): List<ETS100AnswerReader.Paper> {
        Log.d(TAG, "parseLocalResourcePapers: 第 $groupIndex 组，${folders.size} 个文件夹，kind=$kind")
        val templateSelection = readLocalResourceTemplateSections(reader, folders, kind)
        val templateSections = templateSelection.sections
        val baseStid = readLocalBaseStid(reader, folders)
        val templateCursors = mutableMapOf<String, Int>()
        val parsedSections = mutableListOf<LocalParsedSection>()
        var questionIndex = 0

        for ((folderOrder, folder) in folders.withIndex()) {
            val contentPath = "${ETS100FileReader.Path.getResourceDir()}/${folder.name}/content.json"
            processedPaths.add(contentPath)

            val content = reader.readFile(contentPath) ?: continue
            val json = runCatching { JSONObject(content) }.getOrNull() ?: continue
            val resourceDir = "${ETS100FileReader.Path.getResourceDir()}/${folder.name}"
            val infoJson = reader.readFile("$resourceDir/info.json").orEmpty()
            val content2Json = reader.readFile("$resourceDir/content2.json").orEmpty()
            val structureType = json.optString("structure_type", "")
            val templateSection = nextLocalTemplateSection(
                templateSections = templateSections,
                cursors = templateCursors,
                structureType = structureType,
                contentOrder = extractContentTemplateOrder(json, baseStid)
            )
            val sectionInfo = templateSection?.let { localSectionInfoFromTemplate(it, inferLocalSectionInfo(kind, json)) }
                ?: inferLocalSectionInfo(kind, json)
            val sourceOrder = templateSection?.let { templateSections.indexOf(it) } ?: folderOrder
            val (questions, originalContent) = ETS100AnswerReader.parseContentJson(
                json = json,
                startIndex = questionIndex,
                category = sectionInfo.category,
                typeName = sectionInfo.title,
                sectionCaption = sectionInfo.title,
                infoJson = infoJson,
                content2Json = content2Json,
                resJson = templateSection?.resJson,
            )

            if (questions.isNotEmpty()) {
                parsedSections.add(
                    LocalParsedSection(
                        info = sectionInfo,
                        structureType = structureType,
                        sourceOrder = sourceOrder,
                        questions = questions,
                        originalContent = originalContent,
                        firstIndex = folderOrder,
                        templateQuestionNumbers = templateSection?.questionNumbers.orEmpty()
                    )
                )
                questionIndex += questions.size
            } else {
                Log.d(TAG, "parseLocalResourcePapers: 跳过空题型 ${folder.name}, order=$folderOrder")
            }
        }

        if (parsedSections.isEmpty()) return emptyList()

        val orderedSections = mergeAndOrderLocalSections(parsedSections, kind.sectionOrder)
        val paperId = ETS100AnswerReader.buildLocalPaperId(folders, groupIndex)
        val displayNumber = ETS100AnswerReader.localPaperDisplayNumber(groupIndex, totalGroups)
        return listOf(
            ETS100AnswerReader.Paper(
                paperId = paperId,
                title = "${kind.titlePrefix} #$displayNumber",
                dataFileName = folders.first().name,
                fileSize = folders.sumOf { it.size },
                sections = orderedSections,
                downloadTime = folders.first().lastModified,
                regionLabel = kind.regionLabel,
                paperName = null,
                totalScore = templateSelection.totalScore,
                source = ETS100AnswerReader.localPaperSource(reader.getMode(), folders)
            )
        )
    }

    internal fun readLocalResourceTemplateSections(
        reader: ETS100FileReader.Reader,
        folders: List<ETS100FileReader.FileItem>,
        kind: LocalPaperKind
    ): LocalResourceTemplateSelection {
        val resourceDir = ETS100FileReader.Path.getResourceDir()
        val groupNames = folders.mapTo(mutableSetOf()) { it.name }
        val groupTemplates = folders
            .mapNotNull { readLocalResourceTemplate(reader, resourceDir, it) }
            .filter { isTemplateCompatibleWithKind(it.profile, kind) }

        val selectedTemplates = if (groupTemplates.isNotEmpty()) {
            groupTemplates
        } else {
            val groupTimeRange = folders.map { it.lastModified }.filter { it > 0L }
            val candidateTemplates = reader.listFiles(resourceDir)
                .asSequence()
                .filter { it.isDirectory && it.name !in groupNames }
                .map { resolveTemplateFolderModifiedTime(reader, resourceDir, it) }
                .filter { isTemplateNearGroup(it, groupTimeRange) }
                .mapNotNull { readLocalResourceTemplate(reader, resourceDir, it) }
                .filter { isTemplateCompatibleWithKind(it.profile, kind) }
                .toList()

            listOfNotNull(candidateTemplates.minByOrNull { templateDistanceFromGroup(it.folder, groupTimeRange) })
        }

        val templateSections = selectedTemplates.flatMap { it.sections }
        val totalScore = selectedTemplates.mapNotNull { it.totalScore }.firstOrNull()

        if (templateSections.isNotEmpty()) {
            Log.d(TAG, "readLocalResourceTemplateSections: 读取到 ${templateSections.size} 个模板题段")
        }
        return LocalResourceTemplateSelection(templateSections, totalScore)
    }

    internal fun resolveTemplateFolderModifiedTime(
        reader: ETS100FileReader.Reader,
        resourceDir: String,
        folder: ETS100FileReader.FileItem
    ): ETS100FileReader.FileItem {
        if (folder.lastModified > 0L) return folder
        val modifiedTime = reader.getFileModifiedTime("$resourceDir/${folder.name}")
        return folder.copy(lastModified = modifiedTime)
    }

    internal fun readLocalResourceTemplate(
        reader: ETS100FileReader.Reader,
        resourceDir: String,
        folder: ETS100FileReader.FileItem
    ): LocalResourceTemplate? {
        val resContent = reader.readFile("$resourceDir/${folder.name}/res.json") ?: return null
        val json = runCatching { JSONObject(resContent) }.getOrNull() ?: return null
        val examTypeList = json.optJSONArray("exam_type_list") ?: return null
        val templateSections = mutableListOf<LocalResourceTemplateSection>()

        for (typeIndex in 0 until examTypeList.length()) {
            val examType = examTypeList.optJSONObject(typeIndex) ?: continue
            val structureType = examType.optString("exam_type_collect", "")
            val title = examType.optString("exam_type_name", structureType)
            val examList = examType.optJSONArray("exam_list")

            if (examList != null && examList.length() > 0) {
                for (examIndex in 0 until examList.length()) {
                    val exam = examList.optJSONObject(examIndex) ?: continue
                    templateSections.add(
                        LocalResourceTemplateSection(
                            structureType = structureType,
                            title = title,
                            templateOrder = extractTemplateOrder(exam, templateSections.size + 1),
                            questionNumbers = extractTemplateQuestionNumbers(exam),
                            resJson = resContent
                        )
                    )
                }
            } else if (examType.has("exam_id")) {
                templateSections.add(
                    LocalResourceTemplateSection(
                        structureType = structureType,
                        title = title,
                        templateOrder = extractTemplateOrder(examType, templateSections.size + 1),
                        questionNumbers = extractTemplateQuestionNumbers(examType),
                        resJson = resContent
                    )
                )
            }
        }

        if (templateSections.isEmpty()) return null
        return LocalResourceTemplate(
            folder = folder,
            sections = templateSections,
            profile = buildTemplateProfile(templateSections),
            totalScore = readTemplateTotalScore(resContent)
        )
    }

    /** 用解析器 `op: res_template` 规范化 res.json，取官方卷面总分（失败返回 null）。 */
    private fun readTemplateTotalScore(resContent: String): Double? {
        val normalized = ResolverContract.resTemplate(resContent)
        if (!normalized.ok) return null
        return normalized.result
            ?.optDouble("total_score", Double.NaN)
            ?.takeIf { !it.isNaN() && it > 0.0 }
    }

    internal fun buildTemplateProfile(
        templateSections: List<LocalResourceTemplateSection>
    ): ResourceGroupProfile {
        val structureTypes = templateSections
            .mapTo(mutableSetOf()) { it.structureType }
        val structureTypeCounts = templateSections
            .groupingBy { it.structureType }
            .eachCount()
        val hasChoose = ETS100AnswerReader.StructureType.COLLECTOR_CHOOSE in structureTypes
        val hasFill = ETS100AnswerReader.StructureType.COLLECTOR_FILL in structureTypes
        val hasDialogue = ETS100AnswerReader.StructureType.COLLECTOR_DIALOGUE in structureTypes
        return ResourceGroupProfile(
            structureTypes = structureTypes,
            folderCount = templateSections.size,
            structureTypeCounts = structureTypeCounts,
            hasBeijingChooseData = hasChoose,
            hasBeijingFillData = hasFill,
            hasBeijingDialogueData = hasDialogue
        )
    }

    internal fun isTemplateCompatibleWithKind(
        profile: ResourceGroupProfile,
        kind: LocalPaperKind
    ): Boolean {
        return when (kind) {
            LocalPaperKind.GUANGDONG_HIGH -> profile.hasGuangdongHighData
            LocalPaperKind.GUANGDONG_JUNIOR -> profile.hasGuangdongJuniorData
            LocalPaperKind.BEIJING_JUNIOR -> profile.hasBeijingChooseData
            LocalPaperKind.BEIJING_HIGH -> profile.hasBeijingFillData || profile.hasBeijingDialogueData
            LocalPaperKind.GENERIC -> true
        }
    }

    internal fun isTemplateNearGroup(
        folder: ETS100FileReader.FileItem,
        groupTimeRange: List<Long>
    ): Boolean {
        if (groupTimeRange.isEmpty() || folder.lastModified <= 0L) return false
        return templateDistanceFromGroup(folder, groupTimeRange) <= 2000L
    }

    internal fun templateDistanceFromGroup(
        folder: ETS100FileReader.FileItem,
        groupTimeRange: List<Long>
    ): Long {
        if (groupTimeRange.isEmpty() || folder.lastModified <= 0L) return Long.MAX_VALUE
        return groupTimeRange.minOf { kotlin.math.abs(folder.lastModified - it) }
    }

    internal fun extractTemplateOrder(exam: JSONObject, fallback: Int): Int {
        val examId = exam.optString("exam_id", "")
        Regex("st(\\d+)").find(examId)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return fallback
    }

    internal fun extractTemplateQuestionNumbers(exam: JSONObject): List<Int> {
        val numbers = mutableListOf<Int>()
        val examInfo = exam.optJSONArray("exam_info")
        if (examInfo != null) {
            for (index in 0 until examInfo.length()) {
                val item = examInfo.optJSONObject(index) ?: continue
                parsePositiveInt(item.optString("value", "").trim().trimEnd('.', '。'))?.let { numbers.add(it) }
            }
        }
        if (numbers.isNotEmpty()) return numbers

        val count = parsePositiveInt(exam.optString("exam_count", "")) ?: return emptyList()
        val order = parsePositiveInt(exam.optString("exam_order", "")) ?: return emptyList()
        val start = (order - 1) * count + 1
        return (start until start + count).toList()
    }

    internal fun readLocalBaseStid(
        reader: ETS100FileReader.Reader,
        folders: List<ETS100FileReader.FileItem>
    ): Int? {
        val resourceDir = ETS100FileReader.Path.getResourceDir()
        return folders.mapNotNull { folder ->
            val content = reader.readFile("$resourceDir/${folder.name}/content.json") ?: return@mapNotNull null
            val json = runCatching { JSONObject(content) }.getOrNull() ?: return@mapNotNull null
            parsePositiveInt(json.optJSONObject("info")?.optString("stid", "").orEmpty())
        }.minOrNull()
    }

    internal fun extractContentTemplateOrder(json: JSONObject, baseStid: Int?): Int? {
        val stid = parsePositiveInt(json.optJSONObject("info")?.optString("stid", "").orEmpty()) ?: return null
        return baseStid?.let { stid - it + 1 }?.takeIf { it > 0 }
    }

    internal fun nextLocalTemplateSection(
        templateSections: List<LocalResourceTemplateSection>,
        cursors: MutableMap<String, Int>,
        structureType: String,
        contentOrder: Int?
    ): LocalResourceTemplateSection? {
        if (structureType.isEmpty()) return null
        val matches = templateSections.filter { it.structureType == structureType }
        if (matches.isEmpty()) return null

        contentOrder?.let { order ->
            matches.firstOrNull { it.templateOrder == order }?.let { return it }
        }

        val cursor = cursors[structureType] ?: 0
        cursors[structureType] = cursor + 1
        return matches.getOrNull(cursor)
    }

    internal fun mergeAndOrderLocalSections(
        parsedSections: List<LocalParsedSection>,
        sectionOrder: List<String>
    ): List<ETS100AnswerReader.Section> {
        val mergedSections = parsedSections
            .groupBy { it.info.title to it.info.category }
            .map { (key, group) ->
                val orderedGroup = group.sortedBy { it.firstIndex }
                val first = orderedGroup.first()
                val questions = sortLocalQuestionsAcrossPackages(orderedGroup)
                ETS100AnswerReader.Section(
                    caption = key.first,
                    category = key.second,
                    typeName = key.first,
                    questions = questions,
                    originalContent = first.originalContent
                )
            }
        return ETS100AnswerPaperParser.orderLocalSections(mergedSections, sectionOrder)
    }

    internal fun sortLocalQuestionsAcrossPackages(
        group: List<LocalParsedSection>
    ): List<ETS100AnswerReader.Question> {
        val questionsByPackage = group.map { section ->
            val sortedQuestions = sortLocalQuestions(section.questions)
            if (section.templateQuestionNumbers.isNotEmpty()) {
                sortedQuestions.mapIndexed { index, question ->
                    val number = section.templateQuestionNumbers.getOrNull(index)
                    if (number != null) {
                        question.copy(sectionOrder = number, displayOrder = number)
                    } else {
                        question
                    }
                }
            } else {
                sortedQuestions
            }
        }
        val allQuestions = questionsByPackage.flatten()
        val displayOrders = allQuestions.mapNotNull { it.displayOrder }
        val hasDuplicatedDisplayOrder = displayOrders.size != displayOrders.distinct().size

        return if (hasDuplicatedDisplayOrder) {
            questionsByPackage.flatten().mapIndexed { index, question ->
                question.copy(
                    sectionOrder = index + 1,
                    displayOrder = index + 1
                )
            }
        } else {
            sortLocalQuestions(allQuestions)
        }
    }

    internal fun sortLocalQuestions(questions: List<ETS100AnswerReader.Question>): List<ETS100AnswerReader.Question> {
        val hasAnyDisplayOrder = questions.any { it.displayOrder != null }
        return if (hasAnyDisplayOrder) {
            questions.sortedWith(questionOfficialOrderComparator)
        } else {
            questions.sortedBy { it.order }
        }
    }

    internal fun localSectionInfoFromTemplate(
        templateSection: LocalResourceTemplateSection,
        fallback: LocalSectionInfo
    ): LocalSectionInfo {
        val title = templateSection.title.ifBlank { fallback.title }
        val category = when (title) {
            "模仿朗读", "短文朗读", "朗读" -> "read_chapter"
            "听选信息", "听后选择", "听说信息", "3问5答", "听后记录", "填空" -> "simple_expression_ufi"
            "回答问题", "听后回答", "问答信息" -> "simple_expression_ufk"
            "信息转述", "听后转述", "转述" -> "topic"
            "询问信息", "提问" -> "simple_expression_ufj"
            else -> fallback.category
        }
        return LocalSectionInfo(title, category)
    }

    internal fun inferLocalSectionInfo(kind: LocalPaperKind, json: JSONObject): LocalSectionInfo {
        val structureType = json.optString("structure_type", "")
        return when (kind) {
            LocalPaperKind.GUANGDONG_HIGH -> when (structureType) {
                ETS100AnswerReader.StructureType.COLLECTOR_READ -> LocalSectionInfo("模仿朗读", "read_chapter")
                ETS100AnswerReader.StructureType.COLLECTOR_3Q5A -> LocalSectionInfo("3问5答", "simple_expression_ufi")
                else -> inferGenericSectionInfo(json)
            }
            LocalPaperKind.GUANGDONG_JUNIOR -> when (structureType) {
                ETS100AnswerReader.StructureType.COLLECTOR_READ -> LocalSectionInfo("模仿朗读", "read_chapter")
                ETS100AnswerReader.StructureType.COLLECTOR_PICTURE -> LocalSectionInfo("信息转述", "topic")
                ETS100AnswerReader.StructureType.COLLECTOR_ROLE -> inferGuangdongJuniorRoleSectionInfo(json)
                else -> inferGenericSectionInfo(json)
            }
            LocalPaperKind.BEIJING_JUNIOR -> when (structureType) {
                ETS100AnswerReader.StructureType.COLLECTOR_CHOOSE -> LocalSectionInfo("听后选择", "simple_expression_ufi")
                ETS100AnswerReader.StructureType.COLLECTOR_ROLE -> LocalSectionInfo("听后回答", "simple_expression_ufk")
                ETS100AnswerReader.StructureType.COLLECTOR_PICTURE -> LocalSectionInfo("听后转述", "topic")
                ETS100AnswerReader.StructureType.COLLECTOR_READ -> LocalSectionInfo("短文朗读", "read_chapter")
                else -> inferGenericSectionInfo(json)
            }
            LocalPaperKind.BEIJING_HIGH -> when (structureType) {
                ETS100AnswerReader.StructureType.COLLECTOR_CHOOSE -> LocalSectionInfo("听后选择", "simple_expression_ufi")
                ETS100AnswerReader.StructureType.COLLECTOR_FILL -> LocalSectionInfo("听后记录", "simple_expression_ufi")
                ETS100AnswerReader.StructureType.COLLECTOR_PICTURE -> LocalSectionInfo("听后转述", "topic")
                ETS100AnswerReader.StructureType.COLLECTOR_DIALOGUE -> LocalSectionInfo("回答问题", "simple_expression_ufk")
                ETS100AnswerReader.StructureType.COLLECTOR_READ -> LocalSectionInfo("短文朗读", "read_chapter")
                else -> inferGenericSectionInfo(json)
            }
            LocalPaperKind.GENERIC -> inferGenericSectionInfo(json)
        }
    }

    internal fun inferGuangdongJuniorRoleSectionInfo(json: JSONObject): LocalSectionInfo {
        val firstQuestion = json.optJSONObject("info")
            ?.optJSONArray("question")
            ?.optJSONObject(0)
        val ask = firstQuestion?.optString("ask", "").orEmpty()
        val askaudio = firstQuestion?.optString("askaudio", "").orEmpty()
        val hasBr = ask.contains("<br>", ignoreCase = true)
        val hasChinese = containsChinese(ask)
        return when {
            hasBr && askaudio.isNotEmpty() -> LocalSectionInfo("听选信息", "simple_expression_ufi")
            askaudio.isNotEmpty() -> LocalSectionInfo("回答问题", "simple_expression_ufk")
            hasChinese -> LocalSectionInfo("提问", "simple_expression_ufj")
            else -> LocalSectionInfo("听选信息", "simple_expression_ufi")
        }
    }

    internal fun inferGenericSectionInfo(json: JSONObject): LocalSectionInfo {
        return when (json.optString("structure_type", "")) {
            ETS100AnswerReader.StructureType.COLLECTOR_READ -> LocalSectionInfo("朗读", "read_chapter")
            ETS100AnswerReader.StructureType.COLLECTOR_ROLE -> LocalSectionInfo("问答", "simple_expression_ufk")
            ETS100AnswerReader.StructureType.COLLECTOR_3Q5A -> LocalSectionInfo("3问5答", "simple_expression_ufi")
            ETS100AnswerReader.StructureType.COLLECTOR_CHOOSE -> LocalSectionInfo("选择题", "simple_expression_ufi")
            ETS100AnswerReader.StructureType.COLLECTOR_FILL -> LocalSectionInfo("填空", "simple_expression_ufi")
            ETS100AnswerReader.StructureType.COLLECTOR_DIALOGUE -> LocalSectionInfo("回答问题", "simple_expression_ufk")
            ETS100AnswerReader.StructureType.COLLECTOR_PICTURE -> LocalSectionInfo("转述", "topic")
            ETS100AnswerReader.StructureType.COLLECTOR_REPEAT_ESSAY -> LocalSectionInfo("短文复述", "read_chapter")
            ETS100AnswerReader.StructureType.WORD_CHOICE -> LocalSectionInfo("词汇选择", "simple_expression_ufi")
            ETS100AnswerReader.StructureType.WORD_WRITE -> LocalSectionInfo("单词拼写", "simple_expression_ufi")
            ETS100AnswerReader.StructureType.WORD_READ -> LocalSectionInfo("单词跟读", "read_chapter")
            ETS100AnswerReader.StructureType.WORD_DICTATION -> LocalSectionInfo("词汇听写", "simple_expression_ufi")
            ETS100AnswerReader.StructureType.WORD_TRANSLATE_EN_CN -> LocalSectionInfo("英译中", "simple_expression_ufi")
            ETS100AnswerReader.StructureType.WORD_TRANSLATE_CN_EN -> LocalSectionInfo("中译英", "simple_expression_ufi")
            else -> LocalSectionInfo("未知题型", "unknown")
        }
    }
}
