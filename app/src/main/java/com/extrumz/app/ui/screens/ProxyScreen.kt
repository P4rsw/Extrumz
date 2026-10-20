package com.extrumz.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import com.extrumz.app.ui.components.ExtrumzConfirmDialog
import com.extrumz.app.ui.components.ExtrumzDropdownMenu
import com.extrumz.app.ui.components.ExtrumzDropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        var testingIds by remember { mutableStateOf(emptySet<String>()) }
        var hasAutoTestedOnEntry by remember { mutableStateOf(false) }

        fun testProxy(proxy: SavedProxy) {
            testingIds = testingIds + proxy.id
            scope.launch {
                try {
                    val verdict = (Telegram.client as? AndroidMusicTelegramClient)
                        ?.pingProxyVerdict(proxy.settings)
                    if (verdict != null) {
                        ProxyStore.recordPing(proxy.id, verdict)
                    }
                } finally {
                    testingIds = testingIds - proxy.id
                }
            }
        }

        // Auto-test all saved proxies on screen entry (like Telegram)
        LaunchedEffect(proxies) {
            if (!hasAutoTestedOnEntry && proxies.isNotEmpty()) {
                hasAutoTestedOnEntry = true
                proxies.forEach { proxy ->
                    testProxy(proxy)
                }
            }
        }

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
                    val isTesting = proxy.id in testingIds

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
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = proxy.settings.host.ifBlank { stringResource(R.string.proxy) },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.connected),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${proxy.settings.kind.name} · ${proxy.settings.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))

                                // Comparative test verdict (Clean dot indicator, no emojis, ms unit)
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
                                    val isSuccess = proxy.lastPingSeconds > 0
                                    val proxiedMs = (proxy.lastPingSeconds * 1000).toInt()
                                    val statusText = if (isSuccess) {
                                        stringResource(R.string.proxy_proxied_ok, proxiedMs)
                                    } else {
                                        stringResource(R.string.proxy_proxied_failed)
                                    }
                                    val statusDotColor = if (isSuccess) {
                                        Color(0xFF4CAF50)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(statusDotColor)
                                        )
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSuccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                        )
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                        )
                                        Text(
                                            text = stringResource(R.string.proxy_untested),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            // Trailing Actions: Test / Refresh icon + 3-dots More menu
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                IconButton(
                                    onClick = { testProxy(proxy) },
                                    enabled = !isTesting,
                                ) {
                                    if (isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = stringResource(R.string.proxy_test),
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                var menuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = stringResource(R.string.proxy_more_options),
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    ExtrumzDropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        ExtrumzDropdownMenuItem(
                                            text = { Text(stringResource(R.string.proxy_edit)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.Edit,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                editingProxy = proxy
                                            },
                                        )
                                        ExtrumzDropdownMenuItem(
                                            text = { Text(stringResource(R.string.proxy_copy)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.ContentCopy,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                val link = proxy.settings.toLink()
                                                clipboardManager.setText(AnnotatedString(link))
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.proxy_copied),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            },
                                        )
                                        ExtrumzDropdownMenuItem(
                                            text = {
                                                Text(stringResource(R.string.delete))
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.Delete,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                            destructive = true,
                                            onClick = {
                                                menuExpanded = false
                                                proxyToDelete = proxy
                                            },
                                        )
                                    }
                                }
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
                onSave = { draft ->
                    val newId = ProxyStore.add(draft)
                    addingProxy = false
                    scope.launch {
                        val newProxy = ProxyStore.all.value.firstOrNull { it.id == newId }
                        if (newProxy != null) {
                            testProxy(newProxy)
                        }
                    }
                },
            )
        }

        // Edit Dialog
        editingProxy?.let { proxy ->
            ProxyEditDialog(
                initial = proxy.settings,
                onDismiss = { editingProxy = null },
                onSave = { draft ->
                    ProxyStore.update(proxy.id, draft)
                    editingProxy = null
                    scope.launch {
                        val updated = ProxyStore.all.value.firstOrNull { it.id == proxy.id }
                        if (updated != null) {
                            testProxy(updated)
                        }
                    }
                },
            )
        }

        // Delete Confirmation Dialog
        proxyToDelete?.let { proxy ->
            ExtrumzConfirmDialog(
                onDismissRequest = { proxyToDelete = null },
                title = stringResource(R.string.proxy_delete),
                message = stringResource(R.string.proxy_delete_confirm),
                confirmText = stringResource(R.string.delete),
                dismissText = stringResource(R.string.cancel),
                icon = Icons.Rounded.Delete,
                isDestructive = true,
                onConfirm = {
                    ProxyStore.remove(proxy.id)
                    proxyToDelete = null
                },
            )
        }
    }
}
