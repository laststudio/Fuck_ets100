package com.shuaiqiu.fuckets100

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * 官方试卷集模板（`res.json`）—— 卷面题号与逐题满分的**权威来源**。
 *
 * 链路（与后端 `homework.fetch_official_res_json` 完全一致）：
 * ```
 * POST /m/homework/struct {id, resource_id, parent_account_id}
 *   -> body.audio[0].set_name                试卷集名
 *   -> body.audio[0].template.url            模板包名（如 834c62a1….zip，**未加密**）
 *   -> body.audio[0].contents[].code_id      "st1"… 与模板 exam_list[].exam_id "{{st1}}" 一一对应
 * 下载 https://cdn.subject.ets100.com/made/<template.url> -> 解压 -> 读 res.json
 *   -> 交给解析器 op: parse_homework_set（官方题号 + 逐题满分）
 * ```
 * 模板包未加密，因此这里只用 zip4j 解压、不需要密码；资源包（作业 ZIP）才需要 64 位密码。
 */
object ETS100CloudTemplate {

    private const val TAG = "ETS100CloudTemplate"
    private const val CDN_PREFIX = "https://cdn.subject.ets100.com/made/"

    /** `/m/homework/struct` 的结果：试卷集名 + 模板包 + 各大题资源包（含官方挂载键 code_id）。 */
    data class Struct(
        val setName: String,
        val templateUrl: String,
        val contents: List<Content>,
    ) {
        data class Content(
            val codeId: String,
            val groupName: String,
            val realId: String,
            val url: String,
        )

        /** 按 real_id 找官方挂载键（作业列表的 contents 只有 real_id/url） */
        fun codeIdForRealId(realId: String): String =
            contents.firstOrNull { it.realId.isNotBlank() && it.realId == realId }?.codeId.orEmpty()

        /** 按 group_name 找官方挂载键（听选信息1/2/3 这类同名多包时按顺序兜底） */
        fun codeIdsForGroup(groupName: String): List<String> =
            contents.filter { it.groupName == groupName }.map { it.codeId }
    }

    /** 解析 `/m/homework/struct` 响应（纯函数，便于单测）。 */
    fun parseStruct(body: JSONObject): Struct? {
        val audio = body.optJSONArray("audio") ?: return null
        val first = audio.optJSONObject(0) ?: return null
        val template = first.optJSONObject("template")
        val contents = mutableListOf<Struct.Content>()
        first.optJSONArray("contents")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                contents.add(
                    Struct.Content(
                        codeId = c.optString("code_id", ""),
                        groupName = c.optString("group_name", ""),
                        realId = c.optString("real_id", c.optString("entity_id", "")),
                        url = c.optString("url", ""),
                    )
                )
            }
        }
        return Struct(
            setName = first.optString("set_name", ""),
            templateUrl = template?.optString("url", "").orEmpty(),
            contents = contents,
        )
    }

    /** 模板包的完整下载地址（struct 给的 url 通常已带 `.zip`，这里做幂等处理）。 */
    fun templateDownloadUrl(templateUrl: String): String {
        if (templateUrl.isBlank()) return ""
        if (templateUrl.startsWith("http://") || templateUrl.startsWith("https://")) {
            return templateUrl.replaceFirst("http://", "https://")
        }
        val name = if (templateUrl.endsWith(".zip")) templateUrl else "$templateUrl.zip"
        return CDN_PREFIX + name.trimStart('/')
    }

    /**
     * 下载模板包并读出 `res.json`。任何一步失败都返回 null（调用方回退到无模板合并）。
     * 结果会尽量缓存到 [cacheDir]，避免整卷重复下载 1~2MB 的模板包。
     */
    suspend fun fetchResJson(
        templateUrl: String,
        cacheDir: File? = null,
        /** 解压模板包（未加密）。由调用方注入（如 zip4j），data 层不引 zip4j 依赖。 */
        unzip: (zipFile: File, destDir: File) -> Unit,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val url = templateDownloadUrl(templateUrl)
        if (url.isBlank()) return@withContext null
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "template.zip" }
        val zipFile = File(cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp"), fileName)

        try {
            if (!zipFile.isFile || zipFile.length() <= 0L) {
                zipFile.parentFile?.mkdirs()
                val tmp = File(zipFile.parentFile, "${zipFile.name}.part").also { it.delete() }
                // 复用作业包同一套下载实现：ETS100 的 CDN 证书本身不可信，
                // 客户端统一按宽松校验处理（与 ETS100ApiDownload 保持一致）。
                val downloaded = ETS100ApiClient.downloadFile(url, tmp)
                downloaded.getOrElse { throw IllegalStateException("模板包下载失败: ${it.message}") }
                if (!tmp.renameTo(zipFile)) {
                    tmp.copyTo(zipFile, overwrite = true)
                    tmp.delete()
                }
            }
            val resFile = File(zipFile.parentFile, fileName.removeSuffix(".zip") + ".res.json")
            if (!resFile.isFile) {
                val dir = File(zipFile.parentFile, fileName.removeSuffix(".zip")).apply { mkdirs() }
                unzip(zipFile, dir)   // 模板包未加密，无需密码
                val extracted = File(dir, "res.json")
                if (!extracted.isFile) {
                    Log.w(TAG, "模板包内没有 res.json: ${zipFile.name}")
                    return@withContext null
                }
                extracted.copyTo(resFile, overwrite = true)
            }
            JSONObject(resFile.readText())
        } catch (e: Exception) {
            Log.w(TAG, "获取官方 res.json 失败: ${e.message}")
            null
        }
    }

    /**
     * 把已解析好的各资源包组装成 `parse_homework_set` 的 `packages` 入参（纯函数，便于单测）。
     *
     * @param packages 每项: structure_type / group_name / code_id / order / questions(解析器原始题目数组)
     */
    fun buildPackages(packages: List<PackageQuestions>): JSONArray {
        val arr = JSONArray()
        packages.forEachIndexed { index, p ->
            arr.put(
                JSONObject()
                    .put("structure_type", p.structureType)
                    .put("group_name", p.groupName)
                    .put("code_id", p.codeId)
                    .put("real_id", p.realId)
                    .put("order", if (p.order >= 0) p.order else index)
                    .put("questions", p.questions),
            )
        }
        return arr
    }

    data class PackageQuestions(
        val structureType: String,
        val groupName: String,
        val questions: JSONArray,
        val codeId: String = "",
        val realId: String = "",
        val order: Int = -1,
        /** content.json 的 info.stid：官方逐题同步的 entity_id（缺失时回退 realId） */
        val stid: String = "",
    )
}
