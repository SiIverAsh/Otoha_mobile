package io.github.otoha.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MirrorSettingsTest {
    @Test fun `blank selects automatic mirrors and a valid custom base is normalized`() {
        assertEquals("", MirrorSettings.normalize("  "))
        assertEquals("https://api.example.org/proxy", MirrorSettings.normalize(" HTTPS://API.EXAMPLE.ORG/proxy/ "))
        assertEquals("https://api.example.org:8443", MirrorSettings.normalize("https://api.example.org:8443/"))
    }

    @Test fun `rejects credentials query fragment non https and invalid hosts or ports`() {
        listOf("http://example.org", "https://user:pass@example.org", "https://@example.org",
            "https://example.org?token=x", "https://example.org?", "https://example.org#section", "https://example.org#",
            "https:///api", "example.org", "https://example.org:70000", "https://example.org:0",
            "https://example.org/a b", "https://example.org\n/").forEach { value ->
            try { MirrorSettings.normalize(value); fail("Accepted invalid API base: $value") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
}
