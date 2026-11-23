package com.solutions5060.aria.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Spacing

/**
 * The app-wide snackbar host state, provided by the shell, so any screen can
 * report a result ("Recording started", a failed action) in one place.
 */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/** Snackbars in the Aria style: inverse surface, content-row radius, primary action. */
@Composable
fun AriaSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = Spacing.screen),
            shape = AriaShapes.contentRow,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
        )
    }
}
