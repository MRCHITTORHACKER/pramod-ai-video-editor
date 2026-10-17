package com.pramod.videoeditor.aigemini

import com.pramod.videoeditor.coremodel.AiKeyStatus
import com.pramod.videoeditor.coremodel.CaptionSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Google Gemini API client (user-supplied free-tier key from AI Studio).
 * Used for: caption-assist (rewrites, keyword highlights, emoji suggestions).
 *
 * Data sent to Google: caption text the user asks to improve. Nothing else.
 * Keys are stored in SecureKeyStore, never logged — they only travel as
 * function arguments and are never interpolated into any log or error string.
 */
class GeminiClient(
    private val apiKey: () -> String?,
) {
    companion object {
        const val MODELS_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        const val IMPROVE_MODEL = "gemini-2.0-flash"
    }

    /** Short-timeout client used only for key validation (15s per the spec). */
    private val validateHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val generateHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class ModelList(val models: List<Model> = emptyList())
    @Serializable
    private data class Model(val name: String = "")

    /**
     * Validates a candidate [key] (typed by the user, not yet saved) with a
     * cheap GET models request. Returns the mapped status; the key is never
     * logged or persisted here.
     */
    suspend fun validateKey(key: String): AiKeyStatus = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return@withContext AiKeyStatus.INVALID
        try {
            val req = Request.Builder()
                .url("$MODELS_URL_BASE?key=$trimmed")
                .get().build()
            validateHttp.newCall(req).execute().use { res ->
                return@withContext when (res.code) {
                    200 -> AiKeyStatus.VALID
                    // Google returns 400 (INVALID_ARGUMENT) for a bad API key.
                    400, 401, 403 -> AiKeyStatus.INVALID
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

    @Serializable
    private data class GenRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig,
    )
    @Serializable
    private data class GenerationConfig(
        val temperature: Float = 0.3f,
        val responseMimeType: String = "application/json",
    )
    @Serializable
    private data class Content(val parts: List<Part>)
    @Serializable
    private data class Part(val text: String)
    @Serializable
    private data class ImproveItem(val id: String, val text: String)
    @Serializable
    private data class GenResponse(val candidates: List<Candidate> = emptyList())
    @Serializable
    private data class Candidate(val content: Content? = null)

    /**
     * Asks Gemini to improve caption wording, conservatively: it may only
     * rewrite the *text* of each segment — never the count, order, ids or
     * timestamps. Returns new [CaptionSegment]s with the original timestamps
     * intact, matched by segment id.
     *
     * Throws [GeminiException] with an honest, user-facing message on failure
     * — the editor keeps the original captions in that case.
     */
    suspend fun improveCaptions(
        captions: List<CaptionSegment>,
        language: String = "Hindi",
    ): List<CaptionSegment> = withContext(Dispatchers.IO) {
        val key = apiKey()
            ?: throw GeminiException(
                GeminiError.NO_KEY,
                "No Gemini API key saved. Add one in Settings → AI Providers to use caption assist.",
            )
        if (captions.isEmpty()) {
            throw GeminiException(GeminiError.EMPTY_INPUT, "There are no captions to improve.")
        }
        val inputItems = captions.map { ImproveItem(it.id, it.text) }
        val inputJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ImproveItem.serializer()),
            inputItems,
        )
        val prompt = "You are a caption editor for short educational videos. " +
            "Rewrite each caption's TEXT for clarity and punch in $language: fix grammar, " +
            "remove filler words, keep each line short enough to read aloud in its time slot. " +
            "RULES: (1) Keep the meaning identical — never add facts, names, numbers or claims " +
            "that are not in the original text. (2) Return EXACTLY the same number of items, " +
            "in the SAME order, with the SAME ids. (3) Output ONLY a JSON array of objects " +
            "with keys \"id\" and \"text\" — no markdown, no commentary.\n\n" + inputJson
        val payload = json.encodeToString(
            GenRequest.serializer(),
            GenRequest(
                listOf(Content(listOf(Part(prompt)))),
                GenerationConfig(),
            ),
        )
        val url = "$MODELS_URL_BASE/$IMPROVE_MODEL:generateContent?key=$key"
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val rawText = try {
            generateHttp.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw geminiHttpError(res.code)
                val parsed = json.decodeFromString(GenResponse.serializer(), res.body!!.string())
                parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: throw GeminiException(
                        GeminiError.EMPTY_RESPONSE,
                        "Gemini returned no text. Your original captions are unchanged.",
                    )
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw GeminiException(
                GeminiError.TIMEOUT,
                "Caption assist timed out. Check your connection — originals are unchanged.",
            )
        } catch (e: IOException) {
            throw GeminiException(
                GeminiError.NO_NETWORK,
                "Could not reach Google. Check your internet connection — originals are unchanged.",
            )
        } catch (e: Exception) {
            throw GeminiException(
                GeminiError.SERVER,
                "Caption assist failed unexpectedly (${e.javaClass.simpleName}) — originals are unchanged.",
            )
        }
        val improvedById = parseImprovedArray(rawText)
        // Conservative merge: only accept rewrites for known ids; timestamps,
        // order and count come from the originals, never from the model.
        captions.map { original ->
            val improved = improvedById[original.id]?.takeIf { it.isNotBlank() }
            if (improved != null) original.copy(text = improved.trim()) else original
        }
    }

    private fun parseImprovedArray(rawText: String): Map<String, String> {
        val stripped = rawText.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val element = try {
            json.parseToJsonElement(stripped)
        } catch (e: Exception) {
            throw GeminiException(
                GeminiError.PARSE_ERROR,
                "Gemini's reply was not valid JSON — originals are unchanged.",
            )
        }
        val array: JsonArray = (element as? JsonArray)
            ?: throw GeminiException(
                GeminiError.PARSE_ERROR,
                "Gemini did not return a caption list — originals are unchanged.",
            )
        return buildMap {
            for (item in array) {
                val obj = item as? JsonObject ?: continue
                val id = obj["id"]?.jsonPrimitive?.content ?: continue
                val text = obj["text"]?.jsonPrimitive?.content ?: continue
                put(id, text)
            }
        }
    }

    private fun geminiHttpError(code: Int): GeminiException = when (code) {
        400, 401, 403 -> GeminiException(
            GeminiError.BAD_KEY,
            "Google rejected the API key (invalid or revoked). Check it in Settings → AI Providers.",
        )
        429 -> GeminiException(
            GeminiError.RATE_LIMITED,
            "Gemini rate limit reached. Wait a minute and try again.",
        )
        in 500..599 -> GeminiException(
            GeminiError.SERVER,
            "Google's servers returned an error (HTTP $code). Try again shortly.",
        )
        else -> GeminiException(
            GeminiError.SERVER,
            "Caption assist failed (HTTP $code). Try again.",
        )
    }
}

/** Machine-readable failure kinds for Gemini calls. */
enum class GeminiError {
    NO_KEY, BAD_KEY, NO_NETWORK, TIMEOUT, RATE_LIMITED,
    SERVER, EMPTY_INPUT, EMPTY_RESPONSE, PARSE_ERROR,
}

/**
 * Typed failure from [GeminiClient]. [message] is safe to show in the UI —
 * it never contains the API key.
 */
class GeminiException(val kind: GeminiError, message: String) : Exception(message)

/** User-facing message for a key-validation [status]. Never includes the key. */
fun geminiStatusMessage(status: AiKeyStatus): String = when (status) {
    AiKeyStatus.VALID -> "Key is valid and saved."
    AiKeyStatus.INVALID -> "Invalid key — check the key and try again."
    AiKeyStatus.RATE_LIMITED -> "Rate limited — wait a minute and retry."
    AiKeyStatus.QUOTA_EXHAUSTED -> "Quota exhausted on this key."
    AiKeyStatus.NETWORK_ERROR -> "Could not reach Google — check your connection and retry."
    AiKeyStatus.UNKNOWN -> "Not validated yet."
}
