package com.shuaiqiu.fuckets100.feature.read

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveScaffold
import com.shuaiqiu.fuckets100.core.designsystem.expressiveTopAppBarColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100AuthManager
import com.shuaiqiu.fuckets100.FeAccountFeatures
import com.shuaiqiu.fuckets100.SettingsManager
private const val TAG = "ReadScreen"


/**
 * 阅读界面 - 显示ETS 100答案的阅读界面
 * 支持多种激活模式（Shizuku、Root、SAF等）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(
    currentMode: ActivationMode,
    accountOnline: Boolean = true,
    readAccessChecking: Boolean = false,
    readAccessIssue: String? = null,
    onNavigateToActivation: () -> Unit,
    onOpenPaperDetail: (ETS100AnswerReader.Paper) -> Unit,
    onOpenChangyanLogin: () -> Unit = {},
    compactAnswerDisplay: Boolean = SettingsManager.getCompactAnswerDisplay(),
    viewModel: ReadViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val dataFiles = uiState.dataFiles
    val resourceFiles = uiState.resourceFiles
    val readerInfo = uiState.readerInfo
    val papers = uiState.papers
    val isLoading = uiState.isLoading
    val isParsingLocalAnswers = uiState.isParsingLocalAnswers
    val errorMessage = uiState.errorMessage
    fun copyPaperText(paper: ETS100AnswerReader.Paper) {
        val (available, reason) = viewModel.isFeatureAvailable(FeAccountFeatures.ANSWER_SHARE)
        if (!available) {
            android.widget.Toast.makeText(context, reason ?: "答案分享功能当前不可用", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val text = formatPaperAsText(paper)
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(paper.title, text))
        android.widget.Toast.makeText(context, "已复制答案到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
    }

    var selectedPaper by remember { mutableStateOf<ETS100AnswerReader.Paper?>(null) }
    var selectedSectionIndex by remember { mutableIntStateOf(-1) }
    var selectedQuestionIndex by remember { mutableIntStateOf(-1) }

    // 二级页面导航状态 - 宝贝用于切换试卷详情页面喵~
    var showPaperDetail by remember { mutableStateOf(false) }

    // 页面级交互状态
    var sourceExportPaper by remember { mutableStateOf<ETS100AnswerReader.Paper?>(null) }
    var showLocalDirectoryExport by remember { mutableStateOf(false) }
    var showCloudDirectoryExport by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }  // 宝贝添加了删除确认对话框状态喵~

    // 可展开 FAB 相关状态
    var showCloudReadConfirmDialog by remember { mutableStateOf(false) }
    var showReadActionsMenu by remember { mutableStateOf(false) }
    var errorDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    val paperListState = key(currentMode) { rememberLazyListState() }
    val readFabExpanded by remember(paperListState) {
        derivedStateOf {
            paperListState.firstVisibleItemIndex == 0 && paperListState.firstVisibleItemScrollOffset < 100
        }
    }

    val selectedCloudCategory = uiState.selectedCloudCategory
    val selectedCloudHomeworkStatus = uiState.selectedCloudHomeworkStatus
    val homeworkListsByStatus = uiState.homeworkListsByStatus
    val isLoadingCloudHomework = uiState.isLoadingCloudHomework
    val cloudHomeworkError = uiState.cloudHomeworkError
    val downloadedPaperMap = uiState.downloadedPapers
    val downloadedHomeworkNames = uiState.downloadedHomeworkNames
    val cloudDownloadingHomeworks = uiState.cloudDownloadingHomeworks
    val cloudDownloadProgress = uiState.cloudDownloadProgress
    val failedCloudHomeworks = uiState.failedCloudHomeworks
    val isDeleting = uiState.isDeleting

    // 宝贝新增：云端作业占位符列表喵~ 支持当前、历史及词汇PK三类视图
    val homeworkList = remember(selectedCloudCategory, homeworkListsByStatus) {
        ReadDerivedState.homeworkList(selectedCloudCategory, homeworkListsByStatus)
    }
    val selectedCloudHomeworkLabel = remember(selectedCloudCategory) {
        ReadDerivedState.categoryLabel(selectedCloudCategory)
    }
    val isCurrentCategoryLoaded = remember(selectedCloudCategory, homeworkListsByStatus) {
        ReadDerivedState.isCategoryLoaded(selectedCloudCategory, homeworkListsByStatus)
    }
    val cloudHomeworkPlaceholders = remember(homeworkList) {
        homeworkList.map { hw -> createCloudHomeworkPlaceholder(hw) }
    }
    val loadedLocalSectionCount = remember(papers) { papers.totalSectionCount() }
    val loadedLocalQuestionCount = remember(papers) { papers.totalQuestionCount() }
    val downloadedCloudPapers = remember(downloadedPaperMap) {
        ReadDerivedState.downloadedCloudPapers(downloadedPaperMap)
    }
    val downloadedCloudQuestionCount = remember(downloadedCloudPapers) {
        downloadedCloudPapers.totalQuestionCount()
    }
    val shouldOpenChangyanLoginDirectly = remember(currentMode, cloudHomeworkError) {
        currentMode == ActivationMode.CLOUD &&
            cloudHomeworkError != null &&
            ETS100AuthManager.isChangyanWebLogin(context)
    }

    val categoryColors = answerCategoryColors()

    // 云端读取副作用（Toast / 打开详情 / 清理选择）由 ViewModel 事件通道驱动。
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ReadEffect.ShowToast -> Toast.makeText(
                    context,
                    effect.message,
                    if (effect.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()

                is ReadEffect.OpenPaperDetail -> onOpenPaperDetail(effect.paper)

                ReadEffect.ClearPaperSelection -> {
                    selectedPaper = null
                    showPaperDetail = false
                }

                is ReadEffect.ShowError -> errorDialog = effect.title to effect.message
            }
        }
    }

    // 详细错误弹窗：可滚动查看并一键复制，方便反馈给开发者（日志里也有同样内容）。
    errorDialog?.let { (title, message) ->
        DetailedErrorDialog(title = title, message = message, onDismiss = { errorDialog = null })
    }

    LaunchedEffect(currentMode, uiState.reloadTrigger, accountOnline, readAccessChecking, readAccessIssue, viewModel) {
        viewModel.loadLocal(
            mode = currentMode,
            readAccessChecking = readAccessChecking,
            readAccessIssue = readAccessIssue,
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    val loadCloudHomework: () -> Unit = {
        scope.launch {
            isRefreshing = true
            try {
                viewModel.loadCloudHomeworkList(accountOnline = accountOnline)
            } finally {
                isRefreshing = false
            }
        }
    }
    val requestCloudRead: () -> Unit = {
        requestCloudReadWithWarning(
            skipWarning = SettingsManager.getSkipCloudReadWarning(),
            onWarningRequired = { showCloudReadConfirmDialog = true },
            onRead = loadCloudHomework,
        )
    }

    val onRefresh: () -> Unit = {
        if (readAccessChecking || readAccessIssue != null) {
            onNavigateToActivation()
        } else if (currentMode == ActivationMode.CLOUD && !accountOnline) {
            Toast.makeText(context, "请登录E听说账号", Toast.LENGTH_SHORT).show()
        } else if (currentMode == ActivationMode.CLOUD) {
            requestCloudRead()
        } else {
            scope.launch {
                isRefreshing = true
                try {
                    viewModel.reloadLocalWithClearedCache(currentMode)
                    delay(400)
                } finally {
                    isRefreshing = false
                }
            }
        }
    }

    ExpressiveScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal
        ),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("读取答案") },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showReadActionsMenu = true },
                            enabled = currentMode != ActivationMode.CLOUD || accountOnline,
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = showReadActionsMenu,
                            onDismissRequest = { showReadActionsMenu = false },
                        ) {
                            if (currentMode in setOf(
                                    ActivationMode.ROOT,
                                    ActivationMode.SHIZUKU,
                                    ActivationMode.DIRECT_READ,
                                )
                            ) {
                                DropdownMenuItem(
                                    text = { Text("导出本地源文件") },
                                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                    onClick = {
                                        showReadActionsMenu = false
                                        showLocalDirectoryExport = true
                                    },
                                )
                            } else if (currentMode == ActivationMode.CLOUD) {
                                DropdownMenuItem(
                                    text = { Text("导出云端缓存文件") },
                                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                    onClick = {
                                        showReadActionsMenu = false
                                        showCloudDirectoryExport = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (currentMode == ActivationMode.CLOUD) "清除云端缓存" else "清除本地缓存") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    showReadActionsMenu = false
                                    showDeleteConfirmDialog = true
                                },
                            )
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                ),
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                expanded = readFabExpanded,
                onClick = {
                    if (readAccessChecking || readAccessIssue != null) {
                        onNavigateToActivation()
                        return@SmallExtendedFloatingActionButton
                    }
                    if (currentMode == ActivationMode.CLOUD && !accountOnline) {
                        Toast.makeText(context, "请登录E听说账号", Toast.LENGTH_SHORT).show()
                        return@SmallExtendedFloatingActionButton
                    }
                    if (currentMode == ActivationMode.CLOUD) {
                        requestCloudRead()
                    } else {
                        viewModel.reloadLocalWithClearedCache(currentMode)
                    }
                },
                icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                text = { Text(if (currentMode == ActivationMode.CLOUD) "读取作业" else "重新读取") },
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            state = pullRefreshState,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    modifier = Modifier
                        .padding(top = paddingValues.calculateTopPadding())
                        .align(Alignment.TopCenter),
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
            when {
                // 1. 读取前置条件不满足时直接显示可操作提示，不进入底层读取流程。
                readAccessChecking || readAccessIssue != null -> {
                    ReadAccessGate(
                    readAccessChecking = readAccessChecking,
                    readAccessIssue = readAccessIssue,
                    onNavigateToActivation = onNavigateToActivation,
                )
                }

                // 2. 加载中（本地模式 isLoading 或 云端模式 isLoadingCloudHomework）喵~
                isLoading || (currentMode == ActivationMode.CLOUD && isLoadingCloudHomework) -> {
                    ReadLoadingState(
                    currentMode = currentMode,
                    localPhase = uiState.localProgress.phase,
                    downloadedCloudPaperCount = downloadedCloudPapers.size,
                    downloadedCloudQuestionCount = downloadedCloudQuestionCount,
                    localPaperCount = papers.size,
                    localSectionCount = loadedLocalSectionCount,
                    localQuestionCount = loadedLocalQuestionCount,
                )
                }

                // 2. 错误状态（本地 errorMessage 或 云端 cloudHomeworkError）喵~
                errorMessage != null || cloudHomeworkError != null -> {
                    ReadErrorState(
                    message = errorMessage ?: cloudHomeworkError ?: "未知错误",
                    actionLabel = if (shouldOpenChangyanLoginDirectly) "前往登录" else "前往激活",
                    onAction = {
                        if (shouldOpenChangyanLoginDirectly) {
                            onOpenChangyanLogin()
                        } else {
                            onNavigateToActivation()
                        }
                    },
                )
                }

                // 3. 云端模式：作业列表非空，显示作业列表或答案详情喵~
                currentMode == ActivationMode.CLOUD -> {
                    ReadCloudContent(
                        showPaperDetail = showPaperDetail,
                        selectedPaper = selectedPaper,
                        onCloseDetail = {
                            showPaperDetail = false
                            selectedPaper = null
                        },
                        onOpenDetail = onOpenPaperDetail,
                        onCopyText = ::copyPaperText,
                        onExportSource = { sourceExportPaper = it },
                        onCategorySelect = viewModel::selectCloudCategory,
                        onDownloadHomework = { info, parseStatus ->
                            viewModel.requestHomeworkDownload(
                                homeworkInfo = info,
                                status = parseStatus,
                                accountOnline = accountOnline,
                            )
                        },
                        categoryColors = categoryColors,
                        compactAnswerDisplay = compactAnswerDisplay,
                        paperListState = paperListState,
                        selectedCloudHomeworkStatus = selectedCloudHomeworkStatus,
                        selectedCloudCategory = selectedCloudCategory,
                        homeworkList = homeworkList,
                        isCurrentCategoryLoaded = isCurrentCategoryLoaded,
                        selectedCloudHomeworkLabel = selectedCloudHomeworkLabel,
                        downloadedHomeworkNames = downloadedHomeworkNames,
                        downloadedPaperMap = downloadedPaperMap,
                        cloudDownloadingHomeworks = cloudDownloadingHomeworks,
                        failedCloudHomeworks = failedCloudHomeworks,
                        cloudDownloadProgress = cloudDownloadProgress,
                        accountOnline = accountOnline,
                    )
                }

                papers.isEmpty() && homeworkList.isEmpty() -> {
                    ReadEmptyContent()
                }

                else -> {
                    ReadLocalContent(
                        showPaperDetail = showPaperDetail,
                        selectedPaper = selectedPaper,
                        onCloseDetail = {
                            showPaperDetail = false
                            selectedPaper = null
                        },
                        onOpenDetail = onOpenPaperDetail,
                        onCopyText = ::copyPaperText,
                        onExportSource = { sourceExportPaper = it },
                        categoryColors = categoryColors,
                        compactAnswerDisplay = compactAnswerDisplay,
                        paperListState = paperListState,
                        localProgress = uiState.localProgress,
                        papers = papers,
                    )
                }
            }

            // 宝贝删除确认对话框喵~
            if (showDeleteConfirmDialog) {
                DeleteConfirmDialog(
                    onDismiss = { showDeleteConfirmDialog = false },
                    onConfirm = {
                        showDeleteConfirmDialog = false
                        // 宝贝检查模式喵~
                        if (currentMode == ActivationMode.CLOUD) {
                            // 云端模式：清除缓存喵~
                            viewModel.clearCloudHomeworkCache()
                        } else {
                            // 本地模式：删除 data/resource 目录后重新读取喵~
                            viewModel.deleteLocalReadDirectories(currentMode)
                        }
                    },
                    isCloudMode = currentMode == ActivationMode.CLOUD
                )
            }

            if (showCloudReadConfirmDialog) {
                CloudReadConfirmDialog(
                    onDismiss = { showCloudReadConfirmDialog = false },
                    onConfirm = {
                        showCloudReadConfirmDialog = false
                        loadCloudHomework()
                    }
                )
            }

            if (isDeleting) {
                DeletingOverlay()
            }
        }
    }
}
    ReadDialogHosts(
        currentMode = currentMode,
        papers = papers,
        isParsingLocalAnswers = isParsingLocalAnswers,
        downloadedPaperMap = downloadedPaperMap,
        cloudDownloadingHomeworks = cloudDownloadingHomeworks,
        sourceExportPaper = sourceExportPaper,
        onClearSourceExport = { sourceExportPaper = null },
        showLocalDirectoryExport = showLocalDirectoryExport,
        onDismissLocalDirectoryExport = { showLocalDirectoryExport = false },
        showCloudDirectoryExport = showCloudDirectoryExport,
        onDismissCloudDirectoryExport = { showCloudDirectoryExport = false },
    )
}
