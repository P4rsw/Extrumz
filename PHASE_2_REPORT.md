# Phase 2 Report — Extrumz goes Telegram-only

Branch: `phase1-telegram-source` · Base: `daeb914` (PATCH_3 / R3) · Result: `HEAD`
Scope: `OPENCODE_PROMPT_PHASE2.md` — strip every external-platform integration while keeping Telegram playback (online + offline), auto-scan, FTS5 search, Telegram login and the Persian two-tab UI green.

---

## 1. Headline numbers

| Metric | Value |
|---|---|
| Phase-2 commits | 16 (each with a green build) |
| Files changed | 219 (+8,639 / −50,046, net −41,407 lines) |
| Files deleted | 126 (124 of them Kotlin) |
| Dependencies removed | 15 (14 `implementation` + 1 `testImplementation`) |
| Strings removed (base) | 387 dead keys, removed from all 16 locale files |
| Strings added | 25 Telegram tab/login keys × 15 locales = 375 translations |
| Final `assembleProdDebug` | BUILD SUCCESSFUL |
| Unit tests (46 classes) | 0 failures |

### APK size (prod debug, `app/build/outputs/apk/prod/debug/`)

| ABI | Before (R3, Sep 30) | After (Phase 2) | Δ |
|---|---|---|---|
| arm64-v8a | 108,614,569 B (103.6 MiB) | 105,215,006 B (100.3 MiB) | −3.40 MB (−3.1%) |
| armeabi-v7a | 93,546,287 B | 90,146,722 B | −3.40 MB (−3.6%) |
| universal | 270,325,101 B | 266,925,326 B | −3.40 MB |
| x86_64 | 117,149,540 B | 113,749,980 B | −3.40 MB |

(Debug builds are unminified; release shrinker savings will differ.)

---

## 2. What was deleted (by commit)

### Stage A — safe disable (before any deletion)
| Commit | Change |
|---|---|
| `e9be06a` | `ExtrumzApplication`: removed `InnerTubeXResolver.init()` and `Innertube.ensureSessionScope()/cookie/selectChannel` (no more YouTube network at startup, no `PoTokenGenerator` in logs). |
| `54bc055` | `SourceRegistry`: no `SourceKind.YOUTUBE` seed on first run — registry knows `TelegramSource` only. |
| `7dabe2f` | Search pipeline: no fan-out to non-Telegram sources — `MainViewModel.runSearch` = Telegram FTS5 + on-device music only (incl. typeahead). |
| `e5443f3` | Playback fallback: deleted `playback/PlaybackFallback.kt` and the YouTube dimension of `QualityUpgrade` — a Telegram track is one resolve, failure = clean error. |

### Stage B — removal
| Commit | Change |
|---|---|
| `0969b98` **B1 Listen Together** | 16 files (`data/listentogether/**`, `PartySync`, `PartyPersonalQueueStash`, `ListenTogetherScreen/Sheets`, `PartyServerEditor`, `ListenTogetherMembersSheet`, party tests) + party parameters (`controlsLocked`/`playedBy`/`onListenTogether`/`inParty`) stripped from PlayerControls/NowPlaying/Queue/MiniPlayer/GlassNavBar/Drawer/PlayerConnection/QueueCoordinator/Crossfade/TrackAnalyzer/Autoplay + manifest invite/`extrumz://party` filters + `LISTEN_TOGETHER_SERVER` buildConfig field. |
| `951756e` **B2 Discord** | 26 files (`data/discord/**`, entire `com/my/kizzy/**` (19), `DiscordScreen` incl. dialogs, `DiscordLoginScreen`, `DiscordAudioQualityTest`, `ic_discord`) + MainActivity/PlaybackService (~281 lines)/settings surgery; dep `io.ktor:ktor-client-websockets` removed. |
| `67fbfba` **B3 scrobbling/Last.fm** | `data/scrobbling/**`, `AccountAndScrobblingScreen`, `PrimaryArtistTest` deleted; PlaybackService scrobbler + Last.fm bridge removed (listening history/`ListeningRecorder` kept); `ArtistFacts.fetchGenres` + `replayGenres` setting removed; `LASTFM_API_KEY/SECRET` gradle fields and localProps reads removed. |
| `d9fbb31` **B4 WebDAV/SMB** | 18 files (`data/smb/**`, `data/webdav/**`, `data/remote/**`, `SmbDataSource`, `RemoteArtwork.kt`, 3 tests); `rememberRemoteArtworkUrl(x)` → `x?.thumbnailUrl` across 7 UI files; PlaybackService `SmbDataSource.RoutingFactory` unwound (**file/content routing kept**); `ServerEditorAlert` renamed `EditorAlert`; dep `com.hierynomus:smbj` removed. |
| `0fee961` **B5 addon/module/JioSaavn/Sources** | 22 files (`data/jiosaavn`, `data/sources/addon/**`, `data/sources/module/**` incl. `ModuleIndex`/`SpineModule`, `AddonSource`/`ModuleSource`/`JioSaavnSource`, `SourcesScreen`, 3 dead `data/remote` files, 5 tests); `SourceKind` reduced, `BUILT_IN_KINDS = [TELEGRAM]`; Settings "Sources" row, `AddonEditorAlert` removed; test dep `mockwebserver` removed. |
| `049c7a6` **B6 updater** | `AppUpdateChecker.kt` + `UpdateAvailableDialog.kt` deleted (shared alert consts extracted to new `ui/components/AlertPrimitives.kt`); MainActivity update banner/dialog/BackHandler + MainViewModel `updateAvailable` removed; deps `richtext-commonmark`/`richtext-ui-material3` removed; `REQUEST_INSTALL_PACKAGES` manifest permission and `file_paths.xml` updates path removed (FileProvider kept for ReplayShareSheet). |
| `00dfece` **B7a YouTube UI** | `HomeScreen.kt`, `ExploreScreen.kt`, `playback/MusicLink.kt` deleted; MainViewModel YouTube surface (home/moodGenre/YT library/playlists/accounts/web session) removed; DetailScreen YT sections (suggested/subscribers/editorial/save/subscribe) removed; LibraryScreen restructured to Replay + on-device cards; SearchScreen playlist params stripped; local shelf renderers moved to new `ui/screens/Shelves.kt`. |
| `11a17b4` **B7b YouTube call sites** | YouTube references stripped from playback/download/stats/HTTP/auth; `auth/WebSession.kt` + `auth/YtMusicLoginScreen.kt` deleted; AuthStore YT members removed; `YouTubeLyrics` provider wiring removed from `LyricsSource`/`LyricsRepository` (LRCLIB/Musixmatch kept); telegram download path + `authority == "source"` verified intact. |
| `4e858dc` **B7c innertube stack** | 15 files: `data/innertube/**` (6 incl. `InnerTubeXResolver`, `StreamResolver`, `PlayerClient`, `PlaybackTracker`) + `data/innertube/potoken/**` (5), `data/YtMusicRepository.kt`, `data/sources/YouTubeSource.kt`, `data/lyrics/YouTubeLyrics.kt`, NewPipe `Utils.java`; `SourceKind` = TELEGRAM only; `SourceResolver` YouTube helpers (`substituteForYouTube`, `youtubeFallback`, `beatsYouTubeAac`, …) removed; manifest YouTube intent filters (`music.youtube.com`, `youtu.be`) and proguard keeps removed; deps `innertubex`, stripped NewPipeExtractor jar task, `nanojson`, `rhino`+`rhino-engine`, `quickjs-kt`, ktor (`core`/`okhttp`/`content-negotiation`/`serialization`) removed. |
| `106ef4a` **F1 leftovers** | 6 files deleted: `playback/OriginalVersion.kt` (revert-to-YouTube-original), `auth/AuthStore.kt` (Google cookie sessions only), `auth/AccountSessions.kt` (`YouTubeProfile`s), `ui/components/AccountChannelDialog.kt` (no callers), 2 tests; +20 files: revert rows/notification button/session command removed, `SignInBanner` (only `sign_in_youtube_music` user) removed, YT charts shelf branch removed, `youtubeDownloadBadge` → `formatBadge`, `isCanonicalYouTubeRendition` → `isCanonicalRendition`, Google account state removed from MainViewModel, About text reworded. |
| `f41bc2c` **F2 strings** | 387 dead keys removed from `values/strings.xml` + all 15 locales (verified zero code refs across main/test/androidTest/resources/manifest); orphan locale keys cleaned; 25 Telegram login/tab keys translated into all 15 locales (fixes `LocaleStringsTest`); `GeniusTest` live-scraping test now `Assume`-skips when the internet is unreachable. |
| `09ac5ce` **F3 wording/branding** | `auto_login_title` → "Sign in to Telegram…" (16 files), `search_empty` → Telegram/on-device wording (16 files), `Extrumz` → `Extrumz` in every user-visible string of all 15 locales (except the truthful `Music/Extrumz` folder path), stale `<!-- Listen together -->` section comments removed, Settings colophon now `Extrumz <version>` / `~Telegram Music Backend`, YouTube test-fixture names neutralized. |

### Dependencies removed (15)

`innertubex-android`, NewPipeExtractor (stripped jar task), `nanojson`, `quickjs-kt-android`, `rhino`, `rhino-engine`, `ktor-client-core`, `ktor-client-okhttp`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-client-websockets`, `smbj`, `richtext-commonmark`, `richtext-ui-material3`, `mockwebserver` (test).

---

## 3. What was kept (and the ambiguous calls)

**Kept verbatim (golden rules):**
- `data/telegram/**` — **0 files changed in Phase 2** (TDLib, proxy, scanner, Room/FTS5 byte-identical).
- Telegram playback path: `PlaybackService` `authority == "source"` branches (lines 859, 3893), `SourceResolver.resolve(dataSpec.uri)` (line 861), `SourceRegistry.build()` → `TelegramSource(config)`, file/content routing for telegram tracks, `AudioCache` telegram bypass.
- Core playback: equalizer stack, `CrossfadeController`, `SleepTimer`, `Autoplay`, all `Queue*`, `LastPlayed`, `AudioOutput*`/`AudioRouting`, local DSP.
- UI: two tabs (آهنگ‌ها/کانال‌ها), player, `DetailScreen`, `LibraryScreen` (restructured), `HistoryScreen`, `LocalMusicScreen`, `DownloadManagerSheet` + `DownloadService`, widgets, slimmed `SettingsScreen`.
- Lyrics: `data/lyrics/**` minus `YouTubeLyrics` (LRCLIB, Musixmatch, BetterLyrics, SimpMusic, KuGou, embedded tags all kept).
- Telegram login (deeplink + QR + 2FA) and public-channel search.
- `NerdStats` / `DebugLog` (local diagnostics).

**Ambiguous items, decided:**
- `MusicLink.kt` — inspected: a **YouTube URL parser** (not Telegram) → deleted in B7a (prompt asked to check).
- `AccountAndScrobblingScreen` — inspected: YouTube account switcher + scrobble toggles only; Telegram account management lives in `TelegramLoginScreen` (reachable from Songs/Channels tabs) → screen deleted in B3, Telegram auth untouched.
- `AuthStore` — held Google cookie sessions only (`sessions/activeSession/channel*`), no Telegram members; `signedIn` now derives from `Telegram.client.authState is TelegramAuthState.Ready` → deleted in F1.
- `DeviceCodecs` — kept (used by `SourceResolver`, `AudioCache`, `SettingsSheet`).
- `FileProvider` — kept (ReplayShareSheet export).
- `saved_to_extrumz_folder` — kept: `DownloadStore.FOLDER = "Extrumz"`, so "Saved to Music/Extrumz" is factually true on disk.
- `SourceResolver` rank/race machinery — kept: still meaningful for multiple Telegram configs (rank decides who is asked; `worthSwapping`/`upgradeFor`/`rankedAbove` are source-agnostic).
- Bare `watch?v=…` URIs — kept a **clean failure path** (`UnservableTrackException`) instead of a timeout, since no source is keyed by a video id anymore.
- Log tags `TAG = "Extrumz"` and `Theme.Extrumz` — kept (internal identifiers, not user-visible; package is `com.extrumz.app`).

---

## 4. Verification & acceptance simulation

No adb device is attached to this machine, so **on-device acceptance could not be executed here**; the following was done instead:

1. **Green build between every step** — each of the 16 commits was built (`compileProdDebugKotlin` + `compileProdDebugUnitTestKotlin`) and only committed after `BUILD SUCCESSFUL`. Final gate `:app:assembleProdDebug` green (Oct 1, `.sdks/p2-final-assemble.log`).
2. **Full unit suite** — `:app:testProdDebugUnitTest`: 46 test classes, **0 failures**.
   - `LocaleStringsTest` ×15 locales green after the 375 added translations.
   - `GeniusTest` (live network scraping) skips via `Assume` when offline instead of failing.
3. **Dead-reference greps (post-B7c, post-F1, post-F3):**
   - `innertube|jiosaavn|smb|webdav|discord|party|scrobbl|youtube` (case-insensitive, comments excluded) over `app/src/**/*.kt` → **0**.
   - Same pattern over `AndroidManifest.xml`, `app/build.gradle.kts`, `proguard-rules.pro` → **0**.
   - Same pattern over all 16 `strings.xml` → **0**.
   - `local.properties` diff → **0** in every commit.
4. **Acceptance mapping (how prior-phase acceptance was simulated):**
   - **Online playback**: cannot stream without hardware. Simulated by `SourcesTest` (resolve/`worthSwapping`/matching), `StreamChoiceTest`, `TrackIdentityMismatchTest`, compile-time verification of the `authority=="source"` → `SourceResolver.resolve` wiring, and the clean-failure test path for unserviceable URIs.
   - **Offline playback**: `DownloadStoreTest` (16 cases incl. format badges), `DownloadSessionTest`, `OfflineDashTest` green — the `file://`/`content://` routing Telegram relies on was never touched (`git diff --stat` on the routing paths shows only the SMB branch removal).
   - **Auto-scan**: `data/telegram/**` has **0 changed files** across Phase 2 — scanner, TDLib session and Room/FTS5 indexing are byte-identical to R3.
   - **Search**: `runSearch` restricted to `TelegramSource.search` (FTS5) + local music (commit `7dabe2f`); `LocalizationTest`/`LocaleStringsTest` green; UI wiring compiled and inspected.
   - **Login/QR**: `TelegramLoginScreen` entry points (Songs tab, Channels tab, deeplink) verified present; `auto_login_*` media-browser prompt retitled to Telegram.
   - **Device acceptance (explicitly NOT done here)**: must still be run on hardware — online stream start, airplane-mode offline play, fresh-install auto-scan, FTS5 search latency, QR login.

---

## 5. Behaviour changes to be aware of

- **Autoplay radio** has no suggestions for a Telegram track (the YouTube seed source is gone); `Autoplay` itself is kept for queue continuation.
- **Automix analysis** is disabled for `source`/telegram tracks (`analysisUriFor` → null); `isCanonicalRendition` kept as a neutral cache-key check.
- **Artist facts card** fetch (`ArtistFacts.fetchCard`/`noticed`) removed — it was a YouTube search.
- **Library tab**: Replay + on-device cards only (YT playlists/library/subscriptions gone); Home/Explore screens gone (two-tab Songs/Channels structure unchanged).
- **Quality UI**: "revert to original"/version-switch rows removed; the upgrade row simplified; `youtubeDownloadBadge` renamed `formatBadge` (labels unchanged, still "OPUS · 160 kbps" style).
- **Stats-for-nerds labels**: unnamed streams now report `Direct` instead of `YouTube`; the legacy 11-char video-id label branch was removed.
- **Settings**: Sources / Discord / scrobbling / updater / Listen Together rows removed; About colophon reads `Extrumz <version>` + `~Telegram Music Backend`.
- **Launcher name** is now `Extrumz` in every locale (15 locales previously showed `Extrumz`).
- `saved_to_extrumz_folder` intentionally still says **"Saved to Music/Extrumz"** — that is the real on-disk folder (`DownloadStore.FOLDER = "Extrumz"`).

---

## 6. Logs

Build/test logs for this phase: `/run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/.sdks/p2-*.log`
(p2-b1…p2-b7c-verify, p2-f1/f2/f3-tests, p2-final-assemble).
