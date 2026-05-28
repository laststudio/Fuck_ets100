package com.shuaiqiu.fuckets100

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

open class GeneralSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyPredictiveBackWindowTheme()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)

        setContent {
            FeTheme {
                PredictiveBackContent(onBack = { finish() }) {
                    GeneralSettingsScreen(onBack = { finish() })
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(
                context,
                predictiveBackActivityClass(
                    GeneralSettingsActivity::class.java,
                    GeneralSettingsOpaqueActivity::class.java
                )
            )
        }
    }
}
