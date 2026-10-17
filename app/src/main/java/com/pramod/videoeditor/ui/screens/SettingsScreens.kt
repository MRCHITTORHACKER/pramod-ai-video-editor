package com.pramod.videoeditor.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.pramod.videoeditor.aigemini.GeminiClient
import com.pramod.videoeditor.aigemini.geminiStatusMessage
import com.pramod.videoeditor.aigroq.GroqClient
import com.pramod.videoeditor.aigroq.groqStatusMessage
import com.pramod.videoeditor.coremodel.AiKeyStatus
import com.pramod.videoeditor.coremodel.AiProvider
import com.pramod.videoeditor.coresecurity.SecureKeyStore
import com.pramod.videoeditor.corestorage.ProjectFileManager
import com.pramod.videoeditor.ui.ScreenScaffold
import com.pramod.videoeditor.ui.session.EditorSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// ---------------- AI Provider Settings ----------------
private const val KEY_GROQ = "groq"
private const val KEY_GEMINI = "gemini"

/**
 * Real key validation: the Validate button calls the providers' suspend
 * validateKey() over the network (with a timeout guard) and the key is
 * written to the Keystore ONLY when validation succeeds. Keys travel as
 * function arguments and are never logged — status messages never contain
 * key material.
 */
class AiSettingsViewModel(appContext: Context) : ViewModel() {
    private val keyStore = SecureKeyStore(appContext)
    private val groq = GroqClient { keyStore.getKey(KEY_GROQ) }
    private val gemini = GeminiClient { keyStore.getKey(KEY_GEMINI) }

    private val _groqStatus = MutableStateFlow(AiKeyStatus.UNKNOWN)
    val groqStatus: StateFlow<AiKeyStatus> = _groqStatus
    private val _geminiStatus = MutableStateFlow(AiKeyStatus.UNKNOWN)
    val geminiStatus: StateFlow<AiKeyStatus> = _geminiStatus
    private val _groqMessage = MutableStateFlow(
        if (keyStore.hasKey(KEY_GROQ)) "A key is saved — tap Validate & Save to verify it."
        else "No key saved."
    )
    val groqMessage: StateFlow<String> = _groqMessage
    private val _geminiMessage = MutableStateFlow(
        if (keyStore.hasKey(KEY_GEMINI)) "A key is saved — tap Validate & Save to verify it."
        else "No key saved."
    )
    val geminiMessage: StateFlow<String> = _geminiMessage
    private val _validating = MutableStateFlow<AiProvider?>(null)
    val validating: StateFlow<AiProvider?> = _validating

    private fun keyName(provider: AiProvider) = when (provider) {
        AiProvider.GROQ -> KEY_GROQ
        AiProvider.GEMINI -> KEY_GEMINI
        else -> ""
    }

    private fun setStatus(provider: AiProvider, status: AiKeyStatus) {
        if (provider == AiProvider.GROQ) _groqStatus.value = status
        else _geminiStatus.value = status
    }

    private fun setMessage(provider: AiProvider, message: String) {
        if (provider == AiProvider.GROQ) _groqMessage.value = message
        else _geminiMessage.value = message
    }

    private fun statusMessage(provider: AiProvider, status: AiKeyStatus): String =
        if (provider == AiProvider.GROQ) groqStatusMessage(status)
        else geminiStatusMessage(status)

    /** Validates the typed key against the real API; saves it only on success. */
    fun validate(provider: AiProvider, key: String) {
        if (provider != AiProvider.GROQ && provider != AiProvider.GEMINI) return
        viewModelScope.launch {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) {
                setStatus(provider, AiKeyStatus.INVALID)
                setMessage(provider, "Paste your API key first.")
                return@launch
            }
            _validating.value = provider
            setMessage(provider, "Checking key with the provider…")
            val timedOut: Boolean
            val status = try {
                timedOut = false
                withTimeout(20_000) {
                    when (provider) {
                        AiProvider.GROQ -> groq.validateKey(trimmed)
                        else -> gemini.validateKey(trimmed)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                AiKeyStatus.NETWORK_ERROR
            } catch (_: Exception) {
                timedOut = false
                AiKeyStatus.NETWORK_ERROR
            }
            _validating.value = null
            setStatus(provider, status)
            if (status == AiKeyStatus.VALID) {
                keyStore.saveKey(keyName(provider), trimmed)
                setMessage(provider, statusMessage(provider, status))
            } else if (timedOut) {
                setMessage(provider, "Validation timed out — check your connection and retry.")
            } else {
                setMessage(provider, statusMessage(provider, status))
            }
        }
    }

    fun remove(provider: AiProvider) {
        if (provider != AiProvider.GROQ && provider != AiProvider.GEMINI) return
        keyStore.removeKey(keyName(provider))
        setStatus(provider, AiKeyStatus.UNKNOWN)
        setMessage(provider, "Key removed.")
    }
}

@Composable
fun AiProviderSettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val vm = remember(context) { AiSettingsViewModel(context.applicationContext) }
    val groqStatus by vm.groqStatus.collectAsState()
    val geminiStatus by vm.geminiStatus.collectAsState()
    val groqMessage by vm.groqMessage.collectAsState()
    val geminiMessage by vm.geminiMessage.collectAsState()
    val validating by vm.validating.collectAsState()
    var groqKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }

    ScreenScaffold(title = "AI Providers", onBack = { nav.popBackStack() }) { _ ->
        Text(
            "Optional. Add your own FREE-tier keys (Groq console, Google AI Studio). " +
                "Keys are stored encrypted in the Android Keystore, are saved only after " +
                "successful validation, and are never logged. " +
                "Manual editing works fully without AI.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        ProviderRow("Groq", groqKey, { groqKey = it }, groqStatus, groqMessage,
            validating = validating == AiProvider.GROQ,
            onValidate = { vm.validate(AiProvider.GROQ, groqKey) },
            onRemove = { vm.remove(AiProvider.GROQ); groqKey = "" })
        Spacer(Modifier.height(12.dp))
        ProviderRow("Gemini", geminiKey, { geminiKey = it }, geminiStatus, geminiMessage,
            validating = validating == AiProvider.GEMINI,
            onValidate = { vm.validate(AiProvider.GEMINI, geminiKey) },
            onRemove = { vm.remove(AiProvider.GEMINI); geminiKey = "" })
        Spacer(Modifier.height(12.dp))
        Text(
            "Data sent: audio file for transcription (Groq) or caption text for improvement (Gemini). Nothing else.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderRow(
    name: String, key: String, onKey: (String) -> Unit, status: AiKeyStatus,
    message: String, validating: Boolean,
    onValidate: () -> Unit, onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = key, onValueChange = onKey,
            label = { Text("API key") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Status: ${status.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onValidate, enabled = !validating) {
                Text(if (validating) "Checking…" else "Validate & Save")
            }
            OutlinedButton(onClick = onRemove) { Text("Remove") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------- General Settings ----------------
@Composable
fun GeneralSettingsScreen(nav: NavController) {
    var lowEffects by remember { mutableStateOf(false) }
    var analytics by remember { mutableStateOf(false) }
    ScreenScaffold(title = "Settings", onBack = { nav.popBackStack() }) { _ ->
        SettingSwitch("Low-effects mode", "Reduces VFX intensity on this device.", lowEffects) { lowEffects = it }
        SettingSwitch("Usage analytics", "Currently no analytics SDK is integrated.", analytics) { analytics = it }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { nav.navigate("ai_settings") }, modifier = Modifier.fillMaxWidth()) {
            Text("AI Providers")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { nav.navigate("privacy") }, modifier = Modifier.fillMaxWidth()) {
            Text("Privacy & Storage")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { nav.navigate("about") }, modifier = Modifier.fillMaxWidth()) {
            Text("About & Diagnostics")
        }
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
    Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
}

// ---------------- Privacy & Storage ----------------
class PrivacyStorageViewModel : ViewModel() {
    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting

    /** Permanently deletes every project on this device (Room index + files). */
    fun deleteAllProjects(context: Context, onDone: (String) -> Unit) {
        if (_deleting.value) return
        viewModelScope.launch {
            _deleting.value = true
            try {
                val appCtx = context.applicationContext
                EditorSession.ensureInit(appCtx)
                val count = EditorSession.deleteAllProjects()
                onDone("Deleted $count project(s).")
            } catch (e: Exception) {
                onDone("Delete failed: ${e.message}")
            } finally {
                _deleting.value = false
            }
        }
    }

    /** Deletes cached export MP4s from app-private storage. */
    fun clearExportCache(context: Context, onDone: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    ProjectFileManager(context.applicationContext).clearExportCache()
                }
                onDone("Cleared $removed cached export file(s).")
            } catch (e: Exception) {
                onDone("Clear failed: ${e.message}")
            }
        }
    }
}

@Composable
fun PrivacyStorageScreen(nav: NavController) {
    val vm = remember { PrivacyStorageViewModel() }
    val deleting by vm.deleting.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenScaffold(title = "Privacy & Storage", onBack = { nav.popBackStack() }) { showMessage ->
        Text("Your projects, media and exports stay on this device. No account, no cloud sync.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text("Storage used: calculating…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { vm.clearExportCache(context, showMessage) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Clear Export Cache") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !deleting,
        ) { Text(if (deleting) "Deleting…" else "Delete All Projects") }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete all projects?") },
                text = {
                    Text("This permanently deletes every project on this device. " +
                        "This cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            vm.deleteAllProjects(context, showMessage)
                        },
                    ) { Text("Delete all") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                },
            )
        }
    }
}

// ---------------- About & Diagnostics ----------------
@Composable
fun AboutScreen(nav: NavController) {
    ScreenScaffold(title = "About", onBack = { nav.popBackStack() }) { _ ->
        Text("Pramod AI Video Editor", style = MaterialTheme.typography.headlineMedium)
        Text("Version 1.0.0 (debug)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Text("• Templates loaded: see Template Gallery", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("• Database: Room v1", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("• Export engine: Media3 Transformer", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            "No advertisements in this personal build.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
