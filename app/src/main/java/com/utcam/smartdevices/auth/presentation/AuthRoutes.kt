package com.utcam.smartdevices.auth.presentation

/**
 * Navigation route constants for the authentication graph.
 *
 * These are plain strings used by [AuthNavHost] and its [androidx.navigation.NavController].
 * Keeping them in a single object prevents typos and makes rename-refactors safe.
 */
object AuthRoutes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT = "forgot"
    const val HOME = "home"
}
