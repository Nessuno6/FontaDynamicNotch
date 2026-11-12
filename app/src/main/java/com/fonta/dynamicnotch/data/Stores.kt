package com.fonta.dynamicnotch.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    prettyPrint = true
    isLenient = true
}

/** Small helper: debounced, off-main JSON file writes. */
object JsonFiles {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()
    lateinit var folder: File
        private set

    fun init(context: Context) {
        folder = context.filesDir
        File(folder, "themes").mkdirs()
    }

    fun read(name: String): String? = runCatching { File(folder, name).takeIf { it.exists() }?.readText() }.getOrNull()

    fun writeDebounced(name: String, delayMs: Long = 500, content: () -> String) {
        pending.remove(name)?.let(main::removeCallbacks)
        val r = Runnable {
            pending.remove(name)
            val text = content()
            io.execute { runCatching { File(folder, name).apply { parentFile?.mkdirs() }.writeText(text) } }
        }
        pending[name] = r
        main.postDelayed(r, delayMs)
    }

    fun delete(name: String) {
        io.execute { runCatching { File(folder, name).delete() } }
    }
}

/** AppSettings + themes, applied live. */
object SettingsStore {
    var settings by mutableStateOf(AppSettings())
        private set
    var userThemes by mutableStateOf(listOf<Theme>())
        private set

    val themes: List<Theme> get() = listOf(Theme()) + userThemes
    val theme: Theme get() = userThemes.firstOrNull { it.id == settings.selectedThemeID } ?: Theme()

    fun init() {
        JsonFiles.read("settings.json")?.let { text ->
            runCatching { AppJson.decodeFromString<AppSettings>(text) }.getOrNull()?.let { settings = it }
        }
        userThemes = File(JsonFiles.folder, "themes").listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { f -> runCatching { AppJson.decodeFromString<Theme>(f.readText()) }.getOrNull() }
            ?.filter { !it.isBuiltIn }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings)
        if (next == settings) return
        settings = next
        JsonFiles.writeDebounced("settings.json") { AppJson.encodeToString(AppSettings.serializer(), settings) }
    }

    fun resetAll() {
        update { AppSettings() }
    }

    fun saveTheme(theme: Theme) {
        if (theme.isBuiltIn) return
        userThemes = (userThemes.filter { it.id != theme.id } + theme).sortedBy { it.name.lowercase() }
        JsonFiles.writeDebounced("themes/${theme.id}.json") { AppJson.encodeToString(Theme.serializer(), theme) }
    }

    fun deleteTheme(id: String) {
        userThemes = userThemes.filter { it.id != id }
        JsonFiles.delete("themes/$id.json")
        if (settings.selectedThemeID == id) update { it.copy(selectedThemeID = Theme.DEFAULT_ID) }
    }

    /** Copies a theme into a new editable one and selects it. */
    fun duplicate(source: Theme, name: String = source.name + " copy"): Theme {
        val copy = source.copy(id = Theme.newId(), name = name)
        saveTheme(copy)
        update { it.copy(selectedThemeID = copy.id) }
        return copy
    }

    fun importTheme(json: String): Theme? {
        val parsed = runCatching { AppJson.decodeFromString<Theme>(json) }.getOrNull() ?: return null
        val theme = parsed.copy(id = Theme.newId(), name = parsed.name.ifBlank { "Imported theme" })
        saveTheme(theme)
        update { it.copy(selectedThemeID = theme.id) }
        return theme
    }

    fun exportTheme(theme: Theme): String = AppJson.encodeToString(Theme.serializer(), theme)
}
