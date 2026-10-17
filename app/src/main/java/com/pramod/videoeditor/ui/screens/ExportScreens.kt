package com.pramod.videoeditor.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.pramod.videoeditor.coremodel.AspectRatio
import com.pramod.videoeditor.coremodel.ExportSettings
import com.pramod.videoeditor.coredatabase.ExportJobEntity
import com.pramod.videoeditor.featureexport.ExportService
import com.pramod.videoeditor.featureexport.ExportStore
import com.pramod.videoeditor.ui.ScreenScaffold
import com.pramod.videoeditor.ui.navigation.Routes
import com.pramod.videoeditor.ui.session.EditorSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val exportJson = Json { ignoreUnknownKeys = true }

/**
 * Shares an exported MP4 through the system share sheet via FileProvider.
 * Authority must match the manifest provider:
 * "<applicationId>.fileprovider" (see res/xml/filepaths.xml).
 */
internal fun shareExport(context: Context, file: File, onError: (String) -> Unit) {
    try {
        if (!file.exists()) {
            onError("Export file not found: ${file.name}")
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share video")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        onError("Share failed: ${e.message}")
    }
}

// ---------------- Export Settings ----------------
class ExportSettingsViewModel : ViewModel() {
    private val _ratio = MutableStateFlow(AspectRatio.R_9_16)
    val ratio: StateFlow<AspectRatio> = _ratio
    private val _resolution = MutableStateFlow("1080p")
    val resolution: StateFlow<String> = _resolution
    private val _fps = MutableStateFlow(30)
    val fps: StateFlow<Int> = _fps
    private val _bitrate = MutableStateFlow(8_000_000)
    val bitrate: StateFlow<Int> = _bitrate
    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setRatio(r: AspectRatio) { _ratio.value = r }
    fun setResolution(r: String) { _resolution.value = r }
    fun setFps(f: Int) { _fps.value = f }
    fun setBitrate(b: Int) { _bitrate.value = b }
    fun clearError() { _error.value = null }

    fun dimensions(): Pair<Int, Int> {
        val long = if (_resolution.value == "720p") 1280 else 1920
        val short = if (_resolution.value == "720p") 720 else 1080
        return when (_ratio.value) {
            AspectRatio.R_9_16 -> short to long
            AspectRatio.R_16_9 -> long to short
            AspectRatio.R_1_1 -> short to short
        }
    }

    fun buildSettings(): ExportSettings {
        val (w, h) = dimensions()
        return ExportSettings(
            aspectRatio = _ratio.value,
            width = w,
            height = h,
            fps = _fps.value,
            videoBitrate = _bitrate.value,
            audioBitrate = 128_000,
        )
    }

    /**
     * Starts a real export of the current project with these settings.
     * Calls [onStarted] with the new job id on success.
     */
    fun startExport(context: Context, onStarted: (jobId: String) -> Unit) {
        if (_starting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _starting.value = true
            _error.value = null
            try {
                val appCtx = context.applicationContext
                EditorSession.ensureInit(appCtx)
                EditorSession.ensureLoaded()
                val project = EditorSession.project.value
                if (project == null) {
                    _error.value = "No project yet — create one from the Media tab."
                    return@launch
                }
                if (project.clips.isEmpty()) {
                    _error.value = "Project has no clips yet — add photos or videos first."
                    return@launch
                }
                val settings = buildSettings()
                val jobId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                ExportStore.database(appCtx).exportJobDao().upsert(
                    ExportJobEntity(jobId, project.id, "QUEUED", null, null, now, null)
                )
                val projectJson = exportJson.encodeToString(
                    com.pramod.videoeditor.coremodel.Project.serializer(), project)
                val settingsJson = exportJson.encodeToString(ExportSettings.serializer(), settings)
                withContext(Dispatchers.Main) {
                    ExportService.start(appCtx, projectJson, settingsJson, jobId)
                    onStarted(jobId)
                }            } catch (e: Exception) {
                _error.value = "Could not start export: ${e.message}"
            } finally {
                _starting.value = false
            }
        }
    }
}

@Composable
fun ExportSettingsScreen(nav: NavController) {
    val vm = remember { ExportSettingsViewModel() }
    val context = LocalContext.current
    val ratio by vm.ratio.collectAsState()
    val resolution by vm.resolution.collectAsState()
    val fps by vm.fps.collectAsState()
    val bitrate by vm.bitrate.collectAsState()
    val starting by vm.starting.collectAsState()
    val error by vm.error.collectAsState()

    val bitrateOptions = listOf(
        "Data saver" to 4_000_000,
        "Standard" to 8_000_000,
        "High" to 12_000_000,
    )

    ScreenScaffold(title = "Export Settings", onBack = { nav.popBackStack() }) { showMessage ->
        val err = error
        LaunchedEffect(err) { if (err != null) showMessage(err) }

        Text("Aspect ratio", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectRatio.entries.forEach { r ->
                OutlinedButton(onClick = { vm.setRatio(r) }) {
                    Text((if (r == ratio) "✓ " else "") + r.label)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Resolution", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("720p", "1080p").forEach { res ->
                OutlinedButton(onClick = { vm.setResolution(res) }) {
                    Text((if (res == resolution) "✓ " else "") + res)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Frame rate", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24, 30, 60).forEach { f ->
                OutlinedButton(onClick = { vm.setFps(f) }) {
                    Text((if (f == fps) "✓ " else "") + "${f}fps")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Quality", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bitrateOptions.forEach { (label, b) ->
                OutlinedButton(onClick = { vm.setBitrate(b) }) {
                    Text((if (b == bitrate) "✓ " else "") + label)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val (w, h) = vm.dimensions()
        Text("Output: ${w}×$h MP4 (H.264), ${fps}fps",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (err != null) {
            Text(err, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        if (starting) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                vm.clearError()
                vm.startExport(context) { jobId ->
                    EditorSession.setLastExportJobId(jobId)
                    nav.navigate(Routes.EXPORT_PROGRESS)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !starting,
        ) { Text("Start Export") }
    }
}

// ---------------- Export Progress ----------------
class ExportProgressViewModel(private val jobId: String?) : ViewModel() {
    /** Live 0..1 progress from the running ExportService. */
    val progress: StateFlow<Float> = ExportService.progress
    /** Live status text from the running ExportService. */
    val status: StateFlow<String> = ExportService.status
    /** Non-fatal warnings from the most recent export (e.g. a missing SFX asset). */
    val warnings: StateFlow<List<String>> = ExportService.warnings

    private val _job = MutableStateFlow<ExportJobEntity?>(null)
    /** Durable job row from Room — survives process death, shows terminal state. */
    val job: StateFlow<ExportJobEntity?> = _job

    fun observe(context: Context) {
        val id = jobId ?: return
        viewModelScope.launch {
            ExportStore.database(context.applicationContext).exportJobDao()
                .observeAll()
                .map { list -> list.firstOrNull { it.id == id } }
                .collect { _job.value = it }
        }
    }

    fun cancelExport(context: Context) = ExportService.cancel(context)
}

@Composable
fun ExportProgressScreen(nav: NavController) {
    val jobId = EditorSession.lastExportJobId.value
    val vm = remember(jobId) { ExportProgressViewModel(jobId) }
    val context = LocalContext.current
    val progress by vm.progress.collectAsState()
    val status by vm.status.collectAsState()
    val job by vm.job.collectAsState()
    val warnings by vm.warnings.collectAsState()

    LaunchedEffect(jobId) { vm.observe(context) }

    ScreenScaffold(title = "Exporting", onBack = { nav.popBackStack() }) { showMessage ->
        if (jobId == null) {
            Text("No export running.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { nav.navigate(Routes.EXPORT_SETTINGS) },
                modifier = Modifier.fillMaxWidth()) { Text("Export Settings") }
            return@ScreenScaffold
        }
        val jobStatus = job?.status
        val headline = when (jobStatus) {
            "DONE" -> "Export complete"
            "FAILED" -> "Export failed"
            "CANCELLED" -> "Export cancelled"
            else -> status
        }
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (jobStatus == "FAILED") {
            Spacer(Modifier.height(8.dp))
            Text(job?.errorMessage ?: "Unknown error",
                color = MaterialTheme.colorScheme.error)
        }
        if (jobStatus == "DONE" && warnings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Warnings", style = MaterialTheme.typography.titleSmall)
            warnings.forEach { w ->
                Text("• $w",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
        when (jobStatus) {
            "DONE" -> {
                val out = job?.outputUri
                Button(
                    onClick = {
                        if (out != null) shareExport(context, File(out)) { showMessage(it) }
                        else showMessage("Export file path missing")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share Video") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { nav.navigate(Routes.EXPORT_HISTORY) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("View Export History") }
            }
            "FAILED", "CANCELLED" -> {
                Button(
                    onClick = { nav.navigate(Routes.EXPORT_SETTINGS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back to Export Settings") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { nav.navigate(Routes.EXPORT_HISTORY) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("View Export History") }
            }
            else -> {
                OutlinedButton(
                    onClick = { vm.cancelExport(context); showMessage("Cancelling export…") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel Export") }
            }
        }
    }
}

// ---------------- Export History ----------------
class ExportHistoryViewModel : ViewModel() {
    data class Job(
        val id: String,
        val status: String,
        val outputPath: String?,
        val error: String?,
        val createdAt: Long,
    )

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    fun observe(context: Context) {
        viewModelScope.launch {
            ExportStore.database(context.applicationContext).exportJobDao()
                .observeAll()
                .collect { list ->
                    _jobs.value = list.map {
                        Job(it.id, it.status, it.outputUri, it.errorMessage, it.createdAt)
                    }
                }
        }
    }
}

@Composable
fun ExportHistoryScreen(nav: NavController) {
    val vm = remember { ExportHistoryViewModel() }
    val context = LocalContext.current
    val jobs by vm.jobs.collectAsState()

    LaunchedEffect(Unit) { vm.observe(context) }

    val dateFmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }

    ScreenScaffold(title = "Export History", onBack = { nav.popBackStack() }) { showMessage ->
        if (jobs.isEmpty()) {
            Text("No exports yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(jobs, key = { it.id }) { j ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${j.outputPath?.substringAfterLast('/') ?: j.id.take(8)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${j.status} • ${dateFmt.format(Date(j.createdAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (j.status == "FAILED" && j.error != null) {
                            Text(j.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (j.status == "DONE" && j.outputPath != null) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    shareExport(context, File(j.outputPath)) { showMessage(it) }
                                },
                            ) { Text("Share") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val latest = jobs.firstOrNull { it.status == "DONE" && it.outputPath != null }
        OutlinedButton(
            onClick = {
                if (latest?.outputPath != null) {
                    shareExport(context, File(latest.outputPath)) { showMessage(it) }
                } else {
                    showMessage("No completed export to share yet")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = latest != null,
        ) { Text("Share Latest Export") }
    }
}
