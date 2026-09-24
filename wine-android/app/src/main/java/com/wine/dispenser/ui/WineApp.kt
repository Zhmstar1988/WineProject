package com.wine.dispenser.ui

import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wine.dispenser.network.ApiClient
import com.wine.dispenser.network.LoginResponse
import kotlinx.coroutines.launch

@Composable
fun WineApp() {
    val navController = rememberNavController()
    var token by remember { mutableStateOf<String?>(null) }
    var ageVerified by remember { mutableStateOf(false) }
    var userInfo by remember { mutableStateOf<LoginResponse?>(null) }

    val startDestination = when {
        token == null -> "login"
        !ageVerified -> "age_verify"
        else -> "menu"
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            LoginScreen(
                onLoginSuccess = { resp ->
                    token = resp.token
                    ageVerified = resp.ageVerified
                    userInfo = resp
                    navController.navigate(if (resp.ageVerified) "menu" else "age_verify") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }
        composable("age_verify") {
            AgeVerifyScreen(
                token = token!!,
                onVerified = {
                    ageVerified = true
                    navController.navigate("menu") {
                        popUpTo("age_verify") { inclusive = true }
                    }
                }
            )
        }
        composable("menu") {
            MenuScreen(token = token!!)
        }
    }
}
