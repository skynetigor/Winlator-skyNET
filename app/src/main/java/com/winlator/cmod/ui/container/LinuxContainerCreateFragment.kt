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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.winlator.cmod.steam.SteamContainer
import com.winlator.cmod.ui.settings.CpuSelectorRow
import com.winlator.cmod.ui.settings.EditorCategory
import com.winlator.cmod.ui.settings.GroupedSettings
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
        private const val ARG_STEAM = "steam"

        @JvmStatic
        fun forEdit(containerId: Int): LinuxContainerCreateFragment = LinuxContainerCreateFragment().apply {
            arguments = Bundle().apply { putInt(ARG_EDIT_CONTAINER_ID, containerId) }
        }

        /** The settings of the app's Steam container (see [SteamContainer]): its components to download, then its container settings. */
        @JvmStatic
        fun forSteam(): LinuxContainerCreateFragment = LinuxContainerCreateFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_STEAM, true) }
        }
    }

    /** Bumped when the screen comes back (e.g. from Components) so the runtime list is read again. */
    private val refreshTick = mutableIntStateOf(0)
    private val install = RuntimeInstallUi()

    private val installListener = object : LinuxRuntimeInstallTask.Listener {
        override fun onProgress(id: String, phase: LinuxRuntimeInstaller.Phase, percent: Int) {
            install.running = true
            install.currentId = id
            install.phase = phase
            install.percent = percent
        }

        override fun onFinished(id: String, error: String?) {
            install.running = false
            install.currentId = ""
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
        val steam = arguments?.getBoolean(ARG_STEAM, false) == true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                LinuxContainerEditor(
                    editId = editId,
                    steam = steam,
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
    /** The id of the package being installed (a Steam download installs several one after another). */
    var currentId by mutableStateOf("")
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

/** What the Steam settings screen adds to the container editor. */
private class SteamScreen(
    val defaults: SteamContainer.Defaults,
    /** The container exists (so every component is installed): the settings can be changed. */
    val ready: Boolean,
    /** The card above the settings: what is to be downloaded, and how far it is. */
    val header: @Composable () -> Unit,
    /** Wipes the Steam container and makes a new one from the defaults. */
    val onReset: () -> Unit
)

@Composable
private fun LinuxContainerEditor(
    editId: Int?,
    steam: Boolean,
    refreshTick: Int,
    install: RuntimeInstallUi,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { ContainerManager(context) }
    if (steam) {
        SteamSettingsScreen(manager, refreshTick, install, onBack, onDone)
        return
    }
    val editing = remember { editId?.let { manager.getContainerById(it) } }
    LinuxContainerEditorBody(editing, null, manager, refreshTick, install, onBack, onDone)
}

/**
 * The Steam settings: the container's own settings, greyed out until the components Steam needs are installed. At the top, a card
 * says what is to be downloaded and does it; when the last package is in, the container appears (see [SteamContainer.ensure])
 * and the settings below become the container's.
 */
@Composable
private fun SteamSettingsScreen(
    manager: ContainerManager,
    refreshTick: Int,
    install: RuntimeInstallUi,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val defaults = remember { SteamContainer.defaults(context, false) }
    var container by remember { mutableStateOf(SteamContainer.ensure(context, manager)) }
    var plan by remember { mutableStateOf<SteamContainer.Plan?>(null) }
    var planTick by remember { mutableIntStateOf(0) }

    // What is still to be downloaded: the catalogs are read off the main thread, again after a download ended or on "try again".
    LaunchedEffect(container == null, refreshTick, install.finishedTick, planTick) {
        if (container == null) {
            plan = null
            plan = withContext(Dispatchers.IO) { SteamContainer.plan(context) }
        }
    }
    // The container appears when the last component is in.
    LaunchedEffect(install.finishedTick) {
        if (install.finishedTick > 0 && container == null) container = SteamContainer.ensure(context, manager)
    }

    val header: @Composable () -> Unit = {
        SteamComponentsCard(
            plan = plan, install = install, container = container,
            onRetry = { planTick++ },
            onDownload = {
                val current = plan
                if (current != null && current.error == null && current.install.isNotEmpty() && LinuxRuntimeInstallTask.runningId() == null) {
                    install.error = null
                    install.running = true
                    install.percent = 0
                    install.phase = LinuxRuntimeInstaller.Phase.DOWNLOAD
                    LinuxRuntimeInstallTask.startAll(context, current.install)
                }
            }
        )
    }
    key(container?.id) {
        LinuxContainerEditorBody(
            container,
            SteamScreen(defaults, container != null, header) { container = SteamContainer.reset(context, manager) },
            manager, refreshTick, install, onBack, onDone
        )
    }
}

@Composable
private fun SteamComponentsCard(
    plan: SteamContainer.Plan?,
    install: RuntimeInstallUi,
    container: Container?,
    onRetry: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    SettingsCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (container != null) {
                val games = remember(container.id, install.finishedTick) { SteamContainer.gameCount(container) }
                Text("Steam is ready", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (games == 0) "Open the Steam shortcut in the library to sign in; the games you install there get shortcuts of their own."
                    else if (games == 1) "1 game is installed in Steam." else "$games games are installed in Steam.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            Text("Steam needs a few components", style = MaterialTheme.typography.titleSmall)
            Text(
                "The Linux runtime, Box64 (it runs the games' x86-64 code) and the Turnip graphics driver. The settings below are Steam's own; " +
                    "they can be changed once everything is installed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (plan == null) {
                Text("Checking what has to be downloaded…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                return@Column
            }
            if (plan.error != null) {
                Text(plan.error, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text("Try again") }
                return@Column
            }
            val installed = remember(install.finishedTick, install.currentId, install.running) {
                plan.install.filter { entryInstalled(context, it) }.map { it.id }.toSet()
            }
            plan.install.forEach { entry ->
                val state = when {
                    entry.id in installed -> "installed"
                    install.running && install.currentId == entry.id -> when (install.phase) {
                        LinuxRuntimeInstaller.Phase.DOWNLOAD -> "downloading ${install.percent}%"
                        LinuxRuntimeInstaller.Phase.VERIFY -> "checking ${install.percent}%"
                        LinuxRuntimeInstaller.Phase.EXTRACT -> "unpacking ${install.percent}%"
                    }
                    install.running -> "waiting"
                    else -> formatMegabytes(entry.size)
                }
                Text("${entry.name} · $state")
                if (install.running && install.currentId == entry.id) {
                    LinearProgressIndicator(progress = { install.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
            val free = context.filesDir.usableSpace
            val notEnoughSpace = plan.space > 0 && free < plan.space
            if (notEnoughSpace) {
                Text(
                    "Not enough free space: needs about ${formatMegabytes(plan.space)}, ${formatMegabytes(free)} free.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            install.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (install.running) {
                OutlinedButton(onClick = { LinuxRuntimeInstallTask.cancel() }) { Text("Cancel") }
            } else {
                Button(onClick = onDownload, enabled = plan.install.isNotEmpty() && !notEnoughSpace) {
                    Text("Download (${formatMegabytes(plan.download)})")
                }
            }
        }
    }
}

private fun entryInstalled(context: android.content.Context, entry: LinuxRuntimeCatalog.Entry): Boolean = when (entry.kind) {
    com.winlator.cmod.linux.LinuxPackages.KIND_RUNTIME -> LinuxRuntime.isInstalled(context, entry.id)
    com.winlator.cmod.linux.LinuxPackages.KIND_EMULATOR -> LinuxEmulator.isInstalled(context, entry.id)
    com.winlator.cmod.linux.LinuxPackages.KIND_DRIVER -> LinuxDriverManager.isInstalled(context, entry.id)
    else -> false
}

@Composable
private fun LinuxContainerEditorBody(
    editing: Container?,
    steam: SteamScreen?,
    manager: ContainerManager,
    refreshTick: Int,
    install: RuntimeInstallUi,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceIn(1, 32) }

    var name by remember { mutableStateOf(editing?.name ?: "Linux") }
    var screenSize by remember { mutableStateOf(editing?.screenSize ?: Container.DEFAULT_SCREEN_SIZE) }
    var chosenRuntimeId by remember { mutableStateOf(editing?.let { LinuxRuntime.resolve(context, it)?.id } ?: "") }
    var driverId by remember { mutableStateOf(editing?.getExtra(LinuxDriverManager.EXTRA_DRIVER) ?: steam?.defaults?.driver ?: "") }
    var emulator by remember { mutableStateOf(editing?.let { LinuxEmulator.choice(it) } ?: if (steam != null) LinuxEmulator.BOX64 else LinuxEmulator.NONE) }
    var emulatorId by remember { mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_ID) ?: steam?.defaults?.emulator ?: "") }
    var emulatorPreset by remember {
        mutableStateOf(
            editing?.getExtra(LinuxEmulator.EXTRA_PRESET)?.takeIf { it.isNotEmpty() }
                ?: steam?.defaults?.emulatorPreset ?: Box64Preset.COMPATIBILITY
        )
    }
    // FEX only: the root filesystem it runs in, its own preset, and whether the guest's Vulkan/OpenGL go to the runtime's.
    var rootfsId by remember { mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_ROOTFS) ?: "") }
    var fexPreset by remember {
        mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_FEX_PRESET)?.takeIf { it.isNotEmpty() } ?: FEXCorePreset.INTERMEDIATE)
    }
    // Input: a finger acts where it touches, a button opens the keyboard, and a tap on a text field opens it by itself.
    var touchscreen by remember { mutableStateOf(editing?.getExtra(LinuxSession.EXTRA_TOUCHSCREEN) != "0") }
    var keyboardButton by remember { mutableStateOf(editing?.getExtra(LinuxSession.EXTRA_KEYBOARD_BUTTON) != "0") }
    var autoKeyboard by remember { mutableStateOf(editing?.getExtra(LinuxSession.EXTRA_AUTO_KEYBOARD) != "0") }
    var fexThunks by remember { mutableStateOf(editing?.getExtra(LinuxEmulator.EXTRA_FEX_THUNKS) != "0") }
    var glDriver by remember {
        // OpenGL 4.6 on the Adreno through Zink (over Turnip) works; new containers use it, and one without a choice keeps llvmpipe.
        mutableStateOf(
            if (editing == null) steam?.defaults?.glDriver ?: "zink"
            else if (editing.getExtra(LinuxSession.EXTRA_GL_DRIVER) == "zink") "zink" else "software"
        )
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
    val needsDownload = steam == null && editing == null && selected == null
    val freeBytes = context.filesDir.usableSpace
    val neededBytes = offered?.let { LinuxRuntimeInstaller.requiredBytes(it) } ?: 0L
    val notEnoughSpace = needsDownload && offered != null && offered!!.size > 0 && freeBytes < neededBytes
    val busy = creating || install.running || waitingForRuntime
    val canSave = if (steam != null) editing != null && !busy else name.isNotBlank() && sizeValid && !busy && !notEnoughSpace
    var confirmReset by remember { mutableStateOf(false) }

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
        if (steam == null) {
            container.setName(name.trim())
            container.setScreenSize(screenSize.trim())
        }
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
        container.putExtra(LinuxSession.EXTRA_TOUCHSCREEN, if (touchscreen) "1" else "0")
        container.putExtra(LinuxSession.EXTRA_KEYBOARD_BUTTON, if (keyboardButton) "1" else "0")
        container.putExtra(LinuxSession.EXTRA_AUTO_KEYBOARD, if (autoKeyboard) "1" else "0")
        container.putExtra("graphicsFpsPreset", fpsIndex.toString())
        container.putExtra("hudMode", hudMode.toString())
        container.putExtra("graphicsFilterMode", upscaler)
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

    val categories = listOfNotNull(
        if (steam != null) null else EditorCategory("general", "General", name.ifBlank { "Unnamed" }, name.isBlank()) {
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
                if (steam == null) ResolutionSetting(screenSize) { screenSize = it }
                else Text(
                    "Native resolution and Big Picture: Steam always runs at the device's own size, in its full-screen interface. " +
                        "The games it starts choose their own mode, or the one set for them in Steam.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    linkedMapOf("zink" to "Zink on Turnip (OpenGL 4.6 over Vulkan, accelerated)", "software" to "Software (llvmpipe, CPU)")
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
            "input", "Input",
            (if (touchscreen) "touchscreen" else "touchpad") + (if (keyboardButton) " · keyboard button" else "") +
                (if (autoKeyboard) " · auto keyboard" else ""),
            false
        ) {
            SettingsCard {
                SettingToggle("Touchscreen mode (a tap clicks where you touch)", touchscreen) { touchscreen = it }
                Text(
                    "Off: the screen works like a laptop's touchpad, a finger moves the pointer and a tap clicks under it.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsDivider()
                SettingToggle("Keyboard button on the screen", keyboardButton) { keyboardButton = it }
                SettingsDivider()
                SettingToggle("Open the keyboard when a text field is tapped", autoKeyboard) { autoKeyboard = it }
                Text(
                    "A program shows a text cursor over an editable field; the keyboard opens when one is under the finger. Not " +
                        "every program does (a web view such as the Steam client's does not): the button always works.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            Text(if (editing != null || steam != null) "Save" else if (needsDownload) "Download runtime and create" else "Create")
        }
    }
    val resetButton: @Composable () -> Unit = {
        if (steam != null && editing != null) {
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) { Text("Reset Steam") }
        }
    }
    if (confirmReset && steam != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset Steam?") },
            text = { Text("Steam's container is deleted with everything in it (the sign-in, the downloaded games) and made again from the defaults.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; steam.onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (steam != null) "Steam" else if (editing != null) "Edit Linux container" else "New Linux container") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            GroupedSettings(categories, header = steam?.header, enabled = steam?.ready != false) {
                problems()
                saveButton()
                resetButton()
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes >= (1L shl 30)) String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    else "${bytes shr 20} MB"
