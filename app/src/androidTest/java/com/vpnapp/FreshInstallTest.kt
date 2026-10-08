package com.vpnapp

import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.ProfileRepository
import com.vpnapp.ui.theme.*
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class FreshInstallTest {
    @Test fun isolatedNewInstallationHasNoPreparedProfilesOrSubscriptions() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = target.createDeviceProtectedStorageContext()
        val preferences = isolated.getSharedPreferences("vpn_profiles", 0)
        assertTrue("Test context must be unused", preferences.all.isEmpty())
        val repository = ProfileRepository(isolated)
        assertTrue(repository.loadProfiles().isEmpty())
        assertTrue(repository.loadSubscriptions().isEmpty())
    }

    @Test fun darkSurfacesAreNeutralAndScreenBackgroundsAreSolid() {
        for (color in listOf(DarkBackground, DarkSurface, DarkSurfaceVariant, DarkCard)) {
            val rgb = color.toArgb()
            assertEquals(android.graphics.Color.red(rgb), android.graphics.Color.green(rgb))
            assertEquals(android.graphics.Color.green(rgb), android.graphics.Color.blue(rgb))
        }
        assertEquals(GradientStart, GradientMiddle)
        assertEquals(GradientMiddle, GradientEnd)
        assertEquals(LightGradientStart, LightGradientMiddle)
        assertEquals(LightGradientMiddle, LightGradientEnd)
    }
}
