package com.vpnapp.vpn.singbox

internal data class PhysicalInterfaceState(val name: String, val index: Int, val networkHandle: Long, val metered: Boolean)

/** Ignore capability/link notifications that do not change the selected physical interface. */
internal class InterfaceUpdateFilter {
    private var previous: PhysicalInterfaceState? = null
    @Synchronized fun changed(next: PhysicalInterfaceState): Boolean {
        if (next == previous) return false
        previous = next
        return true
    }
}
