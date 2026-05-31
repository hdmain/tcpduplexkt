package com.hdmain.tcpduplex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class ConfigResolverTest {

    @Test
    fun appliesDefaultsForZeroTimeoutsAndVersion() {
        val resolved = ConfigResolver.resolve(
            TcpDuplexConfig(
                dialTimeout = java.time.Duration.ZERO,
                handshakeTimeout = java.time.Duration.ZERO,
                protocolVersion = 0u,
            ),
        )
        assertEquals(TcpDuplexConfig.DEFAULT.dialTimeout, resolved.dialTimeout)
        assertEquals(TcpDuplexConfig.DEFAULT.handshakeTimeout, resolved.handshakeTimeout)
        assertEquals(TcpDuplexConfig.DEFAULT.protocolVersion, resolved.protocolVersion)
    }

    @Test
    fun builderProducesEquivalentConfig() {
        val built = TcpDuplexConfig.builder()
            .maxMessageBytes(1024)
            .preSharedKey("abc".toByteArray())
            .build()
        assertEquals(1024, built.maxMessageBytes)
        assertArrayEquals("abc".toByteArray(), built.handshake.preSharedKey)
    }
}
