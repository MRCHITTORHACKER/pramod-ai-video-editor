package com.pramod.videoeditor.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.pramod.videoeditor.coremodel.SfxEvent
import com.pramod.videoeditor.coremodel.EffectInstance
import com.pramod.videoeditor.ui.session.EditorSession
import com.pramod.videoeditor.featureaudio.AudioMixerController
import com.pramod.videoeditor.featureaudio.SfxLibrary
import com.pramod.videoeditor.featureaudio.SfxPreviewPlayer
import com.pramod.videoeditor.featurevfx.VfxDefinitions
import com.pramod.videoeditor.ui.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.UUID

// ---------------- VFX Library ----------------
@Composable
fun VfxLibraryScreen(nav: NavController) {
    var lowEffects by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(VfxDefinitions.all.firstOrNull()?.id) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    ScreenScaffold(title = "VFX Library", onBack = { nav.popBackStack() }) { showMessage ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Effects (${VfxDefinitions.all.size})", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { lowEffects = !lowEffects }) {
                Text(if (lowEffects) "Low FX: ON" else "Low FX: OFF")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Low-effects mode reduces intensity on constrained devices and avoids rapid flashes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(VfxDefinitions.all) { def ->
                val selected = def.id == selectedId
                Card(
                    onClick = { selectedId = def.id },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Text(
                        (if (selected) "✓ " else "") + def.displayName,
                        modifier = Modifier.height(40.dp),
                    )
                    Text(
                        if (lowEffects) "Fallback: ${def.lowEndFallback}" else "Render: ${def.renderPath}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val def = VfxDefinitions.all.find { it.id == selectedId }
                if (def == null) {
                    showMessage("Pick an effect first.")
                    return@Button
                }
                scope.launch {
                    try {
                        val appCtx = context.applicationContext
                        EditorSession.ensureInit(appCtx)
                        val project = EditorSession.project.value
                        if (project == null) {
                            showMessage("Open or create a project first — the effect needs a timeline.")
                            return@launch
                        }
                        val start = EditorSession.playheadMs.value.coerceAtLeast(0L)
                        val instance = EffectInstance(
                            id = UUID.randomUUID().toString(),
                            effectId = def.id,
                            startMs = start,
                            durationMs = 2_000L,
                            intensity = 0.7f,
                            params = def.params.mapValues { it.value.third },
                        )
                        EditorSession.updateEffects { it + instance }
                        showMessage("\"${def.displayName}\" added at the playhead (2s).")
                    } catch (e: Exception) {
                        showMessage("Couldn't add the effect: ${e.message}")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add Selected to Timeline") }
    }
}

// ---------------- SFX Library ----------------
class SfxLibraryViewModel : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /**
     * Adds the selected category as a real [SfxEvent] at the end of the
     * current project's timeline (there is no playhead state in this screen's
     * scope; appending at the end is the honest, deterministic placement).
     * Uses the real asset duration from [SfxLibrary.durationsMs].
     */
    fun addAtEnd(context: Context, category: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val appCtx = context.applicationContext
                val assetId = SfxLibrary.assetIdFor(category)
                if (SfxLibrary.resolve(assetId) == null) {
                    onResult("No audio asset for '$category' — not added.")
                    return@launch
                }
                val durationMs = SfxLibrary.durationsMs[category]?.toLong()
                if (durationMs == null || durationMs <= 0) {
                    onResult("No duration known for '$category' — not added.")
                    return@launch
                }
                val project = ensureProject(appCtx)
                val startMs = project.clips.maxOfOrNull { it.endMs } ?: 0L
                val event = SfxEvent(
                    assetId = assetId,
                    startMs = startMs,
                    durationMs = durationMs,
                    gainDb = -6f,
                    track = "sfx",
                    enabled = true,
                    validationResult = "ok",
                )
                EditorSession.saveProject(project.copy(sfx = project.sfx + event))
                onResult("Added '$category' at end of timeline (${durationMs}ms)")
            } catch (e: Exception) {
                onResult("Could not add SFX: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }
}

@Composable
fun SfxLibraryScreen(nav: NavController) {
    val vm = remember { SfxLibraryViewModel() }
    val context = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    val busy by vm.busy.collectAsState()

    // Stop any in-flight preview when leaving the screen.
    DisposableEffect(Unit) { onDispose { SfxPreviewPlayer.stop() } }

    ScreenScaffold(title = "SFX Library", onBack = { nav.popBackStack() }) { showMessage ->
        Text("${SfxLibrary.categories.size} categories", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SfxLibrary.categories) { c ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selected = c; SfxPreviewPlayer.stop() },
                ) {
                    Text((if (c == selected) "✓ " else "") + c.replace('_', ' '),
                        modifier = Modifier.height(44.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val c = selected
                    if (c == null) {
                        showMessage("Pick a category first")
                    } else {
                        val ok = SfxPreviewPlayer.play(context, c, 1f)
                        showMessage(if (ok) "Playing '$c'" else "Preview failed")
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Preview") }
            OutlinedButton(
                onClick = {
                    val c = selected
                    if (c == null) {
                        showMessage("Pick a category first")
                    } else {
                        vm.addAtEnd(context, c, showMessage)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) { Text("Add") }
        }
    }
}

// ---------------- Music Library ----------------
class MusicLibraryViewModel : ViewModel() {
    private val _musicName = MutableStateFlow<String?>(null)
    val musicName: StateFlow<String?> = _musicName
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() { _error.value = null }

    fun refresh(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val appCtx = context.applicationContext
            EditorSession.ensureInit(appCtx)
            EditorSession.ensureLoaded()
            val project = EditorSession.project.value
            _musicName.value = project?.backgroundMusicUri?.let {
                displayNameFor(context.applicationContext, Uri.parse(it))
            }
        }
    }

    /** Attaches a user-picked audio file as the project's background music. */
    fun attachMusic(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _error.value = null
            try {
                val appCtx = context.applicationContext
                try {
                    appCtx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    _error.value = "Permission denied for this file — pick it again and allow access."
                    return@launch
                }
                val project = ensureProject(appCtx)
                EditorSession.saveProject(project.copy(backgroundMusicUri = uri.toString()))
                _musicName.value = displayNameFor(appCtx, uri)
            } catch (e: Exception) {
                _error.value = "Could not attach music: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun removeMusic(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _error.value = null
            try {
                val appCtx = context.applicationContext
                val project = ensureProject(appCtx)
                EditorSession.saveProject(project.copy(backgroundMusicUri = null))
                _musicName.value = null
            } catch (e: Exception) {
                _error.value = "Could not remove music: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }
}

@Composable
fun MusicLibraryScreen(nav: NavController) {
    val vm = remember { MusicLibraryViewModel() }
    val context = LocalContext.current
    val moods = listOf("Ambient", "Energetic", "Emotional")
    var mood by remember { mutableStateOf(moods.first()) }
    val musicName by vm.musicName.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.attachMusic(context, it) } }

    LaunchedEffect(Unit) { vm.refresh(context) }

    ScreenScaffold(title = "Music Library", onBack = { nav.popBackStack() }) { showMessage ->
        val err = error
        LaunchedEffect(err) { if (err != null) showMessage(err) }

        Text("Use your own licensed music files. Mood: $mood", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(moods) { m ->
                Card(modifier = Modifier.fillMaxWidth(), onClick = { mood = m }) {
                    Text((if (m == mood) "✓ " else "") + m, modifier = Modifier.height(44.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Music ducks under speech automatically. Only add music you have rights to use.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Background music: ${musicName ?: "none selected"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.height(44.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
        if (err != null) {
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { vm.clearError(); pickAudio.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) { Text("Pick Music File") }
        if (musicName != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.removeMusic(context) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) { Text("Remove Music") }
        }
    }
}

// ---------------- Audio Mixer ----------------
class MixerViewModel : ViewModel() {
    val controller = AudioMixerController()
}

@Composable
fun AudioMixerScreen(nav: NavController) {
    val vm = remember { MixerViewModel() }
    val state by vm.controller.state.collectAsState()
    ScreenScaffold(title = "Audio Mixer", onBack = { nav.popBackStack() }) { _ ->
        MixerRow("Original audio", state.originalVolume, state.originalMuted,
            vm.controller::setOriginalVolume, vm.controller::toggleOriginalMute)
        MixerRow("Music", state.musicVolume, state.musicMuted,
            vm.controller::setMusicVolume, vm.controller::toggleMusicMute)
        MixerRow("SFX", state.sfxVolume, state.sfxMuted,
            vm.controller::setSfxVolume, vm.controller::toggleSfxMute)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.controller.muteAll() }, modifier = Modifier.fillMaxWidth()) {
            Text("Mute All")
        }
    }
}

@Composable
private fun MixerRow(
    label: String, volume: Float, muted: Boolean,
    onVolume: (Float) -> Unit, onMute: () -> Unit,
) {
    Text("$label — ${(volume * 100).roundToInt()}%${if (muted) " (muted)" else ""}",
        style = MaterialTheme.typography.titleMedium)
    Row(modifier = Modifier.fillMaxWidth()) {
        Slider(value = volume, onValueChange = onVolume, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMute) { Text(if (muted) "Unmute" else "Mute") }
    }
    Spacer(Modifier.height(4.dp))
}
