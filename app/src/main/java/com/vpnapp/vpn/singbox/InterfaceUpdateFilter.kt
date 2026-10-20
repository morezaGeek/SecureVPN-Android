package com.vpnapp.vpn.singbox

internal data class PhysicalInterfaceState(val name: String, val index: Int, val networkHandle: Long,
    val metered: Boolean, val addresses: String = "")

/** Ignore capability/link notifications that do not change the selected physical interface. */
internal class InterfaceUpdateFilter {
    private var previous: PhysicalInterfaceState? = null
    @Synchronized fun needsReset(next: PhysicalInterfaceState): Boolean = previous?.let {
        it.index > 0 && next.index > 0 && it.name == next.name && it.index == next.index &&
            (it.networkHandle != next.networkHandle || it.addresses != next.addresses)
    } ?: false
    @Synchronized fun changed(next: PhysicalInterfaceState): Boolean {
        if (next == previous) return false
        previous = next
        return true
    }
}
