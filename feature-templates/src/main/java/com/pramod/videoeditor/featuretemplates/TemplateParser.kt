package com.pramod.videoeditor.featuretemplates

import com.pramod.videoeditor.coremodel.TemplateConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Parses template JSON configuration into [TemplateConfig].
 * Pure Kotlin — unit-testable on the JVM without Android.
 */
object TemplateParser {

    private val json = Json {
        ignoreUnknownKeys = false // strict: unknown fields are a config bug
        isLenient = false
        explicitNulls = true
    }

    sealed interface ParseResult {
        data class Ok(val config: TemplateConfig) : ParseResult
        data class Err(val message: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        return try {
            ParseResult.Ok(json.decodeFromString(TemplateConfig.serializer(), text))
        } catch (e: SerializationException) {
            ParseResult.Err("Template JSON invalid: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ParseResult.Err("Template JSON invalid: ${e.message}")
        }
    }

    /** Parses every template JSON in [assetsDir]; returns (id -> result). */
    fun parseAll(files: Map<String, String>): Map<String, ParseResult> =
        files.mapValues { (_, text) -> parse(text) }
}
