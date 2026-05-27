package com.shuaiqiu.fuckets100

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)

        val paperKey = intent.getStringExtra(EXTRA_PAPER_KEY)
        val paper = PaperStore.get(paperKey)

        if (paper == null) {
            Toast.makeText(this, "分享数据已失效，请重新打开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val theme = ThemeManager.getSavedTheme()
        setContent {
            val effectiveDarkMode = if (ThemeManager.getSavedAutoDarkMode()) {
                isSystemInDarkTheme()
            } else {
                ThemeManager.getSavedDarkMode()
            }

            FeThemeWrapper(
                theme = theme,
                isDarkMode = effectiveDarkMode,
                useDynamicColor = ThemeManager.getSavedDynamicColor()
            ) {
                AospPredictiveBackContent(onBack = { finish() }) {
                    ShareScreen(
                        paper = paper,
                        isDarkMode = effectiveDarkMode,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PAPER_KEY = "paper_key"

        fun createIntent(context: Context, paperKey: String): Intent {
            return Intent(context, ShareActivity::class.java).putExtra(EXTRA_PAPER_KEY, paperKey)
        }
    }
}
