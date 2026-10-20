package com.vpnapp.data

data class SubscriptionImportState(
    val loading: Boolean = false,
    val imported: Int? = null,
    val error: String? = null
)

class SubscriptionImportException(message: String) : Exception(message)
