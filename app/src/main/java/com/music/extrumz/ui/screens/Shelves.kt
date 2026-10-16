package com.extrumz.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import com.extrumz.app.ui.icons.ExtrumzIcons
import com.extrumz.app.R
import coil3.compose.AsyncImage
import com.extrumz.app.data.model.CARD_ART_PX
import com.extrumz.app.data.model.ShelfItem
import com.extrumz.app.data.telegram.Covers
import java.util.Locale
import com.extrumz.app.data.model.artworkAt
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.ui.components.PAGE_GUTTER
import com.extrumz.app.ui.components.SHELF_CARD_WIDTH
import com.extrumz.app.ui.components.thumbnailBorder
import com.extrumz.app.ui.player.MeshGradientBackground
import com.extrumz.app.ui.player.MeshPalette
import com.extrumz.app.ui.components.forLanguage
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite

/**
 * Shared by the home feed, Explore and Library so headings line up across tabs.
 *
 * [onShowAll] is only ever set on Library, whose rows stop at five cards
 * rather than running the shelf's whole length — see [LibraryGridShelf].
 * Home and Explore never pass it, so their heading is unchanged.
 */
@Composable
internal fun SectionHeader(title: String, subtitle: String = "", onShowAll: (() -> Unit)? = null) {
    val displayTitle = localizeShelfTitle(title)
    val displaySubtitle = localizeShelfSubtitle(subtitle)
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = displayTitle,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (displaySubtitle.isNotBlank()) {
                Text(
                    text = displaySubtitle,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onShowAll != null) {
            Text(
                text = stringResource(R.string.show_all),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onShowAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}
@Composable
internal fun localizeShelfTitle(title: String): String {
    val trimmed = title.trim()
    return when {
        trimmed.equals("Recents", ignoreCase = true) ||
            trimmed.equals("Recently played", ignoreCase = true) ||
            trimmed.equals("Gần đây", ignoreCase = true) ||
            trimmed.equals("最近", ignoreCase = true) ->
            stringResource(R.string.shelf_recents)
        trimmed.equals("Playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát", ignoreCase = true) ||
            trimmed.equals("再生リスト", ignoreCase = true) ->
            stringResource(R.string.playlists)
        trimmed.equals("Albums", ignoreCase = true) ||
            trimmed.equals("Album", ignoreCase = true) ||
            trimmed.equals("アルバム", ignoreCase = true) ->
            stringResource(R.string.albums)
        trimmed.equals("Artists", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ", ignoreCase = true) ||
            trimmed.equals("アーティスト", ignoreCase = true) ->
            stringResource(R.string.artists)
        trimmed.equals("Subscriptions", ignoreCase = true) ||
            trimmed.equals("Đã đăng ký", ignoreCase = true) ||
            trimmed.equals("登録チャンネル", ignoreCase = true) ->
            stringResource(R.string.subscriptions)
        trimmed.equals("Trending community playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát cộng đồng thịnh hành", ignoreCase = true) ||
            trimmed.equals("Danh sách phát thịnh hành trong cộng đồng người dùng", ignoreCase = true) ||
            trimmed.equals("急上昇のコミュニティ再生リスト", ignoreCase = true) ->
            stringResource(R.string.shelf_trending_community_playlists)
        trimmed.equals("Featured playlists for you", ignoreCase = true) ||
            trimmed.equals("Danh sách phát đề xuất cho bạn", ignoreCase = true) ||
            trimmed.equals("Danh sách phát nổi bật dành cho bạn", ignoreCase = true) ||
            trimmed.equals("おすすめの再生リスト", ignoreCase = true) ->
            stringResource(R.string.shelf_featured_playlists_for_you)
        trimmed.equals("Quick picks", ignoreCase = true) ||
            trimmed.equals("Lựa chọn nhanh", ignoreCase = true) ||
            trimmed.equals("Chọn nhanh đài phát", ignoreCase = true) ||
            trimmed.equals("クイック ミックス", ignoreCase = true) ->
            stringResource(R.string.shelf_quick_picks)
        trimmed.equals("Listen again", ignoreCase = true) ||
            trimmed.equals("Nghe lại", ignoreCase = true) ||
            trimmed.equals("もう一度聴く", ignoreCase = true) ->
            stringResource(R.string.shelf_listen_again)
        trimmed.equals("Mixed for you", ignoreCase = true) ||
            trimmed.equals("Dành riêng cho bạn", ignoreCase = true) ||
            trimmed.equals("ミックス", ignoreCase = true) ->
            stringResource(R.string.shelf_mixed_for_you)
        trimmed.startsWith("Similar to", ignoreCase = true) -> {
            val rest = trimmed.substring(10).trim()
            stringResource(R.string.shelf_similar_to, rest)
        }
        trimmed.startsWith("Tương tự như", ignoreCase = true) -> {
            val rest = trimmed.substring(12).trim()
            stringResource(R.string.shelf_similar_to, rest)
        }
        trimmed.equals("Forgotten favorites", ignoreCase = true) ||
            trimmed.equals("Giai điệu quen thuộc", ignoreCase = true) ||
            trimmed.equals("よく聴いたお気に入りの曲", ignoreCase = true) ->
            stringResource(R.string.shelf_forgotten_favorites)
        trimmed.equals("Recommended music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc đề xuất", ignoreCase = true) ||
            trimmed.equals("おすすめのミュージック ビデオ", ignoreCase = true) ->
            stringResource(R.string.shelf_recommended_music_videos)
        trimmed.equals("From your library", ignoreCase = true) ||
            trimmed.equals("Từ thư viện của bạn", ignoreCase = true) ||
            trimmed.equals("ライブラリから", ignoreCase = true) ->
            stringResource(R.string.shelf_from_your_library)
        trimmed.equals("Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
            trimmed.equals("チャート", ignoreCase = true) ->
            stringResource(R.string.shelf_charts)
        trimmed.equals("New releases", ignoreCase = true) ||
            trimmed.equals("Bản phát hành mới", ignoreCase = true) ||
            trimmed.equals("最新リリース", ignoreCase = true) ->
            stringResource(R.string.shelf_new_releases)
        trimmed.equals("Top music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc hàng đầu", ignoreCase = true) ||
            trimmed.equals("人気のミュージック ビデオ", ignoreCase = true) ->
            stringResource(R.string.shelf_top_music_videos)
        trimmed.equals("For you", ignoreCase = true) ||
            trimmed.equals("Dành cho bạn", ignoreCase = true) ||
            trimmed.equals("あなたへのおすすめ", ignoreCase = true) ->
            stringResource(R.string.shelf_for_you)
        trimmed.equals("Hits today", ignoreCase = true) ||
            trimmed.equals("Today's Hits", ignoreCase = true) ||
            trimmed.equals("Bản hit hôm nay", ignoreCase = true) ||
            trimmed.equals("今日のヒット曲", ignoreCase = true) ->
            stringResource(R.string.shelf_hits_today)
        trimmed.equals("Artists on the rise", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ đang lên", ignoreCase = true) ->
            stringResource(R.string.shelf_artists_on_the_rise)
        trimmed.equals("Concerts", ignoreCase = true) ||
            trimmed.equals("Buổi hòa nhạc", ignoreCase = true) ||
            trimmed.equals("コンサート", ignoreCase = true) ->
            stringResource(R.string.shelf_concerts)
        trimmed.startsWith("Shorts", ignoreCase = true) ||
            trimmed.equals("Shorts nổi bật", ignoreCase = true) ||
            trimmed.equals("ショート", ignoreCase = true) ->
            stringResource(R.string.shelf_shorts)
        trimmed.equals("Trending", ignoreCase = true) ||
            trimmed.equals("Thịnh hành", ignoreCase = true) ||
            trimmed.equals("急上昇", ignoreCase = true) ->
            stringResource(R.string.shelf_trending)
        else -> title
    }
}
@Composable
internal fun localizeShelfSubtitle(subtitle: String): String {
    val trimmed = subtitle.trim()
    return when {
        trimmed.equals("TOP TUNES RIGHT NOW", ignoreCase = true) ||
            trimmed.equals("Top tunes right now", ignoreCase = true) ||
            trimmed.equals("Giai điệu hàng đầu hiện nay", ignoreCase = true) ||
            trimmed.equals("注目の曲", ignoreCase = true) ->
            stringResource(R.string.shelf_top_tunes_right_now)
        trimmed.equals("From the community", ignoreCase = true) ||
            trimmed.equals("Từ cộng đồng", ignoreCase = true) ||
            trimmed.equals("コミュニティより", ignoreCase = true) ->
            stringResource(R.string.shelf_from_the_community)
        else -> subtitle
    }
}

@Composable
internal fun localizeCardSubtitle(subtitle: String): String {
    if (subtitle.isBlank()) return subtitle
    // Split on either dot, rejoin on the middle one. The app builds subtitles
    // with ` · ` and this looked for ` • `, so the split never fired and an
    // artist beside a count went through as one run — which the digit
    // rewriting then left alone, because it carries Latin letters. Rejoining
    // on the middle dot keeps the separator the reader already sees.
    val parts = subtitle.split(" • ", " · ")
    val songLabel = stringResource(R.string.shelf_song_item)
    val singleLabel = stringResource(R.string.shelf_single)
    val chartLabel = stringResource(R.string.shelf_chart)
    val playlistLabel = stringResource(R.string.playlist)
    val localizedParts = parts.map { part ->
        val trimmed = part.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        when {
            trimmed.equals("Song", ignoreCase = true) || trimmed.equals("Titre", ignoreCase = true) ||
                trimmed.equals("Bài hát", ignoreCase = true) || trimmed.equals("曲", ignoreCase = true) -> songLabel
            trimmed.equals("Single", ignoreCase = true) || trimmed.equals("Đĩa đơn", ignoreCase = true) ||
                trimmed.equals("シングル", ignoreCase = true) -> singleLabel
            trimmed.equals("Chart", ignoreCase = true) || trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
                trimmed.equals("チャート", ignoreCase = true) -> chartLabel
            trimmed.equals("Playlist", ignoreCase = true) || trimmed.equals("Danh sách phát", ignoreCase = true) ||
                trimmed.equals("再生リスト", ignoreCase = true) -> playlistLabel
            lower.endsWith(" views") || lower.endsWith(" view") || lower.endsWith(" lượt xem") ||
                lower.endsWith(" 回視聴") || lower.endsWith("回視聴") -> {
                // The count is split off from whatever it counts before it is
                // rewritten, so an artist's name beside it — `Hoodadk4` — is not
                // read as a number with a four in it.
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) {
                    stringResource(R.string.card_views_format, count.forLanguage())
                } else part
            }
            lower.endsWith(" plays") || lower.endsWith(" play") || lower.endsWith(" lượt phát") ||
                lower.endsWith(" 回再生") || lower.endsWith("回再生") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) {
                    stringResource(R.string.card_plays_format, count.forLanguage())
                } else part
            }
            lower.endsWith(" songs") || lower.endsWith(" song") || lower.endsWith(" bài hát") ||
                lower.endsWith(" 曲") || lower.endsWith("曲") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) {
                    stringResource(R.string.card_songs_format, count.forLanguage())
                } else part
            }
            else -> part
        }
    }
    // Per segment rather than on the joined line: a subtitle is an artist
    // beside a count — `Hoodadk4 · 4 plays` — and only the count is a numeral.
    // forLanguage leaves a segment carrying Latin letters alone, so the name
    // survives and the count gets rewritten without needing to be found first.
    return localizedParts.joinToString(" · ") { it.forLanguage() }
}

/**
 * A device-folder card: mesh gradient in the folder's colours with its glyph
 * on top.
 */
@Composable
private fun ServiceCard(colors: List<Color>, trackKey: String, icon: ImageVector) {
    val palette = remember { MeshPalette(colors) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        MeshGradientBackground(
            palette = palette,
            trackKey = trackKey,
            continuous = true,
            blurRadius = 24.dp,
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    /** Set on a Library playlist card that's in [AppSettings.pinnedPlaylists][com.extrumz.app.data.settings.AppSettings.pinnedPlaylists]. */
    isPinned: Boolean = false,
) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        when {
            // Telegram entries — the manage card and every scanned channel.
            // A channel with an indexed photo shows it; the rest keep the
            // shared blue mesh, which reads as "from Telegram".
            // The playlists tool. Explicitly, because the fallback drew a bare
            // title with nothing above it while the manage channels card beside
            // it carried the Telegram mesh and a mark — the pair read as one
            // finished card and one unfinished one.
            // Liked. Same shape as the other two tools so the three read as
            // one set rather than one finished card beside two bare titles.
            item.browseId == "liked" -> {
                ServiceCard(
                    colors = listOf(Color(0xFFE5397F), Color(0xFFAD1457)),
                    trackKey = "liked",
                    icon = Icons.Rounded.Favorite,
                )
            }
            item.browseId == "playlists:manage" -> {
                ServiceCard(
                    colors = listOf(Color(0xFF7C4DFF), Color(0xFF5E35B1)),
                    trackKey = "playlists:manage",
                    icon = Icons.Rounded.QueueMusic,
                )
            }
            item.browseId?.startsWith("tg:") == true -> {
                // F8: every channel card shows its photo. One call fetches
                // the full-size photo first — CARD_ART_PX is far past the
                // inline minithumbnail's budget, which is where the old
                // blur came from — and falls back to the inline bytes when
                // no full-size file exists. Both are no-ops once the row
                // holds a covers/hq_* URL, and both stay silent while a
                // scan is indexing: one fetch per channel, ever, at two in
                // flight at most.
                val chatId = item.browseId?.removePrefix("tg:chat:")?.toLongOrNull()
                LaunchedEffect(item.browseId, item.thumbnailUrl) {
                    if (chatId != null) {
                        Covers.improveChatPhoto(chatId, item.thumbnailUrl)
                    }
                }
                if (item.thumbnailUrl.isNullOrBlank()) {
                    ServiceCard(
                        colors = listOf(Color(0xFF2AABEE), Color(0xFF229ED9)),
                        trackKey = item.browseId ?: "tg",
                        icon = ExtrumzIcons.Explore,
                    )
                } else {
                    AsyncImage(
                        model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .thumbnailBorder(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
            item.browseId == "local:downloads" -> {
                val palette = remember { MeshPalette(listOf(Color(0xFF1E3C72), Color(0xFF2A5298))) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    MeshGradientBackground(
                        palette = palette,
                        trackKey = "local:downloads",
                        continuous = true,
                        blurRadius = 24.dp,
                    )
                    Icon(
                        imageVector = ExtrumzIcons.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            item.browseId == "local:all" -> ServiceCard(
                colors = listOf(Color(0xFF134E5E), Color(0xFF71B280)),
                trackKey = "local:all",
                icon = Icons.Rounded.LibraryMusic,
            )
            else -> {
                AsyncImage(
                    model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .thumbnailBorder(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPinned) {
                Icon(
                    imageVector = ExtrumzIcons.Pin,
                    contentDescription = stringResource(R.string.pinned),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val displaySubtitle = localizeCardSubtitle(item.subtitle)
        Text(
            text = displaySubtitle,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
