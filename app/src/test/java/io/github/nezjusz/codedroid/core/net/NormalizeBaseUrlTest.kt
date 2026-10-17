package io.github.nezjusz.codedroid.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Typing a bare host used to produce `http://192.168.1.5`, which is port 80 - a connection
 * refused against a server listening on 4096, with nothing in the URL to hint at the mistake.
 */
class NormalizeBaseUrlTest {

    private fun norm(raw: String) = OpenCodeClient.normalizeBaseUrl(raw)

    @Test
    fun `a bare host gains the scheme and the default port`() {
        assertEquals("http://192.168.1.5:4096", norm("192.168.1.5"))
    }

    @Test
    fun `a hostname gains the default port too`() {
        assertEquals("http://opencode.local:4096", norm("opencode.local"))
    }

    @Test
    fun `an explicit port is left alone`() {
        assertEquals("http://192.168.1.5:4096", norm("192.168.1.5:4096"))
    }

    @Test
    fun `a non-default explicit port is respected`() {
        assertEquals("http://127.0.0.1:8080", norm("127.0.0.1:8080"))
    }

    @Test
    fun `an existing scheme is preserved`() {
        assertEquals("https://code.example.com", norm("https://code.example.com"))
    }

    @Test
    fun `an explicit port on https is preserved`() {
        assertEquals("https://code.example.com:8443", norm("https://code.example.com:8443"))
    }

    @Test
    fun `a trailing slash is trimmed`() {
        assertEquals("http://192.168.1.5:4096", norm("192.168.1.5:4096/"))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals("http://192.168.1.5:4096", norm("  192.168.1.5  "))
    }

    @Test
    fun `empty input is left alone`() {
        assertEquals("", norm(""))
        assertEquals("", norm("   "))
    }

    @Test
    fun `a path is not given a port`() {
        // Host plus path already identifies something; adding :4096 would corrupt the path.
        assertEquals("http://example.com/api", norm("example.com/api"))
    }
}