package com.shuaiqiu.fuckets100

import android.util.Log

private const val TAG = "ETS100ApiClient"

// ============================================================================
// 自动完成（真实提交）
// ============================================================================

internal fun autoCompleteCommonParams(token: String): MutableMap<String, Any> = mutableMapOf(
    "sn" to ETS100ApiClient.RequestConfig.DEFAULT_SN,
    "token" to token,
    "version" to ETS100ApiClient.RequestConfig.DEFAULT_VERSION,
    "system" to ETS100ApiClient.RequestConfig.DEFAULT_SYSTEM,
    "global_client_version" to ETS100ApiClient.RequestConfig.DEFAULT_GLOBAL_CLIENT_VERSION,
    "sign_response" to 1
)

internal fun autoCompleteHomeworkId(value: String): Any = value.toIntOrNull() ?: value

internal suspend fun autoCompleteRequest(
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
