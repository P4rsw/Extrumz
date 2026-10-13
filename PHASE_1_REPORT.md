# Phase 1 Report — Telegram as a first-class source

Branch `phase1-telegram-source` · gate: `./gradlew assembleDebug` **GREEN** (4m 8s, 83 tasks)

## What works

| Spec item | Status | Where |
|---|---|---|
| TDLib + Room + KSP dependencies | done | root/app `build.gradle.kts` — `com.github.tdlibx:td:1.8.56`, Room 2.8.5, `sqlite-bundled` 2.7.1 (FTS5 below API 30), KSP 2.3.11 |
| Room schema from old project (v3, FTS5) | done | `data/db/` — entities, `ExtrumzDatabase`, `Fts5Callbacks`, migrations, `TelegramDb` provider (keeps DB name `extrumz.db` so existing installs upgrade cleanly) |
| TDLib client + session + proxy | done | `data/telegram/` — `AndroidMusicTelegramClient`, `TelegramSessionStarter`, `Telegram` holder; socks5 proxy auto-configured from `local.properties` (`PROXY_HOST/PORT`); session persisted under `context.filesDir/extrumz` (spec) |
| Auth: QR + phone/code/password + `tg://login` deep link | done | `TelegramAuthController`, `TelegramAuthDialog` (Persian, states ported from old `LoginScreen` minus the api_id/api_hash fields), `TelegramLoginLink`, manifest intent-filter `tg://login` |
| Scanner (`TelegramLibraryScanner` equivalent) | done | `LibraryScanner` — chat enumeration → audio/document messages → Room upsert, FTS5, progress flow; **auto-scan fires the moment authorization becomes Ready** (also on cold start via `ExtrumzApplication`) |
| UI: 2 Persian tabs | done | `SongsScreen` (search + offline dot + scan banner «دارم آهنگ‌هاتو جمع می‌کنم…» + empty hint), `ChannelsScreen` (chats list → tap filters the songs tab) |
| `TelegramSource : MusicSource` | done | FTS5 → LIKE → TDLib network search; `stream()` Tier 1 MediaStore `/Telegram/` else Tier 3 `file://`; ids `tg_<chatId>_<messageId>`; `health()` = Rejected until authorized; `SourceKind.TELEGRAM` rank 1, **default enabled** (`enabled = kind != JIOSAAVN`) |
| Auth entry on SourcesScreen | done | authorize button «ورود به تلگرام» directly on the Telegram source row (also the empty-state button on both tabs) |

Extra beyond spec: SOCKS5 proxy support for TDLib, deep-link confirm (best-effort),
manual «شروع اسکن» re-scan button, connection dot in the songs header.

## How it was tested

- `./gradlew help`, `:app:dependencies`, then per-feature `:app:compileProdDebugKotlin`
  gates (proxy + `sdkDownload=false` template), final `./gradlew assembleDebug` green.
- APK artifacts verified with aapt2: label **Extrumz** (prod) / Extrumz Dev (dev),
  package `com.extrumz.app`, 8 APKs (dev+prod × arm64/armeabi/x86_64/universal).
- Shipped manifest verified in the APK: `scheme=tg host=login` filter present.
- `lib/arm64-v8a/libtdjni.so` confirmed inside the APK.
- TDLib 1.8.56 signatures verified against the real AAR (`javap`) before porting —
  the old project's `SearchMessages` call (9 args) does not exist in this version; fixed to 8.

Commits: `7036729` deps → `5998156` Room schema → `70790e0` TDLib client →
`7afe893` TelegramSource → `45da91d` UI/auth/deep link.

## Known gaps (honest list)

1. **No device test.** No adb-attached device in this environment, so the acceptance
   journey (install → login → auto-scan → play → airplane mode → filter) has not been
   run end-to-end. All evidence above is build/packaging-level.
2. **Deep link is best-effort.** TDLib's `ConfirmQrCodeAuthentication` is only valid
   after its own `CheckAuthenticationCode`-style parameters execute; the controller
   holds the token until a waiting state arrives and then confirms. Phone+code remains
   the primary path; failures surface in the dialog, not silently.
3. **No automatic incremental sync yet.** `LibraryScanner.incrementalScan(chatId)`
   exists but no new-message watcher triggers it; new audio lands via manual re-scan
   («شروع اسکن») or re-login auto-scan. Phase 2 candidate.
4. **Persian strings live in default `values/strings.xml`** (no `values-fa`), so every
   locale shows the Persian labels for the new keys — intended for Phase 1 (app is
   Persian-first); proper `values-fa` default + RTL layout-direction pass is Phase 2.
   Layout itself is still LTR-ordered (rows/icons), text direction follows the strings.
5. **Old screens are intact but unreachable** from the bottom bar (Home/Explore/
   Library/Search branches kept in `MainActivity`, only the two Telegram tabs are
   offered). Deep links and queue types still route into them.
6. **Live-Telegram behavior unverified**: rate limits during a large scan, proxy
   reachability, `Rejected` health probes — none exercised against a real account here.
7. `Icons.Rounded.Chat` deprecation warning (AutoMirrored) — cosmetic, kept for now.

## Environment note (resolved)

`maven.aliyun.com` serves HTTP 502 for all `com.google.devtools.ksp` plugin-marker
paths; the mirror init-script's buildscript hook was removed so canonical repos are
tried first (central resolves the marker). Documented inline in
`~/.gradle/init.d/zzz-google-mirror.gradle`.
