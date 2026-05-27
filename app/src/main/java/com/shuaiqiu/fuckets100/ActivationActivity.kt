package com.shuaiqiu.fuckets100

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class ActivationActivity : ComponentActivity() {
    private val cloudActivationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setContent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)
        setContent()
    }

    private fun setContent() {
        setContent {
            val shizukuState = rememberShizukuState()
            var currentMode by remember {
                mutableStateOf(
                    SettingsManager.getSavedActivationMode() ?: ShizukuManager.getCurrentActivationMode()
                )
            }

            LaunchedEffect(shizukuState.isRunning, shizukuState.isSui) {
                if (!SettingsManager.hasUserSelectedMode()) {
                    currentMode = ShizukuManager.getCurrentActivationMode()
                }
            }

            FeTheme {
                AospPredictiveBackContent(onBack = { finish() }) {
                    ActivationSettingsScreen(
                        currentMode = currentMode,
                        shizukuState = shizukuState,
                        onModeSelected = { mode ->
                            currentMode = mode
                            SettingsManager.saveActivationMode(mode)
                        },
                        onBack = { finish() },
                        onNavigateToCloudActivation = {
                            cloudActivationLauncher.launch(CloudActivationActivity.createIntent(this))
                        },
                        onNavigateToRead = {
                            startActivity(MainActivity.createIntent(this, Screen.Read.route))
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, ActivationActivity::class.java)
        }
    }
}
