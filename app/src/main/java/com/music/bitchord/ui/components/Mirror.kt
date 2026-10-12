package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Turns a one-way glyph to face the way the row reads.
 *
 * Compose only mirrors the vectors flagged `autoMirrored` — the ones shipped
 * with the framework under `Icons.AutoMirrored`. A glyph built in this project
 * with `ImageVector.Builder` has no such flag to set, so it stays pointing east
 * whichever way the screen is laid out: in a Persian UI a disclosure chevron
 * then faced back the way the user had come, and there are enough of them
 * scattered through the settings and the replay that the wrong turn is
 * everywhere at once.
 *
 * The platform icons are not touched here. They are switched to their
 * `Icons.AutoMirrored` counterparts instead, which is the supported route and
 * costs nothing; this is for the ones this project drew itself.
 *
 * A no-op in an LTR layout, which is where the app spends most of its life —
 * only the screens read right-to-left pay for it.
 */
@Composable
fun Modifier.mirroredForReading(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Ltr) this
    else graphicsLayer { scaleX = -1f }
