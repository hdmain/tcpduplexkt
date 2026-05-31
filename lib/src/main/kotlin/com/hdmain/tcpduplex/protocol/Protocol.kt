package com.hdmain.tcpduplex.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val MSG_TEXT: UByte = 1u
    const val MSG_PING: UByte = 2u
    const val MSG_PONG: UByte = 3u
    const val MSG_CLOSE: UByte = 4u

    private val HANDSHAKE_MAGIC = byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())

    const val CURRENT_PROTOCOL_VERSION: UShort = 1u
    const val X25519_PUB_KEY_LEN = 32
    const val MAX_RECORD_PAYLOAD = 1 shl 20

    class BadHandshakeException(message: String = "protocol: invalid handshake") : Exception(message)
    class BadFrameException(message: String = "protocol: invalid frame") : Exception(message)
    class UnsupportedVersionException(val version: UShort) :
        Exception("protocol: unsupported protocol version: $version")

    fun supportsVersion(v: UShort): Boolean = when (v) {
        1.toUShort() -> true
        else -> false
    }

    private fun validateVersion(v: UShort) {
        if (!supportsVersion(v)) {
            throw UnsupportedVersionException(v)
        }
    }

    fun writeHandshake(out: OutputStream, negotiatedVersion: UShort, pubKey: ByteArray) {
        validateVersion(negotiatedVersion)
        out.write(HANDSHAKE_MAGIC)
        val verBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        verBuf.putShort(negotiatedVersion.toShort())
        out.write(verBuf.array())
        if (pubKey.size != X25519_PUB_KEY_LEN) {
            throw BadHandshakeException()
        }
        out.write(pubKey)
    }

    fun readHandshake(input: InputStream): Pair<UShort, ByteArray> {
        val magic = readFull(input, HANDSHAKE_MAGIC.size)
        if (!magic.contentEquals(HANDSHAKE_MAGIC)) {
            throw BadHandshakeException()
        }
        val verBytes = readFull(input, 2)
        val protocolVersion = ByteBuffer.wrap(verBytes).order(ByteOrder.BIG_ENDIAN).short.toUShort()
        validateVersion(protocolVersion)
        val pubKey = readFull(input, X25519_PUB_KEY_LEN)
        return protocolVersion to pubKey
    }

    fun readRecord(input: InputStream): Pair<UByte, ByteArray> {
        val lenBuf = readFull(input, 4)
        val n = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int.toUInt()
        if (n == 0u || n > MAX_RECORD_PAYLOAD.toUInt()) {
            throw BadFrameException()
        }
        val payload = readFull(input, n.toInt())
        val msgType = payload[0].toUByte()
        val sealed = payload.copyOfRange(1, payload.size)
        return msgType to sealed
    }

    fun writeRecord(out: OutputStream, msgType: UByte, sealed: ByteArray) {
        if (sealed.size > MAX_RECORD_PAYLOAD - 1) {
            throw BadFrameException()
        }
        val n = 1 + sealed.size
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        lenBuf.putInt(n)
        out.write(lenBuf.array())
        out.write(msgType.toInt())
        out.write(sealed)
    }

    private fun readFull(input: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(buf, off, size - off)
            if (n < 0) {
                throw EOFException()
            }
            off += n
        }
        return buf
    }
}
