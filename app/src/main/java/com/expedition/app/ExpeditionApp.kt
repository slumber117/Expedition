package com.expedition.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expedition.app.features.auth.AuthManager
import com.expedition.app.features.auth.LoginScreen
import com.expedition.app.features.auth.RegisterScreen
import com.expedition.app.features.map.MapScreen
import com.expedition.app.features.map.NavigationManager
import com.expedition.app.features.map.SavedRoutesScreen
import com.expedition.app.features.map.PlaceSearchManager
import com.expedition.app.features.social.FriendsScreen
import com.expedition.app.features.social.GroupSessionManager

@Composable
fun ExpeditionApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    
    // Shared Managers (Single instances for the whole app)
    val authManager = remember { AuthManager(context) }
    val navigationManager = remember { NavigationManager(context) }
    val searchManager = remember { PlaceSearchManager() }
    val sessionManager = remember { GroupSessionManager(context) }
    
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
                    navigationManager = navigationManager,
                    searchManager = searchManager,
                    sessionManager = sessionManager,
                    onNavigateToFriends = { navController.navigate("friends") },
                    onNavigateToSavedRoutes = { navController.navigate("savedRoutes") },
                    onLogout = {
                        authManager.logout()
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
                        navController.popBackStack()
                    }
                )
            }
            composable("savedRoutes") {
                SavedRoutesScreen(
                    navigationManager = navigationManager,
                    onNavigateBack = { navController.popBackStack() },
                    onLoadRoute = { route ->
                        navigationManager.loadSavedRoute(route, null)
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
