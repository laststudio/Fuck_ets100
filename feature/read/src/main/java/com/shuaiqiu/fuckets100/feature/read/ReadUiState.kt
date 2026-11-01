package com.shuaiqiu.fuckets100.feature.read

import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100FileReader

data class ReadUiState(
    val readerInfo: String = "",
    val dataFiles: List<ETS100FileReader.FileItem> = emptyList(),
    val resourceFiles: List<ETS100FileReader.FileItem> = emptyList(),
    val papers: List<ETS100AnswerReader.Paper> = emptyList(),
    val isLoading: Boolean = false,
    val isParsingLocalAnswers: Boolean = false,
    val errorMessage: String? = null,
    val reloadTrigger: Int = 0,
    val localProgress: LocalReadProgress = LocalReadProgress(),
    val selectedCloudCategory: String = CloudHomeworkState.CATEGORY_CURRENT,
    val selectedCloudHomeworkStatus: String = CloudHomeworkState.STATUS_CURRENT,
    val homeworkListsByStatus: Map<String, List<ETS100ApiClient.HomeworkInfo>> = emptyMap(),
    val isLoadingCloudHomework: Boolean = false,
    val cloudHomeworkError: String? = null,
    val changyanTokenExpired: Boolean = false,
    val cloudBaseUrl: String = ETS100ApiClient.Config.CDN_BASE_URL,
    val downloadedPapers: Map<String, List<ETS100AnswerReader.Paper>> = emptyMap(),
    val downloadedHomeworkNames: Set<String> = emptySet(),
    val cloudDownloadingHomeworks: Set<String> = emptySet(),
    val cloudDownloadProgress: Map<String, CloudHomeworkState.DownloadProgress> = emptyMap(),
    val failedCloudHomeworks: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
)

/** 一次性界面副作用，由界面层消费。 */
sealed interface ReadEffect {
    data class ShowToast(val message: String, val long: Boolean = false) : ReadEffect
    data class OpenPaperDetail(val paper: ETS100AnswerReader.Paper) : ReadEffect
    data object ClearPaperSelection : ReadEffect
}
