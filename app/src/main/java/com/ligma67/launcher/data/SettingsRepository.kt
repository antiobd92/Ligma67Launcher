package com.ligma67.launcher.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

val LauncherJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

/**
 * Process-wide settings store. The launcher and the settings screen share this instance,
 * so every slider move is visible on the home screen instantly (no IPC, no reload).
 */
class SettingsRepository(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("ligma67_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()
    val value: LauncherSettings get() = _state.value
    private var saveJob: Job? = null

    private fun load(): LauncherSettings =
        prefs.getString(KEY, null)
            ?.let { runCatching { LauncherJson.decodeFromString(LauncherSettings.serializer(), it) }.getOrNull() }
            ?: LauncherSettings()

    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(250) // coalesce slider drags into one write
            write(next)
        }
    }

    fun replace(settings: LauncherSettings) = update { settings }

    fun flush() {
        saveJob?.cancel()
        write(_state.value)
    }

    private fun write(s: LauncherSettings) {
        prefs.edit().putString(KEY, LauncherJson.encodeToString(LauncherSettings.serializer(), s)).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}
