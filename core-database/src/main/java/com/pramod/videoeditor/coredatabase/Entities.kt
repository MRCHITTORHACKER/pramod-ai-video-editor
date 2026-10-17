package com.pramod.videoeditor.coredatabase

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Serialized Project JSON (core-model). Kept as JSON for schema flexibility. */
    val projectJson: String,
    val thumbnailUri: String? = null,
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val version: String,
    /** Full template config JSON. */
    val configJson: String,
)

@Entity(tableName = "export_jobs")
data class ExportJobEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val status: String, // QUEUED | RUNNING | DONE | FAILED | CANCELLED
    val outputUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT id FROM projects")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<TemplateEntity>)

    @Query("SELECT * FROM templates ORDER BY category, name")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE category = :category ORDER BY name")
    fun observeByCategory(category: String): Flow<List<TemplateEntity>>
}

@Dao
interface ExportJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ExportJobEntity)

    @Query("SELECT * FROM export_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExportJobEntity>>

    @Query("SELECT * FROM export_jobs WHERE id = :id")
    suspend fun getById(id: String): ExportJobEntity?
}

@Database(
    entities = [ProjectEntity::class, TemplateEntity::class, ExportJobEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun templateDao(): TemplateDao
    abstract fun exportJobDao(): ExportJobDao
}
