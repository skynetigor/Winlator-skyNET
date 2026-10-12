package com.winlator.cmod.ui.shortcut

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider

/**
 * The settings of a shortcut that starts a Linux program. A Wine shortcut has a page of Wine, DXVK and drive options
 * that mean nothing here (and read fields a Linux container does not have); a Linux program has its launch options.
 */
@Composable
internal fun LinuxShortcutEditor(shortcut: Shortcut, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = shortcut.container
    var options by remember { mutableStateOf(shortcut.getExtra(LinuxLaunchOptions.EXTRA)) }
    val parsed = remember(options) { LinuxLaunchOptions.parse(options) }

    val emulator = remember {
        when (LinuxEmulator.choice(container)) {
            LinuxEmulator.NONE -> "none (ARM64 programs only)"
            else -> LinuxEmulator.resolve(context, container)?.name
                ?: "${LinuxEmulator.choice(container)} (not installed)"
        }
    }

    fun save() {
        // One line: the shortcut file keeps each extra on a line of its own.
        val text = options.replace('\n', ' ').trim()
        shortcut.putExtra(LinuxLaunchOptions.EXTRA, text.ifEmpty { null })
        shortcut.saveData()
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shortcut.name) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Launch options", style = MaterialTheme.typography.titleMedium)
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

            Text("Program", style = MaterialTheme.typography.titleMedium)
            SettingsCard {
                PreviewRow("Path", shortcut.path)
                SettingsDivider()
                PreviewRow("Container", container.name)
                SettingsDivider()
                PreviewRow("Runs under", "Linux · $emulator")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End)
            ) {
                TextButton(onClick = onClose) { Text("Cancel") }
                Button(onClick = { save() }) { Text("Save") }
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
