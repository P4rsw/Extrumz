package com.extrumz.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.extrumz.app.R
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.telegram.TelegramAuthController
import com.extrumz.app.data.telegram.TelegramConfig
import com.extrumz.app.ui.util.ProvideLocaleDirection

/**
 * Collapsible advanced card for login screens allowing the user to configure
 * their own Telegram API ID and Hash or reset to build defaults.
 */
@Composable
fun TelegramCustomCredentialsCard(
    modifier: Modifier = Modifier,
    onCredentialsChanged: () -> Unit = {},
) {
    ProvideLocaleDirection {
        val context = LocalContext.current
        val customApiId by AppSettings.customApiId.collectAsStateWithLifecycle()
        val customApiHash by AppSettings.customApiHash.collectAsStateWithLifecycle()

        var isExpanded by remember { mutableStateOf(false) }
        var useCustom by remember(customApiId, customApiHash) {
            mutableStateOf(customApiId.isNotBlank() && customApiHash.isNotBlank())
        }
        var apiIdInput by remember(customApiId) { mutableStateOf(customApiId) }
        var apiHashInput by remember(customApiHash) { mutableStateOf(customApiHash) }
        var localError by remember { mutableStateOf<String?>(null) }

        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // Header toggle row (clickable)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.tg_credentials_advanced),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (TelegramConfig.isUsingCustomCredentials()) {
                                    stringResource(R.string.tg_credentials_custom_subtitle, customApiId)
                                } else {
                                    stringResource(R.string.tg_credentials_default_subtitle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        // Checkbox row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !useCustom
                                    useCustom = next
                                    localError = null
                                    if (!next) {
                                        AppSettings.clearCustomCredentials()
                                        apiIdInput = ""
                                        apiHashInput = ""
                                        TelegramAuthController.restartAuthorization()
                                        Toast.makeText(context, R.string.tg_credentials_reset_toast, Toast.LENGTH_SHORT).show()
                                        onCredentialsChanged()
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = useCustom,
                                onCheckedChange = { checked ->
                                    useCustom = checked
                                    localError = null
                                    if (!checked) {
                                        AppSettings.clearCustomCredentials()
                                        apiIdInput = ""
                                        apiHashInput = ""
                                        TelegramAuthController.restartAuthorization()
                                        Toast.makeText(context, R.string.tg_credentials_reset_toast, Toast.LENGTH_SHORT).show()
                                        onCredentialsChanged()
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.tg_credentials_use_custom),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tg_credentials_dialog_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(14.dp))

                        // API ID Field (Force LTR for numbers)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = apiIdInput,
                                onValueChange = {
                                    if (it.all { ch -> ch.isDigit() }) {
                                        apiIdInput = it.trim()
                                    }
                                },
                                label = { Text(stringResource(R.string.tg_credentials_api_id)) },
                                placeholder = { Text("1234567") },
                                enabled = useCustom,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next,
                                ),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // API Hash Field (Force LTR and password transformation)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = apiHashInput,
                                onValueChange = { apiHashInput = it.trim() },
                                label = { Text(stringResource(R.string.tg_credentials_api_hash)) },
                                placeholder = { Text("••••••••••••••••••••••••••••••••") },
                                enabled = useCustom,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (localError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = localError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Actions Row: [Save] and [Reset to default]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    val parsedId = apiIdInput.trim().toIntOrNull()
                                    if (parsedId == null || parsedId <= 0) {
                                        localError = context.getString(R.string.tg_credentials_invalid_id)
                                        return@Button
                                    }
                                    if (apiHashInput.isBlank()) {
                                        localError = context.getString(R.string.tg_credentials_empty_hash)
                                        return@Button
                                    }
                                    localError = null
                                    AppSettings.setCustomCredentials(parsedId.toString(), apiHashInput.trim())
                                    TelegramAuthController.restartAuthorization()
                                    Toast.makeText(context, R.string.tg_credentials_saved_toast, Toast.LENGTH_SHORT).show()
                                    onCredentialsChanged()
                                },
                                enabled = useCustom && apiIdInput.isNotBlank() && apiHashInput.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(stringResource(R.string.tg_credentials_save))
                            }

                            OutlinedButton(
                                onClick = {
                                    localError = null
                                    useCustom = false
                                    apiIdInput = ""
                                    apiHashInput = ""
                                    AppSettings.clearCustomCredentials()
                                    TelegramAuthController.restartAuthorization()
                                    Toast.makeText(context, R.string.tg_credentials_reset_toast, Toast.LENGTH_SHORT).show()
                                    onCredentialsChanged()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(stringResource(R.string.tg_credentials_reset))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom sheet dialog for inspecting and modifying Telegram credentials from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramCredentialsDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val customApiId by AppSettings.customApiId.collectAsStateWithLifecycle()
    val customApiHash by AppSettings.customApiHash.collectAsStateWithLifecycle()

    var useCustom by remember(customApiId, customApiHash) {
        mutableStateOf(customApiId.isNotBlank() && customApiHash.isNotBlank())
    }
    var apiIdInput by remember(customApiId) { mutableStateOf(customApiId) }
    var apiHashInput by remember(customApiHash) { mutableStateOf(customApiHash) }
    var localError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        ProvideLocaleDirection {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.tg_credentials_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Subtitle explanation
                Text(
                    text = stringResource(R.string.tg_credentials_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Checkbox row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = !useCustom
                            useCustom = next
                            localError = null
                            if (!next) {
                                AppSettings.clearCustomCredentials()
                                apiIdInput = ""
                                apiHashInput = ""
                                Toast.makeText(context, R.string.tg_credentials_reset_toast, Toast.LENGTH_SHORT).show()
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = useCustom,
                        onCheckedChange = { checked ->
                            useCustom = checked
                            localError = null
                            if (!checked) {
                                AppSettings.clearCustomCredentials()
                                apiIdInput = ""
                                apiHashInput = ""
                                Toast.makeText(context, R.string.tg_credentials_reset_toast, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.tg_credentials_use_custom),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // API ID Field (Force LTR for numbers)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = apiIdInput,
                        onValueChange = {
                            if (it.all { ch -> ch.isDigit() }) {
                                apiIdInput = it.trim()
                            }
                        },
                        label = { Text(stringResource(R.string.tg_credentials_api_id)) },
                        placeholder = { Text("1234567") },
                        enabled = useCustom,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // API Hash Field (Force LTR and password transformation)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = apiHashInput,
                        onValueChange = { apiHashInput = it.trim() },
                        label = { Text(stringResource(R.string.tg_credentials_api_hash)) },
                        placeholder = { Text("••••••••••••••••••••••••••••••••") },
                        enabled = useCustom,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (localError != null) {
                    Text(
                        text = localError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Restart notice
                Text(
                    text = "ℹ️ " + stringResource(R.string.tg_credentials_restart_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(8.dp))

                // Actions: Save & Reset
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            val parsedId = apiIdInput.trim().toIntOrNull()
                            if (parsedId == null || parsedId <= 0) {
                                localError = context.getString(R.string.tg_credentials_invalid_id)
                                return@Button
                            }
                            if (apiHashInput.isBlank()) {
                                localError = context.getString(R.string.tg_credentials_empty_hash)
                                return@Button
                            }
                            localError = null
                            AppSettings.setCustomCredentials(parsedId.toString(), apiHashInput.trim())
                            Toast.makeText(context, R.string.tg_credentials_saved_toast, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        enabled = useCustom && apiIdInput.isNotBlank() && apiHashInput.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.tg_credentials_save))
                    }

                    OutlinedButton(
                        onClick = {
                            localError = null
                            useCustom = false
                            apiIdInput = ""
                            apiHashInput = ""
                            AppSettings.clearCustomCredentials()
                            Toast.makeText(context, R.string.tg_credentials_reset_toast, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.tg_credentials_reset))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
