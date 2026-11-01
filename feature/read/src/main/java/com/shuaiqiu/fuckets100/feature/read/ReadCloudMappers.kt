package com.shuaiqiu.fuckets100.feature.read

import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100HomeworkClassifier

// ========== 云端模式辅助函数喵~ ==========

/**
 * 创建云端作业的占位 Paper 对象
 * 用于在列表中显示"未加载"状态喵~
 */
internal fun createCloudHomeworkPlaceholder(homework: ETS100ApiClient.HomeworkInfo): ETS100AnswerReader.Paper {
    val identity = cloudHomeworkIdentity(homework)
    val isPk = ETS100HomeworkClassifier.classify(homework)?.isVocabPk
        ?: (homework.pk == 1 || homework.setType == 6)
    val defaultTitle = if (isPk) "词汇PK" else "云端作业"
    val placeholderSections = homework.contents.mapIndexed { index, content ->
        val title = content.groupName.ifBlank { "云端内容 ${index + 1}" }
        ETS100AnswerReader.Section(
            caption = title,
            category = cloudHomeworkCategoryFromGroupName(title),
            typeName = title,
            questions = listOf(
                ETS100AnswerReader.Question(
                    order = index + 1,
                    sectionOrder = 1,
                    sectionCaption = title,
                    typeName = title,
                    questionText = "未下载",
                    answers = emptyList(),
                    originalText = null,
                    category = cloudHomeworkCategoryFromGroupName(title),
                    displayOrder = index + 1
                )
            ),
            originalContent = null
        )
    }.ifEmpty {
        listOf(
            ETS100AnswerReader.Section(
                caption = defaultTitle,
                category = if (isPk) "word.choice" else "cloud_homework",
                typeName = "未加载",
                questions = emptyList(),
                originalContent = null
            )
        )
    }

    return ETS100AnswerReader.Paper(
        paperId = identity.hashCode().toLong(),
        title = homework.name,
        dataFileName = "",
        fileSize = 0L,
        sections = placeholderSections,
        downloadTime = 0L,
        regionLabel = if (isPk) "词汇PK" else "云端",
        paperName = homework.name
    )
}

internal fun cloudHomeworkCategoryFromGroupName(groupName: String): String {
    return when {
        groupName.contains("朗读") -> "read_chapter"
        groupName.contains("转述") -> "topic"
        groupName.contains("询问") || groupName.contains("提问") -> "simple_expression_ufj"
        groupName.contains("回答") || groupName.contains("问答") -> "simple_expression_ufk"
        groupName.contains("选择") || groupName.contains("听选") || groupName.contains("记录") -> "simple_expression_ufi"
        else -> "cloud_homework"
    }
}

internal fun cloudHomeworkStatusLabel(status: String): String {
    return if (status == CloudHomeworkState.STATUS_HISTORY) "历史作业" else "当前作业"
}

internal fun cloudHomeworkRequestStatuses(status: String): List<String> {
    return if (status == CloudHomeworkState.STATUS_HISTORY) {
        listOf(CloudHomeworkState.STATUS_EXPIRED, CloudHomeworkState.STATUS_HISTORY)
    } else {
        listOf(status)
    }
}

internal fun cloudHomeworkIdentity(homework: ETS100ApiClient.HomeworkInfo): String {
    if (homework.id.isNotBlank()) return homework.id
    val contentSignature = homework.contents.joinToString("|") { "${it.groupName}:${it.url}" }
    return "${homework.name}:$contentSignature"
}

internal fun cloudHomeworkCacheKey(status: String, homework: ETS100ApiClient.HomeworkInfo): String =
    "$status:${cloudHomeworkIdentity(homework)}"

internal fun sortCloudQuestionsByOfficialOrder(
    questions: List<ETS100AnswerReader.Question>
): List<ETS100AnswerReader.Question> {
    val displayOrders = questions.mapNotNull { it.displayOrder }
    val hasReliableDisplayOrder = displayOrders.size == questions.size &&
        displayOrders.distinct().size == questions.size &&
        displayOrders.sorted() == (1..questions.size).toList()

    if (!hasReliableDisplayOrder) {
        return questions.mapIndexed { index, question ->
            question.copy(
                sectionOrder = index + 1,
                displayOrder = index + 1
            )
        }
    }

    return questions
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<ETS100AnswerReader.Question>> {
                it.value.displayOrder ?: Int.MAX_VALUE
            }.thenBy { it.index }
        )
        .mapIndexed { index, indexedQuestion ->
            indexedQuestion.value.copy(sectionOrder = index + 1)
        }
}

internal fun normalizeCloudParsedSections(
    sections: List<ETS100AnswerReader.Section>
): List<ETS100AnswerReader.Section> {
    val chooseSections = sections.filter { it.category == ETS100AnswerReader.StructureType.COLLECTOR_CHOOSE }
    if (chooseSections.size <= 1) {
        return sections.map { section ->
            if (section.category == ETS100AnswerReader.StructureType.COLLECTOR_CHOOSE) {
                section.copy(questions = sortCloudQuestionsByOfficialOrder(section.questions))
            } else {
                section
            }
        }
    }

    val firstChooseIndex = sections.indexOfFirst { it.category == ETS100AnswerReader.StructureType.COLLECTOR_CHOOSE }
    val mergedChooseSection = chooseSections.first().copy(
        questions = sortCloudQuestionsByOfficialOrder(chooseSections.flatMap { it.questions }),
        originalContent = chooseSections.firstNotNullOfOrNull { it.originalContent }
    )

    val normalizedSections = mutableListOf<ETS100AnswerReader.Section>()
    for ((index, section) in sections.withIndex()) {
        if (index == firstChooseIndex) {
            normalizedSections.add(mergedChooseSection)
        }
        if (section.category != ETS100AnswerReader.StructureType.COLLECTOR_CHOOSE) {
            normalizedSections.add(section)
        }
    }
    return normalizedSections
}


/** 云端分类 -> 请求状态映射。 */
internal fun cloudStatusForCategory(category: String): String =
    if (category == CloudHomeworkState.CATEGORY_HISTORY) {
        CloudHomeworkState.STATUS_HISTORY
    } else {
        CloudHomeworkState.STATUS_CURRENT
    }

/** 请求状态 -> 云端分类映射。 */
internal fun cloudCategoryForStatus(status: String): String =
    if (status == CloudHomeworkState.STATUS_HISTORY) {
        CloudHomeworkState.CATEGORY_HISTORY
    } else {
        CloudHomeworkState.CATEGORY_CURRENT
    }
