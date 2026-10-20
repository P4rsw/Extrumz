package com.extrumz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.extrumz.app.R
import com.extrumz.app.data.telegram.AndroidMusicTelegramClient
import com.extrumz.app.data.telegram.ProxyKind
import com.extrumz.app.data.telegram.ProxyLink
import com.extrumz.app.data.telegram.ProxySettings
import com.extrumz.app.data.telegram.ProxyStore
import com.extrumz.app.data.telegram.ProxyVerdict
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.ui.util.ProvideLocaleDirection
import kotlinx.coroutines.launch

/**
 * Modern bottom sheet for adding a new proxy or editing an existing one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyEditDialog(
    initial: ProxySettings? = null,
    onDismiss: () -> Unit,
    onSave: (ProxySettings) -> Unit,
) {
    val isEdit = initial != null
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: ProxyKind.MTProto) }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf(if ((initial?.port ?: 0) > 0) initial?.port.toString() else "") }
    var secret by remember(initial) { mutableStateOf(initial?.secret.orEmpty()) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var pasted by remember(initial) { mutableStateOf("") }
    var pasteError by remember(initial) { mutableStateOf<String?>(null) }
    var testResult by remember(initial) { mutableStateOf<ProxyVerdict?>(null) }
    var testing by remember(initial) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun applyLink(raw: String) {
        val trimmed = raw.trim()
        val parsed = ProxyLink.parse(trimmed)
        if (parsed != null) {
            enabled = true
            kind = parsed.kind
            host = parsed.host
            port = parsed.port.toString()
            secret = parsed.secret
            username = parsed.username
            password = parsed.password
            pasteError = null
        }
    }

    fun runTest(settings: ProxySettings) {
        testing = true
        testResult = null
        scope.launch {
            val verdict = (Telegram.client as? AndroidMusicTelegramClient)
                ?.pingProxyVerdict(settings) ?: ProxyVerdict(-1.0, -1.0)
            testing = false
            testResult = verdict
        }
    }

    val portNumber = port.toIntOrNull()
    val draft = ProxySettings(
        enabled = enabled,
        kind = kind,
        host = host.trim(),
        port = portNumber ?: 0,
        secret = secret.trim(),
        username = username.trim(),
        password = password,
    )
    val portBad = port.isNotBlank() && (portNumber == null || portNumber !in 1..65535)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle()
        },
    ) {
        ProvideLocaleDirection {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header with title and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(if (isEdit) R.string.proxy_edit else R.string.proxy_add),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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

                // Quick Paste Field with trailing paste button
                OutlinedTextField(
                    value = pasted,
                    onValueChange = {
                        pasted = it
                        applyLink(it)
                    },
                    label = { Text(stringResource(R.string.proxy_paste_link)) },
                    placeholder = { Text("https://t.me/proxy?server=…") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                if (clip.isNotBlank()) {
                                    pasted = clip
                                    applyLink(clip)
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = stringResource(R.string.proxy_paste_clipboard),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                pasteError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Protocol Segmented Pill Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp),
                    ) {
                        ProxyKind.entries.forEach { option ->
                            val isSelected = kind == option
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                                    .clickable { kind = option }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(option.labelRes()),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (kind == ProxyKind.MTProto) {
                        Text(
                            text = stringResource(R.string.proxy_kind_mtproto_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }

                // Server & Port side-by-side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.proxy_host)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(2.4f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.proxy_port)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (kind == ProxyKind.MTProto) ImeAction.Done else ImeAction.Next),
                        isError = portBad,
                        modifier = Modifier.weight(1.1f),
                    )
                }

                // Secret (MTProto) or Username / Password (Socks5 / HTTP)
                if (kind == ProxyKind.MTProto) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.proxy_secret)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        isError = draft.secretUnreadable,
                        supportingText = if (draft.secretUnreadable) {
                            { Text(stringResource(R.string.proxy_secret_unreadable)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.proxy_username)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.proxy_password)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Test Connection Section
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledTonalButton(
                        enabled = !testing &&
                            draft.host.isNotBlank() &&
                            draft.port in 1..65535 &&
                            draft.isUsable,
                        onClick = { runTest(draft) },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.proxy_test))
                    }

                    val result = testResult
                    when {
                        testing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.proxy_test_running),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        result != null && result.proxiedSeconds > 0.0 -> {
                            val proxiedMs = (result.proxiedSeconds * 1000).toInt()
                            val okText = stringResource(R.string.proxy_test_ok, proxiedMs)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = okText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        result != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.proxy_test_failed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                // Action Buttons: Cancel and Save
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        enabled = !portBad && draft.isUsable,
                        onClick = {
                            onSave(draft)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }

                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/** Legacy wrapper preserved for backwards compatibility. */
@Deprecated("Use ProxyScreen or ProxyEditDialog")
@Composable
fun ProxyDialog(onDismiss: () -> Unit) {
    ProxyEditDialog(
        initial = ProxyStore.current.takeIf { it.isUsable },
        onDismiss = onDismiss,
        onSave = {
            ProxyStore.save(it)
            (Telegram.client as? AndroidMusicTelegramClient)
                ?.applyProxy(it, resetNetwork = true)
        },
    )
}

private fun ProxyKind.labelRes(): Int = when (this) {
    ProxyKind.MTProto -> R.string.proxy_kind_mtproto
    ProxyKind.Socks5 -> R.string.proxy_kind_socks5
    ProxyKind.Http -> R.string.proxy_kind_http
}
