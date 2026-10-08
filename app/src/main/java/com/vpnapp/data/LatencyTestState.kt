package com.vpnapp.data

data class LatencyTestState(
    val pendingIds: Set<String> = emptySet(),
    val activeId: String? = null,
    val total: Int = 0
) {
    val isRunning: Boolean get() = pendingIds.isNotEmpty()
    val completed: Int get() = total - pendingIds.size
}
