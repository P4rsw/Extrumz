# Stage 1 — Navigation: 3 tabs + Channels entries

Status: **complete — STOP.** Spec: `../OPENCODE_PROMPT_STAGE1.md`; decisions: `../MASTER_LIST.md`
(§۴/§۵/§۶). This stage touched navigation only; scan speed, metadata, settings, Persianization
and downloads are later stages.

## Commits

| # | Sub-step | Commit | Files | Diff |
|---|----------|--------|-------|------|
| الف | Bottom bar → 3 tabs | `593be6f feat(stage1): switch the bottom bar to Songs \| Search \| Library` | 19 | +150/−31 |
| ب | Library Channels shelf + fullscreen picker | `0bb1b96 feat(stage1): add the Channels shelf in Library and the fullscreen picker` | 20 | +162/−37 |
| ج | Search channel pill + result filtering | `d8f3825 feat(stage1): add the channel filter pill to Search and narrow results by it` | 5 | +137/−2 |

Each sub-step was gated on a green `:app:assembleProdDebug` **and** both test tasks
(`:app:testDevDebugUnitTest`, `:app:testProdDebugUnitTest`) before committing.

## Sub-step الف — bottom bar, 3 tabs

- `MainActivity.kt`
  - `tabs` = `[BottomTab(songsLabel, MusicNote), BottomTab(searchTabLabel, Search), BottomTab(libraryTabLabel, Library)]` (line 684+).
  - Constants reset to list positions: `TAB_HOME=0, TAB_SEARCH=1, TAB_LIBRARY=2`; `TAB_EXPLORE` **deleted**.
  - Content `when` restructured: explicit `TAB_SEARCH` and `TAB_LIBRARY` branches, `else -> SongsScreen` as the
    default-home fallback (the branch order makes Library explicit instead of a catch-all, per spec line 12).
  - Tab-tap handler unchanged — it compares against `TAB_SEARCH`, whose value simply moved from 3 to 1, so
    query-clear + focus + keyboard still fire exactly on the Search tab.
  - `PlaybackSourceType.EXPLORE` restore: first mapped to `TAB_HOME`, upgraded in ب to `showChannels = true`
    (spec line 14's "better" option). The enum value itself is kept (data/history); nothing sets it any more.
  - The idle-list-state comment (formerly "Songs and Channels tabs") rewritten for the new layout.
- `GlassNavBar.kt:115` — the stale "Search is the odd one out… last tab in BitChord's order" comment replaced:
  with the owner's chosen order the standalone **circle is Library**, drawn through the existing icon-only slot
  (`GlassNavBar.kt:205-216`).
- `BarPreviews.kt` (debug preview) — fake tab list updated to the production 3 tabs.
- Strings: `tab_search` = «جستجو», `tab_library` = «کتابخانه» added to `values/strings.xml` **and** all 15
  locale files (noun forms, mirroring each locale's existing `search`/`library`), so `LocaleStringsTest` stays green.

### Fate of TAB_EXPLORE

Gone. `grep -rn "TAB_EXPLORE" app/src` → **0 references** (verified after each sub-step; final check at report time).
Its three former duties moved: the tab entry disappeared (spec), the restore path opens the new overlay (ب),
and ChannelsScreen is now reached from the Library shelf and the Search pill (ب/ج).

### Circle icon decision

Kept `BitChordIcons.Library` for the circle — it is the app's own library glyph, legible at 25 dp icon-only,
and it now marks the tab the shape was always reserved for (the last one). No icon swap was needed; only the
comment changed.

## Sub-step ب — Library Channels shelf + fullscreen picker

- `LibraryScreen.kt`
  - New shelf (key `shelf:<channels title>`) **above** the on-device shelf: first card «مدیریت کانال‌ها»
    (`browseId = "tg:channels"`), then one card per scanned chat (`tg:chat:<chatId>`, subtitle
    `<trackCount> آهنگ`, same hardcoded pattern `ChannelsScreen.ChatRow` already uses). Chats come from
    `TelegramDb.chatDao.observeAllByTrackCount()` collected live — the shelf fills as a scan finishes.
  - `onShowAll` wires the shelf into the existing grid page, so "Show all" works like every other shelf.
- `Shelves.kt` — `ShelfCard` got a `tg:` branch: the shared Telegram blue mesh (`ServiceCard`,
  `BitChordIcons.Explore` glyph) instead of a grey artwork placeholder (channel photos are not indexed yet —
  MASTER_LIST م۲, stage 3). The `when` was converted to subject-less form for that branch.
- `MainActivity.kt`
  - `var showChannels` + `var channelsReturnToSongs`, following the `showHistory`/`showReplay` pattern:
    content-switch key `"channels"` (ordered after History, before the grid — so the picker outranks the grid it
    may open from), dedicated inner branch rendering `ChannelsScreen`, `BackHandler`, top-bar title
    («کانال‌ها»), big-title-always (mirrors the old tab's idle scroll behaviour), `onBack`, and resets in
    `openSource`, `onTabSelected` and the account-button click (Settings must not open over the picker and
    lose to it in the switch).
  - `onLibraryItemClick`: `tg:channels` → `channelsReturnToSongs = true; showChannels = true`;
    `tg:chat:<id>` → toggles `TelegramUi.selectedChatId` and lands on `TAB_HOME` (clearing `libraryShowAll`,
    whose grid key would otherwise outrank the tab key). All other ids keep the original perm-check +
    `openDetail` flow verbatim (inside `else ->`).
  - Long-press guard: `onBrowseLongPress` skips `tg:` ids — there is no page/queue behind those cards.
  - Picker landing: from the shelf, picking a channel closes the overlay onto **Songs** with the existing
    chip filter on (acceptance line 59); `PlaybackSourceType.EXPLORE` opens the picker itself.
- `ChannelsScreen.kt` — KDoc only (picker role, Search entry).

## Sub-step ج — Search channel pill + filtering

- `Models.kt` — `Song.sourceChatId: Long = 0L` added (last field of the primary constructor; legacy
  secondary constructor unaffected).
- `TelegramSource.kt` — `Track.toSong()` now maps `sourceChatId = sourceChatId`. This covers **both** FTS5
  local rows and "remote" results: `search()` re-reads rows through `localResults()` after indexing, so
  network hits are filtered post-fetch exactly as spec line 41 asks. Device files from
  `LocalMediaRepository` keep 0 and are therefore excluded while a channel is chosen — by design.
- `MainViewModel.kt`
  - `results` and `typeaheadResults` are now `combine(raw, TelegramUi.selectedChatId)` → `.stateIn(Eagerly)`.
  - `narrowedTo(chatId)`: `null` → rows untouched; a chosen chat keeps `Track`/`TopTrack` rows whose
    `song.sourceChatId == chatId`, drops `Browse`, and an emptied set becomes
    `UiState.Error(no_results)` instead of a blank list.
  - Filtering is post-fetch/ViewModel-layer: toggling the pill re-applies instantly off the cache — no
    re-query, and remote + local rows go through the same gate.
- `SearchScreen.kt` — new `onOpenChannels` param + `ChannelFilterPill` under the field (always visible, not
  gated on `results != null`): no channel → ghost pill «کانال‌ها» opening the same fullscreen picker
  (`channelsReturnToSongs = false`, listener stays on Search); chosen → `✕ <channel name>` pill clearing
  `TelegramUi.selectedChatId`. Styling mirrors `SearchFilterTabs` (same `FILTER_PILL_SHAPE`, same selected/
  unselected colours).
- `MainActivity.kt` — call site passes `onOpenChannels` and pins the Search landing.
- **`SearchFilterTabs` and `SearchFilter` untouched** (YouTube params stay for stage 5).
- One state, three doors: Library shelf card, Search pill, and the pre-existing SongsScreen chip all turn
  `TelegramUi.selectedChatId` (spec line 42).

## Final gate (report time, tree `d8f3825`)

| Check | Result |
|-------|--------|
| `:app:assembleProdDebug` | **BUILD SUCCESSFUL** |
| `:app:testDevDebugUnitTest` + `:app:testProdDebugUnitTest` | **BUILD SUCCESSFUL** |
| Test totals (both flavors' result XMLs) | **1110 tests, 0 failures, 0 errors**, 2 skipped (the pre-existing offline `GeniusTest` Assume-skips from Phase 2 — unrelated to this stage) |
| `grep -rn "TAB_EXPLORE" app/src` | **0** |
| Bottom bar | `MusicNote` آهنگ‌ها \| `Search` جستجو \| `Library` کتابخانه — no Channels tab |
| `git diff -- local.properties` | 0; working tree clean |
| Test files touched by this stage | **none** — no test referenced `TAB_EXPLORE`/tab order (checked before editing) |

## Acceptance (spec lines 55-62)

- [x] **3-tab bar, no Channels tab** — `MainActivity.kt:684-689`; proven by compile + `grep BottomTab`.
- [x] **All three tabs switch; Search tap clears query + keyboard** — explicit `TAB_SEARCH`/`TAB_LIBRARY`
  branches + unchanged tap handler comparing `index == TAB_SEARCH` (constant moved with the list, so the
  comparison still lands on the Search tab). Interactive tap feel is device verification with the owner.
- [x] **`grep TAB_EXPLORE` = 0; EXPLORE restore rational, no crash** — restore now opens the picker
  (`showChannels = true`); compile-proven, and the branch is unreachable in practice (nothing sets EXPLORE).
- [x] **Library: shelf → manage → fullscreen ChannelsScreen with back; channel tap → Songs with filter** —
  `LibraryScreen` shelf + `showChannels` overlay (key, title, BackHandler, `onBack`) + `tg:chat:` branch
  jumping to `TAB_HOME`; compile-proven.
- [x] **Search: pill (select/clear) + results only from the chosen channel** — `ChannelFilterPill` +
  `narrowedTo()` over `sourceChatId`; compile-proven, filtering logic unit-level simple (post-fetch filter).
- [x] **Green assemble + both test tasks, 1110 tests, 0 errors** — table above; no test updates were needed.
- [x] **This report written.**

## Notes for the device pass (with the owner)

- VPN/Telegram connection first (MASTER_LIST ۵/۹): shelf cards only populate after a scan.
- Expected on device: tab switching, Search tap → keyboard, shelf card → picker → back, channel card →
  Songs chip, pill → picker → results re-filter, pill ✕ → full results back.
- Two skipped tests are the Phase 2 offline skips, not new.

**STOP — awaiting review/next stage.**

---

## Review (Hermes) — 2026-10-03: PASS

**Build/tests re-run independently (forced, not UP-TO-DATE):**
- `assembleProdDebug` green; fresh APKs (prod/arm64, 18:48).
- `testDevDebugUnitTest --rerun` → 555 tests, 0 fail/err. `testProdDebugUnitTest --rerun` → 555 tests, 0 fail/err. **Total 1110 / 0 errors.**
- `grep -rn TAB_EXPLORE app/src` → 0 live references. `git status` clean.

**Code inspection (Hermes):** tab constants renumbered to list positions; `when` has explicit `TAB_HOME/TAB_SEARCH/TAB_LIBRARY` branches; `PlaybackSourceType.EXPLORE → showChannels=true` (restore path covered); `showChannels` key ordered after `history`, before `library_show_all`; `onLibraryItemClick` keeps the original permission/`openDetail` flow verbatim in `else`; `narrowedTo`/`forChannel` applied to both `_results` (with `no_results` on empty) and `_typeaheadResults`; `Browse` rows correctly dropped under a channel filter; GlassNavBar comment updated to the new order.

**Device test (b6583103, fresh install of this HEAD, Telegram connected — decision 9 checked):**
1. Bottom bar renders exactly **آهنگ‌ها | جستجو | کتابخانه**, no کانال‌ها tab. ✓
2. All three tabs switch; Songs content intact. ✓
3. Search tab: field focus + IME opens on tap (`mInputShown=true`); query cleared on re-entry. ✓
4. Search pill «کانال‌ها» → full-screen picker with live channels (names + counts). ✓
5. Channel selected → **stays on Search**, pill becomes «✕ 🌪», and results filter to that channel: query `remix` with filter = 9 rows; after clearing, top rows showed non-channel items (Sange Mazar, Perisan…); scrolling the unfiltered list found the filtered items («Baraan (Remix)», «Nakhla Remix») → **filtered ⊆ unfiltered, non-channel rows removed — A/B proof**. ✓
6. Library: «کانال‌ها» shelf with Show all + «مدیریت کانال‌ها» card + per-channel cards (🌪 1823 آهنگ…). ✓
7. «مدیریت کانال‌ها» → picker opens; BACK → returns to Library (no crash). ✓
8. Channel card tap → lands on Songs with the «✕ 🌪» chip. ✓
9. `logcat`: no FATAL / no com.music.bitchord crash during the whole session. ✓

**Findings:** none blocking. Note (stage 5): shelf subtitle `"${chat.trackCount} آهنگ"` is an inline hardcoded string, consistent with the codebase's existing convention (SourceKind/TelegramSource health strings) — fold into the `values-fa`/string-resource cleanup in stage 5.

**Verdict: stage 1 acceptance criteria all met.** Pending: Gemini review.
