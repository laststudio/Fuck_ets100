package com.shuaiqiu.fuckets100

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

private const val TAG = "ReadScreen"

// ============================================================================
// 调试日志系统 - 用于跟踪应用内数据和答案的读取过程
// ============================================================================

/**
 * 日志级别枚举
 * 定义日志的重要程度和显示颜色
 */
private enum class LogLevel(val label: String, val colorHex: Long) {
    DEBUG("调试", 0xFF60A5FA),      // 蓝色 - 调试信息
    INFO("信息", 0xFFFFD93D),       // 黄色 - 一般信息
    SUCCESS("成功", 0xFF2DD4BF),    // 青色 - 成功状态
    WARN("警告", 0xFFFB923C),       // 橙色 - 警告信息
    ERROR("错误", 0xFFFF6B6B),      // 红色 - 错误信息
    INIT("初始化", 0xFFE879F9)      // 紫色 - 初始化相关
}

/**
 * 日志类别枚举
 * 用于分组显示不同类型的数据
 */
private enum class LogCategory(val label: String) {
    SYSTEM("系统"),
    FILE("文件"),
    PAPER("试卷"),
    SECTION("分区"),
    QUESTION("题目"),
    ANSWER("答案")
}

/**
 * 日志条目数据结构
 * 用于结构化存储日志信息，支持彩色显示
 */
private data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val category: LogCategory,
    val message: String
) {
    /**
     * 将日志条目格式化为显示文本
     * 格式: [时间] [级别] [类别] 消息
     */
    fun toDisplayString(): String = "[$timestamp] [${level.label}] [${category.label}] $message"
}

/**
 * 初始化结果密封类
 * 用于安全地在 IO 线程和主线程之间传递数据
 */
private sealed class InitResult {
    data class Success(
        val readerInfo: String,
        val dataFiles: List<ETS100FileReader.FileItem>,
        val resourceFiles: List<ETS100FileReader.FileItem>,
        val papers: List<ETS100AnswerReader.Paper>
    ) : InitResult()
    
    data class Error(val message: String) : InitResult()
}

// ========== 云端模式辅助函数喵~ ==========

/**
 * 创建云端作业的占位 Paper 对象
 * 用于在列表中显示"未加载"状态喵~
 */
private fun createCloudHomeworkPlaceholder(homework: ETS100ApiClient.HomeworkInfo): ETS100AnswerReader.Paper {
    return ETS100AnswerReader.Paper(
        paperId = homework.name.hashCode().toLong(),
        title = homework.name,
        dataFileName = "",
        fileSize = 0L,
        sections = listOf(
            ETS100AnswerReader.Section(
                caption = "云端作业",
                category = "cloud_homework",
                typeName = "未加载",
                questions = emptyList(),
                originalContent = null
            )
        ),
        downloadTime = 0L,
        regionLabel = "云端",
        paperName = homework.name
    )
}

/**
 * 阅读界面 - 显示ETS 100答案的阅读界面
 * 支持多种激活模式（Shizuku、Root、SAF等）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(
    currentMode: ActivationMode,
    onNavigateToSettings: () -> Unit,
    onNavigateToShare: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 调试相关状态 - 使用结构化日志系统
    var showDebugPanel by remember { mutableStateOf(false) }
    var debugLog by remember { mutableStateOf(listOf<LogEntry>()) }
    var showDataDetails by remember { mutableStateOf(false) }  // 是否显示详细数据信息
    
    // 文件列表状态
    var dataFiles by remember { mutableStateOf<List<ETS100FileReader.FileItem>>(emptyList()) }
    var resourceFiles by remember { mutableStateOf<List<ETS100FileReader.FileItem>>(emptyList()) }
    var readerInfo by remember { mutableStateOf("") }
    
    // 试卷和题目状态
    var papers by remember { mutableStateOf<List<ETS100AnswerReader.Paper>>(emptyList()) }
    var selectedPaper by remember { mutableStateOf<ETS100AnswerReader.Paper?>(null) }
    var selectedSectionIndex by remember { mutableIntStateOf(-1) }
    var selectedQuestionIndex by remember { mutableIntStateOf(-1) }
    
    // 二级页面导航状态 - 宝贝用于切换试卷详情页面喵~
    var showPaperDetail by remember { mutableStateOf(false) }
    
    // 加载状态
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }  // 宝贝添加了重新加载触发器喵~
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }  // 宝贝添加了删除确认对话框状态喵~
    
    // 展开状态 - 宝贝现在使用 PaperListItem，不需要这些展开状态了喵~
    // var expandedPapers by remember { mutableStateOf(setOf<Int>()) }
    // var expandedSections by remember { mutableStateOf(setOf<Pair<Int, Int>>()) }
    // 搜索相关状态 - 已移除喵~
    // var questionSearchQuery by remember { mutableStateOf("") }
    // var showOnlyAnswered by remember { mutableStateOf(false) }
    
    // 可展开 FAB 相关状态
    var isFabExpanded by remember { mutableStateOf(false) }

    // ========== 云端模式状态喵~ 宝贝这些状态现在保存在单例中，Tab 切换不丢失喵~
    var homeworkList by remember { mutableStateOf(CloudHomeworkState.homeworkList) }
    var isLoadingCloudHomework by remember { mutableStateOf(CloudHomeworkState.isLoading) }
    var cloudHomeworkError by remember { mutableStateOf(CloudHomeworkState.error) }
    var cloudBaseUrl by remember { mutableStateOf(CloudHomeworkState.cloudBaseUrl) }
    var downloadedPapers by remember { mutableStateOf(CloudHomeworkState.downloadedPapers) }
    var downloadedHomeworkNames by remember { mutableStateOf(CloudHomeworkState.downloadedHomeworkNames) }
    var cloudDownloadingHomeworks by remember { mutableStateOf(CloudHomeworkState.cloudDownloadingHomeworks) }
    var failedCloudHomeworks by remember { mutableStateOf(CloudHomeworkState.failedCloudHomeworks) }

    // 宝贝新增：云端作业占位符列表喵~ 现在显示所有作业，包括已下载的喵~
    val cloudHomeworkPlaceholders by remember {
        derivedStateOf {
            homeworkList.map { hw -> createCloudHomeworkPlaceholder(hw) }
        }
    }
    
    // 分区颜色映射 - 宝贝这个和试卷详情页面的颜色一致喵~
    val categoryColors = mapOf(
        "read_chapter" to Color(0xFF6366F1),        // 紫色 - 模仿朗读
        "simple_expression_ufi" to Color(0xFF22C55E), // 绿色 - 听说信息
        "simple_expression_ufk" to Color(0xFFF59E0B), // 橙色 - 问答信息
        "topic" to Color(0xFF3B82F6),              // 蓝色 - 信息转述
        "simple_expression_ufj" to Color(0xFFEC4899)  // 粉色 - 询问信息
    )
    
    // 时间戳格式化器
    val timeFormatter = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
    
    /**
     * 添加结构化调试日志
     * 宝贝这个函数会自动带上时间戳和分类信息喵~
     */
    fun addLog(
        level: LogLevel = LogLevel.INFO,
        category: LogCategory = LogCategory.SYSTEM,
        message: String
    ) {
        val timestamp = timeFormatter.format(java.util.Date())
        val entry = LogEntry(timestamp = timestamp, level = level, category = category, message = message)
        debugLog = debugLog + entry
    }

    // ========== 云端模式辅助函数喵~ ==========

    /**
     * 加载云端作业列表
     */
    suspend fun loadCloudHomeworkList() {
        if (!ETS100AuthManager.isLoggedIn(context)) {
            cloudHomeworkError = "未登录，请先在设置中登录云端账号"
            addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 未登录云端账号")
            return
        }

        var token = ETS100AuthManager.getToken(context)
        val parentId = ETS100AuthManager.getParentAccountId(context)

        if (token == null || parentId == null) {
            cloudHomeworkError = "登录信息不完整，请重新登录"
            addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 登录信息不完整")
            return
        }

        isLoadingCloudHomework = true
        cloudHomeworkError = null
        CloudHomeworkState.isLoading = true
        CloudHomeworkState.error = null
        addLog(LogLevel.INFO, LogCategory.SYSTEM, "☁️ 开始加载云端作业列表")

        var lastError: Throwable? = null
        var success = false
        val tokenNonNull = token!!

        addLog(LogLevel.INFO, LogCategory.SYSTEM, "🔐 自动重新登录获取最新 Token...")
        var currentToken: String? = null
        val phone = ETS100AuthManager.getPhone(context)
        val savedPassword = ETS100AuthManager.getPassword(context)
        val deviceCode = ETS100AuthManager.getDeviceCode(context)

        if (phone != null && savedPassword != null) {
            try {
                val loginResult = ETS100ApiClient.login(phone, savedPassword, deviceCode)
                loginResult.onSuccess { loginResponse ->
                    val ecardResult = ETS100ApiClient.getEcardList(loginResponse.token)
                    ecardResult.onSuccess { parentAccountId ->
                        ETS100AuthManager.saveLoginInfo(context, phone, loginResponse.token, parentAccountId)
                        currentToken = loginResponse.token
                        addLog(LogLevel.INFO, LogCategory.SYSTEM, "✓ 登录成功，Token 已更新喵~")
                    }.onFailure { e ->
                        addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 获取父账户ID失败: ${e.message}")
                    }
                }.onFailure { e ->
                    addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 登录失败: ${e.message}")
                    addLog(LogLevel.ERROR, LogCategory.SYSTEM, "请在设置中重新登录")
                }
            } catch (e: ETS100ApiClient.DeviceBindRequiredException) {
                addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 设备需要重新绑定，请在设置中重新登录")
            }
        } else {
            addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 未保存密码，请先在设置中登录")
        }

        val tokenForRequest = currentToken
        if (tokenForRequest == null) {
            cloudHomeworkError = "获取 Token 失败，请重新登录"
            addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 获取 Token 失败，无法加载作业列表")
            isLoadingCloudHomework = false
            return
        }

        val result = ETS100ApiClient.getHomeworkList(tokenForRequest, parentId)
        result.onSuccess { response ->
            homeworkList = response.homeworks
            cloudBaseUrl = response.baseUrl
            CloudHomeworkState.homeworkList = response.homeworks
            CloudHomeworkState.cloudBaseUrl = response.baseUrl
            addLog(LogLevel.SUCCESS, LogCategory.SYSTEM, "✓ 获取到 ${homeworkList.size} 个云端作业")
            addLog(LogLevel.INFO, LogCategory.SYSTEM, "📡 API 返回的 base_url = ${response.baseUrl}")
            success = true
        }.onFailure { e ->
            lastError = e
            addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 获取作业列表失败: ${e.message}")
        }

        if (!success) {
            cloudHomeworkError = lastError?.message ?: "获取作业列表失败"
            CloudHomeworkState.error = cloudHomeworkError
            addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 获取作业列表失败: $cloudHomeworkError")
        }

        isLoadingCloudHomework = false
        CloudHomeworkState.isLoading = false
    }

    /**
     * 清除云端作业缓存
     * 宝贝删除所有已下载的云端作业文件喵~
     */
    fun clearCloudHomeworkCache() {
        val cacheDir = File(context.cacheDir, "cloud_homework")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            addLog(LogLevel.INFO, LogCategory.SYSTEM, "🗑️ 已删除云端缓存目录")
        }
        downloadedPapers = emptyMap()
        downloadedHomeworkNames = emptySet()
        papers = emptyList()
        homeworkList = emptyList()
        CloudHomeworkState.clear()
        Toast.makeText(context, "已清除所有云端缓存", Toast.LENGTH_SHORT).show()
    }

    /**
     * 下载并解析云端作业
     * 宝贝这个函数处理下载、解压和解析的全流程喵~
     */
    suspend fun downloadAndParseHomework(
        homeworkInfo: ETS100ApiClient.HomeworkInfo
    ) {
        addLog(LogLevel.INFO, LogCategory.SYSTEM, "📥 开始下载作业: ${homeworkInfo.name}, 共 ${homeworkInfo.contents.size} 个内容")

        val cacheKey = homeworkInfo.name
        if (downloadedPapers.containsKey(cacheKey)) {
            addLog(LogLevel.INFO, LogCategory.SYSTEM, "📁 缓存命中，作业已下载喵~")
            Toast.makeText(context, "该作业已下载喵~", Toast.LENGTH_SHORT).show()
            return
        }

        cloudDownloadingHomeworks = cloudDownloadingHomeworks + homeworkInfo.name
        failedCloudHomeworks = failedCloudHomeworks - homeworkInfo.name
        CloudHomeworkState.cloudDownloadingHomeworks = cloudDownloadingHomeworks
        CloudHomeworkState.failedCloudHomeworks = failedCloudHomeworks

        try {
            val cacheDir = File(context.cacheDir, "cloud_homework")
            cacheDir.mkdirs()
            val allSections = mutableListOf<ETS100AnswerReader.Section>()
            var questionIndex = 0

            for (content in homeworkInfo.contents) {
                // 1. 构造完整 URL
                val zipUrl = if (content.url.startsWith("http")) {
                    content.url.replaceFirst("http://", "https://")
                } else {
                    var baseUrl = cloudBaseUrl
                    if (baseUrl.startsWith("http://")) {
                        baseUrl = baseUrl.replaceFirst("http://", "https://")
                    }
                    val fullUrl = if (content.url.startsWith("/")) content.url else "/${content.url}"
                    baseUrl.trimEnd('/') + fullUrl
                }
                val zipFileName = zipUrl.substringAfterLast('/').substringBefore('?')
                if (zipFileName.isEmpty()) {
                    addLog(LogLevel.WARN, LogCategory.SYSTEM, "⚠️ 无法从 URL 提取文件名: ${content.url}")
                    continue
                }
                addLog(LogLevel.INFO, LogCategory.SYSTEM, "⬇️ 下载 ${content.groupName}: $zipFileName")

                val zipFile = File(cacheDir, zipFileName)
                if (zipFile.exists() && zipFile.length() > 0) {
                    addLog(LogLevel.INFO, LogCategory.SYSTEM, "📦 文件已存在，跳过下载: ${zipFileName}")
                } else {
                    val downloadResult = ETS100ApiClient.downloadFile(zipUrl, zipFile)
                    downloadResult.onFailure { e ->
                        addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 下载失败 ${content.groupName}: ${e.message}")
                    }
                    if (downloadResult.isFailure) {
                        addLog(LogLevel.WARN, LogCategory.SYSTEM, "⏭️ 跳过失败的下载: ${content.groupName}")
                        continue
                    }
                    addLog(LogLevel.INFO, LogCategory.SYSTEM, "✅ 下载完成: ${zipFile.length()} bytes")
                }

                // 2. 验证 ZIP Magic
                val fis = java.io.FileInputStream(zipFile)
                val magic = ByteArray(4)
                fis.read(magic)
                fis.close()
                val magicHex = magic.joinToString("") { "%02X".format(it) }
                if (magicHex != "504B0304" && magicHex != "504B0506" && magicHex != "504B0708") {
                    addLog(LogLevel.ERROR, LogCategory.SYSTEM, "⚠️ 文件不是有效 ZIP: $magicHex")
                    continue
                }

                // 3. 生成密码 + 解压
                val password = ZipPasswordGenerator.generatePassword(zipFile.absolutePath)
                if (password == null) {
                    addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 无法生成解压密码: ${zipFileName}")
                    continue
                }
                addLog(LogLevel.DEBUG, LogCategory.SYSTEM, "🔑 密码: ${password.substring(0, 8)}...")

                val extractDirName = zipFileName.removeSuffix(".zip")
                val extractDir = File(cacheDir, extractDirName)
                if (extractDir.exists()) {
                    extractDir.deleteRecursively()
                }
                extractDir.mkdirs()

                try {
                    val zip4jFile = net.lingala.zip4j.ZipFile(zipFile)
                    zip4jFile.setPassword(password.toCharArray())
                    zip4jFile.extractAll(extractDir.absolutePath)
                    addLog(LogLevel.DEBUG, LogCategory.SYSTEM, "📦 解压完成: $extractDirName")
                } catch (e: Exception) {
                    addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 解压失败 ${content.groupName}: ${e.message}")
                    continue
                }

                // 4. 直接从解压目录读取 content.json（不需要找子文件夹）
                addLog(LogLevel.INFO, LogCategory.SYSTEM, "📂 解压目录: ${extractDir.name}")

                // 列出解压目录中的所有文件/文件夹（帮助调试）
                extractDir.listFiles()?.forEach { item ->
                    val type = if (item.isDirectory) "📁" else "📄"
                    addLog(LogLevel.DEBUG, LogCategory.FILE, "  $type ${item.name}")
                }

                val contentJsonFile = File(extractDir, "content.json")
                if (!contentJsonFile.exists()) {
                    addLog(LogLevel.WARN, LogCategory.FILE, "⚠️ 解压目录中无 content.json，跳过")
                } else {
                    val contentJson = contentJsonFile.readText()
                    try {
                        val json = org.json.JSONObject(contentJson)
                        val structureType = json.optString("structure_type", "")

                        addLog(LogLevel.INFO, LogCategory.SECTION, "📄 解析 content.json")
                        addLog(LogLevel.INFO, LogCategory.SECTION, "   ├─ structure_type: $structureType")
                        addLog(LogLevel.INFO, LogCategory.SECTION, "   ├─ group_name: ${content.groupName}")

                        val (questions, originalContent) = ETS100AnswerReader.parseContentJson(
                            json = json,
                            startIndex = questionIndex,
                            category = "",
                            typeName = content.groupName,
                            sectionCaption = content.groupName
                        )

                        if (questions.isEmpty()) {
                            addLog(LogLevel.WARN, LogCategory.QUESTION, "   └─ ⚠️ 未解析到任何题目！")
                        } else {
                            addLog(LogLevel.SUCCESS, LogCategory.QUESTION, "   ├─ ✅ 解析到 ${questions.size} 道题")
                            for ((i, q) in questions.withIndex()) {
                                val isLast = i == questions.size - 1
                                val prefix = if (isLast) "   │   └─" else "   │   ├─"
                                addLog(LogLevel.INFO, LogCategory.QUESTION, "$prefix 第${q.order}题: ${q.questionText.take(60)}")
                                if (q.answers.isNotEmpty()) {
                                    for ((j, ans) in q.answers.withIndex()) {
                                        val ansPrefix = if (isLast) "   │       └─" else "   │       ├─"
                                        addLog(LogLevel.INFO, LogCategory.ANSWER, "$ansPrefix 答案${j+1}: ${ans.take(80)}")
                                    }
                                } else {
                                    val ansPrefix = if (isLast) "   │       └─" else "   │       ├─"
                                    addLog(LogLevel.WARN, LogCategory.ANSWER, "$ansPrefix 无标准答案")
                                }
                            }

                            allSections.add(ETS100AnswerReader.Section(
                                caption = content.groupName,
                                category = structureType,
                                typeName = content.groupName,
                                questions = questions,
                                originalContent = originalContent
                            ))
                            questionIndex += questions.size
                        }
                    } catch (e: Exception) {
                        addLog(LogLevel.ERROR, LogCategory.FILE, "   └─ ✗ 解析 content.json 失败: ${e.message}")
                    }
                }
            }

            if (allSections.isEmpty()) {
                throw Exception("未能解析到任何题目")
            }

            addLog(LogLevel.SUCCESS, LogCategory.SYSTEM, "📝 共解析到 ${allSections.size} 个题型，${questionIndex} 道题")

            val paper = ETS100AnswerReader.Paper(
                paperId = cacheKey.hashCode().toLong(),
                title = homeworkInfo.name,
                dataFileName = cacheKey,
                fileSize = 0L,
                sections = allSections
            )
            downloadedPapers = downloadedPapers + (cacheKey to listOf(paper))
            downloadedHomeworkNames = downloadedHomeworkNames + homeworkInfo.name
            cloudDownloadingHomeworks = cloudDownloadingHomeworks - homeworkInfo.name
            CloudHomeworkState.downloadedPapers = downloadedPapers
            CloudHomeworkState.downloadedHomeworkNames = downloadedHomeworkNames
            CloudHomeworkState.cloudDownloadingHomeworks = cloudDownloadingHomeworks
            // 宝贝下载成功后不再切换页面，只标记已下载，停留作业列表喵~
            Toast.makeText(context, "下载并解析成功！", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 下载解析失败: ${e.message}")
            Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            cloudDownloadingHomeworks = cloudDownloadingHomeworks - homeworkInfo.name
            failedCloudHomeworks = failedCloudHomeworks + homeworkInfo.name
            CloudHomeworkState.cloudDownloadingHomeworks = cloudDownloadingHomeworks
            CloudHomeworkState.failedCloudHomeworks = failedCloudHomeworks
        }
    }

    // 初始化加载 - 使用 rememberUpdatedState 确保最新的 currentMode
    // 宝贝添加了 reloadTrigger 依赖，这样点击读取按钮时就可以重新加载喵~
    val currentModeRef = remember { mutableStateOf(currentMode) }
    LaunchedEffect(currentMode, reloadTrigger) {
        currentModeRef.value = currentMode
        
        // 云端模式不执行本地加载，等待用户点击读取按钮喵~
        if (currentMode == ActivationMode.CLOUD) {
            addLog(LogLevel.INFO, LogCategory.SYSTEM, "☁️ 云端模式，等待用户点击读取按钮加载作业列表")
            isLoading = false
            return@LaunchedEffect
        }
        
        isLoading = true
        errorMessage = null
        debugLog = emptyList()  // 清空之前的日志喵~
        
        addLog(LogLevel.INIT, LogCategory.SYSTEM, "=".repeat(50))
        addLog(LogLevel.INIT, LogCategory.SYSTEM, "🚀 开始初始化 ETS 100 数据加载")
        addLog(LogLevel.INIT, LogCategory.SYSTEM, "当前激活模式: ${currentMode.name} (${currentMode.title})")
        addLog(LogLevel.INIT, LogCategory.SYSTEM, "=".repeat(50))
        
        try {
            // 检查是否启用了强执读取模式
            val forceReadMode = SettingsManager.getForceReadMode()
            if (forceReadMode) {
                addLog(LogLevel.WARN, LogCategory.SYSTEM, "⚡ 强执读取模式已启用，跳过权限检查")
            }
            
            // 检查模式是否可用（除非强制读取模式启用）
            addLog(LogLevel.DEBUG, LogCategory.SYSTEM, "检查模式可用性...")
            if (!forceReadMode && !ETS100FileReader.isModeAvailable(currentMode, context)) {
                addLog(LogLevel.ERROR, LogCategory.SYSTEM, "当前模式不可用: $currentMode")
                errorMessage = "当前模式不可用: $currentMode"
                isLoading = false
                return@LaunchedEffect
            }
            addLog(LogLevel.SUCCESS, LogCategory.SYSTEM, "✓ 模式检查通过" + if (forceReadMode) " (强执模式跳过检查)" else "")
            
            // 在 IO 线程执行文件操作
            addLog(LogLevel.INFO, LogCategory.SYSTEM, "开始 IO 操作...")
            val result = withContext(Dispatchers.IO) {
                try {
                    // 获取阅读器
                    addLog(LogLevel.DEBUG, LogCategory.FILE, "获取文件阅读器...")
                    val reader = ETS100FileReader.getReader(currentMode, context)
                    addLog(LogLevel.SUCCESS, LogCategory.FILE, "✓ 阅读器创建成功: ${reader.javaClass.simpleName}")
                    
                    // 获取路径信息
                    val dataDirPath = ETS100FileReader.Path.getDataDir()
                    val resourceDirPath = ETS100FileReader.Path.getResourceDir()
                    addLog(LogLevel.DEBUG, LogCategory.FILE, "数据目录: $dataDirPath")
                    addLog(LogLevel.DEBUG, LogCategory.FILE, "资源目录: $resourceDirPath")
                    
                    // 列出 data 文件
                    addLog(LogLevel.INFO, LogCategory.FILE, "扫描 data 目录...")
                    val dataFilesList = reader.listFiles(dataDirPath)
                        .filter { file -> !file.isDirectory && file.size > ETS100FileReader.Path.MIN_FILE_SIZE }
                        .sortedByDescending { it.lastModified }
                    addLog(LogLevel.SUCCESS, LogCategory.FILE, "✓ 找到 ${dataFilesList.size} 个数据文件")
                    if (dataFilesList.isNotEmpty()) {
                        addLog(LogLevel.DEBUG, LogCategory.FILE, "  最新文件: ${dataFilesList.first().name} (${formatFileSize(dataFilesList.first().size)})")
                    }
                    
                    // 列出 resource 文件
                    addLog(LogLevel.INFO, LogCategory.FILE, "扫描 resource 目录...")
                    val resourceFilesList = reader.listFiles(resourceDirPath)
                    addLog(LogLevel.SUCCESS, LogCategory.FILE, "✓ 找到 ${resourceFilesList.size} 个资源文件")
                    
                    // 读取试卷
                    addLog(LogLevel.INFO, LogCategory.PAPER, "=" .repeat(40))
                    addLog(LogLevel.INFO, LogCategory.PAPER, "📚 开始读取试卷数据...")
                    val paperList = ETS100AnswerReader.readPapers(context, currentMode)
                    addLog(LogLevel.SUCCESS, LogCategory.PAPER, "✓ 成功读取 ${paperList.size} 份试卷")
                    
                    // 详细遍历每份试卷
                    if (paperList.isNotEmpty()) {
                        addLog(LogLevel.INIT, LogCategory.PAPER, "-".repeat(40))
                        paperList.forEachIndexed { paperIndex, paper ->
                            addLog(LogLevel.INIT, LogCategory.PAPER, "📄 试卷 #${paperIndex + 1}: ${paper.title} [${paper.regionLabel}]")
                            addLog(LogLevel.DEBUG, LogCategory.PAPER, "   ID: ${paper.paperId}")
                            addLog(LogLevel.DEBUG, LogCategory.PAPER, "   分区数: ${paper.sections.size}")
                            
                            // 详细遍历每个分区
                            paper.sections.forEachIndexed { sectionIndex, section ->
                                addLog(LogLevel.DEBUG, LogCategory.SECTION, "   ├─ Section #${sectionIndex + 1}: ${section.title}")
                                addLog(LogLevel.DEBUG, LogCategory.SECTION, "   │   Category: ${section.category}")
                                addLog(LogLevel.DEBUG, LogCategory.SECTION, "   │   题目数: ${section.questions.size}")
                                
                                // 详细遍历每道题目
                                section.questions.take(3).forEachIndexed { qIndex, question ->
                                    val answerStatus = if (question.answer.isNotEmpty()) "✓" else "✗"
                                    addLog(
                                        if (question.answer.isNotEmpty()) LogLevel.SUCCESS else LogLevel.WARN,
                                        LogCategory.QUESTION,
                                        "   │   ├─ Q${qIndex + 1}: ${question.question.take(30)}... [答案: $answerStatus]"
                                    )
                                    if (question.answer.isNotEmpty()) {
                                        addLog(LogLevel.DEBUG, LogCategory.ANSWER, "   │   │   答案: ${question.answer.take(50)}")
                                    }
                                }
                                if (section.questions.size > 3) {
                                    addLog(LogLevel.WARN, LogCategory.QUESTION, "   │   └─ ... 还有 ${section.questions.size - 3} 道题目")
                                }
                            }
                            addLog(LogLevel.INIT, LogCategory.PAPER, "-".repeat(40))
                        }
                    }
                    
                    addLog(LogLevel.INIT, LogCategory.PAPER, "📊 试卷统计:")
                    addLog(LogLevel.INIT, LogCategory.PAPER, "   总试卷数: ${paperList.size}")
                    val totalSections = paperList.sumOf { it.sections.size }
                    val totalQuestions = paperList.sumOf { it.sections.sumOf { s -> s.questions.size } }
                    val answeredQuestions = paperList.sumOf { it.sections.sumOf { s -> s.questions.count { q -> q.answer.isNotEmpty() } } }
                    addLog(LogLevel.INIT, LogCategory.SECTION, "   总分区数: $totalSections")
                    addLog(LogLevel.INIT, LogCategory.QUESTION, "   总题目数: $totalQuestions")
                    addLog(LogLevel.SUCCESS, LogCategory.ANSWER, "   已答题目: $answeredQuestions (${(answeredQuestions * 100.0 / totalQuestions).toInt()}%)")
                    
                    InitResult.Success(
                        readerInfo = reader.toString(),
                        dataFiles = dataFilesList,
                        resourceFiles = resourceFilesList,
                        papers = paperList
                    )
                } catch (e: Exception) {
                    addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ IO 操作失败: ${e.message}")
                    InitResult.Error(e.message ?: "未知错误")
                }
            }
            
            addLog(LogLevel.INFO, LogCategory.SYSTEM, "处理加载结果...")
            when (result) {
                is InitResult.Success -> {
                    readerInfo = result.readerInfo
                    dataFiles = result.dataFiles
                    resourceFiles = result.resourceFiles
                    papers = result.papers
                    addLog(LogLevel.SUCCESS, LogCategory.SYSTEM, "✓ 初始化完成！数据加载成功~")
                }
                is InitResult.Error -> {
                    addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 加载失败: ${result.message}")
                    errorMessage = "加载失败: ${result.message}"
                }
            }
        } catch (e: Exception) {
            addLog(LogLevel.ERROR, LogCategory.SYSTEM, "✗ 初始化异常: ${e.message}")
            errorMessage = "初始化异常: ${e.message}"
        } finally {
            isLoading = false
            addLog(LogLevel.INIT, LogCategory.SYSTEM, "初始化流程结束喵~")
        }
    }
    
    // 调试面板 - 使用 Box 覆盖而不是直接 return
    if (showDebugPanel) {
        Box(modifier = Modifier.fillMaxSize()) {
            DebugPanel(
                debugLog = debugLog,
                papers = papers,
                showDataDetails = showDataDetails,
                onToggleDataDetails = { showDataDetails = !showDataDetails },
                onClear = { debugLog = emptyList() },
                onClose = { showDebugPanel = false }
            )
        }
        return@ReadScreen
    }
    
    Scaffold(
        topBar = {
            FeTopAppBar(title = "Fe")
        },
        floatingActionButton = {
            // 宝贝右下角的可展开圆形十字按钮喵~
            val hideDebugButton = SettingsManager.getHideDebugButton()
            ExpandableCrossFab(
                isExpanded = isFabExpanded,
                hideDebugButton = hideDebugButton,
                onToggleExpand = { isFabExpanded = !isFabExpanded },
                onDebugClick = {
                    isFabExpanded = false
                    showDebugPanel = true
                },
                onDeleteClick = {
                    isFabExpanded = false
                    showDeleteConfirmDialog = true  // 宝贝显示删除确认对话框喵~
                },
                onReadClick = {
                    isFabExpanded = false
                    if (currentMode == ActivationMode.CLOUD) {
                        // 云端模式：加载作业列表喵~ 不再清空状态，保留已下载的作业喵~
                        cloudHomeworkError = null
                        scope.launch { loadCloudHomeworkList() }
                    } else {
                        // 本地模式：重新加载本地试卷喵~
                        reloadTrigger++
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                // 1. 加载中（本地模式 isLoading 或 云端模式 isLoadingCloudHomework）喵~
                isLoading || (currentMode == ActivationMode.CLOUD && isLoadingCloudHomework) -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "正在加载试卷...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 2. 错误状态（本地 errorMessage 或 云端 cloudHomeworkError）喵~
                errorMessage != null || cloudHomeworkError != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                errorMessage ?: cloudHomeworkError ?: "未知错误",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = onNavigateToSettings) {
                                Text("前往设置")
                            }
                        }
                    }
                }

                // 3. 云端模式：作业列表非空，显示作业列表或答案详情喵~
                currentMode == ActivationMode.CLOUD && homeworkList.isNotEmpty() -> {
                    // 宝贝用 Crossfade 在作业列表和答案详情之间切换喵~
                    Crossfade(
                        targetState = showPaperDetail && selectedPaper != null,
                        animationSpec = tween(300),
                        label = "cloudPaperDetailCrossfade"
                    ) { showDetail ->
                        if (showDetail && selectedPaper != null) {
                            val paper = selectedPaper!!
                            PaperDetailScreen(
                                paper = paper,
                                onBack = {
                                    showPaperDetail = false
                                    selectedPaper = null
                                },
                                categoryColors = categoryColors,
                                onShare = {
                                    FeApplication.sharePaper = paper
                                    onNavigateToShare()
                                }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item { CloudModeInfoCard() }
                                itemsIndexed(homeworkList) { paperIndex, homeworkInfo ->
                                    val isDownloaded = downloadedHomeworkNames.contains(homeworkInfo.name)
                                    val isDownloading = cloudDownloadingHomeworks.contains(homeworkInfo.name)
                                    val isFailed = failedCloudHomeworks.contains(homeworkInfo.name)
                                    val paper = if (isDownloaded) {
                                        downloadedPapers[homeworkInfo.name]?.firstOrNull() ?: createCloudHomeworkPlaceholder(homeworkInfo)
                                    } else {
                                        createCloudHomeworkPlaceholder(homeworkInfo)
                                    }
                                    PaperListItem(
                                        paper = paper,
                                        paperIndex = paperIndex,
                                        onClick = {
                                            if (isDownloaded) {
                                                // 宝贝已下载，点击查看详情喵~
                                                downloadedPapers[homeworkInfo.name]?.firstOrNull()?.let { downloadedPaper ->
                                                    selectedPaper = downloadedPaper
                                                    showPaperDetail = true
                                                }
                                            } else if (!isDownloading) {
                                                // 宝贝未下载，开始下载喵~
                                                scope.launch { downloadAndParseHomework(homeworkInfo) }
                                            }
                                        },
                                        categoryColors = categoryColors,
                                        isLoading = isDownloading,
                                        isFailed = isFailed,
                                        isDownloaded = isDownloaded
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. 空列表（本地和云端统一处理）喵~
                papers.isEmpty() && homeworkList.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "未找到任何试卷",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "请确保ETS应用数据已正确配置",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                else -> {
                    // 宝贝使用 Crossfade 实现流畅的切换动画喵~
                    Crossfade(
                        targetState = showPaperDetail && selectedPaper != null,
                        animationSpec = tween(300),
                        label = "paperDetailCrossfade"
                    ) { showDetail ->
                        if (showDetail && selectedPaper != null) {
                            // 宝贝保存到局部变量避免 smart cast 问题喵~
                            val paper = selectedPaper!!
                            // 二级页面：显示试卷详情
                            PaperDetailScreen(
                                paper = paper,
                                onBack = {
                                    showPaperDetail = false
                                    selectedPaper = null
                                },
                                categoryColors = categoryColors,
                                onShare = {
                                    // 保存试卷到 FeApplication 并跳转到分享页面
                                    FeApplication.sharePaper = paper
                                        onNavigateToShare()
                                }
                            )
                        } else {
                            // 一级页面：显示试卷列表
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(papers) { paperIndex, paper ->
                                    PaperListItem(
                                        paper = paper,
                                        paperIndex = paperIndex,
                                        onClick = {
                                            selectedPaper = paper
                                            showPaperDetail = true
                                        },
                                        categoryColors = categoryColors
                                    )
                                }
                            }
                        }
                    }
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
                            clearCloudHomeworkCache()
                        } else {
                            // 本地模式：删除 data 和 resource 目录喵~
                            addLog(LogLevel.WARN, LogCategory.FILE, "🗑️ 开始删除 data 和 resource 目录...")

                            // 获取当前模式
                            val currentModeValue = ShizukuManager.getCurrentActivationMode()

                            // 删除 data 目录
                            val dataPath = ETS100FileReader.Path.getDataDir()
                            val dataDeleted = ETS100FileReader.deleteDirectory(currentModeValue, dataPath, context)
                            if (dataDeleted) {
                                addLog(LogLevel.SUCCESS, LogCategory.FILE, "✅ data 目录删除成功")
                            } else {
                                addLog(LogLevel.ERROR, LogCategory.FILE, "❌ data 目录删除失败")
                            }

                            // 删除 resource 目录
                            val resourcePath = ETS100FileReader.Path.getResourceDir()
                            val resourceDeleted = ETS100FileReader.deleteDirectory(currentModeValue, resourcePath, context)
                            if (resourceDeleted) {
                                addLog(LogLevel.SUCCESS, LogCategory.FILE, "✅ resource 目录删除成功")
                            } else {
                                addLog(LogLevel.ERROR, LogCategory.FILE, "❌ resource 目录删除失败")
                            }

                            // 刷新页面重新读取
                            reloadTrigger++
                        }
                    },
                    isCloudMode = currentMode == ActivationMode.CLOUD
                )
            }
        }
    }
}

/**
 * 可展开的圆形十字按钮组件
 * 右下角悬浮，点击向上展开3个子按钮（调试、删除、读取）
 * 喵~ 这个按钮会旋转和缩放动画哦！
 */
@Composable
private fun ExpandableCrossFab(
    isExpanded: Boolean,
    hideDebugButton: Boolean = false,
    onToggleExpand: () -> Unit,
    onDebugClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onReadClick: () -> Unit
) {
    // 主按钮旋转动画 - 展开时旋转45度变成X形状
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 45f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "fab_rotation"
    )
    
    // 子按钮的间距动画
    val subButtonSpacing = 16.dp
    
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 三个子按钮，向上展开喵~
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(200)) + slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(tween(150)) + slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            )
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 读取按钮 - 最上面
                SubFabItem(
                    icon = Icons.Default.Book,
                    label = "读取",
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    onClick = onReadClick
                )
                
                // 删除按钮 - 中间
                SubFabItem(
                    icon = Icons.Default.Delete,
                    label = "删除",
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = onDeleteClick
                )
                
                // 调试按钮 - 最下面（除非 hideDebugButton 为 true 才显示）
                if (!hideDebugButton) {
                    SubFabItem(
                        icon = Icons.Default.BugReport,
                        label = "调试",
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                        onClick = onDebugClick
                    )
                }
            }
        }
        
        // 主十字按钮喵~
        FloatingActionButton(
            onClick = onToggleExpand,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(56.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.rotate(rotationAngle)
            ) {
                // 十字图标 - 两条线交叉
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = if (isExpanded) "收回菜单" else "展开菜单",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * 子按钮组件
 * 小小的圆形按钮，带图标和文字标签
 */
@Composable
private fun SubFabItem(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.padding(end = 4.dp)
    ) {
        // 文字标签
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            shape = RoundedCornerShape(4.dp),
            shadowElevation = 2.dp
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // 小圆形按钮
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = contentColor,
            shape = CircleShape,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun PaperCard(
    paper: ETS100AnswerReader.Paper,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    expandedSections: Set<Pair<Int, Int>>,
    onSectionToggle: (Int) -> Unit,
    selectedSectionIndex: Int,
    selectedQuestionIndex: Int,
    onQuestionSelect: (Int, Int) -> Unit,
    categoryColors: Map<String, Color>,
    searchQuery: String,
    showOnlyAnswered: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            paper.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${paper.sections.size} 个分区 · ${paper.sections.sumOf { it.questions.size }} 道题目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text("${paper.sections.size}")
                }
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                
                paper.sections.forEachIndexed { sectionIndex, section ->
                    val sectionKey = Pair(paper.sections.indexOf(section), sectionIndex)
                    val isSectionExpanded = sectionKey in expandedSections
                    
                    SectionItem(
                        section = section,
                        sectionIndex = sectionIndex,
                        isExpanded = isSectionExpanded,
                        onToggleExpand = { onSectionToggle(sectionIndex) },
                        selectedQuestionIndex = if (selectedSectionIndex == sectionIndex) selectedQuestionIndex else -1,
                        onQuestionSelect = { onQuestionSelect(sectionIndex, it) },
                        categoryColor = categoryColors[section.category] ?: MaterialTheme.colorScheme.primary,
                        searchQuery = searchQuery,
                        showOnlyAnswered = showOnlyAnswered
                    )
                    
                    if (sectionIndex < paper.sections.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionItem(
    section: ETS100AnswerReader.Section,
    sectionIndex: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    selectedQuestionIndex: Int,
    onQuestionSelect: (Int) -> Unit,
    categoryColor: Color,
    searchQuery: String,
    showOnlyAnswered: Boolean
) {
    var expanded by remember { mutableStateOf(isExpanded) }
    
    LaunchedEffect(isExpanded) {
        expanded = isExpanded
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = categoryColor.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(4.dp, 24.dp)
                            .background(categoryColor, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${section.questions.size} 道题目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        section.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = categoryColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                
                section.questions.forEachIndexed { questionIndex, question ->
                    val matchesSearch = searchQuery.isEmpty() || 
                        question.question.contains(searchQuery, ignoreCase = true) ||
                        question.answer.contains(searchQuery, ignoreCase = true)
                    
                    val hasAnswer = question.answer.isNotEmpty()
                    
                    if (matchesSearch && (!showOnlyAnswered || hasAnswer)) {
                        QuestionItem(
                            question = question,
                            questionIndex = questionIndex,
                            isSelected = selectedQuestionIndex == questionIndex,
                            onClick = { onQuestionSelect(questionIndex) },
                            categoryColor = categoryColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionItem(
    question: ETS100AnswerReader.Question,
    questionIndex: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    categoryColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                categoryColor.copy(alpha = 0.2f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Q${questionIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = categoryColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    question.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (question.answer.isNotEmpty()) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "有答案",
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "无答案",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DebugPanel(
    debugLog: List<LogEntry>,
    papers: List<ETS100AnswerReader.Paper>,
    showDataDetails: Boolean,
    onToggleDataDetails: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    // 宝贝去除数据详情标签页喵~
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🐛 调试面板",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    TextButton(onClick = onClear) {
                        Text("清空", color = Color.Gray)
                    }
                    TextButton(onClick = onClose) {
                        Text("关闭", color = Color.White)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 宝贝只保留实时日志喵~
            LogViewerPanel(debugLog = debugLog)
        }
    }
}

/**
 * Tab 按钮组件
 */
@Composable
private fun RowScope.TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Color.White else Color.Gray
        )
    }
}

/**
 * 日志查看器面板
 * 使用颜色高亮不同级别的日志
 */
@Composable
private fun LogViewerPanel(debugLog: List<LogEntry>) {
    // 宝贝只显示 FILE 和 SYSTEM 类别的日志喵~
    val filteredLog = debugLog.filter { 
        it.category == LogCategory.FILE || it.category == LogCategory.SYSTEM 
    }
    
    if (filteredLog.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "📝 暂无操作日志",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "文件操作日志将显示在这里喵~",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
            }
        }
    } else {
        Column {
            // 日志统计信息 - 只显示 FILE 和 SYSTEM
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val fileCount = filteredLog.count { it.category == LogCategory.FILE }
                val systemCount = filteredLog.count { it.category == LogCategory.SYSTEM }
                val successCount = filteredLog.count { it.level == LogLevel.SUCCESS }
                val warnCount = filteredLog.count { it.level == LogLevel.WARN }
                val errorCount = filteredLog.count { it.level == LogLevel.ERROR }
                
                LogStatBadge("文件", fileCount, LogLevel.INFO)
                LogStatBadge("系统", systemCount, LogLevel.DEBUG)
                LogStatBadge("成功", successCount, LogLevel.SUCCESS)
                LogStatBadge("警告", warnCount, LogLevel.WARN)
                LogStatBadge("错误", errorCount, LogLevel.ERROR)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 日志列表 - 只显示过滤后的日志
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(filteredLog) { _, entry ->
                    LogEntryItem(entry = entry)
                }
            }
        }
    }
}

/**
 * 日志统计徽章
 */
@Composable
private fun LogStatBadge(label: String, count: Int, level: LogLevel) {
    val badgeColor = Color(level.colorHex)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFF3D3D3D), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = badgeColor
        )
    }
}

/**
 * 单条日志条目显示
 */
@Composable
private fun LogEntryItem(entry: LogEntry) {
    val textColor = Color(entry.level.colorHex)
    val categoryColor = when (entry.category) {
        LogCategory.SYSTEM -> Color(0xFFE879F9)   // 紫色
        LogCategory.FILE -> Color(0xFF60A5FA)    // 蓝色
        LogCategory.PAPER -> Color(0xFF6366F1)   // 靛蓝
        LogCategory.SECTION -> Color(0xFF22C55E) // 绿色
        LogCategory.QUESTION -> Color(0xFFFFB74D) // 橙色
        LogCategory.ANSWER -> Color(0xFF2DD4BF)  // 青色
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        // 时间戳
        Text(
            text = entry.timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF6B7280),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.width(85.dp)
        )
        
        // 级别标签
        Box(
            modifier = Modifier
                .width(50.dp)
                .background(textColor.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = entry.level.label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
        }
        
        Spacer(modifier = Modifier.width(6.dp))
        
        // 类别标签
        Box(
            modifier = Modifier
                .width(60.dp)
                .background(categoryColor.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = entry.category.label,
                style = MaterialTheme.typography.labelSmall,
                color = categoryColor
            )
        }
        
        Spacer(modifier = Modifier.width(6.dp))
        
        // 消息内容
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.level == LogLevel.ERROR) Color(0xFFFF6B6B) else Color.White,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 数据详情面板
 * 展示试卷、分区、题目的层级结构
 */
@Composable
private fun DataDetailsPanel(
    papers: List<ETS100AnswerReader.Paper>,
    showDataDetails: Boolean,
    onToggleDataDetails: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 控制栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D2D), RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleDataDetails)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (showDataDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "📂 显示完整数据结构",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
            Switch(
                checked = showDataDetails,
                onCheckedChange = { onToggleDataDetails() },
                modifier = Modifier
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (papers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无试卷数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 总体统计卡片
                item {
                    val totalSections = papers.sumOf { it.sections.size }
                    val totalQuestions = papers.sumOf { it.sections.sumOf { s -> s.questions.size } }
                    val answeredQuestions = papers.sumOf { it.sections.sumOf { s -> s.questions.count { q -> q.answer.isNotEmpty() } } }
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3D3D3D))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "📊 数据统计总览",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFE879F9),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem("试卷", papers.size, Color(0xFF6366F1))
                                StatItem("分区", totalSections, Color(0xFF22C55E))
                                StatItem("题目", totalQuestions, Color(0xFFFFB74D))
                                StatItem("已答", answeredQuestions, Color(0xFF2DD4BF))
                            }
                        }
                    }
                }
                
                // 每份试卷详情
                papers.forEachIndexed { paperIndex, paper ->
                    item {
                        PaperDetailCard(paper = paper, paperIndex = paperIndex)
                    }
                }
            }
        }
    }
}

/**
 * 统计项组件
 */
@Composable
private fun StatItem(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$value",
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
    }
}

/**
 * 试卷详情卡片
 */
@Composable
private fun PaperDetailCard(
    paper: ETS100AnswerReader.Paper,
    paperIndex: Int
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 试卷标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "📄",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = paper.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ID: ${paper.paperId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(
                        containerColor = Color(0xFF6366F1),
                        contentColor = Color.White
                    ) {
                        Text("${paper.sections.size} 分区")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Color.Gray
                    )
                }
            }
            
            // 展开的分区详情
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF4D4D4D))
                Spacer(modifier = Modifier.height(12.dp))
                
                paper.sections.forEachIndexed { sectionIndex, section ->
                    SectionDetailItem(section = section, sectionIndex = sectionIndex)
                    if (sectionIndex < paper.sections.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * 分区详情项
 */
@Composable
private fun SectionDetailItem(
    section: ETS100AnswerReader.Section,
    sectionIndex: Int
) {
    var expanded by remember { mutableStateOf(false) }
    
    val categoryColor = when (section.category) {
        "read_chapter" -> Color(0xFF6366F1)
        "simple_expression_ufi" -> Color(0xFF22C55E)
        "simple_expression_ufk" -> Color(0xFFF59E0B)
        "topic" -> Color(0xFF3B82F6)
        "simple_expression_ufj" -> Color(0xFFEC4899)
        else -> Color(0xFF60A5FA)
    }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = categoryColor.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(24.dp)
                            .background(categoryColor, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = section.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = categoryColor
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${section.questions.size} 题",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            // 展开的题目列表
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF4D4D4D))
                Spacer(modifier = Modifier.height(8.dp))
                
                section.questions.forEachIndexed { qIndex, question ->
                    QuestionDetailItem(
                        question = question,
                        questionIndex = qIndex,
                        categoryColor = categoryColor
                    )
                    if (qIndex < section.questions.size - 1) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

/**
 * 题目详情项
 */
@Composable
private fun QuestionDetailItem(
    question: ETS100AnswerReader.Question,
    questionIndex: Int,
    categoryColor: Color
) {
    var expanded by remember { mutableStateOf(false) }
    val hasAnswer = question.answer.isNotEmpty()
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (hasAnswer) Color(0xFF2DD4BF).copy(alpha = 0.1f) else Color(0xFF4D4D4D)
        ),
        modifier = Modifier.clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(categoryColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Q${questionIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = categoryColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            if (hasAnswer) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (hasAnswer) Color(0xFF2DD4BF) else Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = question.question,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            // 展开时显示完整信息
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF5D5D5D))
                Spacer(modifier = Modifier.height(8.dp))
                
                // 选项列表
                if (question.answerList.isNotEmpty()) {
                    Text(
                        text = "📝 选项列表:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    question.answerList.forEachIndexed { index, answer ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2DD4BF),
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = answer,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                }
                
                // 答案信息
                if (hasAnswer) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2DD4BF).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✓ 答案:",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF2DD4BF),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = question.answer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 可折叠项组件
 * 宝贝这个组件用于显示原文和答案的折叠项喵~
 */
@Composable
private fun CollapsibleItem(
    title: String,
    content: String,
    defaultExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * 题目块组件
 * 宝贝每个题目显示为一个块，包含题目信息和两个折叠项（原文、答案）喵~
 */
@Composable
private fun QuestionBlock(
    questionIndex: Int,
    sectionTitle: String,
    question: ETS100AnswerReader.Question,
    categoryColor: Color,
    defaultOriginalExpanded: Boolean = false,
    defaultAnswerExpanded: Boolean = false  // 宝贝答案默认折叠喵~
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 题目头部
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp, 32.dp)
                        .background(categoryColor, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Q${questionIndex + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = categoryColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = categoryColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                sectionTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = categoryColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                if (question.answer.isNotEmpty()) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "有答案",
                        tint = Color(0xFF22C55E),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 题目内容
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 原文折叠项
            if (!question.originalText.isNullOrEmpty()) {
                CollapsibleItem(
                    title = "📖 原文",
                    content = question.originalText!!,
                    defaultExpanded = defaultOriginalExpanded
                )
            }
            
            // 答案折叠项
            if (question.answerList.isNotEmpty()) {
                CollapsibleItem(
                    title = "✅ 答案",
                    content = question.answerList.joinToString("\n") { "${it}" },
                    defaultExpanded = defaultAnswerExpanded
                )
            }
        }
    }
}

/**
 * 试卷详情二级页面
 * 宝贝这个页面显示一个试卷的所有题目喵~
 * 已移除搜索功能，显示所有题目喵~
 */
@Composable
private fun PaperDetailScreen(
    paper: ETS100AnswerReader.Paper,
    onBack: () -> Unit,
    categoryColors: Map<String, Color>,
    onShare: () -> Unit = {}
) {
    // 宝贝添加返回手势支持喵~
    BackHandler(onBack = onBack)
    
    Column(modifier = Modifier.fillMaxSize()) {
        // 获取默认颜色 - 需要在 Composable 上下文中获取
        val defaultPrimaryColor = MaterialTheme.colorScheme.primary
        
        // 顶部导航栏
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "分享",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = paper.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${paper.sections.sumOf { it.questions.size }} 道题目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        HorizontalDivider()
        
        // 题目列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            paper.sections.forEachIndexed { sectionIndex, section ->
                val categoryColor = categoryColors[section.category] ?: defaultPrimaryColor
                
                // 宝贝已移除搜索过滤，显示所有题目喵~
                if (section.questions.isNotEmpty()) {
                    item {
                        // 分区标题
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp, 20.dp)
                                    .background(categoryColor, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = categoryColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = categoryColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = section.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = categoryColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    
// 宝贝按 originalText 分组显示题目喵~
                    val groupedQuestions = section.questions.groupBy { it.originalText }
                    
                    groupedQuestions.forEach { (originalText, questionsInGroup) ->
                        item {
                            MergedQuestionBlock(
                                sectionTitle = section.title,
                                questions = questionsInGroup,
                                categoryColor = categoryColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 合并题目块组件
 * 宝贝这个组件用于显示同一原文下的多个题目，将它们合并在一个大块中喵~
 */
@Composable
private fun MergedQuestionBlock(
    sectionTitle: String,
    questions: List<ETS100AnswerReader.Question>,
    categoryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 块头部 - 显示原文和题目数量
            val originalText = questions.firstOrNull()?.originalText
            if (!originalText.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(4.dp, 32.dp)
                            .background(categoryColor, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "📖 原文 (${questions.size} 题)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = categoryColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = categoryColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    sectionTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = categoryColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    
                    val hasAllAnswers = questions.all { it.answer.isNotEmpty() }
                    if (hasAllAnswers) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "全部有答案",
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 原文内容
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = originalText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = categoryColor.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // 题目列表
            questions.forEachIndexed { index, question ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                // 单个题目
                QuestionItemSimple(
                    questionIndex = index,
                    question = question,
                    categoryColor = categoryColor
                )
            }
        }
    }
}

/**
 * 简化题目项组件
 * 宝贝用于在合并块中显示单个题目喵~
 */
@Composable
private fun QuestionItemSimple(
    questionIndex: Int,
    question: ETS100AnswerReader.Question,
    categoryColor: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 题目编号和题目内容
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(categoryColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "Q${questionIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = categoryColor
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 题目内容
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = question.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 答案显示 - 使用可折叠组件喵~
        if (question.answerList.isNotEmpty()) {
            CollapsibleItem(
                title = "✅ 答案",
                content = question.answerList.joinToString(" | "),
                defaultExpanded = false
            )
        } else {
            // 模仿朗读类型显示特殊提示喵~
            val noAnswerText = if (question.category == "read_chapter") "（该类型无答案）" else "暂无答案"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    noAnswerText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 试卷列表项组件
 * 宝贝这是一个简单的试卷项目，点击后进入二级页面喵~
 */
@Composable
private fun PaperListItem(
    paper: ETS100AnswerReader.Paper,
    paperIndex: Int,
    onClick: () -> Unit,
    categoryColors: Map<String, Color>,
    isLoading: Boolean = false,
    isFailed: Boolean = false,
    isDownloaded: Boolean = false  // 宝贝标记是否已下载喵~
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    
    // 宝贝提取分区类型，只显示前3个喵~
    val sectionTypesList = paper.sections.map { it.typeName }.distinct()
    val displayedTypes = sectionTypesList.take(3)
    val remainingCount = sectionTypesList.size - 3
    val sectionTypes = if (remainingCount > 0) {
        displayedTypes.joinToString(" · ") + " +$remainingCount"
    } else {
        displayedTypes.joinToString(" · ")
    }
    
    // 宝贝获取分区类型的颜色列表喵~
    val sectionColors = paper.sections.map { section ->
        categoryColors[section.category] ?: primaryColor
    }.distinct().take(3)
    
    // 宝贝格式化下载时间喵~
    val downloadTimeStr = if (paper.downloadTime > 0) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(paper.downloadTime))
    } else {
        "未知时间"
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // 宝贝根据状态显示不同的图标喵~
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            when {
                                isLoading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                isFailed -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                                else -> primaryColor.copy(alpha = 0.1f)
                            },
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        isFailed -> {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = "加载失败",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        else -> {
                            Text(
                                text = "${paperIndex + 1}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    // 宝贝显示地区标签和标题喵~
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 宝贝添加地区标签 Badge 喵~
                        if (paper.regionLabel != "未知") {
                            Surface(
                                color = when (paper.regionLabel) {
                                    "初中" -> Color(0xFF6366F1)
                                    "高中" -> Color(0xFF22C55E)
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = paper.regionLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = paper.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // 宝贝添加已下载标记喵~
                        if (isDownloaded) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = Color(0xFF22C55E),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "已下载",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    // 宝贝显示分区类型，用对应颜色喵~
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        sectionColors.forEachIndexed { index, color ->
                            if (index > 0) {
                                Text(
                                    text = " · ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = displayedTypes.getOrElse(index) { "" },
                                style = MaterialTheme.typography.bodySmall,
                                color = color
                            )
                        }
                        if (remainingCount > 0) {
                            Text(
                                text = " +$remainingCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    // 宝贝根据状态显示不同的信息喵~
                    when {
                        isLoading -> {
                            Text(
                                text = "正在加载...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        isFailed -> {
                            Text(
                                text = "加载失败，点击重试",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            Text(
                                text = "${paper.sections.size} 个分区 · ${paper.sections.sumOf { it.questions.size }} 道题目",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 宝贝显示下载时间喵~
                            Text(
                                text = if (paper.downloadTime > 0) "下载时间: $downloadTimeStr" else "未加载",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // 宝贝只在非加载/非失败状态显示箭头图标喵~
            if (!isLoading && !isFailed) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "进入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 宝贝云端模式信息卡片喵~
 * 显示当前为云端作业模式
 */
@Composable
private fun CloudModeInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "云端作业模式",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "点击作业项目开始下载喵~",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 宝贝删除确认对话框喵~
 * 用户点击删除按钮后会弹出这个对话框确认
 */
@Composable
private fun DeleteConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    isCloudMode: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text("确认删除？")
        },
        text = {
            if (isCloudMode) {
                Text("这将清除所有云端缓存的作业数据。\n\n此操作不可撤销！")
            } else {
                Text("这将删除以下目录中的所有文件：\n\n• data/ 目录\n• resource/ 目录\n\n此操作不可撤销！")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${size / (1024 * 1024)} MB"
    }
}