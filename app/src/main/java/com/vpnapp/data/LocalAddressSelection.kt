package com.vpnapp.data

data class PhysicalAddressCandidate(
    val handle: Long,
    val addresses: List<String>,
    val internet: Boolean,
    val vpn: Boolean = false,
    val validated: Boolean = false,
    val lan: Boolean = false
)

/** Pick the Internet uplink, not IMS/MMS or every interface that happens to have an address. */
object LocalAddressSelection {
    fun primary(candidates: List<PhysicalAddressCandidate>, active: Long?, underlying: List<Long>): String? {
        val eligible = candidates.filter { !it.vpn && it.internet && it.addresses.isNotEmpty() }
        val selected = eligible.firstOrNull { it.handle == active }
            ?: underlying.firstNotNullOfOrNull { handle -> eligible.firstOrNull { it.handle == handle } }
            ?: eligible.firstOrNull { it.validated && it.lan }
            ?: eligible.firstOrNull { it.validated }
            ?: eligible.firstOrNull { it.lan }
            ?: eligible.firstOrNull()
        return selected?.addresses?.firstOrNull()
    }
}
