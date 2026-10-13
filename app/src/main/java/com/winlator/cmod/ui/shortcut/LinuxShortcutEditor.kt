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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.gameprofile.GameProfile
import com.winlator.cmod.gameprofile.GameProfileCatalog
import com.winlator.cmod.gameprofile.GameProfiles
import com.winlator.cmod.linux.LinuxEmulator
import com.winlator.cmod.linux.LinuxLaunchOptions
import com.winlator.cmod.linux.LinuxSession
import com.winlator.cmod.lsfg.LsfgManager
import com.winlator.cmod.ui.settings.EditorCategory
import com.winlator.cmod.ui.settings.GroupedSettings
import com.winlator.cmod.ui.container.ResolutionSetting
import com.winlator.cmod.ui.container.isValidScreenSize
import com.winlator.cmod.ui.container.UPSCALER_MODES
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var touch by remember { mutableStateOf(shortcut.getExtra("simTouchScreen")) }
    var steamClient by remember { mutableStateOf(shortcut.getExtra(com.winlator.cmod.linux.LinuxSession.EXTRA_STEAM_MINIMAL)) }
    var multiplier by remember { mutableStateOf(shortcut.getExtra(LsfgManager.EXTRA_MULTIPLIER)) }
    var flowScale by remember { mutableStateOf(shortcut.getExtra(LsfgManager.EXTRA_FLOW_SCALE)) }
    // The game profile: "" follows the registry (a Steam game's own app), "none" turns it off, anything else is a game's id.
    var profileId by remember { mutableStateOf(shortcut.getExtra(GameProfiles.EXTRA_PROFILE)) }
    var profileVersion by remember { mutableStateOf(shortcut.getExtra(GameProfiles.EXTRA_VERSION)) }
    var noSteam by remember { mutableStateOf(shortcut.getExtra(GameProfiles.EXTRA_NO_STEAM)) }
    var gameRoot by remember { mutableStateOf(shortcut.getExtra(GameProfiles.EXTRA_ROOT)) }
    // The registry's games: what is on hand at once, then what the network has.
    var games by remember { mutableStateOf<List<GameProfileCatalog.IndexEntry>>(emptyList()) }
    var profile by remember { mutableStateOf<GameProfile?>(null) }
    LaunchedEffect(Unit) {
        games = withContext(Dispatchers.IO) { GameProfileCatalog.index(context, false) }
        games = withContext(Dispatchers.IO) { GameProfileCatalog.index(context, true) }
    }
    val automaticId = remember(games) {
        if (shortcut.isSteamGame) games.firstOrNull { it.steamAppId == LinuxSession.steamAppId(shortcut.path) }?.id ?: "" else ""
    }
    val profileIdNow = if (profileId == GameProfiles.NONE) "" else profileId.ifEmpty { automaticId }
    LaunchedEffect(profileIdNow) {
        profile = if (profileIdNow.isEmpty()) null else withContext(Dispatchers.IO) {
            GameProfileCatalog.game(context, profileIdNow, false) ?: GameProfileCatalog.game(context, profileIdNow, true)
        }
    }
    // What the profile makes of this shortcut as it is being edited, what it would make with the version recognised and with
    // the "without Steam" choice left to the profile.
    val effective = remember(profile, profileVersion, noSteam, gameRoot) {
        profile?.let { GameProfiles.resolve(context, container, shortcut, it, profileVersion, noSteam, gameRoot) }
    }
    val detected = remember(profile, gameRoot) {
        profile?.let { GameProfiles.resolve(context, container, shortcut, it, "", "", gameRoot) }
    }
    val profileDefault = remember(profile, profileVersion, gameRoot) {
        profile?.let { GameProfiles.resolve(context, container, shortcut, it, profileVersion, "", gameRoot) }
    }
    // A Steam game that is not started without Steam goes through the client, and so runs at the device's own size.
    val throughSteam = shortcut.isSteamGame && effective?.runsWithoutSteam() != true
    fun profileExtra(key: String, label: (String) -> String): String? = effective?.extras?.get(key)?.let(label)

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
        shortcut.putExtra("simTouchScreen", touch.ifEmpty { null })
        shortcut.putExtra(com.winlator.cmod.linux.LinuxSession.EXTRA_STEAM_MINIMAL, steamClient.ifEmpty { null })
        shortcut.putExtra(UPSCALER, upscaler.ifEmpty { null })
        shortcut.putExtra(LsfgManager.EXTRA_MULTIPLIER, multiplier.ifEmpty { null })
        shortcut.putExtra(LsfgManager.EXTRA_FLOW_SCALE, flowScale.ifEmpty { null })
        shortcut.putExtra(GameProfiles.EXTRA_PROFILE, profileId.ifEmpty { null })
        shortcut.putExtra(GameProfiles.EXTRA_VERSION, profileVersion.ifEmpty { null })
        shortcut.putExtra(GameProfiles.EXTRA_NO_STEAM, noSteam.ifEmpty { null })
        shortcut.putExtra(GameProfiles.EXTRA_ROOT, gameRoot.trim().ifEmpty { null })
        shortcut.saveData()
        onClose()
    }

    val graphicsSummary = if (throughSteam) "native size (Steam)" else listOf(
        if (size.isEmpty()) (if (effective?.resolution?.isNotEmpty() == true) "game profile size" else "container size") else if (size.trim() == "native") "native size" else size.trim(),
        when (fit) { "1" -> "fit"; "0" -> "1:1"; else -> "container scaling" },
        UPSCALER_MODES.firstOrNull { it.first == upscaler && it.first != "0" }?.second?.substringBefore(" (")
            ?: if (upscaler == "0") "no upscaler" else "container upscaler"
    ).joinToString(" · ")
    val frameGenerationSummary = when (multiplier) {
        "" -> "Container default ($containerMultiplier)"
        "0" -> "Off"
        else -> "${multiplier}x" + if (flowScale.isNotEmpty()) " · flow $flowScale" else ""
    }

    val gameSummary = profile?.let { it.name + " · " + (effective?.version?.label ?: "version not recognised") }
        ?: if (profileId == GameProfiles.NONE) "None (turned off)" else "None"

    val categories = listOfNotNull(
        EditorCategory("game", "Game profile", gameSummary, false) {
            SettingsCard {
                SettingMappedChoice(
                    "Game",
                    profileId,
                    LinkedHashMap<String, String>().apply {
                        put(
                            "",
                            if (!shortcut.isSteamGame) "None"
                            else if (automaticId.isEmpty()) "Automatic (the registry has none for this game)"
                            else "Automatic (${games.firstOrNull { it.id == automaticId }?.name ?: automaticId})"
                        )
                        if (shortcut.isSteamGame) put(GameProfiles.NONE, "None: don't use a profile")
                        games.forEach { put(it.id, it.name) }
                        if (profileId.isNotEmpty() && profileId != GameProfiles.NONE && profileId !in this) put(profileId, "$profileId (not in the registry)")
                    }
                ) {
                    profileId = it
                    profileVersion = ""
                    noSteam = ""
                }
                val current = profile
                if (current != null) {
                    SettingsDivider()
                    SettingMappedChoice(
                        "Version",
                        profileVersion,
                        LinkedHashMap<String, String>().apply {
                            put("", "Recognise it (now: ${detected?.version?.label ?: "not recognised"})")
                            current.versions.forEach { put(it.id, it.label + if (it.status == GameProfile.STATUS_BROKEN) " (does not work)" else "") }
                        }
                    ) { profileVersion = it }
                    if (!shortcut.isSteamGame) {
                        SettingsDivider()
                        OutlinedTextField(
                            value = gameRoot,
                            onValueChange = { gameRoot = it },
                            label = { Text("Game folder") },
                            placeholder = { Text("/root/programs/${current.name.replace(' ', '_')}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(14.dp)
                        )
                    }
                    val shown = effective
                    if (shown != null && shown.hasNoSteam) {
                        SettingsDivider()
                        SettingMappedChoice(
                            "Launch without Steam",
                            noSteam,
                            linkedMapOf(
                                "" to "Game profile default (${if (profileDefault?.noSteam == true) "On" else "Off"})",
                                "1" to "On",
                                "0" to "Off"
                            )
                        ) { noSteam = it }
                        Text(
                            if (shortcut.isSteamGame) "On: the game's own program starts straight away, with ${shown.noSteamArg}, and the Steam client does not. Off: the game is started through Steam."
                            else "On: ${shown.noSteamArg} is added to the program's arguments.",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SettingsDivider()
                    PreviewRow("Program", shown?.executableGuest ?: "—")
                    val version = shown?.version
                    if (shown != null && version != null) {
                        SettingsDivider()
                        PreviewRow(
                            "Status",
                            when (version.status) {
                                GameProfile.STATUS_WORKS -> "Works"
                                GameProfile.STATUS_BROKEN -> "Does not work"
                                else -> "Not tried"
                            } + (if (version.testedOn.isNotEmpty()) " · ${version.testedOn}" else "") +
                                (if (shown.how.isNotEmpty()) " · found by ${shown.how}" else "") +
                                (if (version.notes.isNotEmpty()) "\n${version.notes}" else "")
                        )
                    }
                }
            }
            if (profile == null) {
                Text(
                    "A game profile from the registry gives the shortcut the program to start, the arguments and the resolution that are " +
                        "known to work for a version of the game. Whatever you set in the groups below wins over it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        // A Steam game started through the client has launch options of its own (in the game's properties in Steam).
        if (throughSteam) null else EditorCategory("launch", "Launch options", options.ifBlank { "None" }, false) {
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
                if (throughSteam) {
                    Text(
                        "Native resolution: a game started through Steam runs in the Steam client's session, at the device's own size. " +
                            "Set its resolution in Steam, or in the game.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ResolutionSetting(
                        size,
                        effective?.resolution?.takeIf { it.isNotEmpty() }
                            ?.let { "Game profile default (${if (it == "native") "native size" else it})" }
                            ?: "Container default ($containerSize)"
                    ) { size = it }
                }
                SettingsDivider()
                SettingMappedChoice(
                    "Scaling",
                    fit,
                    linkedMapOf(
                        "" to (profileExtra(FIT) { "Game profile default (${if (it == "1") "fit" else "1:1"})" } ?: "Container default ($containerFit)"),
                        "1" to "Fit to screen (keep proportions)",
                        "0" to "Original size (1:1)"
                    )
                ) { fit = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Upscaler (needs native output and Fit to screen)",
                    upscaler,
                    LinkedHashMap<String, String>().apply {
                        put(
                            "",
                            profileExtra(UPSCALER) { mode -> "Game profile default (${UPSCALER_MODES.firstOrNull { it.first == mode }?.second?.substringBefore(" (") ?: "Off"})" }
                                ?: "Container default ($containerUpscaler)"
                        )
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
                    linkedMapOf(
                        "" to (profileExtra(LsfgManager.EXTRA_MULTIPLIER) { m -> "Game profile default (${if (m == "0" || m.isEmpty()) "Off" else "${m}x"})" }
                            ?: "Container default ($containerMultiplier)"),
                        "0" to "Off", "2" to "2x", "3" to "3x", "4" to "4x"
                    )
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
        EditorCategory(
            "input", "Input",
            when (touch) { "1" -> "touchscreen"; "0" -> "touchpad"; else -> "container default" },
            false
        ) {
            SettingsCard {
                SettingMappedChoice(
                    "Touchscreen mode",
                    touch,
                    linkedMapOf(
                        "" to (profileExtra("simTouchScreen") { "Game profile default (${if (it == "1") "touchscreen" else "touchpad"})" }
                            ?: "Container default (${if (container.getExtra(com.winlator.cmod.linux.LinuxSession.EXTRA_TOUCHSCREEN) == "0") "touchpad" else "touchscreen"})"),
                        "1" to "Touchscreen: a tap clicks where you touch",
                        "0" to "Touchpad: a finger moves the pointer"
                    )
                ) { touch = it }
            }
        },
        if (!throughSteam) null else EditorCategory(
            "steam", "Steam client",
            if (steamClient == "0") "the whole client" else "minimal (no window)",
            false
        ) {
            SettingsCard {
                SettingMappedChoice(
                    "Steam client",
                    steamClient,
                    linkedMapOf(
                        "" to "Minimal: no Steam window, saves memory (once you are signed in)",
                        "0" to "Whole client: its window stays open"
                    )
                ) { steamClient = it }
            }
            Text(
                "To start a game the Steam client needs neither its window nor the web helper that draws it, which takes " +
                    "most of its memory. The minimal start works once you have signed in with \"Remember me\"; until then the " +
                    "whole client opens so that you can.",
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
