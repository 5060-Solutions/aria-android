package com.solutions5060.aria.ui.dialer

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solutions5060.aria.R
import com.solutions5060.aria.ui.theme.AriaGreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    onCall: (String) -> Unit,
    displayName: String = "",
    extensionNumber: String = "",
    domain: String = "",
    isConnected: Boolean = false,
    callError: String? = null,
    onDismissError: () -> Unit = {},
) {
    var number by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header: Logo + identity + status – tighter, unified row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = "Aria",
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (displayName.isNotEmpty()) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (extensionNumber.isNotEmpty()) {
                    Text(
                        text = if (domain.isNotEmpty()) "Ext $extensionNumber · $domain" else "Ext $extensionNumber",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Status chip – directly associated with identity
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isConnected) AriaGreen.copy(alpha = 0.14f) else MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) AriaGreen else MaterialTheme.colorScheme.error)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        if (isConnected) "Online" else "Offline",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isConnected) AriaGreen else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Error banner
        AnimatedVisibility(
            visible = callError != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            callError?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    onClick = onDismissError,
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // Flexible spacer – reduced to minimize dead space
        Spacer(modifier = Modifier.weight(0.6f))

        // Number display – visible input area with better contrast
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = number.ifEmpty { "Enter number" },
                    fontSize = when {
                        number.length > 16 -> 24.sp
                        number.length > 12 -> 28.sp
                        else -> 32.sp
                    },
                    fontWeight = if (number.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                    color = if (number.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    letterSpacing = 2.sp,
                )

                if (number.isNotEmpty()) {
                    IconButton(
                        onClick = { number = number.dropLast(1) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        // Paste from clipboard hint when field is empty and clipboard has digits
        if (number.isEmpty()) {
            val clipText = clipboardManager.getText()?.text ?: ""
            val clipDigits = clipText.filter { it.isDigit() || it == '+' }
            if (clipDigits.length >= 3) {
                TextButton(
                    onClick = { number = clipDigits },
                    modifier = Modifier.padding(top = 2.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Paste ${clipDigits.take(12)}${if (clipDigits.length > 12) "…" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
        } else {
            // Clear all
            TextButton(
                onClick = { number = "" },
                modifier = Modifier.padding(top = 2.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Dial pad – tighter spacing for better density
        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to ""),
        )

        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { (digit, letters) ->
                    DialPadKey(
                        digit = digit,
                        letters = letters,
                        onClick = {
                            number += digit
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onLongClick = if (digit == "0") {
                            {
                                number += "+"
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        } else null,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Call button – tighter relationship with keypad
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(AriaGreen.copy(alpha = 0.12f))
            )
            FloatingActionButton(
                onClick = {
                    if (number.isNotEmpty()) {
                        onCall(number)
                    }
                },
                containerColor = AriaGreen,
                shape = CircleShape,
                modifier = Modifier.size(66.dp),
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 2.dp,
                ),
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Call",
                    modifier = Modifier.size(28.dp),
                    tint = Color.White,
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.2f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialPadKey(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .size(76.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = CircleShape,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        tonalElevation = 1.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = digit,
                fontSize = 28.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}
