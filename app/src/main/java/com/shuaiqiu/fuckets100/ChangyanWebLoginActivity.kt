package com.shuaiqiu.fuckets100

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

@AndroidEntryPoint
open class ChangyanWebLoginActivity : ComponentActivity() {
    @Inject lateinit var accountManager: FeAccountManager

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    @Volatile
    private var isHandlingLogin = false
    private var lastConsumedAssertionHash: String? = null
    private val loginAttemptNonce = ByteArray(24).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPredictiveBackWindowTheme()
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        Log.i(TAG, "ChangyanWebLoginActivity onCreate")

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            settings.textZoom = 100
            setInitialScale(INITIAL_PAGE_SCALE)
            settings.userAgentString = MOBILE_USER_AGENT
            Log.i(TAG, "WebView 初始化完成")
            addJavascriptInterface(ChangyanBridge(), "etsBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) ProgressBar.GONE else ProgressBar.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "WebView 页面开始加载: ${safePageLabel(url)}")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView 页面加载完成: ${safePageLabel(url)}")
                    if (isTrustedCapturePage(url)) injectAjaxCapture()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request?.isForMainFrame != true) return false
                    return !isAllowedNavigation(request.url)
                }
            }
        }

        setContentView(
            FrameLayout(this).apply {
                addView(webView)
                addView(progressBar)
            }
        )

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }

        Log.i(TAG, "加载畅言登录页: $LOGIN_URL")
        webView.loadUrl(LOGIN_URL)
    }

    override fun onDestroy() {
        Log.i(TAG, "ChangyanWebLoginActivity onDestroy")
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeJavascriptInterface("etsBridge")
            webView.clearHistory()
            webView.clearCache(true)
            webView.destroy()
        }
        if (isFinishing || !isChangingConfigurations) {
            clearCookiesFor("https://pass.changyan.com", ".changyan.com")
            clearCookiesFor("https://www.ets100.com", ".ets100.com")
        }
        super.onDestroy()
    }

    private fun injectAjaxCapture() {
        Log.d(TAG, "开始注入 checkLogin Ajax 拦截脚本")
        webView.evaluateJavascript(
            """
            (function() {
                if (window.__feEtsAjaxCaptureInstalled) return;
                window.__feEtsAjaxCaptureInstalled = true;
                if (!window.${'$'}) return;
                ${'$'}(document).ajaxComplete(function(event, xhr, settings) {
                    if (!settings || !settings.url || settings.url.indexOf('/login/checkLogin') === -1) return;
                    var queryParams = {};
                    var requestParams = {};
                    try {
                        var urlObj = new URL(settings.url, window.location.origin);
                        queryParams = Object.fromEntries(urlObj.searchParams);
                    } catch(e) {}
                    if (settings.data) {
                        if (typeof settings.data === 'string') {
                            try {
                                requestParams = Object.fromEntries(new URLSearchParams(settings.data));
                            } catch(e) {}
                        } else {
                            requestParams = settings.data;
                        }
                    }
                    window.etsBridge.capture(JSON.stringify({
                        attemptNonce: ${JSONObject.quote(loginAttemptNonce)},
                        url: settings.url,
                        method: settings.type || 'GET',
                        queryParams: queryParams,
                        requestParams: requestParams,
                        status: xhr.status,
                        responseBody: xhr.responseText || ''
                    }));
                });
            })();
            """.trimIndent(),
            { result ->
                Log.d(TAG, "checkLogin Ajax 拦截脚本注入完成: $result")
            }
        )
    }

    private fun handleCapturedData(raw: String) {
        Log.d(TAG, "Bridge 收到 checkLogin 结果: length=${raw.length}")
        if (isHandlingLogin) return
        if (raw.length !in 1..MAX_CAPTURE_BYTES) return

        try {
            val data = JSONObject(raw)
            if (data.optString("attemptNonce") != loginAttemptNonce) return
            if (!isTrustedCheckLoginUrl(data.optString("url"))) return
            if (data.optInt("status") != 200) {
                showFailure("网页登录请求失败：HTTP ${data.optInt("status")}")
                return
            }

            val responseBody = JSONObject(data.getString("responseBody"))
            if (responseBody.optInt("Code") != 0) {
                showFailure("网页登录失败：${responseBody.optInt("Code")}")
                return
            }

            val responseData = JSONObject(responseBody.getString("Data"))
            val userId = responseData.optString("uid")
            val sucUserToken = responseData.optString("captchaResult")

            if (userId.isBlank() || userId.length > 128 || sucUserToken.isBlank() || sucUserToken.length > 4096) {
                showFailure("网页登录返回信息不完整")
                return
            }
            val assertionHash = sha256("$userId\u0000$sucUserToken")
            if (assertionHash == lastConsumedAssertionHash) return
            lastConsumedAssertionHash = assertionHash

            isHandlingLogin = true
            exchangeToken(userId, userId, sucUserToken)
        } catch (e: Exception) {
            Log.e(TAG, "解析网页登录数据失败: ${e.message}", e)
            showFailure("解析网页登录数据失败：${e.message}")
        }
    }

    private fun exchangeToken(login: String, userId: String, sucUserToken: String) {
        lifecycleScope.launch {
            try {
                val savedEcardId = ETS100AuthManager.getSelectedEcardId(this@ChangyanWebLoginActivity).orEmpty()
                if (accountManager.gateState.value is FeAccountGateState.Ready) {
                    // LastStudio 已登录：让后端完成校验与绑定，并使用后端下发的短期会话。
                    val session = withContext(Dispatchers.IO) {
                        accountManager.bindChangyanAccount(
                            login = login,
                            userId = userId,
                            sucUserToken = sucUserToken,
                            preferredAccountId = savedEcardId,
                        )
                    }
                    val selectedAccount = parseNativeAccount(session.selectedAccount)
                    val validAccounts = session.validAccounts.map(::parseNativeAccount)
                    if (validAccounts.isEmpty()) {
                        throw IllegalStateException("当前账号没有可用的班级信息")
                    }
                    val shouldAskUser = validAccounts.size > 1 &&
                        (savedEcardId.isBlank() || selectedAccount.id != savedEcardId)
                    if (shouldAskUser) {
                        showAccountSelectionDialog(login, session.token, validAccounts)
                    } else {
                        completeLogin(login, session.token, selectedAccount)
                    }
                } else {
                    // 已跳过 LastStudio 登录：直接在本地完成 ETS100 登录。
                    val loginResponse = withContext(Dispatchers.IO) {
                        ETS100ApiClient.loginZt(
                            login = login,
                            userId = userId,
                            sucUserToken = sucUserToken,
                            deviceCode = ETS100AuthManager.getDeviceCode(this@ChangyanWebLoginActivity),
                        ).getOrThrow()
                    }
                    val preferredId = savedEcardId.ifBlank { loginResponse.recentAccountId.orEmpty() }
                    val selection = withContext(Dispatchers.IO) {
                        ETS100ApiClient.getEcardSelection(
                            token = loginResponse.token,
                            preferredAccountId = preferredId,
                        ).getOrThrow()
                    }
                    if (selection.validAccounts.isEmpty()) {
                        throw IllegalStateException("当前账号没有可用的班级信息")
                    }
                    val shouldAskUser = selection.validAccounts.size > 1 &&
                        (savedEcardId.isBlank() || selection.selectedAccount.id != savedEcardId)
                    if (shouldAskUser) {
                        showAccountSelectionDialog(login, loginResponse.token, selection.validAccounts)
                    } else {
                        completeLogin(login, loginResponse.token, selection.selectedAccount)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isHandlingLogin = false
                Log.e(TAG, "讯飞登录流程失败: ${e.message}")
                showFailure(e.message ?: "讯飞登录失败")
            }
        }
    }

    private suspend fun completeLogin(
        login: String,
        token: String,
        account: ETS100ApiClient.EcardAccount
    ) {
        Log.i(TAG, "讯飞父账户选择完成")
        ETS100AuthManager.saveLoginInfo(
            this@ChangyanWebLoginActivity,
            login,
            token,
            account.parentId
        )
        ETS100AuthManager.saveLoginMethod(
            this@ChangyanWebLoginActivity,
            ETS100AuthManager.LOGIN_METHOD_CHANGYAN_WEB
        )
        ETS100AuthManager.saveSelectedEcard(
            this@ChangyanWebLoginActivity,
            account.id,
            account.name,
            account.grade,
            account.classId
        )
        Log.i(TAG, "讯飞登录信息已安全保存")
        withContext(Dispatchers.Main) {
            Toast.makeText(
                this@ChangyanWebLoginActivity,
                "讯飞登录成功：${account.name.ifBlank { account.grade }}",
                Toast.LENGTH_SHORT
            ).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun showAccountSelectionDialog(
        login: String,
        token: String,
        accounts: List<ETS100ApiClient.EcardAccount>
    ) {
        runOnUiThread {
            val labels = accounts.map { account ->
                buildString {
                    append(account.name.ifBlank { account.grade.ifBlank { "未知账号" } })
                    if (account.className.isNotBlank()) append(" · 班级 ${account.className}")
                    append(" · ${account.id}")
                }
            }
            AlertDialog.Builder(this)
                .setTitle("选择要读取的账号")
                .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)) { dialog, which ->
                    val selected = accounts[which]
                    dialog.dismiss()
                    lifecycleScope.launch {
                        completeLogin(login, token, selected)
                    }
                }
                .setOnCancelListener {
                    isHandlingLogin = false
                    showFailure("已取消账号选择")
                }
                .show()
        }
    }

    private fun showFailure(message: String) {
        Log.e(TAG, message)
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private inner class ChangyanBridge {
        @JavascriptInterface
        fun capture(data: String) {
            if (data.length !in 1..MAX_CAPTURE_BYTES) return
            webView.post {
                if (isTrustedCapturePage(webView.url)) handleCapturedData(data)
            }
        }
    }

    private fun parseNativeAccount(account: JSONObject) = ETS100ApiClient.EcardAccount(
        key = account.optString("key"),
        id = account.optString("id"),
        parentId = account.optString("parent_id"),
        userAccountId = account.optString("user_account_id"),
        name = account.optString("name"),
        grade = account.optString("grade"),
        status = account.optString("status"),
        mobileStatus = account.optString("mobile_status"),
        outOfDate = account.optString("out_of_date"),
        classId = account.optString("class_id"),
        className = account.optString("class_name"),
        machineCodeStatus = account.optString("machine_code_status"),
    )

    private fun isTrustedCapturePage(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        return uri.scheme == "https" && uri.host == "pass.changyan.com"
    }

    private fun isAllowedNavigation(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host.orEmpty()
        return host == "changyan.com" || host.endsWith(".changyan.com") ||
            host == "ets100.com" || host.endsWith(".ets100.com")
    }

    private fun isTrustedCheckLoginUrl(url: String): Boolean {
        if (url.startsWith("/")) return url.substringBefore('?') == "/login/checkLogin"
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "pass.changyan.com" && uri.path == "/login/checkLogin"
    }

    private fun safePageLabel(url: String?): String {
        val uri = url?.let(Uri::parse) ?: return "(empty)"
        return "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun clearCookiesFor(origin: String, domain: String) {
        val manager = CookieManager.getInstance()
        manager.getCookie(origin)
            ?.split(';')
            ?.map { it.substringBefore('=').trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { name ->
                manager.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax")
                manager.setCookie(origin, "$name=; Max-Age=0; Domain=$domain; Path=/; Secure; SameSite=Lax")
            }
        manager.flush()
    }

    companion object {
        private const val TAG = "ChangyanWebLogin"
        private const val INITIAL_PAGE_SCALE = 85
        private const val MAX_CAPTURE_BYTES = 64 * 1024
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; PLC110 Build/BP2A.250605.015) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/148.0.7778.120 Mobile Safari/537.36"
        private const val LOGIN_URL =
            "https://pass.changyan.com/login?nextpage=aHR0cHM6Ly93d3cuZXRzMTAwLmNvbS9sb2dpbkNoZWNrLmh0bWw=&customConfig=e2hpZGRlbl9tb2R1bGU6ICJoZWFkZXIsdGFpbCxsb2dpbkJ5VmVyaWZ5Q29kZSxyZWdpc3Rlcixsb2dpbkJ5VGhpcmRMb2dpbiIscHJvZHVjdF9hcHBrZXk6InFpbmdkYW9fZXRzIiwibmVlZFRpY2tldCI6InRydWUiLCJsb2dpbl9ub3RBdXRvIjoidHJ1ZSJ9&from=ew&appId=pass6port18"

        fun createIntent(context: Context): Intent {
            return Intent(
                context,
                predictiveBackActivityClass(
                    ChangyanWebLoginOpaqueActivity::class.java,
                )
            )
        }
    }
}
