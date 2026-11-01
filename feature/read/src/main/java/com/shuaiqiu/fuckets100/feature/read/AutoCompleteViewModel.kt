package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaiqiu.fuckets100.CloudHomeworkState
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.FeAccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AutoCompleteUiState(
    val status: String = CloudHomeworkState.STATUS_CURRENT,
    val homeworks: List<ETS100ApiClient.HomeworkInfo> = emptyList(),
    val isLoading: Boolean = false,
    val listError: String? = null,
    val tokenExpired: Boolean = false,
    val selectedHomeworkId: String? = null,
    val targetRatio: Float = 0.95f,
    val isRunning: Boolean = false,
    val progress: Int = 0,
    val stageLabel: String = "",
    val currentQuestion: String = "",
    val currentIndex: Int = 0,
    val totalQuestions: Int = 0,
    val result: AutoRunResult? = null,
    val error: String? = null,
    val history: List<AutoRunResult> = emptyList(),
) {
    val selectedHomework: ETS100ApiClient.HomeworkInfo?
        get() = homeworks.firstOrNull { cloudHomeworkIdentity(it) == selectedHomeworkId }
}

@HiltViewModel
class AutoCompleteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: FeAccountManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutoCompleteUiState())
    val uiState: StateFlow<AutoCompleteUiState> = _uiState.asStateFlow()

    private val engine = AutoCompleteEngine(context, accountManager)
    private var listJob: Job? = null
    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { loadHistory() }
            _uiState.update { it.copy(history = history) }
        }
    }

    fun selectStatus(status: String, accountOnline: Boolean) {
        if (_uiState.value.isRunning || status == _uiState.value.status) return
        _uiState.update { it.copy(status = status, homeworks = emptyList(), selectedHomeworkId = null) }
        loadHomeworks(accountOnline)
    }

    fun loadHomeworks(accountOnline: Boolean) {
        if (_uiState.value.isRunning) return
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, listError = null, tokenExpired = false) }
            val status = _uiState.value.status
            when (val result = loadCloudHomeworkListResult(context, status, accountOnline, accountManager)) {
                is CloudHomeworkListResult.Success -> {
                    val selected = _uiState.value.selectedHomeworkId
                        ?.takeIf { key -> result.homeworks.any { cloudHomeworkIdentity(it) == key } }
                        ?: result.homeworks.firstOrNull()?.let(::cloudHomeworkIdentity)
                    _uiState.update {
                        it.copy(
                            homeworks = result.homeworks,
                            selectedHomeworkId = selected,
                            isLoading = false,
                            listError = null,
                            tokenExpired = false,
                        )
                    }
                }

                is CloudHomeworkListResult.ChangyanExpired -> _uiState.update {
                    it.copy(isLoading = false, listError = result.message, tokenExpired = true)
                }

                is CloudHomeworkListResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, listError = result.message)
                }
            }
        }
    }

    fun selectHomework(homeworkId: String) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(selectedHomeworkId = homeworkId, result = null, error = null) }
    }

    /** 目标分比例，最多为卷面的 95%。 */
    fun setTargetRatio(ratio: Float) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(targetRatio = ratio.coerceIn(0.3f, 0.95f), result = null, error = null) }
    }

    fun start() {
        val state = _uiState.value
        if (state.isRunning) return
        val homework = state.selectedHomework
        if (homework == null) {
            _uiState.update { it.copy(error = "请先选择一个作业") }
            return
        }
        runJob?.cancel()
        runJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunning = true,
                    progress = 0,
                    stageLabel = AutoCompleteStage.LABELS.first(),
                    currentQuestion = "",
                    currentIndex = 0,
                    totalQuestions = 0,
                    result = null,
                    error = null,
                )
            }
            try {
                val result = engine.run(
                    homework = homework,
                    targetRatio = state.targetRatio.toDouble(),
                ) { update ->
                    _uiState.update {
                        it.copy(
                            progress = update.progress,
                            stageLabel = update.label,
                            currentQuestion = update.currentQuestion,
                            currentIndex = update.currentQuestionIndex,
                            totalQuestions = update.totalQuestions,
                        )
                    }
                }
                _uiState.update { it.copy(isRunning = false, progress = 100, result = result, error = null) }
                appendHistory(result)
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(isRunning = false, stageLabel = "任务已被手动终止", currentQuestion = "")
                }
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "执行失败"
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        progress = 0,
                        stageLabel = "执行失败: $message",
                        currentQuestion = "",
                        error = message,
                    )
                }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        _uiState.update { it.copy(isRunning = false, stageLabel = "任务已被手动终止", currentQuestion = "") }
    }

    fun clearResult() {
        _uiState.update { it.copy(result = null, error = null, progress = 0, stageLabel = "") }
    }

    // ==================== 历史记录 ====================

    private suspend fun appendHistory(result: AutoRunResult) {
        val next = (listOf(result) + _uiState.value.history).take(HISTORY_LIMIT)
        _uiState.update { it.copy(history = next) }
        withContext(Dispatchers.IO) { saveHistory(next) }
    }

    private fun loadHistory(): List<AutoRunResult> = runCatching {
        val file = historyFile()
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseResult)
        }
    }.getOrDefault(emptyList())

    private fun saveHistory(history: List<AutoRunResult>) {
        runCatching {
            historyFile().writeText(history.toJsonArray().toString(), Charsets.UTF_8)
        }
    }

    private fun historyFile(): File = File(context.filesDir, HISTORY_FILE_NAME)

    private fun List<AutoRunResult>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { result ->
            val questions = JSONArray()
            result.questionResults.forEach { question ->
                questions.put(
                    JSONObject()
                        .put("order", question.order)
                        .put("typeName", question.typeName)
                        .put("text", question.text)
                        .put("awardedScore", question.awardedScore)
                        .put("maxScore", question.maxScore)
                )
            }
            array.put(
                JSONObject()
                    .put("homeworkId", result.homeworkId)
                    .put("homeworkName", result.homeworkName)
                    .put("score", result.score)
                    .put("maxScore", result.maxScore)
                    .put("scoreRate", result.scoreRate)
                    .put("targetTotal", result.targetTotal)
                    .put("useTime", result.useTime)
                    .put("officialPoint", result.officialPoint ?: JSONObject.NULL)
                    .put("officialUseTime", result.officialUseTime ?: JSONObject.NULL)
                    .put("officialSubmitTime", result.officialSubmitTime ?: JSONObject.NULL)
                    .put("completedAt", result.completedAt)
                    .put("questions", questions)
            )
        }
        return array
    }

    private fun parseResult(json: JSONObject): AutoRunResult {
        val questions = mutableListOf<AutoQuestionResult>()
        json.optJSONArray("questions")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { question ->
                    questions.add(
                        AutoQuestionResult(
                            order = question.optInt("order", index + 1),
                            typeName = question.optString("typeName"),
                            text = question.optString("text"),
                            awardedScore = question.optDouble("awardedScore", 0.0),
                            maxScore = question.optDouble("maxScore", 0.0),
                        )
                    )
                }
            }
        }
        return AutoRunResult(
            homeworkId = json.optString("homeworkId"),
            homeworkName = json.optString("homeworkName"),
            score = json.optDouble("score", 0.0),
            maxScore = json.optDouble("maxScore", 0.0),
            scoreRate = json.optDouble("scoreRate", 0.0),
            targetTotal = json.optDouble("targetTotal", 0.0),
            useTime = json.optInt("useTime", 0),
            officialPoint = json.optionalDouble("officialPoint"),
            officialUseTime = json.optionalInt("officialUseTime"),
            officialSubmitTime = json.optString("officialSubmitTime").ifBlank { null },
            completedAt = json.optString("completedAt"),
            questionResults = questions,
        )
    }

    private fun JSONObject.optionalDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    private fun JSONObject.optionalInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key, -1).takeIf { it >= 0 }
    }

    private companion object {
        const val HISTORY_FILE_NAME = "auto_complete_history.json"
        const val HISTORY_LIMIT = 30
    }
}
