package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.configureAppCheck
import com.example.ui.ArcViewModel
import com.example.ui.screens.GalleryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NameOnboardingScreen
import com.example.ui.screens.PermissionsOnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ArcTheme
import com.example.ui.theme.ArcVoid
import kotlinx.serialization.Serializable

@Serializable object OnboardingNameRoute
@Serializable object OnboardingPermissionsRoute
@Serializable object HomeRoute
@Serializable object SettingsRoute
@Serializable data class GalleryRoute(val dateIso: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent before any UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            ArcTheme {
                ArcAppNavigation()
            }
        }
    }
}

@Composable
fun ArcAppNavigation(viewModel: ArcViewModel = viewModel()) {
    val loadedSettings by viewModel.loadedSettings.collectAsStateWithLifecycle()
    val settings = loadedSettings
    if (settings == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = ArcCyan)
        }
        return
    }

    val navController = rememberNavController()

    val startDest: Any = when {
        !settings.onboardingCompleted -> OnboardingNameRoute
        !settings.permissionsReviewed -> OnboardingPermissionsRoute
        else -> HomeRoute
    }

    NavHost(
        navController = navController,
        startDestination = startDest
    ) {
        composable<OnboardingNameRoute> {
            NameOnboardingScreen(
                onContinue = { name ->
                    viewModel.completeNameOnboarding(name)
                    navController.navigate(OnboardingPermissionsRoute) {
                        popUpTo(OnboardingNameRoute) { inclusive = true }
                    }
                }
            )
        }

        composable<OnboardingPermissionsRoute> {
            PermissionsOnboardingScreen(
                onComplete = {
                    viewModel.completePermissionsReview()
                    navController.navigate(HomeRoute) {
                        popUpTo(OnboardingPermissionsRoute) { inclusive = true }
                    }
                }
            )
        }

        composable<HomeRoute> {
            HomeScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenGallery = { dateIso -> navController.navigate(GalleryRoute(dateIso)) }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenPermissionsReview = { navController.navigate(OnboardingPermissionsRoute) }
            )
        }

        composable<GalleryRoute> { entry ->
            val route = entry.toRoute<GalleryRoute>()
            GalleryScreen(
                viewModel = viewModel,
                initialDateIso = route.dateIso,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
