package com.winlator.cmod.ui.container

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import com.winlator.cmod.lsfg.LsfgManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.linux.LinuxDriverManager
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.linux.LinuxEmulator
import com.winlator.cmod.linux.LinuxRootfs
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
    var emulator by remember { mutableStateOf(editing?.let { LinuxEmulator.choice(it) } ?: LinuxEmulator.NONE) }
    var emulatorId by remember { mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_ID) ?: "") }
    var emulatorPreset by remember {
        mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_PRESET)?.takeIf { it.isNotEmpty() } ?: Box64Preset.COMPATIBILITY)
    }
    // FEX only: the root filesystem it runs in, its own preset, and whether the guest's Vulkan/OpenGL go to the runtime's.
    var rootfsId by remember { mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_ROOTFS) ?: "") }
    var fexPreset by remember {
        mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_FEX_PRESET)?.takeIf { it.isNotEmpty() } ?: FEXCorePreset.INTERMEDIATE)
    }
    var startSteam by remember { mutableStateOf(editing?.getExtra(LinuxSession.EXTRA_STEAM) == "1") }
    var fexThunks by remember { mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_FEX_THUNKS) != "0") }
    var glDriver by remember {
        mutableStateOf(if (editing?.getExtra(LinuxSession.EXTRA_GL_DRIVER) == "zink") "zink" else "software")
    }
    var softwareOutput by remember {
        // New containers start with native (dma-buf) output; the CPU copy halves GPU load.
        mutableStateOf(editing != null && editing.getExtra(LinuxSession.EXTRA_VULKAN_PRESENT) != "native")
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
    // Scaled to fit the window with the proportions kept; unchecked, the screen is drawn 1:1 in the middle.
    var stretched by remember { mutableStateOf(editing?.isFullscreenStretched ?: true) }
    var upscaler by remember {
        mutableStateOf(editing?.getExtra("graphicsFilterMode")?.takeIf { m -> UPSCALER_MODES.any { it.first == m } } ?: "0")
    }
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
    val boxBuilds = remember(refreshTick) { LinuxEmulator.list(context, LinuxEmulator.BOX64) }
    val fexBuilds = remember(refreshTick) { LinuxEmulator.list(context, LinuxEmulator.FEX) }
    val rootfsList = remember(refreshTick) { LinuxRootfs.list(context) }
    val boxPresets = remember { Box64PresetManager.getPresets("box64", context).associateTo(LinkedHashMap<String, String>()) { it.id to it.name } }
    val fexPresets = remember { FEXCorePresetManager.getPresets(context).associateTo(LinkedHashMap<String, String>()) { it.id to it.name } }
    // Until the user picks one, the first installed runtime is used.
    val selected = runtimes.firstOrNull { it.id == chosenRuntimeId } ?: runtimes.firstOrNull()
    val sizeValid = isValidScreenSize(screenSize.trim())
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
        container.putExtra(LinuxEmulator.EXTRA_EMULATOR, emulator)
        // Until the user picks a build, the first installed one is used.
        val builds = if (emulator == LinuxEmulator.FEX) fexBuilds else boxBuilds
        container.putExtra(LinuxEmulator.EXTRA_ID, if (emulator == LinuxEmulator.NONE) "" else (builds.firstOrNull { it.id == emulatorId } ?: builds.firstOrNull())?.id ?: "")
        container.putExtra(LinuxEmulator.EXTRA_PRESET, emulatorPreset)
        // Until the user picks one, the first installed root filesystem is used.
        container.putExtra(LinuxEmulator.EXTRA_ROOTFS, if (emulator == LinuxEmulator.FEX) (rootfsList.firstOrNull { it.id == rootfsId } ?: rootfsList.firstOrNull())?.id ?: "" else "")
        container.putExtra(LinuxEmulator.EXTRA_FEX_PRESET, fexPreset)
        container.putExtra(LinuxEmulator.EXTRA_FEX_THUNKS, if (fexThunks) "1" else "0")
        container.putExtra(LinuxSession.EXTRA_STEAM, if (startSteam) "1" else "0")
        container.putExtra("graphicsFpsPreset", fpsIndex.toString())
        container.putExtra("hudMode", hudMode.toString())
        container.putExtra("graphicsFilterMode", upscaler)
        ContainerManager.syncSteamShortcut(container, startSteam)
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

    // The settings, grouped. A wide screen lists the groups beside the page of the chosen one; a narrow screen stacks them
    // as sections that open and close. Both show the same pages, each with a line that says how it is set, and a group that
    // needs the user's attention (a missing runtime or build, a bad screen size) is the one that opens first.
    val noRuntime = selected == null && !install.running
    val emulatorProblem = (emulator == LinuxEmulator.BOX64 && boxBuilds.isEmpty()) ||
        (emulator == LinuxEmulator.FEX && (fexBuilds.isEmpty() || rootfsList.isEmpty()))
    val activeBuild = (if (emulator == LinuxEmulator.FEX) fexBuilds else boxBuilds).let { builds -> builds.firstOrNull { it.id == emulatorId } ?: builds.firstOrNull() }
    val emulatorSummary = when (emulator) {
        LinuxEmulator.BOX64 -> "Box64 · ${activeBuild?.version?.ifEmpty { null } ?: activeBuild?.name ?: "not installed"} · ${boxPresets[emulatorPreset] ?: emulatorPreset}"
        LinuxEmulator.FEX -> "FEX · ${activeBuild?.version?.ifEmpty { null } ?: activeBuild?.name ?: "not installed"} · ${fexPresets[fexPreset] ?: fexPreset}"
        else -> "None (ARM64 programs only)"
    }
    val frameGenerationSummary = editing?.let { container ->
        val multiplier = LsfgManager.getMultiplier(container)
        if (multiplier >= 2) "${multiplier}x" else "Off"
    } ?: "Off"
    val envCount = envVars.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    val categories = listOf(
        EditorCategory("general", "General", name.ifBlank { "Unnamed" }, name.isBlank()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        EditorCategory(
            "runtime", "Runtime",
            selected?.name ?: if (install.running) "Installing…" else "Not installed", noRuntime
        ) {
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
                SettingToggle("Start the Steam client with the container", startSteam) { startSteam = it }
                Text(
                    "Starts the runtime's Steam client beside the game. The first start downloads it (1-2 minutes); its log is Winlator/logs/linux-steam.log.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        EditorCategory("emulation", "x86 emulation (Box64 / FEX)", emulatorSummary, emulatorProblem) {
            SettingsCard {
                SettingMappedChoice(
                    "x86 emulator",
                    emulator,
                    linkedMapOf(LinuxEmulator.NONE to "None (ARM64 programs only)", LinuxEmulator.BOX64 to "Box64", LinuxEmulator.FEX to "FEX")
                ) { emulator = it }
                if (emulator == LinuxEmulator.BOX64) {
                    SettingsDivider()
                    if (boxBuilds.isEmpty()) {
                        Text(
                            "No Box64 build is installed. Install one in Components → Linux Emulator.",
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        val shown = boxBuilds.firstOrNull { it.id == emulatorId } ?: boxBuilds.first()
                        SettingMappedChoice(
                            "Box64 build",
                            shown.id,
                            boxBuilds.associateTo(LinkedHashMap<String, String>()) { it.id to it.name }
                        ) { emulatorId = it }
                    }
                    SettingsDivider()
                    SettingMappedChoice("Box64 preset", emulatorPreset, boxPresets) { emulatorPreset = it }
                }
                if (emulator == LinuxEmulator.FEX) {
                    SettingsDivider()
                    if (fexBuilds.isEmpty()) {
                        Text(
                            "No FEX build is installed. Install one in Components → Linux Emulator.",
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        val shown = fexBuilds.firstOrNull { it.id == emulatorId } ?: fexBuilds.first()
                        SettingMappedChoice(
                            "FEX build",
                            shown.id,
                            fexBuilds.associateTo(LinkedHashMap<String, String>()) { it.id to it.name }
                        ) { emulatorId = it }
                    }
                    SettingsDivider()
                    if (rootfsList.isEmpty()) {
                        // FEX runs programs in an x86-64 userland of its own; without one it cannot start.
                        Text(
                            "No x86-64 root filesystem is installed. FEX needs one: install it in Components → Linux Root Filesystem.",
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        val shownRootfs = rootfsList.firstOrNull { it.id == rootfsId } ?: rootfsList.first()
                        SettingMappedChoice(
                            "x86-64 root filesystem",
                            shownRootfs.id,
                            rootfsList.associateTo(LinkedHashMap<String, String>()) { it.id to it.name }
                        ) { rootfsId = it }
                    }
                    SettingsDivider()
                    SettingMappedChoice("FEX preset", fexPreset, fexPresets) { fexPreset = it }
                    SettingsDivider()
                    SettingToggle("Use the runtime's Vulkan (FEX thunk)", fexThunks) { fexThunks = it }
                }
            }
        },
        EditorCategory(
            "graphics", "Graphics",
            screenSize.trim() + " · " + (if (softwareOutput) "CPU copy" else "native output") +
                (if (stretched) " · fit" else "") + (UPSCALER_MODES.firstOrNull { it.first == upscaler && it.first != "0" }?.let { " · " + it.second.substringBefore(" (") } ?: ""),
            !sizeValid
        ) {
            SettingsCard {
                ResolutionSetting(screenSize) { screenSize = it }
                SettingsDivider()
                SettingToggle("Fit to screen (keep proportions)", stretched) { stretched = it }
                SettingsDivider()
                // The picture is scaled by the Vulkan renderer, which "native" output uses; the CPU-copy output draws with EGL.
                SettingMappedChoice(
                    "Upscaler (needs native output and Fit to screen)",
                    upscaler,
                    UPSCALER_MODES.associateTo(LinkedHashMap<String, String>()) { it.first to it.second }
                ) { upscaler = it }
                SettingsDivider()
                SettingToggle("Software Vulkan output (CPU copy, experimental)", softwareOutput) { softwareOutput = it }
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
                    linkedMapOf("software" to "Software (llvmpipe)", "zink" to "Zink on Turnip (experimental, not working yet)")
                ) { glDriver = it }
                SettingsDivider()
                SettingMappedChoice(
                    "HUD",
                    hudMode.toString(),
                    HUD_LABELS.withIndex().associateTo(LinkedHashMap<String, String>()) { it.index.toString() to it.value }
                ) { hudMode = it.toInt() }
            }
        },
        EditorCategory("framegen", "Frame generation", frameGenerationSummary, false) {
            // The multiplier and its overrides are saved on the container as they are changed, so there has to be one.
            editing?.let { ContainerLsfgCard(it) } ?: ContainerLsfgUnavailableCard()
            Text(
                "Works with native output only (turn off \"Software Vulkan output\"): each generated frame is one more " +
                    "frame to show, and the CPU copy cannot keep up with that. Import Lossless.dll under Settings > Components.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        EditorCategory(
            "performance", "Performance",
            "${cpuSelected.count { it }} of ${cpuSelected.size} cores · " + FPS_LABELS[fpsIndex.coerceIn(0, FPS_LABELS.size - 1)].replace("Off", "no FPS limit"),
            false
        ) {
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
            }
        },
        EditorCategory("advanced", "Environment variables", if (envCount == 0) "None" else "$envCount set", false) {
            EnvironmentVariablesEditor(value = envVars, onChanged = { envVars = it })
        }
    )

    val problems: @Composable () -> Unit = {
        (error ?: install.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!GPUInformation.isAdrenoGPU(context)) {
            Text(
                "The Linux runtime is only known to work on Adreno GPUs.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    val saveButton: @Composable () -> Unit = {
        Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (editing != null) "Save" else if (needsDownload) "Download runtime and create" else "Create")
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            GroupedSettings(categories) {
                problems()
                saveButton()
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes >= (1L shl 30)) String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    else "${bytes shr 20} MB"
