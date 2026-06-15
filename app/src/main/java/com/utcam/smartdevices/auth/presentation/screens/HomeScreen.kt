package com.utcam.smartdevices.auth.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.utcam.smartdevices.R
import com.utcam.smartdevices.auth.presentation.AuthViewModel

/**
 * Home screen composable — shown after successful authentication.
 *
 * Displays a signed-in confirmation and surfaces the JWT seam status (AUTH-7):
 * whether a Firebase ID token is present in the last [AuthUiState.Success] is shown
 * as a human-readable line. The token value itself is NOT displayed to avoid leakage —
 * only its presence is signalled.
 *
 * [lastIdToken] is passed down from [AuthNavHost] which captures it from the Success
 * state at navigation time (the token is no longer in uiState by the time Home renders
 * because consumeState() resets to Idle).
 *
 * Sign Out:
 * Calls [AuthViewModel.signOut] (synchronous repository call + state reset), then
 * invokes [onSignedOut] so AuthNavHost can navigate back to LoginScreen with a cleared
 * back-stack.
 *
 * Design ref: Design section 5 + 6 (JWT seam), spec AUTH-11.
 */
@Composable
fun HomeScreen(
    viewModel: AuthViewModel,
    lastIdToken: String?,
    onSignedOut: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_title_home),
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.msg_signed_in),
                style = MaterialTheme.typography.bodyLarge,
            )

            // JWT seam surface (AUTH-7): display token presence, not the raw token value
            Text(
                text = if (lastIdToken != null) {
                    stringResource(R.string.msg_session_token_acquired)
                } else {
                    stringResource(R.string.msg_no_session_token)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.signOut()
                    onSignedOut()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_sign_out))
            }
        }
    }
}
