package com.winlator.cmod.ui.container

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Container
import com.winlator.cmod.lsfg.LsfgManager
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import java.util.Locale

private val multiplierEntries = listOf("Off", "2x", "3x", "4x")
private val flowScaleEntries = listOf("0.25", "0.50", "0.75", "0.80", "1.00")
private val presentModeEntries = listOf(LsfgManager.PRESENT_MODE_MAILBOX, LsfgManager.PRESENT_MODE_FIFO)
private val performanceEntries = listOf("On", "Off")

internal fun formatFlowScale(value: Float) = String.format(Locale.US, "%.2f", value)

private fun multiplierLabel(multiplier: Int) = if (multiplier >= 2) "${multiplier}x" else "Off"
private fun onOff(value: Boolean) = if (value) "On" else "Off"

/**
 * Per-container frame generation: the multiplier ("Off" by default) and optional overrides of the
 * app-wide defaults. Lossless.dll itself is imported once, under Settings > Components.
 */
@Composable
internal fun ContainerLsfgCard(container: Container) {
    val context = LocalContext.current

    val defaultFlow = remember { "Default (${formatFlowScale(LsfgManager.getDefaultFlowScale(context))})" }
    val defaultPerformance = remember { "Default (${onOff(LsfgManager.getDefaultPerformanceMode(context))})" }
    val defaultPresent = remember { "Default (${LsfgManager.getDefaultPresentMode(context)})" }

    var multiplier by remember { mutableStateOf(multiplierLabel(LsfgManager.getMultiplier(container))) }
    var flowScale by remember {
        mutableStateOf(LsfgManager.getFlowScaleOverride(container)?.let(::formatFlowScale) ?: defaultFlow)
    }
    var performanceMode by remember {
        mutableStateOf(LsfgManager.getPerformanceModeOverride(container)?.let(::onOff) ?: defaultPerformance)
    }
    var presentMode by remember { mutableStateOf(LsfgManager.getPresentModeOverride(container) ?: defaultPresent) }
    val hasDll = remember { LsfgManager.hasDll(context) }

    SettingsCard {
        Text(
            "Frame Generation (LSFG)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
        SettingsDivider()
        SettingChoice("Multiplier", multiplier, multiplierEntries) {
            multiplier = it
            LsfgManager.setMultiplier(container, if (it == "Off") LsfgManager.MULTIPLIER_OFF else it.removeSuffix("x").toInt())
        }
        if (multiplier != "Off" && !hasDll) {
            Text(
                "Frame generation stays off until you import Lossless.dll in Settings > Components > Frame Generation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
        SettingsDivider()
        SettingChoice("Flow Scale", flowScale, listOf(defaultFlow) + flowScaleEntries) {
            flowScale = it
            LsfgManager.setFlowScale(container, if (it == defaultFlow) null else it.toFloat())
        }
        SettingsDivider()
        SettingChoice("Performance Mode", performanceMode, listOf(defaultPerformance) + performanceEntries) {
            performanceMode = it
            LsfgManager.setPerformanceMode(container, if (it == defaultPerformance) null else it == "On")
        }
        SettingsDivider()
        SettingChoice("Present Mode", presentMode, listOf(defaultPresent) + presentModeEntries) {
            presentMode = it
            LsfgManager.setPresentMode(container, if (it == defaultPresent) null else it)
        }
        Text(
            "Uses the lsfg-vk Vulkan layer, so it applies to DXVK, VKD3D, Vulkan and Zink games. Defaults are set in Settings > Components > Frame Generation. Changes take effect on the next launch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** Shown while creating a container: there is no container folder yet to hold Lossless.dll. */
@Composable
internal fun ContainerLsfgUnavailableCard() {
    SettingsCard {
        Text(
            "Frame Generation (LSFG)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
        SettingsDivider()
        Text(
            "Create the container first, then edit it to choose a frame generation multiplier.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
