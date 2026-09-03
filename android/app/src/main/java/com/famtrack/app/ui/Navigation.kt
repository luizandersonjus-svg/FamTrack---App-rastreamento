package com.famtrack.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.famtrack.app.ui.auth.LoginScreen
import com.famtrack.app.ui.auth.RegisterScreen
import com.famtrack.app.ui.home.HomeScreen
import com.famtrack.app.ui.geofence.GeofenceScreen
import com.famtrack.app.ui.history.HistoryScreen
import com.famtrack.app.ui.notifications.NotificationsScreen
import com.famtrack.app.ui.settings.SettingsScreen

@Composable
fun FamTrackNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "login"
    ) {
        composable("login") {
            LoginScreen(
                onNavigateToRegister = {
                    navController.navigate("register")
                },
                onLoginSuccess = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("register") {
            RegisterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onRegisterSuccess = {
                    navController.navigate("login") {
                        popUpTo("register") { inclusive = true }
                    }
                }
            )
        }

        composable("home") {
            HomeScreen(
                onNavigateToGeofence = {
                    navController.navigate("geofence")
                },
                onNavigateToHistory = {
                    navController.navigate("history")
                },
                onNavigateToNotifications = {
                    navController.navigate("notifications")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
                onLogout = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("geofence") {
            GeofenceScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("history") {
            HistoryScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("notifications") {
            NotificationsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onLogout = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
