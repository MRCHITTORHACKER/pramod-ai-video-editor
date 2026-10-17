package com.pramod.videoeditor.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.pramod.videoeditor.coremodel.TemplateConfig
import com.pramod.videoeditor.featuretemplates.TemplateParser
import com.pramod.videoeditor.featuretemplates.TemplateValidator
import com.pramod.videoeditor.ui.ScreenScaffold
import com.pramod.videoeditor.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads the 64 template JSONs from merged library assets. */
class TemplateStore(private val context: Context) {
    suspend fun loadAll(): List<TemplateConfig> = withContext(Dispatchers.IO) {
        val am = context.assets
        val names = try { am.list("templates") ?: emptyArray() } catch (_: Exception) { emptyArray() }
        names.filter { it.endsWith(".json") }.mapNotNull { name ->
            try {
                val text = am.open("templates/$name").bufferedReader().readText()
                when (val r = TemplateParser.parse(text)) {
                    is TemplateParser.ParseResult.Ok ->
                        r.config.takeIf { TemplateValidator.isUsable(it) }
                    is TemplateParser.ParseResult.Err -> null
                }
            } catch (_: Exception) { null }
        }.sortedBy { it.id }
    }
}

class TemplateGalleryViewModel(context: Context) : ViewModel() {
    private val store = TemplateStore(context.applicationContext)
    private val _templates = MutableStateFlow<List<TemplateConfig>>(emptyList())
    val templates: StateFlow<List<TemplateConfig>> = _templates
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _category = MutableStateFlow("all")
    val category: StateFlow<String> = _category

    init {
        viewModelScope.launch {
            _templates.value = store.loadAll()
            _loading.value = false
        }
    }

    fun setCategory(c: String) { _category.value = c }

    fun visible(): List<TemplateConfig> {
        val c = _category.value
        return if (c == "all") _templates.value else _templates.value.filter { it.category == c }
    }
}

@Composable
fun TemplateGalleryScreen(nav: NavController) {
    val context = LocalContext.current
    val vm = remember { TemplateGalleryViewModel(context) }
    val loading by vm.loading.collectAsState()
    val category by vm.category.collectAsState()
    // re-collect templates to refresh on category change
    val templates by vm.templates.collectAsState()
    val visible = remember(templates, category) { vm.visible() }
    val categories = listOf("all", "viral", "cinematic", "education", "motivation", "vlog", "social", "gaming", "business")

    ScreenScaffold(title = "Template Gallery", onBack = { nav.popBackStack() }) { _ ->
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            Text("${templates.size} templates", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible) { t ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { nav.navigate(Routes.templateDetail(t.id)) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium)
                            Text(t.description, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${t.category} • v${t.version}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TemplateDetailScreen(nav: NavController, templateId: String) {
    val context = LocalContext.current
    val vm = remember { TemplateGalleryViewModel(context) }
    val templates by vm.templates.collectAsState()
    val loading by vm.loading.collectAsState()
    val t = remember(templates) { templates.find { it.id == templateId } }

    ScreenScaffold(title = "Template", onBack = { nav.popBackStack() }) { _ ->
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (t == null) {
            Text("Template not found: $templateId", color = MaterialTheme.colorScheme.error)
        } else {
            Text(t.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(t.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Aspect ratios: ${t.aspectRatios.joinToString { it.label }}")
            Text("Duration: ${t.minDurationSec}s – ${t.maxDurationSec}s")
            Text("Music: ${t.musicBehavior.mood} (ducks under speech: ${t.musicBehavior.duckUnderSpeech})")
            Text("SFX rules: ${t.sfxRules.size} • VFX: ${t.vfx.size}")
            Text("Version: v${t.version}")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { nav.navigate(Routes.AI_SETUP) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use This Template") }
        }
    }
}
