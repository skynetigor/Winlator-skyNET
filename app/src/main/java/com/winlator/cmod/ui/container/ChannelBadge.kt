package com.winlator.cmod.ui.container

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Stable" / "Prerelease" badge for registry entries (components, drivers and game configs).
 * Draws nothing when the registry gives no channel.
 */
@Composable
fun ChannelBadge(channel: String?, modifier: Modifier = Modifier) {
    val (label, color) = when (channel) {
        "stable" -> "Stable" to Color(0xFF43A047)
        "prerelease" -> "Prerelease" to Color(0xFFFB8C00)
        else -> return
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1
        )
    }
}
