package com.pdforge.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared action-button components for the whole app.
 *
 * WHY THIS EXISTS: buttons rendered with the raw Material3 defaults wrap and
 * break words when their slot is narrow ("Delete" showing as "Dele / te",
 * "Organize" as "Organi / ze"). Material3's default content padding is 24dp per
 * side, which on a four-button row leaves too little room for the label, so the
 * platform line breaker splits the word.
 *
 * Every action button in the app must go through these components (or at least
 * through [ButtonLabel] for its text) so the rule lives in one place:
 *  - compact horizontal padding so labels fit on narrow rows,
 *  - a real minimum width so two-letter labels never collapse,
 *  - single-line labels that ellipsize instead of breaking mid-word,
 *  - a text size that is NOT reduced to force a fit.
 *
 * If a label still cannot fit, redesign that row (fewer buttons per row) instead
 * of shrinking the text or allowing a word to split.
 */
val AppButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** Default minimum width for an action button — enough for "Up"/"Down", never a sliver. */
val AppButtonMinWidth: Dp = 72.dp

/**
 * The one place a button label is rendered. [icon] is optional; when present the
 * icon sits before the text at a fixed small size.
 */
@Composable
fun ButtonLabel(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
    }
    Text(
        text = text,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

/** Filled action button. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    minWidth: Dp = AppButtonMinWidth
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = minWidth),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = AppButtonPadding
    ) {
        ButtonLabel(text = text, icon = icon)
    }
}

/** Outlined action button (used for secondary page-management actions). */
@Composable
fun AppOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    minWidth: Dp = AppButtonMinWidth
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = minWidth),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = AppButtonPadding
    ) {
        ButtonLabel(text = text, icon = icon)
    }
}

/** Filled tonal action button. */
@Composable
fun AppFilledTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    minWidth: Dp = AppButtonMinWidth
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = minWidth),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = AppButtonPadding
    ) {
        ButtonLabel(text = text, icon = icon)
    }
}

/**
 * Text button for dialogs and inline actions ("Cancel", "Rename"). Text buttons
 * never take a big minimum width, but their label still must not break a word.
 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minWidth: Dp = 0.dp,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = minWidth),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (content != null) content() else ButtonLabel(text = text)
    }
}
