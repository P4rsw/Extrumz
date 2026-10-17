package com.extrumz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch

/**
 * Reusable dialog for adding a new proxy or editing an existing one.
 */
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEdit) R.string.proxy_edit else R.string.proxy_add)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The paste field. Deliberately first: it is how nearly everyone
                // will use this, and it makes the form below a confirmation
                // rather than data entry.
                OutlinedTextField(
                    value = pasted,
                    onValueChange = {
                        pasted = it
                        pasteError = null
                        ProxyLink.parse(it)?.let { parsed ->
                            enabled = true
                            kind = parsed.kind
                            host = parsed.host
                            port = parsed.port.toString()
                            secret = parsed.secret
                            username = parsed.username
                            password = parsed.password
                        }
                    },
                    label = { Text(stringResource(R.string.proxy_paste_link)) },
                    placeholder = { Text("https://t.me/proxy?server=…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                pasteError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Kind. MTProto first and described.
                ProxyKind.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = kind == option, onClick = { kind = option }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == option, onClick = { kind = option })
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(
                                text = stringResource(option.labelRes()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (option == ProxyKind.MTProto) {
                                Text(
                                    text = stringResource(R.string.proxy_kind_mtproto_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.proxy_host)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.proxy_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    isError = portBad,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind == ProxyKind.MTProto) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.proxy_secret)) },
                        singleLine = true,
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.proxy_password)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Test button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        enabled = !testing &&
                            draft.host.isNotBlank() &&
                            draft.port in 1..65535 &&
                            draft.isUsable,
                        onClick = { runTest(draft) },
                    ) {
                        Text(stringResource(R.string.proxy_test))
                    }
                    val result = testResult
                    when {
                        testing -> Text(
                            text = stringResource(R.string.proxy_test_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        result != null && result.proxiedSeconds > 0.0 -> {
                            val okText = stringResource(R.string.proxy_test_ok, result.proxiedSeconds)
                            val directInfo = if (result.directSeconds > 0.0) {
                                " (" + stringResource(R.string.proxy_direct_ok, result.directSeconds) + ")"
                            } else {
                                " (" + stringResource(R.string.proxy_direct_failed) + ")"
                            }
                            Text(
                                text = okText + directInfo,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        result != null -> Text(
                            text = stringResource(R.string.proxy_test_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !portBad && draft.isUsable,
                onClick = {
                    onSave(draft)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
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
