package com.shuaiqiu.fuckets100.feature.read

/** 自动完成：从官方 struct 归一化后的单题。 */
internal data class AutoQuestion(
    val order: Int,
    val sectionOrder: Int,
    val sectionCaption: String,
    val typeName: String,
    val questionText: String,
    val category: String,
    val point: Double,
    val options: List<String> = emptyList(),
)

/** 自动完成：逐题结果。 */
data class AutoQuestionResult(
    val order: Int,
    val typeName: String,
    val text: String,
    val awardedScore: Double,
    val maxScore: Double,
)

/** 自动完成：一次任务的最终结果（同时用于历史记录）。 */
data class AutoRunResult(
    val homeworkId: String,
    val homeworkName: String,
    val score: Double,
    val maxScore: Double,
    val scoreRate: Double,
    val targetTotal: Double,
    val useTime: Int,
    val officialPoint: Double?,
    val officialUseTime: Int?,
    val officialSubmitTime: String?,
    val completedAt: String,
    val questionResults: List<AutoQuestionResult>,
)

/** 自动完成：阶段进度回调。 */
internal data class AutoStageUpdate(
    val stageIndex: Int,
    val progress: Int,
    val label: String,
    val currentQuestion: String = "",
    val currentQuestionIndex: Int = 0,
    val totalQuestions: Int = 0,
)

/** 自动完成：可直接展示给用户的业务错误。 */
internal class AutoCompleteException(message: String) : Exception(message)

internal object AutoCompleteStage {
    val LABELS = listOf(
        "[1/8] 正在初始化 ETS 会话...",
        "[2/8] 正在拉取作业结构与题目信息...",
        "[3/8] 正在生成作答数据...",
        "[4/8] 正在逐题处理作答...",
        "[5/8] 正在提交作答数据...",
        "[6/8] 正在上报逐题成绩...",
        "[7/8] 正在同步用时并提交作业终审...",
        "[8/8] 正在核对云端作业成绩入库...",
    )
}
