package com.winlator.cmod.ui.container

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.winlator.cmod.linux.LinuxRuntimeInstaller
import com.winlator.cmod.ui.theme.WinZTheme
import org.json.JSONObject
import java.util.concurrent.Executors

/** Creates (or edits) a Linux container; the shared Linux runtime is downloaded here on first use. */
class LinuxContainerCreateFragment : Fragment() {
    companion object {
        private const val ARG_EDIT_CONTAINER_ID = "edit_container_id"

        @JvmStatic
        fun forEdit(containerId: Int): LinuxContainerCreateFragment = LinuxContainerCreateFragment().apply {
            arguments = Bundle().apply { putInt(ARG_EDIT_CONTAINER_ID, containerId) }
        }
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
                    onBack = { parentFragmentManager.popBackStack() },
                    onDone = { parentFragmentManager.popBackStack() }
                )
            }
        }
    }
}

/** Install state that outlives recomposition; the install itself runs on a worker thread. */
private class RuntimeInstallState {
    var running by mutableStateOf(false)
    var phase by mutableStateOf<LinuxRuntimeInstaller.Phase?>(null)
    var percent by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
}

private val SIZE_PATTERN = Regex("^\\d{3,5}x\\d{3,5}$")

@Composable
private fun LinuxContainerEditor(editId: Int?, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ContainerManager(context) }
    val editing = remember { editId?.let { manager.getContainerById(it) } }

    var name by remember { mutableStateOf(editing?.name ?: "Linux") }
    var screenSize by remember { mutableStateOf(editing?.screenSize ?: Container.DEFAULT_SCREEN_SIZE) }
    var installedVersion by remember { mutableStateOf(if (LinuxRuntime.isInstalled(context)) LinuxRuntime.installedVersion(context) else "") }
    val install = remember { RuntimeInstallState() }
    var creating by remember { mutableStateOf(false) }

    val runtimeReady = installedVersion.isNotEmpty()
    val sizeValid = SIZE_PATTERN.matches(screenSize.trim())
    val canSave = name.isNotBlank() && sizeValid && !install.running && !creating &&
        (editing != null || runtimeReady)

    fun startInstall() {
        install.running = true
        install.error = null
        install.percent = 0
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Executors.newSingleThreadExecutor().execute {
            val release = LinuxRuntimeInstaller.fetchRelease()
            val error = if (release == null) "Could not read the runtime catalog"
            else LinuxRuntimeInstaller.install(appContext, release) { phase, percent ->
                main.post { install.phase = phase; install.percent = percent }
            }
            main.post {
                install.running = false
                install.error = error
                if (error == null) installedVersion = LinuxRuntime.installedVersion(appContext)
            }
        }
    }

    fun save() {
        val size = screenSize.trim()
        if (editing != null) {
            editing.setName(name.trim())
            editing.setScreenSize(size)
            editing.saveData()
            onDone()
            return
        }
        creating = true
        val data = JSONObject().apply {
            put("name", name.trim())
            put("screenSize", size)
        }
        manager.createLinuxContainerAsync(data) { created ->
            creating = false
            if (created != null) onDone()
            else install.error = "Could not create the container"
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
                    when {
                        install.running -> {
                            val label = when (install.phase) {
                                LinuxRuntimeInstaller.Phase.DOWNLOAD -> "Downloading"
                                LinuxRuntimeInstaller.Phase.VERIFY -> "Verifying"
                                LinuxRuntimeInstaller.Phase.EXTRACT -> "Unpacking"
                                null -> "Starting"
                            }
                            Text("$label… ${install.percent}%")
                            LinearProgressIndicator(
                                progress = { install.percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        runtimeReady -> Text(
                            "Installed" + if (installedVersion.isNotEmpty()) " ($installedVersion)" else ""
                        )
                        else -> {
                            Text("The Linux userland is shared by all Linux containers. About 790 MB to download.")
                            Row { Button(onClick = ::startInstall) { Text("Download runtime") } }
                        }
                    }
                    install.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        if (!install.running && !runtimeReady) {
                            Row { OutlinedButton(onClick = ::startInstall) { Text("Try again") } }
                        }
                    }
                }
            }

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
                Text(if (editing != null) "Save" else "Create")
            }
        }
    }
}
