package com.vpnapp.vpn.sstp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SSTP Protocol Constants and Data Units
 * Based on MS-SSTP specification
 */

// SSTP Control Packet Types
object SstpMessageType {
    const val SSTP_MSG_CALL_CONNECT_REQUEST: Short = 0x0001
    const val SSTP_MSG_CALL_CONNECT_ACK: Short = 0x0002
    const val SSTP_MSG_CALL_CONNECT_NAK: Short = 0x0003
    const val SSTP_MSG_CALL_CONNECTED: Short = 0x0004
    const val SSTP_MSG_CALL_ABORT: Short = 0x0005
    const val SSTP_MSG_CALL_DISCONNECT: Short = 0x0006
    const val SSTP_MSG_CALL_DISCONNECT_ACK: Short = 0x0007
    const val SSTP_MSG_ECHO_REQUEST: Short = 0x0008
    const val SSTP_MSG_ECHO_RESPONSE: Short = 0x0009
}

// SSTP Attribute Types
object SstpAttributeId {
    const val SSTP_ATTRIB_NO_ERROR: Byte = 0x00
    const val SSTP_ATTRIB_ENCAPSULATED_PROTOCOL_ID: Byte = 0x01
    const val SSTP_ATTRIB_STATUS_INFO: Byte = 0x02
}

// PPP Protocol IDs
object PppProtocolId {
    const val IP: Short = 0x0021
    const val IPV6: Short = 0x0057
    const val IPCP: Short = 0x8021.toShort()
    const val LCP: Short = 0xC021.toShort()
    const val PAP: Short = 0xC023.toShort()
    const val CHAP: Short = 0xC223.toShort()
}

// PPP Codes
object PppCode {
    const val CONF_REQ: Byte = 1
    const val CONF_ACK: Byte = 2
    const val CONF_NAK: Byte = 3
    const val CONF_REJ: Byte = 4
    const val TERM_REQ: Byte = 5
    const val TERM_ACK: Byte = 6
    const val CODE_REJ: Byte = 7
    const val PROT_REJ: Byte = 8
    const val ECHO_REQ: Byte = 9
    const val ECHO_REP: Byte = 10
    const val DISC_REQ: Byte = 11
}

// LCP Option Types
object LcpOptionType {
    const val MRU: Byte = 1
    const val AUTH: Byte = 3
    const val MAGIC_NUMBER: Byte = 5
}

// IPCP Option Types
object IpcpOptionType {
    const val IP_ADDRESS: Byte = 3
    const val PRIMARY_DNS: Byte = 129.toByte()
    const val SECONDARY_DNS: Byte = 131.toByte()
}


/**
 * SSTP Packet Header
 */
data class SstpHeader(
    val version: Byte = 0x10,
    val isControl: Boolean = true,
    val length: Short = 0
)

/**
 * SSTP Control Message
 */
class SstpControlMessage(
    val messageType: Short,
    val attributes: List<SstpAttribute> = emptyList()
) {
    companion object {
        const val HEADER_SIZE = 8
    }
    
    fun toBytes(): ByteArray {
        val attributeBytes = attributes.flatMap { it.toBytes().toList() }.toByteArray()
        val totalLength = HEADER_SIZE + attributeBytes.size
        
        val buffer = ByteBuffer.allocate(totalLength)
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        // SSTP Header
        buffer.put(0x10.toByte())
        buffer.put(0x01.toByte()) // C is the least significant bit of the second header byte.
        buffer.putShort(totalLength.toShort())
        
        // Control Header
        buffer.putShort(messageType)
        buffer.putShort(attributes.size.toShort())
        
        // Attributes
        buffer.put(attributeBytes)
        
        return buffer.array()
    }
}

/**
 * SSTP Attribute
 */
data class SstpAttribute(
    val id: Byte,
    val value: ByteArray
) {
    fun toBytes(): ByteArray {
        val length = 4 + value.size
        val buffer = ByteBuffer.allocate(length)
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        buffer.put(0.toByte())
        buffer.put(id)
        buffer.putShort(length.toShort())
        buffer.put(value)
        
        return buffer.array()
    }
}

/**
 * Create SSTP Data Packet (encapsulates PPP frame)
 */
fun createDataPacket(pppFrame: ByteArray): ByteArray {
    val totalLength = 4 + pppFrame.size
    val buffer = ByteBuffer.allocate(totalLength)
    buffer.order(ByteOrder.BIG_ENDIAN)
    
    // SSTP Header for data packet
    buffer.put(0x10.toByte())
    buffer.put(0x00.toByte()) // C bit = 0
    buffer.putShort(totalLength.toShort())
    
    // PPP frame
    buffer.put(pppFrame)
    
    return buffer.array()
}

/**
 * Helper to create Config Request
 */
fun createConfRequests(protocol: Short, id: Byte, options: ByteArray): ByteArray {
    val length = 4 + options.size
    val buffer = ByteBuffer.allocate(2 + length)
    buffer.order(ByteOrder.BIG_ENDIAN)
    
    buffer.putShort(protocol)
    buffer.put(PppCode.CONF_REQ)
    buffer.put(id)
    buffer.putShort(length.toShort())
    buffer.put(options)
    
    return buffer.array()
}

/**
 * Parse SSTP packet from bytes
 */
fun parseSstpPacket(data: ByteArray): Triple<Boolean, Short, ByteArray> {
    if (data.size < 4) return Triple(false, 0, ByteArray(0))
    
    val buffer = ByteBuffer.wrap(data)
    buffer.order(ByteOrder.BIG_ENDIAN)
    
    val version = buffer.get()
    val controlByte = buffer.get()
    val isControl = (controlByte.toInt() and 0x01) != 0
    val length = buffer.short.toInt() and 0xFFFF
    if (version != 0x10.toByte() || length < 4 || length > 4095) return Triple(false, 0, ByteArray(0))
    
    if (data.size < length) return Triple(false, 0, ByteArray(0)) // Incomplete packet
    
    val payload = ByteArray(length - 4)
    if (length > 4) {
        System.arraycopy(data, 4, payload, 0, length - 4)
    }
    
    if (isControl && payload.size >= 4) {
        val msgBuffer = ByteBuffer.wrap(payload)
        msgBuffer.order(ByteOrder.BIG_ENDIAN)
        val messageType = msgBuffer.short
        return Triple(true, messageType, payload)
    }
    
    return Triple(false, 0, payload)
}
