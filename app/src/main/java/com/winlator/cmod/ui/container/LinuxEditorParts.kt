package com.winlator.cmod.ui.container

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider

// What the editors of a Linux container and of a Linux shortcut have in common: the groups, the way they are laid out,
// and the resolution and upscaler choices.

internal val SIZE_PATTERN = Regex("^\\d{3,5}x\\d{3,5}$")

/** The screen size that follows the device's display: worked out when the session starts. */
internal const val NATIVE_RESOLUTION = "native"

/** A size the editors accept: "WxH" or [NATIVE_RESOLUTION]. */
internal fun isValidScreenSize(value: String): Boolean = value == NATIVE_RESOLUTION || SIZE_PATTERN.matches(value)

/** The screen sizes offered, smallest first; "Custom" after them asks for a width and a height. */
internal val RESOLUTION_PRESETS = listOf(
    "640x360" to "640x360 (16:9)",
    "780x360" to "780x360 (19.5:9)",
    "640x480" to "640x480 (4:3)",
    "800x600" to "800x600 (4:3)",
    "854x480" to "854x480 (16:9)",
    "960x544" to "960x544 (16:9)",
    "1024x768" to "1024x768 (4:3)",
    "1280x576" to "1280x576 (20:9)",
    "1280x720" to "1280x720 (16:9)",
    "1280x800" to "1280x800 (16:10)",
    "1280x960" to "1280x960 (4:3)",
    "1280x1024" to "1280x1024 (5:4)",
    "1366x768" to "1366x768 (16:9)",
    "1440x900" to "1440x900 (16:10)",
    "1600x720" to "1600x720 (20:9)",
    "1600x900" to "1600x900 (16:9)",
    "1600x1200" to "1600x1200 (4:3)",
    "1920x1080" to "1920x1080 (16:9)",
    "1920x1200" to "1920x1200 (16:10)",
    "2560x1440" to "2560x1440 (16:9)"
)

// The renderer's filter modes (see the sidebar): 0 is a plain scale, 2 SGSR (Qualcomm), 3 FSR 1, 4 Lanczos 2.
internal val UPSCALER_MODES = listOf("0" to "Off (plain scaling)", "2" to "SGSR (Qualcomm)", "3" to "FSR 1", "4" to "Lanczos 2")



/**
 * The screen size: a list of sizes and "Custom", which asks for a width and a height. [value] is "WxH", or empty when
 * [defaultLabel] is given and the setting follows its default (a shortcut following its container).
 */
@Composable
internal fun ResolutionSetting(value: String, defaultLabel: String? = null, onChange: (String) -> Unit) {
    val current = value.trim()
    // A size that is not in the list opens as Custom, with its width and height in two fields.
    var custom by rememberSaveable {
        mutableStateOf(current.isNotEmpty() && current != NATIVE_RESOLUTION && RESOLUTION_PRESETS.none { it.first == current })
    }
    var widthText by rememberSaveable { mutableStateOf(current.substringBefore('x', "")) }
    var heightText by rememberSaveable { mutableStateOf(current.substringAfter('x', "")) }
    val valid = (defaultLabel != null && current.isEmpty()) || isValidScreenSize(current)
    val entries = LinkedHashMap<String, String>().apply {
        if (defaultLabel != null) put("", defaultLabel)
        put(NATIVE_RESOLUTION, "Native (the device's screen)")
        RESOLUTION_PRESETS.forEach { put(it.first, it.second) }
        put(CUSTOM_RESOLUTION, "Custom")
    }
    SettingMappedChoice("Resolution", if (custom) CUSTOM_RESOLUTION else current, entries) { choice ->
        if (choice == CUSTOM_RESOLUTION) {
            // Start from the size that is set now.
            widthText = current.substringBefore('x', widthText)
            heightText = current.substringAfter('x', heightText)
            custom = true
        } else {
            custom = false
            onChange(choice)
        }
    }
    if (custom) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = widthText,
                onValueChange = { text ->
                    widthText = text.filter { it.isDigit() }.take(5)
                    onChange("${widthText}x$heightText")
                },
                label = { Text("Width") },
                isError = !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Text("×", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = heightText,
                onValueChange = { text ->
                    heightText = text.filter { it.isDigit() }.take(5)
                    onChange("${widthText}x$heightText")
                },
                label = { Text("Height") },
                isError = !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        if (!valid) {
            Text(
                "A width and a height of 3 to 5 digits, for example 1280 and 720.",
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal const val CUSTOM_RESOLUTION = "custom"
