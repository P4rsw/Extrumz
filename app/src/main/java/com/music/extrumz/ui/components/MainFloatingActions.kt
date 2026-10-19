package com.extrumz.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.extrumz.app.R

/**
 * Floating action buttons on the main Library tab:
 * 1. Floating Shield (🛡): Displays when proxy is active; tapping opens ProxyScreen.
 * 2. Jump to Top (↑): Displays when scrolled down; tapping smoothly returns to item 0.
 *
 * Aligned to BottomEnd (which in RTL automatically places it at bottom-left)
 * and padded above the bottom bar and mini-player.
 */
@Composable
fun MainFloatingActions(
    showShield: Boolean,
    showJumpToTop: Boolean,
    onShieldClick: () -> Unit,
    onJumpToTopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Shield Button (Proxy Active)
        AnimatedVisibility(
            visible = showShield,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(elevation = 6.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.proxy_enabled),
                        onClick = onShieldClick,
                    ),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color(0x404CAF50)),
                tonalElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = stringResource(R.string.proxy_enabled),
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp),
                    )
                    // Small active status dot at top corner
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = (-8).dp, y = 8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50)),
                    )
                }
            }
        }

        // 2. Jump to Top Arrow Button
        AnimatedVisibility(
            visible = showJumpToTop,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(elevation = 6.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.jump_to_top),
                        onClick = onJumpToTopClick,
                    ),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                tonalElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.jump_to_top),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Determines whether the jump-to-top floating button should be visible based on list scroll state.
 */
fun shouldShowJumpToTop(
    firstVisibleIndex: Int,
    scrollOffset: Int,
    indexThreshold: Int = 2,
    offsetThreshold: Int = 50,
): Boolean = firstVisibleIndex > indexThreshold || scrollOffset > offsetThreshold

/**
 * Determines whether the main content floating action buttons (shield and jump-to-top)
 * should be displayed. They only appear on the root of Songs (home) and Library tabs,
 * when no overlay, sheet, detail page, or login flow is open.
 */
fun isMainContentTab(
    isWelcomeVisible: Boolean,
    selectedTab: Int,
    homeTab: Int,
    libraryTab: Int,
    hasDetail: Boolean,
    hasLibraryShowAll: Boolean,
    isSettingsOpen: Boolean,
    isEqualizerOpen: Boolean,
    isProxyOpen: Boolean,
    isPhoneLoginOpen: Boolean = false,
    isQrLoginOpen: Boolean = false,
    isProfileOpen: Boolean = false,
    isReplayOpen: Boolean = false,
    isHistoryOpen: Boolean = false,
    isChannelsOpen: Boolean = false,
    isPlaylistsOpen: Boolean = false,
    isLikedOpen: Boolean = false,
    hasOpenPlaylist: Boolean = false,
): Boolean =
    !isWelcomeVisible &&
        (selectedTab == homeTab || selectedTab == libraryTab) &&
        !hasDetail &&
        !hasLibraryShowAll &&
        !isSettingsOpen &&
        !isEqualizerOpen &&
        !isProxyOpen &&
        !isPhoneLoginOpen &&
        !isQrLoginOpen &&
        !isProfileOpen &&
        !isReplayOpen &&
        !isHistoryOpen &&
        !isChannelsOpen &&
        !isPlaylistsOpen &&
        !isLikedOpen &&
        !hasOpenPlaylist
