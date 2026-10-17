package com.pramod.videoeditor.ui.screens

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.pramod.videoeditor.coremodel.Clip
import com.pramod.videoeditor.coremodel.ClipType
import com.pramod.videoeditor.coremodel.Project
import com.pramod.videoeditor.ui.ScreenScaffold
import com.pramod.videoeditor.ui.navigation.Routes
import com.pramod.videoeditor.ui.session.EditorSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// ---------------- Splash ----------------
@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1200)
        onDone()
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pramod AI Video Editor", style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Create. Edit. Export.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
    }
}

// ---------------- Onboarding ----------------
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val pages = listOf(
        "AI Auto Edit" to "Pick a template, add clips — the app drafts your edit.",
        "64 Templates" to "Viral, cinematic, education, motivation and more.",
        "Pro Export" to "MP4 export in 9:16, 16:9 and 1:1, right on your phone.",
    )
    ScreenScaffold(title = "Welcome") { _ ->
        Spacer(Modifier.height(32.dp))
        Text(pages[page].first, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(pages[page].second, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { if (page < pages.lastIndex) page++ else onDone() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (page < pages.lastIndex) "Next" else "Get Started") }
    }
}

// ---------------- Home ----------------
class HomeViewModel : ViewModel() {
    private val _projectCount = MutableStateFlow(0)
    val projectCount: StateFlow<Int> = _projectCount
}

@Composable
fun HomeScreen(nav: NavController) {
    val vm = remember { HomeViewModel() }
    val count by vm.projectCount.collectAsState()
    ScreenScaffold(title = "Pramod AI Video Editor") { _ ->
        Text("Dashboard", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text("$count projects", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { nav.navigate(Routes.NEW_PROJECT) }, modifier = Modifier.fillMaxWidth()) {
            Text("New Project")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { nav.navigate(Routes.TEMPLATES) }, modifier = Modifier.fillMaxWidth()) {
            Text("Browse 64 Templates")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { nav.navigate(Routes.PROJECTS) }, modifier = Modifier.fillMaxWidth()) {
            Text("My Projects")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { nav.navigate(Routes.SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Settings")
        }
    }
}

// ---------------- New Project ----------------
class NewProjectViewModel : ViewModel() {
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name
    fun setName(v: String) { _name.value = v }

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    fun clearError() { _error.value = null }

    /**
     * Persists the typed name as a real project and makes it the session's
     * current project (via the canonical ui.session.EditorSession), then
     * calls [onCreated].
     */
    fun create(context: Context, onCreated: () -> Unit) {
        if (_creating.value) return
        viewModelScope.launch {
            _creating.value = true
            try {
                val appCtx = context.applicationContext
                EditorSession.ensureInit(appCtx)
                EditorSession.createProject(_name.value.ifBlank { "Untitled project" })
                onCreated()
            } catch (e: Exception) {
                _error.value = "Couldn't create the project: ${e.message}"
            } finally {
                _creating.value = false
            }
        }
    }
}

@Composable
fun NewProjectScreen(nav: NavController) {
    val vm = remember { NewProjectViewModel() }
    val name by vm.name.collectAsState()
    val creating by vm.creating.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current
    ScreenScaffold(title = "New Project", onBack = { nav.popBackStack() }) { showMessage ->
        val err = error
        LaunchedEffect(err) {
            if (err != null) {
                showMessage(err)
                vm.clearError()
            }
        }
        Text("Project name", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name, onValueChange = vm::setName,
            label = { Text("e.g. Diwali Short") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.create(context) { nav.navigate(Routes.MEDIA_PICKER) } },
            modifier = Modifier.fillMaxWidth(),
            enabled = !creating,
        ) { Text(if (creating) "Creating…" else "Create Project & Pick Media") }
    }
}

// ---------------- Shared editing session ----------------
// The single session store is com.pramod.videoeditor.ui.session.EditorSession
// (ui.session package) — the only place that owns the open project, playhead,
// and persistence. The tiny bridge below forwards screens to it.

/**
 * Returns the current project, creating and persisting a new one when the
 * session has none (e.g. the user opened the media picker directly).
 */
internal suspend fun ensureProject(context: Context, defaultName: String = "Untitled project"): Project {
    EditorSession.ensureInit(context.applicationContext)
    return EditorSession.ensureProject(defaultName)
}

/** Best-effort display name for a content URI. */
internal fun displayNameFor(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: uri.toString()
    } catch (_: Exception) {
        uri.lastPathSegment ?: uri.toString()
    }
}

// ---------------- Media Picker ----------------

class MediaPickerViewModel : ViewModel() {
    data class AddedClip(val name: String, val kind: String, val durationMs: Long)

    private val _added = MutableStateFlow<List<AddedClip>>(emptyList())
    val added: StateFlow<List<AddedClip>> = _added
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _notices = MutableStateFlow<List<String>>(emptyList())
    val notices: StateFlow<List<String>> = _notices
    private val _projectName = MutableStateFlow<String?>(null)
    val projectName: StateFlow<String?> = _projectName

    fun clearError() { _error.value = null }

    /**
     * Adds picked content URIs as real clips appended to the current project's
     * timeline (creating the project when the session has none). Photos become
     * 3-second clips; videos keep their probed duration (5s fallback when the
     * duration cannot be read, reported as a notice — never silently).
     */
    fun addMedia(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _error.value = null
            try {
                val appCtx = context.applicationContext
                val resolver = appCtx.contentResolver
                val project = ensureProject(appCtx)
                var cursorMs = project.clips.maxOfOrNull { it.endMs } ?: 0L
                val newClips = mutableListOf<Clip>()
                val addedUi = mutableListOf<AddedClip>()
                val failures = mutableListOf<String>()
                val warnings = mutableListOf<String>()
                for (uri in uris) {
                    val name = displayNameFor(appCtx, uri)
                    try {
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: SecurityException) {
                        failures += "Permission denied for \"$name\" — skipped."
                        continue
                    }
                    val mime = resolver.getType(uri) ?: guessMime(uri)
                    val kind = when {
                        mime?.startsWith("image/") == true -> "Photo"
                        mime?.startsWith("video/") == true -> "Video"
                        else -> null
                    }
                    if (kind == null) {
                        failures += "\"$name\" is not a photo or video — skipped."
                        continue
                    }
                    var durationMs = if (kind == "Photo") 3_000L else probeVideoDuration(appCtx, uri)
                    if (durationMs <= 0) {
                        durationMs = 5_000L
                        warnings += "\"$name\": duration unreadable — used 5s."
                    }
                    newClips += Clip(
                        id = UUID.randomUUID().toString(),
                        type = ClipType.VIDEO,
                        sourceUri = uri.toString(),
                        startMs = cursorMs,
                        endMs = cursorMs + durationMs,
                        trimInMs = 0,
                        trimOutMs = durationMs,
                    )
                    addedUi += AddedClip(name, kind, durationMs)
                    cursorMs += durationMs
                }
                if (newClips.isNotEmpty()) {
                    EditorSession.saveProject(project.copy(clips = project.clips + newClips))
                    _projectName.value = project.name
                    _added.value = _added.value + addedUi
                } else if (failures.isEmpty()) {
                    failures += "Nothing was added."
                }
                if (warnings.isNotEmpty()) _notices.value = _notices.value + warnings
                if (failures.isNotEmpty()) _error.value = failures.joinToString("\n")
            } catch (e: Exception) {
                _error.value = "Could not add media: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun guessMime(uri: Uri): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase() ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun probeVideoDuration(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        } finally {
            try { retriever.release() } catch (_: Exception) { /* best effort */ }
        }
    }
}

@Composable
fun MediaPickerScreen(nav: NavController) {
    val vm = remember { MediaPickerViewModel() }
    val context = LocalContext.current
    val added by vm.added.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val notices by vm.notices.collectAsState()
    val projectName by vm.projectName.collectAsState()

    val photoPickerAvailable = remember { PickVisualMedia.isPhotoPickerAvailable(context) }
    val pickVisual = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris: List<Uri> -> vm.addMedia(context, uris) }
    val openDocuments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> vm.addMedia(context, uris) }

    ScreenScaffold(title = "Pick Media", onBack = { nav.popBackStack() }) { showMessage ->
        val err = error
        LaunchedEffect(err) { if (err != null) showMessage(err) }

        Text(
            "Pick photos and videos from your device. They are added as clips " +
                "to the current project timeline, in order." +
                if (!photoPickerAvailable) " (System file picker — your device has no photo picker.)" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                vm.clearError()
                if (photoPickerAvailable) {
                    pickVisual.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                } else {
                    openDocuments.launch(arrayOf("image/*", "video/*"))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) { Text(if (photoPickerAvailable) "Open Photo Picker" else "Open File Picker") }
        Spacer(Modifier.height(8.dp))

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
        if (err != null) {
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        notices.forEach { n ->
            Text(n, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (notices.isNotEmpty()) Spacer(Modifier.height(8.dp))

        val pn = projectName
        if (pn != null) {
            Text("Project: $pn — ${added.size} clip(s) added",
                style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(added) { a ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(a.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${a.kind} • ${a.durationMs / 1000.0}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { nav.navigate(Routes.TIMELINE) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue to Timeline") }
    }
}

// ---------------- My Projects ----------------
class ProjectsViewModel(appContext: Context) : ViewModel() {
    private val app = appContext.applicationContext

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    fun clearError() { _error.value = null }

    init {
        EditorSession.ensureInit(app)
        viewModelScope.launch {
            try {
                EditorSession.ensureLoaded()
                EditorSession.observeProjects().collect {
                    _projects.value = it
                    _loading.value = false
                }
            } catch (e: Exception) {
                _error.value = "Couldn't load projects: ${e.message}"
                _loading.value = false
            }
        }
    }

    /** Opens the project as the session's current project and goes to the timeline. */
    fun openProject(id: String, nav: NavController) {
        viewModelScope.launch {
            try {
                EditorSession.openProject(id)
            } catch (_: Exception) {
                // Navigation still shows an empty state if the open failed.
            }
            nav.navigate(Routes.TIMELINE)
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            try {
                EditorSession.deleteProject(id)
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }
}

private val projectsDateFmt = java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault())

@Composable
fun MyProjectsScreen(nav: NavController) {
    val context = LocalContext.current
    val vm = remember { ProjectsViewModel(context.applicationContext) }
    val projects by vm.projects.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    ScreenScaffold(title = "My Projects", onBack = { nav.popBackStack() }) { showMessage ->
        val err = error
        LaunchedEffect(err) {
            if (err != null) {
                showMessage(err)
                vm.clearError()
            }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (projects.isEmpty()) {
            Text("No projects yet. Create your first one!",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { nav.navigate(Routes.NEW_PROJECT) }) { Text("New Project") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { p ->
                    Card(
                        onClick = { vm.openProject(p.id, nav) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${p.clips.size} clip(s) • Updated ${projectsDateFmt.format(java.util.Date(p.updatedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = { vm.deleteProject(p.id) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { nav.navigate(Routes.RECOVERY) }, modifier = Modifier.fillMaxWidth()) {
            Text("Recover Projects")
        }
    }
}

// ---------------- Project Recovery ----------------
class RecoveryViewModel : ViewModel() {
    private val _found = MutableStateFlow<List<String>>(emptyList())
    val found: StateFlow<List<String>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    fun clearError() { _error.value = null }

    /** Lists project ids found on disk but missing from the database index. */
    fun scan(context: Context) {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            try {
                val appCtx = context.applicationContext
                EditorSession.ensureInit(appCtx)
                _found.value = EditorSession.findRecoverable()
            } catch (e: Exception) {
                _error.value = "Scan failed: ${e.message}"
            } finally {
                _scanning.value = false
            }
        }
    }

    /** Re-indexes the orphaned project JSON into the database and opens it. */
    fun restore(id: String, context: Context, nav: NavController) {
        viewModelScope.launch {
            try {
                val appCtx = context.applicationContext
                EditorSession.ensureInit(appCtx)
                // openProject() restores from disk, which re-indexes the JSON into Room.
                EditorSession.openProject(id)
                nav.navigate(Routes.TIMELINE)
            } catch (e: Exception) {
                _error.value = "Restore failed: ${e.message}"
            }
        }
    }
}

@Composable
fun ProjectRecoveryScreen(nav: NavController) {
    val vm = remember { RecoveryViewModel() }
    val found by vm.found.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current
    ScreenScaffold(title = "Project Recovery", onBack = { nav.popBackStack() }) { showMessage ->
        val err = error
        LaunchedEffect(err) {
            if (err != null) {
                showMessage(err)
                vm.clearError()
            }
        }
        Text(
            "Scans app storage for project files that are missing from the database (e.g. after a crash).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.scan(context) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !scanning,
        ) { Text(if (scanning) "Scanning…" else "Scan Now") }
        Spacer(Modifier.height(16.dp))
        if (found.isEmpty()) {
            Text("No recoverable projects found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found) { id ->
                    Card(
                        onClick = { vm.restore(id, context, nav) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(id, style = MaterialTheme.typography.bodyMedium)
                            Text("Tap to restore and open",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
