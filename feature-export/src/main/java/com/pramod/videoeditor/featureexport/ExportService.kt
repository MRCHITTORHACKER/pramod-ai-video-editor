package com.pramod.videoeditor.featureexport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.pramod.videoeditor.coredatabase.AppDatabase
import com.pramod.videoeditor.coredatabase.ExportJobEntity
import com.pramod.videoeditor.coremodel.ExportSettings
import com.pramod.videoeditor.coremodel.Project
import com.pramod.videoeditor.corestorage.ProjectFileManager
import com.pramod.videoeditor.mediaengine.Media3ExportEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Process-wide store for the export pipeline: one cached Room instance and a
 * ProjectRepository built on it. Screens use this instead of building their
 * own database instances.
 */
object ExportStore {
    @Volatile
    private var db: AppDatabase? = null

    fun database(context: Context): AppDatabase =
        db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pramod_editor.db",
            ).build().also { db = it }
        }

    fun files(context: Context): ProjectFileManager =
        ProjectFileManager(context.applicationContext)
}

/**
 * Foreground service for long-running exports, with a progress notification.
 *
 * Progress contract: the VFX/media-engine chunk exposes
 * `Media3ExportEngine.progressListener: ((fraction: Float, status: String) -> Unit)?`.
 * This service sets that listener and forwards every callback into the
 * [progress] / [status] flows below, which [ExportProgressScreen] collects.
 * Same process, so the UI reads them straight from the companion.
 *
 * Started with extras: EXTRA_PROJECT_JSON, EXTRA_SETTINGS_JSON, EXTRA_JOB_ID.
 * Send [ACTION_CANCEL] to cancel the running export.
 */
class ExportService : Service() {

    companion object {
        const val EXTRA_PROJECT_JSON = "project_json"
        const val EXTRA_SETTINGS_JSON = "settings_json"
        const val EXTRA_JOB_ID = "job_id"
        const val ACTION_CANCEL = "com.pramod.videoeditor.featureexport.action.CANCEL"
        private const val CHANNEL_ID = "export_channel"
        private const val NOTIF_ID = 1001

        private val _progress = MutableStateFlow(0f)
        /** 0..1 progress of the current (or most recent) export. */
        val progress: StateFlow<Float> = _progress

        private val _status = MutableStateFlow("Idle")
        /** Human-readable status of the current (or most recent) export. */
        val status: StateFlow<String> = _status

        private val _warnings = MutableStateFlow<List<String>>(emptyList())
        /**
         * Non-fatal warnings from the most recent export run, e.g. a missing
         * background-music file or skipped SFX event. Surfaced by
         * ExportProgressScreen; empty while an export is running.
         */
        val warnings: StateFlow<List<String>> = _warnings

        fun start(context: Context, projectJson: String, settingsJson: String, jobId: String) {
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra(EXTRA_PROJECT_JSON, projectJson)
                putExtra(EXTRA_SETTINGS_JSON, settingsJson)
                putExtra(EXTRA_JOB_ID, jobId)
            }
            // minSdk 26: startForegroundService exists on the framework Context.
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private var exportJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Video exports", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            _status.value = "Cancelling…"
            exportJob?.cancel()
            return START_NOT_STICKY
        }
        if (exportJob?.isActive == true) {
            // One export at a time; never silently stack a second job.
            _status.value = "An export is already running"
            return START_NOT_STICKY
        }
        val projectJson = intent?.getStringExtra(EXTRA_PROJECT_JSON) ?: return START_NOT_STICKY
        val settingsJson = intent?.getStringExtra(EXTRA_SETTINGS_JSON) ?: return START_NOT_STICKY
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: UUID.randomUUID().toString()

        _progress.value = 0f
        _status.value = "Starting…"
        _warnings.value = emptyList()
        startForeground(NOTIF_ID, buildNotification(0f, "Starting…"))
        exportJob = scope.launch {
            try {
                runExport(projectJson, settingsJson, jobId)
            } catch (e: CancellationException) {
                markCancelled(jobId)
                _status.value = "Cancelled"
                throw e
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runExport(projectJson: String, settingsJson: String, jobId: String) {
        val db = ExportStore.database(applicationContext)
        val dao = db.exportJobDao()
        val files = ExportStore.files(applicationContext)
        val engine = Media3ExportEngine(applicationContext)
        val now = System.currentTimeMillis()
        try {
            val project = json.decodeFromString(Project.serializer(), projectJson)
            val settings = json.decodeFromString(ExportSettings.serializer(), settingsJson)
            val out = files.exportFile(jobId)
            dao.upsert(ExportJobEntity(jobId, project.id, "RUNNING", null, null, now, null))
            // Contract with the VFX chunk: the engine reports progress through
            // progressListener (fraction 0..1 + status text).
            engine.progressListener = { fraction, status -> onEngineProgress(fraction, status) }
            val result = engine.export(project, settings, out) { p ->
                // Legacy lambda channel; kept until the VFX chunk removes the
                // onProgress parameter (contract says the listener is additive).
                onEngineProgress(p, "Rendering video…")
            }
            when (result) {
                is Media3ExportEngine.ExportResult.Success -> {
                    dao.upsert(
                        ExportJobEntity(jobId, project.id, "DONE", out.absolutePath, null,
                            now, System.currentTimeMillis())
                    )
                    _progress.value = 1f
                    _status.value = "Done"
                    _warnings.value = result.warnings
                    updateNotification(1f, "Done")
                }
                is Media3ExportEngine.ExportResult.Failure -> {
                    dao.upsert(
                        ExportJobEntity(jobId, project.id, "FAILED", null, result.message,
                            now, System.currentTimeMillis())
                    )
                    _status.value = "Failed: ${result.message}"
                    updateNotification(_progress.value, "Failed")
                }
                Media3ExportEngine.ExportResult.Cancelled -> {
                    markCancelled(jobId)
                    _status.value = "Cancelled"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.upsert(
                ExportJobEntity(jobId, "unknown", "FAILED", null, e.message,
                    now, System.currentTimeMillis())
            )
            _status.value = "Failed: ${e.message ?: "unknown error"}"
            updateNotification(_progress.value, "Failed")
        }
    }

    private suspend fun markCancelled(jobId: String) {
        val now = System.currentTimeMillis()
        try {
            ExportStore.database(applicationContext).exportJobDao().upsert(
                ExportJobEntity(jobId, "unknown", "CANCELLED", null, "Cancelled by user", now, now)
            )
        } catch (_: Exception) {
            // DB unavailable; nothing more we can record.
        }
    }

    private fun onEngineProgress(fraction: Float, status: String) {
        val f = fraction.coerceIn(0f, 1f)
        _progress.value = f
        _status.value = status
        updateNotification(f, status)
    }

    private fun buildNotification(fraction: Float, status: String): Notification {
        val pct = (fraction * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting video")
            .setContentText("$status • $pct%")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(fraction: Float, status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(fraction, status))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
