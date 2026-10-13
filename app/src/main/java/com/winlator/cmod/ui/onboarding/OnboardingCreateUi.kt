package com.winlator.cmod.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What the first container is made from; the start screen offers one big button for each. */
internal enum class ContainerStart { Windows, Linux, Game, Import }

private class StartOption(
    val start: ContainerStart,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val bestWhen: String,
    val accent: @Composable () -> Color
)

private val startOptions = listOf(
    StartOption(
        ContainerStart.Windows, Icons.Outlined.DesktopWindows,
        "Windows game",
        "A Windows environment: Wine or Proton with DXVK and VKD3D, and Box64 or FEX to run x86 code on the phone.",
        "Most PC games and .exe programs. The best-supported choice, so start here if unsure."
    ) { MaterialTheme.colorScheme.primary },
    StartOption(
        ContainerStart.Linux, Icons.Outlined.Terminal,
        "Linux system",
        "A real Linux (Arch Linux ARM) in a sandbox, with its own Vulkan driver and x86 games through Box64 or FEX.",
        "Games that have a Linux build, such as BeamNG.drive. Steam has its own place in Settings."
    ) { MaterialTheme.colorScheme.tertiary },
    StartOption(
        ContainerStart.Game, Icons.Outlined.SportsEsports,
        "Set up for a game",
        "A ready-made container tuned for one game, taken from the community list. What it needs is installed for you, after you confirm.",
        "Your game is on the list. The quickest way to a setup that is known to work."
    ) { MaterialTheme.colorScheme.secondary },
    StartOption(
        ContainerStart.Import, Icons.Outlined.Download,
        "Import from a file",
        "Restores a container from a .wcfg profile that was exported on another device or shared by someone.",
        "You already have a profile file and want the same settings here."
    ) { MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) }
)

/**
 * The first screen after the theme: the ways to make a container, each a large button that says what it is and when to
 * choose it. The gear at the top right opens the app's settings.
 */
@Composable
internal fun OnboardingCreateScreen(
    onChoose: (ContainerStart) -> Unit,
    onSettings: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    title: String = "Create your first container",
    subtitle: String = "A container is a separate environment for games, with its own settings. Pick how to start; " +
        "you can make more later.",
    onBack: (() -> Unit)? = null
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 40.dp else 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(Modifier.widthIn(max = 1000.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (onBack != null) {
                            IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Outlined.ArrowBack, "Back", modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Column(Modifier.weight(1f).padding(top = 6.dp, end = 12.dp)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                subtitle,
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onSettings != null) {
                            IconButton(onClick = onSettings, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Outlined.Settings, "Settings", modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    if (wide) {
                        startOptions.chunked(2).forEach { pair ->
                            Row(
                                Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                pair.forEach { option ->
                                    StartCard(option, Modifier.weight(1f).fillMaxSize()) { onChoose(option.start) }
                                }
                            }
                        }
                    } else {
                        startOptions.forEach { option ->
                            StartCard(option, Modifier.fillMaxWidth().padding(bottom = 14.dp)) { onChoose(option.start) }
                        }
                    }
                    if (onSkip != null) {
                        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Skip for now, I'll set up later")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartCard(option: StartOption, modifier: Modifier, onClick: () -> Unit) {
    val accent = option.accent()
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.04f), Color.Transparent)
                )
            )
        ) {
            Column(Modifier.padding(22.dp).heightIn(min = 170.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(accent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(option.icon, null, tint = accent, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            option.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(Icons.Outlined.ArrowForward, null, tint = accent)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        option.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = accent, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        option.bestWhen,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
