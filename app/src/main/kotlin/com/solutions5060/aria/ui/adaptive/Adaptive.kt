package com.solutions5060.aria.ui.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.foundation.layout.Box
import androidx.window.core.layout.WindowSizeClass

/** The window's width class (Material window size classes: under 600 dp, 600 to 839, 840 and up). */
enum class WidthClass { Compact, Medium, Expanded }

@Composable
fun rememberWidthClass(): WidthClass {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WidthClass.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WidthClass.Medium
        else -> WidthClass.Compact
    }
}

/** Medium and expanded windows: a navigation rail and list-detail screens. */
@Composable
fun isWideWindow(): Boolean = rememberWidthClass() != WidthClass.Compact

/**
 * List on the left, detail on the right, side by side. The list takes 40 % of
 * the width, between 300 and 420 dp.
 */
@Composable
fun ListDetail(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = (maxWidth * 0.4f).coerceIn(300.dp, 420.dp)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight()) { list() }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        }
    }
}
