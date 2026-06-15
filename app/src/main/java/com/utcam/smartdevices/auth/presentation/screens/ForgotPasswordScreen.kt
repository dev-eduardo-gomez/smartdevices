package com.utcam.smartdevices.auth.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.utcam.smartdevices.R
import com.utcam.smartdevices.auth.presentation.AuthUiState
import com.utcam.smartdevices.auth.presentation.AuthViewModel

/**
 * Forgot Password screen composable.
 *
 * Sends a password-reset email when the user taps [R.string.btn_send_reset_link].
 *
 * Important contract (AUTH-6, D5 — enumeration-safe):
 * On [AuthUiState.Success] from [AuthViewModel.resetPassword], this screen shows the
 * GENERIC confirmation message [R.string.msg_reset_link_sent] inline. It does NOT
 * navigate away — the user must tap "Back to Login" explicitly. This prevents account
 * enumeration (same message whether the email is registered or not).
 *
 * State cleanup: [DisposableEffect] calls [AuthViewModel.consumeState] when the screen
 * leaves the composition so stale state does not leak to other screens.
 *
 * Navigation:
 * - [onNavigateToLogin] — pops this screen off the back-stack, returning to LoginScreen
 *
 * Design ref: Design section 5 (Navigation), spec AUTH-6.
 */
@Composable
fun ForgotPasswordScreen(
    viewModel: AuthViewModel,
    onNavigateToLogin: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    var email by rememberSaveable { mutableStateOf("") }

    // Consume any leftover state when the screen leaves composition so it does not
    // bleed into Login or Register (shared VM, Design D2).
    DisposableEffect(Unit) {
        onDispose {
            viewModel.consumeState()
        }
    }

    val isLoading = uiState is AuthUiState.Loading
    val isSuccess = uiState is AuthUiState.Success

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_title_forgot_password),
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.label_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && !isSuccess,
            )

            // Error state: show mapped error message inline
            if (uiState is AuthUiState.Error) {
                Text(
                    text = (uiState as AuthUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Success state: enumeration-safe generic confirmation (AUTH-6, D5)
            // Do NOT navigate — user must tap "Back to Login"
            if (isSuccess) {
                Text(
                    text = stringResource(R.string.msg_reset_link_sent),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.resetPassword(email) },
                enabled = !isLoading && !isSuccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(20.dp),
                    )
                } else {
                    Text(stringResource(R.string.btn_send_reset_link))
                }
            }

            TextButton(onClick = {
                viewModel.consumeState()
                onNavigateToLogin()
            }) {
                Text(stringResource(R.string.link_back_to_login))
            }
        }
    }
}
