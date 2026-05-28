package com.shuaiqiu.fuckets100

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

open class LegalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyPredictiveBackWindowTheme()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)

        setContent {
            FeTheme {
                PredictiveBackContent(onBack = { finish() }) {
                    LegalScreen(onBack = { finish() })
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(
                context,
                predictiveBackActivityClass(
                    LegalActivity::class.java,
                    LegalOpaqueActivity::class.java
                )
            )
        }
    }
}
