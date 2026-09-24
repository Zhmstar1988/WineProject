package com.wine.dispenser.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wine.dispenser.network.ApiClient
import com.wine.dispenser.network.CupOption
import com.wine.dispenser.network.OrderResponse
import com.wine.dispenser.network.OrderStatus
import com.wine.dispenser.network.WineItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OrderScreen(token: String, wine: WineItem, cup: CupOption, onBack: () -> Unit) {
    var phase by remember { mutableStateOf(0) } // 0-confirm 1-paying 2-ready 3-dispensing 4-done
    var order by remember { mutableStateOf<OrderResponse?>(null) }
    var countdown by remember { mutableStateOf(3) }
    var cupPlaced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showCashier by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(wine.wineName, style = MaterialTheme.typography.titleLarge)
        Text("${cup.volumeName} - ${cup.volumeMl}ml")
        Text("SGD ${"%.2f".format(cup.price)}", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(32.dp))

        when (phase) {
            0 -> Button(onClick = {
                scope.launch {
                    error = ""
                    try {
                        // 1. 创建订单
                        val resp = ApiClient.api.createOrder(
                            "Bearer $token", mapOf(
                                "barId" to 1001L,
                                "dispenserId" to (cup.dispenserId ?: 0L),
                                "slotNo" to (cup.slotNo ?: 0),
                                "wineSkuId" to wine.wineSkuId,
                                "volumeMl" to cup.volumeMl
                            )
                        )
                        if (resp.code != 200 || resp.data == null) {
                            error = resp.message
                            return@launch
                        }
                        val created = resp.data!!
                        order = created
                        // 2. 调起支付，拿到通联收银台 URL
                        val payResp = ApiClient.api.pay("Bearer $token", created.orderNo)
                        if (payResp.code != 200 || payResp.data == null) {
                            error = payResp.message
                            return@launch
                        }
                        val paid = payResp.data!!
                        order = paid
                        phase = 1
                        // 3. 打开收银台 WebView（mock 模式下若没有 cashierUrl，直接轮询状态）
                        if (!paid.cashierUrl.isNullOrBlank()) {
                            showCashier = true
                        }
                        // 4. 后台轮询订单状态
                        launch { pollOrderStatus(token, paid.orderNo) { newStatus ->
                            when {
                                newStatus >= OrderStatus.PAID -> {
                                    showCashier = false
                                    phase = 2
                                }
                                newStatus == OrderStatus.REFUNDED -> {
                                    showCashier = false
                                    error = "支付失败或已退款"
                                    phase = 0
                                }
                            }
                        } }
                    } catch (e: Exception) {
                        error = e.message ?: "下单失败"
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("下单支付") }

            1 -> {
                CircularProgressIndicator()
                Text("支付中...请在收银台完成付款")
                if (order?.cashierUrl.isNullOrBlank()) {
                    Text("（mock 模式）正在等待通联异步回调", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showCashier = true }) { Text("重新打开收银台") }
            }

            2 -> {
                Text("请将酒杯放置于 ${cup.slotNo} 号出酒口下方")
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = cupPlaced, onCheckedChange = {
                        cupPlaced = it
                        if (it) {
                            countdown = 3
                            scope.launch {
                                while (countdown > 0) {
                                    delay(1000)
                                    countdown--
                                }
                            }
                        }
                    })
                    Text("我已放好酒杯")
                }
                if (cupPlaced) {
                    Text(if (countdown > 0) "$countdown" else "可以出酒",
                        style = MaterialTheme.typography.displayMedium,
                        color = if (countdown > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary)
                    Button(onClick = {
                        scope.launch {
                            try {
                                order?.let { doDispense(token, it, cup) { phase = it } }
                            } catch (e: Exception) {
                                error = e.message ?: "出酒失败"
                            }
                        }
                    }, enabled = countdown == 0, modifier = Modifier.fillMaxWidth()) { Text("开始出酒") }
                }
            }

            3 -> {
                CircularProgressIndicator()
                Text("出酒中...")
            }

            4 -> {
                Text("出酒完成，请慢用！", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("返回酒单") }
            }
        }

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }

    // 通联收银台 WebView：用户在 WebView 内输卡号、走 3DS，回调由通联异步通知后端
    if (showCashier && order?.cashierUrl != null) {
        CashierWebView(url = order!!.cashierUrl!!) { showCashier = false }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CashierWebView(url: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("已完成支付") }
        },
        text = {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(500.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadUrl(url)
                    }
                }
            )
        }
    )
}

/** 轮询订单状态：每 2s 拉一次，最多 5 分钟，命中终态回调 */
private suspend fun pollOrderStatus(token: String, orderNo: String, onState: (Int) -> Unit) {
    val maxAttempts = 150 // 150 * 2s = 5min
    repeat(maxAttempts) {
        delay(2000)
        try {
            val resp = ApiClient.api.getOrder("Bearer $token", orderNo)
            val status = resp.data?.status ?: return@repeat
            if (status >= OrderStatus.PAID) {
                onState(status)
                return
            }
        } catch (_: Exception) { /* 忽略瞬时网络错误 */ }
    }
}

private suspend fun doDispense(token: String, order: OrderResponse, cup: CupOption, onPhase: (Int) -> Unit) {
    onPhase(3)
    ApiClient.api.startDispense("Bearer $token", order.orderNo)
    delay(1500)
    ApiClient.api.dispenseCallback(mapOf(
        "order_id" to order.orderNo,
        "status" to "SUCCESS",
        "actual_ml" to cup.volumeMl
    ))
    onPhase(4)
}
