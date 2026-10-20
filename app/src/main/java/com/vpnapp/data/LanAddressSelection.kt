package com.vpnapp.data

data class LanInterfaceCandidate(
    val name: String,
    val up: Boolean,
    val loopback: Boolean,
    val addresses: List<String>
)

/** LAN proxy endpoints only; this does not choose the device's Internet uplink. */
object LanAddressSelection {
    private val lanInterface = Regex(
        "^(?:(?:ap|swlan|wlan|eth|rndis|usb|bnep|bridge)[a-z0-9_.:-]*|br(?:[0-9]|[-_])[a-z0-9_.:-]*)$",
        RegexOption.IGNORE_CASE
    )

    fun addresses(
        knownLanAddresses: List<String>,
        interfaces: List<LanInterfaceCandidate>,
        excludedNames: Set<String> = emptySet(),
        excludedAddresses: Set<String> = emptySet()
    ): List<String> {
        val blockedNames = excludedNames.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        fun eligible(address: String) = isPrivateIpv4(address) && address !in excludedAddresses
        val discovered = interfaces.asSequence()
            .filter { it.up && !it.loopback && it.name.lowercase(java.util.Locale.ROOT) !in blockedNames }
            .filter { lanInterface.matches(it.name) }
            .flatMap { it.addresses.asSequence() }
        return (knownLanAddresses.asSequence() + discovered).filter(::eligible).distinct().toList()
    }

    private fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { digit -> digit !in '0'..'9' } }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 192 && octets[1] == 168
    }
}
