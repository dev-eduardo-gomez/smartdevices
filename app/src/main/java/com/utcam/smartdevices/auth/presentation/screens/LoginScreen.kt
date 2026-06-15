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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.utcam.smartdevices.R
import com.utcam.smartdevices.auth.presentation.AuthUiState
import com.utcam.smartdevices.auth.presentation.AuthViewModel

/**
 * Login screen composable.
 *
 * Reads [AuthViewModel.uiState] and renders:
 * - Email + Password fields
 * - Sign In button (disabled while Loading)
 * - [CircularProgressIndicator] while Loading
 * - Inline error text when state is Error
 * - Navigates to home via [onAuthenticated] on Success (non-null or null token)
 * - Links to Register and ForgotPassword screens
 *
 * Navigation:
 * - [onAuthenticated] — called when [AuthUiState.Success] is observed (navigates to HOME,
 *   pops the auth back-stack)
 * - [onNavigateToRegister] — navigates to RegisterScreen
 * - [onNavigateToForgot] — navigates to ForgotPasswordScreen
 *
 * Design ref: Design section 5 (Navigation), spec AUTH-1, AUTH-2, AUTH-3.
 */
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onAuthenticated: (idToken: String?) -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgot: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // One-shot navigation: navigate when Success is observed, then consume the state
    // so it does not re-trigger on recomposition.
    LaunchedEffect(uiState) {
        if (uiState is AuthUiState.Success) {
            onAuthenticated((uiState as AuthUiState.Success).idToken)
            viewModel.consumeState()
        }
    }

    val isLoading = uiState is AuthUiState.Loading

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
                text = stringResource(R.string.screen_title_login),
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
                enabled = !isLoading,
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.label_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
            )

            // Inline error — displayed when state is Error
            if (uiState is AuthUiState.Error) {
                Text(
                    text = (uiState as AuthUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.signIn(email, password) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(20.dp),
                    )
                } else {
                    Text(stringResource(R.string.btn_sign_in))
                }
            }

            TextButton(onClick = onNavigateToForgot, enabled = !isLoading) {
                Text(stringResource(R.string.link_forgot_password))
            }

            TextButton(onClick = onNavigateToRegister, enabled = !isLoading) {
                Text(stringResource(R.string.link_create_account))
            }
        }
    }
}
