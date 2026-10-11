package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * Western digits, rewritten for the language the app is being read in.
 *
 * Formatting happens with `Locale.ROOT` throughout this app on purpose: a
 * timestamp that changed shape with the phone's region would be a bug in a
 * file parser, and `%02d` through a Persian locale pads with Persian digits
 * that nothing downstream expects. So the digits come out as `0..9` and get
 * rewritten here, at the point where a person reads them.
 *
 * Only the ten digit characters move. Everything else — the colon in `3:05`,
 * the `kHz`, the `LUFS`, the `-` in a decibel figure — is left alone, because
 * those are units and punctuation, not numerals, and a half-translated
 * measurement reads worse than a Western one.
 */
private val WESTERN = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
private val PERSIAN = charArrayOf('\u06f0', '\u06f1', '\u06f2', '\u06f3', '\u06f4',
    '\u06f5', '\u06f6', '\u06f7', '\u06f8', '\u06f9')

/**
 * Whether this build should be showing Persian numerals.
 *
 * Asked of the configuration rather than of `AppSettings`, because by the time
 * a `Text` is composing the answer has already been decided: the locale is what
 * picked the strings, and the numerals belong to the same choice.
 */
@Composable
internal fun persianNumerals(): Boolean =
    LocalConfiguration.current.locales.get(0).language == "fa"

/** Digits for the current language: `3:05` becomes `۳:۰۵` in Persian. */
@Composable
internal fun String.forLanguage(): String {
    if (!persianNumerals() || none { it in WESTERN }) return this
    val out = toCharArray()
    for (i in out.indices) {
        val digit = WESTERN.indexOf(out[i])
        if (digit >= 0) out[i] = PERSIAN[digit]
    }
    return String(out)
}
