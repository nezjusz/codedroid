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

    /**
     * Adds a host, or replaces the one already stored under the same id.
     *
     * Upsert rather than append, deliberately. The hosts list is a `LazyColumn` keyed on id, so a
     * duplicate id is not a cosmetic problem: it throws during composition, and because the list
     * is persisted that throw repeats on every launch, leaving clearing app data as the only way
     * out. Making the store incapable of producing a duplicate is cheaper than auditing every
     * caller for it.
     */
    suspend fun add(server: SavedServer) = mutate { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it }
        else list + server
    }

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

    /**
     * Reads the stored list, collapsing any duplicate ids.
     *
     * The last entry for an id wins, because a bad write appends the newer copy after the one it
     * replaced. This also repairs installs that are already corrupt: without it, a phone that
     * stored a duplicate once would keep throwing on every launch even after the fix, and the
     * only way out would still be clearing app data and re-pairing.
     */
    private fun decode(raw: String?): List<SavedServer> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { json.decodeFromString<List<SavedServer>>(raw) }
            .getOrDefault(emptyList())
        return parsed.foldRight(mutableListOf<SavedServer>()) { server, kept ->
            if (kept.none { it.id == server.id }) kept += server
            kept
        }
    }
}
