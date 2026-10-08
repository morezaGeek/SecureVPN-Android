package com.vpnapp.vpn.sstp

import org.junit.Assert.*
import org.junit.Test

class SstpProtocolTest {
    private fun hex(text: String) = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun connectRequestMatchesMicrosoftWireExample() {
        val request = SstpControlMessage(SstpMessageType.SSTP_MSG_CALL_CONNECT_REQUEST,
            listOf(SstpAttribute(1, byteArrayOf(0, 1)))).toBytes()
        assertArrayEquals(hex("1001000e00010001000100060001"), request)
        val parsed = parseSstpPacket(request)
        assertTrue(parsed.first)
        assertEquals(SstpMessageType.SSTP_MSG_CALL_CONNECT_REQUEST, parsed.second)
        assertFalse(parseSstpPacket(createDataPacket(byteArrayOf(0, 0x21))).first)
    }

    @Test fun sha256BindingMatchesMicrosoftKnownVector() {
        val nonce = hex("412B489AEBD7ECC7D08966F26BE7CD72B231A0E9210D7C91B308862B0344C435")
        val cert = hex("7993EF314C493DACE9F02D60E7E61C84B6690AAFE9D7AEEA92CBBE8AD599422D")
        val hlak = hex("2A1BB40D55AB0F5EF32F06F2B3CC73C48FD3FAC41D7A1315A19228D9024CA164")
        val packet = SstpCryptoBinding.withCertificateHash(SstpCryptoBinding.Request(2, nonce), cert, hlak)
        assertEquals(112, packet.size)
        assertArrayEquals(hex("10010070000400010003006800000002"), packet.copyOfRange(0, 16))
        assertArrayEquals(hex("52A68EFD8CFFBF52770B8F0FE8EC73716583AF6D611EB6D179B3B20840985449"), packet.copyOfRange(80, 112))
    }

    @Test fun sha1BindingMatchesMicrosoftKnownVectorWithZeroPadding() {
        val nonce = hex("0F1A2D58D4A3E3000FAD3CE4906E07B707AA9E441CCEAC5CBD7B2CC1C9D86CDF")
        val cert = hex("5826B629BDA59B8E6FD8DCD2622FD34C534805A5")
        val hlak = hex("4B3128F43925D9006EEFB1C4E86515A1D88E56BAB3CA2BDF0373B7F5A8A13B19")
        val packet = SstpCryptoBinding.withCertificateHash(SstpCryptoBinding.Request(1, nonce), cert, hlak)
        assertArrayEquals(hex("69915DD583D8062FEF16F61DB2F03290EC27CB6C"), packet.copyOfRange(80, 100))
        assertArrayEquals(ByteArray(12), packet.copyOfRange(100, 112))
        assertArrayEquals(ByteArray(12), packet.copyOfRange(68, 80))
    }

    @Test fun bindingRequestPrefersSha256AndRetainsNonce() {
        val nonce = ByteArray(32) { it.toByte() }
        val payload = hex("000200010004002800000003") + nonce
        val parsed = SstpCryptoBinding.parseRequest(payload)
        assertEquals(2, parsed.hashProtocol)
        assertArrayEquals(nonce, parsed.nonce)
    }

    @Test fun malformedHeadersCannotAllocateNegativePayloads() {
        assertEquals(0, parseSstpPacket(hex("10010000")).third.size)
        assertEquals(0, parseSstpPacket(hex("100100ff")).third.size)
    }
}
