package com.pramod.videoeditor.featureprojects

import com.pramod.videoeditor.coredatabase.AppDatabase
import com.pramod.videoeditor.coredatabase.ProjectEntity
import com.pramod.videoeditor.coremodel.Project
import com.pramod.videoeditor.corestorage.ProjectFileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

/** Single source of truth for projects: Room index + JSON files on disk. */
class ProjectRepository(
    private val db: AppDatabase,
    private val files: ProjectFileManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeProjects(): Flow<List<Project>> =
        db.projectDao().observeAll().map { list ->
            list.mapNotNull { e ->
                try { json.decodeFromString(Project.serializer(), e.projectJson) }
                catch (_: Exception) { null }
            }
        }

    suspend fun createProject(name: String): Project {
        val now = System.currentTimeMillis()
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled project" },
            createdAt = now, updatedAt = now,
        )
        save(project)
        return project
    }

    suspend fun save(project: Project) {
        val updated = project.copy(updatedAt = System.currentTimeMillis())
        val text = json.encodeToString(Project.serializer(), updated)
        files.writeProjectJson(updated.id, text)
        db.projectDao().upsert(
            ProjectEntity(updated.id, updated.name, updated.createdAt, updated.updatedAt, text)
        )
    }

    suspend fun delete(projectId: String) {
        db.projectDao().deleteById(projectId)
        files.projectDir(projectId).deleteRecursively()
    }

    /**
     * Deletes every project from the Room index and removes its files on
     * disk. Returns the number of projects removed.
     */
    suspend fun deleteAll(): Int {
        val ids = db.projectDao().getAllIds()
        ids.forEach { files.projectDir(it).deleteRecursively() }
        db.projectDao().deleteAll()
        return ids.size
    }

    /** Project ids found on disk but missing from the Room index (crash recovery). */
    suspend fun findRecoverable(): List<String> {
        val known = db.projectDao().getAllIds().toSet()
        val onDisk = files.recoverableProjectIds().toSet()
        return (onDisk - known).toList()
    }

    suspend fun restoreFromDisk(projectId: String): Project? {
        val text = files.readProjectJson(projectId) ?: return null
        val project = try { json.decodeFromString(Project.serializer(), text) }
        catch (_: Exception) { return null }
        db.projectDao().upsert(
            ProjectEntity(project.id, project.name, project.createdAt, project.updatedAt, text)
        )
        return project
    }
}
