package com.winlator.cmod.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.lsfg.LsfgManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val flowScaleChoices = listOf("0.25", "0.50", "0.75", "0.80", "1.00")
private val presentModeChoices = listOf(LsfgManager.PRESENT_MODE_MAILBOX, LsfgManager.PRESENT_MODE_FIFO)
private val performanceChoices = listOf("On", "Off")

/**
 * The app-wide frame generation component (Settings > Components > Frame Generation): the single Lossless.dll every container uses and the
 * defaults containers fall back to. Collapsed by default so it does not push the component list
 * off the screen.
 */
@Composable
internal fun GlobalLsfgCard(collapsible: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var expandedState by remember { mutableStateOf(false) }
    val expanded = expandedState || !collapsible
    var dllSize by remember { mutableStateOf(dllSizeOf(context)) }
    var importing by remember { mutableStateOf(false) }
    var flowScale by remember { mutableStateOf(String.format(Locale.US, "%.2f", LsfgManager.getDefaultFlowScale(context))) }
    var performanceMode by remember { mutableStateOf(if (LsfgManager.getDefaultPerformanceMode(context)) "On" else "Off") }
    var presentMode by remember { mutableStateOf(LsfgManager.getDefaultPresentMode(context)) }

    val dllPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val error = withContext(Dispatchers.IO) { LsfgManager.importDll(context, uri) }
            importing = false
            dllSize = dllSizeOf(context)
            Toast.makeText(context, error ?: "Lossless.dll imported", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.padding(bottom = 8.dp)) {
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = collapsible) { expandedState = !expandedState }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Frame Generation (Lossless.dll)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        dllSize?.let { "Imported · $it" } ?: "Not imported",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dllSize != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
                if (collapsible) Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = if (expanded) "Collapse" else "Expand")
            }
            if (expanded) {
                SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lossless.dll", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (dllSize != null) {
                        TextButton(enabled = !importing, onClick = {
                            LsfgManager.removeDll(context); dllSize = dllSizeOf(context)
                        }) { Text("Remove") }
                        Spacer(Modifier.width(4.dp))
                    }
                    OutlinedButton(enabled = !importing, onClick = { dllPicker.launch(arrayOf("*/*")) }) {
                        Text(if (importing) "Importing…" else if (dllSize != null) "Replace" else "Import")
                    }
                }
                Text(
                    "Select Lossless.dll from your own Lossless Scaling install (Steam). It is proprietary, so it is not bundled and not in the registry. One copy serves every container; set the multiplier per container.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
                SettingsDivider()
                Text(
                    "Defaults for all containers",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
                SettingChoice("Flow Scale", flowScale, flowScaleChoices) {
                    flowScale = it; LsfgManager.setDefaultFlowScale(context, it.toFloat())
                }
                SettingsDivider()
                SettingChoice("Performance Mode", performanceMode, performanceChoices) {
                    performanceMode = it; LsfgManager.setDefaultPerformanceMode(context, it == "On")
                }
                SettingsDivider()
                SettingChoice("Present Mode", presentMode, presentModeChoices) {
                    presentMode = it; LsfgManager.setDefaultPresentMode(context, it)
                }
                Text(
                    "A container can override these in its own frame generation settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

private fun dllSizeOf(context: Context): String? {
    if (!LsfgManager.hasDll(context)) return null
    return String.format(Locale.US, "%.1f MB", LsfgManager.getDllFile(context).length() / 1048576.0)
}
