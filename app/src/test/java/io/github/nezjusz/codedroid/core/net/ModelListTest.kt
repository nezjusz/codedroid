package io.github.nezjusz.codedroid.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model list must be exactly what opencode offers - no more, no less.
 *
 * `enabled` is the host's own flag, already applied by `OpenCodeClient.models`; the picker adds
 * no filtering of its own, so anything extra here would be a model opencode would refuse.
 */
class ModelListTest {

    private val ten = """
        {"data":[
          {"id":"big-pickle","modelID":"big-pickle","providerID":"opencode","name":"Big Pickle","enabled":true,"status":"active","limit":{"context":200000,"output":32000}},
          {"id":"space-bunny-free","modelID":"space-bunny-free","providerID":"opencode","name":"Space Bunny Free","enabled":true,"status":"active","variant":"default","limit":{"context":1048576}},
          {"id":"retired-thing","modelID":"retired-thing","providerID":"opencode","name":"Retired","enabled":false,"status":"deprecated"}
        ]}
    """.trimIndent()

    @Test
    fun `only enabled models survive`() {
        val envelope = OpenCodeJson.decodeFromString<ModelEnvelope>(ten)
        val enabled = envelope.data.filter { it.enabled }
        assertEquals(2, enabled.size)
        assertTrue(enabled.none { it.id == "retired-thing" })
    }

    @Test
    fun `the ref sent to the host uses id, not modelID`() {
        // Verified against a live host: the route rejects a ref without `id`, and a ref carrying
        // `modelID` instead does not select anything. The list calls the same thing modelID.
        val model = OpenCodeJson.decodeFromString<ModelEnvelope>(ten).data.first { it.id == "space-bunny-free" }
        val ref = ModelRef(id = model.id, providerID = model.providerID, variant = model.variant)
        assertEquals("space-bunny-free", ref.id)
        assertEquals("opencode", ref.providerID)
        assertEquals("default", ref.variant)
    }

    @Test
    fun `a model with no variant sends none`() {
        val model = OpenCodeJson.decodeFromString<ModelEnvelope>(ten).data.first { it.id == "big-pickle" }
        assertEquals(null, ModelRef(id = model.id, providerID = model.providerID).variant)
    }

    @Test
    fun `the context ceiling decodes so the picker can show it`() {
        val model = OpenCodeJson.decodeFromString<ModelEnvelope>(ten).data.first { it.id == "space-bunny-free" }
        assertEquals(1_048_576L, model.limit?.context)
    }

    @Test
    fun `a model without a limit decodes rather than throwing`() {
        val json = """{"data":[{"id":"m","providerID":"p","name":"M","enabled":true}]}"""
        val model = OpenCodeJson.decodeFromString<ModelEnvelope>(json).data.single()
        assertEquals(null, model.limit)
    }

    @Test
    fun `models from different providers stay distinguishable`() {
        // Selection is keyed on provider plus id, so two ids that collide across providers must
        // not be confused for one another.
        val json = """{"data":[
          {"id":"shared","providerID":"opencode","enabled":true},
          {"id":"shared","providerID":"anthropic","enabled":true}
        ]}"""
        val models = OpenCodeJson.decodeFromString<ModelEnvelope>(json).data
        assertEquals(2, models.map { "${it.providerID}/${it.id}" }.distinct().size)
    }
}