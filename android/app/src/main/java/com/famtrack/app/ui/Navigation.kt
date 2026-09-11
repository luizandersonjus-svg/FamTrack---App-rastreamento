package com.famtrack.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.famtrack.app.data.remote.AuthRepository
import com.famtrack.app.feature.activity.ActivityScreen
import com.famtrack.app.feature.invite.InviteScreen
import com.famtrack.app.feature.invite.JoinScreen
import com.famtrack.app.feature.places.PlaceEditScreen
import com.famtrack.app.feature.places.PlacesScreen
import com.famtrack.app.feature.routes.RouteSearchScreen
import com.famtrack.app.ui.auth.LoginScreen
import com.famtrack.app.ui.auth.RegisterScreen
import com.famtrack.app.ui.home.HomeScreen
import com.famtrack.app.ui.geofence.GeofenceScreen
import com.famtrack.app.ui.history.HistoryScreen
import com.famtrack.app.ui.notifications.NotificationsScreen
import com.famtrack.app.ui.settings.SettingsScreen
import io.github.jan.supabase.auth.status.SessionStatus

@Composable
fun FamTrackNavigation() {
    val authRepository = remember { AuthRepository() }
    val sessionStatus by authRepository.sessionStatus.collectAsState()
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionStatus) {
        if (sessionStatus !is SessionStatus.Initializing) {
            if (startDestination == null) {
                val hasStoredSession = authRepository.hasStoredSession()
                startDestination = when (sessionStatus) {
                    is SessionStatus.Authenticated -> "home"
                    is SessionStatus.NotAuthenticated -> "login"
                    is SessionStatus.RefreshFailure ->
                        if (hasStoredSession) "home" else "login"
                    else -> "login"
                }
                android.util.Log.d("FamTrackAuth", "sessionStatus: ${sessionStatus::class.simpleName}; startDestination=$startDestination")
            }
        }
    }

    val destination = startDestination ?: return Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = destination
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

        composable("joinFamily") {
            JoinScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onJoined = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
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
                onNavigateToPlaces = {
                    navController.navigate("places")
                },
                onNavigateToActivity = {
                    navController.navigate("activity")
                },
                onNavigateToInvite = {
                    navController.navigate("invite")
                },
                onNavigateToPrivacy = {
                    navController.navigate("settings")
                },
                onNavigateToRoutes = {
                    navController.navigate("routes")
                },
                onNavigateToMemberHistory = { memberId ->
                    navController.navigate("activity?memberId=$memberId")
                },
                onLogout = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("places") {
            PlacesScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToCreate = {
                    navController.navigate("placeEdit?placeId=")
                },
                onNavigateToEdit = { placeId ->
                    navController.navigate("placeEdit?placeId=$placeId")
                }
            )
        }

        composable(
            route = "placeEdit?placeId={placeId}",
            arguments = listOf(
                navArgument("placeId") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) {
            PlaceEditScreen(
                placeId = it.arguments?.getString("placeId"),
                onNavigateBack = {
                    navController.popBackStack()
                },
                onSaved = {
                    navController.popBackStack()
                }
            )
        }

        composable("invite") {
            InviteScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "activity?memberId={memberId}",
            arguments = listOf(
                navArgument("memberId") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) {
            val memberId = it.arguments?.getString("memberId")?.takeIf { arg -> arg.isNotBlank() }
            ActivityScreen(
                familyId = "",
                memberId = memberId,
                onNavigateBack = {
                    navController.popBackStack()
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
                },
                onOpenRoutes = {
                    navController.navigate("routes")
                }
            )
        }

        composable("routes") {
            RouteSearchScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}