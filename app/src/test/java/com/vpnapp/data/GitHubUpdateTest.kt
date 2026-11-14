package com.vpnapp.data

import com.vpnapp.update.GitHubUpdate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GitHubUpdateTest {
    private val root = "https://github.com/morezaGeek/SecureVPN-Android/releases/download/v1.1.10/"
    private fun asset(name: String, url: String = root + name) = JSONObject()
        .put("name", name).put("browser_download_url", url).put("state", "uploaded")
        .put("size", 100).put("digest", "sha256:" + "a".repeat(64))
    private fun release(vararg assets: JSONObject) = JSONObject().put("tag_name", "v1.1.10")
        .put("body", "Release notes").put("assets", JSONArray(assets.toList()))

    @Test fun picksSupportedAbiAndComparesVersionsNumerically() {
        val abi = "SecureVPN-1.1.10-arm64-v8a.apk"
        val result = GitHubUpdate.parseRelease(release(asset("SecureVPN-1.1.10.apk"), asset(abi)), "1.1.9", listOf("arm64-v8a"))
        assertEquals(root + abi, result?.url)
        assertEquals("Release notes", result?.notes)
        assertNull(GitHubUpdate.parseRelease(release(asset(abi)), "1.1.10", listOf("arm64-v8a")))
    }

    @Test fun rejectsUntrustedDownloadAndFallsBackOnlyToVerifiedUniversal() {
        val abi = asset("SecureVPN-1.1.10-arm64-v8a.apk", "https://example.org/file.apk")
        val universal = "SecureVPN-1.1.10.apk"
        assertEquals(root + universal, GitHubUpdate.parseRelease(release(abi, asset(universal)), "1.1.8", listOf("arm64-v8a"))?.url)
        assertThrows(IOException::class.java) {
            GitHubUpdate.parseRelease(release(abi), "1.1.8", listOf("arm64-v8a"))
        }
    }
}
