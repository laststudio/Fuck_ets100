package com.shuaiqiu.fuckets100

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLConnection

class RemoteContentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)

        val content = RemoteContent(
            announcementTitle = intent.getStringExtra(EXTRA_ANNOUNCEMENT_TITLE).orEmpty(),
            announcementMessage = intent.getStringExtra(EXTRA_ANNOUNCEMENT_MESSAGE).orEmpty(),
            announcementUpdatedAt = intent.getStringExtra(EXTRA_ANNOUNCEMENT_UPDATED_AT).orEmpty(),
            announcementUrl = intent.getStringExtra(EXTRA_ANNOUNCEMENT_URL).orEmpty(),
            changelogTitle = intent.getStringExtra(EXTRA_CHANGELOG_TITLE).orEmpty(),
            changelogSummary = intent.getStringExtra(EXTRA_CHANGELOG_SUMMARY).orEmpty(),
            changelogUrl = intent.getStringExtra(EXTRA_CHANGELOG_URL).orEmpty()
        )

        setContent {
            val effectiveDarkMode = if (ThemeManager.getSavedAutoDarkMode()) {
                isSystemInDarkTheme()
            } else {
                ThemeManager.getSavedDarkMode()
            }

            FeThemeWrapper(
                theme = ThemeManager.getSavedTheme(),
                isDarkMode = effectiveDarkMode
            ) {
                RemoteContentScreen(
                    content = content,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ANNOUNCEMENT_TITLE = "announcement_title"
        private const val EXTRA_ANNOUNCEMENT_MESSAGE = "announcement_message"
        private const val EXTRA_ANNOUNCEMENT_UPDATED_AT = "announcement_updated_at"
        private const val EXTRA_ANNOUNCEMENT_URL = "announcement_url"
        private const val EXTRA_CHANGELOG_TITLE = "changelog_title"
        private const val EXTRA_CHANGELOG_SUMMARY = "changelog_summary"
        private const val EXTRA_CHANGELOG_URL = "changelog_url"

        fun createIntent(
            context: Context,
            announcementTitle: String,
            announcementMessage: String,
            announcementUpdatedAt: String,
            announcementUrl: String,
            changelogTitle: String,
            changelogSummary: String,
            changelogUrl: String
        ): Intent {
            return Intent(context, RemoteContentActivity::class.java).apply {
                putExtra(EXTRA_ANNOUNCEMENT_TITLE, announcementTitle)
                putExtra(EXTRA_ANNOUNCEMENT_MESSAGE, announcementMessage)
                putExtra(EXTRA_ANNOUNCEMENT_UPDATED_AT, announcementUpdatedAt)
                putExtra(EXTRA_ANNOUNCEMENT_URL, announcementUrl)
                putExtra(EXTRA_CHANGELOG_TITLE, changelogTitle)
                putExtra(EXTRA_CHANGELOG_SUMMARY, changelogSummary)
                putExtra(EXTRA_CHANGELOG_URL, changelogUrl)
            }
        }
    }
}

private data class RemoteContent(
    val announcementTitle: String,
    val announcementMessage: String,
    val announcementUpdatedAt: String,
    val announcementUrl: String,
    val changelogTitle: String,
    val changelogSummary: String,
    val changelogUrl: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteContentScreen(
    content: RemoteContent,
    onBack: () -> Unit
) {
    var announcementBody by remember(content) {
        mutableStateOf(content.announcementMessage.ifBlank { "暂无公告" })
    }
    var changelogBody by remember(content) {
        mutableStateOf(content.changelogSummary.ifBlank { "暂无更新内容" })
    }
    var isLoading by remember(content) {
        mutableStateOf(content.announcementUrl.isNotBlank() || content.changelogUrl.isNotBlank())
    }

    LaunchedEffect(content) {
        val announcementResult = fetchRemoteTextOrNull(content.announcementUrl)
        val changelogResult = fetchRemoteTextOrNull(content.changelogUrl)
        if (!announcementResult.isNullOrBlank()) {
            announcementBody = parseRemoteContent(announcementResult)
        }
        if (!changelogResult.isNullOrBlank()) {
            changelogBody = parseRemoteContent(changelogResult)
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("公告与更新", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isLoading) {
                Text(
                    "正在同步远程内容...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RemoteContentSection(
                icon = Icons.Default.Campaign,
                title = content.announcementTitle.ifBlank { "公告" },
                body = announcementBody,
                meta = content.announcementUpdatedAt
            )

            RemoteContentSection(
                icon = Icons.Default.Update,
                title = content.changelogTitle.ifBlank { "更新日志" },
                body = changelogBody,
                meta = ""
            )
        }
    }
}

@Composable
private fun RemoteContentSection(
    icon: ImageVector,
    title: String,
    body: String,
    meta: String
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private suspend fun fetchRemoteTextOrNull(url: String): String? {
    if (url.isBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as URLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            connection.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}

private fun parseRemoteContent(raw: String): String {
    return raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .lines()
        .map { line ->
            line.trim()
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^>\\s?"), "")
                .replace(Regex("^[-*+]\\s+"), "• ")
                .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
                .replace(Regex("[*_`]+"), "")
        }
        .joinToString("\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
