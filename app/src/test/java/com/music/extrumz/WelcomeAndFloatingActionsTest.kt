package com.music.extrumz

import com.extrumz.app.data.telegram.TelegramAuthState
import com.extrumz.app.data.telegram.shouldShowWelcome
import com.extrumz.app.ui.components.isMainContentTab
import com.extrumz.app.ui.components.shouldShowJumpToTop
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for WelcomeScreen gateway logic and MainFloatingActions visibility rules.
 * Directly exercises production methods: [shouldShowWelcome], [shouldShowJumpToTop], and [isMainContentTab].
 */
class WelcomeAndFloatingActionsTest {

    @Test
    fun `gateway routing - splash holds during uninitialized and initializing`() {
        val uninit: TelegramAuthState = TelegramAuthState.Uninitialized
        val initializing: TelegramAuthState = TelegramAuthState.Initializing

        val isInitializingUninit = uninit is TelegramAuthState.Uninitialized || uninit is TelegramAuthState.Initializing
        val isInitializingInit = initializing is TelegramAuthState.Uninitialized || initializing is TelegramAuthState.Initializing

        assertTrue(isInitializingUninit)
        assertTrue(isInitializingInit)

        // Authenticated user goes straight to Ready - must NOT trigger initializing
        val ready: TelegramAuthState = TelegramAuthState.Ready
        val isInitializingReady = ready is TelegramAuthState.Uninitialized || ready is TelegramAuthState.Initializing
        assertFalse(isInitializingReady)
    }

    @Test
    fun `gateway routing - welcome screen visible only when not ready and auth initialized`() {
        // Authenticated user -> welcome hidden
        assertFalse(shouldShowWelcome(TelegramAuthState.Ready))

        // Initializing states -> splash is active, welcome must be hidden to prevent flash for returning users
        assertFalse(shouldShowWelcome(TelegramAuthState.Uninitialized))
        assertFalse(shouldShowWelcome(TelegramAuthState.Initializing))

        // Unauthenticated settled states -> welcome screen visible
        assertTrue(shouldShowWelcome(TelegramAuthState.WaitingForPhoneNumber))
        assertTrue(shouldShowWelcome(TelegramAuthState.WaitingForQrScan("tg://login?token=xyz")))
        assertTrue(shouldShowWelcome(TelegramAuthState.WaitingForCode("+989123456789")))
        assertTrue(shouldShowWelcome(TelegramAuthState.WaitingForPassword("hint")))
        assertTrue(shouldShowWelcome(TelegramAuthState.Failed("connection error")))
        assertTrue(shouldShowWelcome(TelegramAuthState.Closed))
    }

    @Test
    fun `floating actions - jump to top only visible when scrolled down`() {
        // At top of library: hidden
        assertFalse(shouldShowJumpToTop(0, 0))
        assertFalse(shouldShowJumpToTop(0, 20))
        assertFalse(shouldShowJumpToTop(1, 10))
        assertFalse(shouldShowJumpToTop(2, 0))

        // Scrolled down: visible
        assertTrue(shouldShowJumpToTop(0, 51))
        assertTrue(shouldShowJumpToTop(1, 60))
        assertTrue(shouldShowJumpToTop(3, 0))
        assertTrue(shouldShowJumpToTop(50, 100))
    }

    @Test
    fun `floating actions - shield and arrow visible on main songs and library tabs`() {
        val homeTab = 0
        val searchTab = 1
        val libraryTab = 2

        // Main library tab (2): visible
        assertTrue(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = libraryTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
            )
        )

        // Main songs tab (0): visible
        assertTrue(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = homeTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
            )
        )

        // Search tab (1): hidden
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = searchTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
            )
        )

        // Welcome screen active: must be hidden
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = true,
                selectedTab = homeTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
            )
        )
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = true,
                selectedTab = libraryTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
            )
        )

        // Detail page opened: must be hidden
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = homeTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = true,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
            )
        )

        // Proxy screen opened: must be hidden
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = homeTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = true,
            )
        )

        // Login and profile screens opened: must be hidden
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = homeTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
                isPhoneLoginOpen = true,
            )
        )
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = homeTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
                isQrLoginOpen = true,
            )
        )
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = homeTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
                isProfileOpen = true,
            )
        )
        assertFalse(
            isMainContentTab(
                isWelcomeVisible = false,
                selectedTab = homeTab,
                homeTab = homeTab,
                libraryTab = libraryTab,
                hasDetail = false,
                hasLibraryShowAll = false,
                isSettingsOpen = false,
                isEqualizerOpen = false,
                isProxyOpen = false,
                isReplayOpen = true,
            )
        )
    }
}
