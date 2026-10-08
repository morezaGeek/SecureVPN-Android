package com.vpnapp.vpn.sstp

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** MS-SSTP 2.2.7 and 3.2.5.2. PAP uses a zero HLAK because it produces no MPPE keys. */
internal object SstpCryptoBinding {
    data class Request(val hashProtocol: Int, val nonce: ByteArray)

    fun parseRequest(controlPayload: ByteArray): Request {
        require(controlPayload.size >= 4) { "Truncated SSTP Connect Ack" }
        val count = ((controlPayload[2].toInt() and 255) shl 8) or (controlPayload[3].toInt() and 255)
        var offset = 4
        repeat(count) {
            require(offset + 4 <= controlPayload.size) { "Truncated SSTP attribute" }
            val length = ((controlPayload[offset + 2].toInt() and 15) shl 8) or (controlPayload[offset + 3].toInt() and 255)
            require(length >= 4 && offset + length <= controlPayload.size) { "Invalid SSTP attribute length" }
            if (controlPayload[offset + 1].toInt() == 4) {
                require(length == 40) { "Invalid crypto binding request" }
                val supported = controlPayload[offset + 7].toInt()
                val hash = if (supported and 2 != 0) 2 else if (supported and 1 != 0) 1 else error("Unsupported SSTP hash protocol")
                return Request(hash, controlPayload.copyOfRange(offset + 8, offset + 40))
            }
            offset += length
        }
        error("Server did not provide SSTP crypto binding request")
    }

    fun connected(request: Request, certificate: ByteArray): ByteArray = withCertificateHash(
        request, MessageDigest.getInstance(if (request.hashProtocol == 2) "SHA-256" else "SHA-1").digest(certificate))

    fun withCertificateHash(request: Request, certHash: ByteArray, hlak: ByteArray = ByteArray(32)): ByteArray {
        require(request.hashProtocol in 1..2 && request.nonce.size == 32 && hlak.size == 32)
        val size = if (request.hashProtocol == 2) 32 else 20
        require(certHash.size == size)
        val algorithm = if (size == 32) "HmacSHA256" else "HmacSHA1"
        fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance(algorithm).run {
            init(SecretKeySpec(key, algorithm)); doFinal(data)
        }
        val seed = "SSTP inner method derived CMK".toByteArray(Charsets.US_ASCII)
        val cmk = hmac(hlak, seed + byteArrayOf(size.toByte(), 0, 1))
        val value = ByteArray(100)
        value[3] = request.hashProtocol.toByte()
        request.nonce.copyInto(value, 4)
        certHash.copyInto(value, 36)
        val packet = SstpControlMessage(SstpMessageType.SSTP_MSG_CALL_CONNECTED,
            listOf(SstpAttribute(3, value))).toBytes()
        hmac(cmk, packet).copyInto(packet, 80)
        return packet
    }
}
