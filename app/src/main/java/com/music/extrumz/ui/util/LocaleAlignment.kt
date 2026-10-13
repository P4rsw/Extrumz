package com.extrumz.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Whether the running locale reads right-to-left.
 *
 * The app keeps an English skeleton — `supportsRtl` is off, so containers stay
 * left-to-right and the two languages never trade places. Persian text still
 * *reads* right-to-left inside its own box, and a few fields ask for that edge
 * directly rather than inheriting it. Asking unconditionally would flip the
 * English fields too, which is how this value came to exist: the alignment
 * belongs to the language of the words, not to the screen they sit on.
 *
 * Only `fa` is RTL here; the app ships no other RTL locale.
 */
val isRtlReading: Boolean
    @Composable get() = LocalConfiguration.current.locales[0].language == "fa"

/**
 * [androidx.compose.ui.text.style.TextAlign.End] under [isRtlReading],
 * [androidx.compose.ui.text.style.TextAlign.Start] otherwise — the alignment
 * Persian wants, granted only when Persian is what is on screen.
 */
@Composable
fun rtlAlign(
    end: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.End,
    start: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start,
) = if (isRtlReading) end else start

/**
 * Runs [content] with the reading direction of the current locale, so a screen
 * that wants to mirror for Persian can without the window ever flipping.
 *
 * `supportsRtl` is off and stays off: the app keeps one skeleton and the two
 * languages never trade places. Persian still reads the other way, and the
 * parts of the app the user asked to mirror — Library, Settings, the top bar's
 * status, the channel filter — get it here, scoped to their own subtree rather
 * than to the whole window. English passes LTR through unchanged, so this is
 * inert for everything but `fa`.
 */
@Composable
fun ProvideLocaleDirection(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtlReading) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) { content() }
}
