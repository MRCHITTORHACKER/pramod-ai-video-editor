package com.pramod.videoeditor.corestorage

import android.content.Context
import java.io.File

/**
 * File layout for projects, kept inside app-private storage so no
 * broad storage permission is needed on modern Android:
 *
 *   files/projects/<projectId>/project.json
 *   files/projects/<projectId>/assets/...
 *   files/exports/<exportId>.mp4   (then copied to MediaStore on share)
 */
class ProjectFileManager(private val context: Context) {

    fun projectDir(projectId: String): File =
        File(context.filesDir, "projects/$projectId").apply { mkdirs() }

    fun projectJsonFile(projectId: String): File =
        File(projectDir(projectId), "project.json")

    fun assetsDir(projectId: String): File =
        File(projectDir(projectId), "assets").apply { mkdirs() }

    fun exportFile(exportId: String): File =
        File(context.filesDir, "exports").apply { mkdirs() }
            .let { File(it, "$exportId.mp4") }

    fun writeProjectJson(projectId: String, json: String) {
        projectJsonFile(projectId).writeText(json)
    }

    fun readProjectJson(projectId: String): String? =
        projectJsonFile(projectId).takeIf { it.exists() }?.readText()

    /** Recovery: list project ids that have a project.json but may not be in Room. */
    fun recoverableProjectIds(): List<String> =
        File(context.filesDir, "projects").listFiles()
            ?.filter { it.isDirectory && File(it, "project.json").exists() }
            ?.map { it.name }
            ?: emptyList()

    /** Deletes cached export MP4s; returns the number of files removed. */
    fun clearExportCache(): Int {
        val dir = File(context.filesDir, "exports")
        val files = dir.listFiles { f -> f.isFile && f.extension == "mp4" } ?: return 0
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return removed
    }
}
