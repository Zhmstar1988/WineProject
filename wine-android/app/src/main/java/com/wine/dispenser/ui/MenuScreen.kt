package com.wine.dispenser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wine.dispenser.network.ApiClient
import com.wine.dispenser.network.CupOption
import com.wine.dispenser.network.MenuResponse
import com.wine.dispenser.network.WineItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(token: String) {
    var barCode by remember { mutableStateOf("SG_BAR_01") }
    var menu by remember { mutableStateOf<MenuResponse?>(null) }
    var selected by remember { mutableStateOf<Pair<WineItem, CupOption>?>(null) }
    val scope = rememberCoroutineScope()

    if (selected != null) {
        OrderScreen(token = token, wine = selected!!.first, cup = selected!!.second) {
            selected = null
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            OutlinedTextField(
                value = barCode,
                onValueChange = { barCode = it },
                label = { Text("酒吧编码") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    try {
                        val resp = ApiClient.api.getMenu(barCode)
                        menu = resp.data
                    } catch (e: Exception) { }
                }
            }) { Text("查询") }
        }
        Spacer(Modifier.height(16.dp))

        menu?.let { m ->
            Text(m.barName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(m.wines) { wine ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(wine.wineName, style = MaterialTheme.typography.titleSmall)
                            wine.origin?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Spacer(Modifier.height(8.dp))
                            Row {
                                wine.cupOptions.forEach { cup ->
                                    AssistChip(
                                        onClick = { selected = wine to cup },
                                        label = { Text("${cup.volumeName} $${"%.2f".format(cup.price)}") }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            wine.currentCapacity?.let {
                                Text("余量: ${it}ml", style = MaterialTheme.typography.bodySmall,
                                    color = if (it < 100) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}
