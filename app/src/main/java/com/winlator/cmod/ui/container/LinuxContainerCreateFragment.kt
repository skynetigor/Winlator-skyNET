package com.winlator.cmod.ui.container

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.linux.LinuxDriverManager
import com.winlator.cmod.linux.LinuxRuntime
import com.winlator.cmod.linux.LinuxRuntimeCatalog
import com.winlator.cmod.linux.LinuxRuntimeInstallTask
import com.winlator.cmod.linux.LinuxRuntimeInstaller
import com.winlator.cmod.linux.LinuxSession
import com.winlator.cmod.ui.settings.CpuSelectorRow
import com.winlator.cmod.ui.settings.EnvironmentVariablesEditor
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.ui.theme.WinZTheme
import org.json.JSONObject
import java.util.Locale

/** Creates (or edits) a Linux container; its runtime is picked from the ones installed in Components. */
class LinuxContainerCreateFragment : Fragment() {
    companion object {
        private const val ARG_EDIT_CONTAINER_ID = "edit_container_id"

        @JvmStatic
        fun forEdit(containerId: Int): LinuxContainerCreateFragment = LinuxContainerCreateFragment().apply {
            arguments = Bundle().apply { putInt(ARG_EDIT_CONTAINER_ID, containerId) }
        }
    }

    /** Bumped when the screen comes back (e.g. from Components) so the runtime list is read again. */
    private val refreshTick = mutableIntStateOf(0)
    private val install = RuntimeInstallUi()

    private val installListener = object : LinuxRuntimeInstallTask.Listener {
        override fun onProgress(id: String, phase: LinuxRuntimeInstaller.Phase, percent: Int) {
            install.running = true
            install.phase = phase
            install.percent = percent
        }

        override fun onFinished(id: String, error: String?) {
            install.running = false
            install.error = error?.takeUnless { it == LinuxRuntimeInstaller.CANCELLED }
            install.succeeded = error == null
            install.finishedTick++
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
        LinuxRuntimeInstallTask.setListener(installListener)
    }

    override fun onPause() {
        LinuxRuntimeInstallTask.setListener(null)
        super.onPause()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        val editId = arguments?.getInt(ARG_EDIT_CONTAINER_ID, -1)?.takeIf { it > 0 }
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                LinuxContainerEditor(
                    editId = editId,
                    refreshTick = refreshTick.intValue,
                    install = install,
                    onBack = { parentFragmentManager.popBackStack() },
                    onDone = { parentFragmentManager.popBackStack() }
                )
            }
        }
    }
}

/** What the runtime download started from this screen is doing; the install itself lives in [LinuxRuntimeInstallTask]. */
private class RuntimeInstallUi {
    var running by mutableStateOf(false)
    var phase by mutableStateOf(LinuxRuntimeInstaller.Phase.DOWNLOAD)
    var percent by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var succeeded by mutableStateOf(false)
    var finishedTick by mutableIntStateOf(0)
}

private val SIZE_PATTERN = Regex("^\\d{3,5}x\\d{3,5}$")
private val FPS_LABELS = listOf("Off", "30 FPS", "60 FPS", "90 FPS", "120 FPS")
private val HUD_LABELS = listOf("Off", "Classic", "Modern")

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LinuxContainerEditor(
    editId: Int?,
    refreshTick: Int,
    install: RuntimeInstallUi,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { ContainerManager(context) }
    val editing = remember { editId?.let { manager.getContainerById(it) } }
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceIn(1, 32) }

    var name by remember { mutableStateOf(editing?.name ?: "Linux") }
    var screenSize by remember { mutableStateOf(editing?.screenSize ?: Container.DEFAULT_SCREEN_SIZE) }
    var chosenRuntimeId by remember { mutableStateOf(editing?.let { LinuxRuntime.resolve(context, it)?.id } ?: "") }
    var driverId by remember { mutableStateOf(editing?.getExtra(LinuxDriverManager.EXTRA_DRIVER) ?: "") }
    var glDriver by remember {
        mutableStateOf(if (editing?.getExtra(LinuxSession.EXTRA_GL_DRIVER) == "zink") "zink" else "software")
    }
    var softwareOutput by remember {
        mutableStateOf(editing?.getExtra(LinuxSession.EXTRA_VULKAN_PRESENT) != "native")
    }
    val cpuSelected = remember {
        mutableStateListOf<Boolean>().apply {
            val saved = editing?.getCPUList()?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
            for (i in 0 until cores) add(saved == null || saved.isEmpty() || i in saved)
        }
    }
    var fpsIndex by remember { mutableStateOf(editing?.getExtra("graphicsFpsPreset")?.toIntOrNull()?.coerceIn(0, 4) ?: 0) }
    var hudMode by remember {
        mutableStateOf(
            editing?.getExtra("hudMode")?.toIntOrNull()?.coerceIn(0, 2) ?: if (editing?.isShowFPS == true) 1 else 0
        )
    }
    var stretched by remember { mutableStateOf(editing?.isFullscreenStretched ?: false) }
    var envVars by remember {
        mutableStateOf(editing?.envVars?.takeIf { it != Container.DEFAULT_ENV_VARS } ?: "")
    }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // True from "Create" until the runtime download it started has finished.
    var waitingForRuntime by remember { mutableStateOf(false) }
    // The runtime that will be downloaded, once the catalog has been read.
    var offered by remember { mutableStateOf<LinuxRuntimeCatalog.Entry?>(null) }

    val runtimes = remember(refreshTick, install.finishedTick) { LinuxRuntime.listInstalled(context) }
    val drivers = remember(refreshTick) { LinuxDriverManager.list(context) }
    // Until the user picks one, the first installed runtime is used.
    val selected = runtimes.firstOrNull { it.id == chosenRuntimeId } ?: runtimes.firstOrNull()
    val sizeValid = SIZE_PATTERN.matches(screenSize.trim())
    val needsDownload = editing == null && selected == null
    val freeBytes = context.filesDir.usableSpace
    val neededBytes = offered?.let { LinuxRuntimeInstaller.requiredBytes(it) } ?: 0L
    val notEnoughSpace = needsDownload && offered != null && offered!!.size > 0 && freeBytes < neededBytes
    val busy = creating || install.running || waitingForRuntime
    val canSave = name.isNotBlank() && sizeValid && !busy && !notEnoughSpace

    LaunchedEffect(needsDownload) {
        if (needsDownload && offered == null) {
            val main = Handler(Looper.getMainLooper())
            Thread {
                val first = LinuxRuntimeCatalog.fetch()?.firstOrNull()
                main.post { offered = first }
            }.start()
        }
    }

    /** Writes everything the editor shows into the container (the caller saves it). */
    fun apply(container: Container) {
        container.setName(name.trim())
        container.setScreenSize(screenSize.trim())
        if (selected != null) container.setLinuxRuntime(selected.id)
        container.setEnvVars(envVars.trim())
        container.setCPUList(
            if (cpuSelected.all { it }) null
            else cpuSelected.indices.filter { cpuSelected[it] }.joinToString(",")
        )
        container.setFullscreenStretched(stretched)
        container.putExtra(LinuxDriverManager.EXTRA_DRIVER, driverId)
        container.putExtra(LinuxSession.EXTRA_VULKAN_PRESENT, if (softwareOutput) "sw" else "native")
        container.putExtra(LinuxSession.EXTRA_GL_DRIVER, glDriver)
        container.putExtra("graphicsFpsPreset", fpsIndex.toString())
        container.putExtra("hudMode", hudMode.toString())
    }

    fun save() {
        if (editing != null) {
            apply(editing)
            editing.saveData()
            onDone()
            return
        }
        if (selected == null) {
            // No runtime yet: download it first; the container is created when that finishes.
            error = null
            install.error = null
            waitingForRuntime = true
            if (LinuxRuntimeInstallTask.runningId() == null) {
                val entry = offered
                if (entry == null) {
                    waitingForRuntime = false
                    error = "Could not read the runtime catalog. Check the connection and try again."
                } else {
                    install.running = true
                    install.phase = LinuxRuntimeInstaller.Phase.DOWNLOAD
                    install.percent = 0
                    LinuxRuntimeInstallTask.start(context, entry)
                }
            } else {
                install.running = true
            }
            return
        }
        creating = true
        val data = JSONObject().apply {
            put("name", name.trim())
            put("screenSize", screenSize.trim())
            put("linuxRuntime", selected.id)
            put("envVars", "")
        }
        manager.createLinuxContainerAsync(data) { created ->
            creating = false
            if (created != null) {
                apply(created)
                created.saveData()
                onDone()
            } else error = "Could not create the container"
        }
    }

    LaunchedEffect(install.finishedTick) {
        if (waitingForRuntime && install.finishedTick > 0) {
            waitingForRuntime = false
            if (install.succeeded && selected != null) save()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing != null) "Edit Linux container" else "New Linux container") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle("General")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = screenSize,
                onValueChange = { screenSize = it },
                label = { Text("Screen size") },
                supportingText = { Text("WIDTHxHEIGHT, for example 1280x720") },
                isError = !sizeValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionTitle("Runtime and graphics")
            SettingsCard {
                if (install.running) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val label = when (install.phase) {
                            LinuxRuntimeInstaller.Phase.DOWNLOAD -> "Downloading"
                            LinuxRuntimeInstaller.Phase.VERIFY -> "Verifying"
                            LinuxRuntimeInstaller.Phase.EXTRACT -> "Unpacking"
                        }
                        Text("Linux runtime: $label… ${install.percent}%")
                        LinearProgressIndicator(
                            progress = { install.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = { LinuxRuntimeInstallTask.cancel() }) { Text("Cancel") }
                    }
                } else if (selected == null) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing == null) {
                            val size = offered?.size?.takeIf { it > 0 }?.let { formatMegabytes(it) } ?: "about 790 MB"
                            Text(
                                "No Linux runtime is installed. Creating the container will download it first ($size).",
                                color = if (notEnoughSpace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            if (notEnoughSpace) {
                                Text(
                                    "Not enough free space: needs about ${formatMegabytes(neededBytes)}, " +
                                        "${formatMegabytes(freeBytes)} free.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            Text("This container's runtime is not installed. Download it in Components.")
                        }
                    }
                } else {
                    SettingMappedChoice(
                        "Runtime",
                        selected.id,
                        runtimes.associateTo(LinkedHashMap<String, String>()) { it.id to it.name }
                    ) { chosenRuntimeId = it }
                }
                SettingsDivider()
                val driverEntries = LinkedHashMap<String, String>().apply {
                    put("", "Runtime default")
                    drivers.forEach { put(it.id, it.label()) }
                    // A driver that was removed after being chosen stays visible until another is picked.
                    if (driverId.isNotEmpty() && driverId !in this) put(driverId, "$driverId (not installed)")
                }
                SettingMappedChoice("Turnip driver", driverId, driverEntries) { driverId = it }
                if (drivers.isEmpty()) {
                    Text(
                        "Import a Linux Turnip zip in Components → Linux Driver to choose another version.",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SettingsDivider()
                SettingMappedChoice(
                    "OpenGL driver",
                    glDriver,
                    linkedMapOf("software" to "Software (llvmpipe)", "zink" to "Zink on Turnip")
                ) { glDriver = it }
                SettingsDivider()
                SettingToggle("Software Vulkan output (CPU copy)", softwareOutput) { softwareOutput = it }
            }

            SectionTitle("Performance")
            SettingsCard {
                CpuSelectorRow("CPU cores", cpuSelected) { index, checked ->
                    // At least one core must stay selected.
                    if (checked || cpuSelected.count { it } > 1) cpuSelected[index] = checked
                }
                SettingsDivider()
                SettingMappedChoice(
                    "FPS limit",
                    fpsIndex.toString(),
                    FPS_LABELS.withIndex().associateTo(LinkedHashMap<String, String>()) { it.index.toString() to it.value }
                ) { fpsIndex = it.toInt() }
                SettingsDivider()
                SettingMappedChoice(
                    "HUD",
                    hudMode.toString(),
                    HUD_LABELS.withIndex().associateTo(LinkedHashMap<String, String>()) { it.index.toString() to it.value }
                ) { hudMode = it.toInt() }
                SettingsDivider()
                SettingToggle("Fullscreen stretched", stretched) { stretched = it }
            }

            SectionTitle("Environment variables")
            EnvironmentVariablesEditor(value = envVars, onChanged = { envVars = it })

            (error ?: install.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!GPUInformation.isAdrenoGPU(context)) {
                Text(
                    "The Linux runtime is only known to work on Adreno GPUs.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (editing != null) "Save" else if (needsDownload) "Download runtime and create" else "Create")
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes >= (1L shl 30)) String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    else "${bytes shr 20} MB"
