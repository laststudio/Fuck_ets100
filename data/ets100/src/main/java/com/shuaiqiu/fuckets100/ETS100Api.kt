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

private const val TAG = "ETS100ApiClient"

    // ============================================================================
    // API 方法
    // ============================================================================

    /**
     * 发送设备绑定短信验证码
     * POST /code/send-user-exist
     */
internal suspend fun ets100SendDeviceBindingCode(phone: String): Result<Unit> {
        val bodyData = mapOf(
            "r" to "code/send-user-exist",
            "params" to mapOf(
                "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
                "phone" to phone,
                "email" to "",
                "class_id" to "1",
                "use" to "3",
                "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to "",
                "sign_response" to 1
            )
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.SEND_USER_EXIST, bodyData).mapCatching { responseBody ->
            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)
            if (code != 0) {
                val message = json.optString("msg").ifBlank { "发送验证码失败，错误码: $code" }
                throw Exception(message)
            }
        }
    }

    /**
     * 设备绑定（自动绑定当前设备或使用短信验证码绑定）
     * POST /user/rebind-code
     *
     * 喵~ 当登录返回 code=30014 时调用此接口进行设备绑定
     * 绑定成功后服务器会返回 token，设备自动绑定到账户喵！
     *
     * @param phone 手机号
     * @param password 密码（明文）
     * @param deviceCode 机器码
     * @param verificationCode 短信验证码；无需短信验证时使用默认值 "0"
     * @return 绑定结果，包含 token
     */
internal suspend fun ets100BindDevice(
        phone: String,
        password: String,
        deviceCode: String,
        verificationCode: String = "0"
    ): Result<ETS100ApiClient.LoginResponse> {
        Log.d(TAG, "bindDevice: 开始绑定设备")

        val bodyData = mapOf(
            "r" to "user/rebind-code",
            "params" to mapOf(
                "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
                "phone" to phone,
                "email" to "",            // 空邮箱
                "password" to password,
                "code" to verificationCode,
                "version" to ETS100ApiClient.RequestConfig.DEFAULT_REBIND_VERSION,  // "2"
                "device_name" to ETS100ApiClient.RequestConfig.DEFAULT_REBIND_DEVICE_NAME,  // "1337"
                "device_code" to deviceCode,
                "local_ip" to ETS100ApiClient.RequestConfig.DEFAULT_LOCAL_IP,
                "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
                "sign_response" to 1
            )
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.REBIND_CODE, bodyData).mapCatching { responseBody ->
            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)

            if (code != 0) {
                val msg = json.optString("msg", "设备绑定失败")
                Log.e(TAG, "绑定失败! code=$code, msg=$msg")
                if (code == 60001) {
                    throw ETS100ApiClient.InvalidVerificationCodeException()
                }
                throw Exception("设备绑定失败: $msg")
            }

            val bodyObj = json.optJSONObject("body")
            val token = bodyObj?.optString("token") ?: ""

            if (token.isEmpty()) {
                Log.e(TAG, "❌ 绑定成功但 token 为空！")
                throw Exception("设备绑定失败：未获取到 token")
            }

            Log.i(TAG, "✓ 设备绑定成功！token 长度: ${token.length}")
            ETS100ApiClient.LoginResponse(token = token)
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
internal suspend fun ets100RefreshToken(oldToken: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "refreshToken: 尝试刷新 token...")

            val bodyData = mapOf(
                "r" to "user/rebind-code",
                "params" to mapOf(
                    "token" to oldToken,
                    "version" to ETS100ApiClient.RequestConfig.DEFAULT_REBIND_VERSION,
                    "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM
                )
            )

            ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.REBIND_CODE, bodyData).mapCatching { responseBody ->
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
internal suspend fun ets100Login(phone: String, password: String, deviceCode: String): Result<ETS100ApiClient.LoginResponse> {
        Log.d(TAG, "login: 开始账号登录")

        val bodyData = mapOf(
            "r" to "user/login",
            "params" to mapOf(
                "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
                "phone" to phone,
                "password" to password,
                "device_code" to deviceCode,
                "device_name" to ETS100ApiClient.RequestConfig.DEFAULT_DEVICE_NAME,
                "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
                "local_ip" to ETS100ApiClient.RequestConfig.DEFAULT_LOCAL_IP,
                "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
                "sign_response" to 1
            )
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.LOGIN, bodyData).mapCatching { responseBody ->
            // 喵~ 按照 Python 版本的逻辑：response[0]["body"]["token"]
            val json = responseBody.parseJson()
            val bodyObj = json.optJSONObject("body")

            // 喵~ Python 版本没有 code 检查，直接读取 token 喵！
            // 但为了兼容性，我们检查 code（如果存在且非0才报错）
            val code = json.optInt("code", -999)  // 使用特殊默认值表示字段不存在
            if (code != -999 && code != 0) {
                // 只有当 code 字段存在且非0时才报错
                val msg = json.optString("msg", "")
                Log.e(TAG, "登录失败! code=$code, msg=$msg")
                when (code) {
                    30008 -> throw ETS100ApiClient.SmsVerificationRequiredException(phone, password, deviceCode)
                    30014 -> throw ETS100ApiClient.DeviceBindRequiredException(phone, password, deviceCode)
                }
                val errorMsg = msg.ifEmpty { "登录失败，错误码: $code" }
                throw Exception(errorMsg)
            }

            // 喵~ 直接从 body 读取 token，与 Python 版本一致喵！
            val token = bodyObj?.optString("token") ?: ""
            if (token.isEmpty()) {
                Log.e(TAG, "登录成功响应中 token 为空")
                throw Exception("登录失败：未获取到 token")
            }

            Log.i(TAG, "✓ 登录成功！token 长度: ${token.length}")
            ETS100ApiClient.LoginResponse(token = token)
        }
    }

    /**
     * 讯飞网页登录
     * POST /user/login-zt
     *
     * @param login 手机号（从 checkLogin 请求参数 i 获取）
     * @param userId 畅言用户 ID（从 checkLogin 响应 uid 获取）
     * @param sucUserToken 验证 token（从 checkLogin 响应 captchaResult 获取）
     * @param deviceCode 机器码
     * @return 登录结果，包含 token
     */
internal suspend fun ets100LoginZt(
        login: String,
        userId: String,
        sucUserToken: String,
        deviceCode: String
    ): Result<ETS100ApiClient.LoginResponse> {
        Log.d(TAG, "loginZt: 开始讯飞网页登录")

        val bodyData = mapOf(
            "r" to "user/login-zt",
            "params" to mapOf(
                "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
                "login" to login,
                "user_id" to userId,
                "device_name" to "Android",
                "device_code" to deviceCode,
                "suc_user_token" to sucUserToken,
                "suc_device_name" to "Other",
                "os_type" to "android",
                "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to "",
                "sign_response" to 1
            )
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.LOGIN_ZT, bodyData).mapCatching { responseBody ->
            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)
            Log.d(TAG, "login-zt 响应 code=$code, bodyKeys=${json.optJSONObject("body")?.keys()?.asSequence()?.toList().orEmpty()}")
            if (code != -999 && code != 0) {
                val msg = json.optString("msg", "")
                Log.e(TAG, "login-zt 返回失败: code=$code, msg=$msg")
                throw Exception(msg.ifEmpty { "讯飞登录失败，错误码: $code" })
            }

            val bodyObj = json.optJSONObject("body")
            val token = bodyObj?.optString("token") ?: ""
            if (token.isEmpty()) {
                throw Exception("讯飞登录失败：未获取到 token")
            }

            Log.i(TAG, "✓ 讯飞网页登录成功！token 长度: ${token.length}")
            ETS100ApiClient.LoginResponse(
                token = token,
                recentAccountId = bodyObj?.optString("recent_account_id")?.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * 获取父账户ID列表
     * POST /m/ecard/list
     *
     * @param token 登录返回的 token
     * @return 父账户 ID
     */
internal suspend fun ets100GetEcardList(token: String, preferredAccountId: String? = null): Result<String> {
        return ets100GetEcardSelection(token, preferredAccountId).map { it.parentAccountId }
    }

internal suspend fun ets100GetEcardSelection(
        token: String,
        preferredAccountId: String? = null
    ): Result<ETS100ApiClient.EcardSelectionResult> {
        Log.d(TAG, "getEcardList: preferredAccountId=$preferredAccountId")

        val bodyData = mapOf(
            "r" to "m/ecard/list",
            "params" to mapOf(
                "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
                "token" to token,
                "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
                "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
                "sign_response" to 1
            )
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.ECARD_LIST, bodyData).mapCatching { responseBody ->
            Log.d(TAG, "===== 父账户 API 响应 =====")
            Log.d(TAG, "原始响应: $responseBody")

            // 喵~ 按照 Python 版本：response[0]["body"]["0"]["parent_id"]
            val json = responseBody.parseJson()
            val bodyObj = json.optJSONObject("body")

            Log.d(TAG, "body 对象: $bodyObj")

            // 喵~ body 格式: {"0": {"parent_id": "123456"}}
            if (bodyObj != null && bodyObj.length() > 0) {
                Log.i(TAG, "ecard/list 返回账户条目数: ${bodyObj.length()}")
                val diagnosticKeys = bodyObj.keys()
                while (diagnosticKeys.hasNext()) {
                    val key = diagnosticKeys.next()
                    val account = bodyObj.optJSONObject(key)
                    Log.i(
                        TAG,
                        "ecard[$key]: id=${account?.optString("id")}, " +
                            "parent_id=${account?.optString("parent_id")}, " +
                            "user_account_id=${account?.optString("user_account_id")}, " +
                            "name=${account?.optString("name")}, " +
                            "grade=${account?.optString("grade")}, " +
                            "status=${account?.optString("status")}, " +
                            "mobile_status=${account?.optString("mobile_status")}, " +
                            "out_of_date=${account?.optString("out_of_date")}, " +
                            "class_id=${account?.optString("class_id")}, " +
                            "class_name=${account?.optString("class_name")}, " +
                            "machine_code_status=${account?.optString("machine_code_status")}"
                    )
                }

                val ecardAccounts = parseEcardAccounts(bodyObj)
                val selectedAccount = selectEcardAccount(ecardAccounts, preferredAccountId)
                val firstKey = selectedAccount?.key

                if (firstKey == null) {
                    Log.e(TAG, "body 没有任何字段")
                    throw Exception("未找到账户信息")
                }

                Log.d(TAG, "使用账户 key: $firstKey")

                val parentId = selectedAccount.parentId

                if (parentId.isEmpty()) {
                    Log.e(TAG, "parent_id 为空！打印账户内容: $selectedAccount")
                    throw Exception("未找到账户信息")
                }

                Log.i(TAG, "✓ 获取父账户ID成功: $parentId")
                ETS100ApiClient.EcardSelectionResult(
                    parentAccountId = parentId,
                    selectedAccount = selectedAccount,
                    validAccounts = ecardAccounts.filter { it.isValid }
                )
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
internal suspend fun ets100GetHomeworkList(
        token: String,
        parentAccountId: String,
        status: String = CloudHomeworkState.STATUS_CURRENT
    ): Result<ETS100ApiClient.HomeworkListResponse> {
        Log.d(TAG, "getHomeworkList: parentAccountId=$parentAccountId, status=$status")
        Log.i(
            TAG,
            "准备请求 homework/list: parent_account_id=$parentAccountId, " +
                "status=$status, token=${maskSensitiveToken(token)}, tokenLength=${token.length}"
        )

        val bodyData = mapOf(
            "r" to "g/homework/list",
            "params" to mapOf(
                "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
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
                "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
                "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
                "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
                "sign_response" to 1
            )
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.HOMEWORK_LIST, bodyData).mapCatching { responseBody ->
            Log.d(TAG, "homework_list response: $responseBody")

            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)
            if (code != -999 && code != 0) {
                Log.e(
                    TAG,
                    "homework/list 返回业务错误: code=$code, msg=${json.optString("msg")}, " +
                        "parent_account_id=$parentAccountId, status=$status, token=${maskSensitiveToken(token)}"
                )
            }
            val bodyObj = json.optJSONObject("body")

            if (bodyObj == null) {
                throw Exception("作业列表获取失败")
            }

            val baseUrl = bodyObj.optString("base_url", ETS100ApiClient.Config.CDN_BASE_URL)
            val dataArray = bodyObj.optJSONArray("data") ?: org.json.JSONArray()
            Log.i(TAG, "homework_list parsed: baseUrl=$baseUrl, dataCount=${dataArray.length()}")

            val homeworks = mutableListOf<ETS100ApiClient.HomeworkInfo>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val homeworkId = item.optString("id", "")
                val name = item.optString("name", "未知作业")
                val setType = item.optInt("set_type", 0)
                val pk = item.optInt("pk", 0)
                val resourceId = item.optString("resource_id", "")
                val setId = item.optString("set_id", "")
                val totalScore = item.optDouble("total_score", 0.0)
                val point = if (item.has("point") && !item.isNull("point")) item.optDouble("point", 0.0) else null
                val useTime = item.optInt("use_time", 0)
                val itemStatus = item.optString("status", status)

                // 解析 struct.contents 获取作业详情
                val struct = item.optJSONObject("struct")
                val contentsArray = struct?.optJSONArray("contents") ?: org.json.JSONArray()
                Log.i(
                    TAG,
                    "homework[$i]: id=$homeworkId, name=$name, set_type=$setType, pk=$pk, resource_id=$resourceId, " +
                        "structKeys=${struct?.keys()?.asSequence()?.toList().orEmpty()}, " +
                        "contentsCount=${contentsArray.length()}"
                )

                val contents = mutableListOf<ETS100ApiClient.HomeworkContent>()
                for (j in 0 until contentsArray.length()) {
                    val content = contentsArray.getJSONObject(j)
                    val groupName = content.optString("group_name", "")
                    val url = content.optString("url", "")
                    val realId = content.optString("real_id", content.optString("entity_id", ""))
                    Log.i(TAG, "homework[$i].content[$j]: groupName=$groupName, url=$url, realId=$realId")
                    contents.add(ETS100ApiClient.HomeworkContent(
                        groupName = groupName,
                        url = url,
                        realId = realId
                    ))
                }

                homeworks.add(ETS100ApiClient.HomeworkInfo(
                    id = homeworkId,
                    name = name,
                    contents = contents,
                    setType = setType,
                    pk = pk,
                    resourceId = resourceId,
                    setId = setId,
                    totalScore = totalScore,
                    point = point,
                    useTime = useTime,
                    status = itemStatus
                ))
            }

            ETS100ApiClient.HomeworkListResponse(
                baseUrl = baseUrl,
                homeworks = homeworks
            )
        }
    }

    /**
     * 获取 PK / 在线作业详情
     * POST /g/homework/pk-info
     *
     * @param token 登录返回的 token
     * @param homeworkId 作业 ID
     * @param parentAccountId 父账户 ID
     */
internal suspend fun ets100GetPkInfo(
        token: String,
        homeworkId: String,
        parentAccountId: String = ""
    ): Result<org.json.JSONObject> {
        Log.d(TAG, "getPkInfo: homeworkId=$homeworkId, parentAccountId=$parentAccountId")
        val hwIdVal: Any = homeworkId.toIntOrNull() ?: homeworkId
        val params = mutableMapOf<String, Any>(
            "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
            "token" to token,
            "homework_id" to hwIdVal,
            "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
            "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
            "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
            "sign_response" to 1
        )
        if (parentAccountId.isNotBlank()) {
            params["parent_account_id"] = parentAccountId
        }

        val bodyData = mapOf(
            "r" to "g/homework/pk-info",
            "params" to params
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.HOMEWORK_PK_INFO, bodyData).mapCatching { responseBody ->
            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)
            if (code != -999 && code != 0) {
                val msg = json.optString("msg", "")
                throw Exception("pk-info 失败: $msg ($code)")
            }
            extractBody(json)
        }
    }

    /**
     * 获取作业结构 (在线作业 / 词汇等)
     * POST /g/homework/struct
     *
     * @param token 登录返回的 token
     * @param homeworkId 作业 ID
     * @param resourceId 资源 ID (可选)
     * @param parentAccountId 父账户 ID (可选)
     */
internal suspend fun ets100GetHomeworkStruct(
        token: String,
        homeworkId: String,
        resourceId: String = "",
        parentAccountId: String = ""
    ): Result<org.json.JSONObject> {
        Log.d(TAG, "getHomeworkStruct: homeworkId=$homeworkId, resourceId=$resourceId, parentAccountId=$parentAccountId")
        val params = mutableMapOf<String, Any>(
            "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
            "token" to token,
            "id" to homeworkId,
            "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
            "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
            "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
            "sign_response" to 1
        )
        if (resourceId.isNotBlank()) {
            params["resource_id"] = resourceId
        }
        if (parentAccountId.isNotBlank()) {
            params["parent_account_id"] = parentAccountId
        }

        val bodyData = mapOf(
            "r" to "g/homework/struct",
            "params" to params
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.HOMEWORK_STRUCT, bodyData).mapCatching { responseBody ->
            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)
            if (code != -999 && code != 0) {
                val msg = json.optString("msg", "")
                throw Exception("homework/struct 失败: $msg ($code)")
            }
            extractBody(json)
        }
    }

    /**
     * 获取试卷集结构 (含模板包地址)
     * POST /m/homework/struct
     *
     * 这是拿官方 `res.json`（卷面题号 + 逐题满分权威来源）的唯一入口：
     * `body.audio[0].template.url` 是模板包（未加密），`contents[].code_id` 与模板
     * `exam_list[].exam_id`（`{{st1}}`）一一对应，用作"资源包 → 官方大题"的挂载键。
     *
     * @param token 登录返回的 token
     * @param homeworkId 作业 ID
     * @param resourceId 资源 ID（如 58/1783/1784，影响题目池）
     * @param parentAccountId 父账户 ID（可选）
     */
    internal suspend fun ets100GetHomeworkStructM(
        token: String,
        homeworkId: String,
        resourceId: String = "",
        parentAccountId: String = ""
    ): Result<org.json.JSONObject> {
        Log.d(TAG, "getHomeworkStructM: homeworkId=$homeworkId, resourceId=$resourceId")
        val params = mutableMapOf<String, Any>(
            "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
            "token" to token,
            "id" to homeworkId,
            "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
            "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
            "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
            "sign_response" to 1
        )
        if (resourceId.isNotBlank()) {
            params["resource_id"] = resourceId
        }
        if (parentAccountId.isNotBlank()) {
            params["parent_account_id"] = parentAccountId
        }
        val bodyData = mapOf("r" to "m/homework/struct", "params" to params)
        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.HOMEWORK_STRUCT_M, bodyData)
            .mapCatching { responseBody ->
                val json = responseBody.parseJson()
                val code = json.optInt("code", -999)
                if (code != -999 && code != 0) {
                    throw Exception("m/homework/struct 失败: ${json.optString("msg", "")} ($code)")
                }
                extractBody(json)
            }
    }

    /**
     * 获取已完成作业得分与单词详情 (逆向历史作业)
     * POST /g/homework/score-detail
     */
internal suspend fun ets100GetScoreDetail(
        token: String,
        homeworkId: String,
        resourceId: String = "",
        parentAccountId: String = ""
    ): Result<org.json.JSONObject> {
        Log.d(TAG, "getScoreDetail: homeworkId=$homeworkId, resourceId=$resourceId, parentAccountId=$parentAccountId")
        val hwIdVal: Any = homeworkId.toIntOrNull() ?: homeworkId
        val params = mutableMapOf<String, Any>(
            "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
            "token" to token,
            "homework_id" to hwIdVal,
            "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
            "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
            "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
            "sign_response" to 1
        )
        if (resourceId.isNotBlank()) {
            params["resource_id"] = resourceId
        }
        if (parentAccountId.isNotBlank()) {
            params["parent_account_id"] = parentAccountId
        }

        val bodyData = mapOf(
            "r" to "g/homework/score-detail",
            "params" to params
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.HOMEWORK_SCORE_DETAIL, bodyData).mapCatching { responseBody ->
            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)
            if (code != -999 && code != 0) {
                val msg = json.optString("msg", "")
                throw Exception("score-detail 失败: $msg ($code)")
            }
            extractBody(json)
        }
    }

    /**
     * 批量获取单词与题目定义结构
     * POST /g/word/struct
     */
internal suspend fun ets100GetWordStruct(
        token: String,
        wordIds: List<Int>
    ): Result<org.json.JSONObject> {
        Log.d(TAG, "getWordStruct: count=${wordIds.size}")
        val params = mutableMapOf<String, Any>(
            "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
            "token" to token,
            "word_ids" to wordIds,
            "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
            "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
            "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
            "sign_response" to 1
        )

        val bodyData = mapOf(
            "r" to "g/word/struct",
            "params" to params
        )

        return ETS100ApiClient.postRequest(ETS100ApiClient.Endpoints.WORD_STRUCT, bodyData).mapCatching { responseBody ->
            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)
            if (code != -999 && code != 0) {
                val msg = json.optString("msg", "")
                throw Exception("word/struct 失败: $msg ($code)")
            }
            extractBody(json)
        }
    }

    // ============================================================================
    // 自动完成（真实提交）
    // ============================================================================

    private fun autoCompleteCommonParams(token: String): MutableMap<String, Any> = mutableMapOf(
        "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
        "token" to token,
        "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
        "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
        "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
        "sign_response" to 1
    )

    private fun autoCompleteHomeworkId(value: String): Any = value.toIntOrNull() ?: value

    private suspend fun autoCompleteRequest(
        endpoint: String,
        route: String,
        params: Map<String, Any>,
    ): Result<org.json.JSONObject> {
        val bodyData = mapOf("r" to route, "params" to params)
        return ETS100ApiClient.postRequest(endpoint, bodyData).mapCatching { responseBody ->
            val json = responseBody.parseJson()
            val code = json.optInt("code", -999)
            if (code != -999 && code != 0) {
                throw Exception("$route 失败: ${json.optString("msg", "")} ($code)")
            }
            extractBody(json)
        }
    }

    /** POST /m/homework/info（官方 egress 口径：version=2 + parent_account_id） */
    internal suspend fun ets100GetHomeworkInfo(
        token: String,
        homeworkId: String,
        resourceId: String = "",
        parentAccountId: String = ""
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params["version"] = "2"
        params["homework_id"] = autoCompleteHomeworkId(homeworkId)
        params["resource_id"] = resourceId
        if (parentAccountId.isNotBlank()) params["parent_account_id"] = parentAccountId
        Log.d(TAG, "getHomeworkInfo: homeworkId=$homeworkId, resourceId=$resourceId, parentAccountId=$parentAccountId")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.HOMEWORK_INFO,
            "m/homework/info",
            params
        )
    }

    /** POST /g/homework/checkstart（返回最新的 resource_id） */
    internal suspend fun ets100CheckStart(
        token: String,
        homeworkId: String
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params["homework_id"] = autoCompleteHomeworkId(homeworkId)
        Log.d(TAG, "checkStart: homeworkId=$homeworkId")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.HOMEWORK_CHECKSTART,
            "g/homework/checkstart",
            params
        )
    }

    /** POST /g/homework/score-drop（重新提交前清掉官方旧分：resource_id/seq_id 置空、不带 parent） */
    internal suspend fun ets100ScoreDrop(
        token: String,
        homeworkId: String
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params["homework_id"] = autoCompleteHomeworkId(homeworkId)
        params["resource_id"] = ""
        params["seq_id"] = ""
        Log.d(TAG, "scoreDrop: homeworkId=$homeworkId")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.HOMEWORK_SCORE_DROP,
            "g/homework/score-drop",
            params
        )
    }

    /** POST /m/audio/sync-v2（逐题作答同步：官方记账的唯一入口） */
    internal suspend fun ets100SyncQuestion(
        token: String,
        extraParams: Map<String, Any>
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params.putAll(extraParams)
        val order = extraParams["order"]
        Log.d(TAG, "sync-v2: entity=${extraParams["entity_id"]}, order=$order")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.AUDIO_SYNC_V2,
            "m/audio/sync-v2",
            params
        )
    }

    /** POST /m/file/pre-upload（作答文件预上传，返回官方文件 id） */
    internal suspend fun ets100PreUpload(
        token: String
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        Log.d(TAG, "pre-upload")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.FILE_PRE_UPLOAD,
            "m/file/pre-upload",
            params
        )
    }

    /** POST /system/oss-config（OSS 直传配置：host/dir/accessid/policy/signature/callback） */
    internal suspend fun ets100OssConfig(
        token: String,
        type: String,
        id: String
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params["type"] = type
        params["id"] = id
        Log.d(TAG, "oss-config: type=$type, id=$id")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.SYSTEM_OSS_CONFIG,
            "system/oss-config",
            params
        )
    }

    /** POST /g/set/ppr-res（初始化试卷题目资源绑定） */
    internal suspend fun ets100PprResources(
        token: String,
        batch: List<Map<String, Any>>
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params["batch"] = batch
        Log.d(TAG, "pprResources: count=${batch.size}")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.SET_PPR_RES,
            "g/set/ppr-res",
            params
        )
    }

    /** POST /m/homework/set-use-time */
    internal suspend fun ets100SetUseTime(
        token: String,
        extraParams: Map<String, Any>
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params.putAll(extraParams)
        Log.d(TAG, "setUseTime: $extraParams")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.HOMEWORK_SET_USE_TIME,
            "m/homework/set-use-time",
            params
        )
    }

    /** POST /g/homework/report-score */
    internal suspend fun ets100ReportScore(
        token: String,
        extraParams: Map<String, Any>
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params.putAll(extraParams)
        Log.d(TAG, "reportScore: detailCount=${(extraParams["detail"] as? List<*>)?.size ?: 0}")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.HOMEWORK_REPORT_SCORE,
            "g/homework/report-score",
            params
        )
    }

    /** POST /m/homework/submit */
    internal suspend fun ets100SubmitHomework(
        token: String,
        extraParams: Map<String, Any>
    ): Result<org.json.JSONObject> {
        val params = autoCompleteCommonParams(token)
        params.putAll(extraParams)
        Log.d(TAG, "submitHomework: $extraParams")
        return autoCompleteRequest(
            ETS100ApiClient.Endpoints.HOMEWORK_SUBMIT,
            "m/homework/submit",
            params
        )
    }
