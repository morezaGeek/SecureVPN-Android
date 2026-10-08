package com.vpnapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/** Compact forms retain system font scaling and full-size touch targets. */
@Composable
internal fun CompactSettingsTypography(content: @Composable () -> Unit) {
    val current = MaterialTheme.typography
    MaterialTheme(typography = current.copy(
        headlineSmall = current.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = current.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = current.titleMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodyLarge = current.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = current.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = current.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = current.labelLarge.copy(fontSize = 13.sp, lineHeight = 19.sp)
    ), content = content)
}
