package com.shuaiqiu.fuckets100

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class ThemeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)

        setContent {
            var currentTheme by remember { mutableStateOf(ThemeManager.getSavedTheme()) }
            var isDarkMode by remember { mutableStateOf(ThemeManager.getSavedDarkMode()) }
            var isAutoDarkMode by remember { mutableStateOf(ThemeManager.getSavedAutoDarkMode()) }
            var useDynamicColor by remember { mutableStateOf(ThemeManager.getSavedDynamicColor()) }
            val systemDarkMode = isSystemInDarkTheme()
            val effectiveDarkMode = if (isAutoDarkMode) systemDarkMode else isDarkMode

            FeThemeWrapper(
                theme = currentTheme,
                isDarkMode = effectiveDarkMode,
                useDynamicColor = useDynamicColor
            ) {
                AospPredictiveBackContent(onBack = { finish() }) {
                    ThemeSettingsScreen(
                        onBack = { finish() },
                        onThemeChanged = { currentTheme = it },
                        onDarkModeChanged = { isDarkMode = it },
                        onAutoDarkModeChanged = { isAutoDarkMode = it },
                        onDynamicColorChanged = { useDynamicColor = it }
                    )
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, ThemeSettingsActivity::class.java)
        }
    }
}
