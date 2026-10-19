package com.extrumz.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Warning
import com.extrumz.app.ui.components.ExtrumzInfoDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.extrumz.app.R
import com.extrumz.app.data.telegram.ProxyStore
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthController
import com.extrumz.app.data.telegram.TelegramAuthState
import com.extrumz.app.ui.components.ExtrumzAnimatedMark
import com.extrumz.app.ui.components.mirroredForReading
import com.extrumz.app.ui.util.ProvideLocaleDirection
import java.util.Locale

/**
 * Welcome gateway screen for initial authorization, language selection,
 * and proxy configuration — styled seamlessly in the Telegram aesthetic.
 */
@Composable
fun WelcomeScreen(
    onLoginPhoneClick: () -> Unit,
    onLoginQrClick: () -> Unit,
    onProxyClick: () -> Unit,
    onLanguageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideLocaleDirection {
        val proxyOn by ProxyStore.masterEnabled.collectAsStateWithLifecycle()
        val savedProxies by ProxyStore.all.collectAsStateWithLifecycle()
        val activeProxyId by ProxyStore.activeId.collectAsStateWithLifecycle()
        val authState by Telegram.client.authState.collectAsStateWithLifecycle()

        var showHelpDialog by remember { mutableStateOf(false) }

        // Find active proxy ping if any
        val activeProxy = savedProxies.firstOrNull { it.id == activeProxyId }
        val activePingMs = activeProxy?.verdict?.proxiedSeconds?.let { (it * 1000).toInt() }

        // Determine current language name
        val currentLangTag = AppCompatDelegate.getApplicationLocales().get(0)?.language
            ?: Locale.getDefault().language
        val currentLangLabel = if (currentLangTag == "fa") "فارسی ▾" else "English ▾"

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top header section with animated mark
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 32.dp),
                ) {
                    ExtrumzAnimatedMark(
                        modifier = Modifier.size(100.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.welcome_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.welcome_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Middle actions section (Buttons + Proxy Card)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Error state banner if failed/closed
                    AnimatedVisibility(
                        visible = authState is TelegramAuthState.Failed || authState is TelegramAuthState.Closed,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val errorMsg = (authState as? TelegramAuthState.Failed)?.message
                                        ?: stringResource(R.string.tg_connection_reset)
                                    Text(
                                        text = errorMsg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                                TextButton(
                                    onClick = { TelegramAuthController.restartAuthorization() },
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.tg_retry))
                                }
                            }
                        }
                    }

                    // 1. Sign In with Phone Number (Primary action)
                    Button(
                        onClick = onLoginPhoneClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.welcome_login_phone),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // 2. Sign In with QR Code (Secondary action)
                    OutlinedButton(
                        onClick = onLoginQrClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.welcome_login_qr),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // 3. Proxy Configuration Card (Prominent for Iranian users)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onProxyClick),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            if (proxyOn) Color(0x554CAF50) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        ),
                        tonalElevation = 2.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (proxyOn) Color(0x224CAF50) else MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Security,
                                    contentDescription = null,
                                    tint = if (proxyOn) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (proxyOn) {
                                            if (activePingMs != null && activePingMs > 0) {
                                                stringResource(R.string.welcome_proxy_active_ping, activePingMs)
                                            } else {
                                                stringResource(R.string.welcome_proxy_active)
                                            }
                                        } else {
                                            stringResource(R.string.welcome_proxy_settings)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (proxyOn) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (proxyOn) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF4CAF50)),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (proxyOn) {
                                        stringResource(R.string.proxy_title)
                                    } else {
                                        stringResource(R.string.proxy_enable)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(20.dp)
                                    .mirroredForReading(),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Bottom row: Language selector & Help dialog trigger
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 400.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onLanguageClick) {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = currentLangLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    TextButton(onClick = { showHelpDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.welcome_help),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Help Dialog
            if (showHelpDialog) {
                ExtrumzInfoDialog(
                    onDismissRequest = { showHelpDialog = false },
                    title = stringResource(R.string.welcome_help_title),
                    message = stringResource(R.string.welcome_help_desc),
                    buttonText = stringResource(R.string.welcome_help_got_it),
                    icon = Icons.AutoMirrored.Rounded.HelpOutline,
                )
            }
        }
    }
}
