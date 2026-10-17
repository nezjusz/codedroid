package io.github.nezjusz.codedroid.core.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host list is a `LazyColumn` keyed on id, so a duplicate id is fatal: Compose throws during
 * composition, and because the list is persisted the throw repeats on every launch until the data
 * is cleared. That is exactly what editing a host used to do - it appended a second entry carrying
 * the original's id.
 *
 * These run without Android: the two behaviours worth pinning are the pure fold and the JSON round
 * trip, so the decoder is exercised against the same encoder that writes the file.
 */
class ServerListIntegrityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun server(id: String, name: String) =
        SavedServer(id = id, name = name, baseUrl = "http://$id:4096", token = "t")

    /** Same rule as the store's decode: last entry for an id wins. */
    private fun dedupe(list: List<SavedServer>): List<SavedServer> {
        val kept = mutableListOf<SavedServer>()
        for (server in list.asReversed()) {
            if (kept.none { it.id == server.id }) kept += server
        }
        return kept
    }

    @Test
    fun `a duplicate id collapses to the newest entry`() {
        val stored = listOf(server("a", "original"), server("a", "edited"))
        val healed = dedupe(stored)
        assertEquals(1, healed.size)
        assertEquals("edited", healed.single().name)
    }

    @Test
    fun `distinct ids are untouched`() {
        val stored = listOf(server("a", "one"), server("b", "two"))
        assertEquals(2, dedupe(stored).size)
    }

    @Test
    fun `a duplicate in the middle still collapses to the newest`() {
        val stored = listOf(server("a", "one"), server("b", "two"), server("a", "three"))
        val healed = dedupe(stored)
        assertEquals(listOf("a", "b"), healed.map { it.id })
        assertEquals("three", healed.first { it.id == "a" }.name)
    }

    @Test
    fun `an empty list stays empty`() {
        assertTrue(dedupe(emptyList()).isEmpty())
    }

    @Test
    fun `a single entry survives untouched`() {
        assertEquals(1, dedupe(listOf(server("a", "only"))).size)
    }

    @Test
    fun `the corruption survives a JSON round trip, which is why decode has to heal it`() {
        // Documents the original bug end to end: writing the list as-is is what persisted the
        // duplicate that then threw on every launch.
        val corrupt = json.encodeToString(
            listOf(server("ac351501-ca61-404c-bcba-c02e5f64f1e4", "original"),
                   server("ac351501-ca61-404c-bcba-c02e5f64f1e4", "edited"))
        )
        val readBack = json.decodeFromString<List<SavedServer>>(corrupt)
        assertEquals("precondition: the raw store really does hold two", 2, readBack.size)
        assertEquals(1, dedupe(readBack).size)
    }
}