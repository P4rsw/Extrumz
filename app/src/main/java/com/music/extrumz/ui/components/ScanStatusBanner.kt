package com.extrumz.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.extrumz.app.R
import com.extrumz.app.data.telegram.ScanProgress
import com.extrumz.app.data.telegram.ScanStage
import com.extrumz.app.ui.util.ProvideLocaleDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * What the scan is doing, told honestly.
 *
 * The screen used to show an indeterminate bar and the text "Attempt N",
 * where N came from a dialog poll that finishes in the first second and then
 * never changes — so a working scan looked stuck for its whole duration while
 * the engine was publishing a real fraction the UI never read.
 *
 * The bar is determinate whenever a fraction exists, and one-way: it takes the
 * larger of what it has shown and what it is being told, so a late discovery
 * that grows the denominator cannot drag it backwards. While the fraction is
 * still unknown (a fresh install, where the shelf is empty and discovery has
 * not answered yet) it falls back to an indeterminate bar rather than showing
 * a number it cannot stand behind.
 */
@Composable
fun ScanStatusBanner(
    scan: ScanProgress,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    // Monotonic on the way to the screen, because the source of truth is a
    // denominator that can still grow.
    var ceiling = remember { 0f }
    val raw = scan.workFraction
    if (raw > ceiling) ceiling = raw
    val shown by animateFloatAsState(ceiling, label = "scanFraction")

    val determinate = scan.totalChats > 0 && scan.stage == ScanStage.INDEXING_TRACKS

    // Persian reads the other way. Without this the banner kept its English
    // skeleton under Persian words: the stage line stayed pinned to the left
    // with its ellipsis trailing off the wrong edge, and the button that
    // undoes a failed scan sat on the right — the side an RTL reader finishes
    // on, not the one they start from. Scoped to this banner, inert in English.
    ProvideLocaleDirection {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The palette, not Material's default. This screen is deliberately
        // monochrome — artwork is meant to be the only colour on it — and the
        // default indicator shipped its own purple-grey track and lavender
        // fill, which read as a foreign element and, being close in value to
        // each other on black, were barely distinguishable. primary is the
        // theme's own foreground (white on dark, black on light) and outline
        // is its own hairline colour, so the empty part of the bar becomes a
        // real, visible line instead of a tinted smear.
        val fill = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.outline
        if (determinate) {
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxWidth(),
                color = fill,
                trackColor = track,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = fill,
                trackColor = track,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stageLabel(scan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The stage is also where an error surfaces, so the way out lives
            // beside it rather than nowhere at all.
            when {
                scan.lastError != null && onRetry != null ->
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.scan_retry))
                    }
                scan.lastError != null && onDismiss != null ->
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.scan_dismiss))
                    }
                !scan.isRunning && scan.tracksIndexed > 0 && onDismiss != null ->
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.scan_dismiss))
                    }
            }
        }

        // A live line while a page is in flight and taking too long. The
        // scan cannot report a duration for a call it is still inside, so the
        // wait is derived here from the start time it published — which is the
        // only way the banner can say anything at all during a stall.
        if (scan.pageStartedAt != null) {
            var elapsed by remember(scan.pageStartedAt) { mutableStateOf(0L) }
            LaunchedEffect(scan.pageStartedAt) {
                val started = scan.pageStartedAt ?: return@LaunchedEffect
                while (true) {
                    elapsed = System.currentTimeMillis() - started
                    delay(1_000)
                }
            }
            if (elapsed > WAITING_VISIBLE_MS) {
                Text(
                    text = stringResource(
                        R.string.scan_waiting_for_telegram,
                        (elapsed / 1_000L).toInt(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        val detail = stageDetail(scan)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
}

@Composable
private fun stageLabel(scan: ScanProgress): String = when (scan.stage) {
    ScanStage.SYNCING_DIALOGS ->
        if (scan.detectedChats > 0) {
            stringResource(R.string.scan_stage_dialogs_count, scan.detectedChats)
        } else {
            stringResource(R.string.scan_stage_dialogs)
        }
    ScanStage.RESOLVING_SOURCES ->
        if (scan.resolvedChats > 0) {
            stringResource(R.string.scan_stage_sources_count, scan.resolvedChats)
        } else {
            stringResource(R.string.scan_stage_sources)
        }
    ScanStage.INDEXING_TRACKS -> stringResource(R.string.scan_stage_indexing)
    ScanStage.FINISHED -> stringResource(R.string.scan_stage_finished, scan.tracksIndexed)
    ScanStage.IDLE -> stringResource(R.string.scan_stage_dialogs)
}

@Composable
private fun stageDetail(scan: ScanProgress): String? = when (scan.stage) {
    ScanStage.INDEXING_TRACKS -> stringResource(
        R.string.scan_stage_indexing_detail,
        scan.chatsScanned,
        scan.totalChats,
        scan.tracksIndexed,
    )
    ScanStage.FINISHED -> null
    else -> null
}

/**
 * How long a page must be in flight before the banner says anything about it.
 *
 * Below this the scan is simply working — 179ms is a healthy median on this
 * account — and a line that appears on every page would be noise. The stalls
 * run to twenty seconds, so anything from a second up is unambiguous.
 */
private const val WAITING_VISIBLE_MS = 2_000L
