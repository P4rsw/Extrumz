# PATCH 3 Report — Round 3 fixes

Gate: `JAVA_HOME=/usr/lib/jvm/java-21-temurin ./gradlew :app:assembleProdDebug` —
**GREEN** (`BUILD SUCCESSFUL in 12s`, 45 tasks; `:app:compileProdDebugKotlin`
gates green before each commit). `local.properties` untouched.

Commits: `d9b3a18` (bug 1), `ef6f3b3` (bug 2), `1d64964` (bug 3), `436fb29` (cleanups).

## Bug 1 — first-login auto-scan never ran (critical)

**Fixed in `data/telegram/TelegramSessionStarter.kt`.**
`awaitReadyThen` (was lines 58–63) dropped its `withTimeoutOrNull(60_000)`:
it now suspends on `client.authState.first { it is Ready }` with no budget —
cancellation of the owning scope is what ends the wait (doc comment says so).
All three callers (`start()`'s Initializing branch, the post-`client.start`
path, `provideAndStart`) go through it, so a login that takes 90s still fires
`onReady` → `fullScan`. Also: `startAsync()`'s and `provideAndStart`'s
`runCatching` now log via `TrackLog.w(TAG, …)` (new `TelegramSession` tag), and
in `data/telegram/TelegramAuthController.kt` the remaining silent
`runCatching`s — `ensureStarted`'s `start()` launch and the deep-link
`confirmLoginLink` — gained `.onFailure { TrackLog.w }` too.

**Scenario simulated (code-path walkthrough, no device in this environment):**
`pm clear` → launch → `ExtrumzApplication` calls `ensureStarted()` →
`start()` → `client.start(credentials)` → authState walks
`Initializing → WaitingForPhoneNumber (t≈0s) → …user types number/code/2FA…
→ Ready (t≈90s)`. Old code: `withTimeoutOrNull` returned null at t=60s,
`block()` never ran, `onReady`/`fullScan` dead → empty list (matches the
field report). New code: `first{}` suspends 0→90s, returns at Ready,
`block()` runs → `fullScan` → `awaitCandidateChats` (R2 retry) → banner + list,
no button pressed. Failure leg: any exception in `start()` is now logged by
both layers instead of vanishing into `runCatching`.
**Device acceptance to re-run:** `pm clear` + deliberately slow login (>60s),
hands off — banner appears, list fills.

## Bug 2 — transient `Not Found` on tier-3 download

**Fixed in `data/telegram/MusicTelegramClient.kt` (new
`getAudioByMessage(chatId, messageId): RemoteAudio?`), its Android
implementation (`TdApi.GetMessage` → `MessageAudio` → existing
`toRemoteAudio` mapper, in `AndroidMusicTelegramClient.kt`), and
`data/sources/TelegramSource.kt` `stream()` (tier 3).**
On any `getStreamPath` failure the source now: logs the error + old fileId →
fetches the message fresh → takes the new `remoteFileId` → logs
`fileId old -> new` → retries `getStreamPath` once → persists the fresh id
with `trackDao.update(track.copy(fileId = freshId))` so the next play starts
from the good id. A second failure logs and returns null (previous behaviour).
On whether `file.id` is stable in this TDLib build: **not verified against a
live server here** — the refresh path makes the question moot for playback
(the message always yields the currently-valid id), which is what the prompt
asked to prioritise.

**Scenario simulated:** read-ahead/quick-skip fires `stream(tg_x_y)` while
TDLib still holds the file reference → `GetFile(id)` throws `Not Found` →
`getAudioByMessage` returns the same message with a (possibly) new
`audio.id` → retry succeeds → path returned → play proceeds; TrackLog shows
both ids for the next diagnosis. Persisted id means the dance happens at most
once per track.

## Bug 3 — Telegram files cached twice on disk

**Fixed in `playback/AudioCache.kt`, `playbackFactory().open()`** (was lines
447–455): the bypass now triggers for `file`/`content` **and** for
`isTelegramTrack(uri)` — `authority == "source" && t param startsWith "tg_"` —
so a Telegram queue item goes straight to `upstreamDs` (resolver → local
file) instead of `cacheDs`, and `SimpleCache` never gets the second copy.
Read-ahead was already excluded (`prefetchQueue` drops source-backed keys via
`SourceRegistry.parseTrackKey`), so this closes the last doubling path.

**Scenario simulated:** queue item `extrumz://source?…&t=tg_6773656102_…` →
`AudioCache.playbackFactory.open()` → `isTelegramTrack == true` →
`upstreamDs.open()` → resolving chain serves `file:///data/user/0/…/file.mp3`
from the TDLib dir → zero bytes written to `SimpleCache` (disk usage per
Telegram track: 1× instead of 2×). **Offline/airplane replay:** replay hits
the same `extrumz://` item → resolver runs again → `TelegramSource.stream()`
→ tier 1 (`/Telegram/` file) or tier 3 (`getStreamPath` returns the already
downloaded local path — no network needed) → plays. The old safety net (cache
serving bytes without resolving) is not needed because the "cache" *is* the
TDLib file itself, which never leaves the disk.
**Device acceptance to re-run:** play a tier-3 track, compare free disk /
`du` of `SimpleCache` vs TDLib dir before/after; airplane mode → replay.

## Cleanups (commit `436fb29`)

1. `LibraryScanner.fullScan` catch: `if (e is CancellationException) throw e`
   before recording `lastError`.
2. Silent `runCatching`s logged — see bug 1 list above (starter + controller).
3. `PlaybackService.kt`: both `DefaultDataSource.Factory(this, …)` →
   `applicationContext` (lines ~1500, ~1528).
4. `res/values/strings.xml`: all 20 `Extrumz` values rebranded to `Extrumz`
   **except one**: `saved_to_extrumz_folder` still says
   «Saved to Music/Extrumz», because the real on-disk folder is still
   `Music/Extrumz` (`DownloadStore.FOLDER`) — renaming the string would lie,
   renaming the folder would orphan existing downloads. XML comment added at
   the string. (Locale files untouched, as specified: only base `values/`.)
5. `LibraryScanner.incrementalScan` **removed** (grep: zero callers; its
   cursor lives on in `updateScanProgress` for the future watcher).
   `scanChat` now writes `trackCount = trackDao.countForChat(chatId)` — new
   `TrackDao.countForChat` query — so a chat's count is cumulative across
   rescans, not what one pass saw.
6. (Optional item done) `ScanProgress.attempts` is published during the 120s
   dialog wait; both banners append «تلاش N» (`tg_scan_attempt`).

## Verified in the APK

`aapt2 dump`: `string/tg_scan_attempt` + `string/tg_scan_failed` present;
«Extrumz listener» (rebrand) present; «Saved to Music/Extrumz» intact.

## Not verifiable here

No attached device: the three acceptance scenarios above are code-path
simulations; please re-run them on hardware (steps listed per bug).
