package com.shuaiqiu.fuckets100

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme

open class AnswerActivity : ComponentActivity() {
    private var paperKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPredictiveBackWindowTheme()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)

        paperKey = intent.getStringExtra(EXTRA_PAPER_KEY)
        val paper = PaperStore.get(paperKey)

        if (paper == null) {
            android.widget.Toast.makeText(this, "试卷数据已失效，请重新打开", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            val effectiveDarkMode = if (ThemeManager.getSavedAutoDarkMode()) {
                isSystemInDarkTheme()
            } else {
                ThemeManager.getSavedDarkMode()
            }

            FeThemeWrapper(
                theme = ThemeManager.getSavedTheme(),
                isDarkMode = effectiveDarkMode,
                useDynamicColor = ThemeManager.getSavedDynamicColor()
            ) {
                PredictiveBackContent(onBack = { finish() }) {
                    PaperDetailScreen(
                        paper = paper,
                        onBack = { finish() },
                        categoryColors = answerCategoryColors(),
                        onCopyText = {
                            val text = formatPaperAsText(paper)
                            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(paper.title, text))
                            android.widget.Toast.makeText(this, "已复制答案到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            PaperStore.remove(paperKey)
        }
    }

    companion object {
        private const val EXTRA_PAPER_KEY = "paper_key"

        fun createIntent(context: Context, paperKey: String): Intent {
            return Intent(
                context,
                predictiveBackActivityClass(
                    AnswerActivity::class.java,
                    AnswerOpaqueActivity::class.java,
                    AnswerKernelSuClassicActivity::class.java
                )
            ).putExtra(EXTRA_PAPER_KEY, paperKey)
        }
    }
}
