package com.vpnapp.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** Explicit indication overload supports the current Material theme's legacy ripple on newer Foundation. */
@Composable
fun Modifier.appClickable(enabled: Boolean = true, onClickLabel: String? = null, onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = LocalIndication.current,
        enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
