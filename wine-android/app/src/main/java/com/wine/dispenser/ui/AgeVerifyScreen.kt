package com.wine.dispenser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wine.dispenser.network.ApiClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgeVerifyScreen(token: String, onVerified: () -> Unit) {
    var birthDate by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("请确认您已年满 18 周岁", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("根据新加坡法律，购买酒精饮品需年满 18 周岁。\n我们不会收集您的身份证号码。")
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = birthDate,
            onValueChange = { birthDate = it },
            label = { Text("出生日期 (yyyy-MM-dd)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        Button(onClick = {
            scope.launch {
                try {
                    val resp = ApiClient.api.verifyAge("Bearer $token", mapOf("birthDate" to birthDate))
                    if (resp.code == 200) onVerified()
                    else error = resp.message
                } catch (e: Exception) { error = e.message ?: "提交失败" }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("确认提交") }

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
