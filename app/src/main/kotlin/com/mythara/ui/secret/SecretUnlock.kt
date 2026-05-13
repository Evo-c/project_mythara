package com.mythara.ui.secret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Compose dialog that asks for the secret-mode password. On first
 * invocation it pivots to a "set a password" form (with confirm field);
 * subsequent invocations show the verify form.
 *
 * Triggers [onUnlocked] when verification succeeds, [onDismiss]
 * when the user cancels.
 */
@Composable
fun SecretUnlockDialog(
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Lambda the host Activity provides to launch BiometricPrompt with
     * Secret-mode copy. First arg fires on success, second on cancel/error
     * (with optional error message).
     */
    onBiometricRequest: (onSuccess: () -> Unit, onFailure: (String?) -> Unit) -> Unit,
    vm: SecretViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.probe() }

    LaunchedEffect(state.unlocked) {
        if (state.unlocked) {
            onUnlocked()
            vm.reset()
        }
    }

    // Auto-fire biometric the moment the dialog opens with biometric enabled
    // and the user hasn't explicitly fallen back to password. The user can
    // cancel the system prompt and tap "use password instead" to flip
    // `biometricSkipped` and render the verify form.
    LaunchedEffect(state.biometricEnabled, state.biometricSkipped, state.isSetupMode) {
        if (state.biometricEnabled && !state.biometricSkipped && !state.isSetupMode) {
            onBiometricRequest(
                { vm.onBiometricSucceeded() },
                { msg -> vm.onBiometricFailed(msg) },
            )
        }
    }

    val biometricPath = state.biometricEnabled && !state.biometricSkipped && !state.isSetupMode

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { vm.reset(); onDismiss() },
        containerColor = MytharaColors.Surface,
        title = {
            Text(
                text = when {
                    state.isSetupMode -> "set a secret password"
                    biometricPath     -> "biometric unlock"
                    else              -> "secret mode"
                },
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when {
                        state.isSetupMode -> "this is the password for Observe controls (later: continuous learning + memory tools). keep it different from your device PIN. minimum 6 characters."
                        biometricPath     -> "use face / fingerprint / device pin to continue. tap below to fall back to the secret password."
                        else              -> "enter your secret password."
                    },
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )

                if (biometricPath) {
                    TextButton(onClick = { vm.useFallbackPassword() }) {
                        Text("${Glyph.Arrow} use password instead", color = MytharaColors.Charple)
                    }
                }
                if (!biometricPath) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = { Text("password", color = MytharaColors.FgDim) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MytharaColors.Fg,
                            unfocusedTextColor = MytharaColors.Fg,
                            focusedBorderColor = MytharaColors.Charple,
                            unfocusedBorderColor = MytharaColors.SurfaceHigh,
                            cursorColor = MytharaColors.Charple,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.isSetupMode) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = { Text("confirm", color = MytharaColors.FgDim) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MytharaColors.Fg,
                            unfocusedTextColor = MytharaColors.Fg,
                            focusedBorderColor = MytharaColors.Charple,
                            unfocusedBorderColor = MytharaColors.SurfaceHigh,
                            cursorColor = MytharaColors.Charple,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${Glyph.Cross} $it",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (biometricPath) {
                // Biometric mode: the system prompt does the unlocking; we just
                // offer a retry button in case the auto-fire didn't reach the
                // system (e.g. system was busy). On most paths the dialog
                // closes before this is ever tappable.
                Button(
                    onClick = {
                        onBiometricRequest(
                            { vm.onBiometricSucceeded() },
                            { msg -> vm.onBiometricFailed(msg) },
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("${Glyph.Refresh} prompt again")
                }
            } else {
                Button(
                    onClick = {
                        if (state.isSetupMode) vm.setup(password, confirm) else vm.verify(password)
                    },
                    enabled = !state.checking && password.isNotBlank() && (!state.isSetupMode || confirm.isNotBlank()),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text(
                        text = when {
                            state.checking -> "${Glyph.Ellipsis} checking"
                            state.isSetupMode -> "${Glyph.Check} set + unlock"
                            else -> "${Glyph.Check} unlock"
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.reset(); onDismiss() }) {
                Text("cancel", color = MytharaColors.FgMute)
            }
        },
    )
}
