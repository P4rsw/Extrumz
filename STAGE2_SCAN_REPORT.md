# Stage 2 — Telegram scan speed (STAGE2_SCAN_REPORT.md)

Status: **complete — STOP.** Spec: `../OPENCODE_PROMPT_STAGE2.md` (sub-steps س۱–س۶);
decisions: `../MASTER_LIST.md` §۴/§۶, decision 6 = «500 + LoadChats». Scope: scan speed
only — metadata (م۱–م۴), settings, Persianization and downloads untouched. **No DB schema
change, no migration, no new column.**

## Commits

| Sub-steps | Commit | Files | Diff |
|-----------|--------|-------|------|
| س۱ + س۲ — parallel scan + batched writes | `c61bbb2 feat(stage2): scan chats in parallel and batch DB writes per page` | 2 | +539/−29 |
| س۳ — resume boundary | `3619382 feat(stage2): resume scans from lastScannedMessageId` | 2 | +99/−11 |
| س۴ + س۵ + س۶ — retry/limit/preload | `5ea8756 feat(stage2): raise the chat cap to 500, preload chats, trim RPCs` | 3 | +20/−18 |

Each batch was gated on green `:app:assembleProdDebug` + `:app:testDevDebugUnitTest` +
`:app:testProdDebugUnitTest --rerun` before committing (JDK `java-21-temurin`, per spec).

## Per-file changes

### `data/telegram/LibraryScanner.kt`
- **س۱ `fullScan`**: `chats.forEach` → `coroutineScope { launch { semaphore.withPermit { scanOneChat(info) } } }`
  with `Semaphore(SCAN_PARALLEL_CHATS = 6)` (approved range 4–8). Launches happen in list
  order, so the newest-first dialog ranking claims the first permits. `totalChats` still set
  before the loop.
- **س۱ new `scanOneChat`**: reads `chatDao.getById` first (с۳), upserts with
  `lastScannedMessageId`/`trackCount` carried forward, then `scanChat(..., stopBefore)`.
  Per-chat `try/catch`: failure → `TrackLog.w` + `lastError` (high-level, `isRunning`
  untouched) and `chatsScanned + 1` in `finally` (failed chats count too); `CancellationException`
  is always rethrown so a cancelled scan is never painted as a failure.
- **All `ScanProgress` counters** now go through `_progress.update { it.copy(...) }`
  (CAS — no read-then-copy race under parallelism): totalChats, chatsScanned, tracksIndexed,
  attempts, lastError, isRunning; `indexAudio`'s counter as well.
- **س۲ new `batchIndex(pageAudios)`**: 1× `getByIds` → `MetadataMerger.merge` split into
  insert/update → `insertAll` (IGNORE absorbs two chats racing the same `fileUniqueId`) +
  `update` for existing rows. Counted only as inserts (see deviation 2). `scanChat` calls it
  per page instead of `indexAudio` per song.
- **`indexAudio` kept unchanged** (public) for its `TelegramSource.kt:85` caller — verified.
- **س۳ `scanChat(chatId, fromMessageId, full, stopBefore = 0L)`**: filters
  `messageId > stopBefore` before `batchIndex`; breaks on `page.nextFromMessageId in 1..stopBefore`
  in **both** the empty-page and the full-page branch; final
  `updateScanProgress(messageId = maxOf(stopBefore, maxMessageId), trackCount = countForChat)`.
  KDoc documents why a rescan *walks the same newest-first path and stops at the boundary*
  rather than starting at it (starting there would collect older messages — wrong direction).
- **س۴** `CHATS_RETRY_DELAY_MS` `8_000L` → `1_000L` (`CHATS_LOAD_TIMEOUT_MS` stays 120 000 ⇒
  120 attempts); `progress.attempts` still published per attempt.

### `data/telegram/AndroidMusicTelegramClient.kt`
- **س۵ `getChatInfo`**: the whole `GetSupergroup` branch removed → `username = null` with a
  comment (usernames only build t.me links, the field is write-only, the entity column stays —
  no schema change). One `GetChat` per supergroup instead of two round trips.
- **س۵ + س۶ `listTrackedCandidateChats`**: `runCatching { send(TdApi.LoadChats(ChatListMain, MAX_TOP_CHATS)) }`
  **before** `GetChats` (GetChats only sees the TDLib cache; the error TDLib returns when the
  whole list already fits is harmless), then
  `coroutineScope { slice.chatIds.map { async { getChatInfo(it) } }.awaitAll() }` —
  `awaitAll` preserves input order (newest-first ranking intact), `filterNotNull().take(MAX_TOP_CHATS)`.

### `data/telegram/TelegramConfig.kt`
- **س۶** `MAX_TOP_CHATS` `200` → `500` (decision 6).

### `app/src/test/java/com/extrumz/app/LibraryScannerSpeedTest.kt` (new)
Hand-written fakes (no mockk — not on the classpath): `FakeChatDao`, `FakeTrackDao`
(models INSERT IGNORE with `-1` row ids, and yields *after* each `getByIds` read to open a
deterministic read-before-write window), `FakeTelegramClient` (newest-first paging where the
cursor is the last id of the page, in-flight counter, scripted fail/hang chats). 9 tests,
run in both flavors.

## Logical before/after — sample: 500 chats, one 1 825-track channel

| Cost | Before | After |
|------|--------|-------|
| Dialog listing RPC | 1 GetChats + 500×(GetChat + GetSupergroup) = **1 001**, sequential | 1 LoadChats + 1 GetChats + 500×GetChat = **502** (−50 %), the 500 info calls **parallel** |
| Dialog-list retry | every 8 s (≤ 15 attempts / 120 s) | every 1 s (≤ 120 attempts / 120 s) |
| Channel scan RPC | 19 `SearchChatMessages` pages (1 825/100; +≤2 empty tail pages) | unchanged (page depth untouched) |
| Channel scan DB | 1 825×(getById+insert) = 3 650 + 3 = **3 653 queries** | 19 `getByIds` + 19 `insertAll` + 0 updates + 3 = **41 queries** (≈ **89×** fewer) |
| Chat concurrency | ×1 (strictly sequential) | **6** in parallel ⇒ chat-phase wall clock ≈ per-chat sum / 6 |
| Second full scan, unchanged channel | full depth again — REPLACE had zeroed the boundary (3 pages + 3 650 queries) | **1 page** per chat, boundary filter, cursor/count preserved (proven by test) |

`chats.size` after «500 + LoadChats» = min(real dialog count, 500): the actual number
depends on the account's cache state and can only be read off a live device run with the
owner (spec line 97 puts real timing/counting on the device session; unit side is green).

## Acceptance (spec lines 99–106)

- [x] **س۱..س۶ implemented and committed** — commit table above.
- [x] `grep -rn GetSupergroup app/src/main/java/com/extrumz/app/data/telegram` ⇒ **0**;
      `MAX_TOP_CHATS == 500`; `LoadChats` precedes `GetChats`; `CHATS_RETRY_DELAY_MS == 1_000L`
      — all greps run against the final tree.
- [x] **Resume proven by tests**: second run asks for fewer pages (3 → 1), nothing at/below
      the boundary is re-indexed (no update pass, only the 2 genuinely new messages inserted),
      and the upsert between scans provably carries `lastScannedMessageId = 250` /
      `trackCount = 250` (REPLACE can no longer zero them — the disappearance bug from the
      spec is gone).
- [x] **`batchIndex`**: ≤ 1 `getByIds` + 1 `insertAll` (+ updates for existing rows) per page —
      asserted exactly (2 pages → 2/2, per-song `getById`/`insert` = 0). `indexAudio` kept for
      `TelegramSource` (grep proof).
- [x] **Error policy**: one failing chat → others continue, `lastError` painted,
      `chatsScanned == totalChats`; `CancellationException` rethrown (cancellation leaves
      `lastError == null`, test-proven); counters race-free (`_progress.update` everywhere).
- [x] **Green gate**: `assembleProdDebug` + both suites — **1 128 tests, 0 failures, 0 errors,
      2 skipped** (1 110 existing + 9 new × 2 flavors; the 2 skips are the pre-existing offline
      `GeniusTest` skips). No existing test referenced `MAX_TOP_CHATS`, `fullScan` or scan
      order (grep: *none outside the new file*) — so none needed updating.
- [x] **`STAGE2_SCAN_REPORT.md` written** (this file). `local.properties` untouched.

## New tests (9, both flavors)

1. `chats scan in parallel bounded by six and start in list order` — с۱ (max in-flight = 6,
   launch order = list order, counters).
2. `one failing chat is contained and the rest still scan` — س۱ error policy.
3. `cancelling the scan propagates instead of becoming lastError` — с۱ cancellation rethrow.
4. `each page costs one batch lookup and one insert instead of per-song queries` — س۲.
5. `the same file in two parallel chats lands as a single row` — с۲ IGNORE.
6. `an empty candidate list still surfaces the timeout line` — wait path (spec #6).
7. `a chat with no audio pages completes without an error` — empty-chat path (spec #6).
8. `the first scan walks the whole chat like before` — с۳ spec #2 (3 pages, 250 rows, boundary 250).
9. `the second scan stops at the boundary and never re-indexes below it` — س۳ spec #1.

## Deviations from the spec

1. **No per-page `updateScanProgress` checkpoint (spec line 60 — explicitly a «پیشنهادی»).**
   The persisted value is `max(seen)`, which after page 1 already equals the chat's final
   boundary. Checkpointing during a *first* scan would therefore write `boundary = newest`
   while older pages are still un-indexed: a killed first scan would then resume with a
   one-page run forever and those older messages would **never** be indexed (today, with the
   boundary preserved at 0, a killed first scan simply restarts and gets everything). The
   end-of-chat `updateScanProgress` still writes the boundary on completion, which covers the
   incremental-run case the suggestion was aiming at. Flagged here per the spec's
   «هر انحراف از این پرامپت» requirement.
2. **`tracksIndexed` counts `insertAll` row ids (`!= -1`) instead of `toInsert.size`.**
   The spec's literal `+= toInsert.size` would overcount rows the IGNORE strategy dropped;
   the row-id count preserves the pre-existing «count only real inserts» behaviour of
   `indexAudio` exactly.
3. **On-device numbers not produced here** (spec line 97): real scan timing, `chats.size`
   after LoadChats, and the 500-cap behaviour are measured together with the owner.

**STOP — awaiting review / stage 3.**
