package com.vpnapp

import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.*
import com.vpnapp.vpn.singbox.SingBoxDelayTester
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in diagnostics: the user fixture is pushed into debug cache, never shipped in the APK. */
class PrivateSubscriptionDiagnosticsTest {
    @Test fun importedProfilesPassRealAndroidCoreTrafficChecks() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.cacheDir,"subscription-diagnostics.private.txt")
        assumeTrue("Requires a local private fixture",input.isFile)
        try {
            val decoded=String(Base64.decode(input.readText().trim(),Base64.DEFAULT),Charsets.UTF_8)
            val profiles=decoded.lines().mapNotNull(V2RayLinkParser::parseLink)
            assertEquals(28,profiles.size)
            val chosen=profiles.withIndex().filter { it.index in listOf(0,1,3,5,7,9,12,27) }.map { it.value }
            val results=JSONArray()
            for (p in chosen) {
                val applied=XHttpModePolicy.apply(p,true)
                val delay=SingBoxDelayTester.testProfileDelay(context,applied)
                results.put(JSONObject().put("name",p.name).put("latency",delay).put("mode",applied.v2rayType))
            }
            File(context.cacheDir,"subscription-diagnostics-results.json").writeText(results.toString())
            assertTrue("ECH XHTTP must pass real core traffic check",results.getJSONObject(0).getInt("latency") > 0)
        } finally { input.delete() }
    }
}
