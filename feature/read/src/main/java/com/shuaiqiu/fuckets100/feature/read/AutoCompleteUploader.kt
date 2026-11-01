package com.shuaiqiu.fuckets100.feature.read

import android.util.Log
import com.shuaiqiu.fuckets100.ETS100ApiClient

private const val TAG = "AutoComplete"

/** 后端 `voice_mode=silent` 同款静音 mp3 占位（TTS 未接入阶段使用）。 */
private val SILENT_MP3_STUB: ByteArray =
    byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(32)

/** 逐题上传产物：sync-v2 的 `upload_file_*` 与 `detail_file_*`。 */
internal data class UploadArtifacts(
    val uploadFileId: String = "",
    val uploadFileUrl: String = "",
    val detailFileId: String = "",
    val detailFileUrl: String = "",
)

/**
 * 逐题上传作答文件：
 * * 任何题型都先 `pre-upload` 拿 `upload_file_id`；
 * * 口语题把音频（TTS 未接入时用后端同款静音 mp3 占位）传到 OSS，回填 `upload_file_url`；
 * * 所有题都把评分 XML 传到 OSS，回填 `detail_file_id` / `detail_file_url`。
 *
 * 上传失败只告警、不中断整卷（与后端 `_raise_if_session_failed + warning` 同口径）。
 */
internal suspend fun uploadAnswerArtifacts(
    token: String,
    question: AutoQuestion,
    text: String,
    ise: Double,
    isChoose: Boolean,
    index: Int,
): UploadArtifacts {
    var uploadFileId = ""
    var uploadFileUrl = ""
    var detailFileId = ""
    var detailFileUrl = ""

    // 1) 预上传：拿到官方文件 id（文件名固定为 `{id}.mp3` / `{id}.xml`）
    uploadFileId = runCatching {
        ETS100ApiClient.preUpload(token).getOrThrow().optString("id")
    }.getOrElse { error ->
        Log.w(TAG, "第 ${index + 1} 题预上传作答文件失败: ${error.message}")
        ""
    }

    // 2) 口语题：音频直传 OSS
    if (!isChoose && uploadFileId.isNotBlank()) {
        uploadFileUrl = runCatching {
            val config = ETS100ApiClient.ossConfig(token, "audio", uploadFileId).getOrThrow()
            ETS100ApiClient.uploadOssForm(
                host = config.optString("host"),
                dirName = config.optString("dir"),
                fileName = "$uploadFileId.mp3",
                accessId = config.optString("accessid"),
                policy = config.optString("policy"),
                signature = config.optString("signature"),
                callback = config.optString("callback"),
                content = SILENT_MP3_STUB,
            )
        }.getOrElse { error ->
            Log.w(TAG, "第 ${index + 1} 题上传作答音频失败: ${error.message}")
            ""
        }
    }

    // 3) 评分 XML：所有题型都上传
    val xml = generateScoreXml(
        text = text,
        profile = profileForIse(ise),
        category = question.category,
        seed = index.toLong(),
    )
    detailFileId = runCatching {
        ETS100ApiClient.preUpload(token).getOrThrow().optString("id")
    }.getOrElse { error ->
        Log.w(TAG, "第 ${index + 1} 题预上传评分 XML 失败: ${error.message}")
        ""
    }
    if (detailFileId.isNotBlank()) {
        detailFileUrl = runCatching {
            val config = ETS100ApiClient.ossConfig(token, "xml", detailFileId).getOrThrow()
            ETS100ApiClient.uploadOssForm(
                host = config.optString("host"),
                dirName = config.optString("dir"),
                fileName = "$detailFileId.xml",
                accessId = config.optString("accessid"),
                policy = config.optString("policy"),
                signature = config.optString("signature"),
                callback = config.optString("callback"),
                content = xml.toByteArray(Charsets.UTF_8),
            )
        }.getOrElse { error ->
            Log.w(TAG, "第 ${index + 1} 题上传评分 XML 失败: ${error.message}")
            ""
        }
    }

    return UploadArtifacts(
        uploadFileId = uploadFileId,
        uploadFileUrl = uploadFileUrl,
        detailFileId = detailFileId,
        detailFileUrl = detailFileUrl,
    )
}
