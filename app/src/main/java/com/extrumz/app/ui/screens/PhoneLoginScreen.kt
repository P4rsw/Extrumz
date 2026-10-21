package com.extrumz.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.extrumz.app.R
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthController
import com.extrumz.app.data.telegram.TelegramAuthState
import com.extrumz.app.ui.components.TelegramCustomCredentialsCard
import com.extrumz.app.ui.util.ProvideLocaleDirection
import kotlinx.coroutines.launch

/**
 * Dedicated Telegram Phone Login Screen styled to match Extrumz design language.
 * Handles phone entry, verification code, and 2FA password in a seamless full-page flow.
 */
@Composable
fun PhoneLoginScreen(
    contentPadding: PaddingValues,
    onSwitchToQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideLocaleDirection {
        val authState by Telegram.client.authState.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current

        var phoneInput by remember { mutableStateOf("") }
        var codeInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        var isSubmitting by remember { mutableStateOf(false) }
        var localError by remember { mutableStateOf<String?>(null) }

        // Ensure authorization client is started
        LaunchedEffect(Unit) {
            TelegramAuthController.ensureStarted()
            if (Telegram.client.authState.value is TelegramAuthState.WaitingForQrScan) {
                TelegramAuthController.usePhoneNumberInstead()
            }
        }

        // Reset local error when state changes
        LaunchedEffect(authState) {
            localError = null
            isSubmitting = false
        }

        fun normalizePhone(raw: String): String {
            val digits = raw.trim().replace(" ", "").replace("-", "")
            return when {
                digits.startsWith("+") -> digits
                digits.startsWith("00") -> "+" + digits.substring(2)
                digits.startsWith("0") -> "+98" + digits.substring(1)
                digits.length == 10 && digits.startsWith("9") -> "+98$digits"
                digits.startsWith("98") -> "+$digits"
                digits.isNotEmpty() -> "+$digits"
                else -> ""
            }
        }

        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Spacer(Modifier.height(16.dp))
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp)
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (val state = authState) {
                        is TelegramAuthState.Uninitialized,
                        is TelegramAuthState.Initializing -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    Text(
                                        text = stringResource(R.string.tg_connecting),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        is TelegramAuthState.WaitingForPhoneNumber -> {
                            // Phone input stage
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Phone,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }

                                    Spacer(Modifier.height(16.dp))

                                    Text(
                                        text = stringResource(R.string.login_phone_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        text = stringResource(R.string.tg_phone_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )

                                    Spacer(Modifier.height(24.dp))

                                    val normalized = normalizePhone(phoneInput)

                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        OutlinedTextField(
                                            value = phoneInput,
                                            onValueChange = { phoneInput = it },
                                            placeholder = {
                                                Text(
                                                    text = stringResource(R.string.tg_phone_placeholder),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                                )
                                            },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                            ),
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Phone,
                                                imeAction = ImeAction.Done,
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    if (normalized.isNotBlank() && !isSubmitting) {
                                                        focusManager.clearFocus()
                                                        isSubmitting = true
                                                        scope.launch {
                                                            runCatching {
                                                                Telegram.client.submitPhoneNumber(normalized)
                                                            }.onFailure {
                                                                localError = it.message ?: "Failed to submit phone"
                                                                isSubmitting = false
                                                            }
                                                        }
                                                    }
                                                },
                                            ),
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.Phone,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            },
                                            trailingIcon = {
                                                if (phoneInput.isNotEmpty()) {
                                                    IconButton(onClick = { phoneInput = "" }) {
                                                        Icon(
                                                            Icons.Rounded.Close,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                                cursorColor = MaterialTheme.colorScheme.primary,
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }

                                    if (phoneInput.isNotBlank() && normalized != phoneInput.trim()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = "${stringResource(R.string.tg_phone_send_to)} $normalized",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Start,
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = localError != null,
                                        enter = fadeIn(),
                                        exit = fadeOut(),
                                    ) {
                                        localError?.let { err ->
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                text = err,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(20.dp))

                                    Button(
                                        onClick = {
                                            if (normalized.isNotBlank()) {
                                                focusManager.clearFocus()
                                                isSubmitting = true
                                                scope.launch {
                                                    runCatching {
                                                        Telegram.client.submitPhoneNumber(normalized)
                                                    }.onFailure {
                                                        localError = it.message ?: "Failed to submit phone"
                                                        isSubmitting = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = normalized.isNotBlank() && !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        if (isSubmitting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.tg_send_code),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // Switch to QR code alternative
                            OutlinedButton(
                                onClick = onSwitchToQr,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.QrCode,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.tg_switch_to_qr),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        is TelegramAuthState.WaitingForCode -> {
                            // SMS / Telegram verification code stage
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MarkEmailRead,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }

                                    Spacer(Modifier.height(16.dp))

                                    Text(
                                        text = stringResource(R.string.tg_code),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        text = stringResource(R.string.tg_code_desc, state.phoneNumber),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )

                                    Spacer(Modifier.height(24.dp))

                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        OutlinedTextField(
                                            value = codeInput,
                                            onValueChange = { codeInput = it },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                                textAlign = TextAlign.Center,
                                                letterSpacing = 6.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            ),
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Done,
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    if (codeInput.isNotBlank() && !isSubmitting) {
                                                        focusManager.clearFocus()
                                                        isSubmitting = true
                                                        scope.launch {
                                                            runCatching {
                                                                Telegram.client.submitCode(codeInput.trim())
                                                            }.onFailure {
                                                                localError = it.message ?: "Failed to verify code"
                                                                isSubmitting = false
                                                            }
                                                        }
                                                    }
                                                },
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                                cursorColor = MaterialTheme.colorScheme.primary,
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = localError != null,
                                        enter = fadeIn(),
                                        exit = fadeOut(),
                                    ) {
                                        localError?.let { err ->
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                text = err,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(20.dp))

                                    Button(
                                        onClick = {
                                            if (codeInput.isNotBlank()) {
                                                focusManager.clearFocus()
                                                isSubmitting = true
                                                scope.launch {
                                                    runCatching {
                                                        Telegram.client.submitCode(codeInput.trim())
                                                    }.onFailure {
                                                        localError = it.message ?: "Failed to verify code"
                                                        isSubmitting = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = codeInput.isNotBlank() && !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        if (isSubmitting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.tg_verify),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    TextButton(
                                        onClick = { TelegramAuthController.usePhoneNumberInstead() },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.tg_code_edit_phone))
                                    }
                                }
                            }
                        }

                        is TelegramAuthState.WaitingForPassword -> {
                            // 2-Step Verification password stage
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Lock,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }

                                    Spacer(Modifier.height(16.dp))

                                    Text(
                                        text = stringResource(R.string.tg_password_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        text = state.passwordHint?.let {
                                            stringResource(R.string.tg_password_hint, it)
                                        } ?: stringResource(R.string.tg_password_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )

                                    Spacer(Modifier.height(24.dp))

                                    OutlinedTextField(
                                        value = passwordInput,
                                        onValueChange = { passwordInput = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                if (passwordInput.isNotBlank() && !isSubmitting) {
                                                    focusManager.clearFocus()
                                                    isSubmitting = true
                                                    scope.launch {
                                                        runCatching {
                                                            Telegram.client.submitPassword(passwordInput)
                                                        }.onFailure {
                                                            localError = it.message ?: "Failed to unlock password"
                                                            isSubmitting = false
                                                        }
                                                    }
                                                }
                                            },
                                        ),
                                        trailingIcon = {
                                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                                Icon(
                                                    imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                            cursorColor = MaterialTheme.colorScheme.primary,
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    AnimatedVisibility(
                                        visible = localError != null,
                                        enter = fadeIn(),
                                        exit = fadeOut(),
                                    ) {
                                        localError?.let { err ->
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                text = err,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(20.dp))

                                    Button(
                                        onClick = {
                                            if (passwordInput.isNotBlank()) {
                                                focusManager.clearFocus()
                                                isSubmitting = true
                                                scope.launch {
                                                    runCatching {
                                                        Telegram.client.submitPassword(passwordInput)
                                                    }.onFailure {
                                                        localError = it.message ?: "Failed to unlock password"
                                                        isSubmitting = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = passwordInput.isNotBlank() && !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        if (isSubmitting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.tg_unlock),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is TelegramAuthState.Ready -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, Color(0x444CAF50)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(56.dp),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(R.string.tg_login_success),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50),
                                    )
                                }
                            }
                        }

                        is TelegramAuthState.Failed,
                        is TelegramAuthState.Closed -> {
                            val msg = (state as? TelegramAuthState.Failed)?.message
                                ?: stringResource(R.string.tg_connection_reset)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(20.dp))
                                    Button(
                                        onClick = { TelegramAuthController.restartAuthorization() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.tg_retry))
                                    }
                                }
                            }
                        }

                        is TelegramAuthState.WaitingForQrScan -> {
                            // If user lands here while in phone screen, prompt to switch
                            LaunchedEffect(Unit) {
                                TelegramAuthController.usePhoneNumberInstead()
                            }
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                TelegramCustomCredentialsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp)
                        .padding(horizontal = 20.dp),
                )
            }

            item {
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
