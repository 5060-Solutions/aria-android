package com.solutions5060.aria.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.hold.TranscriptLine
import com.solutions5060.aria.ui.theme.Spacing

/**
 * What the assistant heard while it waited, shown with the "They're back"
 * ring. Absent (not an error) until there is a line to show.
 */
@Composable
internal fun HoldReturnTranscript(lines: List<TranscriptLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(HoldTags.TRANSCRIPT),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                stringResource(R.string.ui_hold_transcript_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            lines.forEach { line ->
                val speaker = stringResource(
                    if (line.speaker == "assistant") R.string.ui_hold_speaker_assistant else R.string.ui_hold_speaker_caller,
                )
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(speaker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
