package com.shuaiqiu.fuckets100

/**
 * 云端作业状态单例
 * 保存在内存中，Tab 切换时不丢失喵~
 */
object CloudHomeworkState {
    const val STATUS_CURRENT = "1"
    const val STATUS_HISTORY = "2"

    var selectedStatus: String = STATUS_CURRENT
    var homeworkListsByStatus: Map<String, List<ETS100ApiClient.HomeworkInfo>> = emptyMap()
    var homeworkList: List<ETS100ApiClient.HomeworkInfo>
        get() = homeworkListsByStatus[selectedStatus].orEmpty()
        set(value) {
            homeworkListsByStatus = homeworkListsByStatus + (selectedStatus to value)
        }
    var cloudBaseUrl: String = ETS100ApiClient.Config.CDN_BASE_URL
    var downloadedPapers: Map<String, List<ETS100AnswerReader.Paper>> = emptyMap()
    var downloadedHomeworkNames: Set<String> = emptySet()
    var cloudDownloadingHomeworks: Set<String> = emptySet()
    var failedCloudHomeworks: Set<String> = emptySet()
    var isLoading: Boolean = false
    var error: String? = null

    fun clear() {
        selectedStatus = STATUS_CURRENT
        homeworkListsByStatus = emptyMap()
        downloadedPapers = emptyMap()
        downloadedHomeworkNames = emptySet()
        cloudDownloadingHomeworks = emptySet()
        failedCloudHomeworks = emptySet()
        isLoading = false
        error = null
    }
}
