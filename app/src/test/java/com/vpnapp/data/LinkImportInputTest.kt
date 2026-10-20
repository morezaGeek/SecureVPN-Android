package com.vpnapp.data

import org.junit.Assert.*
import org.junit.Test

class LinkImportInputTest {
    private val url = "https://example.invalid/sub/MyToken?key=a%2Fb+Cd=="
    @Test fun chatWrappersPreserveCaseAndEscapedCredentials() {
        assertEquals(url, LinkImportInput.normalize("[$url]($url)"))
        assertEquals(url, LinkImportInput.normalize("<$url>"))
        assertEquals(url, LinkImportInput.normalize("`$url`"))
    }
    @Test fun copiedDirectionMarkersAndBomDoNotHideTheScheme() {
        assertEquals(url, LinkImportInput.normalize("\uFEFF\u200E $url\u200F \n"))
        assertEquals("$url\nhttps://other.invalid", LinkImportInput.normalize("$url\nhttps://other.invalid"))
    }
}
