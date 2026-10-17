package com.extrumz.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.extrumz.app.R
import com.extrumz.app.data.LocalMediaRepository
import com.extrumz.app.data.LikeState
import com.extrumz.app.data.stats.ListeningStats
import com.extrumz.app.data.lyrics.EmbeddedLyrics
import com.extrumz.app.data.lyrics.LyricLine
import com.extrumz.app.data.lyrics.LyricsRepository
import com.extrumz.app.data.lyrics.LyricsSource
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthState
import com.extrumz.app.data.telegram.TelegramUi
import com.extrumz.app.data.model.Account
import com.extrumz.app.data.model.BrowseType
import com.extrumz.app.data.model.DetailPage
import com.extrumz.app.data.model.LikeStatus
import com.extrumz.app.data.model.SearchFilter
import com.extrumz.app.data.model.SearchResult
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.model.UiState
import com.extrumz.app.data.model.SearchHistoryEntity
import com.extrumz.app.data.model.EntityType
import com.extrumz.app.data.settings.SearchHistory
import com.extrumz.app.download.Downloads
import android.util.LruCache
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.extrumz.app.data.sources.SourceKind
import com.extrumz.app.data.sources.SourceRegistry
import com.extrumz.app.data.sources.SourceResolver
import com.extrumz.app.data.sources.TrackMatcher
import com.extrumz.app.playback.StreamChoice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.extrumz.app.data.db.TelegramDb

/** What the current track's provider picker already knows without another request. */
enum class LyricsProviderState {
    NOT_FETCHED,
    FETCHING,
    FOUND,
    NOT_FOUND,
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _signedIn = MutableStateFlow(
        Telegram.client.authState.value is TelegramAuthState.Ready,
    )
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    // Search rows plus the channel filter, as one stream: with a channel
    // chosen the published rows are narrowed to it (post-fetch, so remote
    // results are filtered the same way local FTS rows are), and clearing
    // the filter brings the full set straight back off the cache.
    val results: StateFlow<UiState<List<SearchResult>>?> = combine(
        _results.asStateFlow(),
        TelegramUi.selectedChatId,
    ) { state, chatId -> state.narrowedTo(chatId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The mixed YouTube Music result page is the fast, useful default. */
    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _searchLoadingMore = MutableStateFlow(false)
    val searchLoadingMore: StateFlow<Boolean> = _searchLoadingMore.asStateFlow()

    /** Increments once per first-page request so the UI can reset its list. */
    private val _searchScrollReset = MutableStateFlow(0)
    val searchScrollReset: StateFlow<Int> = _searchScrollReset.asStateFlow()

    /** True while a committed search is in flight — gates suggestion/media callbacks. */
    private var searchSubmitted = false

    /**
     * What the search page offers while a query is being typed, led by the
     * query itself.
     *
     * Non-empty *is* the signal that the field is mid-edit, so the screen
     * needs no second flag: these rows are shown in place of the results
     * whenever there are any, and cleared the moment a search is actually run
     * — see [submitSearch], [searchFor].
     *
     * Element 0 is always the raw text as typed. It's put there by the
     * keystroke itself rather than taken from the response, so the row the
     * thumb is already heading for is correct before the network answers, and
     * stays correct if it never does — YouTube's list never contains the
     * half-typed text, only completions of it.
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /**
     * Live media results shown alongside typeahead suggestions. Populated by a
     * lightweight search that runs in parallel with text completions; the UI
     * renders these as playable track cards and browse items below the text
     * suggestion rows. Cleared when the user commits to a search or empties
     * the field.
     */
    private val _typeaheadResults = MutableStateFlow<List<SearchResult>>(emptyList())
    // The same filter over the live rows under the field, so a channel chosen
    // anywhere narrows typing too.
    val typeaheadResults: StateFlow<List<SearchResult>> = combine(
        _typeaheadResults.asStateFlow(),
        TelegramUi.selectedChatId,
    ) { rows, chatId ->
        val filtered = if (chatId == null) rows else rows.forChannel(chatId)
        // The cap lands after narrowing, not before — see the write side.
        filtered.take(TYPEAHEAD_MAX_RESULTS)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // The search pipeline's own state. Declared here, above [init], because
    // that is where the collector is started from and a property declared
    // below it would still be null when it runs. See [startSearchPipeline].

    /**
     * Buffered so an emission is never lost to a collector that happens to be
     * mid-search, and [BufferOverflow.DROP_OLDEST] because when two arrive
     * together the later one is the one meant.
     */
    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The same arrangement as [searchRequests], for the typeahead — where the
     * drop policy earns its keep rather than just being safe: this one really
     * does take a keystroke each, and a fast typist's backlog should collapse
     * to the prefix they ended on instead of being worked through a letter at
     * a time.
     */
    private val suggestRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val newestRequestId = AtomicLong(0L)

    /**
     * Results of recent searches, so a query searched before is answered
     * without asking again. That covers the two ways a query is repeated most:
     * a filter tab, which re-runs the same text against a different tab and
     * then usually goes back, and a term tapped out of the recent searches.
     *
     * Its other half is [prefixMatch], which is what the typeahead makes worth
     * keeping: picking "coldplay yellow" off a list is normally preceded by
     * having searched "coldplay", and those results are close enough to leave
     * up for the moment the narrower one takes rather than blanking the page
     * to a spinner.
     */
    private data class SearchCacheEntry(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    private data class SearchSession(
        val key: String,
        val requestId: Long,
        val filter: SearchFilter,
        val continuation: String?,
    )

    private val searchCache = LruCache<String, SearchCacheEntry>(SEARCH_CACHE_ENTRIES)
    private var searchSession: SearchSession? = null

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    /** Which of the four databases [lyrics] came from, for the panel's credit. */
    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    private var lyricsJob: Job? = null
    private val manualLyricsJobs = mutableMapOf<LyricsSource, Job>()

    private val _lyricsProviderStates = MutableStateFlow(
        LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED },
    )
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        _lyricsProviderStates.asStateFlow()

    /** Completed hits are retained for the playing track so choosing one is instant. */
    private val lyricsProviderResults = ConcurrentHashMap<LyricsSource, LyricsRepository.Result>()

    private data class LyricsRequest(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    private var currentLyricsRequest: LyricsRequest? = null
    private var lyricsGeneration = 0L
    private var selectedLyricsSource: LyricsSource? = null

    /**
     * What the loaded lyrics are for. Both the track *and* the settings that
     * chose them, so switching a source on or off re-runs the lookup rather
     * than leaving the last answer sitting on a player that would now find a
     * different one.
     */
    private var lyricsFor: Pair<String, Set<LyricsSource>>? = null

    /**
     * Called as the playing track changes; cheap no-op when already loaded.
     *
     * [localUri] is the file this track plays from when it is on the device,
     * and it is tried before the network: a downloaded track had its lyrics
     * fetched once already and written into its own file (see `LyricsTag`), so
     * asking the same servers again is a round trip to arrive at a string that
     * is on disk — and one that fails outright with the connection off, which
     * is what made a downloaded song show nothing offline.
     */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
    ) {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        val key = videoId to sources
        if (lyricsFor == key) return
        // The duration lands a beat after the track, and a database match needs
        // it. Turned away here rather than inside the job below: claiming the
        // lookup first and giving it up asynchronously means the very re-trigger
        // that carries the duration can arrive while the claim still stands, be
        // dropped as a duplicate, and leave the track marked as being looked up
        // by nobody — which is what left a paused track loading for ever, since
        // pausing is when the duration is most likely to arrive a frame late.
        if (localUri == null && durationMs <= 0L) return
        lyricsFor = key
        lyricsGeneration += 1
        val generation = lyricsGeneration
        currentLyricsRequest = LyricsRequest(videoId, title, artist, durationMs, album)
        selectedLyricsSource = null
        manualLyricsJobs.values.forEach(Job::cancel)
        manualLyricsJobs.clear()
        lyricsProviderResults.clear()
        _lyricsProviderStates.value =
            LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }
        _lyrics.value = null
        _lyricsSource.value = null
        lyricsJob?.cancel()
        if (sources.isEmpty()) {
            // Switched off, or every source unticked. Nothing to look up, and
            // nothing to say about it — the player drops the lyric strip
            // rather than reporting a track with no lyrics.
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        lyricsJob = viewModelScope.launch {
            // The file first, and without the duration gate below: a length is
            // only needed to *match* a track against a stranger's database, and
            // nothing is being matched here — these lyrics were written into
            // this exact file, for this exact recording.
            if (localUri != null) {
                EmbeddedLyrics.forUri(getApplication(), localUri)?.let { embedded ->
                    _lyrics.value = embedded
                    // No source to name: what the file records is the lyrics,
                    // not which of the eight services they came from months ago.
                    _lyricsSource.value = null
                    _lyricsChecked.value = true
                    return@launch
                }
            }
            if (durationMs <= 0L) {
                // Duration arrives a beat after the track does; wait for it.
                lyricsFor = null
                return@launch
            }
            val found = LyricsRepository.lyrics(
                videoId, title, artist, durationMs, album, sources,
                AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { source -> providerStarted(generation, source) },
                onSourceResult = { source, result ->
                    providerFinished(generation, source, result)
                },
                onSourceCancelled = { source -> providerCancelled(generation, source) },
            )
            val selected = selectedLyricsSource?.let(lyricsProviderResults::get) ?: found
            _lyrics.value = selected?.lines
            _lyricsSource.value = selected?.source
            _lyricsChecked.value = true
        }
    }

    /**
     * Selects a provider from the player drawer. Completed hits are applied
     * from memory; misses are inert; only an untouched provider goes online.
     */
    fun selectLyricsProvider(source: LyricsSource) {
        val request = currentLyricsRequest ?: return
        when (_lyricsProviderStates.value[source]) {
            LyricsProviderState.FOUND -> {
                selectedLyricsSource = source
                lyricsProviderResults[source]?.let(::showLyricsResult)
            }
            LyricsProviderState.FETCHING -> {
                // Apply it as soon as the already-running automatic attempt completes.
                selectedLyricsSource = source
            }
            LyricsProviderState.NOT_FETCHED, null -> {
                selectedLyricsSource = source
                fetchLyricsProvider(request, lyricsGeneration, source)
            }
            LyricsProviderState.NOT_FOUND -> Unit
        }
    }

    private fun fetchLyricsProvider(
        request: LyricsRequest,
        generation: Long,
        source: LyricsSource,
    ) {
        if (manualLyricsJobs[source]?.isActive == true) return
        manualLyricsJobs[source] = viewModelScope.launch {
            LyricsRepository.lyrics(
                videoId = request.videoId,
                title = request.title,
                artist = request.artist,
                durationMs = request.durationMs,
                album = request.album,
                sources = setOf(source),
                order = listOf(source),
                prioritizeSyllableSync = false,
                onSourceStarted = { provider -> providerStarted(generation, provider) },
                onSourceResult = { provider, result ->
                    providerFinished(generation, provider, result)
                },
                onSourceCancelled = { provider -> providerCancelled(generation, provider) },
            )
        }
    }

    private fun providerStarted(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FETCHING) }
    }

    private fun providerFinished(
        generation: Long,
        source: LyricsSource,
        result: LyricsRepository.Result?,
    ) {
        if (generation != lyricsGeneration) return
        if (result == null) {
            _lyricsProviderStates.update { it + (source to LyricsProviderState.NOT_FOUND) }
            return
        }
        lyricsProviderResults[source] = result
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FOUND) }
        if (selectedLyricsSource == source) showLyricsResult(result)
    }

    private fun providerCancelled(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { states ->
            if (states[source] == LyricsProviderState.FETCHING) {
                states + (source to LyricsProviderState.NOT_FETCHED)
            } else {
                states
            }
        }
        // A tap may have selected a provider while the priority race was still
        // using it. If that race then cancels the loser, honour the tap with a
        // dedicated request instead of leaving the row stuck at "Fetching".
        if (selectedLyricsSource == source) {
            currentLyricsRequest?.let { fetchLyricsProvider(it, generation, source) }
        }
    }

    private fun showLyricsResult(result: LyricsRepository.Result) {
        _lyrics.value = result.lines
        _lyricsSource.value = result.source
        _lyricsChecked.value = true
    }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _history = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val history: StateFlow<UiState<List<Song>>> = _history.asStateFlow()

    /**
     * Album / artist / playlist pages, as a stack — opening an artist from an
     * album page and pressing back returns to the album, not to search.
     */
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()

    // ---- Ratings ---------------------------------------------------------

    /**
     * Every rating known for this session. Local only: the ratings live in
     * [LikeState] for as long as the process runs, shared with the playback
     * service so a notification tap and a player-screen tap paint the same
     * result immediately.
     */
    val likeStatuses: StateFlow<Map<String, LikeStatus>> = LikeState.overrides


    fun likeStatusOf(videoId: String): LikeStatus =
        likeStatuses.value[videoId] ?: LikeStatus.INDIFFERENT

    /**
     * Sets (or clears) the thumbs rating on [videoId].
     *
     * Written straight through: there is no remote account to refuse it, so
     * there is nothing to roll back. A rating is a one-tap, low-stakes action
     * taken while a song is playing, and the heart has to answer the tap.
     */
    fun setLike(videoId: String, status: LikeStatus) {
        if (!requireSignIn()) return
        if (likeStatusOf(videoId) == status) return
        // Memory only: this path has an id and nothing else, so there is
        // nothing to store. The heart goes through [toggleLike], which has the
        // song.
        LikeState.remember(videoId, status)
    }

    /**
     * The heart: liked becomes neutral, anything else becomes liked.
     *
     * Takes the Song, not the id, because the change has to be written down as
     * well as remembered and a bare id cannot be. See [LikeState.set].
     */
    fun toggleLike(song: Song) {
        if (!requireSignIn()) return
        LikeState.set(
            song,
            if (likeStatusOf(song.videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT
            else LikeStatus.LIKE,
        )
    }

    @Suppress("unused")
    private fun toggleLikeById(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    /** As [toggleLike], for the thumb-down. */
    fun toggleDislike(videoId: String): LikeStatus? {
        if (!requireSignIn()) return null
        val previous = likeStatusOf(videoId)
        setLike(
            videoId,
            if (previous == LikeStatus.DISLIKE) {
                LikeStatus.INDIFFERENT
            } else {
                LikeStatus.DISLIKE
            },
        )
        return previous
    }

    /**
     * The open track menu's account state, or null while it is still being
     * fetched. Only one menu can be open at a time, so one slot is enough.
     */
    /**
     * Guards every account write. All of them are signed-in-only, and the UI
     * hides them for guests — this is the backstop for a session that expired
     * between the menu opening and the tap.
     */
    private fun requireSignIn(): Boolean = _signedIn.value

    init {
        startSearchPipeline()
        startSuggestPipeline()
        startTypeaheadMediaPipeline()
        viewModelScope.launch {
            Telegram.client.authState.collect { state ->
                _signedIn.value = state is TelegramAuthState.Ready
            }
        }
        viewModelScope.launch {
            AppSettings.filterNonMusicAudio.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == "local:all" }) {
                    reloadLocalDetail("local:all")
                }
            }
        }
    }

    /** Tapping a tab should leave any pushed page behind. */
    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
    }

    /**
     * The songs played on this device, most recent first.
     *
     * Read straight off the library rather than fetched: every play is written
     * to the on-device track table as it happens, so the newest play is already
     * in hand and there is no account to ask. Rebuilt on each visit for the same
     * reason the remote page was — it is a list whose whole subject is what
     * happened most recently, and one that opened showing the state it was in
     * last time would be answering a different question.
     */
    fun loadHistory() {
        _history.value = UiState.Loading
        viewModelScope.launch {
            _history.value = runCatching {
                val songs = ListeningStats.recentTracks(HISTORY_ENTRIES)
                if (songs.isEmpty()) UiState.Error(text(R.string.history_empty))
                else UiState.Success(songs)
            }.getOrElse { UiState.Error(it.friendly()) }
        }
    }


    /** Recent searches, kept on device as rich entity records. */
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = SearchHistory.recent

    /**
     * Records a full entity payload in the history — used when the user taps
     * a suggestion, result row, or typeahead card so the recents list shows
     * real artwork and metadata instead of raw text.
     */
    fun recordEntity(entity: SearchHistoryEntity) = SearchHistory.record(entity)

    /**
     * Records a track entity from a search hit.
     * Reads the filtered [typeaheadResults] first (live media while typing),
     * then the filtered [results] (committed search) — the exact rows on
     * screen, so a channel search never records a song it didn't show.
     * Falls back to recording the raw query if no song data is available.
     */
    fun recordSearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        // Prefer typeahead results — they are live and always available while typing.
        val fromTypeahead = typeaheadResults.value.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (fromTypeahead is SearchResult.Track) {
            recordEntity(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        if (fromTypeahead is SearchResult.TopTrack) {
            recordEntity(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        // Fall back to committed search results.
        val topResult = (results.value as? UiState.Success)?.data?.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (topResult is SearchResult.Track) {
            recordEntity(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else if (topResult is SearchResult.TopTrack) {
            recordEntity(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else {
            // Fallback: record as a raw-text entity for backwards compatibility
            recordEntity(SearchHistoryEntity(
                id = "q:$q",
                title = q,
                subtitle = "",
                artworkUrl = null,
                entityType = EntityType.TRACK,
            ))
        }
    }

    /**
     * Runs a term the user picked out of a list rather than typed — a recent
     * search, or one of [suggestions] — and floats it to the top of the
     * history. Picking is as deliberate as submitting, so it searches on the
     * spot.
     *
     * Recording happens downstream via [recordSearch] when the user actually
     * interacts with a result (taps a song, album, or artist card), which
     * ensures the saved entity carries real artwork and metadata instead of
     * a blank placeholder.
     */
    fun searchFor(term: String) {
        _query.value = term
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        runSearch()
    }

    fun removeSearch(id: String) = SearchHistory.remove(id)

    fun clearSearchHistory() = SearchHistory.clear()

    fun onQueryChange(newValue: String) {
        _query.value = newValue
        if (newValue.isBlank()) {
            searchSubmitted = false
            _suggestions.value = emptyList()
            _typeaheadResults.value = emptyList()
            _results.value = null
            // The list is about to switch from search results to recent
            // searches (or the empty state) — without this it can keep
            // whatever scroll offset the results list was left at, landing
            // the new, much shorter list somewhere other than the top.
            _searchScrollReset.value += 1
            return
        }
        // Reset the submission gate so typeahead pipelines fire again.
        searchSubmitted = false
        // While typing, surface text completions — the pipeline already feeds
        // them through [suggestRequests] and publishes results via typeahead.
        suggestRequests.tryEmit(newValue)
    }

    /**
     * Commits the current query text: clears suggestions/typeahead, runs the
     * full search, and records the term in history for future recall.
     */
    fun submitSearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        runSearch()
    }

    fun onFilterChange(value: SearchFilter) {
        if (_filter.value == value) return
        _filter.value = value
        searchSubmitted = true
        runSearch()
    }

    /**
     * A search asked for, as a request the pipeline below decides what to do
     * with.
     *
     * [requestId] is what makes a late answer harmless: a response is only
     * written to the screen if its id is still the newest one asked for.
     */
    private data class SearchRequest(
        val query: String,
        val filter: SearchFilter,
        val requestId: Long,
    )

    private fun cacheKey(query: String, filter: SearchFilter) = "${filter.name}:$query"

    /**
     * The results of the longest earlier query this one starts with — near
     * enough to leave up while the narrower search runs.
     */
    private fun prefixMatch(query: String, filter: SearchFilter): List<SearchResult>? {
        val prefix = "${filter.name}:"
        return searchCache.snapshot()
            .filterKeys { it.startsWith(prefix) && query.startsWith(it.removePrefix(prefix), true) }
            .maxByOrNull { it.key.length }
            ?.value
            ?.rows
    }

    private fun runSearch() {
        val query = _query.value
        if (query.isBlank()) {
            // Nothing in flight can still be waiting to overwrite this: the
            // id it would be checked against has already moved past it.
            newestRequestId.incrementAndGet()
            searchSession = null
            _searchLoadingMore.value = false
            _results.value = null
            return
        }
        val id = newestRequestId.incrementAndGet()
        _searchScrollReset.value += 1
        searchSession = null
        _searchLoadingMore.value = false
        searchRequests.tryEmit(SearchRequest(query, _filter.value, id))
    }

    /**
     * The search pipeline, started once and left running for the lifetime of
     * the view model.
     *
     * The point of it being one long-lived collector is that a new search no
     * longer cancels the request before it out of a fresh coroutine.
     * Cancelling a call mid-flight tears down its socket, and on a pooled HTTP
     * client that is felt by whatever picks that connection up next — which is
     * how one search could end in "Software caused connection abort" for a
     * request that was never itself in any trouble.
     *
     * There is no debounce here any more, and nothing to absorb: a search is
     * only ever asked for by a deliberate act — the search button, a
     * suggestion or history row, a filter tab — so the request that arrives is
     * already the one the user meant, and making them wait out a timer for it
     * would be a delay with nothing behind it. Typing asks
     * [startSuggestPipeline] for completions instead and leaves the results
     * alone.
     */
    private fun startSearchPipeline() = viewModelScope.launch {
        searchRequests
            .collectLatest { request ->
                val key = cacheKey(request.query, request.filter)
                // Something to look at immediately: the exact answer if this
                // query has been run before, otherwise the closest earlier
                // one. Only fall back to a spinner with neither.
                val exact = searchCache.get(key)
                val cached = exact?.rows ?: prefixMatch(request.query, request.filter)
                _results.value = cached?.let { UiState.Success(it) } ?: UiState.Loading
                if (exact != null) {
                    searchSession = SearchSession(key, request.requestId, request.filter, exact.continuation)
                    return@collectLatest
                }

                // Search is the local index plus this device's own files —
                // Telegram's FTS5 first, then whatever audio is on the phone.
                // There is no external catalogue left to fan out to: phase 2
                // removed every non-Telegram source, so this is the whole
                // answer rather than a supplement to a YouTube page.
                val rows = searchTelegramAndLocal(request.query, request.filter)
                // A search that has been superseded shouldn't land on screen.
                if (request.requestId != newestRequestId.get()) return@collectLatest
                _results.value = published(rows, key, request.requestId)
            }
    }

    /**
     * Continues the visible search only when the list reaches its end.
     *
     * Kept for the UI's load-more call site; the local search answers in one
     * page — FTS5 and MediaStore have no continuation token — so there is
     * never anything to fetch here.
     */
    fun loadMoreSearchResults() {
        val session = searchSession ?: return
        if (session.continuation == null) return
        if (_searchLoadingMore.value) return
    }

    /**
     * The typeahead pipeline, alongside [startSearchPipeline] and for the same
     * structural reason — one long-lived collector rather than a coroutine per
     * keystroke, so a lookup the user has typed past doesn't take a pooled
     * socket down with it.
     *
     * This one *does* debounce, and that isn't the timer that was taken off the
     * search. It's two orders of magnitude shorter, and it's paid for by the
     * request behind it being a few hundred bytes rather than a full page of
     * results — a burst of keystrokes shouldn't each cost a round trip, but the
     * gap has to be short enough that the list is up before the next letter is
     * typed. Nothing is waiting on it either way: the row the user typed is
     * already on screen from the keystroke itself.
     *
     * A failure is left on the floor. There is no worthwhile way to report
     * "couldn't suggest anything" in a list of suggestions, and the typed text
     * is standing there as a working first row regardless.
     */
    @OptIn(FlowPreview::class)
    private fun startSuggestPipeline() = viewModelScope.launch {
        // Whether a list for [input] is still wanted. False once the field has
        // moved on: typed further, or searched — which empties [_suggestions],
        // and a late answer writing to it would reopen the suggestions over
        // the results the user is by then reading.
        fun stillWanted(input: String) = _query.value == input && !searchSubmitted

        suggestRequests
            .debounce(SUGGEST_DEBOUNCE_MS)
            .collectLatest { input ->
                if (!stillWanted(input)) return@collectLatest
                val fetched = runCatching { suggestionTitles(input, TelegramUi.selectedChatId.value) }.getOrNull()
                    ?: return@collectLatest
                // Asked again on the way back; the field is live throughout.
                if (!stillWanted(input)) return@collectLatest
                _suggestions.value = listOf(input) +
                    fetched.filterNot { it.equals(input, ignoreCase = true) }
            }
    }

    /**
     * Parallel pipeline that fetches live media results (tracks, artists,
     * albums) for the current query text. Runs alongside [startSuggestPipeline]
     * with its own debounce so a fast typist doesn't saturate the network.
     */
    @OptIn(FlowPreview::class)
    private fun startTypeaheadMediaPipeline() = viewModelScope.launch {
        suggestRequests
            .debounce(TYPEAHEAD_MEDIA_DEBOUNCE_MS)
            .collectLatest { input ->
                if (input.isBlank()) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                // Only show media results while the user is still typing — not
                // reading committed search results. The query-text check on its
                // own isn't enough: a late suggestion callback can repopulate
                // _suggestions after submission, and we must not re-open the
                // typeahead dropdown under an already-committed search.
                if (searchSubmitted) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                val result = searchTelegramAndLocal(input, SearchFilter.SONGS)
                // If the field moved on, drop the result silently.
                if (_query.value != input) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                // Stored whole: [typeaheadResults] narrows to the chosen
                // channel first and only then caps — slicing to 8 here would
                // drop a channel's matches past rank 8 of the global list
                // and the dropdown could come back empty with hits in range.
                _typeaheadResults.value = result
            }
    }

    /** Caches and publishes the first (and only) search page. */
    private fun published(
        rows: List<SearchResult>,
        key: String,
        requestId: Long,
    ): UiState<List<SearchResult>> {
        if (rows.isEmpty()) return UiState.Error(text(R.string.no_results))
        searchCache.put(key, SearchCacheEntry(rows, continuation = null))
        searchSession = SearchSession(key, requestId, _filter.value, continuation = null)
        return UiState.Success(rows)
    }

    /**
     * The channel filter, applied after the fact. With no channel chosen the
     * rows stand as they are; with one, only that channel's indexed tracks
     * remain — and a set that empties out says so the way a search that never
     * found anything does.
     */
    private fun UiState<List<SearchResult>>?.narrowedTo(
        chatId: Long?,
    ): UiState<List<SearchResult>>? {
        if (chatId == null) return this
        val rows = (this as? UiState.Success)?.data ?: return this
        val kept = rows.forChannel(chatId)
        return if (kept.isEmpty()) {
            UiState.Error(text(R.string.no_results))
        } else {
            UiState.Success(kept)
        }
    }

    /** A row survives the channel filter only if it was indexed from that chat. */
    private fun List<SearchResult>.forChannel(chatId: Long): List<SearchResult> = filter { row ->
        when (row) {
            is SearchResult.TopTrack -> row.song.sourceChatId == chatId
            is SearchResult.Track -> row.song.sourceChatId == chatId
            is SearchResult.Browse -> false
        }
    }

    private fun searchResultKey(row: SearchResult): String = when (row) {
        is SearchResult.TopTrack -> "v:${row.song.videoId}"
        is SearchResult.Track -> "v:${row.song.videoId}"
        is SearchResult.Browse -> "b:${row.item.browseId}"
    }

    /**
     * The whole of search: the indexed Telegram catalogue (FTS5 first, LIKE
     * as fallback — see [TelegramSource.localResults]) plus this device's own
     * audio files. External catalogues are gone as of phase 2, so the
     * browse-shaped filters have nothing to answer with and return empty.
     *
     * Telegram first and it keeps its own ranking; local files follow, tokens
     * matching title or artist. One page — neither FTS5 nor MediaStore has a
     * continuation token.
     */
    private suspend fun searchTelegramAndLocal(
        query: String,
        filter: SearchFilter,
    ): List<SearchResult> {
        if (filter == SearchFilter.ALBUMS || filter == SearchFilter.ARTISTS ||
            filter == SearchFilter.PLAYLISTS
        ) {
            return emptyList()
        }
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val telegram = runCatching {
            SourceRegistry.active()
                .firstOrNull { it.kind == SourceKind.TELEGRAM }
                ?.search(query, SEARCH_PAGE_SIZE)
                .orEmpty()
        }.getOrElse { emptyList() }
        val local = runCatching {
            val context = getApplication<Application>()
            LocalMediaRepository.getLocalMusic(context).filter { song ->
                tokens.all { token ->
                    song.title.contains(token, ignoreCase = true) ||
                        song.artist.contains(token, ignoreCase = true)
                }
            }
        }.getOrElse { emptyList() }
        return (telegram + local)
            .distinctBy { it.videoId }
            .take(SEARCH_PAGE_SIZE)
            .map { SearchResult.Track(it) }
    }

    /**
     * What the suggestion chips under the search field offer: titles from the
     * same local index the search itself would answer with, capped small —
     * the typed text is already a working first row.
     */
    private suspend fun suggestionTitles(
        input: String,
        chatId: Long?,
    ): List<String> =
        runCatching {
            SourceRegistry.active()
                .firstOrNull { it.kind == SourceKind.TELEGRAM }
                ?.search(input, SUGGEST_SOURCE_LIMIT)
                .orEmpty()
        }.getOrElse { emptyList() }
            // A chosen channel narrows the chips the way it narrows the rows:
            // suggestions from other chats would contradict the filter.
            .filter { chatId == null || it.sourceChatId == chatId }
            .map { it.title.ifBlank { it.artist } }
            .distinct()
            .take(SUGGEST_MAX_RESULTS)

    companion object {
        /**
         * How long a keystroke waits before the typeahead is asked about it.
         *
         * Not the search's timer — searches aren't on a timer any more. This
         * one only stops a fast typist spending a round trip per letter, so it
         * wants to be as short as it can be while still collapsing a burst:
         * long enough that "cold" isn't four lookups, short enough that the
         * list is up by the time the thumb has left the key.
         */
        const val SUGGEST_DEBOUNCE_MS = 180L

        /**
         * Debounce for the parallel media-search pipeline. Slightly longer than
         * text suggestions so it doesn't fire on every single keystroke — a
         * full search is heavier than a suggestion request, and the UI only
         * needs a few results to fill the dropdown.
         */
        const val TYPEAHEAD_MEDIA_DEBOUNCE_MS = 350L

        /**
         * Maximum number of live media results shown in the typeahead dropdown.
         * Enough to give variety without making the list unscrollable.
         */
        const val TYPEAHEAD_MAX_RESULTS = 8

        const val SEARCH_CACHE_ENTRIES = 100

        /**
         * How many rows the one search page carries.
         *
         * One page because there is nothing to continue from: FTS5 and
         * MediaStore both answer in full. Big enough to scroll, small enough
         * that a broad query doesn't build a list nobody reaches the end of.
         */
        const val SEARCH_PAGE_SIZE = 50

        /** How deep the suggestion chips dig into the same local index. */
        const val SUGGEST_SOURCE_LIMIT = 10

        /** Titles offered under the field, above the typed text itself. */
        const val SUGGEST_MAX_RESULTS = 8

        /**
         * How many rows the on-device listening history carries.
         *
         * One screenful's worth, not the whole table: the list is read fresh
         * every visit and anything past the first rows answers a question the
         * scroll already answered.
         */
        const val HISTORY_ENTRIES = 100

        /**
         * What a downloaded playlist's page says once the files under it are
         * gone.
         *
         * A record here outlives the folder it names — the user is expected to
         * manage Downloads with a file manager — so this is a state its page has
         * to be able to reach, not an error. Named because three places say it:
         * the page, its refresh, and the long-press menu that queues it without
         * opening it.
         */

        fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
            browseId.startsWith(Downloads.PLAYLIST_PREFIX) -> BrowseType.PLAYLIST
            browseId.startsWith("UC") -> BrowseType.ARTIST
            browseId.startsWith("MPREb") || browseId.startsWith("VLOLAK") || browseId.startsWith("OLAK") -> BrowseType.ALBUM
            browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
            else -> fallback
        }
    }

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
    ) {
        // A fast double tap used to push two identical loading pages and launch
        // two identical browse requests. Besides wasting the connection, both
        // completions then raced to update every matching browse id in the
        // stack. The page is pushed synchronously, so this closes that window
        // without suppressing a deliberate revisit after the first page loads.
        if (_detailStack.value.lastOrNull()?.let {
                it.browseId == browseId && it.songs is UiState.Loading
            } == true
        ) return
        val resolved = browseTypeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
        )
        viewModelScope.launch {
            // Callers that open an artist from a track — the player, the
            // long-press menu — only have that track's cover art and its full
            // credit ("A, B & C") to hand, so the page's own header has to be
            // taken over from them once it arrives. Local pages supply nothing
            // here; the card the user tapped already said what it is.
            val state = when {
                Downloads.recordIdOf(browseId) != null -> {
                    val songs = downloadedPlaylist(browseId)
                    if (songs.isEmpty()) UiState.Error(text(R.string.downloaded_playlist_empty))
                    else UiState.Success(songs)
                }
                browseId == "local:downloads" -> {
                    val context = getApplication<Application>()
                    val songs = Downloads.getDownloadedSongs(context)
                    if (songs.isEmpty()) UiState.Error("No downloaded tracks")
                    else UiState.Success(songs)
                }
                browseId == "local:all" -> {
                    val context = getApplication<Application>()
                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                        UiState.Error(text(R.string.storage_required_read))
                    } else {
                        val songs = LocalMediaRepository.getLocalMusic(context)
                        if (songs.isEmpty()) UiState.Error(text(R.string.no_local_audio_found))
                        else UiState.Success(songs)
                    }
                }
                else -> UiState.Error(text(R.string.no_tracks_here))
            }
            // Update by id — the user may have pushed another page meanwhile.
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId && it.songs is UiState.Loading) {
                    it.copy(songs = state)
                } else {
                    it
                }
            }
        }
    }

    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val state: UiState<List<Song>> = when {
                Downloads.recordIdOf(browseId) != null -> {
                    val songs = downloadedPlaylist(browseId)
                    if (songs.isEmpty()) UiState.Error(text(R.string.downloaded_playlist_empty))
                    else UiState.Success(songs)
                }
                browseId == "local:downloads" -> {
                    val songs = Downloads.getDownloadedSongs(context)
                    if (songs.isEmpty()) UiState.Error("No downloaded tracks")
                    else UiState.Success(songs)
                }
                browseId == "local:all" -> {
                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                        UiState.Error(text(R.string.storage_required_read))
                    } else {
                        val songs = LocalMediaRepository.getLocalMusic(context)
                        if (songs.isEmpty()) UiState.Error(text(R.string.no_local_audio_found))
                        else UiState.Success(songs)
                    }
                }
                else -> return@launch
            }
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(songs = state)
                } else it
            }
        }
    }

    /**
     * The tracks of the downloaded playlist [browseId] names that are still on
     * disk, in the order the playlist had.
     *
     * The download record already names those files, so opening this page must
     * not scan every unrelated download first. [Downloads.getCollectionSongs]
     * verifies only this collection and still collapses aliases that point to
     * the same saved file.
     *
     * Empty is the honest answer for a record whose files have all been deleted
     * from under it, and callers turn that into an empty-state message rather than
     * into a blank page.
     */
    private suspend fun downloadedPlaylist(browseId: String): List<Song> {
        val id = Downloads.recordIdOf(browseId) ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    /**
     * An album's track listing doesn't repeat the cover on every row — the
     * page carries it once — so rows arrive with no artwork and stay blank
     * through to the queue and the notification. Fall back to the page's.
     */
    private fun List<Song>.withArtwork(fallback: String?): List<Song> {
        if (fallback == null) return this
        return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
    }

    /**
     * Home and Explore cards don't say what they point at, and an artist
     * fetched as an album only yields the five songs on its landing page.
     * YouTube's browse ids are prefixed by kind, so use that.
     *
     * Public because the long-press menus ask the same question of a card
     * before offering to queue what is behind it — an artist is not a running
     * order, so it gets no queue actions.
     */
    fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType =
        Companion.browseTypeOf(browseId, fallback)

    /**
     * Every track behind an album or playlist, handed to [onResult] once it is
     * all in.
     *
     * What a long-press on a card is acting on. A card has nothing but a browse
     * id — its page was never opened, so there is no track list anywhere to
     * read — and "add this album to the queue" means the whole album, so this
     * follows continuations to the end rather than taking the first page.
     *
     * Runs in [viewModelScope], not the caller's: the sheet the tap came from
     * closes immediately, and a three-hundred-track playlist must not be
     * abandoned halfway because of it.
     */
    fun collectSongs(
        browseId: String,
        artworkFallback: String? = null,
        onResult: (Result<List<Song>>) -> Unit,
    ) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val result = when {
                Downloads.recordIdOf(browseId) != null -> runCatching {
                    downloadedPlaylist(browseId).ifEmpty {
                        error(text(R.string.downloaded_playlist_empty))
                    }
                }
                browseId == "local:downloads" -> runCatching {
                    Downloads.getDownloadedSongs(context)
                        .ifEmpty { error("No downloaded tracks") }
                }
                browseId == "local:all" -> runCatching {
                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                        error(text(R.string.storage_required_read))
                    }
                    LocalMediaRepository.getLocalMusic(context)
                        .ifEmpty { error(text(R.string.no_local_audio_found)) }
                }
                else -> runCatching { error(text(R.string.no_tracks_here)) }
            }
            onResult(result.map { it.withArtwork(artworkFallback) })
        }
    }

    /** Pops one page; returns false when there was nothing to pop. */
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        return true
    }

    private fun Throwable.friendly(): String = when {
        message?.contains("resolve host", true) == true ||
            message?.contains("Unable to resolve", true) == true -> text(R.string.no_internet_connection)
        else -> message ?: text(R.string.something_went_wrong)
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)
}
