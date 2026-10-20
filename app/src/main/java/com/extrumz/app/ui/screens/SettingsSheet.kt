package com.extrumz.app.ui.screens

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.extrumz.app.data.lyrics.translationLanguageName
import com.extrumz.app.ui.components.ExtrumzConfirmDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.extrumz.app.ui.components.isGlassSupported
import com.extrumz.app.ui.components.languageDisplayNameRes
import com.extrumz.app.ui.components.MessageState
import com.extrumz.app.ui.components.SearchField
import com.extrumz.app.ui.icons.ExtrumzIcons
import com.extrumz.app.ui.performance.resolvePerformanceRefreshRate
import com.extrumz.app.ui.performance.supportedPerformanceRefreshRates
import com.extrumz.app.data.LocalMediaRepository
import com.extrumz.app.data.NerdStats
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.settings.OutputPcmMode
import com.extrumz.app.playback.AudioOutputStatus
import com.extrumz.app.R
import com.extrumz.app.data.settings.ThemeMode
import com.extrumz.app.data.stats.Backup
import com.extrumz.app.playback.AudioCache
import com.extrumz.app.ui.player.fullBleedArtworkAvailable
import com.extrumz.app.ui.components.mirroredForReading
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.runtime.setValue
import com.extrumz.app.ui.util.ProvideLocaleDirection
import com.extrumz.app.data.telegram.ProxyStore
import androidx.compose.material.icons.rounded.Security

/**
 * Grouped settings, in the shape phones have taught people to expect: inset
 * cards of rows, a leading glyph per row, the current value on the right, and a
 * plain-language footer under any group whose effect isn't obvious from its
 * title. Anything with more than two choices opens a sheet rather than pushing
 * a row of chips into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The window's width, for the gates that depend on it. */
    windowWidth: Dp,
    onEqualizer: () -> Unit,
    onProxy: () -> Unit,
    onOpenReplay: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onAppLanguage: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    ProvideLocaleDirection {
        val context = LocalContext.current

        val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
        val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
        val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
        val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
        val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
        val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
        val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
        val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
        val liquidGlassSupported = isGlassSupported()
        val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
        val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
        val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
        val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
        val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
        val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
        val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
        val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
        val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
        val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
        val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
        val playingFormat by NerdStats.current.collectAsStateWithLifecycle()
        val playingDolbyAtmos = playingFormat?.isDolbyAtmos == true
        val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
        val wifiOnlyDownloads by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
        val exportDownloads by AppSettings.exportDownloads.collectAsStateWithLifecycle()
        val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
        val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
        val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()
        val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
        val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
        val smartVersionAlignment by AppSettings.smartVersionAlignment.collectAsStateWithLifecycle()
        val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
        val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
        val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
        val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
        val currentDisplay = LocalView.current.display
        val supportedRefreshRates = remember(currentDisplay) {
            currentDisplay.supportedPerformanceRefreshRates()
        }
        val selectedPerformanceRefreshRate = remember(currentDisplay, performanceRefreshRate) {
            currentDisplay.resolvePerformanceRefreshRate(performanceRefreshRate)
        }

        LaunchedEffect(selectedPerformanceRefreshRate, performanceRefreshRate) {
            if (selectedPerformanceRefreshRate != performanceRefreshRate) {
                AppSettings.setPerformanceRefreshRate(selectedPerformanceRefreshRate)
            }
        }

        // Filters the rows below — see [SettingsSearch]. Blank shows everything,
        // exactly as if the field weren't there.
        var searchQuery by remember { mutableStateOf("") }
        // What the last export or import did, shown on the row that did it rather
        // than as a toast: a backup is the one action here whose outcome nobody can
        // check by looking at the app afterwards. Held per direction, or an import's
        // result reports itself under the word "Export".
        var exportStatus by remember { mutableStateOf<String?>(null) }
        var importStatus by remember { mutableStateOf<String?>(null) }
        var confirmImport by remember { mutableStateOf(false) }
        var showPerformanceWarning by remember { mutableStateOf(false) }
        var showPerformanceConfirmation by remember { mutableStateOf(false) }
        val backupScope = rememberCoroutineScope()

        val batterySettingsLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            showPerformanceConfirmation = true
        }
        val localMusicFolderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { folder ->
            if (folder == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    folder,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            AppSettings.setLocalMusicFolderUri(folder.toString())
        }

        /**
         * Both halves go through the system document picker rather than a path of
         * this app's own choosing. That is what puts the file somewhere the user can
         * actually find it — Drive, Files, a folder they already back up — and it
         * means neither direction needs a storage permission, since the grant
         * arrives with the document they picked.
         */
        val exportPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { target ->
            if (target == null) return@rememberLauncherForActivityResult
            backupScope.launch {
                exportStatus = Backup.exportTo(context, target).fold(
                    onSuccess = { months ->
                        context.getString(R.string.export_succeeded, context.countOfMonths(months))
                    },
                    onFailure = {
                        context.getString(R.string.export_failed, it.message ?: context.getString(R.string.unknown_error))
                    },
                )
            }
        }
        val importPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { source ->
            if (source == null) return@rememberLauncherForActivityResult
            backupScope.launch {
                importStatus = Backup.importFrom(context, source).fold(
                    onSuccess = {
                        context.getString(R.string.import_succeeded, context.countOfMonths(it.months), it.from)
                    },
                    onFailure = {
                        context.getString(R.string.import_failed, it.message ?: context.getString(R.string.unknown_error))
                    },
                )
            }
        }

        val version = remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "1.0"
        }

        // Made fresh on every pass rather than remembered: it tallies the rows
        // that answered to the query, and that tally has to start from zero each
        // time the query changes.
        val search = SettingsSearch(searchQuery)

        // What is left after a keystroke is a different list, and the offset the
        // last one was scrolled to means nothing in it.
        val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        var isFirstSearchComposition by remember { mutableStateOf(true) }
        LaunchedEffect(searchQuery) {
            if (isFirstSearchComposition && searchQuery.isEmpty()) {
                // Skip the initial composition during Activity recreation:
                // rememberSaveable already restored the scroll position.
                isFirstSearchComposition = false
            } else {
                isFirstSearchComposition = false
                scrollState.scrollTo(0)
            }
        }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(contentPadding),
        ) {
            // fillMaxWidth before the padding, per the fix that landed for the
            // channel shelf (see the letter-break entry in MASTER_LIST). Without a
            // fixed width Compose closes the line at the text's intrinsic width,
            // shaped Persian is wider than that, and the last letter is sent to a
            // second line that draws as nothing — «تنظیما» over «ت».
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            )
            SearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSubmit = {},
                placeholder = stringResource(R.string.settings_search_hint),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )

            // Its own group rather than rows bolted onto the two above, because a
            // download is not a third kind of connection. The ceilings answer "what
            // does this minute cost"; these answer "what am I keeping, and when may
            // it be fetched" — and those two questions only make sense read
            // together, which is what puts them side by side here.
            SearchableSettingsGroup(search, header = stringResource(R.string.downloads)) {
                val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
                row(downloadWifiOnlyTitle, "wi-fi", "cellular", "دانلود", "وای فای", "فقط وای فای", "اینترنت همراه", divided = false) {
                    SettingsSubRow(
                        title = downloadWifiOnlyTitle,
                        checked = wifiOnlyDownloads,
                        onCheckedChange = AppSettings::setWifiOnlyDownloads,
                        badge = stringResource(R.string.blocking).takeIf { wifiOnlyDownloads && metered == true },
                    )
                }
                val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
                row(exportDownloadsTitle, "music folder", "پوشه", "پوشه موسیقی", "خروجی", "دانلود", "استخراج", "پوشه دانلود", divided = false) {
                    SettingsSubRow(
                        title = exportDownloadsTitle,
                        checked = exportDownloads,
                        onCheckedChange = AppSettings::setExportDownloads,
                        subtitle = "Music/Extrumz".takeIf { exportDownloads },
                    )
                }
            }

            // The connection settings, above playback: on a network that
            // blocks Telegram this is the row that makes the rest of the app
            // work at all, and burying it under playback would be answering
            // the wrong question.
            val proxySettings by ProxyStore.settings.collectAsStateWithLifecycle()

            SearchableSettingsGroup(
                search,
                header = stringResource(R.string.proxy),
                footer = stringResource(R.string.proxy_kind_mtproto_hint),
            ) {
                row("proxy", "vpn", "socks", "mtproto", "پروکسی", "فیلترشکن") {
                    SettingsRow(
                        icon = Icons.Rounded.Security,
                        title = stringResource(R.string.proxy),
                        subtitle = when {
                            !proxySettings.enabled -> stringResource(R.string.proxy_off)
                            proxySettings.isUsable -> proxySettings.label
                            else -> stringResource(R.string.proxy_incomplete)
                        },
                        onClick = onProxy,
                    )
                }
            }

            SearchableSettingsGroup(search, header = stringResource(R.string.playback)) {
                val outputPrecisionTitle = stringResource(R.string.output_precision)
                row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac", "دقت خروجی", "خروجی", "عمق بیت", "نرخ نمونهبرداری", "داک") {
                    SettingsRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = outputPrecisionTitle,
                        subtitle = buildString {
                            append(outputStatus.sink)
                            append(" · ")
                            append(outputStatus.deviceName)
                            (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                                ?.let { append(" · ${it / 1000.0} kHz") }
                            append(" · ")
                            append(AudioOutputStatus.encodingLabel(outputStatus))
                        },
                    )
                    SegmentedControl(
                        options = OutputPcmMode.entries.map(OutputPcmMode::label),
                        selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                        onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                        modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
                    )
                }
                val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
                row(preferUsbDacTitle, "headphone", "output", "هدفون", "خروجی", "یو اس بی", "داک", "داک یو اس بی") {
                    SettingsSubRow(
                        title = preferUsbDacTitle,
                        checked = preferUsbDac,
                        onCheckedChange = AppSettings::setPreferUsbDac,
                        badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
                    )
                }
                val loudnessTitle = stringResource(R.string.loudness_normalization)
                row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs", "نرمالسازی بلندی", "بلندی صدا", "نرمالسازی", "صدا", "گین ریپلی", "یکنواخت سازی") {
                    SettingsRow(
                        icon = Icons.Rounded.VolumeUp,
                        title = loudnessTitle,
                        subtitle = stringResource(R.string.loudness_normalization_subtitle),
                        trailing = {
                            Switch(
                                checked = loudnessNormalization,
                                onCheckedChange = AppSettings::setLoudnessNormalization,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
                    )
                }
                val crossfadeTitle = stringResource(R.string.crossfade)
                row(crossfadeTitle, "fade", "gapless", "کراسفید", "محو شدن", "پیوسته", "تداخل") {
                    SliderRow(
                        icon = Icons.Rounded.Waves,
                        title = crossfadeTitle,
                        subtitle = stringResource(R.string.crossfade_subtitle),
                        value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                        sliderValue = crossfade.toFloat(),
                        onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                        valueRange = 0f..12f,
                        steps = 11,
                    )
                }
                val skipSilenceTitle = stringResource(R.string.skip_silence)
                row(skipSilenceTitle, "silence", "رد کردن سکوت", "سکوت", "حذف سکوت") {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Rounded.VolumeOff,
                        title = skipSilenceTitle,
                        // Silence skipping is Media3's own processor, and
                        // `DefaultAudioSink.configure` appends that processor list
                        // only on its 16-bit branch — the float branch gets the
                        // format converter and nothing else. So on a float track
                        // this switch really does nothing, and the row says so
                        // rather than letting someone toggle it and wonder. The
                        // equaliser is unaffected: it runs upstream of that sink.
                        subtitle = if (outputStatus.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            stringResource(R.string.skip_silence_float_subtitle)
                        } else {
                            stringResource(R.string.skip_silence_subtitle)
                        },
                        trailing = {
                            Switch(
                                checked = skipSilence,
                                onCheckedChange = AppSettings::setSkipSilence,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setSkipSilence(!skipSilence) },
                    )
                }
                val spatialAudioTitle = stringResource(R.string.spatial_audio)
                row(spatialAudioTitle, "surround", "3d", "صدای فضایی", "سه بعدی", "فراگیر", "فضایی", "سوراند") {
                    SettingsRow(
                        icon = Icons.Rounded.SurroundSound,
                        title = spatialAudioTitle,
                        // Widening a JOC stream would fight the object-based mix
                        // Dolby has already spatialized, so the service holds this
                        // off for the duration of an Atmos track — see
                        // `PlaybackService.applySpatialAudioEnabled`. Said on the
                        // row because the switch stays where the listener left it,
                        // and a switch reading "on" over an effect that is not
                        // running is the same silent lie the equaliser screen used
                        // to tell.
                        subtitle = if (playingDolbyAtmos) {
                            stringResource(R.string.spatial_audio_atmos_subtitle)
                        } else {
                            stringResource(R.string.spatial_audio_subtitle)
                        },
                        trailing = {
                            Switch(
                                checked = spatialAudio,
                                onCheckedChange = AppSettings::setSpatialAudio,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
                    )
                }
                // The system panel is not listed here as well. A device with a
                // Dolby or Dirac panel has something Extrumz cannot reproduce and
                // keeps its row — but one level in, at the foot of the equaliser
                // screen, rather than as a second equaliser entry alongside ours.
                val equalizerTitle = stringResource(R.string.equalizer)
                row(equalizerTitle, "eq", "bass", "treble", "اکولایزر", "بیس", "تریبل", "تنظیم صدا") {
                    SettingsRow(
                        icon = Icons.Rounded.Tune,
                        title = equalizerTitle,
                        subtitle = stringResource(R.string.equalizer_subtitle),
                        onClick = onEqualizer,
                    )
                }
            }

            SearchableSettingsGroup(search, header = stringResource(R.string.appearance)) {
                val themeTitle = stringResource(R.string.theme)
                row(themeTitle, "dark mode", "light mode", "پوسته", "تم", "حالت تیره", "حالت روشن", "دارک مود") {
                    SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
                    SegmentedControl(
                        options = ThemeMode.entries.map { it.localizedLabel() },
                        selectedIndex = ThemeMode.entries.indexOf(theme),
                        onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                        modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                    )
                }
                val reduceAnimationTitle = stringResource(R.string.reduce_animation)
                row(reduceAnimationTitle, "motion", "پویانمایی", "کاهش پویانمایی", "انیمیشن", "کاهش انیمیشن", "حرکت") {
                    SettingsRow(
                        icon = Icons.Rounded.MotionPhotosOff,
                        title = reduceAnimationTitle,
                        subtitle = stringResource(R.string.reduce_animation_subtitle),
                        trailing = {
                            Switch(
                                checked = reduceAnimation,
                                onCheckedChange = AppSettings::setReduceAnimation,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
                    )
                }
                val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
                row(reduceDynamicBlurTitle, "blur", "performance", "تاری", "تاری پویا", "کاهش تاری", "بلر", "عملکرد") {
                    SettingsRow(
                        icon = Icons.Rounded.BlurOff,
                        title = reduceDynamicBlurTitle,
                        subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                        trailing = {
                            Switch(
                                checked = reduceDynamicBlur,
                                onCheckedChange = AppSettings::setReduceDynamicBlur,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
                    )
                }
                val liquidGlassTitle = stringResource(R.string.liquid_glass)
                row(liquidGlassTitle, "glass", "blur", "شیشه", "شیشه شفاف", "افکت شیشه", "تاری") {
                    SettingsRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = liquidGlassTitle,
                        subtitle = stringResource(
                            if (liquidGlassSupported) {
                                R.string.liquid_glass_subtitle
                            } else {
                                R.string.liquid_glass_unavailable
                            },
                        ),
                        enabled = liquidGlassSupported,
                        trailing = {
                            Switch(
                                checked = liquidGlass,
                                onCheckedChange = AppSettings::setLiquidGlass,
                                enabled = liquidGlassSupported,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
                    )
                }
                // Left out where the player won't honour it: a window too wide for
                // the player to fill and too narrow to stand a page beside it keeps
                // the sleeve either way. A docked pane is a phone's width, so it does
                // honour it — see [fullBleedArtworkAvailable].
                if (fullBleedArtworkAvailable(windowWidth)) {
                    val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
                    row(fullScreenCoverArtTitle, "artwork", "player", "کاور", "تمامصفحه", "کاور تمامصفحه", "پخشکننده", "تصویر آلبوم") {
                        SettingsRow(
                            icon = Icons.Rounded.Fullscreen,
                            title = fullScreenCoverArtTitle,
                            subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                            trailing = {
                                Switch(
                                    checked = fullBleedArtwork,
                                    onCheckedChange = AppSettings::setFullBleedArtwork,
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                                        ),
                                )
                            },
                            onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                        )
                    }
                }
                val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
                // Same reasoning as Animated cover art above: the lyrics source and
                // translation rows are only here while this is on.
                row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch", "متن آهنگ", "ترجمه", "متن ترانه", "همگام") {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Rounded.Notes,
                        title = syncedLyricsTitle,
                        subtitle = stringResource(R.string.synced_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = syncedLyrics,
                                onCheckedChange = AppSettings::setSyncedLyrics,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                    )
                }
                // Nothing to choose between while the feature is off, and the
                // sources are third-party services being reached on the user's
                // connection — which is the part worth being able to narrow.
                if (syncedLyrics) {
                    val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
                    row(lyricsBlurTitle, "lyrics", "blur", "متن آهنگ", "تاری", "محو کردن متن", "تاری متن") {
                        SettingsRow(
                            icon = Icons.Rounded.BlurOn,
                            title = lyricsBlurTitle,
                            subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                            trailing = {
                                Switch(
                                    checked = lyricsBlur,
                                    onCheckedChange = AppSettings::setLyricsBlur,
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            },
                            onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                        )
                    }
                    val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
                    row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch", "متن آهنگ", "منابع متن آهنگ", "منابع متن") {
                        SettingsRow(
                            icon = Icons.Rounded.Language,
                            title = lyricsSourcesTitle,
                            subtitle = lyricsSources
                                .sortedBy { it.ordinal }
                                .joinToString(", ") { it.label }
                                .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                            trailing = { Chevron() },
                            onClick = onLyricsSources,
                        )
                    }
                    val translationLanguageTitle = stringResource(R.string.translation_language)
                    row(translationLanguageTitle, "lyrics", "translate", "متن آهنگ", "ترجمه", "زبان ترجمه") {
                        SettingsRow(
                            icon = Icons.Rounded.Translate,
                            title = translationLanguageTitle,
                            subtitle = if (translationLanguage.isBlank()) {
                                stringResource(R.string.translation_language_subtitle)
                            } else {
                                translationLanguageName(
                                    translationLanguage,
                                    AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                                )
                            },
                            trailing = { Chevron() },
                            onClick = onTranslationLanguage,
                        )
                    }
                }
            }

            SearchableSettingsGroup(search, header = stringResource(R.string.language)) {
                val appLanguageTitle = stringResource(R.string.app_language)
                row(appLanguageTitle, "locale", "translate", "persian", "english", "فارسی", "زبان برنامه", "زبان", "انگلیسی", "ترجمه") {
                    val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.language
                        ?: java.util.Locale.getDefault().language
                    SettingsRow(
                        icon = Icons.Rounded.Language,
                        title = appLanguageTitle,
                        subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                        onClick = onAppLanguage,
                    )
                }
            }

            SearchableSettingsGroup(search, header = stringResource(R.string.performance)) {
                val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
                row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth", "عملکرد بالا", "نرخ نوسازی", "نرخ فریم", "روان", "باتری", "هرتز") {
                    SettingsRow(
                        icon = ExtrumzIcons.Performance,
                        title = highPerformanceModeTitle,
                        subtitle = if (highPerformanceMode) {
                            stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                        } else {
                            stringResource(R.string.high_performance_subtitle)
                        },
                        trailing = {
                            Switch(
                                checked = highPerformanceMode,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showPerformanceWarning = true
                                    } else {
                                        AppSettings.setHighPerformanceMode(false)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = {
                            if (highPerformanceMode) {
                                AppSettings.setHighPerformanceMode(false)
                            } else {
                                showPerformanceWarning = true
                            }
                        },
                    )
                }
                if (highPerformanceMode) {
                    val refreshRateTitle = stringResource(R.string.refresh_rate)
                    row(refreshRateTitle, "hz", "frame rate", "نرخ نوسازی", "نرخ فریم", "هرتز") {
                        SettingsRow(
                            icon = ExtrumzIcons.FrameRate,
                            title = refreshRateTitle,
                        )
                        SegmentedControl(
                            options = supportedRefreshRates.map { "$it Hz" },
                            selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                            onSelect = { index ->
                                AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                            },
                            modifier = Modifier.padding(
                                start = TEXT_INSET,
                                end = ROW_INSET,
                                bottom = 14.dp,
                            ),
                        )
                    }
                }
            }

            SearchableSettingsGroup(search, header = stringResource(R.string.local_music)) {
                val localMusicFolderTitle = stringResource(R.string.local_music_folder)
                row(localMusicFolderTitle, "folder", "offline", "files", "پوشه", "موسیقی محلی", "آهنگ محلی", "پوشه آهنگ", "فایلها", "آفلاین") {
                    SettingsRow(
                        icon = Icons.Rounded.Folder,
                        title = localMusicFolderTitle,
                        subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                            ?: stringResource(R.string.all_audio_folders),
                        onClick = { localMusicFolderPicker.launch(null) },
                    )
                }
                // Always drawn, never gated on a folder already being picked: behind
                // that gate the row was invisible on a fresh install, which read as
                // the app having no such option at all. It is greyed instead — there
                // is nothing to go back from when every folder is already in use.
                val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
                row(useAllAudioFoldersTitle, "folder", "پوشه", "همهٔ پوشههای صوتی", "پوشههای صوتی", "همه پوشهها") {
                    SettingsRow(
                        icon = Icons.Rounded.LibraryMusic,
                        title = useAllAudioFoldersTitle,
                        subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                        enabled = localMusicFolderUri.isNotBlank(),
                        onClick = { AppSettings.setLocalMusicFolderUri("") },
                    )
                }
                val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
                row(filterNonMusicAudioTitle, "podcast", "recording", "فیلتر", "پادکست", "ضبط صدا", "فایلهای صوتی غیرموسیقی", "غیر موسیقی") {
                    SettingsRow(
                        icon = Icons.Rounded.FilterAlt,
                        title = filterNonMusicAudioTitle,
                        subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                        trailing = {
                            Switch(
                                checked = filterNonMusicAudio,
                                onCheckedChange = AppSettings::setFilterNonMusicAudio,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
                    )
                }
            }

            val cacheLimitMb = (cacheLimitBytes / (1024 * 1024)).toInt()
            SearchableSettingsGroup(search, header = stringResource(R.string.storage)) {
                val songCacheLimitTitle = stringResource(R.string.song_cache_limit)
                row(songCacheLimitTitle, "cache", "space", "کش", "کش آهنگ", "محدودیت کش", "حافظه", "فضا", "حافظه کش") {
                    SliderRow(
                        icon = Icons.Rounded.Storage,
                        title = songCacheLimitTitle,
                        subtitle = if (cacheLimitMb > CACHE_WARNING_MB) {
                            stringResource(R.string.song_cache_large_subtitle, formatCacheSize(cacheLimitMb))
                        } else {
                            stringResource(R.string.song_cache_limit_subtitle)
                        },
                        value = formatCacheSize(cacheLimitMb),
                        sliderValue = cacheLimitMb.toFloat(),
                        onSliderValue = {
                            AppSettings.setAudioCacheLimitBytes(it.roundToInt().toLong() * 1024 * 1024)
                        },
                        valueRange = (AppSettings.DEFAULT_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat()..
                            (AppSettings.MAX_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat(),
                        steps = 18,
                    )
                }
                val clearSongCacheTitle = stringResource(R.string.clear_song_cache)
                row(clearSongCacheTitle, "cache", "free space", "کش", "پاک کردن کش", "حذف کش", "فضای خالی", "کش آهنگ") {
                    SettingsRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = clearSongCacheTitle,
                        subtitle = stringResource(R.string.clear_song_cache_subtitle),
                        onClick = {
                            AudioCache.clear {
                                Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                val clearImageCacheTitle = stringResource(R.string.clear_image_cache)
                row(clearImageCacheTitle, "cache", "artwork", "free space", "کش", "کاور", "کش تصاویر", "پاک کردن کش", "تصاویر آلبوم") {
                    SettingsRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = clearImageCacheTitle,
                        subtitle = stringResource(R.string.clear_image_cache_subtitle),
                        onClick = {
                            val loader = SingletonImageLoader.get(context)
                            loader.memoryCache?.clear()
                            loader.diskCache?.clear()
                            Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }

            SearchableSettingsGroup(search, header = stringResource(R.string.your_data)) {
                val replayTitle = stringResource(R.string.replay)
                row(replayTitle, "stats", "history", "wrapped", "آمار", "تاریخچه", "ریپلی", "مرور", "آمار شنیدن") {
                    SettingsRow(
                        icon = Icons.Rounded.BarChart,
                        title = replayTitle,
                        subtitle = stringResource(R.string.replay_subtitle),
                        onClick = onOpenReplay,
                    )
                }
                val exportDataTitle = stringResource(R.string.export_data)
                row(exportDataTitle, "backup", "پشتیبان", "پشتیبانگیری", "بکاپ", "خروجی", "خروجی دادهها", "استخراج") {
                    SettingsRow(
                        icon = Icons.Rounded.FileUpload,
                        title = exportDataTitle,
                        subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                        onClick = { exportPicker.launch(Backup.suggestedName()) },
                    )
                }
                val importDataTitle = stringResource(R.string.import_data)
                row(importDataTitle, "backup", "restore", "پشتیبان", "بازیابی", "بکاپ", "وارد کردن دادهها", "ریستور") {
                    SettingsRow(
                        icon = Icons.Rounded.FileDownload,
                        title = importDataTitle,
                        subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                        onClick = { confirmImport = true },
                    )
                }
            }

            // No footer: it only restated the stop-on-close row's own subtitle,
            // which sits four rows above it and says the same thing in fewer words.
            SearchableSettingsGroup(search, header = stringResource(R.string.miscellaneous)) {
                val playNextOnSwipeTitle = stringResource(R.string.play_next_on_swipe)
                row(playNextOnSwipeTitle, "swipe", "queue", "سوایپ", "کشیدن", "صف پخش", "پخش بعدی") {
                    SettingsRow(
                        icon = Icons.Rounded.PlaylistPlay,
                        title = playNextOnSwipeTitle,
                        subtitle = if (swipeToPlayNext) {
                            stringResource(R.string.swipe_plays_next)
                        } else {
                            stringResource(R.string.swipe_adds_to_queue)
                        },
                        trailing = {
                            Switch(
                                checked = swipeToPlayNext,
                                onCheckedChange = AppSettings::setSwipeToPlayNext,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
                    )
                }
                val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
                row(dontRepeatSongsTitle, "radio", "suggestions", "رادیو", "پیشنهادات", "تکرار", "عدم تکرار", "پیشنهادها") {
                    SettingsRow(
                        icon = Icons.Rounded.History,
                        title = dontRepeatSongsTitle,
                        subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                        trailing = {
                            Switch(
                                checked = dontRepeatSuggestions,
                                onCheckedChange = AppSettings::setDontRepeatSuggestions,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
                    )
                }
                val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
                row(stopMusicOnCloseTitle, "notification", "background", "اعلان", "پسزمینه", "توقف پخش", "بستن", "بستن برنامه") {
                    SettingsRow(
                        icon = Icons.Rounded.MusicOff,
                        title = stopMusicOnCloseTitle,
                        subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                        trailing = {
                            Switch(
                                checked = stopOnTaskRemoved,
                                onCheckedChange = AppSettings::setStopOnTaskRemoved,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
                    )
                }
                val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
                row(hideVolumeBarTitle, "volume", "player", "صدا", "میزان صدا", "ولوم", "نوار صدا", "پخشکننده") {
                    SettingsRow(
                        icon = Icons.Rounded.VolumeOff,
                        title = hideVolumeBarTitle,
                        subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                        trailing = {
                            Switch(
                                checked = hideVolumeBar,
                                onCheckedChange = AppSettings::setHideVolumeBar,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
                    )
                }
                val hideSongStatusTitle = stringResource(R.string.hide_song_status)
                row(hideSongStatusTitle, "player", "playing from", "played by", "وضعیت آهنگ", "پخشکننده", "در حال پخش از", "پخش شده توسط") {
                    SettingsRow(
                        icon = Icons.Rounded.VisibilityOff,
                        title = hideSongStatusTitle,
                        subtitle = stringResource(R.string.hide_song_status_subtitle),
                        trailing = {
                            Switch(
                                checked = hideSongStatus,
                                onCheckedChange = AppSettings::setHideSongStatus,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
                    )
                }
            }

            SearchableSettingsGroup(search, header = stringResource(R.string.advanced_options)) {
                val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
                row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro", "همگامسازی", "هماهنگسازی", "شکل موج", "ویدیو", "صوت", "مقدمه") {
                    SettingsRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = smartAlignmentTitle,
                        subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                        trailing = {
                            Switch(
                                checked = smartVersionAlignment,
                                onCheckedChange = AppSettings::setSmartVersionAlignment,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
                    )
                }
                val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
                row(showNerdStatsTitle, "debug", "bitrate", "codec", "دیباگ", "بیت ریت", "کدک", "آمار تخصصی", "اطلاعات فنی") {
                    SettingsRow(
                        icon = Icons.Rounded.BarChart,
                        title = showNerdStatsTitle,
                        subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                        trailing = {
                            Switch(
                                checked = nerdStats,
                                onCheckedChange = AppSettings::setShowNerdStats,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                    )
                }
            }

            // Read after every group above has had its turn at the query, which is
            // what makes this an accurate "nothing here" rather than a guess.
            if (!search.anyMatch) {
                MessageState(stringResource(R.string.settings_search_empty, searchQuery))
            }

            // The version line is the page's colophon, not a setting: it belongs to
            // the whole list, so it goes when the list is narrowed to a few rows.
            if (searchQuery.isBlank()) {
            Text(
                text = buildAnnotatedString {
                    append("Extrumz $version  ")
                    val linkStyles = TextLinkStyles(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    withLink(LinkAnnotation.Url("https://github.com/P4rsw/Extrumz", linkStyles)) {
                        append("GitHub")
                    }
                    append("  ")
                    withLink(LinkAnnotation.Url("https://github.com/P4rsw", linkStyles)) {
                        append("Developer")
                    }
                    append("  ")
                    withLink(LinkAnnotation.Url("https://github.com/P4rsw/Extrumz/releases", linkStyles)) {
                        append("Website")
                    }
                    append("\nTelegram Music Player")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
            )
            }
        }

        // Asked before the picker opens rather than after a file is chosen: the
        // thing being confirmed is that this device's own history is about to be
        // thrown away, and that is true whichever file gets picked.
        if (confirmImport) {
            ExtrumzConfirmDialog(
                onDismissRequest = { confirmImport = false },
                title = stringResource(R.string.import_backup_title),
                message = stringResource(R.string.import_backup_warning),
                confirmText = stringResource(R.string.choose_file),
                dismissText = stringResource(R.string.cancel),
                icon = Icons.Rounded.Warning,
                onConfirm = {
                    confirmImport = false
                    importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
            )
        }

        if (showPerformanceWarning) {
            ExtrumzConfirmDialog(
                onDismissRequest = { showPerformanceWarning = false },
                title = stringResource(R.string.high_performance_before_enabling),
                message = stringResource(R.string.high_performance_battery_warning),
                confirmText = stringResource(R.string.open_battery_settings),
                dismissText = stringResource(R.string.cancel),
                icon = Icons.Rounded.BatteryChargingFull,
                onConfirm = {
                    showPerformanceWarning = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    val settingsIntent = intent.takeIf {
                        it.resolveActivity(context.packageManager) != null
                    } ?: Intent(Settings.ACTION_SETTINGS)
                    batterySettingsLauncher.launch(settingsIntent)
                },
            )
        }

        if (showPerformanceConfirmation) {
            ExtrumzConfirmDialog(
                onDismissRequest = { showPerformanceConfirmation = false },
                title = stringResource(R.string.enable_high_performance_title),
                message = stringResource(R.string.enable_high_performance_confirmation),
                confirmText = stringResource(R.string.enable),
                dismissText = stringResource(R.string.not_yet),
                icon = Icons.Rounded.Speed,
                onConfirm = {
                    showPerformanceConfirmation = false
                    AppSettings.setHighPerformanceMode(true)
                },
            )
        }


    }}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

@Composable
private fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
    },
)

/**
 * Hands the device's own effect panel this app's audio session.
 *
 * Shared with [EqualizerScreen], which offers the same escape hatch a second
 * time next to the equaliser it might be an alternative to.
 */
internal fun openEqualizer(context: Context, sessionId: Int) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, context.getString(R.string.no_equalizer), Toast.LENGTH_SHORT).show()
    }
}

/** Above this, the cache limit slider's subtitle warns rather than reassures. */
private const val CACHE_WARNING_MB = 2048

/** "512 MB", "2 GB", "2.5 GB" — whichever reads more naturally at that size. */
private fun formatCacheSize(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val gb = mb / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(Locale.ROOT, gb)
}

// ---- Building blocks --------------------------------------------------------

internal val GroupShape = RoundedCornerShape(14.dp)
internal val GROUP_INSET = 16.dp
internal val ROW_INSET = 16.dp
internal val ICON_SIZE = 22.dp
internal val ICON_GAP = 14.dp

/** Where a row's text starts — dividers are inset to match, as on iOS. */
internal val TEXT_INSET = ROW_INSET + ICON_SIZE + ICON_GAP

/**
 * What is typed into the field under the Settings title, and whether anything
 * on the screen has answered to it.
 *
 * A blank query matches everything, so the screen with the field untouched is
 * the screen as it was before there was a field. Rows ask [matches] as they
 * are composed; the empty state at the foot of the list reads [anyMatch] after
 * every group has had its turn, which is why this is made fresh on each pass
 * rather than remembered.
 */
private class SettingsSearch(query: String) {
    // Trimmed, because a trailing space is a typo rather than a search for
    // something ending in one — and on a phone keyboard it is one keystroke
    // away from every word typed.
    private val needle = query.trim()

    var anyMatch = false
        private set

    fun matches(vararg keywords: String?): Boolean {
        val hit = needle.isEmpty() ||
            keywords.any { it?.contains(needle, ignoreCase = true) == true }
        if (hit) anyMatch = true
        return hit
    }
}

/** Collects the rows of one group that survived the search. */
private class SettingsGroupScope(
    private val search: SettingsSearch,
    private val header: String?,
) {
    class Entry(val divided: Boolean, val content: @Composable () -> Unit)

    val entries = mutableListOf<Entry>()

    /**
     * One searchable setting. [keywords] is everything somebody might type
     * looking for it: its title at least, plus whatever its subtitle or the
     * controls under it say that the title doesn't — "Spotify", say, for the
     * canvas link that lives under Animated cover art.
     *
     * [divided] is false for the rows drawn as part of the row above them
     * rather than as settings in their own right, so they keep hugging their
     * parent when both survive the filter.
     */
    fun row(
        vararg keywords: String?,
        divided: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        // The header counts for every row beneath it: searching "playback"
        // should turn up the playback group entire, not nothing at all.
        if (search.matches(header, *keywords)) entries += Entry(divided, content)
    }
}

/**
 * A [SettingsGroup] whose rows are filtered by the search field, and which
 * takes itself off the screen when none of them are left. Dividers fall
 * between the rows that survived rather than around the gaps left by the ones
 * that didn't, so a filtered card is indistinguishable from a hand-written one.
 */
@Composable
private fun SearchableSettingsGroup(
    search: SettingsSearch,
    header: String? = null,
    footer: String? = null,
    topSpacing: Dp = 26.dp,
    content: @Composable SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope(search, header)
    scope.content()
    if (scope.entries.isEmpty()) return
    SettingsGroup(header = header, footer = footer, topSpacing = topSpacing) {
        scope.entries.forEachIndexed { index, entry ->
            when {
                index > 0 && entry.divided -> RowDivider()
                // An undivided row is drawn tight to the top because something
                // it belongs to is usually above it. Filtered down to itself it
                // has nothing to sit under, so it is given that room back.
                index == 0 && !entry.divided -> Spacer(Modifier.height(10.dp))
            }
            entry.content()
        }
    }
}

/**
 * One inset card of rows, with an uppercase header above and an optional
 * plain-language [footer] below. Rows are separated by [RowDivider].
 */
@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    /** Room above the card, where there is no [header] to provide it. */
    topSpacing: Dp = 26.dp,
    content: @Composable () -> Unit,
) {
    if (header != null) {
        // softWrap=false is the fix, and it is not a layout preference. The
        // letter was never too wide for the box: a loose constraint closes the
        // line at the text's intrinsic width, shaped Persian is wider than
        // that measurement, and Compose sent the final letter to a second line
        // nothing drew («تنظیما» for «تنظیمات»). Refusing to wrap removes the
        // second line the letter was escaping to. fillMaxWidth then pins the
        // constraint so shaping gets the real box, not an intrinsic guess.
        Text(
            text = header.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = GROUP_INSET + 4.dp,
                    end = GROUP_INSET,
                    top = 26.dp,
                    bottom = 8.dp,
                ),
        )
    } else {
        Spacer(Modifier.height(topSpacing))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = TEXT_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * The standard row: glyph, title, optional subtitle, and on the right either
 * [trailing] (a switch, say) or the current [value] followed by a chevron.
 *
 * [iconPainter] is for the handful of rows whose glyph is a drawable rather
 * than a Material icon — the Dolby double-D, which is a mark and not something
 * to approximate with the nearest speaker outline. Exactly one of it and [icon]
 * is expected; the painter wins where both are given.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

/**
 * A toggle that reads as part of the option above it rather than a setting
 * of its own: no icon, no divider, and pulled up close against its parent
 * instead of getting the same breathing room a full [SettingsRow] gets.
 */
@Composable
internal fun SettingsSubRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** Marks the connection whose ceiling is actually in force right now. */
@Composable
internal fun Badge(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Chevron() {
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        // Built in-house and not flagged autoMirrored, so it turns itself.
        modifier = Modifier.size(20.dp).mirroredForReading(),
    )
}

/** A continuous setting: label and current value on one line, track beneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderValue,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            // Bare track: the step ticks and the end-stop dot are noise when the
            // value is already spelled out on the line above.
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = colors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
        )
    }
}

/** Sign out: centered, accent-coloured, no glyph — the shape of a real one. */
@Composable
internal fun DestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Sliding pill selector, for the handful of settings with two or three states. */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.outline)
            // Enough of a margin for the track to read as a track. At 2dp the
            // selected pill sat all but flush against the container's own edge,
            // so the two rounded rectangles merged into one shape and the
            // control stopped looking like something with a position in it.
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val pill by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = tween(160),
                label = "segmentPill",
            )
            val labelColor by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "segmentLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pill)
                    .clickable(enabled = enabled) {
                        if (!chosen) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        }
    }
}
