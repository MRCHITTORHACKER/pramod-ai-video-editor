package com.pramod.videoeditor.aigroq

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Copies a project clip's audio/content URI into a temp file that the Groq
 * transcription endpoint can accept, using the app's ContentResolver.
 *
 * Honest limits enforced here (no silent truncation):
 * - Only content:// and file:// URIs are supported.
 * - Files larger than Groq's 25 MB transcription limit are rejected with a
 *   clear message instead of being silently cut.
 *
 * Callers must delete the returned file when done (see [GroqClient.transcribe]
 * callers); stale temp files from crashed runs are swept on each call.
 */
object AudioUriResolver {

    private const val TEMP_PREFIX = "groq_audio_"

    @Throws(GroqException::class)
    fun copyToTempFile(context: Context, uriString: String): File {
        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            throw GroqException(GroqError.BAD_AUDIO, "Could not parse the media URI.")
        }
        if (uri.scheme != "content" && uri.scheme != "file") {
            throw GroqException(
                GroqError.BAD_AUDIO,
                "Unsupported media location (only device media can be transcribed).",
            )
        }
        sweepStaleTempFiles(context)
        val suffix = guessExtension(context, uri)
        val tmp = try {
            File.createTempFile(TEMP_PREFIX, suffix, context.cacheDir)
        } catch (e: IOException) {
            throw GroqException(GroqError.BAD_AUDIO, "Could not create a temp audio file (storage full?).")
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > GroqClient.MAX_AUDIO_BYTES) {
                            throw GroqException(
                                GroqError.FILE_TOO_LARGE,
                                "Audio is larger than 25 MB (Groq's transcription limit). " +
                                    "Use a shorter clip or trim the project first.",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) {
                        throw GroqException(GroqError.BAD_AUDIO, "Audio file is empty.")
                    }
                }
            } ?: throw GroqException(GroqError.BAD_AUDIO, "Could not open the media file.")
        } catch (e: GroqException) {
            tmp.delete()
            throw e
        } catch (e: SecurityException) {
            tmp.delete()
            throw GroqException(GroqError.BAD_AUDIO, "No permission to read this media file.")
        } catch (e: IOException) {
            tmp.delete()
            throw GroqException(GroqError.BAD_AUDIO, "Failed while reading the media file.")
        }
        return tmp
    }

    /** Deletes leftover temp files from previous (possibly crashed) runs. */
    fun sweepStaleTempFiles(context: Context) {
        try {
            context.cacheDir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) }
                ?.forEach { it.delete() }
        } catch (_: Exception) {
            // Best effort only — a failed sweep must not block transcription.
        }
    }

    private fun guessExtension(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            val name = uri.lastPathSegment ?: return ".tmp"
            val dot = name.lastIndexOf('.')
            if (dot > 0 && dot < name.length - 1) return name.substring(dot)
        }
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        return when {
            mime?.contains("mp4") == true -> ".mp4"
            mime?.contains("3gp") == true -> ".3gp"
            mime?.contains("webm") == true -> ".webm"
            mime?.contains("mpeg") == true -> ".mp3"
            mime?.contains("wav") == true -> ".wav"
            mime?.contains("ogg") == true -> ".ogg"
            mime?.contains("m4a") == true || mime?.contains("mp4a") == true -> ".m4a"
            else -> ".tmp"
        }
    }
}
