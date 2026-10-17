package com.pramod.videoeditor.ui.session

import android.content.Context
import androidx.room.Room
import com.pramod.videoeditor.coredatabase.AppDatabase
import com.pramod.videoeditor.coremodel.CaptionSegment
import com.pramod.videoeditor.coremodel.EffectInstance
import com.pramod.videoeditor.coremodel.EmojiOverlay
import com.pramod.videoeditor.coremodel.Project
import com.pramod.videoeditor.corestorage.ProjectFileManager
import com.pramod.videoeditor.featureprojects.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Session-scoped editor state shared by the editor screens: which project is
 * open, the shared playhead, and the selected caption.
 *
 * Persistence goes through the real [ProjectRepository] (Room index +
 * project.json on disk), built lazily here following the same pattern as the
 * export module: Room.databaseBuilder(context, AppDatabase, "pramod_editor.db").
 *
 * NOTE: compiling this file requires `implementation(libs.room.runtime)` in
 * the app module's build.gradle.kts (Room is `implementation`-scoped inside
 * core-database, so it is not visible here otherwise).
 */
object EditorSession {

    private const val PREFS = "editor_session_prefs"
    private const val KEY_PROJECT_ID = "current_project_id"
    private const val DB_NAME = "pramod_editor.db"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var repository: ProjectRepository? = null

    private val _currentProjectId = MutableStateFlow<String?>(null)
    val currentProjectId: StateFlow<String?> = _currentProjectId.asStateFlow()

    private val _project = MutableStateFlow<Project?>(null)
    /** The currently open project, or null when none is open. */
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    /** Shared playhead in milliseconds, owned by the editor session. */
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    private val _selectedCaptionId = MutableStateFlow<String?>(null)
    val selectedCaptionId: StateFlow<String?> = _selectedCaptionId.asStateFlow()

    private val _lastExportJobId = MutableStateFlow<String?>(null)
    /** Id of the most recently started export job (drives the progress screen). */
    val lastExportJobId: StateFlow<String?> = _lastExportJobId.asStateFlow()

    fun setLastExportJobId(id: String?) {
        _lastExportJobId.value = id
    }

    /** Idempotent: stores the application context and restores the last project id. */
    fun ensureInit(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    val ctx = context.applicationContext
                    appContext = ctx
                    _currentProjectId.value = ctx
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_PROJECT_ID, null)
                }
            }
        }
    }

    private fun repo(): ProjectRepository {
        val ctx = appContext
            ?: error("EditorSession.ensureInit(context) must be called before use")
        return repository ?: synchronized(this) {
            repository ?: run {
                val db = Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME).build()
                ProjectRepository(db, ProjectFileManager(ctx)).also { repository = it }
            }
        }
    }

    private fun persistCurrentId(id: String?) {
        _currentProjectId.value = id
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_PROJECT_ID, id)
            ?.apply()
    }

    /** Loads the last-opened project if there is one and nothing is loaded yet. */
    suspend fun ensureLoaded() {
        if (_project.value != null) return
        val id = _currentProjectId.value ?: return
        openProject(id)
    }

    /**
     * Returns the open project, creating (and persisting) a new one with
     * [defaultName] when the session has none — e.g. the user opened the
     * media picker directly.
     */
    suspend fun ensureProject(defaultName: String = "Untitled project"): Project {
        ensureLoaded()
        return _project.value ?: createProject(defaultName)
    }

    /** Live list of all projects, newest first. */
    fun observeProjects(): Flow<List<Project>> = repo().observeProjects()

    /** Projects found on disk but missing from the Room index (crash recovery). */
    suspend fun findRecoverable(): List<String> = withContext(Dispatchers.IO) {
        repo().findRecoverable()
    }

    /** Deletes one project from the Room index and its files on disk. */
    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        repo().delete(id)
        if (_project.value?.id == id) {
            _project.value = null
            _selectedCaptionId.value = null
            persistCurrentId(null)
        }
    }

    /** Deletes ALL projects from the Room index and their files on disk. */
    suspend fun deleteAllProjects(): Int = withContext(Dispatchers.IO) {
        val count = repo().deleteAll()
        _project.value = null
        _selectedCaptionId.value = null
        persistCurrentId(null)
        count
    }

    /** Opens a project by id; clears the session when the id has no data on disk. */
    suspend fun openProject(id: String): Project? = withContext(Dispatchers.IO) {
        val loaded = repo().restoreFromDisk(id)
        _project.value = loaded
        _selectedCaptionId.value = null
        persistCurrentId(if (loaded != null) id else null)
        loaded
    }

    suspend fun createProject(name: String): Project = withContext(Dispatchers.IO) {
        val created = repo().createProject(name)
        _project.value = created
        _selectedCaptionId.value = null
        persistCurrentId(created.id)
        created
    }

    suspend fun saveProject(project: Project) = withContext(Dispatchers.IO) {
        repo().save(project)
        _project.value = project.copy(updatedAt = System.currentTimeMillis())
    }

    suspend fun updateCaptions(transform: (List<CaptionSegment>) -> List<CaptionSegment>) {
        val p = _project.value ?: return
        saveProject(p.copy(captions = transform(p.captions)))
    }

    suspend fun updateEmojiOverlays(transform: (List<EmojiOverlay>) -> List<EmojiOverlay>) {
        val p = _project.value ?: return
        saveProject(p.copy(emojiOverlays = transform(p.emojiOverlays)))
    }

    suspend fun updateEffects(transform: (List<EffectInstance>) -> List<EffectInstance>) {
        val p = _project.value ?: return
        saveProject(p.copy(effects = transform(p.effects)))
    }

    fun seekTo(ms: Long) {
        _playheadMs.value = ms.coerceAtLeast(0L)
    }

    fun selectCaption(id: String?) {
        _selectedCaptionId.value = id
    }
}
