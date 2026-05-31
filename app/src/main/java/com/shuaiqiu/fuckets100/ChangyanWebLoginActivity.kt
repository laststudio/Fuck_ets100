package com.shuaiqiu.fuckets100

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

open class ChangyanWebLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    @Volatile
    private var isHandlingLogin = false

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
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = MOBILE_USER_AGENT
            Log.i(TAG, "WebView 初始化完成: ua=${settings.userAgentString}")
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
                    Log.d(TAG, "WebView 页面开始加载: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView 页面加载完成，准备注入 JS: $url")
                    injectAjaxCapture()
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
            webView.destroy()
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
        Log.d(TAG, "Bridge 收到 WebView 数据: length=${raw.length}, preview=${raw.take(240)}")
        if (isHandlingLogin) return

        try {
            val data = JSONObject(raw)
            if (!data.optString("url").contains("/login/checkLogin")) return
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
            Log.d(
                TAG,
                "checkLogin 成功: uid=$userId, captchaResult=${maskToken(sucUserToken)}, " +
                    "captchaResultLength=${sucUserToken.length}"
            )

            if (userId.isBlank() || sucUserToken.isBlank()) {
                showFailure("网页登录返回信息不完整")
                return
            }

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
                val deviceCode = ETS100AuthManager.getDeviceCode(this@ChangyanWebLoginActivity)
                Log.d(
                    TAG,
                    "准备换取 ETS token: login=$login, userId=$userId, " +
                        "deviceCode=${maskDeviceCode(deviceCode)}, sucUserToken=${maskToken(sucUserToken)}"
                )
                val loginResult = ETS100ApiClient.loginZt(login, userId, sucUserToken, deviceCode)
                loginResult.onSuccess { loginResponse ->
                    Log.i(
                        TAG,
                        "login-zt 换 token 成功: token=${maskToken(loginResponse.token)}, " +
                            "tokenLength=${loginResponse.token.length}"
                    )
                    Log.d(TAG, "开始使用 token 获取父账户信息")
                    val savedEcardId = ETS100AuthManager.getSelectedEcardId(this@ChangyanWebLoginActivity)
                    val preferredAccountId = savedEcardId ?: loginResponse.recentAccountId
                    val ecardResult = ETS100ApiClient.getEcardSelection(
                        loginResponse.token,
                        preferredAccountId
                    )
                    ecardResult.onSuccess { selectionResult ->
                        val validAccounts = selectionResult.validAccounts
                        val shouldAskUser = savedEcardId.isNullOrBlank() && validAccounts.size > 1

                        if (shouldAskUser) {
                            showAccountSelectionDialog(
                                login = login,
                                token = loginResponse.token,
                                accounts = validAccounts
                            )
                        } else {
                            completeLogin(
                                login = login,
                                token = loginResponse.token,
                                account = selectionResult.selectedAccount
                            )
                        }
                    }.onFailure { error ->
                        isHandlingLogin = false
                        Log.e(
                            TAG,
                            "token 已获取但父账户信息失败: token=${maskToken(loginResponse.token)}, " +
                                "error=${error.message}",
                            error
                        )
                        showFailure("获取账户信息失败：${error.message}")
                    }
                }.onFailure { error ->
                    isHandlingLogin = false
                    Log.e(
                        TAG,
                        "login-zt 换 token 失败: login=$login, userId=$userId, " +
                            "sucUserToken=${maskToken(sucUserToken)}, error=${error.message}",
                        error
                    )
                    showFailure("讯飞登录失败：${error.message}")
                }
            } catch (e: Exception) {
                isHandlingLogin = false
                Log.e(TAG, "换取或保存 token 流程异常: ${e.message}", e)
                showFailure("讯飞登录异常：${e.message}")
            }
        }
    }

    private suspend fun completeLogin(
        login: String,
        token: String,
        account: ETS100ApiClient.EcardAccount
    ) {
        Log.i(
            TAG,
            "父账户信息选择完成: ecardId=${account.id}, name=${account.name}, " +
                "grade=${account.grade}, parentAccountId=${account.parentId}"
        )
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
        Log.i(
            TAG,
            "登录信息已保存: login=$login, parentAccountId=${account.parentId}, " +
                "ecardId=${account.id}, token=${maskToken(token)}"
        )
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
            Log.d(TAG, "JavascriptInterface.capture 被调用: length=${data.length}")
            handleCapturedData(data)
        }
    }

    private fun maskToken(token: String): String {
        if (token.isBlank()) return "(empty)"
        if (token.length <= 12) return "${token.take(3)}***${token.takeLast(3)}"
        return "${token.take(6)}***${token.takeLast(6)}"
    }

    private fun maskDeviceCode(deviceCode: String): String {
        val parts = deviceCode.split("|")
        if (parts.size != 2) return maskToken(deviceCode)
        return "${maskToken(parts[0])}|${maskToken(parts[1])}"
    }

    companion object {
        private const val TAG = "ChangyanWebLogin"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; PLC110 Build/BP2A.250605.015) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/148.0.7778.120 Mobile Safari/537.36"
        private const val LOGIN_URL =
            "https://pass.changyan.com/login?nextpage=aHR0cHM6Ly93d3cuZXRzMTAwLmNvbS9sb2dpbkNoZWNrLmh0bWw=&customConfig=e3ZpZXdfdHlwZToiV0VCIixoaWRkZW5fbW9kdWxlOiAiaGVhZGVyLHRhaWwsbG9naW5CeVZlcmlmeUNvZGUscmVnaXN0ZXIsbG9naW5CeVRoaXJkTG9naW4iLHByb2R1Y3RfYXBwa2V5OiJxaW5nZGFvX2V0cyIsIm5lZWRUaWNrZXQiOiJ0cnVlIiwibG9naW5fbm90QXV0byI6InRydWUifQ&from=ew&appId=pass6port18"

        fun createIntent(context: Context): Intent {
            return Intent(
                context,
                predictiveBackActivityClass(
                    ChangyanWebLoginActivity::class.java,
                    ChangyanWebLoginOpaqueActivity::class.java,
                    ChangyanWebLoginKernelSuClassicActivity::class.java
                )
            )
        }
    }
}
