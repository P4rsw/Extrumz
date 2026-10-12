package com.music.bitchord.ui.components

import androidx.appcompat.app.AppCompatDelegate
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
 * Asked of the app's locales, which is the only answer that counts here: the
 * phone can be set to English while the app is set to Persian, and that is
 * how this one is configured. `Locale.getDefault` still answers the system
 * and `LocalConfiguration` is derived from it, so either of those decides the
 * numerals from a language the reader never chose. `AppCompatDelegate` holds
 * what was actually picked — the same question the translation and the app
 * language dialogs already ask — and the resources the strings came from
 * follow from it.
 */
internal fun persianNumerals(): Boolean =
    AppCompatDelegate.getApplicationLocales().get(0)?.language == "fa"

/**
 * Digits for the current language: `3:05` becomes `۳:۰۵` in Persian.
 *
 * A run that contains a Latin letter is left exactly as it is. The field this
 * is applied to is not always a numeral — the Replay's top-artist card prints
 * an artist's name in the slot where every other card prints a figure — and
 * `Hoodadk4` is a name with a four in it, not a count of four. Rewriting it
 * produced `Hoodadk۴`, which is neither the artist nor a number. Units and
 * punctuation are already left alone; this is the same rule one step up.
 */
internal fun String.forLanguage(): String {
    if (!persianNumerals() || none { it in WESTERN }) return this
    if (any { it in 'a'..'z' || it in 'A'..'Z' }) return this
    val out = toCharArray()
    for (i in out.indices) {
        val digit = WESTERN.indexOf(out[i])
        if (digit >= 0) out[i] = PERSIAN[digit]
    }
    return String(out)
}
