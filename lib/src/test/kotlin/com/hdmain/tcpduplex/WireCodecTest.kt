package com.hdmain.tcpduplex

import com.hdmain.tcpduplex.internal.protocol.WireCodec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WireCodecTest {

    @Test
    fun handshakeRoundTrip() {
        val publicKey = ByteArray(32) { it.toByte() }
        val output = ByteArrayOutputStream()
        WireCodec.writeHandshake(output, 1u, publicKey)

        val input = ByteArrayInputStream(output.toByteArray())
        val (version, readKey) = WireCodec.readHandshake(input)
        assertEquals(1.toUShort(), version)
        assertArrayEquals(publicKey, readKey)
    }

    @Test
    fun recordRoundTrip() {
        val sealed = byteArrayOf(1, 2, 3, 4, 5)
        val output = ByteArrayOutputStream()
        WireCodec.writeRecord(output, com.hdmain.tcpduplex.internal.protocol.MessageType.TEXT, sealed)

        val input = ByteArrayInputStream(output.toByteArray())
        val (type, payload) = WireCodec.readRecord(input)
        assertEquals(com.hdmain.tcpduplex.internal.protocol.MessageType.TEXT, type)
        assertArrayEquals(sealed, payload)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertThrows<TcpDuplexException.UnsupportedProtocolVersion> {
            WireCodec.writeHandshake(ByteArrayOutputStream(), 99u, ByteArray(32))
        }
    }
}
