package com.shuaiqiu.fuckets100

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow

sealed class Screen(val route: String, val icon: ImageVector, val label: String) {
    object Read : Screen("read", Icons.AutoMirrored.Filled.MenuBook, "读取")
    object Auto : Screen("auto", Icons.Default.AutoAwesome, "自动完成")
    object Settings : Screen("settings", Icons.Default.Settings, "设置")
}

/**
 * 应用入口：承载“读取 + 自动完成 + 设置”。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TARGET_ROUTE)?.let { route ->
            pendingTargetRoute = route
            targetRouteEvents.tryEmit(route)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getStringExtra(EXTRA_TARGET_ROUTE)?.let { route ->
            pendingTargetRoute = route
        }

        ThemeManager.init(this)

        setContent {
            FeAppMain()
        }
    }

    companion object {
        private const val EXTRA_TARGET_ROUTE = "target_route"

        var pendingTargetRoute: String? = null
        val targetRouteEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

        fun createIntent(context: android.content.Context, targetRoute: String? = null): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (targetRoute != null) {
                    putExtra(EXTRA_TARGET_ROUTE, targetRoute)
                    pendingTargetRoute = targetRoute
                }
            }
        }
    }
}
