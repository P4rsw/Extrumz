package com.extrumz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.extrumz.app.R
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthController
import com.extrumz.app.data.telegram.TelegramAuthState
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box

/**
 * Persian phone/code/password/QR login flow, ported from the old project's
 * LoginScreen. api_id/api_hash are baked in (BuildConfig), so the credentials
 * step of the original screen is gone — authorization starts with a single
 * button and the states below take it from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramAuthDialog(onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val authState by Telegram.client.authState.collectAsState()
    val scope = rememberCoroutineScope()

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.tg_login))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.tg_login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val state = authState) {
                    is TelegramAuthState.Uninitialized,
                    is TelegramAuthState.Initializing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.tg_login))
                        }
                        // The controller starts the client before showing this
                        // dialog; give TDLib a moment to reach a waiting state.
                        remember {
                            TelegramAuthController.ensureStarted()
                            true
                        }
                    }

                    is TelegramAuthState.Failed -> {
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { TelegramAuthController.restartAuthorization() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.tg_retry)) }
                    }

                    is TelegramAuthState.WaitingForPhoneNumber -> {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(stringResource(R.string.tg_phone)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        Telegram.client.submitPhoneNumber(phone.trim())
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = phone.isNotBlank(),
                        ) { Text(stringResource(R.string.tg_send_code)) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        Telegram.client.requestQrCodeAuthentication()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.tg_qr)) }
                    }

                    is TelegramAuthState.WaitingForCode -> {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text(stringResource(R.string.tg_code)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching { Telegram.client.submitCode(code.trim()) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = code.isNotBlank(),
                        ) { Text(stringResource(R.string.tg_verify)) }
                    }

                    is TelegramAuthState.WaitingForPassword -> {
                        Text(
                            state.passwordHint?.let {
                                stringResource(R.string.tg_password_hint, it)
                            } ?: stringResource(R.string.tg_password),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.tg_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        Telegram.client.submitPassword(password)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = password.isNotBlank(),
                        ) { Text(stringResource(R.string.tg_unlock)) }
                    }

                    is TelegramAuthState.WaitingForQrScan -> {
                        Text(
                            stringResource(R.string.tg_qr_link),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // The screen is called "sign in with a QR code" and
                        // offered a URL. QrCode has existed beside this dialog
                        // the whole time and nothing called it, so the one
                        // thing the flow needs — something the other device's
                        // camera can read — was the one thing missing.
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            QrCode(
                                content = state.link,
                                size = 180.dp,
                                foreground = MaterialTheme.colorScheme.onSurface,
                                background = MaterialTheme.colorScheme.surface,
                            )
                        }
                        // Kept below the code, and selectable with a copy
                        // button: a camera is not always the way, and a link
                        // that cannot be copied or highlighted is not an
                        // alternative, it is decoration.
                        SelectionContainer {
                            Text(
                                state.link,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(state.link)) }) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.tg_qr_copy))
                            }
                        }
                        Text(
                            stringResource(R.string.tg_qr_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The way out. Reaching this state is one-way otherwise:
                        // dismissing the dialog hides it, but the authorization
                        // state is unchanged, so opening it again shows the same
                        // QR. There was no way to say "not this, the phone".
                        TextButton(onClick = { TelegramAuthController.usePhoneNumberInstead() }) {
                            Text(stringResource(R.string.tg_qr_switch_to_phone))
                        }
                    }

                    is TelegramAuthState.Ready -> {
                        Text(
                            stringResource(R.string.tg_connected),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    is TelegramAuthState.Closed -> {
                        Text(stringResource(R.string.tg_login))
                        Button(
                            onClick = { TelegramAuthController.restartAuthorization() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.tg_retry)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tg_close))
            }
        },
    )
}
