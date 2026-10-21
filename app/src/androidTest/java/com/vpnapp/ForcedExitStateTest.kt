package com.vpnapp

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.ConnectionState
import com.vpnapp.viewmodel.VpnViewModel
import org.junit.Assert.*
import org.junit.Test

class ForcedExitStateTest {
    @Test fun workerDeathCanCompleteDisconnectWithoutFinalStatusBroadcast() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        lateinit var viewModel: VpnViewModel
        instrumentation.runOnMainSync {
            viewModel = VpnViewModel(instrumentation.targetContext.applicationContext as Application)
            store.put("forced-exit-diagnostic", viewModel)
            viewModel.updateConnectionStatus("DISCONNECTING", null, null, closedWorkerPid = Int.MAX_VALUE)
        }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            while (viewModel.connectionInfo.value.state == ConnectionState.DISCONNECTING &&
                android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertEquals(ConnectionState.DISCONNECTED, viewModel.connectionInfo.value.state)
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }
}
