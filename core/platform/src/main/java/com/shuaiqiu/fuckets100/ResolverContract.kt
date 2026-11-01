package com.shuaiqiu.fuckets100

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析器跨平台固定契约（contract v1）的 Kotlin 侧薄封装。
 *
 * 目标：业务代码不再手拼 JSON、不再自己 switch 题型枚举。
 * 所有能力都通过同一个 `op` 通道获取，解析器新增能力时**本文件不需要改**，
 * 只要业务代码调用新的 `op` 即可（可用 [capabilities] 探测 `status`）。
 *
 * 对应 Rust 侧 `src/contract.rs`；契约文档 `docs/INTERFACE.md`。
 */
object ResolverContract {

    const val VERSION = 1

    /** 一次调用的结果。失败时 [errorCode] 非空，客户端只需判断 [ok]。 */
    data class Response(
        val ok: Boolean,
        val op: String,
        val result: JSONObject?,
        val resultArray: JSONArray?,
        val meta: JSONObject?,
        val errorCode: String?,
        val errorMessage: String?,
        val errorHint: String?,
        val raw: JSONObject,
    ) {
        /** `meta.layer == "none"` 表示官方结构超出认知，应上报 [shapeHash] 以便跟进。 */
        val layer: String? get() = meta?.optString("layer")?.takeIf { it.isNotBlank() }

        val family: String? get() = meta?.optString("family")?.takeIf { it.isNotBlank() }

        val shapeHash: String? get() = meta?.optString("shape")?.takeIf { it.isNotBlank() }

        /** false = 不在官方权威题型表里：要么是新题型，要么是方言别名。 */
        val specKnown: Boolean get() = meta?.optBoolean("spec_known", true) ?: true

        val questionCount: Int get() = meta?.optInt("question_count") ?: 0
    }

    private fun parse(rawText: String): Response {
        val raw = runCatching { JSONObject(rawText) }.getOrNull()
            ?: return Response(
                ok = false,
                op = "",
                result = null,
                resultArray = null,
                meta = null,
                errorCode = "invalid_response",
                errorMessage = "解析器返回了无法解析的响应",
                errorHint = null,
                raw = JSONObject(),
            )
        val error = raw.optJSONObject("error")
        val resultValue = raw.opt("result")
        return Response(
            ok = raw.optBoolean("ok", false),
            op = raw.optString("op", ""),
            result = resultValue as? JSONObject,
            resultArray = resultValue as? JSONArray,
            meta = raw.optJSONObject("meta"),
            errorCode = error?.optString("code"),
            errorMessage = error?.optString("message"),
            errorHint = error?.optString("hint"),
            raw = raw,
        )
    }

    /** 统一入口：构造请求信封并调用 native。任何异常都被收敛成失败响应。 */
    fun call(op: String, payload: JSONObject? = null): Response {
        val request = JSONObject()
            .put("version", VERSION)
            .put("op", op)
        if (payload != null) {
            request.put("payload", payload)
        }
        return try {
            parse(ResolverNative.resolveRequest(request.toString()))
        } catch (e: Throwable) {
            Response(
                ok = false,
                op = op,
                result = null,
                resultArray = null,
                meta = null,
                errorCode = "native_error",
                errorMessage = e.message ?: "解析器调用异常",
                errorHint = null,
                raw = JSONObject(),
            )
        }
    }

    /** 能力清单：ops / 官方题型全集 / 分类枚举 / 错误码 / 覆盖率。 */
    /**
     * 能力清单（契约信封形态：`{ok,result:{contract_version, ops[], …}}`）。
     *
     * 注意：`ResolverNative.capabilities()` 返回的是**裸能力对象**（库内 `capabilities_value()`，
     * 没有 `{ok,result}` 信封），不能直接交给 [parse] —— 早期实现就是那样，导致
     * `ok=false`、`supports()` 恒为 false。这里统一走契约 op；需要裸对象时用 [capabilitiesRaw]。
     */
    fun capabilities(): Response = call("capabilities")

    /** 裸能力对象（无信封）。仅在需要与旧接口对齐时使用。 */
    fun capabilitiesRaw(): JSONObject? = try {
        JSONObject(ResolverNative.capabilities())
    } catch (_: Throwable) {
        null
    }

    /** 某 op 是否可用（`status == "stable"`）。 */
    fun supports(op: String): Boolean {
        val ops = capabilities().result?.optJSONArray("ops") ?: return false
        for (i in 0 until ops.length()) {
            val item = ops.optJSONObject(i) ?: continue
            if (item.optString("op") == op) return item.optString("status") == "stable"
        }
        return false
    }

    // ---------------- 解析 ----------------

    /** 解析单份资源包。返回值同时给出题目数组与 meta（layer/family/shape）。 */
    fun parseTask(
        content: String,
        info: String = "",
        content2: String = "",
    ): Response {
        val payload = JSONObject().put("content", content)
        if (info.isNotBlank()) payload.put("info", info)
        if (content2.isNotBlank()) payload.put("content2", content2)
        return call("parse_task", payload)
    }

    /**
     * 习题集（老师布置的一整套多卷）：把多包解析结果合并成一张卷。
     *
     * @param packages `[{structure_type, group_name, real_id?, order?, questions[]}]`
     * @param resJson  官方 res.json（可选但强烈建议：权威大题顺序与逐题满分）
     * @param homework 作业列表 item（可选：用于分类与标题）
     */
    fun parseHomeworkSet(
        packages: JSONArray,
        resJson: String? = null,
        homework: JSONObject? = null,
    ): Response {
        val payload = JSONObject().put("packages", packages)
        if (!resJson.isNullOrBlank()) payload.put("res", resJson)
        if (homework != null) payload.put("homework", homework)
        return call("parse_homework_set", payload)
    }

    /** 官方 res.json 规范化：大题顺序、大题分、逐题满分。 */
    fun resTemplate(resJson: String): Response =
        call("res_template", JSONObject().put("res", resJson))

    /**
     * 把官方 res.json 的**题号与逐题满分**盖到已解析题目上。
     *
     * 只补 `no` / `score`（不覆盖已有题号、不改内容与答案）。
     * 分节定位优先 [structureType]（`exam_type_collect`），找不到再用第 0 节。
     */
    fun applyResTemplate(
        resJson: String,
        questions: JSONArray,
        structureType: String? = null,
        sectionIndex: Int? = null,
    ): Response {
        val payload = JSONObject()
            .put("res", resJson)
            .put("questions", questions)
        if (!structureType.isNullOrBlank()) payload.put("structure_type", structureType)
        if (sectionIndex != null) payload.put("section_index", sectionIndex)
        return call("apply_res_template", payload)
    }

    /** 官方权威规格：题型全集、词汇双通道、展示要素、res.json 权威路径与边界规则。 */
    fun spec(): Response = call("spec")

    /**
     * 官方题型全集（`structure_type` 白名单，来自 `op: spec`）。
     *
     * 解析成功但 `structure_type` 不在其中，说明官方加了新题型：应当上报，而不是静默吞掉。
     * 取不到时返回 null（不阻断解析主流程）。
     */
    fun officialStructureTypes(): Set<String>? {
        officialStructureTypesCache?.let { cached ->
            if (cached.isNotEmpty()) return cached
        }
        val types = mutableSetOf<String>()
        val array = spec().result?.optJSONArray("structure_types") ?: return null
        for (i in 0 until array.length()) {
            val id = array.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank()) types.add(id)
        }
        if (types.isEmpty()) return null
        officialStructureTypesCache = types
        return types
    }

    @Volatile
    private var officialStructureTypesCache: Set<String>? = null

    // ---------------- 诊断与分类 ----------------

    /** 解析诊断（不含题目文本，可安全打日志）。 */
    fun diagnose(content: String, info: String = "", content2: String = ""): Response {
        val payload = JSONObject().put("content", content)
        if (info.isNotBlank()) payload.put("info", info)
        if (content2.isNotBlank()) payload.put("content2", content2)
        return call("diagnose", payload)
    }

    /** 作业分类：`is_exercise_set` = 老师布置的一整套多卷（习题集）。 */
    fun classifyHomework(homework: JSONObject): Response =
        call("classify_homework", JSONObject().put("homework", homework))

    /** 作业列表批量分类：返回 `category_counts` 与 `exercise_set_ids`。 */
    fun classifyHomeworkList(homeworks: JSONArray): Response =
        call("classify_homework_list", JSONObject().put("homeworks", homeworks))

    /**
     * 解析失败/为空时上报形状指纹用的最小载荷（不含题目文本）。
     * 返回 null 表示连诊断都拿不到，调用方可放弃上报。
     */
    fun shapeFingerprintOf(content: String, info: String = "", content2: String = ""): String? {
        val payload = JSONObject().put("content", content)
        if (info.isNotBlank()) payload.put("info", info)
        if (content2.isNotBlank()) payload.put("content2", content2)
        return call("shape_fingerprint", payload).result?.optString("hash")?.takeIf { it.isNotBlank() }
    }

    /**
     * 解析器出不了题时的诊断上报（**不含题目文本**，可安全进日志）。
     *
     * 这是"官方又出了新题型"在端侧唯一的发现渠道：形状指纹相同 = 只是改名，
     * 出现新指纹 = 结构变了、需要补适配。失败一律静默，绝不影响解析主流程。
     */
    fun reportUnparsed(
        content: String,
        info: String = "",
        content2: String = "",
        tag: String,
    ) {
        try {
            val report = diagnose(content, info, content2)
            if (!report.ok) {
                Log.w(TAG, "[$tag] 诊断请求失败: ${report.errorCode} ${report.errorMessage}")
                return
            }
            val detail = report.result ?: return
            if (detail.optInt("question_count", 0) > 0) return  // 有题目就不上报，避免噪音
            val shape = detail.optJSONObject("shape")?.optString("hash", "").orEmpty()
            Log.w(
                TAG,
                "[$tag] 解析为空 -> structure_type=${detail.optString("structure_type")} " +
                    "layer=${detail.optString("layer")} family=${detail.optString("family")} " +
                    "shape=$shape spec_known=${report.specKnown} " +
                    "warnings=${detail.optJSONArray("warnings")}",
            )
        } catch (_: Throwable) {
            // 诊断是尽力而为，任何异常都不允许影响解析主流程
        }
    }

    private const val TAG = "ResolverContract"
}
