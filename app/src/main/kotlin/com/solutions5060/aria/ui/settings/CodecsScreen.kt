package com.solutions5060.aria.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.solutions5060.aria.R
import com.solutions5060.aria.call.CodecOrder
import com.solutions5060.aria.call.CodecPreferences
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Spacing
import uniffi.aria_mobile.AudioCodec

object CodecsTags {
    fun row(codec: AudioCodec) = "codec_${codec.name}"
    fun up(codec: AudioCodec) = "codec_up_${codec.name}"
}

private fun description(codec: AudioCodec): Int = when (codec) {
    AudioCodec.OPUS -> R.string.codec_opus_desc
    AudioCodec.PCMU -> R.string.codec_pcmu_desc
    AudioCodec.PCMA -> R.string.codec_pcma_desc
    AudioCodec.G729 -> R.string.codec_g729_desc
}

/**
 * Codec preferences: the order Aria offers codecs in, and which are offered
 * at all. Applied to the engine (`set_codec_preferences`) at once and on
 * every start; it takes effect from the next call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodecsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val list by CodecPreferences.state.collectAsState()
    LaunchedEffect(Unit) { CodecPreferences.load(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.codecs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        AriaIcon(AriaIcons.Back, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
        ) {
            Text(
                stringResource(R.string.codecs_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
            list.forEachIndexed { index, setting ->
                val name = CodecPreferences.label(setting.codec)
                val upLabel = stringResource(R.string.codecs_move_up, name)
                val downLabel = stringResource(R.string.codecs_move_down, name)
                AriaListItem(
                    headline = name,
                    supporting = stringResource(description(setting.codec)),
                    modifier = Modifier.testTag(CodecsTags.row(setting.codec)),
                    trailing = {
                        IconButton(
                            onClick = { CodecPreferences.save(context, CodecOrder.move(list, index, -1)) },
                            enabled = index > 0,
                            modifier = Modifier.testTag(CodecsTags.up(setting.codec)),
                        ) { AriaIcon(AriaIcons.ExpandLess, contentDescription = upLabel) }
                        IconButton(
                            onClick = { CodecPreferences.save(context, CodecOrder.move(list, index, 1)) },
                            enabled = index < list.lastIndex,
                        ) { AriaIcon(AriaIcons.ExpandMore, contentDescription = downLabel) }
                        Switch(
                            checked = setting.enabled,
                            onCheckedChange = { CodecPreferences.save(context, CodecOrder.setEnabled(list, setting.codec, it)) },
                            modifier = Modifier.semantics { contentDescription = name },
                        )
                    },
                )
            }
            Text(
                stringResource(R.string.codecs_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xl),
            )
        }
    }
}
