package com.solutions5060.aria.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import kotlinx.coroutines.delay

/** Size of the small brand mark at the dialer's header. */
val HeaderMarkSize = 28.dp

/**
 * The brand mark at the dialer's leading edge: where the splash icon lands,
 * and a quiet anchor afterwards. Decorative. It reports where it is so the
 * exit transition can aim at it, and stays hidden while the icon travels.
 */
@Composable
fun HeaderMark(modifier: Modifier = Modifier) {
    // A safety net: the mark always shows even if no exit animation runs.
    LaunchedEffect(Unit) {
        delay(SplashHandoff.EXIT_MS + 1_500)
        SplashHandoff.markHidden = false
    }
    DisposableEffect(Unit) { onDispose { SplashHandoff.headerMark = null } }
    Image(
        painter = painterResource(R.drawable.ic_aria_logo),
        contentDescription = null,
        modifier = modifier
            .size(HeaderMarkSize)
            .alpha(if (SplashHandoff.markHidden) 0f else 1f)
            .onGloballyPositioned { SplashHandoff.headerMark = it.boundsInWindow() },
    )
}
