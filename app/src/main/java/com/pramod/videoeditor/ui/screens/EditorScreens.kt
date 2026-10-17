package com.pramod.videoeditor.ui.screens

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.pramod.videoeditor.aigemini.GeminiClient
import com.pramod.videoeditor.aigroq.AudioUriResolver
import com.pramod.videoeditor.aigroq.GroqClient
import com.pramod.videoeditor.coremodel.AiProvider
import com.pramod.videoeditor.coremodel.CaptionSegment
import com.pramod.videoeditor.coremodel.Clip
import com.pramod.videoeditor.coremodel.ClipType
import com.pramod.videoeditor.coremodel.EmojiOverlay
import com.pramod.videoeditor.coremodel.Project
import com.pramod.videoeditor.coremodel.TemplateConfig
import com.pramod.videoeditor.coresecurity.SecureKeyStore
import com.pramod.videoeditor.corestorage.ProjectFileManager
import com.pramod.videoeditor.featurecaptions.CaptionLayerHost
import com.pramod.videoeditor.featurecaptions.EmojiOverlayHost
import com.pramod.videoeditor.featurecaptions.SrtExporter
import com.pramod.videoeditor.featurecaptions.contentDurationMs
import com.pramod.videoeditor.featureeditor.AiAutoEditOrchestrator
import com.pramod.videoeditor.featureeditor.AiAutoEditOrchestrator.AudioSource
import com.pramod.videoeditor.featureeditor.AiAutoEditOrchestrator.AutoEditInput
import com.pramod.videoeditor.featureeditor.AiAutoEditOrchestrator.AutoEditResult
import com.pramod.videoeditor.featureeditor.ProjectCaptionStore
import com.pramod.videoeditor.featuretypography.KineticPresets
import com.pramod.videoeditor.mediaengine.PreviewPlayer
import com.pramod.videoeditor.ui.ScreenScaffold
import com.pramod.videoeditor.ui.navigation.Routes
import com.pramod.videoeditor.ui.session.EditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** mm:ss.d formatter for timeline timestamps shown in the editor UI. */
private fun formatMs(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d.%d".format(totalSec / 60, totalSec % 60, (ms.coerceAtLeast(0L) % 1000) / 100)
}

/** Shared "create project" action for the editor screens' empty states. */
internal fun CoroutineScope.launchCreateProject(name: String, onDone: (String) -> Unit) {
    launch {
        try {
            val p = EditorSession.createProject(name.ifBlank { "Untitled project" })
            onDone("Project \"${p.name}\" created.")
        } catch (e: Exception) {
            onDone("Could not create project: ${e.message}")
        }
    }
}

/** Empty state shown by editor screens when no project is open. */
@Composable
private fun NoProjectState(onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Text(
        "No project open. Create one to continue — it is saved on this device.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("Project name") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onCreate(name) }, modifier = Modifier.fillMaxWidth()) {
        Text("Create Project")
    }
}

// ---------------- Editor session plumbing (Preview + Timeline share one player) ----------------

/**
 * Activity-scoped editor session: owns the real [PreviewPlayer], the selected
 * clip, and trim/split edits. The open project itself lives in [EditorSession]
 * (single source of truth, persisted via ProjectRepository).
 */
class EditorViewModel(appContext: Context) : ViewModel() {
    val player = PreviewPlayer(appContext.applicationContext)

    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    private var lastPlaylistSignature: String? = null

    init {
        EditorSession.ensureInit(appContext.applicationContext)
        viewModelScope.launch {
            EditorSession.ensureLoaded()
            EditorSession.project.collect { project ->
                val clips = project?.clips.orEmpty()
                val signature = playlistSignature(clips)
                if (signature != lastPlaylistSignature) {
                    lastPlaylistSignature = signature
                    val keepPosition = player.positionMs.value
                    player.loadClips(clips)
                    if (keepPosition > 0L) player.seekTo(keepPosition)
                }
                val selected = _selectedClipId.value
                if (selected != null && clips.none { it.id == selected }) {
                    _selectedClipId.value = null
                }
            }
        }
        // The player is the source of truth for the playhead; mirror it into
        // the shared session so every editor screen shows the same position.
        viewModelScope.launch {
            player.positionMs.collect { EditorSession.seekTo(it) }
        }
        // Session→player seek bridge: when something OUTSIDE the player (the
        // emoji screen's playhead slider, a caption jump, a deep link) calls
        // EditorSession.seekTo(), the preview player must follow. Playhead
        // writes that merely mirror the player itself are ignored via the
        // tolerance window, so this never fights normal playback.
        viewModelScope.launch {
            EditorSession.playheadMs.collect { sessionMs ->
                val playerMs = player.positionMs.value
                if (abs(sessionMs - playerMs) > SEEK_SYNC_TOLERANCE_MS) {
                    player.seekTo(sessionMs)
                }
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    fun selectClip(id: String?) { _selectedClipId.value = id }

    fun seekBack5s() {
        player.seekTo(maxOf(0L, player.positionMs.value - 5_000L))
    }

    fun playableClips(clips: List<Clip>): List<Clip> =
        clips.filter { (it.type == ClipType.VIDEO || it.type == ClipType.AUDIO) && !it.sourceUri.isNullOrBlank() }

    /** Honest trim bounds for a clip: (minStart, maxStart, minEnd, maxEnd, durationKnown). */
    data class TrimBounds(
        val minStartMs: Long,
        val maxStartMs: Long,
        val minEndMs: Long,
        val maxEndMs: Long,
        val durationKnown: Boolean,
    )

    fun trimBounds(clipId: String, sourceDurations: Map<String, Long>): TrimBounds? {
        val project = EditorSession.project.value ?: return null
        val clips = project.clips.sortedBy { it.startMs }
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return null
        val clip = clips[index]
        val prevEnd = if (index > 0) clips[index - 1].endMs else 0L
        val sourceDuration = sourceDurations[clipId]
        val minStart = maxOf(clip.startMs - clip.trimInMs, prevEnd)
        val maxStart = clip.endMs - MIN_CLIP_MS
        val minEnd = clip.startMs + MIN_CLIP_MS
        val maxEnd = if (sourceDuration != null) {
            clip.startMs + (sourceDuration - clip.trimInMs)
        } else {
            clip.endMs // duration unknown: only shrinking the end is allowed
        }
        return TrimBounds(minStart, maxStart, minEnd, maxEnd, sourceDuration != null)
    }

    /**
     * Trims the clip's start/end on the timeline and its source trim window.
     * Clips after the edited one shift so the timeline stays contiguous.
     */
    fun trimClip(clipId: String, newStartMs: Long?, newEndMs: Long?) {
        val project = EditorSession.project.value ?: return
        val clips = project.clips.sortedBy { it.startMs }.toMutableList()
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return
        val clip = clips[index]
        if (clip.sourceUri.isNullOrBlank()) {
            _message.value = "Only media clips can be trimmed"
            return
        }
        val bounds = trimBounds(clipId, player.sourceDurations.value) ?: return
        var start = clip.startMs
        var end = clip.endMs
        var trimIn = clip.trimInMs
        var trimOut = clip.trimOutMs
        val notes = mutableListOf<String>()

        newStartMs?.let { requested ->
            if (bounds.minStartMs <= bounds.maxStartMs) {
                val clamped = requested.coerceIn(bounds.minStartMs, bounds.maxStartMs)
                if (clamped != requested) notes += "Start clamped to ${formatMs(clamped)}"
                trimIn += clamped - start
                start = clamped
            }
        }
        newEndMs?.let { requested ->
            // Recompute against the (possibly just moved) start so start/end can't cross.
            val minEnd = start + MIN_CLIP_MS
            if (minEnd <= bounds.maxEndMs) {
                val clamped = requested.coerceIn(minEnd, bounds.maxEndMs)
                if (clamped != requested) {
                    notes += if (!bounds.durationKnown && requested > bounds.maxEndMs) {
                        "Source duration still loading — the end can only shrink for now"
                    } else {
                        "End clamped to ${formatMs(clamped)}"
                    }
                }
                trimOut += clamped - end
                end = clamped
            }
        }

        val shift = (start - clip.startMs) + (end - clip.endMs)
        clips[index] = clip.copy(startMs = start, endMs = end, trimInMs = trimIn, trimOutMs = trimOut)
        for (i in index + 1 until clips.size) {
            val c = clips[i]
            clips[i] = c.copy(startMs = c.startMs + shift, endMs = c.endMs + shift)
        }
        persist(project.copy(clips = clips))
        if (notes.isNotEmpty()) _message.value = notes.joinToString("; ")
    }

    /** Splits the selected clip (or the clip under the playhead) at the current position. */
    fun splitClipAtPlayhead() {
        val project = EditorSession.project.value ?: run {
            _message.value = "No project open"
            return
        }
        val clips = project.clips.sortedBy { it.startMs }
        val position = player.positionMs.value
        val target = _selectedClipId.value?.let { id -> clips.find { it.id == id } }
            ?: clips.find { position in it.startMs until it.endMs }
        if (target == null) {
            _message.value = "Nothing to split at the playhead"
            return
        }
        if (target.sourceUri.isNullOrBlank()) {
            _message.value = "Only media clips can be split"
            return
        }
        if (position < target.startMs + MIN_CLIP_MS || position > target.endMs - MIN_CLIP_MS) {
            _message.value = "Move the playhead inside the clip to split it"
            return
        }
        val index = clips.indexOf(target)
        val splitOffset = position - target.startMs
        val left = target.copy(
            endMs = position,
            trimOutMs = target.trimInMs + splitOffset,
        )
        val right = target.copy(
            id = UUID.randomUUID().toString(),
            startMs = position,
            trimInMs = target.trimInMs + splitOffset,
        )
        val updated = clips.toMutableList().also {
            it[index] = left
            it.add(index + 1, right)
        }
        persist(project.copy(clips = updated))
        _message.value = "Clip split at ${formatMs(position)}"
    }

    private fun persist(updated: Project) {
        viewModelScope.launch {
            try {
                EditorSession.saveProject(updated)
            } catch (e: Exception) {
                _message.value = "Couldn't save: ${e.message}"
            }
        }
    }

    private fun playlistSignature(clips: List<Clip>): String =
        clips.sortedBy { it.startMs }.joinToString("|") {
            "${it.id},${it.sourceUri},${it.trimInMs},${it.trimOutMs}," +
                "${it.startMs},${it.endMs},${it.muted},${it.volume}"
        }

    companion object {
        const val MIN_CLIP_MS = 200L

        /**
         * Session→player seek tolerance: session playhead writes within this
         * many ms of the player's own position are treated as the player
         * mirroring itself, not an external seek request.
         */
        const val SEEK_SYNC_TOLERANCE_MS = 750L
    }
}

private class EditorViewModelFactory(private val appContext: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditorViewModel::class.java)) {
            return EditorViewModel(appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}

/** Activity-scoped editor session shared by the Preview and Timeline screens. */
@Composable
private fun rememberEditorViewModel(): EditorViewModel {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val factory = remember { EditorViewModelFactory(activity.applicationContext) }
    return viewModel(viewModelStoreOwner = activity, key = "editor", factory = factory)
}

// ---------------- Video Preview ----------------
@Composable
fun VideoPreviewScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val vm = rememberEditorViewModel()
    val project by EditorSession.project.collectAsState()
    val positionMs by vm.player.positionMs.collectAsState()
    val isPlaying by vm.player.isPlaying.collectAsState()
    val durationMs by vm.player.timelineDurationMs.collectAsState()
    val error by vm.player.error.collectAsState()
    val message by vm.message.collectAsState()

    ScreenScaffold(title = "Preview", onBack = { nav.popBackStack() }) { showMessage ->
        LaunchedEffect(message) {
            if (message != null) {
                showMessage(message!!)
                vm.consumeMessage()
            }
        }
        if (project == null) {
            NoProjectState { name -> scope.launchCreateProject(name) { showMessage(it) } }
        } else {
            val current = project!!
            val playable = remember(current) { vm.playableClips(current.clips) }
            val hasMedia = playable.isNotEmpty()
            val aspect = current.aspectRatio
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect.w.toFloat() / aspect.h.toFloat()),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            player = vm.player.player
                        }
                    },
                    update = { it.player = vm.player.player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                // Caption + emoji overlays (implemented by the captions chunk).
                CaptionLayerHost(
                    project = current,
                    positionMs = positionMs,
                )
                EmojiOverlayHost(
                    project = current,
                    positionMs = positionMs,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (!hasMedia) {
                Text(
                    "No media added yet — add clips from the Media tab",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { nav.navigate(Routes.MEDIA_PICKER) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Media Picker") }
                Spacer(Modifier.height(8.dp))
            }
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(
                    onClick = { if (isPlaying) vm.player.pause() else vm.player.play() },
                    enabled = hasMedia,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                Text(
                    "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val max = durationMs.toFloat().coerceAtLeast(1f)
            Slider(
                value = positionMs.toFloat().coerceIn(0f, max),
                onValueChange = { vm.player.seekTo(it.toLong()) },
                valueRange = 0f..max,
                enabled = hasMedia,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { nav.navigate(Routes.TIMELINE) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open Timeline Editor") }
    }
}

// ---------------- Timeline Editor ----------------
@Composable
fun TimelineEditorScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val vm = rememberEditorViewModel()
    val project by EditorSession.project.collectAsState()
    val selectedId by vm.selectedClipId.collectAsState()
    val playheadMs by EditorSession.playheadMs.collectAsState()
    val message by vm.message.collectAsState()

    ScreenScaffold(title = "Timeline", onBack = { nav.popBackStack() }) { showMessage ->
        LaunchedEffect(message) {
            if (message != null) {
                showMessage(message!!)
                vm.consumeMessage()
            }
        }
        if (project == null) {
            NoProjectState { name -> scope.launchCreateProject(name) { showMessage(it) } }
        } else {
            val clips = project!!.clips.sortedBy { it.startMs }
            Text(
                "Playhead: ${formatMs(playheadMs)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (clips.isEmpty()) {
                Text(
                    "No clips on the timeline yet — add media from the Media tab.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { nav.navigate(Routes.MEDIA_PICKER) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Media Picker") }
            } else {
                Text("Clips — tap to select", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(clips, key = { it.id }) { clip ->
                        val selected = clip.id == selectedId
                        Card(
                            onClick = { vm.selectClip(if (selected) null else clip.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.width(150.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(clip.type.name, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${formatMs(clip.startMs)} – ${formatMs(clip.endMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val selected = clips.find { it.id == selectedId }
                if (selected != null) {
                    SelectedClipPanel(clip = selected, vm = vm)
                } else {
                    Text(
                        "Select a clip to trim it, or press Split to cut the clip under the playhead.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { vm.splitClipAtPlayhead() }, modifier = Modifier.weight(1f)) {
                    Text("Split at Playhead")
                }
                OutlinedButton(onClick = { vm.seekBack5s() }, modifier = Modifier.weight(1f)) {
                    Text("−5s")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { nav.navigate(Routes.CAPTIONS) }, modifier = Modifier.weight(1f)) {
                Text("Captions")
            }
            OutlinedButton(onClick = { nav.navigate(Routes.TYPOGRAPHY) }, modifier = Modifier.weight(1f)) {
                Text("Typography")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { nav.navigate(Routes.EMOJI) }, modifier = Modifier.weight(1f)) {
                Text("Emoji")
            }
            OutlinedButton(onClick = { nav.navigate(Routes.VFX) }, modifier = Modifier.weight(1f)) {
                Text("VFX")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { nav.navigate(Routes.SFX) }, modifier = Modifier.weight(1f)) {
                Text("SFX")
            }
            OutlinedButton(onClick = { nav.navigate(Routes.MUSIC) }, modifier = Modifier.weight(1f)) {
                Text("Music")
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { nav.navigate(Routes.EXPORT_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Export")
        }
    }
}

/** Trim UI for the selected timeline clip: sliders + ±0.1s steppers. */
@Composable
private fun SelectedClipPanel(clip: Clip, vm: EditorViewModel) {
    val sourceDurations by vm.player.sourceDurations.collectAsState()
    val bounds = vm.trimBounds(clip.id, sourceDurations)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Selected clip", style = MaterialTheme.typography.titleMedium)
        Text(
            "Start ${formatMs(clip.startMs)} · End ${formatMs(clip.endMs)} · " +
                "Length ${formatMs(clip.endMs - clip.startMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (bounds == null || clip.sourceUri.isNullOrBlank()) {
            Text(
                "This clip has no trimmable media.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            var startValue by remember(clip.id, clip.startMs) { mutableStateOf(clip.startMs.toFloat()) }
            var endValue by remember(clip.id, clip.endMs) { mutableStateOf(clip.endMs.toFloat()) }
            val startRange = bounds.minStartMs.toFloat()..
                maxOf(bounds.maxStartMs.toFloat(), bounds.minStartMs.toFloat() + 1f)
            val endRange = bounds.minEndMs.toFloat()..
                maxOf(bounds.maxEndMs.toFloat(), bounds.minEndMs.toFloat() + 1f)
            Text("Trim start: ${formatMs(startValue.toLong())}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = startValue.coerceIn(startRange),
                onValueChange = { startValue = it },
                onValueChangeFinished = { vm.trimClip(clip.id, startValue.toLong(), null) },
                valueRange = startRange,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Trim end: ${formatMs(endValue.toLong())}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = endValue.coerceIn(endRange),
                onValueChange = { endValue = it },
                onValueChangeFinished = { vm.trimClip(clip.id, null, endValue.toLong()) },
                valueRange = endRange,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.trimClip(clip.id, clip.startMs - 100, null) },
                    modifier = Modifier.weight(1f),
                ) { Text("−0.1s") }
                OutlinedButton(
                    onClick = { vm.trimClip(clip.id, clip.startMs + 100, null) },
                    modifier = Modifier.weight(1f),
                ) { Text("+0.1s") }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.trimClip(clip.id, null, clip.endMs - 100) },
                    modifier = Modifier.weight(1f),
                ) { Text("End −0.1s") }
                OutlinedButton(
                    onClick = { vm.trimClip(clip.id, null, clip.endMs + 100) },
                    modifier = Modifier.weight(1f),
                ) { Text("End +0.1s") }
            }
            if (!bounds.durationKnown) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Source duration still loading — the end can only shrink until the player reads it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------- Caption Editor ----------------
class CaptionEditorViewModel(appContext: Context) : ViewModel() {
    private val appCtx = appContext.applicationContext

    init { EditorSession.ensureInit(appCtx) }

    val project: StateFlow<Project?> = EditorSession.project
    val selectedId: StateFlow<String?> = EditorSession.selectedCaptionId

    fun ensureLoaded() { viewModelScope.launch { EditorSession.ensureLoaded() } }

    fun select(id: String?) = EditorSession.selectCaption(id)

    fun addCaption(text: String, onDone: (String) -> Unit) {
        val p = EditorSession.project.value ?: run { onDone("No project open."); return }
        if (text.isBlank()) { onDone("Type a caption first."); return }
        val start = maxOf(
            EditorSession.playheadMs.value,
            p.captions.maxOfOrNull { it.endMs } ?: 0L,
        )
        val duration = p.contentDurationMs()
        var end = start + 2000L
        if (duration > 0) end = minOf(end, duration).coerceAtLeast(start + 200L)
        val seg = CaptionSegment(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            startMs = start,
            endMs = end,
        )
        viewModelScope.launch {
            try {
                EditorSession.updateCaptions { it + seg }
                onDone("Caption added at ${formatMs(start)}.")
            } catch (e: Exception) {
                onDone("Save failed: ${e.message}")
            }
        }
    }

    fun saveCaption(seg: CaptionSegment, onDone: (String) -> Unit) {
        val p = EditorSession.project.value ?: run { onDone("No project open."); return }
        val error = validateCaption(p, seg)
        if (error != null) { onDone(error); return }
        val duration = p.contentDurationMs()
        val fixed = seg.copy(
            text = seg.text.trim(),
            startMs = seg.startMs.coerceAtLeast(0L),
            endMs = if (duration > 0) seg.endMs.coerceAtMost(duration) else seg.endMs,
        )
        viewModelScope.launch {
            try {
                EditorSession.updateCaptions { list ->
                    list.map { if (it.id == fixed.id) fixed else it }
                }
                onDone("Caption saved.")
            } catch (e: Exception) {
                onDone("Save failed: ${e.message}")
            }
        }
    }

    private fun validateCaption(p: Project, seg: CaptionSegment): String? {
        if (seg.text.isBlank()) return "Caption text can't be empty."
        if (seg.startMs < 0) return "Start time can't be negative."
        val duration = p.contentDurationMs()
        val end = if (duration > 0) seg.endMs.coerceAtMost(duration) else seg.endMs
        if (seg.startMs >= end) return "Start must be before end."
        if (duration > 0 && seg.startMs > duration) {
            return "Start is beyond the project duration (${formatMs(duration)})."
        }
        return null
    }

    fun deleteCaption(id: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            try {
                EditorSession.updateCaptions { list -> list.filterNot { it.id == id } }
                EditorSession.selectCaption(null)
                onDone("Caption deleted.")
            } catch (e: Exception) {
                onDone("Delete failed: ${e.message}")
            }
        }
    }

    /** Writes a real .srt file into the project's files and reports its path. */
    fun exportSrt(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = EditorSession.project.value ?: run { onDone("ERR:No project open."); return@launch }
            if (p.captions.isEmpty()) { onDone("ERR:No captions yet — add one first."); return@launch }
            try {
                val srt = SrtExporter.export(p.captions)
                val file = File(ProjectFileManager(appCtx).projectDir(p.id), "captions.srt")
                file.writeText(srt)
                onDone("OK:${file.absolutePath}")
            } catch (e: Exception) {
                onDone("ERR:Export failed: ${e.message}")
            }
        }
    }
}

@Composable
private fun CaptionRow(seg: CaptionSegment, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = MaterialTheme.shapes.medium,
            ),
        onClick = onSelect,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(seg.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatMs(seg.startMs)} → ${formatMs(seg.endMs)}" +
                    (seg.presetId?.let { " · ${KineticPresets.byId(it).displayName}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CaptionEditPanel(
    seg: CaptionSegment,
    onSave: (CaptionSegment) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(seg.id) { mutableStateOf(seg.text) }
    var start by remember(seg.id) { mutableStateOf(seg.startMs.toString()) }
    var end by remember(seg.id) { mutableStateOf(seg.endMs.toString()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Text") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start, onValueChange = { start = it.filter(Char::isDigit) },
                    label = { Text("Start (ms)") },
                    modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = end, onValueChange = { end = it.filter(Char::isDigit) },
                    label = { Text("End (ms)") },
                    modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        onSave(
                            seg.copy(
                                text = text,
                                startMs = start.toLongOrNull() ?: seg.startMs,
                                endMs = end.toLongOrNull() ?: seg.endMs,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
fun CaptionEditorScreen(nav: NavController) {
    val ctx = LocalContext.current
    val vm = remember { CaptionEditorViewModel(ctx) }
    val project by vm.project.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.ensureLoaded() }
    ScreenScaffold(title = "Caption Editor", onBack = { nav.popBackStack() }) { showMessage ->
        val p = project
        if (p == null) {
            NoProjectState { name -> vm.viewModelScope.launchCreateProject(name, showMessage) }
            return@ScreenScaffold
        }
        OutlinedTextField(
            value = draft, onValueChange = { draft = it },
            label = { Text("New caption line") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                vm.addCaption(draft) { msg ->
                    showMessage(msg)
                    if (msg.startsWith("Caption added")) draft = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add Caption") }
        Spacer(Modifier.height(12.dp))
        val segs = p.captions.sortedBy { it.startMs }
        if (segs.isEmpty()) {
            Text("No captions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(segs, key = { it.id }) { seg ->
                CaptionRow(
                    seg = seg,
                    selected = seg.id == selectedId,
                    onSelect = { vm.select(if (selectedId == seg.id) null else seg.id) },
                )
                if (seg.id == selectedId) {
                    CaptionEditPanel(
                        seg = seg,
                        onSave = { updated -> vm.saveCaption(updated, showMessage) },
                        onDelete = { vm.deleteCaption(seg.id, showMessage) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                vm.exportSrt { msg ->
                    if (msg.startsWith("OK:")) showMessage("SRT exported:\n${msg.removePrefix("OK:")}")
                    else showMessage(msg.removePrefix("ERR:"))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export SRT") }
    }
}

// ---------------- Typography Editor ----------------
class TypographyViewModel(appContext: Context) : ViewModel() {
    init { EditorSession.ensureInit(appContext.applicationContext) }

    val project: StateFlow<Project?> = EditorSession.project
    val selectedId: StateFlow<String?> = EditorSession.selectedCaptionId

    fun ensureLoaded() { viewModelScope.launch { EditorSession.ensureLoaded() } }

    /** Applies a kinetic preset to the currently selected caption, persisted via the repository. */
    fun applyPreset(presetId: String, onDone: (String) -> Unit) {
        val sel = EditorSession.selectedCaptionId.value
        if (sel == null) {
            onDone("Select a caption in the Caption Editor first.")
            return
        }
        viewModelScope.launch {
            try {
                EditorSession.updateCaptions { list ->
                    list.map { if (it.id == sel) it.copy(presetId = presetId) else it }
                }
                onDone("Preset \"${KineticPresets.byId(presetId).displayName}\" applied.")
            } catch (e: Exception) {
                onDone("Apply failed: ${e.message}")
            }
        }
    }
}

@Composable
fun TypographyEditorScreen(nav: NavController) {
    val ctx = LocalContext.current
    val vm = remember { TypographyViewModel(ctx) }
    val project by vm.project.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    LaunchedEffect(Unit) { vm.ensureLoaded() }
    ScreenScaffold(title = "Typography", onBack = { nav.popBackStack() }) { showMessage ->
        val p = project
        if (p == null) {
            NoProjectState { name -> vm.viewModelScope.launchCreateProject(name, showMessage) }
            return@ScreenScaffold
        }
        val sel = p.captions.find { it.id == selectedId }
        var picked by remember(sel?.id) { mutableStateOf(sel?.presetId ?: "scale_in") }
        Text(
            if (sel != null) "Selected caption: \"${sel.text}\"" +
                "\n${formatMs(sel.startMs)} → ${formatMs(sel.endMs)}"
            else "No caption selected — pick one in the Caption Editor first.",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text("Kinetic preset", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(KineticPresets.all) { preset ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { picked = preset.id },
                ) {
                    Text(
                        (if (preset.id == picked) "✓ " else "") + preset.displayName,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.applyPreset(picked, showMessage) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Apply Preset") }
    }
}

// ---------------- Emoji & Stickers ----------------
private val EMOJI_BY_CATEGORY: Map<String, List<String>> = mapOf(
    "Love" to listOf("❤️", "💕", "😍", "💖", "🥰", "💘"),
    "Funny" to listOf("😂", "🤣", "😜", "🤪", "😹", "😆"),
    "Motivation" to listOf("🔥", "💪", "🚀", "⭐", "🏆", "💯"),
    "Education" to listOf("📚", "🎓", "✏️", "💡", "🧠", "📝"),
    "Gaming" to listOf("🎮", "🕹️", "👾", "🏅", "🎯", "⚡"),
    "Travel" to listOf("✈️", "🌍", "🏖️", "🗺️", "🚗", "🏔️"),
    "Celebration" to listOf("🎉", "🎊", "🥳", "🎂", "✨", "🎁"),
    "Ideas" to listOf("💡", "🤔", "🧩", "🔍", "❓", "❗"),
    "Success" to listOf("🏆", "🥇", "👏", "💯", "🌟", "🎖️"),
    "Aesthetic" to listOf("🌸", "🌙", "☁️", "🦋", "🌷", "🌊"),
)

class EmojiViewModel(appContext: Context) : ViewModel() {
    init { EditorSession.ensureInit(appContext.applicationContext) }

    val project: StateFlow<Project?> = EditorSession.project
    val playhead: StateFlow<Long> = EditorSession.playheadMs

    fun ensureLoaded() { viewModelScope.launch { EditorSession.ensureLoaded() } }

    fun seekTo(ms: Long) = EditorSession.seekTo(ms)

    /** Places the emoji as a text overlay at the current playhead, persisted in the project. */
    fun addOverlay(emoji: String, x: Float, y: Float, durationMs: Long, onDone: (String) -> Unit) {
        val p = EditorSession.project.value ?: run { onDone("No project open."); return }
        val start = EditorSession.playheadMs.value.coerceAtLeast(0L)
        val dur = durationMs.coerceIn(200L, 30_000L)
        val overlay = EmojiOverlay(
            id = UUID.randomUUID().toString(),
            emoji = emoji,
            startMs = start,
            endMs = start + dur,
            xFraction = x.coerceIn(0f, 1f),
            yFraction = y.coerceIn(0f, 1f),
        )
        viewModelScope.launch {
            try {
                EditorSession.updateEmojiOverlays { it + overlay }
                onDone("Emoji placed at ${formatMs(start)}.")
            } catch (e: Exception) {
                onDone("Save failed: ${e.message}")
            }
        }
    }

    fun removeOverlay(id: String) {
        viewModelScope.launch {
            EditorSession.updateEmojiOverlays { list -> list.filterNot { it.id == id } }
        }
    }
}

@Composable
fun EmojiStickerScreen(nav: NavController) {
    val ctx = LocalContext.current
    val vm = remember { EmojiViewModel(ctx) }
    val project by vm.project.collectAsState()
    val playhead by vm.playhead.collectAsState()
    var cat by remember { mutableStateOf(EMOJI_BY_CATEGORY.keys.first()) }
    var picked by remember { mutableStateOf<String?>(null) }
    var xPos by remember { mutableStateOf(0.5f) }
    var yPos by remember { mutableStateOf(0.5f) }
    var durationText by remember { mutableStateOf("2000") }
    LaunchedEffect(Unit) { vm.ensureLoaded() }
    ScreenScaffold(title = "Emoji & Stickers", onBack = { nav.popBackStack() }) { showMessage ->
        val p = project
        if (p == null) {
            NoProjectState { name -> vm.viewModelScope.launchCreateProject(name, showMessage) }
            return@ScreenScaffold
        }
        val duration = p.contentDurationMs()
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Playhead: ${formatMs(playhead)}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = playhead.toFloat(),
                onValueChange = { vm.seekTo(it.toLong()) },
                valueRange = 0f..maxOf(duration, 1L).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Category: $cat", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EMOJI_BY_CATEGORY.keys.forEach { c ->
                    OutlinedButton(onClick = { cat = c }) {
                        Text((if (c == cat) "✓ " else "") + c)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (EMOJI_BY_CATEGORY[cat] ?: emptyList()).forEach { e ->
                    if (e == picked) {
                        Button(onClick = { picked = e }) { Text(e, fontSize = 26.sp) }
                    } else {
                        OutlinedButton(onClick = { picked = e }) { Text(e, fontSize = 26.sp) }
                    }
                }
            }
            Text("Horizontal: ${(xPos * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(value = xPos, onValueChange = { xPos = it }, valueRange = 0f..1f)
            Text("Vertical: ${(yPos * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(value = yPos, onValueChange = { yPos = it }, valueRange = 0f..1f)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Duration (ms)") },
                    modifier = Modifier.weight(1f), singleLine = true,
                )
                Text(picked ?: "Pick an emoji ↑", fontSize = 30.sp)
            }
            Button(
                onClick = {
                    val e = picked
                    if (e == null) { showMessage("Pick an emoji first."); return@Button }
                    vm.addOverlay(e, xPos, yPos, durationText.toLongOrNull() ?: 2000L, showMessage)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add at Playhead") }
            Spacer(Modifier.height(4.dp))
            Text("Placed overlays", style = MaterialTheme.typography.titleMedium)
            val overlays = p.emojiOverlays.sortedBy { it.startMs }
            if (overlays.isEmpty()) {
                Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            overlays.forEach { o ->
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${o.emoji}  ${formatMs(o.startMs)} → ${formatMs(o.endMs)}", fontSize = 18.sp)
                    OutlinedButton(onClick = { vm.removeOverlay(o.id) }) { Text("Delete") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Emoji is never auto-added — you place each one yourself. " +
                    "Overlays render in the preview and are saved in the project.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Carries the AI Setup screen's choices (provider + template) to the Analysis screen. */
private object AiSession {
    var provider: AiProvider = AiProvider.NONE
    var templateId: String? = null
}

// ---------------- AI Auto Edit Setup ----------------
class AiSetupViewModel(appContext: Context) : ViewModel() {
    private val _provider = MutableStateFlow(AiSession.provider)
    val provider: StateFlow<AiProvider> = _provider
    private val _effectIntensity = MutableStateFlow(0.5f)
    val effectIntensity: StateFlow<Float> = _effectIntensity
    private val _sfxEnabled = MutableStateFlow(true)
    val sfxEnabled: StateFlow<Boolean> = _sfxEnabled
    private val _templates = MutableStateFlow<List<TemplateConfig>>(emptyList())
    val templates: StateFlow<List<TemplateConfig>> = _templates
    private val _loadingTemplates = MutableStateFlow(true)
    val loadingTemplates: StateFlow<Boolean> = _loadingTemplates
    private val _templateId = MutableStateFlow<String?>(AiSession.templateId)
    val templateId: StateFlow<String?> = _templateId

    init {
        viewModelScope.launch {
            val all = TemplateStore(appContext.applicationContext).loadAll()
            _templates.value = all
            _loadingTemplates.value = false
            if (_templateId.value == null) {
                all.firstOrNull()?.let { selectTemplate(it.id) }
            }
        }
    }

    fun setProvider(p: AiProvider) {
        _provider.value = p
        AiSession.provider = p
    }
    fun selectTemplate(id: String) {
        _templateId.value = id
        AiSession.templateId = id
    }
    fun toggleSfx() { _sfxEnabled.value = !_sfxEnabled.value }
}

@Composable
fun AiAutoEditSetupScreen(nav: NavController) {
    val context = LocalContext.current
    val vm = remember { AiSetupViewModel(context.applicationContext) }
    val provider by vm.provider.collectAsState()
    val sfxEnabled by vm.sfxEnabled.collectAsState()
    val templates by vm.templates.collectAsState()
    val templateId by vm.templateId.collectAsState()
    val loadingTemplates by vm.loadingTemplates.collectAsState()
    ScreenScaffold(title = "AI Auto Edit", onBack = { nav.popBackStack() }) { _ ->
        Text("Transcription provider", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AiProvider.NONE, AiProvider.GROQ, AiProvider.GEMINI).forEach { p ->
                OutlinedButton(onClick = { vm.setProvider(p) }) {
                    Text((if (p == provider) "✓ " else "") + p.name)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Template", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (loadingTemplates) {
            CircularProgressIndicator()
        } else if (templates.isEmpty()) {
            Text(
                "No templates found in the app install.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(templates, key = { it.id }) { t ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.selectTemplate(t.id) },
                    ) {
                        Text(
                            (if (t.id == templateId) "✓ " else "") + t.name,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("SFX: ${if (sfxEnabled) "on" else "off"}")
        OutlinedButton(onClick = { vm.toggleSfx() }) { Text("Toggle SFX") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Manual editing stays available with or without AI. Nothing is claimed as " +
                "\"AI analyzed\" unless it really ran.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { nav.navigate(Routes.AI_PROGRESS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Run Auto Edit")
        }
    }
}

// ---------------- AI Analysis Progress ----------------
/**
 * Runs the real [AiAutoEditOrchestrator] pipeline — no simulated steps.
 * Progress text comes from the orchestrator's onStep callback; the final UI
 * is driven by its [AutoEditResult] sealed type with honest, user-facing
 * messages. Keys stay in [SecureKeyStore] and never appear in messages.
 */
class AiProgressViewModel(appContext: Context) : ViewModel() {
    private val app = appContext.applicationContext
    private val keyStore = SecureKeyStore(app)
    private val groq = GroqClient { keyStore.getKey("groq") }
    private val gemini = GeminiClient { keyStore.getKey("gemini") }

    private var currentProject: Project? = null
    private val captionStore = object : ProjectCaptionStore {
        override suspend fun updateCaptions(projectId: String, captions: List<CaptionSegment>) {
            val current = currentProject ?: error("No project loaded")
            check(current.id == projectId) { "Project changed during auto-edit" }
            val updated = current.copy(captions = captions)
            EditorSession.saveProject(updated)
            currentProject = updated
        }
    }
    private val orchestrator = AiAutoEditOrchestrator(
        groq = groq,
        gemini = gemini,
        captionStore = captionStore,
        resolveAudioUri = { uri -> AudioUriResolver.copyToTempFile(app, uri) },
    )

    private val _step = MutableStateFlow("Starting…")
    val step: StateFlow<String> = _step.asStateFlow()
    private val _running = MutableStateFlow(true)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _result = MutableStateFlow<AutoEditResult?>(null)
    val result: StateFlow<AutoEditResult?> = _result.asStateFlow()

    init {
        EditorSession.ensureInit(app)
        viewModelScope.launch {
            try {
                runPipeline()
            } catch (e: Exception) {
                _result.value = AutoEditResult.Failed(
                    "Auto Edit couldn't start: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun runPipeline() {
        _step.value = "Loading project…"
        EditorSession.ensureLoaded()
        val project = EditorSession.project.value
        if (project == null) {
            _result.value = AutoEditResult.Failed(
                "No project is open. Create a project and add media first.")
            return
        }
        currentProject = project
        if (project.clips.none { !it.sourceUri.isNullOrBlank() }) {
            _result.value = AutoEditResult.NeedsManualCaptions(
                "The project has no media yet. Add a clip first, then run Auto Edit.")
            return
        }
        _step.value = "Loading template…"
        val template = resolveTemplate(project)
        if (template == null) {
            _result.value = AutoEditResult.Failed(
                "No usable template found. The template library may be missing from this install.")
            return
        }
        val provider = AiSession.provider
        val audioUri = project.clips.firstOrNull { !it.sourceUri.isNullOrBlank() }?.sourceUri
        val input = AutoEditInput(
            project = project,
            template = template,
            provider = provider,
            audioSource = audioUri?.let { AudioSource.FromUri(it) },
            improveCaptionsWithGemini = provider == AiProvider.GEMINI,
        )
        _result.value = orchestrator.run(input) { stepText -> _step.value = stepText }
    }

    private suspend fun resolveTemplate(project: Project): TemplateConfig? {
        val all = TemplateStore(app).loadAll()
        val wanted = AiSession.templateId ?: project.appliedTemplateId
        return wanted?.let { id -> all.find { it.id == id } } ?: all.firstOrNull()
    }
}

@Composable
fun AiAnalysisProgressScreen(nav: NavController) {
    val context = LocalContext.current
    val vm = remember { AiProgressViewModel(context.applicationContext) }
    val step by vm.step.collectAsState()
    val running by vm.running.collectAsState()
    val result by vm.result.collectAsState()
    ScreenScaffold(title = "AI Analysis", onBack = { nav.popBackStack() }) { _ ->
        val finished = result
        if (running || finished == null) {
            Text(step, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "Running the real pipeline — transcription can take a while on long clips.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else when (finished) {
            is AutoEditResult.Ready -> {
                Text(
                    "Draft timeline ready — review it in the editor before exporting.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(finished.messages) { message ->
                        Text(
                            "• $message",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { nav.navigate(Routes.TIMELINE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Review Timeline") }
            }
            is AutoEditResult.NeedsManualCaptions -> {
                Text("Manual captions needed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(finished.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { nav.navigate(Routes.CAPTIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add Captions Manually") }
            }
            is AutoEditResult.Failed -> {
                Text("Auto Edit failed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(finished.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Go Back") }
            }
        }
    }
}
