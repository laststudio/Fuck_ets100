package com.shuaiqiu.fuckets100

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

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
            Toast.makeText(this, "试卷数据已失效，请重新打开", Toast.LENGTH_SHORT).show()
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
                        onShare = {
                            startActivity(ShareActivity.createIntent(this, paperKey!!))
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

private fun answerCategoryColors(): Map<String, Color> {
    return mapOf(
        "read_chapter" to Color(0xFF6366F1),
        "simple_expression_ufi" to Color(0xFF22C55E),
        "simple_expression_ufk" to Color(0xFFF59E0B),
        "topic" to Color(0xFF3B82F6),
        "simple_expression_ufj" to Color(0xFFEC4899)
    )
}
