package com.shuaiqiu.fuckets100

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * ETS100 API 客户端
 * 负责所有网络请求和签名生成喵~
 *
 * API 文档参考: ets读取/API_DOC.md
 */
object ETS100ApiClient {

    private const val TAG = "ETS100ApiClient"

    // ============================================================================
    // API 配置
    // ============================================================================

    object Config {
        const val API_BASE_URL = "https://api.ets100.com"
        const val CDN_BASE_URL = "https://cdn.subject.ets100.com"
        const val PID = "grlx"
        const val SECRET_KEY = "555ffbe95ccf4e9535a110170b445ab8"
        const val TIMEOUT_MS = 30000  // 30秒超时
    }

    // ============================================================================
    // API 端点
    // ============================================================================

    object Endpoints {
        const val LOGIN = "/user/login"
        const val LOGIN_ZT = "/user/login-zt"
        const val ECARD_LIST = "/m/ecard/list"
        const val HOMEWORK_LIST = "/g/homework/list"
        const val HOMEWORK_PK_INFO = "/g/homework/pk-info"
        const val HOMEWORK_STRUCT = "/g/homework/struct"

        /** 试卷集结构（含模板包地址 -> 官方 res.json 的权威题号与逐题满分） */
        const val HOMEWORK_STRUCT_M = "/m/homework/struct"
        const val HOMEWORK_SCORE_DETAIL = "/g/homework/score-detail"
        const val WORD_STRUCT = "/g/word/struct"

        /** 自动完成：作业详情（回读成绩/用时） */
        const val HOMEWORK_INFO = "/m/homework/info"

        /** 自动完成：同步作答用时 */
        const val HOMEWORK_SET_USE_TIME = "/m/homework/set-use-time"

        /** 自动完成：上报逐题成绩 */
        const val HOMEWORK_REPORT_SCORE = "/g/homework/report-score"

        /** 自动完成：提交作业 */
        const val HOMEWORK_SUBMIT = "/m/homework/submit"

        /** 自动完成：开始作答（返回最新的 resource_id） */
        const val HOMEWORK_CHECKSTART = "/g/homework/checkstart"

        /** 自动完成：重新提交前清除官方侧旧分数 */
        const val HOMEWORK_SCORE_DROP = "/g/homework/score-drop"

        /** 自动完成：逐题作答同步（官方记账的唯一入口） */
        const val AUDIO_SYNC_V2 = "/m/audio/sync-v2"

        /** 自动完成：作答文件预上传（拿到文件 id） */
        const val FILE_PRE_UPLOAD = "/m/file/pre-upload"

        /** 自动完成：OSS 直传配置（host/dir/accessid/policy/signature） */
        const val SYSTEM_OSS_CONFIG = "/system/oss-config"

        /** 自动完成：初始化试卷题目资源绑定 */
        const val SET_PPR_RES = "/g/set/ppr-res"
        const val SEND_USER_EXIST = "/code/send-user-exist"
        const val REBIND_CODE = "/user/rebind-code"  // 设备绑定端点
    }

    // ============================================================================
    // 请求配置常量
    // ============================================================================

    object RequestConfig {
        const val DEFAULT_SN = "test"
        const val DEFAULT_VERSION = "3"
        const val DEFAULT_REBIND_VERSION = "2"  // 绑定时使用 version=2
        const val DEFAULT_SYSTEM = "4"
        const val DEFAULT_GLOBAL_CLIENT_VERSION = "5.4.5"  // Python 使用 5.4.5
        const val DEFAULT_DEVICE_NAME = "DESKTOP"
        const val DEFAULT_REBIND_DEVICE_NAME = "1337"  // 绑定时使用固定的 device_name
        const val DEFAULT_LOCAL_IP = "127.0.0.1"
    }

    // ============================================================================
    // 签名生成
    // ============================================================================

    /**
     * 生成 API 签名
     * sign_string = PID + timestamp + content + SECRET_KEY
     * signature = MD5(sign_string)
     */
    fun generateSign(timestamp: Long, bodyBase64: String): String {
        val signString = Config.PID + timestamp.toString() + bodyBase64 + Config.SECRET_KEY
        return md5(signString)
    }

    /**
     * MD5 哈希
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ============================================================================
    // HTTP 请求
    // ============================================================================

    /**
     * 发送 POST 请求
     * @param endpoint API 端点
     * @param bodyData 请求体数据（Map）
     * @return 响应 JSON 字符串
     */
    internal suspend fun postRequest(endpoint: String, bodyData: Map<String, Any>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = Config.API_BASE_URL + endpoint
            Log.d(TAG, "===== 网络请求信息 =====")
            Log.d(TAG, "URL: $fullUrl")

            val url = URL(fullUrl)
            Log.d(TAG, "协议: ${url.protocol}")
            Log.d(TAG, "主机: ${url.host}")
            Log.d(TAG, "路径: ${url.path}")

            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                connectTimeout = Config.TIMEOUT_MS
                readTimeout = Config.TIMEOUT_MS
                doOutput = true
                doInput = true
                setRequestProperty("Host", "api.ets100.com")
                setRequestProperty("User-Agent", "libcurl-agent/1.0")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "*/*")
            }

            // 构建请求体
            // 注意：根据 API 文档，body_data 是一个数组格式：[{"r": "...", "params": {...}}]
            val timestamp = System.currentTimeMillis() / 1000
            val bodyArrayJson = "[${bodyData.toJson()}]"  // 包装成数组
            val bodyBase64 = Base64.encodeToString(bodyArrayJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val sign = generateSign(timestamp, bodyBase64)

            // 构建 head JSON（紧凑格式，无空格）
            val headJson = "{\"version\":\"1.0\",\"sign\":\"$sign\",\"pid\":\"${Config.PID}\",\"time\":$timestamp}"

            // 构建完整的请求数据 (JSON 格式，直接发送给服务器)
            // 注意：Python 参考代码使用 data=payload_json，所以这里要直接发送 JSON 字符串
            val requestJson = "{\"body\":\"$bodyBase64\",\"head\":$headJson}"

            // 发送请求
            connection.outputStream.use { os ->
                os.write(requestJson.toByteArray(Charsets.UTF_8))
            }

            // 读取响应
            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP Response Code: $responseCode")

            // 根据响应码决定从哪个流读取
            val responseBody = if (responseCode in 200..299) {
                // 成功响应，从 inputStream 读取
                connection.inputStream.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { br ->
                        br.readText()
                    }
                }
            } else {
                // 错误响应，尝试从 errorStream 读取
                Log.e(TAG, "HTTP Error $responseCode，尝试读取 errorStream")
                try {
                    val errorContent = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    errorContent ?: "无 errorStream 内容"
                } catch (e: Exception) {
                    Log.e(TAG, "读取 errorStream 失败: ${e.message}")
                    "errorStream 读取失败: ${e.message}"
                }
            }

            Log.d(TAG, "POST $endpoint -> $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(responseBody)
            } else {
                Log.e(TAG, "HTTP Error: $responseCode")
                Result.failure(Exception("HTTP Error $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 直传阿里云 OSS（multipart/form-data），逐字对齐后端 `upload_oss_form`。
     *
     * @return 可回填到 sync-v2 的 `upload_file_url` / `detail_file_url`
     */
    suspend fun uploadOssForm(
        host: String,
        dirName: String,
        fileName: String,
        accessId: String,
        policy: String,
        signature: String,
        callback: String,
        content: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val base = host.trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
        val boundary = "------------------------" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream().use { out ->
            fun field(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(value.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray(Charsets.UTF_8))
            }
            field("name", fileName)
            field("key", dirName + fileName)
            field("OSSAccessKeyId", accessId)
            field("policy", policy)
            field("signature", signature)
            field("callback", callback)
            out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            out.write(
                "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n"
                    .toByteArray(Charsets.UTF_8)
            )
            out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(content)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            out.toByteArray()
        }

        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val connection = URL(base).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = Config.TIMEOUT_MS
                    readTimeout = Config.TIMEOUT_MS
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setFixedLengthStreamingMode(body.size)
                }
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val errorStream = if (code in 200..299) null else runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                connection.disconnect()
                if (code in 200..299) {
                    Log.d(TAG, "OSS uploaded $fileName -> $code")
                    return@withContext base + "/" + dirName + fileName
                }
                lastError = Exception("OSS 上传响应非成功状态: $code ${errorStream.orEmpty().take(200)}")
                Log.w(TAG, "OSS upload $fileName failed: $code")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "OSS upload $fileName error: ${e.message}")
            }
            if (attempt < 2) delay(2_000L * (attempt + 1))
        }
        throw lastError ?: Exception("OSS 上传失败")
    }

    // ============================================================================
    // 设备绑定异常
    // ============================================================================

    /**
     * 设备需要绑定异常
     * 喵~ 包含重新登录所需信息喵！
     * 当 login 返回 code=30014 时抛出此异常
     */
    class DeviceBindRequiredException(
        val phone: String,
        val password: String,
        val deviceCode: String
    ) : Exception("设备需要绑定验证")

    class SmsVerificationRequiredException(
        val phone: String,
        val password: String,
        val deviceCode: String
    ) : Exception("需要短信验证码")

    class InvalidVerificationCodeException : Exception("验证码错误，请重新输入")

    // ============================================================================
    // 数据类
    // ============================================================================

    data class LoginResponse(
        val token: String,
        val recentAccountId: String? = null
    )

    data class EcardSelectionResult(
        val parentAccountId: String,
        val selectedAccount: EcardAccount,
        val validAccounts: List<EcardAccount>
    )

    data class EcardAccount(
        val key: String,
        val id: String,
        val parentId: String,
        val userAccountId: String,
        val name: String,
        val grade: String,
        val status: String,
        val mobileStatus: String,
        val outOfDate: String,
        val classId: String,
        val className: String,
        val machineCodeStatus: String
    ) {
        val isValid: Boolean
            get() = status == "0" &&
                outOfDate == "0" &&
                classId.isNotBlank() &&
                mobileStatus == "1"
    }

    data class HomeworkListResponse(
        val baseUrl: String,
        val homeworks: List<HomeworkInfo>
    )

    data class HomeworkInfo(
        val id: String,
        val name: String,
        val contents: List<HomeworkContent>,
        val setType: Int = 0,
        val pk: Int = 0,
        val resourceId: String = "",
        /** 官方试卷集 ID（官方提交链路里作为 set_id；缺失时回退 homework_id） */
        val setId: String = "",
        /** 卷面总分（作业列表中由平台下发，缺失时为 0） */
        val totalScore: Double = 0.0,
        /** 已得分数（未完成时为 null） */
        val point: Double? = null,
        /** 已用时长（秒） */
        val useTime: Int = 0,
        /** 作业所属状态（1 当前 / 2 历史 / 3 过期） */
        val status: String = ""
    )

    data class HomeworkContent(
        val groupName: String,
        val url: String,
        /** 官方资源标识：与 /m/homework/struct 的 contents[].real_id / code_id 对应，
         *  用于把资源包挂到官方模板大题（听选信息1/2/3 这类同名多包必须靠它区分）。 */
        val realId: String = ""
    )




    /**
     * 下载文件到指定路径。
     *
     * 实现位于 [ets100DownloadFile]，这里保留原有公开入口以兼容调用方。
     */
    suspend fun downloadFile(
        url: String,
        destFile: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Unit> = ets100DownloadFile(url, destFile, onProgress)

    // ============================================================================
    // 公开 API（实现位于 ETS100Api.kt）
    // ============================================================================

    suspend fun sendDeviceBindingCode(phone: String): Result<Unit> = ets100SendDeviceBindingCode(phone)

    suspend fun bindDevice(
        phone: String,
        password: String,
        deviceCode: String,
        verificationCode: String = "0",
    ): Result<LoginResponse> = ets100BindDevice(phone, password, deviceCode, verificationCode)

    suspend fun refreshToken(oldToken: String): String? = ets100RefreshToken(oldToken)

    suspend fun login(phone: String, password: String, deviceCode: String): Result<LoginResponse> =
        ets100Login(phone, password, deviceCode)

    suspend fun loginZt(
        login: String,
        userId: String,
        sucUserToken: String,
        deviceCode: String,
    ): Result<LoginResponse> = ets100LoginZt(login, userId, sucUserToken, deviceCode)

    suspend fun getEcardList(token: String, preferredAccountId: String? = null): Result<String> =
        ets100GetEcardList(token, preferredAccountId)

    suspend fun getEcardSelection(
        token: String,
        preferredAccountId: String? = null,
    ): Result<EcardSelectionResult> = ets100GetEcardSelection(token, preferredAccountId)

    suspend fun getHomeworkList(
        token: String,
        parentAccountId: String,
        status: String = CloudHomeworkState.STATUS_CURRENT,
    ): Result<HomeworkListResponse> = ets100GetHomeworkList(token, parentAccountId, status)

    suspend fun getPkInfo(
        token: String,
        homeworkId: String,
        parentAccountId: String = "",
    ): Result<org.json.JSONObject> = ets100GetPkInfo(token, homeworkId, parentAccountId)

    suspend fun getHomeworkStruct(
        token: String,
        homeworkId: String,
        resourceId: String = "",
        parentAccountId: String = "",
    ): Result<org.json.JSONObject> = ets100GetHomeworkStruct(token, homeworkId, resourceId, parentAccountId)

    /** 试卷集结构（含官方模板包地址 -> res.json 的卷面题号与逐题满分）。 */
    suspend fun getHomeworkStructM(
        token: String,
        homeworkId: String,
        resourceId: String = "",
        parentAccountId: String = "",
    ): Result<org.json.JSONObject> = ets100GetHomeworkStructM(token, homeworkId, resourceId, parentAccountId)

    suspend fun getScoreDetail(
        token: String,
        homeworkId: String,
        resourceId: String = "",
        parentAccountId: String = "",
    ): Result<org.json.JSONObject> = ets100GetScoreDetail(token, homeworkId, resourceId, parentAccountId)

    suspend fun getWordStruct(token: String, wordIds: List<Int>): Result<org.json.JSONObject> =
        ets100GetWordStruct(token, wordIds)

    // ============================================================================
    // 自动完成（真实提交）
    // ============================================================================

    /** 作业详情：回读官方 point / total_point / use_time / submit_time。 */
    suspend fun getHomeworkInfo(
        token: String,
        homeworkId: String,
        resourceId: String = "",
        parentAccountId: String = "",
    ): Result<org.json.JSONObject> =
        ets100GetHomeworkInfo(token, homeworkId, resourceId, parentAccountId)

    /** 开始作答：返回最新的 resource_id。 */
    suspend fun checkStart(
        token: String,
        homeworkId: String,
    ): Result<org.json.JSONObject> = ets100CheckStart(token, homeworkId)

    /** 重新提交前清除官方侧旧分数。 */
    suspend fun scoreDrop(
        token: String,
        homeworkId: String,
    ): Result<org.json.JSONObject> = ets100ScoreDrop(token, homeworkId)

    /** 逐题作答同步（官方记账的唯一入口）。 */
    suspend fun syncQuestion(
        token: String,
        params: Map<String, Any>,
    ): Result<org.json.JSONObject> = ets100SyncQuestion(token, params)

    /** 初始化试卷题目资源绑定。 */
    suspend fun pprResources(
        token: String,
        batch: List<Map<String, Any>>,
    ): Result<org.json.JSONObject> = ets100PprResources(token, batch)

    /** 同步作答用时。 */
    suspend fun setUseTime(
        token: String,
        params: Map<String, Any>,
    ): Result<org.json.JSONObject> = ets100SetUseTime(token, params)

    /** 上报逐题成绩。 */
    suspend fun reportScore(
        token: String,
        params: Map<String, Any>,
    ): Result<org.json.JSONObject> = ets100ReportScore(token, params)

    /** 提交作业。 */
    suspend fun submitHomework(
        token: String,
        params: Map<String, Any>,
    ): Result<org.json.JSONObject> = ets100SubmitHomework(token, params)

    /** 预上传：返回官方文件 id（`{id}.mp3` / `{id}.xml` 的文件名）。 */
    suspend fun preUpload(token: String): Result<org.json.JSONObject> = ets100PreUpload(token)

    /** OSS 直传配置：type=audio|xml，id 为预上传返回的文件 id。 */
    suspend fun ossConfig(
        token: String,
        type: String,
        id: String,
    ): Result<org.json.JSONObject> = ets100OssConfig(token, type, id)

}
