package com.vpnapp.data

import java.util.Locale

enum class ProfileSort(val label: String) {
    DEFAULT("Default server order"), NAME("Name · A–Z"), PING("Ping · low to high");

    fun apply(profiles: List<VpnProfile>): List<VpnProfile> = when (this) {
        DEFAULT -> profiles
        NAME -> profiles.sortedBy { it.name.lowercase(Locale.ROOT) }
        PING -> profiles.sortedBy { it.latency?.takeIf { value -> value >= 0 } ?: Int.MAX_VALUE }
    }

    companion object {
        const val PREFERENCE_KEY = "profile_sort"
        fun stored(value: String?): ProfileSort = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
