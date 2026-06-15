package com.utcam.smartdevices.auth.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.utcam.smartdevices.auth.presentation.screens.ForgotPasswordScreen
import com.utcam.smartdevices.auth.presentation.screens.HomeScreen
import com.utcam.smartdevices.auth.presentation.screens.LoginScreen
import com.utcam.smartdevices.auth.presentation.screens.RegisterScreen

/**
 * Navigation host for the authentication flow.
 *
 * Wires the four auth routes:
 * - [AuthRoutes.LOGIN]    → [LoginScreen]
 * - [AuthRoutes.REGISTER] → [RegisterScreen]
 * - [AuthRoutes.FORGOT]   → [ForgotPasswordScreen]
 * - [AuthRoutes.HOME]     → [HomeScreen]
 *
 * A single shared [AuthViewModel] is obtained here and passed to all screens (Design D2).
 * Scoping it to this composable (or the activity scope via the default ViewModelStoreOwner)
 * guarantees all three auth screens and the home screen share the same instance and the
 * same [AuthUiState] StateFlow.
 *
 * Start destination (Design section 5):
 * - If [AuthViewModel.isLoggedIn] is true (there is an existing Firebase session), the
 *   start destination is [AuthRoutes.HOME].
 * - Otherwise it is [AuthRoutes.LOGIN].
 *
 * JWT seam:
 * [lastIdToken] is a remembered state that captures the idToken from [AuthUiState.Success]
 * at the moment of navigation. It survives the consumeState() call so [HomeScreen] can
 * display whether a session token was acquired (AUTH-7, Design section 6).
 * When the user was already logged in (startDestination == HOME), lastIdToken is null
 * (no fresh sign-in, so no fresh token in this session — acceptable per design).
 *
 * Back-stack policy:
 * - Register / Forgot navigate with a simple push (standard back returns to Login).
 * - On successful auth: navigate to HOME and popUpTo(LOGIN, inclusive = true) so no auth
 *   screen remains on the back-stack. Pressing back from Home exits the app.
 * - On sign-out: navigate to LOGIN and popUpTo(HOME, inclusive = true) so Home is removed.
 *
 * RUNTIME CAVEAT: Firebase initialization requires google-services.json in
 * app/ at build time. Without it, the app will crash at runtime with a
 * FirebaseApp initialization exception. The build (unit tests) works fine
 * without it via -x processDebugGoogleServices.
 */
@Composable
fun AuthNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory()),
) {
    // Determine start destination once, based on current Firebase session
    val startDestination = remember {
        if (viewModel.isLoggedIn) AuthRoutes.HOME else AuthRoutes.LOGIN
    }

    // Capture the last JWT token from a successful auth so HomeScreen can surface it.
    // This survives consumeState() which resets uiState to Idle.
    var lastIdToken by remember { mutableStateOf<String?>(null) }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(AuthRoutes.LOGIN) {
            LoginScreen(
                viewModel = viewModel,
                onAuthenticated = { token ->
                    // Capture the JWT token before navigating (state will reset to Idle)
                    lastIdToken = token
                    // Navigate to HOME and clear the entire auth back-stack
                    navController.navigate(AuthRoutes.HOME) {
                        popUpTo(AuthRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(AuthRoutes.REGISTER)
                },
                onNavigateToForgot = {
                    navController.navigate(AuthRoutes.FORGOT)
                },
            )
        }

        composable(AuthRoutes.REGISTER) {
            RegisterScreen(
                viewModel = viewModel,
                onAuthenticated = { token ->
                    // Capture the JWT token before navigating
                    lastIdToken = token
                    // Navigate to HOME and clear back-stack up to and including LOGIN
                    // (LOGIN was the bottom of the stack; inclusive = true removes it too)
                    navController.navigate(AuthRoutes.HOME) {
                        popUpTo(AuthRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.popBackStack()
                },
            )
        }

        composable(AuthRoutes.FORGOT) {
            ForgotPasswordScreen(
                viewModel = viewModel,
                onNavigateToLogin = {
                    navController.popBackStack()
                },
            )
        }

        composable(AuthRoutes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                lastIdToken = lastIdToken,
                onSignedOut = {
                    // Clear HOME from back-stack and go back to LOGIN
                    navController.navigate(AuthRoutes.LOGIN) {
                        popUpTo(AuthRoutes.HOME) { inclusive = true }
                    }
                },
            )
        }
    }
}
