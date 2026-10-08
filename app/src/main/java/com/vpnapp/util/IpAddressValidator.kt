package com.vpnapp.util

object IpAddressValidator {
    fun isValid(value: String): Boolean {
        if (value.contains(':')) {
            if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return false
            return try { java.net.InetAddress.getByName(value) is java.net.Inet6Address } catch (_: Exception) { false }
        }
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }
}
