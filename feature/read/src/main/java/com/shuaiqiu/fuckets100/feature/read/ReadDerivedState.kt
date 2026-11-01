package com.shuaiqiu.fuckets100.feature.read

import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100HomeworkClassifier
import com.shuaiqiu.fuckets100.PaperSource

/**
 * 云端作业列表的纯派生计算。
 *
 * 这些函数不依赖 Compose，从 ReadScreen 中抽出后可以单元测试，
 * 也便于后续迁入 ReadViewModel。
 */
internal object ReadDerivedState {
    /** 当前所选云端分类对应的作业列表（含词汇 PK 过滤与去重）。 */
    fun homeworkList(
        category: String,
        homeworkListsByStatus: Map<String, List<ETS100ApiClient.HomeworkInfo>>,
    ): List<ETS100ApiClient.HomeworkInfo> = when (category) {
        CloudHomeworkState.CATEGORY_CURRENT ->
            homeworkListsByStatus[CloudHomeworkState.STATUS_CURRENT].orEmpty()

        CloudHomeworkState.CATEGORY_HISTORY ->
            homeworkListsByStatus[CloudHomeworkState.STATUS_HISTORY].orEmpty()

        CloudHomeworkState.CATEGORY_VOCAB_PK -> {
            val allLoaded = homeworkListsByStatus[CloudHomeworkState.STATUS_CURRENT].orEmpty() +
                homeworkListsByStatus[CloudHomeworkState.STATUS_HISTORY].orEmpty()
            allLoaded
                .distinctBy { it.id.ifBlank { it.name } }
                // 解析器判定词汇/PK（失败时回退旧字段）
                .filter { ETS100HomeworkClassifier.classify(it)?.isVocabPk ?: (it.pk == 1 || it.setType == 6) }
        }

        else -> homeworkListsByStatus[CloudHomeworkState.STATUS_CURRENT].orEmpty()
    }

    /** 云端分类的中文显示名。 */
    fun categoryLabel(category: String): String = when (category) {
        CloudHomeworkState.CATEGORY_CURRENT -> "当前作业"
        CloudHomeworkState.CATEGORY_HISTORY -> "历史作业"
        CloudHomeworkState.CATEGORY_VOCAB_PK -> "词汇PK"
        else -> "当前作业"
    }

    /** 所选云端分类是否已经从后端加载过。 */
    fun isCategoryLoaded(
        category: String,
        homeworkListsByStatus: Map<String, List<ETS100ApiClient.HomeworkInfo>>,
    ): Boolean = when (category) {
        CloudHomeworkState.CATEGORY_CURRENT ->
            homeworkListsByStatus.containsKey(CloudHomeworkState.STATUS_CURRENT)

        CloudHomeworkState.CATEGORY_HISTORY ->
            homeworkListsByStatus.containsKey(CloudHomeworkState.STATUS_HISTORY)

        CloudHomeworkState.CATEGORY_VOCAB_PK ->
            homeworkListsByStatus.containsKey(CloudHomeworkState.STATUS_CURRENT) ||
                homeworkListsByStatus.containsKey(CloudHomeworkState.STATUS_HISTORY)

        else -> false
    }

    /** 已下载到本地的云端试卷（过滤掉非 Cloud 来源的脏数据）。 */
    fun downloadedCloudPapers(
        downloadedPapers: Map<String, List<ETS100AnswerReader.Paper>>,
    ): List<ETS100AnswerReader.Paper> =
        downloadedPapers.values.flatten().filter { it.source is PaperSource.Cloud }

    /** 恢复云端缓存时，过滤掉来源非 Cloud 的试卷并同步下载名集合。 */
    fun sanitizeDownloadedPapers(
        downloadedPapers: Map<String, List<ETS100AnswerReader.Paper>>,
        downloadedHomeworkNames: Set<String>,
    ): Pair<Map<String, List<ETS100AnswerReader.Paper>>, Set<String>> {
        val valid = downloadedPapers.filterValues { papers ->
            papers.isNotEmpty() && papers.all { it.source is PaperSource.Cloud }
        }
        return valid to downloadedHomeworkNames.intersect(valid.keys)
    }
}
