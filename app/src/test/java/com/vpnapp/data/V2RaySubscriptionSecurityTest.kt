package com.vpnapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL

class V2RaySubscriptionSecurityTest {
    @Test fun acceptsHttpsSubscriptionUrl() {
        assertEquals("https", V2RayLinkParser.validateSecureSubscriptionUrl("https://example.com/sub?token=abc").protocol)
    }

    @Test fun rejectsHttpSubscriptionUrl() {
        assertRejected { V2RayLinkParser.validateSecureSubscriptionUrl("http://example.com/sub?token=abc") }
    }

    @Test fun allowsHttpsRedirectButRejectsDowngrade() {
        val base = URL("https://example.com/sub/path")
        assertEquals("https://example.com/next", V2RayLinkParser.resolveSecureSubscriptionRedirect(base, "/next").toString())
        assertRejected { V2RayLinkParser.resolveSecureSubscriptionRedirect(base, "http://example.com/next") }
    }

    private inline fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected insecure subscription URL to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
