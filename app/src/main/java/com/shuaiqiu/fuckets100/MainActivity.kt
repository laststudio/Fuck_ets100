package com.shuaiqiu.fuckets100

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow

// ============================================================================
// 瀛椾綋瀹氫箟 - 鐢ㄤ簬搴旂敤鏍囬鐨勯啋鐩睍绀烘晥鏋?// ============================================================================

/**
 * 婵€娲绘ā寮忔灇涓? * 瀹氫箟搴旂敤鐨勪笉鍚岃繍琛屾巿鏉冩ā寮? */

sealed class Screen(val route: String, val icon: ImageVector, val label: String) {
    object Home : Screen("home", Icons.Default.Home, "首页")
    object Read : Screen("read", Icons.AutoMirrored.Filled.MenuBook, "答题")
    object Auto : Screen("auto", Icons.Default.AutoAwesome, "自动完成")
    object Settings : Screen("settings", Icons.Default.Settings, "设置")
}
// ============================================================================
// 涓昏娲诲姩绫?- 搴旂敤鍏ュ彛
// ============================================================================

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accountManager: FeAccountManager
    @Inject lateinit var updateStatusRepository: AppUpdateStatusRepository


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
        enableEdgeToEdge() // 鍚敤娌夋蹈寮忚竟缂樺埌杈圭紭甯冨眬
        intent.getStringExtra(EXTRA_TARGET_ROUTE)?.let { route ->
            pendingTargetRoute = route
        }

        // 鍒濆鍖?ThemeManager
        ThemeManager.init(this)

        setContent {
            FeAppMain(accountManager, updateStatusRepository)
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
