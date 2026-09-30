package com.music.bitchord.ui.components

import com.music.bitchord.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * One free-text field — an activity name, a button label.
 *
 * [message] is where the caller explains the field, including which `{...}`
 * variables it accepts, since that is the only place a user would find out.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TextValueAlert(
    hazeState: HazeState,
    title: String,
    message: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** False greys Save out — for a field that isn't worth saving empty. */
    saveEnabled: Boolean = true,
    /** A third action between Save and Cancel, for a value that can be cleared. */
    onRemove: (() -> Unit)? = null,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.save),
            emphasised = true,
            onClick = onSave,
            enabled = saveEnabled,
        )
        if (onRemove != null) {
            AlertRule()
            AlertAction(label = stringResource(R.string.remove), emphasised = false, onClick = onRemove)
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/**
 * One field of a server editor: the value, its change handler, the hint, and
 * how the keyboard behaves on it. The last field always gets Done-to-save
 * while the rest advance with Next, which is what every editor below already
 * did by hand.
 */
data class EditorField(
    val value: String,
    val onChange: (String) -> Unit,
    val placeholder: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val isPassword: Boolean = false,
)

/**
 * A two-action confirmation using the same frosted UIAlertController shell as
 * the other alerts in this file. Kept here so warnings opened from settings do
 * not fall back to a visually unrelated Material dialog.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ConfirmationAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        AlertRule()
        AlertAction(label = confirmLabel, emphasised = true, onClick = onConfirm)
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/**
 * Single-select list, ticked like [LyricsSourcesDialog] rather than with radio
 * buttons — same reasoning: a column of Material radios would be the one
 * Material thing left on an otherwise Apple-shaped alert.
 *
 * Picking commits immediately and closes, so there is no Save action to reach
 * for; Cancel is the only one, and it's the dismiss.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun <T> ChoiceAlert(
    hazeState: HazeState,
    title: String,
    message: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
        options.forEach { option ->
            AlertRule()
            ChoiceRow(
                label = label(option),
                detail = detail(option),
                checked = option == selected,
                onClick = { onSelect(option) },
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    detail: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** The scrim + frosted card frame shared by every UIAlertController-style dialog. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun AlertScaffold(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            content = content,
        )
    }
}
