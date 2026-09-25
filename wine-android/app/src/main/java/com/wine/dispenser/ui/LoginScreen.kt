package com.wine.dispenser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wine.dispenser.network.ApiClient
import com.wine.dispenser.network.LoginResponse
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: (LoginResponse) -> Unit) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var welcomeMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("新加坡智能分酒", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("手机号 (+65...)") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        if (showCode) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("验证码") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                scope.launch {
                    try {
                        val resp = ApiClient.api.login(mapOf(
                            "phone" to phone, "code" to code, "loginType" to 1
                        ))
                        if (resp.code == 200) {
                            val user = resp.data!!
                            // 新用户引导：首次登录自动注册，提示完成注册完善流程（年龄校验等）
                            if (user.isNewUser) {
                                welcomeMsg = "欢迎注册！请完成年龄校验后开始点单"
                            }
                            onLoginSuccess(user)
                        } else {
                            error = resp.message
                        }
                    } catch (e: Exception) { error = e.message ?: "登录失败" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("登录 / 注册") }
        } else {
            Button(onClick = {
                scope.launch {
                    try {
                        ApiClient.api.sendSms(mapOf("phone" to phone))
                        showCode = true
                    } catch (e: Exception) { error = e.message ?: "发送失败" }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = phone.isNotEmpty()) {
                Text("发送验证码")
            }
        }

        if (welcomeMsg.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(welcomeMsg, color = MaterialTheme.colorScheme.primary)
        }

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
