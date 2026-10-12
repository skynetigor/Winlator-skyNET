package com.winlator.cmod.ui.shortcut

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.linux.LinuxEmulator
import com.winlator.cmod.linux.LinuxLaunchOptions
import com.winlator.cmod.lsfg.LsfgManager
import com.winlator.cmod.ui.container.EditorCategory
import com.winlator.cmod.ui.container.GroupedSettings
import com.winlator.cmod.ui.container.ResolutionSetting
import com.winlator.cmod.ui.container.isValidScreenSize
import com.winlator.cmod.ui.container.UPSCALER_MODES
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider

private val FLOW_SCALES = listOf("0.25", "0.50", "0.75", "0.80", "1.00")
private const val FIT = "fullscreenStretched"
private const val UPSCALER = "graphicsFilterMode"
private const val SIZE = "screenSize"

/**
 * The settings of a shortcut that starts a Linux program. A Wine shortcut has a page of Wine, DXVK and drive options
 * that mean nothing here (and read fields a Linux container does not have). They are grouped as in the container's
 * editor. What a shortcut leaves at "Container default" follows its container; anything else is its own and applies to
 * the sessions started from it.
 */
@Composable
internal fun LinuxShortcutEditor(shortcut: Shortcut, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = shortcut.container
    var options by remember { mutableStateOf(shortcut.getExtra(LinuxLaunchOptions.EXTRA)) }
    val parsed = remember(options) { LinuxLaunchOptions.parse(options) }
    var size by remember { mutableStateOf(shortcut.getExtra(SIZE)) }
    var fit by remember { mutableStateOf(shortcut.getExtra(FIT)) }
    var upscaler by remember { mutableStateOf(shortcut.getExtra(UPSCALER)) }
    var multiplier by remember { mutableStateOf(shortcut.getExtra(LsfgManager.EXTRA_MULTIPLIER)) }
    var flowScale by remember { mutableStateOf(shortcut.getExtra(LsfgManager.EXTRA_FLOW_SCALE)) }

    val emulator = remember {
        when (LinuxEmulator.choice(container)) {
            LinuxEmulator.NONE -> "none (ARM64 programs only)"
            else -> LinuxEmulator.resolve(context, container)?.name
                ?: "${LinuxEmulator.choice(container)} (not installed)"
        }
    }
    val hasDll = remember { LsfgManager.hasDll(context) }
    val sizeValid = size.isEmpty() || isValidScreenSize(size.trim())

    // What each setting is when the shortcut leaves it alone.
    val containerSize = container.screenSize
    val containerFit = if (container.isFullscreenStretched) "fit" else "1:1"
    val containerUpscaler = UPSCALER_MODES.firstOrNull { it.first == container.getExtra(UPSCALER) }?.second?.substringBefore(" (")
        ?: "Off"
    val containerMultiplier = LsfgManager.getMultiplier(container).let { if (it >= 2) "${it}x" else "Off" }
    val containerFlow = "%.2f".format(java.util.Locale.US, LsfgManager.getFlowScale(context, container))

    fun save() {
        // One line: the shortcut file keeps each extra on a line of its own.
        val text = options.replace('\n', ' ').trim()
        shortcut.putExtra(LinuxLaunchOptions.EXTRA, text.ifEmpty { null })
        shortcut.putExtra(SIZE, size.trim().ifEmpty { null })
        shortcut.putExtra(FIT, fit.ifEmpty { null })
        shortcut.putExtra(UPSCALER, upscaler.ifEmpty { null })
        shortcut.putExtra(LsfgManager.EXTRA_MULTIPLIER, multiplier.ifEmpty { null })
        shortcut.putExtra(LsfgManager.EXTRA_FLOW_SCALE, flowScale.ifEmpty { null })
        shortcut.saveData()
        onClose()
    }

    val graphicsSummary = listOf(
        if (size.isEmpty()) "container size" else if (size.trim() == "native") "native size" else size.trim(),
        when (fit) { "1" -> "fit"; "0" -> "1:1"; else -> "container scaling" },
        UPSCALER_MODES.firstOrNull { it.first == upscaler && it.first != "0" }?.second?.substringBefore(" (")
            ?: if (upscaler == "0") "no upscaler" else "container upscaler"
    ).joinToString(" · ")
    val frameGenerationSummary = when (multiplier) {
        "" -> "Container default ($containerMultiplier)"
        "0" -> "Off"
        else -> "${multiplier}x" + if (flowScale.isNotEmpty()) " · flow $flowScale" else ""
    }

    val categories = listOf(
        EditorCategory("launch", "Launch options", options.ifBlank { "None" }, false) {
            OutlinedTextField(
                value = options,
                onValueChange = { options = it },
                label = { Text("Launch options") },
                placeholder = { Text("NAME=value %command% -windowed") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Like Steam's: variables before %command%, the program's arguments after it " +
                    "(MANGOHUD=1 BOX64_LOG=1 %command% -windowed -width 1280). A wrapper may stand between them " +
                    "(taskset -c 4-7 %command%). Without %command%, leading NAME=value words are variables and the " +
                    "rest are arguments. Quote a word that holds a space.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (options.isNotBlank()) {
                SettingsCard {
                    PreviewRow("Variables", parsed.env.entries.joinToString("\n") { "${it.key}=${it.value}" })
                    SettingsDivider()
                    PreviewRow("Wrapper", parsed.wrapper.joinToString(" "))
                    SettingsDivider()
                    PreviewRow("Arguments", parsed.args.joinToString("\n"))
                }
            }
        },
        EditorCategory("graphics", "Graphics", graphicsSummary, !sizeValid) {
            SettingsCard {
                ResolutionSetting(size, "Container default ($containerSize)") { size = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Scaling",
                    fit,
                    linkedMapOf("" to "Container default ($containerFit)", "1" to "Fit to screen (keep proportions)", "0" to "Original size (1:1)")
                ) { fit = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Upscaler (needs native output and Fit to screen)",
                    upscaler,
                    LinkedHashMap<String, String>().apply {
                        put("", "Container default ($containerUpscaler)")
                        UPSCALER_MODES.forEach { put(it.first, it.second) }
                    }
                ) { upscaler = it }
            }
        },
        EditorCategory("framegen", "Frame generation", frameGenerationSummary, false) {
            SettingsCard {
                SettingMappedChoice(
                    "Multiplier",
                    multiplier,
                    linkedMapOf("" to "Container default ($containerMultiplier)", "0" to "Off", "2" to "2x", "3" to "3x", "4" to "4x")
                ) { multiplier = it }
                if (multiplier.isNotEmpty() && multiplier != "0" && !hasDll) {
                    Text(
                        "Frame generation stays off until you import Lossless.dll in Settings > Components > Frame Generation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
                SettingsDivider()
                SettingMappedChoice(
                    "Flow scale",
                    flowScale,
                    LinkedHashMap<String, String>().apply {
                        put("", "Container default ($containerFlow)")
                        FLOW_SCALES.forEach { put(it, it) }
                    }
                ) { flowScale = it }
            }
            Text(
                "Works with native output only (the container's \"Software Vulkan output\" off). Changes made in the " +
                    "game's sidebar are kept on the shortcut once it has its own multiplier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        EditorCategory("program", "Program", shortcut.path, false) {
            SettingsCard {
                PreviewRow("Path", shortcut.path)
                SettingsDivider()
                PreviewRow("Container", container.name)
                SettingsDivider()
                PreviewRow("Runs under", "Linux · $emulator")
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shortcut.name) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            GroupedSettings(categories) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End)
                ) {
                    TextButton(onClick = onClose) { Text("Cancel") }
                    Button(onClick = { save() }, enabled = sizeValid) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}
