package com.shuaiqiu.fuckets100.feature.read

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.shuaiqiu.fuckets100.ETS100AnswerReader
import com.shuaiqiu.fuckets100.FeAccountFeatures
import com.shuaiqiu.fuckets100.FeAccountManager

private const val FE_SHARE_SIGNATURE = "Fe守护你的答案喵~ 官网:lastudio.cc"

internal fun exportPaperAsImage(
    context: android.content.Context,
    paper: ETS100AnswerReader.Paper,
    accountManager: FeAccountManager,
) {
    val (available, reason) = accountManager.isFeatureAvailable(FeAccountFeatures.ANSWER_SHARE)
    if (!available) {
        android.widget.Toast.makeText(context, reason ?: "答案分享功能当前不可用", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val width = 1080
        val pad = 60
        val cw = width - pad * 2

        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f; typeface = Typeface.DEFAULT_BOLD; color = AndroidColor.parseColor("#333333") }
        val secP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; typeface = Typeface.DEFAULT_BOLD; color = AndroidColor.parseColor("#007AFF") }
        val qP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = AndroidColor.parseColor("#333333") }
        val footP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = AndroidColor.parseColor("#999999") }

        val lg = 16f; val sg = 32f

        fun ms(text: String, paint: TextPaint): Float {
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, cw).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(lg, 1f).build().height.toFloat()
        }

        var th = pad.toFloat()
        th += ms(paper.title, titleP) + sg
        for (sec in paper.sections) {
            if (sec.questions.isEmpty()) continue
            if (sec.category == "read_chapter") continue
            th += ms(sec.title, secP) + lg
            for (q in sec.questions) {
                val isZS = sec.category == "topic"
                val ans = q.answerList.take(if (isZS) 1 else 2)
                val cleaned = ans.map { a -> ETS100AnswerReader.cleanAnswerText(a).lines().map { it.trim() }.filter { it.isNotEmpty() } }
                val at = if (isZS) cleaned.flatten().joinToString(" ") else cleaned.joinToString("\n") { it.joinToString(" ") }.ifEmpty { "" }
                th += ms("Q" + q.order + ". " + at, qP) + lg
            }
            th += sg
        }
        th += ms(FE_SHARE_SIGNATURE, footP) + pad

        val bmp = Bitmap.createBitmap(width, th.toInt(), Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        cvs.drawColor(AndroidColor.WHITE)
        var y = pad.toFloat()

        fun drawTL(text: String, paint: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
            val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, cw).setAlignment(align).setLineSpacing(lg, 1f).build()
            cvs.save(); cvs.translate(pad.toFloat(), y); l.draw(cvs); cvs.restore()
            y += l.height; return l.height.toFloat()
        }

        drawTL(paper.title, titleP, Layout.Alignment.ALIGN_CENTER); y += sg
        for (sec in paper.sections) {
            if (sec.questions.isEmpty()) continue
            if (sec.category == "read_chapter") continue
            drawTL(sec.title, secP); y += lg
            for (q in sec.questions) {
                val isZS = sec.category == "topic"
                val ans = q.answerList.take(if (isZS) 1 else 2)
                val cleaned = ans.map { a -> ETS100AnswerReader.cleanAnswerText(a).lines().map { it.trim() }.filter { it.isNotEmpty() } }
                val at = if (isZS) cleaned.flatten().joinToString(" ") else cleaned.joinToString("\n") { it.joinToString(" ") }.ifEmpty { "" }
                drawTL("Q" + q.order + ". " + at, qP); y += lg
            }
            y += sg - lg
        }
        drawTL(FE_SHARE_SIGNATURE, footP, Layout.Alignment.ALIGN_CENTER)

        val fn = "Fe_Answer_" + System.currentTimeMillis() + ".png"
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fn)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Fe")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri).use { out ->
                if (out != null) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
            android.widget.Toast.makeText(context, "已保存到图片/Fe", android.widget.Toast.LENGTH_SHORT).show()
            val si = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                putExtra(Intent.EXTRA_TEXT, paper.title)
            }
            try {
                context.startActivity(Intent.createChooser(si, "导出图片"))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(context, "没有可用的分享应用", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            bmp.recycle()
            android.widget.Toast.makeText(context, "保存失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        android.util.Log.e("ExportImage", "Export failed", e)
        android.widget.Toast.makeText(context, "导出失败", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 将试卷格式化为纯文本，用于复制到剪贴板。
 */
internal fun formatPaperAsText(paper: ETS100AnswerReader.Paper): String {
    val sb = StringBuilder()
    sb.appendLine(paper.title)
    sb.appendLine()

    for (section in paper.sections) {
        if (section.questions.isEmpty()) continue
        if (section.category == "read_chapter") continue
        sb.appendLine(section.title)
        sb.appendLine()

        val isZhuanShu = section.typeName.contains("转述") || section.category == "topic"
        val takeCount = if (isZhuanShu) 1 else 2

        for (question in section.questions) {
            val answers = question.answerList.take(takeCount)
            val cleaned = answers.map { a ->
                ETS100AnswerReader.cleanAnswerText(a).lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            val answerText = if (isZhuanShu) {
                cleaned.flatten().joinToString(" ")
            } else {
                cleaned.joinToString("\n") { it.joinToString(" ") }
            }
            sb.append("Q").append(question.order).append(". ")
            if (answerText.isNotEmpty()) sb.appendLine(answerText) else sb.appendLine()
        }
        sb.appendLine()
    }
    sb.appendLine(FE_SHARE_SIGNATURE)
    return sb.toString().trimEnd()
}
