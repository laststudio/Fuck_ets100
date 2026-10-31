package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import com.shuaiqiu.fuckets100.CloudContent
import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100CloudMerge
import com.shuaiqiu.fuckets100.ETS100CloudTemplate
import com.shuaiqiu.fuckets100.ETS100AuthManager
import com.shuaiqiu.fuckets100.PaperSource
import com.shuaiqiu.fuckets100.PaperSourceExporter
import com.shuaiqiu.fuckets100.ResolverAuthorizer
import com.shuaiqiu.fuckets100.ResolverAuthorizationDeniedException
import com.shuaiqiu.fuckets100.ZipPasswordGenerator
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface CloudPaperResult {
    data class Success(val paper: ETS100AnswerReader.Paper) : CloudPaperResult

    /** 授权被拒绝：不写入失败缓存，交由调用方提示。 */
    data class AuthDenied(val message: String, val longToast: Boolean) : CloudPaperResult

    data class Failure(
        val message: String,
        val sourceOnlyPaper: ETS100AnswerReader.Paper?,
    ) : CloudPaperResult
}

/**
 * 云端作业下载与解析流水线。
 *
 * 负责授权校验、ZIP 下载/解压、PK 与词汇在线作业回退，以及 content.json 解析。
 * 返回结构化结果，进度通过 [onProgress] 回调上报，不接触 Compose 状态。
 */
internal suspend fun downloadAndParseCloudPaper(
    context: Context,
    homeworkInfo: ETS100ApiClient.HomeworkInfo,
    status: String,
    cloudBaseUrl: String,
    onProgress: suspend (CloudHomeworkState.DownloadProgress?) -> Unit,
): CloudPaperResult {
    val cacheKey = cloudHomeworkCacheKey(status, homeworkInfo)

    try {
        // 并发执行授权校验以消除串行网络等待
        coroutineScope {
            val cloudAuth = async { ResolverAuthorizer.requireCloudAuthorized(context) }
            val resolverAuth = async { ResolverAuthorizer.requireAuthorized(context) }
            cloudAuth.await()
            resolverAuth.await()
        }
    } catch (e: ResolverAuthorizationDeniedException) {
        return CloudPaperResult.AuthDenied(e.message ?: "答案解析功能当前不可用", longToast = true)
    } catch (e: Exception) {
        return CloudPaperResult.AuthDenied(e.message ?: "授权校验失败", longToast = false)
    }

    ReadCloudLog.info("📥 开始下载作业: ${homeworkInfo.name}, 共 ${homeworkInfo.contents.size} 个内容")
    onProgress(CloudHomeworkState.DownloadProgress(currentFileName = homeworkInfo.name))

    var sourceOnlyPaper: ETS100AnswerReader.Paper? = null
    var cacheDir: File? = null
    try {
        val source = PaperSource.Cloud(
            status = status,
            homeworkIdentity = cloudHomeworkIdentity(homeworkInfo),
            baseUrl = cloudBaseUrl,
            contents = homeworkInfo.contents.map { CloudContent(it.groupName, it.url) },
        )
        sourceOnlyPaper = ETS100AnswerReader.Paper(
            paperId = cacheKey.hashCode().toLong(),
            title = homeworkInfo.name,
            dataFileName = cacheKey,
            fileSize = 0L,
            sections = emptyList(),
            source = source,
        )
        cacheDir = PaperSourceExporter.cloudCacheDirectory(
            context,
            status,
            cloudHomeworkIdentity(homeworkInfo),
            cloudBaseUrl,
            homeworkInfo.contents.map { CloudContent(it.groupName, it.url) }
        ).apply { mkdirs() }
        val allSections = mutableListOf<ETS100AnswerReader.Section>()
        // 每包的解析器原始题目 + 官方挂载键（整卷合并用；见下方 officialMerge）
        val nativePackages = mutableListOf<ETS100CloudTemplate.PackageQuestions>()
        // 逐包失败原因：全部为空时拼进错误信息，方便定位（下载/解压/解析/授权各阶段）
        val parseIssues = mutableListOf<String>()
        var questionIndex = 0

        val isPkOrVocab = homeworkInfo.contents.isEmpty() || homeworkInfo.pk == 1 || homeworkInfo.setType == 6
        if (isPkOrVocab) {
            parseOnlineHomework(context, homeworkInfo, cacheDir, allSections, parseIssues) { added ->
                questionIndex += added
            }
        }

        if (allSections.isEmpty()) {
            for ((contentIndex, content) in homeworkInfo.contents.withIndex()) {
                questionIndex += parseZipContent(
                    context = context,
                    homeworkInfo = homeworkInfo,
                    content = content,
                    contentIndex = contentIndex,
                    startIndex = questionIndex,
                    cacheDir = cacheDir,
                    cloudBaseUrl = cloudBaseUrl,
                    onProgress = onProgress,
                    allSections = allSections,
                    nativePackages = nativePackages,
                    issues = parseIssues,
                )
            }
        }

        if (allSections.isEmpty()) {
            val detail = buildString {
                append("未能解析到任何题目")
                append("（作业：").append(homeworkInfo.name).append("；")
                append("内容包 ").append(homeworkInfo.contents.size).append(" 个，pk=").append(homeworkInfo.pk)
                append("，setType=").append(homeworkInfo.setType)
                append("；解析器授权：").append(ResolverAuthorizer.localAuthStatus())
                append("；Token：").append(if (ETS100AuthManager.getToken(context).isNullOrBlank()) "缺失" else "已保存")
                append("）")
                if (parseIssues.isNotEmpty()) {
                    append("\n失败环节：\n")
                    parseIssues.take(6).forEach { append("- ").append(it).append("\n") }
                }
                append("可先点作业卡片重试；若仍失败，请把 ReadCloud 日志反馈给开发者。")
            }
            throw Exception(detail)
        }

        // 官方模板口径：从 /m/homework/struct 取模板包 -> res.json -> op: parse_homework_set
        // 成功时用官方大题顺序、卷面题号与逐题满分；失败则保持原有逐包结果（不降级数据）。
        val official = ETS100CloudMerge.merge(
            context = context,
            homeworkId = homeworkInfo.id,
            homeworkName = homeworkInfo.name,
            resourceId = homeworkInfo.resourceId,
            setType = homeworkInfo.setType,
            pk = homeworkInfo.pk,
            contents = homeworkInfo.contents,
            packages = nativePackages,
            cacheDir = cacheDir,
            // 模板包未加密：只解压，不需要密码（zip4j 只在 feature 层，故注入）
            unzip = { zipFile, destDir ->
                net.lingala.zip4j.ZipFile(zipFile).extractAll(destDir.absolutePath)
            },
        )
        official?.logs?.forEach { line ->
            if (line.startsWith("⚠️")) ReadCloudLog.warn(line) else ReadCloudLog.info(line)
        }
        // 缺题必须在日志里显眼：卷面分是官方值，光看总分看不出少题
        official?.integrity?.takeIf { !it.isComplete }?.let {
            ReadCloudLog.warn("⚠️ 本卷题目不完整（可能少题/有包未取到）：${it.notice}")
        }
        val sectionsForPaper = official?.sections ?: run {
            val normalized = normalizeCloudParsedSections(allSections)
            if (normalized.size != allSections.size) {
                ReadCloudLog.info("🔀 已合并云端听后选择分包并按正式题号排序")
            }
            normalized
        }
        questionIndex = sectionsForPaper.sumOf { it.questions.size }
        ReadCloudLog.success(
            "📝 共解析到 ${sectionsForPaper.size} 个题型，$questionIndex 道题" +
                (official?.let { "（官方模板：${it.totalScore} 分）" } ?: "（未取到官方模板）")
        )

        val isPk = homeworkInfo.pk == 1 || homeworkInfo.setType == 6
        val paper = ETS100AnswerReader.Paper(
            paperId = cacheKey.hashCode().toLong(),
            title = homeworkInfo.name,
            dataFileName = cacheKey,
            fileSize = 0L,
            sections = sectionsForPaper,
            totalScore = official?.totalScore,
            integrity = official?.integrity,
            downloadTime = System.currentTimeMillis(),
            regionLabel = if (isPk) "词汇PK" else "云端",
            paperName = homeworkInfo.name,
            source = PaperSource.Cloud(
                status = status,
                homeworkIdentity = cloudHomeworkIdentity(homeworkInfo),
                baseUrl = cloudBaseUrl,
                contents = homeworkInfo.contents.map { CloudContent(it.groupName, it.url) },
            )
        )
        return CloudPaperResult.Success(paper)
    } catch (e: Exception) {
        ReadCloudLog.error("✗ 下载解析失败: ${e.message}")
        val hasCachedSource = cacheDir?.let { directory ->
            homeworkInfo.contents.any { content ->
                val name = content.url.substringAfterLast('/').substringBefore('?')
                name.isNotBlank() && PaperSourceExporter.isUsableZip(
                    PaperSourceExporter.cloudCachedFile(directory, content.url, name),
                )
            }
        } == true
        return CloudPaperResult.Failure(
            message = e.message ?: "下载或解析失败",
            sourceOnlyPaper = sourceOnlyPaper.takeIf { hasCachedSource },
        )
    }
}

private suspend fun parseOnlineHomework(
    context: Context,
    homeworkInfo: ETS100ApiClient.HomeworkInfo,
    cacheDir: File,
    allSections: MutableList<ETS100AnswerReader.Section>,
    issues: MutableList<String>,
    onQuestionIndexAdvanced: (Int) -> Unit,
) {
    ReadCloudLog.info("🌐 检测到 PK/词汇在线作业，正在从云端获取题目数据...")
    val token = ETS100AuthManager.getToken(context).orEmpty()
    val parentId = ETS100AuthManager.getParentAccountId(context).orEmpty()

    var pkJson: JSONObject? = null
    fun hasQuestions(json: JSONObject?): Boolean {
        if (json == null || json.length() == 0) return false
        return (json.optJSONArray("word_question")?.length() ?: 0) > 0
            || (json.optJSONArray("word_questions")?.length() ?: 0) > 0
            || (json.optJSONArray("questions")?.length() ?: 0) > 0
            || (json.optJSONArray("data")?.length() ?: 0) > 0
            || (json.optJSONArray("words")?.length() ?: 0) > 0
            || (json.optJSONArray("word")?.length() ?: 0) > 0
    }

    if (token.isNotBlank()) {
        // 路径 1: pk-info
        val pkResult = ETS100ApiClient.getPkInfo(token, homeworkInfo.id, parentId)
        val rawPk = pkResult.getOrNull()
        if (hasQuestions(rawPk)) {
            pkJson = rawPk
            ReadCloudLog.info("✅ 成功获取 pk-info 响应")
        } else {
            val errMsg = pkResult.exceptionOrNull()?.message ?: "无题目"
            ReadCloudLog.warn("⚠️ pk-info 未返回题目 ($errMsg)，尝试 homework/struct...")

            // 路径 2: homework/struct (带 resourceId)
            val structResult = ETS100ApiClient.getHomeworkStruct(token, homeworkInfo.id, homeworkInfo.resourceId, parentId)
            val rawStruct = structResult.getOrNull()
            if (hasQuestions(rawStruct)) {
                pkJson = rawStruct
                ReadCloudLog.info("✅ 成功从 homework/struct 获取题目数据")
            } else if (homeworkInfo.resourceId.isNotBlank()) {
                // 路径 3: homework/struct (不带 resourceId)
                ReadCloudLog.warn("⚠️ homework/struct(带resourceId) 未返回题目，尝试不带 resourceId...")
                val structNoRes = ETS100ApiClient.getHomeworkStruct(token, homeworkInfo.id, "", parentId)
                val rawStruct2 = structNoRes.getOrNull()
                if (hasQuestions(rawStruct2)) {
                    pkJson = rawStruct2
                    ReadCloudLog.info("✅ 成功从 homework/struct(无resourceId) 获取题目数据")
                }
            }

            // 路径 4: score-detail (已完成历史作业回退)
            if (pkJson == null) {
                ReadCloudLog.warn("⚠️ struct 未返回题目，尝试 score-detail 历史作业解析...")
                val detailResult = ETS100ApiClient.getScoreDetail(token, homeworkInfo.id, homeworkInfo.resourceId, parentId)
                val detailBody = detailResult.getOrNull()
                val detailsArr = detailBody?.optJSONArray("details")
                if (detailsArr != null && detailsArr.length() > 0) {
                    val wordIdSet = mutableSetOf<Int>()
                    val questionIdSet = mutableSetOf<Int>()
                    for (i in 0 until detailsArr.length()) {
                        val d = detailsArr.optJSONObject(i) ?: continue
                        val wArr = d.optJSONArray("word") ?: continue
                        for (j in 0 until wArr.length()) {
                            val entry = wArr.optJSONObject(j) ?: continue
                            val wid = entry.optInt("word_id", 0)
                            val qid = entry.optInt("question_id", 0)
                            if (wid > 0) wordIdSet.add(wid)
                            if (qid > 0) questionIdSet.add(qid)
                        }
                    }
                    if (wordIdSet.isNotEmpty()) {
                        ReadCloudLog.info("🔍 score-detail 发现 ${wordIdSet.size} 个单词，调用 word/struct...")
                        val wordStructRes = ETS100ApiClient.getWordStruct(token, wordIdSet.toList())
                        val wordStructBody = wordStructRes.getOrNull()
                        val dataArr = wordStructBody?.optJSONArray("data")
                        if (dataArr != null && dataArr.length() > 0) {
                            val filteredArr = JSONArray()
                            for (k in 0 until dataArr.length()) {
                                val item = dataArr.optJSONObject(k) ?: continue
                                val qList = item.optJSONArray("question") ?: continue
                                val matchingQs = JSONArray()
                                for (m in 0 until qList.length()) {
                                    val q = qList.optJSONObject(m) ?: continue
                                    val qid = q.optInt("id", 0)
                                    if (questionIdSet.isEmpty() || questionIdSet.contains(qid)) {
                                        matchingQs.put(q)
                                    }
                                }
                                if (matchingQs.length() > 0) {
                                    val itemCopy = JSONObject(item.toString())
                                    itemCopy.put("question", matchingQs)
                                    filteredArr.put(itemCopy)
                                }
                            }
                            if (filteredArr.length() > 0) {
                                pkJson = JSONObject().put("data", filteredArr)
                                ReadCloudLog.info("✅ 成功从 score-detail + word/struct 恢复 ${filteredArr.length()} 个单词题目")
                            }
                        }
                    }
                }
            }
        }
    }

    if (pkJson == null || pkJson.length() == 0) {
        issues += "在线作业（" + homeworkInfo.name + "）未从官方接口获取到题目数据" +
            "（已尝试 pk-info / homework/struct / score-detail；请确认该作业类型或账号权限）"
    }

    if (pkJson != null && pkJson.length() > 0) {
        try {
            val groupName = if (homeworkInfo.setType == 6 || homeworkInfo.pk == 1) "词汇PK" else "在线作业"
            pkJson.put("structure_type", "word_pk")
            File(cacheDir, "content.json").writeText(pkJson.toString())
            val (questions, originalContent) = ETS100AnswerReader.parseContentJson(
                json = pkJson,
                startIndex = 0,
                category = "",
                typeName = groupName,
                sectionCaption = groupName,
                infoJson = "",
                content2Json = "",
            )

            if (questions.isNotEmpty()) {
                allSections.add(
                    ETS100AnswerReader.Section(
                        caption = groupName,
                        category = pkJson.optString("structure_type", "word.choice"),
                        typeName = groupName,
                        questions = questions,
                        originalContent = originalContent
                    )
                )
                onQuestionIndexAdvanced(questions.size)
                ReadCloudLog.success("✅ 成功解析 PK/词汇题目 ${questions.size} 道")
            } else {
                ReadCloudLog.warn("⚠️ PK/词汇作业未解析出题目")
                issues += "在线作业（" + homeworkInfo.name + "）拿到了题目数据但解析为 0 题"
            }
        } catch (e: Exception) {
            ReadCloudLog.error("✗ 解析 PK/词汇作业失败: ${e.message}")
            issues += "在线作业（" + homeworkInfo.name + "）解析异常：" + (e.message ?: "未知错误")
        }
    }
}


private suspend fun parseZipContent(
    context: Context,
    homeworkInfo: ETS100ApiClient.HomeworkInfo,
    content: ETS100ApiClient.HomeworkContent,
    contentIndex: Int,
    startIndex: Int,
    cacheDir: File,
    cloudBaseUrl: String,
    onProgress: suspend (CloudHomeworkState.DownloadProgress?) -> Unit,
    allSections: MutableList<ETS100AnswerReader.Section>,
    nativePackages: MutableList<ETS100CloudTemplate.PackageQuestions>,
    issues: MutableList<String>,
): Int {
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
        ReadCloudLog.warn("⚠️ 无法从 URL 提取文件名: ${content.url}")
        issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）无法从下载地址解析文件名：" + content.url
        return 0
    }
    ReadCloudLog.info(
        "⬇️ 内容 ${contentIndex + 1}/${homeworkInfo.contents.size}: " +
            "group=${content.groupName}, url=${content.url}, zip=$zipFileName"
    )

    val zipFile = PaperSourceExporter.cloudCachedFile(cacheDir, content.url, zipFileName)
    if (PaperSourceExporter.isUsableZip(zipFile)) {
        ReadCloudLog.info("📦 文件已存在，跳过下载: $zipFileName")
        onProgress(
            CloudHomeworkState.DownloadProgress(
                downloadedBytes = zipFile.length(),
                totalBytes = zipFile.length(),
                currentFileName = zipFileName
            )
        )
    } else {
        val temporaryFile = File(cacheDir, "${zipFile.name}.part").also { it.delete() }
        val downloadResult = ETS100ApiClient.downloadFile(zipUrl, temporaryFile) { downloadedBytes, totalBytes ->
            onProgress(
                CloudHomeworkState.DownloadProgress(
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    currentFileName = zipFileName
                )
            )
        }
        downloadResult.onFailure { e ->
            ReadCloudLog.error("✗ 下载失败 ${content.groupName}: ${e.message}")
        }
        if (downloadResult.isFailure) {
            val err = downloadResult.exceptionOrNull()?.message ?: "未知网络错误"
            temporaryFile.delete()
            ReadCloudLog.warn("⏭️ 跳过失败的下载: ${content.groupName}")
            issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）下载失败：" + err
            return 0
        }
        if (!PaperSourceExporter.isUsableZip(temporaryFile)) {
            temporaryFile.delete()
            ReadCloudLog.error("✗ 下载结果不是完整 ZIP: $zipFileName")
            issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）下载结果不是完整 ZIP"
            return 0
        }
        if (!temporaryFile.renameTo(zipFile)) {
            temporaryFile.copyTo(zipFile, overwrite = true)
            temporaryFile.delete()
        }
        ReadCloudLog.info("✅ 下载完成: ${zipFile.length()} bytes")
    }

    // 2. 验证 ZIP Magic
    val magic = ByteArray(4)
    java.io.FileInputStream(zipFile).use { it.read(magic) }
    val magicHex = magic.joinToString("") { "%02X".format(it) }
    if (magicHex != "504B0304" && magicHex != "504B0506" && magicHex != "504B0708") {
        ReadCloudLog.error("⚠️ 文件不是有效 ZIP: $magicHex")
        issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）文件不是有效 ZIP（magic=" + magicHex + "）"
        return 0
    }

    // 3. 生成密码 + 解压
    val password = ZipPasswordGenerator.generatePassword(zipFile.absolutePath)
    if (password == null) {
        ReadCloudLog.error("✗ 无法生成解压密码: $zipFileName")
        issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）无法生成解压密码"
        return 0
    }

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
        ReadCloudLog.debug("📦 解压完成: $extractDirName")
    } catch (e: Exception) {
        ReadCloudLog.error("✗ 解压失败 ${content.groupName}: ${e.message}")
        issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）解压失败：" + (e.message ?: "未知错误")
        return 0
    }

    // 4. 直接从解压目录读取 content.json（不需要找子文件夹）
    ReadCloudLog.info("📂 解压目录: ${extractDir.name}")
    extractDir.listFiles()?.forEach { item ->
        val type = if (item.isDirectory) "📁" else "📄"
        ReadCloudLog.debug("  $type ${item.name}")
    }

    val contentJsonFile = File(extractDir, "content.json")
    if (!contentJsonFile.exists()) {
        ReadCloudLog.warn("⚠️ 解压目录中无 content.json，跳过")
        issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）解压目录缺少 content.json"
        return 0
    }

    try {
        val json = JSONObject(contentJsonFile.readText())
        val structureType = json.optString("structure_type", "")
        val questionCountInJson = json.optJSONObject("info")?.optJSONArray("question")?.length() ?: 0
        val chooseCountInJson = json.optJSONObject("info")?.optJSONArray("xtlist")?.length() ?: 0
        val stdCountInJson = json.optJSONObject("info")?.optJSONArray("std")?.length() ?: 0

        ReadCloudLog.info("📄 解析 content.json")
        ReadCloudLog.info("   ├─ structure_type: $structureType")
        ReadCloudLog.info("   ├─ group_name: ${content.groupName}")
        ReadCloudLog.info("   ├─ raw数量: question=$questionCountInJson, xtlist=$chooseCountInJson, std=$stdCountInJson")

        val infoJson = File(extractDir, "info.json").takeIf { it.isFile }?.readText().orEmpty()
        val content2Json = File(extractDir, "content2.json").takeIf { it.isFile }?.readText().orEmpty()
        val (questions, originalContent) = ETS100AnswerReader.parseContentJson(
            json = json,
            startIndex = startIndex,
            category = "",
            typeName = content.groupName,
            sectionCaption = content.groupName,
            infoJson = infoJson,
            content2Json = content2Json,
        )
        // 同时留一份解析器原始题目：整卷合并要把它们交给 parse_homework_set
        runCatching {
            nativePackages.add(
                ETS100CloudTemplate.PackageQuestions(
                    structureType = structureType,
                    groupName = content.groupName,
                    questions = ETS100AnswerReader.parseContentNative(json, infoJson, content2Json),
                    realId = content.realId ?: "",
                    order = contentIndex,
                ),
            )
        }.onFailure { ReadCloudLog.warn("   └─ 记录原始题目失败（不影响解析）: ${it.message}") }

        if (questions.isEmpty()) {
            ReadCloudLog.warn("   └─ ⚠️ 未解析到任何题目！")
            issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）content.json 解析为 0 题" +
                "（structure_type=" + structureType + ", raw question=" + questionCountInJson + "）"
            return 0
        }
        ReadCloudLog.success(
            "   ├─ ✅ content ${contentIndex + 1}/${homeworkInfo.contents.size} 解析到 ${questions.size} 道题"
        )
        for ((i, q) in questions.withIndex()) {
            val isLast = i == questions.size - 1
            val prefix = if (isLast) "   │   └─" else "   │   ├─"
            ReadCloudLog.info("$prefix 第${q.order}题: ${q.questionText.take(60)}")
            if (q.answers.isNotEmpty()) {
                for ((j, ans) in q.answers.withIndex()) {
                    val ansPrefix = if (isLast) "   │       └─" else "   │       ├─"
                    ReadCloudLog.info("$ansPrefix 答案${j + 1}: ${ans.take(80)}")
                }
            } else {
                val ansPrefix = if (isLast) "   │       └─" else "   │       ├─"
                ReadCloudLog.warn("$ansPrefix 无标准答案")
            }
        }

        allSections.add(
            ETS100AnswerReader.Section(
                caption = content.groupName,
                category = structureType,
                typeName = content.groupName,
                questions = questions,
                originalContent = originalContent
            )
        )
        ReadCloudLog.info("   └─ 累计: sections=${allSections.size}, questions=${startIndex + questions.size}")
        return questions.size
    } catch (e: Exception) {
        ReadCloudLog.error("   └─ ✗ 解析 content.json 失败: ${e.message}")
        issues += "内容" + (contentIndex + 1) + "（" + content.groupName + "）解析异常：" + (e.message ?: "未知错误")
        return 0
    }
}
