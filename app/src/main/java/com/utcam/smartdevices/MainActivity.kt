package com.utcam.smartdevices

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.utcam.smartdevices.auth.presentation.AuthNavHost
import com.utcam.smartdevices.ui.theme.SmartdevicesTheme

/**
 * Single-activity entry point.
 *
 * Hosts [AuthNavHost] inside [SmartdevicesTheme]. The nav host owns the
 * [AuthViewModel] and the entire authentication navigation graph.
 *
 * RUNTIME CAVEAT: Firebase initialization requires google-services.json placed in
 * the app/ module directory before running on a device or emulator. The unit test
 * build skips Firebase initialization via -x processDebugGoogleServices and runs
 * fine without it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartdevicesTheme {
                AuthNavHost()
            }
        }
    }
}
