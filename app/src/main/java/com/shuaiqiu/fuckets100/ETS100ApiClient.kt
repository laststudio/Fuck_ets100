package com.shuaiqiu.fuckets100

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        const val ECARD_LIST = "/m/ecard/list"
        const val HOMEWORK_LIST = "/g/homework/list"
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
    private suspend fun postRequest(endpoint: String, bodyData: Map<String, Any>): Result<String> = withContext(Dispatchers.IO) {
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
            Log.d(TAG, "bodyArrayJson: $bodyArrayJson")
            val bodyBase64 = Base64.encodeToString(bodyArrayJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val sign = generateSign(timestamp, bodyBase64)

            // 构建 head JSON（紧凑格式，无空格）
            val headJson = "{\"version\":\"1.0\",\"sign\":\"$sign\",\"pid\":\"${Config.PID}\",\"time\":$timestamp}"

            Log.d(TAG, "===== 请求详情 =====")
            Log.d(TAG, "body_json: $bodyArrayJson")
            Log.d(TAG, "body_base64: $bodyBase64")
            Log.d(TAG, "sign: $sign")
            Log.d(TAG, "head_json: $headJson")

            // 构建完整的请求数据 (JSON 格式，直接发送给服务器)
            // 注意：Python 参考代码使用 data=payload_json，所以这里要直接发送 JSON 字符串
            val requestJson = "{\"body\":\"$bodyBase64\",\"head\":$headJson}"
            Log.d(TAG, "完整请求JSON: $requestJson")

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
                    Log.d(TAG, "errorStream 内容: ${errorContent ?: "为空"}")
                    errorContent ?: "无 errorStream 内容"
                } catch (e: Exception) {
                    Log.e(TAG, "读取 errorStream 失败: ${e.message}")
                    "errorStream 读取失败: ${e.message}"
                }
            }

            Log.d(TAG, "POST $endpoint -> $responseCode")
            Log.d(TAG, "Response body: $responseBody")
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(responseBody)
            } else {
                Log.e(TAG, "HTTP Error: $responseCode, body: $responseBody")
                Result.failure(Exception("HTTP Error $responseCode: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}", e)
            Result.failure(e)
        }
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

    // ============================================================================
    // API 方法
    // ============================================================================

    /**
     * 设备绑定（自动绑定当前设备）
     * POST /user/rebind-code
     *
     * 喵~ 当登录返回 code=30014 时调用此接口进行设备绑定
     * 绑定成功后服务器会返回 token，设备自动绑定到账户喵！
     *
     * @param phone 手机号
     * @param password 密码（明文）
     * @param deviceCode 机器码
     * @return 绑定结果，包含 token
     */
    suspend fun bindDevice(phone: String, password: String, deviceCode: String): Result<LoginResponse> {
        Log.d(TAG, "bindDevice: phone=$phone, deviceCode=$deviceCode")

        val bodyData = mapOf(
            "r" to "user/rebind-code",
            "params" to mapOf(
                "sn" to RequestConfig.DEFAULT_SN,
                "phone" to phone,
                "email" to "",            // 空邮箱
                "password" to password,
                "code" to "0",            // 固定为 "0"
                "version" to RequestConfig.DEFAULT_REBIND_VERSION,  // "2"
                "device_name" to RequestConfig.DEFAULT_REBIND_DEVICE_NAME,  // "1337"
                "device_code" to deviceCode,
                "local_ip" to RequestConfig.DEFAULT_LOCAL_IP,
                "system" to RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
                "sign_response" to 1
            )
        )

        return postRequest(Endpoints.REBIND_CODE, bodyData).mapCatching { responseBody ->
            Log.d(TAG, "===== 设备绑定 API 响应 =====")
            Log.d(TAG, "原始响应: $responseBody")

            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)

            if (code != 0) {
                val msg = json.optString("msg", "设备绑定失败")
                Log.e(TAG, "绑定失败! code=$code, msg=$msg")
                throw Exception("设备绑定失败: $msg")
            }

            val bodyObj = json.optJSONObject("body")
            val token = bodyObj?.optString("token") ?: ""

            if (token.isEmpty()) {
                Log.e(TAG, "❌ 绑定成功但 token 为空！")
                throw Exception("设备绑定失败：未获取到 token")
            }

            Log.i(TAG, "✓ 设备绑定成功！token 长度: ${token.length}")
            LoginResponse(token = token)
        }
    }

    /**
     * 刷新 Token
     * POST /user/rebind-code
     * 
     * 宝贝这个方法用于在 token 过期时自动刷新喵~
     * 
     * @param oldToken 旧 token
     * @return 新 token，失败返回 null
     */
    suspend fun refreshToken(oldToken: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "refreshToken: 尝试刷新 token...")
            
            val bodyData = mapOf(
                "r" to "user/rebind-code",
                "params" to mapOf(
                    "token" to oldToken,
                    "version" to RequestConfig.DEFAULT_REBIND_VERSION,
                    "system" to RequestConfig.DEFAULT_SYSTEM
                )
            )
            
            postRequest(Endpoints.REBIND_CODE, bodyData).mapCatching { responseBody ->
                Log.d(TAG, "refreshToken response: $responseBody")
                val json = responseBody.parseJson()
                val bodyObj = json.optJSONObject("body")
                bodyObj?.optString("token") ?: throw Exception("刷新 token 失败")
            }.getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "refreshToken 失败: ${e.message}")
            null
        }
    }

    /**
     * 用户登录
     * POST /user/login
     *
     * @param phone 手机号
     * @param password 密码（明文）
     * @param deviceCode 机器码
     * @return 登录结果，包含 token
     */
    suspend fun login(phone: String, password: String, deviceCode: String): Result<LoginResponse> {
        Log.d(TAG, "login: phone=$phone, deviceCode=$deviceCode")
        
        val bodyData = mapOf(
            "r" to "user/login",
            "params" to mapOf(
                "sn" to RequestConfig.DEFAULT_SN,
                "phone" to phone,
                "password" to password,
                "device_code" to deviceCode,
                "device_name" to RequestConfig.DEFAULT_DEVICE_NAME,
                "version" to RequestConfig.DEFAULT_VERSION,
                "local_ip" to RequestConfig.DEFAULT_LOCAL_IP,
                "system" to RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
                "sign_response" to 1
            )
        )

        return postRequest(Endpoints.LOGIN, bodyData).mapCatching { responseBody ->
            Log.d(TAG, "===== 登录 API 响应 =====")
            Log.d(TAG, "原始响应: $responseBody")
            
            // 喵~ 按照 Python 版本的逻辑：response[0]["body"]["token"]
            val json = responseBody.parseJson()
            val bodyObj = json.optJSONObject("body")
            
            Log.d(TAG, "body 对象: $bodyObj")
            
            // 喵~ Python 版本没有 code 检查，直接读取 token 喵！
            // 但为了兼容性，我们检查 code（如果存在且非0才报错）
            val code = json.optInt("code", -999)  // 使用特殊默认值表示字段不存在
            if (code != -999 && code != 0) {
                // 只有当 code 字段存在且非0时才报错
                val msg = json.optString("msg", "")
                Log.e(TAG, "登录失败! code=$code, msg=$msg")
                if (code == 30014) {
                    // 喵~ 设备需要绑定，抛出特殊异常喵！
                    throw DeviceBindRequiredException(phone, password, deviceCode)
                }
                val errorMsg = msg.ifEmpty { "登录失败，错误码: $code" }
                throw Exception(errorMsg)
            }
            
            // 喵~ 直接从 body 读取 token，与 Python 版本一致喵！
            val token = bodyObj?.optString("token") ?: ""
            Log.d(TAG, "解析到的 token: ${if (token.isEmpty()) "(空)" else token.take(20) + "..."}")
            
            if (token.isEmpty()) {
                Log.e(TAG, "❌ token 为空！打印 body 所有字段进行调试...")
                bodyObj?.let { obj ->
                    val iterator = obj.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        Log.d(TAG, "  $key = ${obj.opt(key)}")
                    }
                }
                throw Exception("登录失败：未获取到 token")
            }
            
            Log.i(TAG, "✓ 登录成功！token 长度: ${token.length}")
            LoginResponse(token = token)
        }
    }

    /**
     * 获取父账户ID列表
     * POST /m/ecard/list
     * 
     * @param token 登录返回的 token
     * @return 父账户 ID
     */
    suspend fun getEcardList(token: String): Result<String> {
        Log.d(TAG, "getEcardList")
        
        val bodyData = mapOf(
            "r" to "m/ecard/list",
            "params" to mapOf(
                "sn" to RequestConfig.DEFAULT_SN,
                "token" to token,
                "version" to RequestConfig.DEFAULT_VERSION,
                "system" to RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
                "sign_response" to 1
            )
        )

        return postRequest(Endpoints.ECARD_LIST, bodyData).mapCatching { responseBody ->
            Log.d(TAG, "===== 父账户 API 响应 =====")
            Log.d(TAG, "原始响应: $responseBody")
            
            // 喵~ 按照 Python 版本：response[0]["body"]["0"]["parent_id"]
            val json = responseBody.parseJson()
            val bodyObj = json.optJSONObject("body")
            
            Log.d(TAG, "body 对象: $bodyObj")
            
            // 喵~ body 格式: {"0": {"parent_id": "123456"}}
            if (bodyObj != null && bodyObj.length() > 0) {
                // 获取第一个 key（通常是 "0"）
                val iterator = bodyObj.keys()
                val firstKey = if (iterator.hasNext()) iterator.next() else null
                
                if (firstKey == null) {
                    Log.e(TAG, "body 没有任何字段")
                    throw Exception("未找到账户信息")
                }
                
                Log.d(TAG, "使用第一个账户 key: $firstKey")
                
                val firstAccount = bodyObj.optJSONObject(firstKey)
                val parentId = firstAccount?.optString("parent_id") ?: ""
                
                if (parentId.isEmpty()) {
                    Log.e(TAG, "parent_id 为空！打印账户内容: $firstAccount")
                    throw Exception("未找到账户信息")
                }
                
                Log.i(TAG, "✓ 获取父账户ID成功: $parentId")
                parentId
            } else {
                Log.e(TAG, "bodyObj 为空或长度为0")
                throw Exception("未找到账户信息")
            }
        }
    }

    /**
     * 获取历史作业列表
     * POST /g/homework/list
     *
     * @param token 登录返回的 token
     * @param parentAccountId 父账户 ID
     * @return 历史作业列表响应（status=2 获取历史作业喵~）
     */
    suspend fun getHomeworkList(
        token: String,
        parentAccountId: String,
        status: String = CloudHomeworkState.STATUS_CURRENT
    ): Result<HomeworkListResponse> {
        Log.d(TAG, "getHomeworkList: parentAccountId=$parentAccountId, status=$status")

        val bodyData = mapOf(
            "r" to "g/homework/list",
            "params" to mapOf(
                "sn" to RequestConfig.DEFAULT_SN,
                "token" to token,
                "parent_account_id" to parentAccountId,
                "limit" to "0",
                "status" to status,
                "offset" to "0",
                "max_end_time" to "",
                "max_homework_id" to "",
                "min_end_time" to "",
                "min_homework_id" to "",
                "get_to_do_count" to 1,
                "show_old_homework" to 1,
                "parent_homework_id" to "",
                "get_all_count" to 1,
                "check_pass" to 1,
                "get_to_overtime_count" to 1,
                "version" to RequestConfig.DEFAULT_VERSION,
                "system" to RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
                "sign_response" to 1
            )
        )

        return postRequest(Endpoints.HOMEWORK_LIST, bodyData).mapCatching { responseBody ->
            Log.d(TAG, "homework_list response: $responseBody")
            
            val json = responseBody.parseJson()
            val bodyObj = json.optJSONObject("body")
            
            if (bodyObj == null) {
                throw Exception("作业列表获取失败")
            }
            
            val baseUrl = bodyObj.optString("base_url", Config.CDN_BASE_URL)
            val dataArray = bodyObj.optJSONArray("data") ?: org.json.JSONArray()
            
            val homeworks = mutableListOf<HomeworkInfo>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val name = item.optString("name", "未知作业")
                
                // 解析 struct.contents 获取作业详情
                val struct = item.optJSONObject("struct")
                val contentsArray = struct?.optJSONArray("contents") ?: org.json.JSONArray()
                
                val contents = mutableListOf<HomeworkContent>()
                for (j in 0 until contentsArray.length()) {
                    val content = contentsArray.getJSONObject(j)
                    contents.add(HomeworkContent(
                        groupName = content.optString("group_name", ""),
                        url = content.optString("url", "")
                    ))
                }
                
                homeworks.add(HomeworkInfo(
                    name = name,
                    contents = contents
                ))
            }
            
            HomeworkListResponse(
                baseUrl = baseUrl,
                homeworks = homeworks
            )
        }
    }

    // ============================================================================
    // 数据类
    // ============================================================================
    
    data class LoginResponse(
        val token: String
    )
    
    data class HomeworkListResponse(
        val baseUrl: String,
        val homeworks: List<HomeworkInfo>
    )
    
    data class HomeworkInfo(
        val name: String,
        val contents: List<HomeworkContent>
    )
    
    data class HomeworkContent(
        val groupName: String,
        val url: String
    )

    // ============================================================================
    // JSON 解析工具
    // ============================================================================
    
    /**
     * 简化 JSON 字符串解析
     * 喵~ 支持 JSON 对象和数组喵！
     * 如果响应是数组（如 [{...}]），返回数组第一个元素作为 JSONObject
     */
    private fun String.parseJson(): org.json.JSONObject {
        val trimmed = this.trim()
        return if (trimmed.startsWith("[")) {
            // 响应是数组，取第一个元素
            val array = org.json.JSONArray(trimmed)
            if (array.length() > 0) {
                array.getJSONObject(0)
            } else {
                org.json.JSONObject()
            }
        } else {
            org.json.JSONObject(this)
        }
    }
    
    /**
     * 检查响应是否为数组格式
     */
    private fun String.isJsonArray(): Boolean {
        return this.trim().startsWith("[")
    }
    
    /**
     * Map 转 JSON 字符串
     */
    private fun Map<String, Any>.toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(",")
            sb.append("\"$key\":")
            when (value) {
                is String -> sb.append("\"${value.escapeJson()}\"")
                is Number -> sb.append(value)
                is Boolean -> sb.append(value)
                is Map<*, *> -> sb.append((value as Map<String, Any>).toJson())
                else -> sb.append("\"${value.toString().escapeJson()}\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }
    
    /**
     * JSON 字符串转 Map
     */
    private fun String.toMapJson(): String {
        return "{$this}"
    }
    
/**
     * 下载文件到指定路径
     * 宝贝用于下载云端作业的 ZIP 文件喵~
     *
     * @param url 文件 URL
     * @param destFile 目标文件
     * @return 下载结果
     */
    suspend fun downloadFile(
        url: String,
        destFile: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "downloadFile: 开始下载 $url")
            val urlObj = URL(url)
            val connection = urlObj.openConnection() as HttpURLConnection

            // 宝贝如果是 HTTPS URL，需要处理 SSL 证书验证问题喵~
            // 因为 CDN 的 SSL 证书配置有问题（证书是发给 default.chinanetcenter.com 的）
            if (connection is javax.net.ssl.HttpsURLConnection) {
                // 创建一个信任所有证书的 TrustManager喵~
                val trustAllCerts = arrayOf(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                connection.sslSocketFactory = sslContext.socketFactory
                // 宝贝也忽略主机名验证喵~
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }

            connection.connectTimeout = Config.TIMEOUT_MS
            connection.readTimeout = Config.TIMEOUT_MS

            // 设置 CDN 请求头（参考 release.py）
            val host = if (url.contains("cdn.subject.ets100.com")) "cdn.subject.ets100.com" else "api.ets100.com"
            connection.setRequestProperty("Host", host)
            connection.setRequestProperty("User-Agent", "libcurl-agent/1.0")
            connection.setRequestProperty("Accept", "*/*")

            connection.connect()

            val responseCode = connection.responseCode
            Log.d(TAG, "downloadFile: HTTP Response = $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 使用缓冲流提高下载效率
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                val totalBytes = connection.contentLengthLong

                connection.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            onProgress(totalBytesRead, totalBytes)
                        }
                    }
                }

                Log.d(TAG, "downloadFile: 下载完成 ${destFile.absolutePath}, 大小=$totalBytesRead bytes")
                Result.success(Unit)
            } else {
                // 读取错误信息
                val errorStream = connection.errorStream
                val errorMsg = if (errorStream != null) {
                    errorStream.bufferedReader().readText()
                } else {
                    "HTTP $responseCode"
                }
                Log.e(TAG, "downloadFile: 下载失败 $errorMsg")
                Result.failure(Exception("下载失败: $errorMsg"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile: 下载异常 ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 转义 JSON 字符串
     * 喵~ 用于安全地处理字符串喵！
     */
    private fun String.escapeJson(): String {
        return this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
