# PATCH 2 Report — Round 2 bug fixes

Gate: `./gradlew :app:assembleProdDebug` (see bottom for result).
Commits: `c0f4f93` (bug 1), `1f78583` (bug 2).

## Bug 1 — `file://`/`content://` into OkHttp → `Malformed URL`

**Where it was fixed:** `playback/PlaybackService.kt`

1. **The route (line ~1492):** `resolvingFactory` used to wrap only
   `ChunkedDataSource(OkHttp…)` — so *every* URI the resolver substituted
   (`file:///…` from Telegram tier 1/tier 3, `content://`, `smb://`, `http://`)
   was handed to OkHttp, which speaks http(s) only and threw `Malformed URL`.
   The inner factory is now

   `SmbDataSource.RoutingFactory(DefaultDataSource.Factory(this, chunkedFactory))`

   — resolved `smb://` rides the existing SMB path, resolved `file`/`content`/
   `asset` resolve locally through `DefaultDataSource`, everything else falls
   through to the bounded-range OkHttp fetch. The scheme decision now happens
   *after* the substitution; the outer chain (cache → Smb routing → outer
   `DefaultDataSource`) is unchanged and still only ever sees `bitchord://`.

2. **Resolve logging (same block, `.also { resolved -> … }`):** every
   resolution now writes a `TrackLog.d` line
   `<mediaId> via <scheme> ("<title>")` — one lookup answers "which scheme did
   this track end up on".

3. **No YouTube dead-end for `tg_` (line ~3194,
   `fallbackFailedAlternativeToYouTube`):** a track whose `t=` param starts
   with `tg_` no longer converts to a YouTube title-search fallback (that is
   the `No YouTube audio fallback matched <title>` loop). It keeps the
   Telegram source and falls through to the ordinary retry/verdict path.

**Not touched:** `QualityUpgrade` itself — with the first open succeeding it
never reaches the "broke on its upgrade" state for these tracks; the guard
above covers the fallback leg.

## Bug 2 — auto-scan races dialog loading

**Where it was fixed:**

1. `data/telegram/LibraryScanner.kt` — `fullScan()` now calls
   `awaitCandidateChats()` (new, same file): retries `GetChats` every **8s**
   while it answers **0**, up to **120s**; logs each wait (`TrackLog.d`) and
   the final give-up (`TrackLog.w`). If still empty after the deadline it sets
   `ScanProgress.lastError` instead of finishing silently. The `catch` in
   `fullScan` now also writes `TrackLog.w` (it previously only stuffed
   `lastError`).
2. `data/telegram/TelegramAuthController.kt` — `onReady` and `startScan` log
   start (`TrackLog.d`) and wrap their `runCatching` with `TrackLog.w` on
   failure, so a dead auto-scan can't repeat unnoticed.
3. `ui/screens/ChannelsScreen.kt` + `ui/screens/SongsScreen.kt` — when the
   scan is not running and `lastError != null`, both tabs show the new line
   `R.string.tg_scan_failed` («اتصال تلگرام برقرار نشد — چت‌ها بارگذاری
   نشدند؛ دوباره تلاش کن.») instead of implying "no music".
4. `res/values/strings.xml` — added `tg_scan_failed`.

**Acceptance mapping:** fresh install + login → Ready → auto-scan now waits
for dialogs (≤2 min) before scanning, so the list fills without touching
«شروع اسکن», inside the 3-minute budget; retries are visible as
`TelegramScan: candidate chats not loaded yet…` lines in TrackLog.

## Build

`JAVA_HOME=/usr/lib/jvm/java-21-temurin ./gradlew :app:assembleProdDebug` —
**GREEN** (`BUILD SUCCESSFUL in 26s`, 45 tasks; `:app:compileProdDebugKotlin`
gates run green before each commit).
