package com.music.bitchord

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
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.canvas.CanvasCache
import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.smb.SmbCoverFetcher
import com.music.bitchord.data.telegram.Telegram
import com.music.bitchord.data.telegram.TelegramAuthController
import com.music.bitchord.data.webdav.WebDavCoilAuth
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.LastPlayed
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.download.Downloads
import kotlin.concurrent.thread

class BitChordApplication : Application(), SingletonImageLoader.Factory {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // PlaybackService shares this process, so seeding the cookie here means
        // stream resolution is authenticated from the first play onwards.
        authStore = AuthStore(this)
        // Opened off the main thread, alongside everything below: none of these
        // reads a setting or the session, and between them they are the slowest
        // opens at startup — SourceRegistry's encrypted store most of all.
        // Started only once [AuthStore] exists, because both encrypted stores
        // share one keystore master key and a first launch must not have two
        // threads racing to create it. Joined before onCreate returns, so
        // nothing that runs after startup can see any of them half open.
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
            // Its own directory: canvas clips are looping video, not audio, and
            // belong in a cache AudioCache's own limit and eviction policy were
            // never sized for. See CanvasCache's doc for why this one exists at
            // all — it is the fix for canvas clips re-fetching the same few
            // seconds of video from the network on every loop.
            CanvasCache.init(this)
        }
        // Migration-safe: an old single cookie becomes the first encrypted
        // session, while newer installs restore the profile the listener chose.
        val restoredSession = authStore.activeSession
        if (restoredSession != null && authStore.activeAccountId == null) {
            authStore.select(restoredSession.accountId, restoredSession.activeProfileId)
        }
        authStore.cookie = restoredSession?.cookie
        AppSettings.init(this, authStore)
        SearchHistory.init(this)
        LastPlayed.init(this)
        // Which tracks the listener has reverted to YouTube's own upload. Read
        // by [Song.toMediaItem], so it has to be open before the restart
        // snapshot below is turned back into queue items.
        OriginalVersion.init(this)
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
        // The offscreen WebView that mints a Spotify access token from the
        // listener's own session cookie needs a Context, and nothing in the
        // suspend call chain that reaches it (a track's canvas lookup) has
        // one to hand — see SpotifyToken's doc for why.
        SpotifyToken.init(this)
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
            // Covers on the WebDAV server need the credential or every one
            // of them 401s — which reads as "this track has no artwork".
            // Coil's own transport never sees Http.client's interceptor, so
            // the header is attached per request instead. See WebDavCoilAuth.
            .components {
                add(WebDavCoilAuth())
                // Covers filed on the SMB share; anything else falls
                // through to Coil's own fetchers. See SmbCoverFetcher.
                add(SmbCoverFetcher.Factory())
            }
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

    companion object {
        lateinit var authStore: AuthStore
            private set
    }
}
