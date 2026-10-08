package com.winlator.cmod.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.core.ProtonPackageManager
import com.winlator.cmod.ui.settings.GlobalLsfgCard

private val bundledRuntimeId = "bundled:${ProtonPackageManager.DEFAULT_IDENTIFIER}"
private val bundledRuntimeName = ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER)?.title
    ?: "Proton 10.0-5 arm64ec"

private const val frameGenerationCategory = "Frame Generation"

private val componentCategories = listOf(
    "Recommended", "Wine & Proton", "DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64", "AdrenoTools", frameGenerationCategory
)

private val latestRecommendedTypes = setOf("DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64")

private fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
    val count = maxOf(left.size, right.size)
    for (index in 0 until count) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

private fun componentVersionParts(type: String, name: String): List<Int> {
    var clean = name.lowercase()
        .replace("arm64ec", "")
        .replace("x86_64", "")

    if (type == "FEXCore") {
        val token = Regex("\\d{6}|\\d{4}(?:\\.\\d+)?").find(clean)?.value.orEmpty()
        val base = token.substringBefore('.')
        val suffix = token.substringAfter('.', "").toIntOrNull()
        return when (base.length) {
            6 -> listOf(
                base.substring(0, 2).toIntOrNull() ?: 0,
                base.substring(2, 4).toIntOrNull() ?: 0,
                base.substring(4, 6).toIntOrNull() ?: 0
            )
            4 -> buildList {
                add(base.substring(0, 2).toIntOrNull() ?: 0)
                add(base.substring(2, 4).toIntOrNull() ?: 0)
                if (suffix != null) add(suffix)
            }
            else -> emptyList()
        }
    }

    if ((type == "Box64" || type == "WOWBox64") && Regex("^0?\\d{3}(?:\\D|$)").containsMatchIn(clean)) {
        val digits = Regex("\\d{3}").find(clean)?.value.orEmpty()
        if (digits.length == 3) {
            return listOf(
                digits.substring(0, 1).toIntOrNull() ?: 0,
                digits.substring(1, 2).toIntOrNull() ?: 0,
                digits.substring(2, 3).toIntOrNull() ?: 0
            )
        }
    }

    if (type == "DXVK" && clean.startsWith("11.1")) {
        clean = "1.1.1" + clean.removePrefix("11.1")
    }

    val token = Regex("\\d+(?:\\.\\d+){0,3}").find(clean)?.value ?: return emptyList()
    return token.split('.').map { it.toIntOrNull() ?: 0 }
}

private val componentTypeOrder = listOf("Proton", "Wine", "DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64", "AdrenoTools")

/** Every number in the name, e.g. "Turnip_Gen8_V36" -> [8, 36], so drivers and 11.0-2 style suffixes sort sensibly. */
private fun nameNumbers(name: String): List<Int> =
    Regex("\\d+").findAll(name.lowercase().replace("arm64ec", "").replace("x86_64", ""))
        .mapNotNull { it.value.toIntOrNull() }.toList()

/** Type order first, then newest version first, then A-Z. */
private val componentComparator = Comparator<OnboardingComponent> { left, right ->
    val byType = componentTypeOrder.indexOf(left.type).compareTo(componentTypeOrder.indexOf(right.type))
    if (byType != 0) return@Comparator byType
    val byVersion = compareVersionParts(
        componentVersionParts(right.type, right.name),
        componentVersionParts(left.type, left.name)
    )
    if (byVersion != 0) return@Comparator byVersion
    val byNumbers = compareVersionParts(nameNumbers(right.name), nameNumbers(left.name))
    if (byNumbers != 0) byNumbers else left.name.compareTo(right.name, ignoreCase = true)
}

private fun inCategory(category: String, component: OnboardingComponent, recommendedIds: Set<String>): Boolean = when (category) {
    "Recommended" -> component.id in recommendedIds
    "Wine & Proton" -> component.type == "Wine" || component.type == "Proton"
    else -> component.type == category
}

private fun recommendedComponentIds(all: List<OnboardingComponent>): Set<String> {
    val result = all.filter { it.recommended && it.type !in latestRecommendedTypes }
        .mapTo(linkedSetOf()) { it.id }

    latestRecommendedTypes.forEach { type ->
        all.withIndex()
            .filter { it.value.type == type }
            .maxWithOrNull { left, right ->
                val byVersion = compareVersionParts(
                    componentVersionParts(type, left.value.name),
                    componentVersionParts(type, right.value.name)
                )
                if (byVersion != 0) byVersion else left.index.compareTo(right.index)
            }
            ?.value
            ?.let { result.add(it.id) }
    }
    return result
}

@Composable
internal fun OnboardingComponentsScreen(
    ready: State<Boolean>,
    progress: State<Int>,
    bundledInstalled: State<Boolean>,
    bundledInUse: State<Boolean>,
    all: List<OnboardingComponent>,
    installing: String?,
    installingLabel: String?,
    installingProgress: Int,
    managerMode: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    cb: OnboardingCallbacks,
    onSkip: (() -> Unit)? = null
) {
    var category by rememberSaveable { mutableStateOf("Recommended") }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val recommendedIds = remember(all) { recommendedComponentIds(all) }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(all, category, recommendedIds, query) {
        val q = query.trim().lowercase()
        all.filter { inCategory(category, it, recommendedIds) }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedWith(componentComparator)
    }
    val categoryCounts = remember(all, recommendedIds) {
        componentCategories.associateWith { c -> if (c == frameGenerationCategory) null else all.count { inCategory(c, it, recommendedIds) } }
    }
    val hasInstalledRuntime = bundledInstalled.value || all.any {
        it.installed && (it.type == "Wine" || it.type == "Proton") && !it.runtimeIdentifier.isNullOrBlank()
    }
    val showBundled = category == "Recommended" || category == "Wine & Proton"
    val showLocalInstallProgress = installing == "local" || installing == "driver-local"

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (landscape) {
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(Modifier.weight(.9f).fillMaxHeight()) {
                    Text("Choose components", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (managerMode) "Install and manage runtime versions."
                        else "Install a Wine or Proton layer before continuing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showLocalInstallProgress) {
                        Spacer(Modifier.height(14.dp))
                        InstallProgressCard(installingLabel, installingProgress)
                    }
                    Spacer(Modifier.height(14.dp))
                    CategoryList(category, categoryCounts, Modifier.weight(1f).fillMaxWidth()) { category = it }
                    if (!managerMode && !hasInstalledRuntime) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (!ready.value) "Wait for $bundledRuntimeName to finish installing, or install another Wine/Proton version."
                            else "Install at least one Wine or Proton version to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (category == frameGenerationCategory) {
                    Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        GlobalLsfgCard(collapsible = false)
                    }
                } else {
                    // The search field stays above the list, outside it, so it never scrolls away.
                    Column(Modifier.weight(1.2f).fillMaxHeight()) {
                        ComponentSearchField(query) { query = it }
                        Spacer(Modifier.height(8.dp))
                        ComponentList(
                            visible,
                            all.isEmpty(),
                            installing,
                            installingLabel,
                            installingProgress,
                            cb,
                            Modifier.weight(1f).fillMaxWidth()
                        ) {
                            LocalInstallButton(category, cb, Modifier.fillMaxWidth())
                            if (showBundled) {
                                CoreComponentCard(
                                    ready = ready,
                                    progress = progress,
                                    installed = bundledInstalled.value,
                                    inUse = bundledInUse.value,
                                    busy = installing == bundledRuntimeId,
                                    locked = installing != null,
                                    onInstall = cb::onInstallBundledRuntime,
                                    onRemove = cb::onRemoveBundledRuntime
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Choose components", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (managerMode) "Install and manage runtime versions."
                        else "Install as many versions as you want. At least one Wine or Proton is required.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showLocalInstallProgress) {
                        Spacer(Modifier.height(16.dp))
                        InstallProgressCard(installingLabel, installingProgress)
                    }
                    Spacer(Modifier.height(16.dp))
                    CategorySelector(category) { category = it }
                    Spacer(Modifier.height(10.dp))
                    ComponentSearchField(query) { query = it }
                    if (category != frameGenerationCategory) {
                        Spacer(Modifier.height(8.dp))
                        LocalInstallButton(category, cb, Modifier)
                    }
                    if (showBundled) {
                        Spacer(Modifier.height(10.dp))
                        CoreComponentCard(
                            ready = ready,
                            progress = progress,
                            installed = bundledInstalled.value,
                            inUse = bundledInUse.value,
                            busy = installing == bundledRuntimeId,
                            locked = installing != null,
                            onInstall = cb::onInstallBundledRuntime,
                            onRemove = cb::onRemoveBundledRuntime
                        )
                    }
                }
                if (category == frameGenerationCategory) item { GlobalLsfgCard(collapsible = false) }
                else if (all.isEmpty()) item { LoadingCard() }
                else items(visible, key = { it.id }) {
                    ComponentCard(
                        it,
                        installing == it.id,
                        installing != null,
                        installingLabel,
                        installingProgress,
                        cb
                    )
                }
                if (!managerMode && !hasInstalledRuntime) {
                    item {
                        Text(
                            if (!ready.value) "Continue unlocks when $bundledRuntimeName finishes installing or another Wine/Proton layer is installed."
                            else "Install at least one Wine or Proton version to continue.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        ComponentsFooter(
            back = onBack,
            next = onContinue,
            landscape = landscape,
            nextEnabled = managerMode || hasInstalledRuntime,
            nextLabel = if (managerMode) "Done" else "Continue",
            skip = if (managerMode) null else onSkip
        )
    }
}

@Composable
private fun ComponentList(
    list: List<OnboardingComponent>,
    loading: Boolean,
    installing: String?,
    installingLabel: String?,
    installingProgress: Int,
    cb: OnboardingCallbacks,
    modifier: Modifier,
    header: (@Composable ColumnScope.() -> Unit)? = null
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = header) }
        if (loading) item { LoadingCard() }
        else if (list.isEmpty()) item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Text(
                    "No components available in this category.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else items(list, key = { it.id }) {
            ComponentCard(
                it,
                installing == it.id,
                installing != null,
                installingLabel,
                installingProgress,
                cb
            )
        }
    }
}

@Composable
private fun ComponentSearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search components") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Clear") }
            }
        },
        shape = RoundedCornerShape(14.dp)
    )
}

/** "Install local ..." for the selected type; none for Frame Generation, which has no packages. */
@Composable
private fun LocalInstallButton(category: String, cb: OnboardingCallbacks, modifier: Modifier) {
    when (category) {
        frameGenerationCategory -> Unit
        "AdrenoTools" -> OutlinedButton(onClick = { cb.onBrowseDriver() }, modifier = modifier) { Text("Install local driver") }
        else -> OutlinedButton(onClick = { cb.onBrowseLocal() }, modifier = modifier) { Text("Install local component") }
    }
}

/** Vertical category picker for the landscape layout: one row per component type, with its count. */
@Composable
private fun CategoryList(selected: String, counts: Map<String, Int?>, modifier: Modifier, select: (String) -> Unit) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(componentCategories, key = { it }) { category ->
            val isSelected = category == selected
            Surface(
                onClick = { select(category) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        category,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    counts[category]?.let {
                        Text(it.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySelector(selected: String, select: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        componentCategories.forEach {
            Surface(
                onClick = { select(it) },
                shape = RoundedCornerShape(10.dp),
                color = if (selected == it) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(it, Modifier.padding(horizontal = 13.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CoreComponentCard(
    ready: State<Boolean>,
    progress: State<Int>,
    installed: Boolean,
    inUse: Boolean,
    busy: Boolean,
    locked: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(bundledRuntimeName, fontWeight = FontWeight.SemiBold)
                val status = when {
                    busy -> "Working…"
                    installed && inUse -> "Bundled • Installed • In use"
                    installed -> "Bundled • Installed"
                    !ready.value -> "Bundled • Installing ${progress.value}%"
                    else -> "Bundled • Not installed"
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            when {
                busy || (!ready.value && !installed) -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                installed -> OutlinedButton(onClick = onRemove, enabled = !locked && !inUse) {
                    Icon(Icons.Outlined.DeleteOutline, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (inUse) "In use" else "Delete")
                }
                else -> OutlinedButton(onClick = onInstall, enabled = !locked) {
                    Icon(Icons.Outlined.Download, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Install")
                }
            }
        }
    }
}

@Composable
private fun ComponentCard(
    item: OnboardingComponent,
    busy: Boolean,
    locked: Boolean,
    installingLabel: String?,
    installingProgress: Int,
    cb: OnboardingCallbacks
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        busy && installingProgress >= 0 ->
                            "${installingLabel ?: "Installing"} • ${installingProgress}%"
                        busy -> installingLabel ?: "Working…"
                        item.inUse -> "${item.type} • In use"
                        else -> item.type
                    }
                    Text(
                        status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                else if (item.installed && item.removable) {
                    OutlinedButton(onClick = { cb.onRemove(item.id) }, enabled = !locked && !item.inUse) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Spacer(Modifier.width(5.dp))
                        Text(if (item.inUse) "In use" else "Delete")
                    }
                } else if (!item.installed) {
                    OutlinedButton(onClick = { cb.onInstall(item.id) }, enabled = !locked) { Text("Download") }
                } else Icon(Icons.Outlined.Check, null)
            }
            if (busy) {
                Spacer(Modifier.height(9.dp))
                if (installingProgress >= 0) {
                    LinearProgressIndicator(
                        progress = { installingProgress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun InstallProgressCard(label: String?, progress: Int) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Component installation", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (progress >= 0) "${label ?: "Installing"} • ${progress}%"
                        else label ?: "Installing component…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }
            Spacer(Modifier.height(10.dp))
            if (progress >= 0) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Text("Loading component catalog…")
        }
    }
}

@Composable
private fun ComponentsFooter(
    back: () -> Unit,
    next: () -> Unit,
    landscape: Boolean,
    nextEnabled: Boolean,
    nextLabel: String,
    skip: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = if (landscape) 22.dp else 20.dp,
                vertical = if (landscape) 8.dp else 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = back,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Back") }
            if (skip != null) {
                TextButton(
                    onClick = skip,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Skip for now") }
            }
            Button(
                onClick = next,
                enabled = nextEnabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text(nextLabel) }
        }
    }
}