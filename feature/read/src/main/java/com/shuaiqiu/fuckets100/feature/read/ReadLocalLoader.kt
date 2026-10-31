package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import com.shuaiqiu.fuckets100.ActivationMode
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.ETS100FileReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed class LocalReadResult<out T> {
    data class Success<T>(val value: T) : LocalReadResult<T>()
    data class Error(val message: String) : LocalReadResult<Nothing>()
}

internal data class LocalReadSnapshot(
    val readerInfo: String,
    val dataFiles: List<ETS100FileReader.FileItem>,
    val resourceFiles: List<ETS100FileReader.FileItem>,
    val papers: List<ETS100AnswerReader.Paper>,
)

internal object ReadLocalLoader {
    suspend fun scan(context: Context, mode: ActivationMode): LocalReadResult<LocalReadSnapshot> =
        withContext(Dispatchers.IO) {
            try {
                val reader = ETS100FileReader.getReader(mode, context)
                val dataDirPath = ETS100FileReader.Path.getDataDir()
                val resourceDirPath = ETS100FileReader.Path.getResourceDir()
                val shouldScanDebugFileLists = mode == ActivationMode.DIRECT_READ

                val dataFiles = if (shouldScanDebugFileLists) {
                    reader.listFiles(dataDirPath)
                        .filter { file -> !file.isDirectory && file.size > ETS100FileReader.Path.MIN_FILE_SIZE }
                        .sortedByDescending { it.lastModified }
                } else {
                    emptyList()
                }
                val resourceFiles = if (shouldScanDebugFileLists) {
                    reader.listFiles(resourceDirPath)
                } else {
                    emptyList()
                }

                LocalReadResult.Success(
                    LocalReadSnapshot(
                        readerInfo = reader.toString(),
                        dataFiles = dataFiles,
                        resourceFiles = resourceFiles,
                        papers = ETS100AnswerReader.readPaperSummaries(context, mode),
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocalReadResult.Error(
                    "扫描本地数据失败（模式=" + mode + "）：" + (e.message ?: "未知错误") +
                        "；请确认文件访问权限，或切换到其它读取模式后重试"
                )
            }
        }

    suspend fun parse(
        context: Context,
        mode: ActivationMode,
        onGroupStarted: (suspend (Int, Int) -> Unit)? = null,
        onGroupParsed: (suspend (Int, List<ETS100AnswerReader.Paper>) -> Unit)? = null,
    ): LocalReadResult<List<ETS100AnswerReader.Paper>> = withContext(Dispatchers.IO) {
        try {
            LocalReadResult.Success(
                ETS100AnswerReader.readPapersParallel(
                    context = context,
                    mode = mode,
                    onGroupStarted = onGroupStarted,
                    onGroupParsed = onGroupParsed,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalReadResult.Error(
                "解析本地答案失败（模式=" + mode + "）：" + (e.message ?: "未知错误") +
                    "；若提示授权/内部错误，请反馈日志中的 ResolverAuthorizer / ReadScreen 行"
            )
        }
    }
}
