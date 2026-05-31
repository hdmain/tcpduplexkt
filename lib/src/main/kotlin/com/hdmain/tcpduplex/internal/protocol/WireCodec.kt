package com.hdmain.tcpduplex.internal.protocol

import com.hdmain.tcpduplex.TcpDuplexException
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WireCodec {

    fun supportsVersion(version: UShort): Boolean = when (version) {
        1.toUShort() -> true
        else -> false
    }

    fun writeHandshake(output: OutputStream, negotiatedVersion: UShort, publicKey: ByteArray) {
        requireSupportsVersion(negotiatedVersion)
        output.write(ProtocolConstants.HANDSHAKE_MAGIC)
        val versionBytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        versionBytes.putShort(negotiatedVersion.toShort())
        output.write(versionBytes.array())
        if (publicKey.size != ProtocolConstants.X25519_PUBLIC_KEY_LENGTH) {
            throw TcpDuplexException.ProtocolError("invalid handshake public key length")
        }
        output.write(publicKey)
    }

    fun readHandshake(input: InputStream): Pair<UShort, ByteArray> {
        val magic = readFully(input, ProtocolConstants.HANDSHAKE_MAGIC.size)
        if (!magic.contentEquals(ProtocolConstants.HANDSHAKE_MAGIC)) {
            throw TcpDuplexException.ProtocolError("invalid handshake magic")
        }
        val versionBytes = readFully(input, 2)
        val version = ByteBuffer.wrap(versionBytes).order(ByteOrder.BIG_ENDIAN).short.toUShort()
        requireSupportsVersion(version)
        val publicKey = readFully(input, ProtocolConstants.X25519_PUBLIC_KEY_LENGTH)
        return version to publicKey
    }

    fun writeRecord(output: OutputStream, type: MessageType, sealedPayload: ByteArray) {
        if (sealedPayload.size > ProtocolConstants.MAX_RECORD_PAYLOAD - 1) {
            throw TcpDuplexException.ProtocolError("record payload too large")
        }
        val length = 1 + sealedPayload.size
        val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length).array()
        output.write(lengthBytes)
        output.write(type.wireValue.toInt())
        output.write(sealedPayload)
    }

    fun readRecord(input: InputStream): Pair<MessageType, ByteArray> {
        val lengthBytes = readFully(input, 4)
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int
        if (length <= 0 || length > ProtocolConstants.MAX_RECORD_PAYLOAD) {
            throw TcpDuplexException.ProtocolError("invalid record length: $length")
        }
        val payload = readFully(input, length)
        val type = MessageType.fromWire(payload[0].toUByte())
            ?: throw TcpDuplexException.ProtocolError("unknown message type: ${payload[0]}")
        return type to payload.copyOfRange(1, payload.size)
    }

    private fun requireSupportsVersion(version: UShort) {
        if (!supportsVersion(version)) {
            throw TcpDuplexException.UnsupportedProtocolVersion(version)
        }
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw EOFException("unexpected end of stream")
            offset += read
        }
        return buffer
    }
}
