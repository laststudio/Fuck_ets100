package com.shuaiqiu.fuckets100.feature.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shuaiqiu.fuckets100.ETS100ApiClient
import com.shuaiqiu.fuckets100.ETS100AuthManager
import com.shuaiqiu.fuckets100.core.designsystem.TonalCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * E听说（ETS100）账号直登卡片。
 *
 * 只使用 E听说手机号和密码调用官方接口，不依赖 LastStudio 后端或内嵌网页。
 * 登录成功后会把 token、班级信息保存到 [ETS100AuthManager]，供云端模式与自动完成使用。
 */
@Composable
fun EtingshuoLocalLoginCard(
    modifier: Modifier = Modifier,
    title: String = "登录 E听说账号",
    subtitle: String = "使用 E听说 App 的手机号和密码登录，与 LastStudio 账号无关。",
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var verificationCode by rememberSaveable { mutableStateOf("") }
    var needsSmsCode by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingToken by remember { mutableStateOf<String?>(null) }
    var pendingAccounts by remember { mutableStateOf<List<ETS100ApiClient.EcardAccount>?>(null) }

    fun persistLogin(token: String, account: ETS100ApiClient.EcardAccount) {
        ETS100AuthManager.saveLoginInfo(context, phone.trim(), token, account.parentId)
        ETS100AuthManager.saveLoginMethod(context, ETS100AuthManager.LOGIN_METHOD_PASSWORD)
        ETS100AuthManager.saveSelectedEcard(
            context,
            account.id,
            account.name,
            account.grade,
            account.classId,
        )
        ETS100AuthManager.clearPassword(context)
        password = ""
        verificationCode = ""
        needsSmsCode = false
        onLoggedIn()
    }

    suspend fun applyToken(token: String) {
        val preferred = ETS100AuthManager.getSelectedEcardId(context).orEmpty()
        val selection = ETS100ApiClient.getEcardSelection(token, preferred).getOrThrow()
        if (selection.validAccounts.isEmpty()) {
            throw IllegalStateException("当前账号没有可用的班级信息")
        }
        val shouldAskUser = selection.validAccounts.size > 1 &&
            (preferred.isBlank() || selection.selectedAccount.id != preferred)
        if (shouldAskUser) {
            pendingToken = token
            pendingAccounts = selection.validAccounts
        } else {
            persistLogin(token, selection.selectedAccount)
        }
    }

    fun submitLogin() {
        if (phone.isBlank() || password.isBlank()) {
            error = "请输入 E听说手机号和密码"
            return
        }
        scope.launch {
            working = true
            error = null
            try {
                val deviceCode = ETS100AuthManager.getDeviceCode(context)
                val result = ETS100ApiClient.login(phone.trim(), password, deviceCode)
                val failure = result.exceptionOrNull()
                if (failure != null) {
                    when (failure) {
                        is ETS100ApiClient.SmsVerificationRequiredException,
                        is ETS100ApiClient.DeviceBindRequiredException -> {
                            needsSmsCode = true
                            runCatching {
                                ETS100ApiClient.sendDeviceBindingCode(phone.trim()).getOrThrow()
                            }.onFailure { error = it.message ?: "发送验证码失败" }
                            return@launch
                        }
                        else -> throw failure
                    }
                }
                applyToken(result.getOrThrow().token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "登录失败，请检查手机号和密码"
            } finally {
                working = false
            }
        }
    }

    fun submitVerificationCode() {
        if (verificationCode.isBlank()) {
            error = "请输入短信验证码"
            return
        }
        scope.launch {
            working = true
            error = null
            try {
                val deviceCode = ETS100AuthManager.getDeviceCode(context)
                val token = ETS100ApiClient.bindDevice(
                    phone = phone.trim(),
                    password = password,
                    deviceCode = deviceCode,
                    verificationCode = verificationCode.trim(),
                ).getOrThrow().token
                applyToken(token)
            } catch (e: ETS100ApiClient.InvalidVerificationCodeException) {
                error = "验证码错误，请重新输入"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "验证失败，请重试"
            } finally {
                working = false
            }
        }
    }

    TonalCard(modifier = modifier, shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!needsSmsCode) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("E听说手机号") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("E听说密码") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            } else {
                Text(
                    "该账号需要短信验证，验证码已发送至 ${phone.trim()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = verificationCode,
                    onValueChange = { verificationCode = it },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("短信验证码") },
                    leadingIcon = { Icon(Icons.Default.Sms, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }

            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { if (needsSmsCode) submitVerificationCode() else submitLogin() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (needsSmsCode) "验证并登录" else "登录 E听说账号")
                }
            }

            if (needsSmsCode) {
                TextButton(
                    onClick = {
                        needsSmsCode = false
                        verificationCode = ""
                        error = null
                    },
                    enabled = !working,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("返回重新输入账号")
                }
            }
        }
    }

    val accounts = pendingAccounts
    val token = pendingToken
    if (accounts != null && token != null) {
        AlertDialog(
            onDismissRequest = {
                pendingAccounts = null
                pendingToken = null
            },
            title = { Text("选择登录班级") },
            text = {
                Column {
                    accounts.forEach { account ->
                        TextButton(
                            onClick = {
                                persistLogin(token, account)
                                pendingAccounts = null
                                pendingToken = null
                            },
                        ) {
                            Text(
                                listOf(account.name, account.grade, account.className)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAccounts = null
                        pendingToken = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }
}
