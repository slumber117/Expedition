package com.expedition.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expedition.app.features.auth.AuthManager
import com.expedition.app.features.auth.LoginScreen
import com.expedition.app.features.auth.RegisterScreen
import com.expedition.app.features.map.MapScreen
import com.expedition.app.features.map.SavedRoutesScreen
import com.expedition.app.features.social.FriendsScreen

@Composable
fun ExpeditionApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val authManager = remember { AuthManager(context) }
    
    val isLoggedIn by authManager.isLoggedIn.collectAsState()
    val startDestination = if (isLoggedIn) "map" else "login"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavHost(
            navController = navController, 
            startDestination = startDestination
        ) {
            composable("login") {
                LoginScreen(
                    onLoginSuccess = { user ->
                        navController.navigate("map") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onNavigateToRegister = { navController.navigate("register") }
                )
            }
            composable("register") {
                RegisterScreen(
                    onRegisterSuccess = { user ->
                        navController.navigate("map") {
                            popUpTo("register") { inclusive = true }
                        }
                    },
                    onNavigateToLogin = { navController.navigate("login") }
                )
            }
            composable("map") {
                MapScreen(
                    onNavigateToFriends = { navController.navigate("friends") },
                    onNavigateToSavedRoutes = { navController.navigate("savedRoutes") },
                    onLogout = {
                        navController.navigate("login") {
                            popUpTo("map") { inclusive = true }
                        }
                    }
                )
            }
            composable("friends") {
                FriendsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDestination = { destination ->
                        // The MapScreen will pick up the currentSession destination 
                        // from GroupSessionManager, so we just need to go back
                        navController.popBackStack()
                    }
                )
            }
            composable("savedRoutes") {
                SavedRoutesScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
