package com.extrumz.app

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthController
import com.extrumz.app.playback.AudioCache
import com.extrumz.app.playback.LastPlayed
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.settings.SearchHistory
import com.extrumz.app.data.sources.SourceRegistry
import com.extrumz.app.data.stats.ArtistFacts
import com.extrumz.app.data.stats.ListeningStats
import com.extrumz.app.download.Downloads
import kotlin.concurrent.thread

class ExtrumzApplication : Application(), SingletonImageLoader.Factory {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Opened off the main thread, alongside everything below: none of these
        // reads a setting, and between them they are the slowest opens at
        // startup — SourceRegistry's encrypted store most of all. Joined before
        // onCreate returns, so nothing that runs after startup can see any of
        // them half open.
        val backgroundInit = thread(name = "startup-init") {
            // Cheap: only stores the context — the Room database itself opens
            // lazily on the first DAO call, off the startup critical path.
            TelegramDb.init(this)
            // Same for the TDLib client holder: TDLib only boots on start().
            Telegram.init(this)
            // Boots TDLib (once) and arms the auto-scan that fires the moment
            // authorization completes — cold start to an already-logged-in
            // session fills the songs tab without anyone tapping anything.
            TelegramAuthController.ensureStarted()
            SourceRegistry.init(this)
        }
        AppSettings.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        // What's already saved to Downloads, so the song menu can say so
        // without a media-store query per row.
        Downloads.init(this)
        // The device's own listening record. Opened here rather than in
        // PlaybackService because the Replay page reads it from the UI side and
        // both live in this process — one owner, one directory.
        ListeningStats.init(this)
        // After AppSettings, whose switch decides whether half of it runs.
        ArtistFacts.init(this)
        // One cache directory can only be opened once per process, and
        // PlaybackService shares this one — so it's opened here, not there.
        AudioCache.init(this)
        // A sideloaded update is just a new APK over the old one, so app data —
        // including whatever the old build left in these caches — survives it
        // untouched. Wipe both on the first launch of a higher versionCode so a
        // format or key change between builds can't serve stale or mismatched
        // bytes from a cache the new code didn't write.
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            AudioCache.clear()
            SingletonImageLoader.get(this).let { loader ->
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
        backgroundInit.join()
    }

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Covers arriving with a hard cut read as the list flickering as
            // it scrolls; a short fade reads as them developing.
            .crossfade(200)
            .build()
}
