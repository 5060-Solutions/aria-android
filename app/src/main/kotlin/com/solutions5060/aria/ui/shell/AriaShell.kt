package com.solutions5060.aria.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import com.solutions5060.aria.R
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaSnackbarHost
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.theme.AriaIcons

/** The five tabs (DESIGN.md §5 order: Dialer · History · Contacts · Voicemail · Settings). */
enum class AriaTab(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    Dialer("dialer", R.string.ui_tab_dialer, AriaIcons.Dialer, AriaIcons.DialerFill),
    History("history", R.string.ui_tab_history, AriaIcons.History, AriaIcons.HistoryFill),
    Contacts("contacts", R.string.ui_tab_contacts, AriaIcons.Contacts, AriaIcons.ContactsFill),
    Voicemail("voicemail", R.string.ui_tab_voicemail, AriaIcons.Voicemail, AriaIcons.VoicemailFill),
    Settings("settings", R.string.ui_tab_settings, AriaIcons.Settings, AriaIcons.SettingsFill);

    companion object {
        /** The tab a route belongs to; sub-screens of Settings keep Settings selected. */
        fun forRoute(route: String?): AriaTab? =
            entries.firstOrNull { it.route == route } ?: if (route != null) Settings.takeIf { route !in topLevelRoutes } else null

        private val topLevelRoutes = entries.map { it.route }.toSet()
    }
}

/** Width from which the rail replaces the bottom bar (Material window size class "medium"). */
private val RailBreakpoint = 600.dp

/**
 * The signed-in shell: an Expressive navigation bar (a rail on wide
 * screens) with an unread badge on Voicemail, the app snackbar host, and
 * edge-to-edge insets. [content] gets the padding for the navigation bar;
 * each screen handles the status bar with its own top bar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AriaShell(
    currentRoute: String?,
    onSelectTab: (AriaTab) -> Unit,
    modifier: Modifier = Modifier,
    voicemailUnread: Int = 0,
    content: @Composable (PaddingValues) -> Unit,
) {
    val selected = AriaTab.forRoute(currentRoute)
    val snackbar = LocalSnackbarHostState.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= RailBreakpoint
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                WideNavigationRail(
                    colors = androidx.compose.material3.WideNavigationRailDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    AriaTab.entries.forEach { tab ->
                        WideNavigationRailItem(
                            selected = tab == selected,
                            onClick = { onSelectTab(tab) },
                            icon = { TabIcon(tab, tab == selected, voicemailUnread) },
                            label = { TabLabel(tab) },
                            railExpanded = false,
                            colors = WideNavigationRailItemDefaults.colors(),
                        )
                    }
                }
                Scaffold(
                    snackbarHost = { AriaSnackbarHost(snackbar) },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0),
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content(PaddingValues()) }
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0),
                snackbarHost = { AriaSnackbarHost(snackbar) },
                bottomBar = {
                    ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        AriaTab.entries.forEach { tab ->
                            ShortNavigationBarItem(
                                selected = tab == selected,
                                onClick = { onSelectTab(tab) },
                                icon = { TabIcon(tab, tab == selected, voicemailUnread) },
                                label = { TabLabel(tab) },
                                colors = ShortNavigationBarItemDefaults.colors(
                                    selectedIndicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedTextColorTopIconPosition = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize()) { content(padding) }
            }
        }
    }
}

@Composable
private fun TabIcon(tab: AriaTab, selected: Boolean, voicemailUnread: Int) {
    // Filled when selected: a short cross-fade, the Expressive label/indicator motion does the rest.
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val icon = @Composable {
        AnimatedContent(
            targetState = selected,
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "tabIcon",
        ) { isSelected ->
            AriaIcon(if (isSelected) tab.selectedIcon else tab.icon, contentDescription = null)
        }
    }
    if (tab == AriaTab.Voicemail && voicemailUnread > 0) {
        val badgeDescription = pluralStringResource(R.plurals.ui_voicemail_unread, voicemailUnread, voicemailUnread)
        BadgedBox(
            badge = {
                Badge(Modifier.semantics { contentDescription = badgeDescription }) {
                    Text(if (voicemailUnread > 99) "99+" else voicemailUnread.toString())
                }
            },
        ) { icon() }
    } else {
        icon()
    }
}

/** Tab labels shrink to fit at large font scales instead of clipping. */
@Composable
private fun TabLabel(tab: AriaTab) {
    val style = MaterialTheme.typography.labelMedium
    // Five labels share the width: grow with the font scale only so far, then shrink to fit.
    com.solutions5060.aria.ui.components.CappedFontScale(max = 1.3f) {
        Text(
            stringResource(tab.label),
            style = style,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize),
        )
    }
}
