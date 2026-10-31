package com.shuaiqiu.fuckets100.feature.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pin
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/* 设备绑定短信验证弹窗（从 CloudActivationScreen 拆出，控制单文件行数）。 */

@Composable
private fun DeviceVerificationDialog(
    phone: String,
    verificationCode: String,
    errorText: String?,
    isSendingCode: Boolean,
    isVerifyingCode: Boolean,
    verificationCodeSent: Boolean,
    resendSeconds: Int,
    onCodeChange: (String) -> Unit,
    onResend: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isBusy = isSendingCode || isVerifyingCode

    AlertDialog(
        onDismissRequest = {
            if (!isBusy) onDismiss()
        },
        icon = {
            Icon(Icons.Default.Sms, contentDescription = null)
        },
        title = { Text("验证手机号") },
        text = {
            Column {
                Text(
                    if (verificationCodeSent) {
                        "验证码已发送至 ${maskPhone(phone)}，验证后将绑定当前设备。"
                    } else {
                        "需要验证 ${maskPhone(phone)}，请重新发送短信验证码。"
                    }
                )
                OutlinedTextField(
                    value = verificationCode,
                    onValueChange = onCodeChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    label = { Text("短信验证码") },
                    leadingIcon = {
                        Icon(Icons.Default.Pin, contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!isBusy && verificationCode.isNotBlank()) onConfirm()
                        }
                    ),
                    singleLine = true,
                    enabled = !isVerifyingCode,
                    isError = errorText != null,
                    supportingText = errorText?.let { message ->
                        { Text(message) }
                    }
                )
                TextButton(
                    onClick = onResend,
                    enabled = !isBusy && resendSeconds == 0,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (isSendingCode) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (resendSeconds > 0) "${resendSeconds} 秒后重新发送" else "重新发送验证码")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isBusy && verificationCode.isNotBlank()
            ) {
                if (isVerifyingCode) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("验证并绑定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("取消")
            }
        }
    )
}

private fun maskPhone(phone: String): String {
    return if (phone.length >= 7) {
        phone.replaceRange(3, phone.length - 4, "****")
    } else {
        phone
    }
}
