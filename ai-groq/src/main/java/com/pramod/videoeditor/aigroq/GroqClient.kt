package com.pramod.videoeditor.aigroq

import com.pramod.videoeditor.coremodel.AiKeyStatus
import com.pramod.videoeditor.coremodel.CaptionSegment
import com.pramod.videoeditor.coremodel.WordTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Groq API client (user-supplied free-tier key).
 * Used for: audio transcription (whisper-large-v3) with word timestamps,
 * and caption-assist via chat completions.
 *
 * Data sent to Groq: the project's audio file (transcription) or caption text
 * (assist). Nothing else. Keys are stored in SecureKeyStore, never logged —
 * they only travel as function arguments and are never interpolated into any
 * log or error string.
 */
class GroqClient(
    private val apiKey: () -> String?,
) {
    companion object {
        const val MODELS_URL = "https://api.groq.com/openai/v1/models"
        const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val TRANSCRIBE_MODEL = "whisper-large-v3"
        /** Groq's /audio/transcriptions file-size limit. */
        const val MAX_AUDIO_BYTES = 25L * 1024 * 1024
    }

    /** Short-timeout client used only for key validation (15s per the spec). */
    private val validateHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Long-timeout client for transcription uploads. */
    private val transcribeHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Word(val word: String, val start: Double, val end: Double)

    @Serializable
    private data class Segment(
        val text: String, val start: Double, val end: Double,
        val words: List<Word> = emptyList(),
    )

    @Serializable
    private data class TranscriptionResponse(val segments: List<Segment> = emptyList())

    /**
     * Validates a candidate [key] (typed by the user, not yet saved) with a
     * cheap GET /v1/models request. Returns the mapped status; the key is
     * never logged or persisted here.
     */
    suspend fun validateKey(key: String): AiKeyStatus = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return@withContext AiKeyStatus.INVALID
        try {
            val req = Request.Builder()
                .url(MODELS_URL)
                .header("Authorization", "Bearer $trimmed")
                .get().build()
            validateHttp.newCall(req).execute().use { res ->
                return@withContext when (res.code) {
                    200 -> AiKeyStatus.VALID
                    401 -> AiKeyStatus.INVALID
                    429 -> AiKeyStatus.RATE_LIMITED
                    else -> AiKeyStatus.NETWORK_ERROR
                }
            }
        } catch (e: SocketTimeoutException) {
            AiKeyStatus.NETWORK_ERROR
        } catch (e: IOException) {
            AiKeyStatus.NETWORK_ERROR
        } catch (e: Exception) {
            AiKeyStatus.NETWORK_ERROR
        }
    }

    /** Validates the currently stored key (if any). */
    suspend fun validateKey(): AiKeyStatus =
        validateKey(apiKey() ?: return AiKeyStatus.INVALID)

    /**
     * Transcribes [audioFile] and returns caption segments with word
     * timestamps. Throws [GroqException] with an honest, user-facing message
     * on any failure — the key is never part of the message.
     */
    suspend fun transcribe(audioFile: File, language: String = "hi"): List<CaptionSegment> =
        withContext(Dispatchers.IO) {
            val key = apiKey()
                ?: throw GroqException(
                    GroqError.NO_KEY,
                    "No Groq API key saved. Add one in Settings → AI Providers to use AI transcription.",
                )
            if (!audioFile.exists() || !audioFile.canRead()) {
                throw GroqException(GroqError.BAD_AUDIO, "Audio file is missing or unreadable.")
            }
            if (audioFile.length() == 0L) {
                throw GroqException(GroqError.BAD_AUDIO, "Audio file is empty.")
            }
            if (audioFile.length() > MAX_AUDIO_BYTES) {
                throw GroqException(
                    GroqError.FILE_TOO_LARGE,
                    "Audio is larger than 25 MB (Groq's transcription limit). " +
                        "Use a shorter clip or trim the project first.",
                )
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/*".toMediaType()))
                .addFormDataPart("model", TRANSCRIBE_MODEL)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "word")
                .build()
            val req = Request.Builder()
                .url(TRANSCRIPTIONS_URL)
                .header("Authorization", "Bearer $key")
                .post(body).build()
            val parsed = try {
                transcribeHttp.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw groqHttpError(res.code)
                    json.decodeFromString(
                        TranscriptionResponse.serializer(), res.body!!.string()
                    )
                }
            } catch (e: GroqException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw GroqException(
                    GroqError.TIMEOUT,
                    "Transcription timed out. Check your connection and try a shorter clip.",
                )
            } catch (e: IOException) {
                throw GroqException(
                    GroqError.NO_NETWORK,
                    "Could not reach Groq. Check your internet connection and try again.",
                )
            } catch (e: Exception) {
                throw GroqException(
                    GroqError.SERVER,
                    "Transcription failed unexpectedly (${e.javaClass.simpleName}).",
                )
            }
            val segments = parsed.segments.mapIndexed { i, s ->
                CaptionSegment(
                    id = "cap_$i",
                    text = s.text.trim(),
                    startMs = (s.start * 1000).toLong(),
                    endMs = (s.end * 1000).toLong(),
                    words = s.words.map { w ->
                        WordTimestamp(w.word, (w.start * 1000).toLong(), (w.end * 1000).toLong())
                    },
                )
            }.filter { it.text.isNotBlank() }
            if (segments.isEmpty()) {
                throw GroqException(
                    GroqError.NO_SPEECH,
                    "No speech detected in the audio. Add captions manually if the clip has speech.",
                )
            }
            segments
        }

    private fun groqHttpError(code: Int): GroqException = when (code) {
        401 -> GroqException(
            GroqError.BAD_KEY,
            "Groq rejected the API key (invalid or revoked). Check it in Settings → AI Providers.",
        )
        429 -> GroqException(
            GroqError.RATE_LIMITED,
            "Groq rate limit reached. Wait a minute and try again.",
        )
        402 -> GroqException(
            GroqError.QUOTA,
            "Groq quota exhausted for this key. Check your Groq console usage.",
        )
        400 -> GroqException(
            GroqError.BAD_AUDIO,
            "Groq could not read this audio (unsupported format). Try exporting the clip's audio first.",
        )
        413 -> GroqException(
            GroqError.FILE_TOO_LARGE,
            "Audio is larger than 25 MB (Groq's transcription limit). Use a shorter clip.",
        )
        in 500..599 -> GroqException(
            GroqError.SERVER,
            "Groq's servers returned an error (HTTP $code). Try again shortly.",
        )
        else -> GroqException(
            GroqError.SERVER,
            "Groq transcription failed (HTTP $code). Try again.",
        )
    }
}

/** Machine-readable failure kinds for Groq calls. */
enum class GroqError {
    NO_KEY, BAD_KEY, NO_NETWORK, TIMEOUT, RATE_LIMITED, QUOTA,
    SERVER, NO_SPEECH, FILE_TOO_LARGE, BAD_AUDIO,
}

/**
 * Typed failure from [GroqClient]. [message] is safe to show in the UI —
 * it never contains the API key.
 */
class GroqException(val kind: GroqError, message: String) : Exception(message)

/** User-facing message for a key-validation [status]. Never includes the key. */
fun groqStatusMessage(status: AiKeyStatus): String = when (status) {
    AiKeyStatus.VALID -> "Key is valid and saved."
    AiKeyStatus.INVALID -> "Invalid key — check the key and try again."
    AiKeyStatus.RATE_LIMITED -> "Rate limited — wait a minute and retry."
    AiKeyStatus.QUOTA_EXHAUSTED -> "Quota exhausted on this key."
    AiKeyStatus.NETWORK_ERROR -> "Could not reach Groq — check your connection and retry."
    AiKeyStatus.UNKNOWN -> "Not validated yet."
}
