package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import androidx.lifecycle.ViewModel
import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.PaperSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CoroutineScope


/**
 * 阅读页云端流程控制器。
 *
 * 承载作业列表拉取、会话刷新、下载解析与缓存清理，配合 [ReadViewModel] 的
 * 状态流与副作用通道工作，避免 ViewModel 文件无限膨胀。
 */
internal class ReadCloudController(
    private val context: Context,
    private val uiStateFlow: MutableStateFlow<ReadUiState>,
    private val effectsFlow: Channel<ReadEffect>,
    private val scope: CoroutineScope,
) {
    private var cloudSnapshotRestored = false

    // ==================== 云端模式 ====================

    fun selectCloudCategory(category: String) {
        val state = uiStateFlow.value
        if (category == state.selectedCloudCategory) return

        val targetStatus = cloudStatusForCategory(category)
        CloudHomeworkState.selectedCategory = category
        CloudHomeworkState.selectedStatus = targetStatus
        CloudHomeworkState.error = null
        uiStateFlow.update {
            it.copy(
                selectedCloudCategory = category,
                selectedCloudHomeworkStatus = targetStatus,
                cloudHomeworkError = null,
            )
        }
        // 分类切换只切换当前视图，不自动发起网络读取；由用户点击“读取作业”主动加载。
        effectsFlow.trySend(ReadEffect.ClearPaperSelection)
        saveCloudReadState()
    }

    fun selectCloudHomeworkStatus(status: String) {
        selectCloudCategory(cloudCategoryForStatus(status))
    }

    fun restoreCloudSnapshotIfNeeded() {
        if (cloudSnapshotRestored) return
        cloudSnapshotRestored = true
        val snapshot = ReadPageStateStore.loadCloud(context) ?: return
        if (CloudHomeworkState.homeworkListsByStatus.isNotEmpty() ||
            CloudHomeworkState.downloadedPapers.isNotEmpty()
        ) {
            return
        }
        val (validDownloadedPapers, validHomeworkNames) = ReadDerivedState.sanitizeDownloadedPapers(
            downloadedPapers = snapshot.downloadedPapers,
            downloadedHomeworkNames = snapshot.downloadedHomeworkNames,
        )
        CloudHomeworkState.selectedStatus = snapshot.selectedStatus
        CloudHomeworkState.selectedCategory = snapshot.selectedCategory
        CloudHomeworkState.homeworkListsByStatus = snapshot.homeworkListsByStatus
        CloudHomeworkState.cloudBaseUrl = snapshot.cloudBaseUrl
        CloudHomeworkState.downloadedPapers = validDownloadedPapers
        CloudHomeworkState.downloadedHomeworkNames = validHomeworkNames
        CloudHomeworkState.failedCloudHomeworks = snapshot.failedCloudHomeworks
        uiStateFlow.update {
            it.copy(
                selectedCloudCategory = snapshot.selectedCategory,
                selectedCloudHomeworkStatus = snapshot.selectedStatus,
                homeworkListsByStatus = snapshot.homeworkListsByStatus,
                cloudBaseUrl = snapshot.cloudBaseUrl,
                downloadedPapers = validDownloadedPapers,
                downloadedHomeworkNames = validHomeworkNames,
                failedCloudHomeworks = snapshot.failedCloudHomeworks,
            )
        }
    }

    fun saveCloudReadState() {
        val state = uiStateFlow.value
        ReadPageStateStore.saveCloud(
            context,
            ReadPageStateStore.CloudSnapshot(
                selectedStatus = state.selectedCloudHomeworkStatus,
                homeworkListsByStatus = state.homeworkListsByStatus,
                cloudBaseUrl = state.cloudBaseUrl,
                downloadedPapers = state.downloadedPapers,
                downloadedHomeworkNames = state.downloadedHomeworkNames,
                failedCloudHomeworks = state.failedCloudHomeworks,
                selectedCategory = state.selectedCloudCategory,
            )
        )
    }

    fun updateCloudDownloadProgress(
        cacheKey: String,
        progress: CloudHomeworkState.DownloadProgress?,
    ) {
        uiStateFlow.update { state ->
            val next = if (progress == null) {
                state.cloudDownloadProgress - cacheKey
            } else {
                state.cloudDownloadProgress + (cacheKey to progress)
            }
            CloudHomeworkState.cloudDownloadProgress = next
            state.copy(cloudDownloadProgress = next)
        }
    }

    suspend fun loadCloudHomeworkList(
        status: String = uiStateFlow.value.selectedCloudHomeworkStatus,
        accountOnline: Boolean,
    ) {
        if (uiStateFlow.value.isLoadingCloudHomework) return

        // 先进入加载态，再执行授权校验和账号获取，避免点击后网络操作期间页面像卡死。
        CloudHomeworkState.isLoading = true
        CloudHomeworkState.error = null
        uiStateFlow.update {
            it.copy(
                isLoadingCloudHomework = true,
                cloudHomeworkError = null,
                changyanTokenExpired = false,
                selectedCloudHomeworkStatus = status,
            )
        }
        CloudHomeworkState.selectedStatus = status

        val result = loadCloudHomeworkListResult(
            context = context,
            status = status,
            accountOnline = accountOnline,
        )

        when (result) {
            is CloudHomeworkListResult.Success -> {
                val state = uiStateFlow.value
                val nextLists = state.homeworkListsByStatus + (status to result.homeworks)
                val nextBaseUrl = result.baseUrl.ifBlank { state.cloudBaseUrl }
                CloudHomeworkState.homeworkListsByStatus = nextLists
                CloudHomeworkState.cloudBaseUrl = nextBaseUrl
                CloudHomeworkState.error = null
                CloudHomeworkState.isLoading = false
                uiStateFlow.update {
                    it.copy(
                        homeworkListsByStatus = nextLists,
                        cloudBaseUrl = nextBaseUrl,
                        isLoadingCloudHomework = false,
                        cloudHomeworkError = null,
                        changyanTokenExpired = false,
                    )
                }
                saveCloudReadState()
            }

            is CloudHomeworkListResult.ChangyanExpired -> {
                CloudHomeworkState.error = result.message
                CloudHomeworkState.isLoading = false
                uiStateFlow.update {
                    it.copy(
                        isLoadingCloudHomework = false,
                        cloudHomeworkError = result.message,
                        changyanTokenExpired = true,
                    )
                }
            }

            is CloudHomeworkListResult.Failure -> {
                CloudHomeworkState.error = result.message
                CloudHomeworkState.isLoading = false
                uiStateFlow.update {
                    it.copy(
                        isLoadingCloudHomework = false,
                        cloudHomeworkError = result.message,
                    )
                }
                effectsFlow.trySend(ReadEffect.ShowError("获取作业列表失败", result.message))
            }
        }
    }

    /** 由界面触发下载；协程生命周期跟随 ViewModel。 */
    fun requestHomeworkDownload(
        homeworkInfo: ETS100ApiClient.HomeworkInfo,
        status: String,
        accountOnline: Boolean,
    ) {
        scope.launch {
            downloadAndParseHomework(
                homeworkInfo = homeworkInfo,
                status = status,
                accountOnline = accountOnline,
            )
        }
    }

    suspend fun downloadAndParseHomework(
        homeworkInfo: ETS100ApiClient.HomeworkInfo,
        status: String = uiStateFlow.value.selectedCloudHomeworkStatus,
        accountOnline: Boolean,
    ) {
        val state = uiStateFlow.value
        val cacheKey = cloudHomeworkCacheKey(status, homeworkInfo)
        val currentKey = cloudHomeworkCacheKey(CloudHomeworkState.STATUS_CURRENT, homeworkInfo)
        val historyKey = cloudHomeworkCacheKey(CloudHomeworkState.STATUS_HISTORY, homeworkInfo)
        val targetIdentity = cloudHomeworkIdentity(homeworkInfo)

        // 1. 命中已解析缓存则直接打开
        val existingPaper = state.downloadedPapers[cacheKey]?.firstOrNull { it.sections.isNotEmpty() }
            ?: state.downloadedPapers[currentKey]?.firstOrNull { it.sections.isNotEmpty() }
            ?: state.downloadedPapers[historyKey]?.firstOrNull { it.sections.isNotEmpty() }
            ?: state.downloadedPapers.entries.firstOrNull { (k, list) ->
                (k.endsWith(":$targetIdentity") ||
                    list.firstOrNull()?.source.let { it is PaperSource.Cloud && it.homeworkIdentity == targetIdentity }) &&
                    list.firstOrNull()?.sections?.isNotEmpty() == true
            }?.value?.firstOrNull()

        if (existingPaper != null) {
            ReadCloudLog.info("📁 缓存命中，作业已解析喵: ${homeworkInfo.name}")
            val clearedDownloading = state.cloudDownloadingHomeworks - cacheKey - currentKey - historyKey
            val clearedProgress = state.cloudDownloadProgress - cacheKey - currentKey - historyKey
            val nextNames = state.downloadedHomeworkNames + cacheKey
            CloudHomeworkState.cloudDownloadingHomeworks = clearedDownloading
            CloudHomeworkState.cloudDownloadProgress = clearedProgress
            CloudHomeworkState.downloadedHomeworkNames = nextNames
            uiStateFlow.update {
                it.copy(
                    cloudDownloadingHomeworks = clearedDownloading,
                    cloudDownloadProgress = clearedProgress,
                    downloadedHomeworkNames = nextNames,
                )
            }
            effectsFlow.trySend(ReadEffect.OpenPaperDetail(existingPaper))
            return
        }

        if (!accountOnline) {
            val cleared = state.cloudDownloadingHomeworks - cacheKey
            CloudHomeworkState.cloudDownloadingHomeworks = cleared
            uiStateFlow.update { it.copy(cloudDownloadingHomeworks = cleared) }
            effectsFlow.trySend(ReadEffect.ShowToast("已离线"))
            return
        }

        // 进入下载中状态并显示授权校验进度
        val downloading = state.cloudDownloadingHomeworks + cacheKey
        val failed = state.failedCloudHomeworks - cacheKey
        val papersWithoutKey = state.downloadedPapers - cacheKey
        CloudHomeworkState.cloudDownloadingHomeworks = downloading
        CloudHomeworkState.failedCloudHomeworks = failed
        CloudHomeworkState.downloadedPapers = papersWithoutKey
        uiStateFlow.update {
            it.copy(
                cloudDownloadingHomeworks = downloading,
                failedCloudHomeworks = failed,
                downloadedPapers = papersWithoutKey,
            )
        }
        updateCloudDownloadProgress(
            cacheKey,
            CloudHomeworkState.DownloadProgress(currentFileName = "正在校验授权..."),
        )

        val result = downloadAndParseCloudPaper(
            context = context,
            homeworkInfo = homeworkInfo,
            status = status,
            cloudBaseUrl = state.cloudBaseUrl,
            onProgress = { progress ->
                // 下载回调运行在 IO 线程；进度状态保持在主线程更新。
                withContext(Dispatchers.Main) {
                    updateCloudDownloadProgress(cacheKey, progress)
                }
            },
        )

        try {
            when (result) {
                is CloudPaperResult.Success -> {
                    val current = uiStateFlow.value
                    val nextPapers = current.downloadedPapers + (cacheKey to listOf(result.paper))
                    val nextNames = current.downloadedHomeworkNames + cacheKey
                    val nextDownloading = current.cloudDownloadingHomeworks - cacheKey
                    val nextProgress = current.cloudDownloadProgress - cacheKey
                    val nextFailed = current.failedCloudHomeworks - cacheKey
                    CloudHomeworkState.downloadedPapers = nextPapers
                    CloudHomeworkState.downloadedHomeworkNames = nextNames
                    CloudHomeworkState.cloudDownloadingHomeworks = nextDownloading
                    CloudHomeworkState.cloudDownloadProgress = nextProgress
                    CloudHomeworkState.failedCloudHomeworks = nextFailed
                    uiStateFlow.update {
                        it.copy(
                            downloadedPapers = nextPapers,
                            downloadedHomeworkNames = nextNames,
                            cloudDownloadingHomeworks = nextDownloading,
                            cloudDownloadProgress = nextProgress,
                            failedCloudHomeworks = nextFailed,
                        )
                    }
                    saveCloudReadState()
                    // 下载成功后不再切换页面，只标记已下载，停留作业列表喵~
                    effectsFlow.trySend(ReadEffect.ShowToast("下载并解析成功！"))
                }

                is CloudPaperResult.AuthDenied -> {
                    val current = uiStateFlow.value
                    val nextDownloading = current.cloudDownloadingHomeworks - cacheKey
                    val nextProgress = current.cloudDownloadProgress - cacheKey
                    CloudHomeworkState.cloudDownloadingHomeworks = nextDownloading
                    CloudHomeworkState.cloudDownloadProgress = nextProgress
                    uiStateFlow.update {
                        it.copy(
                            cloudDownloadingHomeworks = nextDownloading,
                            cloudDownloadProgress = nextProgress,
                        )
                    }
                    ReadCloudLog.error("✗ ${result.message}")
                    effectsFlow.trySend(ReadEffect.ShowError("答案解析不可用", result.message))
                }

                is CloudPaperResult.Failure -> {
                    val current = uiStateFlow.value
                    val nextDownloading = current.cloudDownloadingHomeworks - cacheKey
                    val nextProgress = current.cloudDownloadProgress - cacheKey
                    val nextFailed = current.failedCloudHomeworks + cacheKey
                    val nextPapers = result.sourceOnlyPaper?.let { current.downloadedPapers + (cacheKey to listOf(it)) }
                        ?: current.downloadedPapers
                    CloudHomeworkState.cloudDownloadingHomeworks = nextDownloading
                    CloudHomeworkState.cloudDownloadProgress = nextProgress
                    CloudHomeworkState.failedCloudHomeworks = nextFailed
                    CloudHomeworkState.downloadedPapers = nextPapers
                    uiStateFlow.update {
                        it.copy(
                            downloadedPapers = nextPapers,
                            cloudDownloadingHomeworks = nextDownloading,
                            cloudDownloadProgress = nextProgress,
                            failedCloudHomeworks = nextFailed,
                        )
                    }
                    saveCloudReadState()
                    effectsFlow.trySend(ReadEffect.ShowError("下载或解析失败", result.message))
                }
            }
        } finally {
            val current = uiStateFlow.value
            if (current.cloudDownloadingHomeworks.contains(cacheKey)) {
                val nextDownloading = current.cloudDownloadingHomeworks - cacheKey
                CloudHomeworkState.cloudDownloadingHomeworks = nextDownloading
                uiStateFlow.update { it.copy(cloudDownloadingHomeworks = nextDownloading) }
            }
        }
    }

    fun clearCloudHomeworkCache() {
        if (uiStateFlow.value.isDeleting) return
        uiStateFlow.update { it.copy(isDeleting = true) }
        scope.launch {
            try {
                ReadCloudLog.info("🗑️ 开始后台清除云端缓存")
                withContext(Dispatchers.IO) {
                    val cacheDir = File(context.cacheDir, "cloud_homework")
                    if (cacheDir.exists()) {
                        cacheDir.deleteRecursively()
                    }
                }
                ReadCloudLog.info("🗑️ 已删除云端缓存目录")
                CloudHomeworkState.clear()
                ReadPageStateStore.clearCloud(context)
                uiStateFlow.update {
                    it.copy(
                        downloadedPapers = emptyMap(),
                        downloadedHomeworkNames = emptySet(),
                        cloudDownloadingHomeworks = emptySet(),
                        cloudDownloadProgress = emptyMap(),
                        failedCloudHomeworks = emptySet(),
                        papers = emptyList(),
                        homeworkListsByStatus = emptyMap(),
                        selectedCloudCategory = CloudHomeworkState.CATEGORY_CURRENT,
                        selectedCloudHomeworkStatus = CloudHomeworkState.STATUS_CURRENT,
                    )
                }
                effectsFlow.trySend(ReadEffect.ShowToast("已清除所有云端缓存"))
            } catch (e: Exception) {
                ReadCloudLog.error("✗ 清除云端缓存失败: ${e.message}")
                effectsFlow.trySend(ReadEffect.ShowToast("删除失败: ${e.message}", long = true))
            } finally {
                uiStateFlow.update { it.copy(isDeleting = false) }
            }
        }
    }
}
