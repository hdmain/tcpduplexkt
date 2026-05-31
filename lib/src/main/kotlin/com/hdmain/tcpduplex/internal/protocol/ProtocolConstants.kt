package com.hdmain.tcpduplex.internal.protocol

object ProtocolConstants {
    const val CURRENT_VERSION: UShort = 1u
    const val X25519_PUBLIC_KEY_LENGTH = 32
    const val MAX_RECORD_PAYLOAD = 1 shl 20
    val HANDSHAKE_MAGIC = byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())
}

enum class MessageType(val wireValue: UByte) {
    TEXT(1u),
    PING(2u),
    PONG(3u),
    CLOSE(4u),
    ;

    companion object {
        fun fromWire(value: UByte): MessageType? = entries.find { it.wireValue == value }
    }
}
