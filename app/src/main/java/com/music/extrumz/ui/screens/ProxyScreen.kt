package com.extrumz.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.extrumz.app.R
import com.extrumz.app.data.telegram.AndroidMusicTelegramClient
import com.extrumz.app.data.telegram.ProxyStore
import com.extrumz.app.data.telegram.SavedProxy
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.ui.components.ProxyEditDialog
import com.extrumz.app.ui.util.ProvideLocaleDirection
import kotlinx.coroutines.launch

/**
 * Proxy management screen displaying saved proxies, active selection,
 * comparative round-trip time verdicts (direct vs proxied), and management actions.
 */
@Composable
fun ProxyScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    ProvideLocaleDirection {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()

        val proxies by ProxyStore.all.collectAsStateWithLifecycle()
        val activeId by ProxyStore.activeId.collectAsStateWithLifecycle()
        val masterEnabled by ProxyStore.masterEnabled.collectAsStateWithLifecycle()

        var addingProxy by remember { mutableStateOf(false) }
        var editingProxy by remember { mutableStateOf<SavedProxy?>(null) }
        var proxyToDelete by remember { mutableStateOf<SavedProxy?>(null) }
        var testingId by remember { mutableStateOf<String?>(null) }

        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = contentPadding,
        ) {
            // Master toggle: enable or disable proxy usage globally
            item("master_switch") {
                SettingsGroup(topSpacing = 16.dp) {
                    SettingsRow(
                        icon = Icons.Rounded.Security,
                        title = stringResource(R.string.proxy_enable),
                        subtitle = if (masterEnabled) {
                            stringResource(R.string.connected)
                        } else {
                            stringResource(R.string.proxy_off)
                        },
                        trailing = {
                            Switch(
                                checked = masterEnabled,
                                onCheckedChange = { ProxyStore.setEnabled(it) },
                            )
                        },
                    )
                }
            }

            // Add proxy action button
            item("add_proxy_row") {
                SettingsGroup(topSpacing = 16.dp) {
                    SettingsRow(
                        icon = Icons.Rounded.Add,
                        title = stringResource(R.string.proxy_add),
                        subtitle = stringResource(R.string.proxy_paste_link),
                        onClick = { addingProxy = true },
                    )
                }
            }

            // Saved proxies section
            item("saved_header") {
                Text(
                    text = stringResource(R.string.proxy_saved_list),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 20.dp, top = 26.dp, bottom = 8.dp),
                )
            }

            if (proxies.isEmpty()) {
                item("empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(GroupShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.proxy_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.proxy_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(proxies, key = { it.id }) { proxy ->
                    val isSelected = masterEnabled && activeId == proxy.id
                    val isTesting = testingId == proxy.id

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .clip(GroupShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        // Clickable selection row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ProxyStore.activate(proxy.id) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { ProxyStore.activate(proxy.id) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = proxy.settings.host.ifBlank { stringResource(R.string.proxy) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${proxy.settings.kind.name} · ${proxy.settings.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))

                                // Comparative test verdict
                                if (isTesting) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.proxy_test_running),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else if (proxy.lastPingSeconds != null) {
                                    val directStr = if (proxy.lastDirectSeconds != null && proxy.lastDirectSeconds > 0) {
                                        stringResource(R.string.proxy_direct_ok, proxy.lastDirectSeconds)
                                    } else {
                                        stringResource(R.string.proxy_direct_failed)
                                    }
                                    val proxiedStr = if (proxy.lastPingSeconds > 0) {
                                        stringResource(R.string.proxy_proxied_ok, proxy.lastPingSeconds)
                                    } else {
                                        stringResource(R.string.proxy_proxied_failed)
                                    }
                                    val verdictText = "$directStr · $proxiedStr"
                                    val verdictColor = when {
                                        proxy.verdict?.worthIt == true -> MaterialTheme.colorScheme.primary
                                        proxy.lastPingSeconds > 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                    Text(
                                        text = verdictText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = verdictColor,
                                    )
                                } else {
                                    Text(
                                        text = "— ❓ " + stringResource(R.string.proxy_untested),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (isSelected) {
                                Text(
                                    text = "✅",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        )

                        // Action buttons row: Test, Edit, Copy, Delete
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = !isTesting,
                                onClick = {
                                    testingId = proxy.id
                                    scope.launch {
                                        val verdict = (Telegram.client as? AndroidMusicTelegramClient)
                                            ?.pingProxyVerdict(proxy.settings)
                                        if (verdict != null) {
                                            ProxyStore.recordPing(proxy.id, verdict)
                                        }
                                        testingId = null
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.proxy_test))
                            }

                            TextButton(onClick = { editingProxy = proxy }) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.proxy_edit))
                            }

                            TextButton(
                                onClick = {
                                    val link = proxy.settings.toLink()
                                    clipboardManager.setText(AnnotatedString(link))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.proxy_copied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.proxy_copy))
                            }

                            TextButton(onClick = { proxyToDelete = proxy }) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.proxy_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            item("bottom_spacing") {
                Spacer(Modifier.height(16.dp))
            }
        }

        // Add Dialog
        if (addingProxy) {
            ProxyEditDialog(
                initial = null,
                onDismiss = { addingProxy = false },
                onSave = {
                    ProxyStore.add(it)
                    addingProxy = false
                },
            )
        }

        // Edit Dialog
        editingProxy?.let { proxy ->
            ProxyEditDialog(
                initial = proxy.settings,
                onDismiss = { editingProxy = null },
                onSave = {
                    ProxyStore.update(proxy.id, it)
                    editingProxy = null
                },
            )
        }

        // Delete Confirmation Dialog
        proxyToDelete?.let { proxy ->
            AlertDialog(
                onDismissRequest = { proxyToDelete = null },
                title = { Text(stringResource(R.string.proxy_delete)) },
                text = { Text(stringResource(R.string.proxy_delete_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            ProxyStore.remove(proxy.id)
                            proxyToDelete = null
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { proxyToDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
