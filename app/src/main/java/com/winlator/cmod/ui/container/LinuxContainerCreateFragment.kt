package com.winlator.cmod.ui.container

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.winlator.cmod.linux.LinuxRuntime
import com.winlator.cmod.linux.LinuxRuntimeCatalog
import com.winlator.cmod.linux.LinuxRuntimeInstallTask
import com.winlator.cmod.linux.LinuxRuntimeInstaller
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

    var name by remember { mutableStateOf(editing?.name ?: "Linux") }
    var screenSize by remember { mutableStateOf(editing?.screenSize ?: Container.DEFAULT_SCREEN_SIZE) }
    var chosenRuntimeId by remember { mutableStateOf(editing?.let { LinuxRuntime.resolve(context, it)?.id } ?: "") }
    var menuOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // True from "Create" until the runtime download it started has finished.
    var waitingForRuntime by remember { mutableStateOf(false) }
    // The runtime that will be downloaded, once the catalog has been read.
    var offered by remember { mutableStateOf<LinuxRuntimeCatalog.Entry?>(null) }

    val runtimes = remember(refreshTick, install.finishedTick) { LinuxRuntime.listInstalled(context) }
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

    fun save() {
        val size = screenSize.trim()
        if (editing != null) {
            editing.setName(name.trim())
            editing.setScreenSize(size)
            if (selected != null) editing.setLinuxRuntime(selected.id)
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
            put("screenSize", size)
            put("linuxRuntime", selected?.id ?: "")
        }
        manager.createLinuxContainerAsync(data) { created ->
            creating = false
            if (created != null) onDone() else error = "Could not create the container"
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
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

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Linux runtime", style = MaterialTheme.typography.titleSmall)
                    if (install.running) {
                        val label = when (install.phase) {
                            LinuxRuntimeInstaller.Phase.DOWNLOAD -> "Downloading"
                            LinuxRuntimeInstaller.Phase.VERIFY -> "Verifying"
                            LinuxRuntimeInstaller.Phase.EXTRACT -> "Unpacking"
                        }
                        Text("$label… ${install.percent}%")
                        LinearProgressIndicator(
                            progress = { install.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = { LinuxRuntimeInstallTask.cancel() }) { Text("Cancel") }
                    } else if (selected == null) {
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
                    } else {
                        Box {
                            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selected.name)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                runtimes.forEach { runtime ->
                                    DropdownMenuItem(
                                        text = { Text(runtime.name) },
                                        onClick = {
                                            chosenRuntimeId = runtime.id
                                            menuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            (error ?: install.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!GPUInformation.isAdrenoGPU(context)) {
                Text(
                    "The Linux runtime is only known to work on Adreno GPUs.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Launching Linux containers is not available yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (editing != null) "Save" else if (needsDownload) "Download runtime and create" else "Create")
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes >= (1L shl 30)) String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    else "${bytes shr 20} MB"
