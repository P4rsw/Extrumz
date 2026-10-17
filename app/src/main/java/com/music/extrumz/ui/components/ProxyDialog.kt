package com.extrumz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.extrumz.app.R
import com.extrumz.app.data.telegram.ProxyKind
import com.extrumz.app.data.telegram.ProxyLink
import com.extrumz.app.data.telegram.ProxySettings
import com.extrumz.app.data.telegram.ProxyStore
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.AndroidMusicTelegramClient
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * The proxy editor.
 *
 * Two ways in, because there are two kinds of user. Most people have a link —
 * every channel that shares proxies shares a `t.me/proxy` URL — and for them the
 * paste field is the whole feature: it accepts the link, fills the form, and
 * saves. Everyone else can type host, port and secret by hand.
 *
 * The form is the same either way. The paste field fills it rather than
 * replacing it, so a link that parses but is wrong is still visible and still
 * editable instead of being silently saved.
 *
 * Saving does not just store: it calls through to the running client, so a
 * proxy entered here takes effect without a restart. That matters more than it
 * sounds — the connection is the thing that is broken, and asking someone to
 * close and reopen the app to find out whether they fixed it is the difference
 * between a setting and a guess.
 */
@Composable
fun ProxyDialog(onDismiss: () -> Unit) {
    val stored by ProxyStore.settings.collectAsStateWithLifecycle()

    var enabled by remember(stored) { mutableStateOf(stored.enabled) }
    var kind by remember(stored) { mutableStateOf(stored.kind) }
    var host by remember(stored) { mutableStateOf(stored.host) }
    var port by remember(stored) { mutableStateOf(if (stored.port > 0) stored.port.toString() else "") }
    var secret by remember(stored) { mutableStateOf(stored.secret) }
    var username by remember(stored) { mutableStateOf(stored.username) }
    var password by remember(stored) { mutableStateOf(stored.password) }
    var pasted by remember(stored) { mutableStateOf("") }
    var pasteError by remember(stored) { mutableStateOf<String?>(null) }
    // null = not run; -1.0 = could not reach; anything else = round trip in seconds
    var testResult by remember(stored) { mutableStateOf<Double?>(null) }
    var testing by remember(stored) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runTest(settings: ProxySettings) {
        testing = true
        testResult = null
        scope.launch {
            val seconds = (Telegram.client as? AndroidMusicTelegramClient)
                ?.pingProxy(settings) ?: -1.0
            testing = false
            testResult = seconds
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
        title = { Text(stringResource(R.string.proxy_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.proxy_enable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

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

                // Kind. MTProto first and described, because it is the one that
                // works: a SOCKS5 tunnel opens a raw socket per datacenter and
                // Telegram answers DC 4 with a flood, which is what made the
                // scan stop dead. Saying so here is cheaper than the support.
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
                    // Said out loud, because the opposite was invisible: the
                    // secret was accepted into the field, the app could not
                    // read it, and the client turned the proxy off while the
                    // screen went on showing it on. Three proxy links in a row
                    // "did not work" with nothing pointing at the reason.
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

                // Test before saving, not after failing.
                //
                // A proxy is a link pasted from a channel, and whether it is
                // alive is not something the user can tell by looking at it.
                // Until now the only way to find out was to save it, wait, and
                // guess -- which is how three links in a row got written off
                // with nothing to point at. This asks TDLib to walk the proxy
                // through to a datacenter and time it, without disturbing the
                // proxy currently in use.
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
                        result != null && result > 0.0 -> Text(
                            text = stringResource(R.string.proxy_test_ok, result),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                enabled = !portBad,
                onClick = {
                    val final = if (enabled && !draft.isUsable) {
                        // Asked for but incomplete: refused rather than saved,
                        // because a proxy that looks configured and never
                        // connects is harder to diagnose than one that is
                        // plainly off.
                        pasteError = null
                        return@TextButton
                    } else {
                        draft
                    }
                    ProxyStore.save(final)
                    // The one place a reset is wanted: the user just changed
                    // something and should not have to wait out TDLib's own
                    // backoff to find out whether it took.
                    (Telegram.client as? AndroidMusicTelegramClient)
                        ?.applyProxy(final, resetNetwork = true)
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

private fun ProxyKind.labelRes(): Int = when (this) {
    ProxyKind.MTProto -> R.string.proxy_kind_mtproto
    ProxyKind.Socks5 -> R.string.proxy_kind_socks5
    ProxyKind.Http -> R.string.proxy_kind_http
}
