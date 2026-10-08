package com.vpnapp.util

import org.junit.Assert.*
import org.junit.Test

class IpAddressValidatorTest {
    @Test fun acceptsOnlyLiteralAddresses() {
        listOf("8.8.8.8", "1.2.3.4", "2001:db8::1", "::1").forEach { assertTrue(it, IpAddressValidator.isValid(it)) }
        listOf("", "Checking...", "example.com", "<html>1.2.3.4</html>", "999.1.2.3", "1.2.3", "abc:def", "1.2.3.4:80").forEach {
            assertFalse(it, IpAddressValidator.isValid(it))
        }
    }
}
