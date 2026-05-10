package com.shuaiqiu.fuckets100

/**
 * 云端作业状态单例
 * 保存在内存中，Tab 切换时不丢失喵~
 */
object CloudHomeworkState {
    var homeworkList: List<ETS100ApiClient.HomeworkInfo> = emptyList()
    var cloudBaseUrl: String = ETS100ApiClient.Config.CDN_BASE_URL
    var downloadedPapers: Map<String, List<ETS100AnswerReader.Paper>> = emptyMap()
    var downloadedHomeworkNames: Set<String> = emptySet()
    var cloudDownloadingHomeworks: Set<String> = emptySet()
    var failedCloudHomeworks: Set<String> = emptySet()
    var isLoading: Boolean = false
    var error: String? = null

    fun clear() {
        homeworkList = emptyList()
        downloadedPapers = emptyMap()
        downloadedHomeworkNames = emptySet()
        cloudDownloadingHomeworks = emptySet()
        failedCloudHomeworks = emptySet()
        isLoading = false
        error = null
    }
}