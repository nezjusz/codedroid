package io.github.nezjusz.codedroid.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("codedroid")

@Serializable
data class SavedServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val token: String = "",
    /** Directory last used for new sessions on this host. */
    val directory: String? = null,
    val notifyPermissions: Boolean = true,
    val notifyCompletion: Boolean = true,
    val keepAlive: Boolean = true,
) {
    val displayName: String get() = name.ifBlank { baseUrl }
    val host: String
        get() = baseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
}

/** Persists the set of paired hosts across launches. */
class ServerStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("servers")

    val servers: Flow<List<SavedServer>> = context.dataStore.data.map { prefs ->
        decode(prefs[key])
    }

    suspend fun add(server: SavedServer) = mutate { it + server }

    suspend fun update(server: SavedServer) = mutate { list ->
        list.map { if (it.id == server.id) server else it }
    }

    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun find(id: String): SavedServer? =
        servers.first().firstOrNull { it.id == id }

    private suspend fun mutate(block: (List<SavedServer>) -> List<SavedServer>) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[key])
            prefs[key] = json.encodeToString(block(current))
        }
    }

    private fun decode(raw: String?): List<SavedServer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SavedServer>>(raw) }.getOrDefault(emptyList())
    }
}
