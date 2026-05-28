package com.shuaiqiu.fuckets100

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

open class CloudActivationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyPredictiveBackWindowTheme()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)

        setContent {
            FeTheme {
                PredictiveBackContent(onBack = { finish() }) {
                    CloudActivationScreen(
                        onBack = { finish() },
                        onLoginSuccess = {
                            SettingsManager.saveActivationMode(ActivationMode.CLOUD)
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(
                context,
                predictiveBackActivityClass(
                    CloudActivationActivity::class.java,
                    CloudActivationOpaqueActivity::class.java
                )
            )
        }
    }
}
