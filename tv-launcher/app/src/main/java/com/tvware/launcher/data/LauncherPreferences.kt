package com.tvware.launcher.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_prefs")

enum class BackgroundMode {
    BLACK,
    GRADIENT,
    CUSTOM_FILE,
}

@Immutable
data class LauncherSettings(
    val pinnedPackages: List<String> = emptyList(),
    val gridOrder: List<String> = emptyList(),
    val hiddenPackages: Set<String> = emptySet(),
    val gridColumns: Int = 5,
    val backgroundMode: BackgroundMode = BackgroundMode.GRADIENT,
    val customBackgroundPath: String? = null,
    val initializedDefaults: Boolean = false,
)

class LauncherPreferences(private val context: Context) {

    val settingsFlow: Flow<LauncherSettings> = context.dataStore.data.map { prefs ->
        LauncherSettings(
            pinnedPackages = decodeOrder(prefs[KEY_PINNED_ORDER]),
            gridOrder = decodeOrder(prefs[KEY_GRID_ORDER]),
            hiddenPackages = prefs[KEY_HIDDEN_PACKAGES] ?: emptySet(),
            gridColumns = (prefs[KEY_GRID_COLUMNS] ?: DEFAULT_COLUMNS).coerceIn(4, 6),
            backgroundMode = prefs[KEY_BG_MODE]
                ?.let { runCatching { BackgroundMode.valueOf(it) }.getOrNull() }
                ?: BackgroundMode.GRADIENT,
            customBackgroundPath = prefs[KEY_CUSTOM_BG_PATH]?.takeIf { it.isNotBlank() },
            initializedDefaults = prefs[KEY_INITIALIZED_DEFAULTS] ?: false,
        )
    }

    suspend fun ensureDefaultPins(installedPackages: Set<String>) {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_INITIALIZED_DEFAULTS] == true) return@edit
            val defaults = buildList {
                DEFAULT_PIN_GROUPS.forEach { candidates ->
                    candidates.firstOrNull { it in installedPackages }?.let(::add)
                }
            }
            prefs[KEY_PINNED_ORDER] = encodeOrder(defaults)
            prefs[KEY_INITIALIZED_DEFAULTS] = true
        }
    }

    suspend fun togglePin(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = decodeOrder(prefs[KEY_PINNED_ORDER]).toMutableList()
            if (packageName in current) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            prefs[KEY_PINNED_ORDER] = encodeOrder(current)
            prefs[KEY_INITIALIZED_DEFAULTS] = true
        }
    }

    suspend fun movePinned(packageName: String, delta: Int) {
        context.dataStore.edit { prefs ->
            movePackage(decodeOrder(prefs[KEY_PINNED_ORDER]), packageName, delta)?.let {
                prefs[KEY_PINNED_ORDER] = encodeOrder(it)
            }
        }
    }

    suspend fun moveInGrid(packageName: String, visibleGridPackages: List<String>, delta: Int) {
        context.dataStore.edit { prefs ->
            movePackage(visibleGridPackages, packageName, delta)?.let {
                prefs[KEY_GRID_ORDER] = encodeOrder(it)
            }
        }
    }

    suspend fun hideApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val hidden = (prefs[KEY_HIDDEN_PACKAGES] ?: emptySet()) + packageName
            prefs[KEY_HIDDEN_PACKAGES] = hidden
            val pinned = decodeOrder(prefs[KEY_PINNED_ORDER]).filterNot { it == packageName }
            prefs[KEY_PINNED_ORDER] = encodeOrder(pinned)
        }
    }

    suspend fun unhideApp(packageName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HIDDEN_PACKAGES] = (prefs[KEY_HIDDEN_PACKAGES] ?: emptySet()) - packageName
        }
    }

    suspend fun setGridColumns(columns: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GRID_COLUMNS] = columns.coerceIn(4, 6)
        }
    }

    suspend fun setBackgroundMode(mode: BackgroundMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BG_MODE] = mode.name
        }
    }

    suspend fun setCustomBackgroundPath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path.isNullOrBlank()) {
                prefs.remove(KEY_CUSTOM_BG_PATH)
                if (prefs[KEY_BG_MODE] == BackgroundMode.CUSTOM_FILE.name) {
                    prefs[KEY_BG_MODE] = BackgroundMode.GRADIENT.name
                }
            } else {
                prefs[KEY_CUSTOM_BG_PATH] = path
                prefs[KEY_BG_MODE] = BackgroundMode.CUSTOM_FILE.name
            }
        }
    }

    private fun decodeOrder(raw: String?): List<String> =
        raw?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    private fun encodeOrder(list: List<String>): String =
        list.distinct().joinToString(",")

    private companion object {
        const val DEFAULT_COLUMNS = 5
        val KEY_INITIALIZED_DEFAULTS = booleanPreferencesKey("initialized_defaults")
        val KEY_PINNED_ORDER = stringPreferencesKey("pinned_order")
        val KEY_GRID_ORDER = stringPreferencesKey("grid_order")
        val KEY_HIDDEN_PACKAGES = stringSetPreferencesKey("hidden_packages")
        val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        val KEY_BG_MODE = stringPreferencesKey("background_mode")
        val KEY_CUSTOM_BG_PATH = stringPreferencesKey("custom_bg_path")

        val DEFAULT_PIN_GROUPS = listOf(
            listOf(
                "com.google.android.youtube.tv",
                "com.google.android.youtube",
                "com.liskovsoft.smarttubetv.beta",
                "com.liskovsoft.smarttubetv",
            ),
            listOf(
                "com.spotify.tv.android",
                "com.spotify.music",
            ),
            listOf(
                "su.happ.proxyutility",
                "com.happproxy",
            ),
        )
    }
}
