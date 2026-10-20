package com.vpnapp.data

data class LatencyTestState(
    val pendingIds: Set<String> = emptySet(),
    val activeIds: Set<String> = emptySet(),
    val total: Int = 0
) {
    companion object {
        const val MAX_CONCURRENT_PROBES = 8
    }
    val isRunning: Boolean get() = pendingIds.isNotEmpty()
    val completed: Int get() = total - pendingIds.size
}
