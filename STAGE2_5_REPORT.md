# Stage 2.5 — device-proven fixes (STAGE2_5_REPORT.md)

Status: **complete — STOP.** Spec: `../OPENCODE_PROMPT_STAGE2_5.md`. Three small fixes
root-caused with device evidence during the stage-2 A/B run — nothing else touched: no DB
schema/migration, no scan-speed/stop logic changes (`stopBefore`, `Semaphore(6)`,
2-empty-page stop all as they were), no `values-fa` / metadata / download / logo /
`MAX_TOP_CHATS` edits.

## Commit

| Commit | Files | Diff |
|--------|-------|------|
| `4bbb8c1 fix(stage2.5): scan button insets, real error detail, getChatInfo logging` | 5 | +149/−14 |

## What / where / why

### 1. 🔴 Scan button unreachable under the status bar
- **Evidence:** button bounds `[447,39][634,96]` vs system status bar `[0,0][1080,127]` —
  the tap landed on the status bar, so the manual scan never started (a real-user bug, not
  a test artifact).
- **File:** `ui/screens/ChannelsScreen.kt`.
- **Fix:** the screen's topmost layer — the root `Column` — now carries
  `Modifier.windowInsetsPadding(WindowInsets.statusBars)` (`androidx.compose.foundation.layout`).
  The whole column shifts down uniformly, so the centred empty state keeps exactly the size
  and centring it had; no size modifiers changed. `displayCutout` was not needed separately:
  the status-bar inset already covers the cutout region the bar paints over (will be
  re-checked on device).
- **Device acceptance (for the owner's run):** button `y_top ≥ 127` and a tap actually
  starts/stops the scan.

### 2. 🔴 Every failure showed «اتصال تلگرام برقرار نشد»
- **Evidence:** `SQLException: database disk image is malformed` reached only logcat; the UI
  showed the generic `tg_scan_failed` title with no cause.
- **Files:**
  - `data/telegram/LibraryScanner.kt` — `ScanProgress` gains
    `lastErrorDetail: String?` (the raw `e.message`, fallback = class name). Every catch
    that paints `lastError` now also stores the detail **and** logs the full throwable with
    the existing tag via `TrackLog.w(TAG, message, error)` (stack included): the `fullScan`
    catch, the per-chat catch in `scanOneChat`, and — for symmetry — the
    "no candidate chats" branch carries the same line as its own detail. Existing
    `ScanProgress` fields untouched; a new scan resets both (state, not DB — no schema
    change involved).
  - `ui/screens/ChannelsScreen.kt` — under the unchanged, still-Farsi `tg_scan_failed`
    title, one small muted line (`onSurfaceVariant`, `bodySmall`) renders
    `displayErrorDetail(scan.lastErrorDetail)`; `null`/blank → line skipped.
  - New guard `internal fun displayErrorDetail(detail, max = 120): String?` — trims,
    hides blanks, cuts anything longer than 120 chars with `…`. Nothing can crash the
    banner, however long the message is.
  - `SongsScreen`'s banner was **not** touched: the spec's file list covers
    `LibraryScanner` + `ChannelsScreen` (+ its ViewModel if needed — not needed, the
    scanner's own `ScanProgress` is the state), so the Songs tab keeps the title-only
    line. Noted as a possible follow-up if the reviewer wants parity there.
- **Acceptance:** with a corrupt DB or any synthetic failure, ChannelsScreen shows the
  real exception text under the title; `TrackLog`/`Log.e`-level record keeps the complete
  exception (the `w(tag, message, error)` overload writes the stack).

### 3. 🟠 `getChatInfo` dropped chats silently
- **Evidence:** `runCatching {}.getOrNull()` with no log — a failing id vanished from the
  scan candidates forever with no trace.
- **File:** `data/telegram/AndroidMusicTelegramClient.kt`.
- **Fix:**
  - inside `getChatInfo`: `.onFailure { TrackLog.w(TAG, "getChatInfo(chatId) failed: <type>: <msg>", e) }`
    — the real exception, with `chatId`, logged where the cause still is;
  - in `listTrackedCandidateChats`: one `TrackLog.w` per `null` result naming the dropped
    id (order-aligned with `slice.chatIds`).
  - File-level `private const val TAG = "TelegramClient"` (matches the `TelegramX`
    tag family). The drop behaviour itself is **unchanged** — a bad id still leaves the
    list, now with a warning.
- **Acceptance:** a broken id appears in the log as a warning with its exception.

## Build / test result

Command (as mandated, both assemble flavors):
`./gradlew :app:assembleProdDebug :app:assembleDevDebug :app:testDevDebugUnitTest :app:testProdDebugUnitTest --rerun`
(JDK `java-21-temurin`) → **BUILD SUCCESSFUL**.

| Metric | Result |
|--------|--------|
| Combined tests (both suites) | **1 138, 0 failures, 0 errors**, 2 skipped (pre-existing offline `GeniusTest`) |
| `LibraryScannerSpeedTest` | 10 tests (was 9): detail assertions on the failing-chat path + new candidate-list-failure test |
| `ScanErrorBannerTest` (new) | 4 tests: blank hidden, short shown trimmed, >120 cut with `…` (121 chars, keeps the message start), exactly-120 not cut |
| `local.properties` | untouched |

Unit tests for fix 3 were not written: it is pure logging (the drop behaviour must stay
identical, and `TrackLog` has no capturable sink in unit scope) — the spec made tests for
items 2/3 conditional («اگر … ممکن بود»).

## Device round (with the owner)

- Button bounds after the fix (`y_top ≥ 127`) and a real tap starting the scan.
- Force a failure (or the corrupt-DB case) → detail line visible under the title,
  truncation on a long message.
- Log check: `getChatInfo(<id>) failed:` / `candidate chat <id> dropped:` warnings.

**STOP — awaiting review / next stage.**
