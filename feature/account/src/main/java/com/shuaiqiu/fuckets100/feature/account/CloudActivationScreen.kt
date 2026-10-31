package com.shuaiqiu.fuckets100.feature.account

import android.content.Context
import android.content.Intent
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100AuthManager

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.shuaiqiu.fuckets100.core.designsystem.ExpressiveScaffold
import com.shuaiqiu.fuckets100.core.designsystem.TonalCard
import com.shuaiqiu.fuckets100.core.designsystem.TopBarBackButton
import com.shuaiqiu.fuckets100.core.designsystem.expressiveTopAppBarColors

private data class PendingDeviceBinding(
    val phone: String,
    val password: String,
    val deviceCode: String
)

/**
 * 云端激活/登录页面
 * 喵~ 用于用户登录 ETS100 账号喵！
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CloudActivationScreen(
    onBack: () -> Unit,
    createChangyanLoginIntent: (Context) -> Intent,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changyanLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onLoginSuccess()
        }
    }
    
    // 表单状态
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var pendingBinding by remember { mutableStateOf<PendingDeviceBinding?>(null) }
    var verificationCode by remember { mutableStateOf("") }
    var verificationError by remember { mutableStateOf<String?>(null) }
    var isSendingCode by remember { mutableStateOf(false) }
    var isVerifyingCode by remember { mutableStateOf(false) }
    var verificationCodeSent by remember { mutableStateOf(false) }
    var resendSeconds by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    
    // 登录状态检查
    LaunchedEffect(Unit) {
        if (ETS100AuthManager.isLoggedIn(context)) {
            // 已经登录，直接跳转到云端首页
            onLoginSuccess()
        }
    }
    
    LaunchedEffect(pendingBinding, resendSeconds > 0) {
        if (pendingBinding != null && resendSeconds > 0) {
            while (resendSeconds > 0) {
                delay(1_000)
                resendSeconds--
            }
        }
    }

    suspend fun completeLogin(
        loginResponse: ETS100ApiClient.LoginResponse,
        loginPhone: String,
        loginPassword: String,
        successMessage: String
    ) {
        try {
            val parentAccountId = ETS100ApiClient.getEcardList(loginResponse.token).getOrThrow()
            ETS100AuthManager.saveLoginInfo(context, loginPhone, loginResponse.token, parentAccountId)
            ETS100AuthManager.saveLoginMethod(context, ETS100AuthManager.LOGIN_METHOD_PASSWORD)
            ETS100AuthManager.savePassword(context, loginPassword)
            Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
            onLoginSuccess()
        } catch (e: Exception) {
            Log.e("CloudActivationScreen", "获取账户信息失败", e)
            Toast.makeText(context, "获取账户信息失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun handleLogin() {
        if (phone.isBlank() || password.isBlank()) {
            Toast.makeText(context, "请输入手机号和密码", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            isLoading = true
            val loginPhone = phone.trim()
            val loginPassword = password

            try {
                val deviceCode = ETS100AuthManager.getDeviceCode(context)
                val loginResponse = ETS100ApiClient.login(loginPhone, loginPassword, deviceCode).getOrThrow()
                completeLogin(loginResponse, loginPhone, loginPassword, "登录成功！")
            } catch (e: ETS100ApiClient.SmsVerificationRequiredException) {
                pendingBinding = PendingDeviceBinding(e.phone, e.password, e.deviceCode)
                verificationCode = ""
                verificationError = null
                verificationCodeSent = false
                isSendingCode = true
                try {
                    ETS100ApiClient.sendDeviceBindingCode(e.phone).getOrThrow()
                    verificationCodeSent = true
                    resendSeconds = 60
                    Toast.makeText(context, "验证码已发送", Toast.LENGTH_SHORT).show()
                } catch (sendError: Exception) {
                    Log.e("CloudActivationScreen", "发送验证码失败", sendError)
                    verificationError = "发送验证码失败: ${sendError.message}"
                } finally {
                    isSendingCode = false
                }
            } catch (e: ETS100ApiClient.DeviceBindRequiredException) {
                try {
                    Toast.makeText(context, "设备需要绑定，正在自动绑定...", Toast.LENGTH_SHORT).show()
                    val bindResponse = ETS100ApiClient.bindDevice(e.phone, e.password, e.deviceCode).getOrThrow()
                    completeLogin(bindResponse, e.phone, e.password, "设备绑定并登录成功！")
                } catch (bindError: Exception) {
                    Log.e("CloudActivationScreen", "设备绑定失败", bindError)
                    Toast.makeText(context, "设备绑定失败: ${bindError.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("CloudActivationScreen", "登录失败", e)
                Toast.makeText(context, "登录失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }

    fun resendVerificationCode() {
        val binding = pendingBinding ?: return
        scope.launch {
            isSendingCode = true
            verificationError = null
            try {
                ETS100ApiClient.sendDeviceBindingCode(binding.phone).getOrThrow()
                verificationCodeSent = true
                resendSeconds = 60
                Toast.makeText(context, "验证码已重新发送", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                verificationError = "发送验证码失败: ${e.message}"
            } finally {
                isSendingCode = false
            }
        }
    }

    fun verifyAndBindDevice() {
        val binding = pendingBinding ?: return
        val normalizedCode = verificationCode.trim()
        if (normalizedCode.isEmpty()) {
            verificationError = "请输入短信验证码"
            return
        }

        scope.launch {
            isVerifyingCode = true
            verificationError = null
            try {
                val bindResponse = ETS100ApiClient.bindDevice(
                    binding.phone,
                    binding.password,
                    binding.deviceCode,
                    normalizedCode
                ).getOrThrow()
                pendingBinding = null
                completeLogin(bindResponse, binding.phone, binding.password, "设备绑定并登录成功！")
            } catch (e: ETS100ApiClient.InvalidVerificationCodeException) {
                verificationError = e.message
            } catch (e: Exception) {
                Log.e("CloudActivationScreen", "验证码绑定失败", e)
                verificationError = "设备绑定失败: ${e.message}"
            } finally {
                isVerifyingCode = false
            }
        }
    }
    
    ExpressiveScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("云端模式") },
                subtitle = { Text("登录后在线获取作业和答案") },
                navigationIcon = { TopBarBackButton(onClick = onBack) },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // 云端图标
            TonalCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceBright,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "登录 ETS100",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "使用 ETS100 账号继续",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("手机号") },
                        placeholder = { Text("请输入手机号") },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Next,
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        placeholder = { Text("请输入密码") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { handleLogin() }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { handleLogin() },
                        enabled = !isLoading && phone.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("登 录", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    TextButton(
                        onClick = {
                            Log.d("CloudActivationScreen", "点击讯飞登录，准备启动讯飞网页登录")
                            changyanLoginLauncher.launch(createChangyanLoginIntent(context))
                        },
                        enabled = !isLoading,
                    ) {
                        Text(text = "讯飞登录", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            // 提示信息
            TonalCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceBright,
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "登录风险提示",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "登录云端模式可能会导致 E听说账号在官方客户端退出登录。请确认当前没有正在使用 E听说，再继续登录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = true
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    pendingBinding?.let { binding ->
        DeviceVerificationDialog(
            phone = binding.phone,
            verificationCode = verificationCode,
            errorText = verificationError,
            isSendingCode = isSendingCode,
            isVerifyingCode = isVerifyingCode,
            verificationCodeSent = verificationCodeSent,
            resendSeconds = resendSeconds,
            onCodeChange = {
                verificationCode = it
                verificationError = null
            },
            onResend = ::resendVerificationCode,
            onConfirm = ::verifyAndBindDevice,
            onDismiss = {
                pendingBinding = null
                verificationCode = ""
                verificationError = null
                verificationCodeSent = false
                resendSeconds = 0
            }
        )
    }
}
