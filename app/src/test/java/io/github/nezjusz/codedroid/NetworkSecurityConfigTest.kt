package io.github.nezjusz.codedroid

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the network security policy.
 *
 * This exists because the policy was wrong once: `<domain>10.0.0.0</domain>` reads like a CIDR
 * range but Android matches it as an exact host, so every host except 10.0.0.0 itself was denied
 * cleartext. Because the paired server's address is unknown until the user types it, cleartext
 * must be permitted at the base config.
 */
class NetworkSecurityConfigTest {

    private val configFile = File("src/main/res/xml/network_security_config.xml")
    private val manifestFile = File("src/main/AndroidManifest.xml")

    private fun config() = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(configFile)

    @Test
    fun `cleartext is permitted at the base config`() {
        val base = config().getElementsByTagName("base-config").item(0)
        assertNotNull("network security config declares no base-config", base)
        assertEquals(
            "self-hosted opencode hosts are reached over plain http",
            "true",
            base.attributes.getNamedItem("cleartextTrafficPermitted")?.nodeValue,
        )
    }

    @Test
    fun `no domain entries claim to cover an IP range`() {
        val domains = config().getElementsByTagName("domain")
        val offenders = (0 until domains.length)
            .map { domains.item(it).attributes.getNamedItem("includeSubdomains")?.nodeValue }
            .mapNotNull { it }
            .filter { entry ->
                // Any literal IPv4 entry is a trap: it matches exactly one host, never a subnet.
                Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(entry)
            }
        assertTrue("remove literal-IP domain entries; they cannot express a range: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the manifest actually applies the config`() {
        val manifest = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(manifestFile)

        val application = manifest.getElementsByTagName("application").item(0)
        val configured = application.attributes.getNamedItem("android:networkSecurityConfig")?.nodeValue
        assertEquals("@xml/network_security_config", configured)
    }

    @Test
    fun `system trust anchors remain, so https is still validated`() {
        val anchors = config().getElementsByTagName("certificates")
        val sources = (0 until anchors.length).map { anchors.item(it).attributes.getNamedItem("src")?.nodeValue }
        assertTrue("user-installed CAs must not be trusted: $sources", "user" !in sources)
        assertTrue("system anchors are missing", sources.isNotEmpty())
    }
}
