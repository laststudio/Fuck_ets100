package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100FileReader
import com.shuaiqiu.fuckets100.FeAccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ReadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val accountManager: FeAccountManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReadUiState())
    val uiState: StateFlow<ReadUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ReadEffect>(Channel.BUFFERED)
    val effects: Flow<ReadEffect> = _effects.receiveAsFlow()

    private val cloudController: ReadCloudController by lazy {
        ReadCloudController(
            context = context,
            uiStateFlow = _uiState,
            effectsFlow = _effects,
            scope = viewModelScope,
        )
    }

    private var activeMode: ActivationMode? = null
    private var cachedLocalSnapshot: ReadPageStateStore.LocalSnapshot? = null
    private var loadJob: Job? = null
    private var parseJob: Job? = null

    init {
        restoreCloudSnapshotIfNeeded()
    }

    fun loadLocal(
        mode: ActivationMode,
        accountOnline: Boolean,
        readAccessChecking: Boolean,
        readAccessIssue: String?,
    ) {
        prepareMode(mode)
        _uiState.update { it.copy(isParsingLocalAnswers = false) }

        if (readAccessChecking || readAccessIssue != null) {
            _uiState.update { it.copy(isLoading = false, isParsingLocalAnswers = false, errorMessage = null) }
            return
        }

        val state = _uiState.value
        if (state.reloadTrigger == 0 &&
            mode != ActivationMode.CLOUD &&
            (state.localProgress.phase == LocalParsePhase.SCANNING ||
                state.localProgress.phase == LocalParsePhase.PARSING)
        ) {
            _uiState.update { it.copy(isLoading = false, isParsingLocalAnswers = true) }
            log("已恢复正在进行的本地答案解析进度")
            return
        }

        if (mode == ActivationMode.CLOUD) {
            log("☁️ 云端模式，等待用户点击读取按钮加载作业列表")
            _uiState.update { it.copy(isLoading = false, isParsingLocalAnswers = false) }
            return
        }

        if (state.reloadTrigger == 0 && cachedLocalSnapshot?.mode == mode) {
            val snapshot = cachedLocalSnapshot ?: return
            _uiState.update {
                it.copy(
                    readerInfo = snapshot.readerInfo,
                    dataFiles = snapshot.dataFiles,
                    resourceFiles = snapshot.resourceFiles,
                    papers = snapshot.papers,
                    errorMessage = null,
                    isLoading = false,
                    isParsingLocalAnswers = false,
                    localProgress = LocalReadProgress(),
                )
            }
            log("已恢复上次读取的本地试卷内容")
            return
        }

        if (!accountOnline) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isParsingLocalAnswers = false,
                    errorMessage = if (it.papers.isEmpty()) "已离线" else null,
                )
            }
            return
        }

        parseJob?.cancel()
        parseJob = null
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = true,
                isParsingLocalAnswers = false,
                errorMessage = null,
                localProgress = LocalReadProgress(
                    phase = LocalParsePhase.SCANNING,
                    message = "正在扫描试卷列表",
                ),
            )
        }

        log("=".repeat(50))
        log("🚀 开始初始化 ETS 100 数据加载")
        log("当前激活模式: ${mode.name} (${mode.title})")
        log("=".repeat(50))

        loadJob = viewModelScope.launch {
            try {
                log("检查模式可用性...")
                if (!ETS100FileReader.isModeAvailable(mode, context)) {
                    log("当前模式不可用: $mode", isError = true)
                    _uiState.update {
                        it.copy(
                            errorMessage = "当前模式不可用: $mode",
                            isLoading = false,
                        )
                    }
                    return@launch
                }
                log("✓ 模式检查通过", isSuccess = true)
                log("开始扫描试卷列表...")

                val summaryStartMs = System.currentTimeMillis()
                val summaryResult = ReadLocalLoader.scan(context, mode)
                val summaryCostMs = System.currentTimeMillis() - summaryStartMs

                when (summaryResult) {
                    is LocalReadResult.Success -> {
                        val snapshot = summaryResult.value
                        _uiState.update {
                            it.copy(
                                readerInfo = snapshot.readerInfo,
                                dataFiles = snapshot.dataFiles,
                                resourceFiles = snapshot.resourceFiles,
                                papers = snapshot.papers,
                                errorMessage = null,
                                isLoading = false,
                                localProgress = it.localProgress.copy(
                                    totalCount = snapshot.papers.size,
                                    doneCount = 0,
                                    parsedGroupIndexes = emptySet(),
                                    message = "列表扫描耗时 ${summaryCostMs}ms，正在后台解析答案",
                                ),
                            )
                        }
                        log("✓ 阅读器创建成功: ${snapshot.readerInfo.substringAfterLast('.')}", isSuccess = true)
                        log("✓ 数据文件 ${snapshot.dataFiles.size} 个，资源文件 ${snapshot.resourceFiles.size} 个", isSuccess = true)
                        snapshot.dataFiles.firstOrNull()?.let { file ->
                            log("  最新文件: ${file.name} (${formatFileSize(file.size)})")
                        }
                        log("✓ 成功扫描到 ${snapshot.papers.size} 份试卷", isSuccess = true)
                        log(
                            "✓ 已显示 ${snapshot.papers.size} 份试卷，列表扫描耗时 ${summaryCostMs}ms，开始后台解析答案",
                            isSuccess = true,
                        )

                        if (snapshot.papers.isNotEmpty()) {
                            startLocalParse(mode, snapshot, summaryCostMs)
                        } else {
                            log("未扫描到试卷，跳过后台答案解析", isWarning = true)
                            _uiState.update {
                                it.copy(
                                    localProgress = it.localProgress.copy(
                                        phase = LocalParsePhase.COMPLETED,
                                        message = "未扫描到试卷",
                                    )
                                )
                            }
                        }
                    }

                    is LocalReadResult.Error -> {
                        log("✗ 加载失败: ${summaryResult.message}", isError = true)
                        _uiState.update {
                            it.copy(
                                errorMessage = "加载失败: ${summaryResult.message}",
                                isLoading = false,
                                localProgress = it.localProgress.copy(
                                    phase = LocalParsePhase.FAILED,
                                    message = summaryResult.message,
                                ),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("✗ 初始化异常: ${e.message}", isError = true)
                _uiState.update {
                    it.copy(
                        errorMessage = "初始化异常: ${e.message}",
                        isLoading = false,
                        localProgress = it.localProgress.copy(
                            phase = LocalParsePhase.FAILED,
                            message = e.message ?: "未知错误",
                        ),
                    )
                }
            } finally {
                log("初始化流程结束喵~")
            }
        }
    }

    fun isFeatureAvailable(featureKey: String): Pair<Boolean, String?> =
        accountManager.isFeatureAvailable(featureKey)

    suspend fun cloudSession(preferredAccountId: String) =
        accountManager.cloudSession(preferredAccountId)

    fun requestReload() {
        _uiState.update { it.copy(reloadTrigger = it.reloadTrigger + 1, errorMessage = null) }
    }

    // ==================== 云端模式（委托给 ReadCloudController） ====================

    fun selectCloudCategory(category: String) = cloudController.selectCloudCategory(category)

    fun selectCloudHomeworkStatus(status: String) = cloudController.selectCloudHomeworkStatus(status)

    fun restoreCloudSnapshotIfNeeded() = cloudController.restoreCloudSnapshotIfNeeded()

    fun updateCloudDownloadProgress(
        cacheKey: String,
        progress: CloudHomeworkState.DownloadProgress?,
    ) = cloudController.updateCloudDownloadProgress(cacheKey, progress)

    suspend fun loadCloudHomeworkList(
        status: String = _uiState.value.selectedCloudHomeworkStatus,
        accountOnline: Boolean,
    ) = cloudController.loadCloudHomeworkList(status, accountOnline)

    fun requestHomeworkDownload(
        homeworkInfo: ETS100ApiClient.HomeworkInfo,
        status: String,
        accountOnline: Boolean,
    ) = cloudController.requestHomeworkDownload(homeworkInfo, status, accountOnline)

    suspend fun downloadAndParseHomework(
        homeworkInfo: ETS100ApiClient.HomeworkInfo,
        status: String = _uiState.value.selectedCloudHomeworkStatus,
        accountOnline: Boolean,
    ) = cloudController.downloadAndParseHomework(homeworkInfo, status, accountOnline)

    fun clearCloudHomeworkCache() = cloudController.clearCloudHomeworkCache()


    fun reloadLocalWithClearedCache(mode: ActivationMode) {
        ETS100AnswerReader.clearResourceScanCache(mode)
        ReadPageStateStore.clearLocal(context)
        cachedLocalSnapshot = null
        requestReload()
    }


    /** 本地模式：删除 data/resource 目录并重新读取。 */
    fun deleteLocalReadDirectories(mode: ActivationMode) {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            try {
                ReadCloudLog.warn("🗑️ 开始后台删除 data 和 resource 目录...")
                val (dataDeleted, resourceDeleted) = withContext(Dispatchers.IO) {
                    val dataPath = ETS100FileReader.Path.getDataDir()
                    val resourcePath = ETS100FileReader.Path.getResourceDir()
                    ETS100FileReader.deleteDirectory(mode, dataPath, context) to
                        ETS100FileReader.deleteDirectory(mode, resourcePath, context)
                }
                if (dataDeleted) ReadCloudLog.success("✅ data 目录删除成功") else ReadCloudLog.error("❌ data 目录删除失败")
                if (resourceDeleted) {
                    ReadCloudLog.success("✅ resource 目录删除成功")
                } else {
                    ReadCloudLog.error("❌ resource 目录删除失败")
                }

                loadJob?.cancel()
                parseJob?.cancel()
                loadJob = null
                parseJob = null
                ReadPageStateStore.clearLocal(context)
                cachedLocalSnapshot = null
                _uiState.update {
                    it.copy(
                        readerInfo = "",
                        dataFiles = emptyList(),
                        resourceFiles = emptyList(),
                        papers = emptyList(),
                        isLoading = false,
                        isParsingLocalAnswers = false,
                        errorMessage = null,
                        localProgress = LocalReadProgress(),
                    )
                }
                _effects.trySend(ReadEffect.ClearPaperSelection)
                requestReload()
            } catch (e: Exception) {
                ReadCloudLog.error("❌ 删除失败: ${e.message}")
                _effects.trySend(ReadEffect.ShowToast("删除失败: ${e.message}", long = true))
            } finally {
                _uiState.update { it.copy(isDeleting = false) }
            }
        }
    }

    private fun prepareMode(mode: ActivationMode) {
        if (activeMode == mode) return
        loadJob?.cancel()
        parseJob?.cancel()
        loadJob = null
        parseJob = null
        activeMode = mode
        cachedLocalSnapshot = if (mode == ActivationMode.CLOUD) {
            null
        } else {
            ReadPageStateStore.loadLocal(context)?.takeIf { it.mode == mode }
        }
        val snapshot = cachedLocalSnapshot
        _uiState.update {
            if (mode == ActivationMode.CLOUD) {
                it.copy(
                    isLoading = false,
                    isParsingLocalAnswers = false,
                    errorMessage = null,
                    localProgress = LocalReadProgress(),
                )
            } else {
                it.copy(
                    readerInfo = snapshot?.readerInfo.orEmpty(),
                    dataFiles = snapshot?.dataFiles.orEmpty(),
                    resourceFiles = snapshot?.resourceFiles.orEmpty(),
                    papers = snapshot?.papers.orEmpty(),
                    isLoading = snapshot == null,
                    isParsingLocalAnswers = false,
                    errorMessage = null,
                    localProgress = LocalReadProgress(),
                )
            }
        }
    }

    private fun startLocalParse(
        mode: ActivationMode,
        snapshot: LocalReadSnapshot,
        summaryCostMs: Long,
    ) {
        _uiState.update {
            it.copy(
                isParsingLocalAnswers = true,
                localProgress = it.localProgress.copy(
                    phase = LocalParsePhase.PARSING,
                    message = "列表扫描耗时 ${summaryCostMs}ms，正在后台解析答案",
                ),
            )
        }
        parseJob = viewModelScope.launch {
            delay(32)
            val parseStartMs = System.currentTimeMillis()
            val fullResult = ReadLocalLoader.parse(
                context = context,
                mode = mode,
                onGroupStarted = { groupIndex, totalGroups ->
                    _uiState.update {
                        it.copy(
                            localProgress = it.localProgress.copy(
                                totalGroupCount = totalGroups,
                                activeGroupIndex = groupIndex,
                                message = "正在解析第 ${groupIndex + 1}/$totalGroups 组",
                            )
                        )
                    }
                },
                onGroupParsed = { groupIndex, parsedGroupPapers ->
                    val updatedState = _uiState.updateAndGet { current ->
                        val updatedPapers = mergeParsedGroup(
                            currentPapers = current.papers,
                            groupIndex = groupIndex,
                            parsedGroupPapers = parsedGroupPapers,
                        )
                        val parsedIndexes = current.localProgress.parsedGroupIndexes + groupIndex
                        current.copy(
                            papers = updatedPapers,
                            localProgress = current.localProgress.copy(
                                parsedGroupIndexes = parsedIndexes,
                                doneCount = parsedIndexes.size,
                                completedGroupCount = parsedIndexes.size,
                                sectionCount = updatedPapers.totalSectionCount(),
                                questionCount = updatedPapers.totalQuestionCount(),
                            ),
                        )
                    }
                    if (parsedGroupPapers.isNotEmpty()) {
                        log(
                            "✓ 已解析第 ${groupIndex + 1} 份试卷 " +
                                "(${updatedState.localProgress.doneCount}/${updatedState.localProgress.totalCount})，" +
                                "累计 ${updatedState.localProgress.sectionCount} 个分区、" +
                                "${updatedState.localProgress.questionCount} 道题",
                            isSuccess = true,
                        )
                    }
                },
            )

            try {
                when (fullResult) {
                    is LocalReadResult.Success -> {
                        val parsedPapers = fullResult.value
                        if (parsedPapers.isNotEmpty()) {
                            val savedSnapshot = ReadPageStateStore.LocalSnapshot(
                                mode = mode,
                                readerInfo = snapshot.readerInfo,
                                dataFiles = snapshot.dataFiles,
                                resourceFiles = snapshot.resourceFiles,
                                papers = parsedPapers,
                            )
                            ReadPageStateStore.saveLocal(context, savedSnapshot)
                            cachedLocalSnapshot = savedSnapshot
                            val totalSections = parsedPapers.sumOf { it.sections.size }
                            val totalQuestions = parsedPapers.sumOf { it.sections.sumOf { section -> section.questions.size } }
                            val answeredQuestions = parsedPapers.sumOf { paper ->
                                paper.sections.sumOf { section -> section.questions.count { it.answer.isNotEmpty() } }
                            }
                            val answeredPercent = if (totalQuestions > 0) {
                                (answeredQuestions * 100.0 / totalQuestions).toInt()
                            } else {
                                0
                            }
                            val parseCostMs = System.currentTimeMillis() - parseStartMs
                            log("📊 试卷统计:")
                            log("   总试卷数: ${parsedPapers.size}")
                            log("   总分区数: $totalSections")
                            log("   总题目数: $totalQuestions")
                            log("   已答题目: $answeredQuestions ($answeredPercent%)", isSuccess = true)
                            log("✓ 答案后台解析完成，耗时 ${parseCostMs}ms", isSuccess = true)
                            _uiState.update {
                                it.copy(
                                    papers = parsedPapers,
                                    localProgress = it.localProgress.copy(
                                        doneCount = it.localProgress.totalCount,
                                        sectionCount = totalSections,
                                        questionCount = totalQuestions,
                                        phase = LocalParsePhase.COMPLETED,
                                        message = "共 ${parsedPapers.size} 份试卷、$totalSections 个分区、$totalQuestions 道题",
                                    ),
                                )
                            }
                        } else {
                            log("后台解析未返回完整试卷，保留当前试卷列表", isWarning = true)
                            _uiState.update {
                                it.copy(
                                    localProgress = it.localProgress.copy(
                                        phase = LocalParsePhase.FAILED,
                                        message = "后台解析未返回完整试卷",
                                    )
                                )
                            }
                        }
                    }

                    is LocalReadResult.Error -> {
                        log("✗ 后台解析答案失败: ${fullResult.message}", isError = true)
                        _uiState.update {
                            it.copy(
                                localProgress = it.localProgress.copy(
                                    phase = LocalParsePhase.FAILED,
                                    message = "后台解析答案失败: ${fullResult.message}",
                                )
                            )
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(isParsingLocalAnswers = false) }
            }
        }
    }


    private fun log(
        message: String,
        isError: Boolean = false,
        isWarning: Boolean = false,
        isSuccess: Boolean = false,
    ) {
        val prefix = when {
            isError -> "[错误] "
            isWarning -> "[警告] "
            isSuccess -> "[成功] "
            else -> "[初始化] "
        }
        val text = "$prefix$message"
        when {
            isError -> Log.e(READ_TAG, text)
            isWarning -> Log.w(READ_TAG, text)
            else -> Log.i(READ_TAG, text)
        }
    }

    private companion object {
        const val READ_TAG = "ReadScreen"
    }
}
