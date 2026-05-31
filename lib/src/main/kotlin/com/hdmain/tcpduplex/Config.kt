package com.hdmain.tcpduplex

import com.hdmain.tcpduplex.protocol.Protocol
import java.time.Duration

data class HandshakeAuth(
    var preSharedKey: ByteArray? = null,
    var expectedPeerPubKeySha256: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeAuth) return false
        return preSharedKey.contentEquals(other.preSharedKey) &&
            expectedPeerPubKeySha256.contentEquals(other.expectedPeerPubKeySha256)
    }

    override fun hashCode(): Int {
        var result = preSharedKey?.contentHashCode() ?: 0
        result = 31 * result + (expectedPeerPubKeySha256?.contentHashCode() ?: 0)
        return result
    }
}

/** Tunes transport timing, queue depths, protocol revision, and handshake authentication. */
class Config {
    var dialTimeout: Duration = DEFAULT_DIAL_TIMEOUT
    var handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT
    var protocolVersion: UShort = Protocol.CURRENT_PROTOCOL_VERSION
    var maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES
    var sendQueueDepth: Int = DEFAULT_SEND_QUEUE_DEPTH
    var receiveQueueDepth: Int = DEFAULT_RECEIVE_QUEUE_DEPTH
    var onMessage: ((ByteArray) -> Unit)? = null
    var onMessageBufferDepth: Int = DEFAULT_ON_MESSAGE_BUFFER_DEPTH
    var disconnectOnSlowCallbackConsumer: Boolean = false
    var handshake: HandshakeAuth = HandshakeAuth()

    companion object {
        private val DEFAULT_DIAL_TIMEOUT = Duration.ofSeconds(30)
        private val DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(15)
        private const val DEFAULT_MAX_MESSAGE_BYTES = 512 * 1024
        private const val DEFAULT_SEND_QUEUE_DEPTH = 256
        private const val DEFAULT_RECEIVE_QUEUE_DEPTH = 256
        private const val DEFAULT_ON_MESSAGE_BUFFER_DEPTH = 128

        fun defaultConfig(): Config = Config()
    }
}

internal data class FrozenConfig(
    val dialTimeout: Duration,
    val handshakeTimeout: Duration,
    val protocolVersion: UShort,
    val maxMessageBytes: Int,
    val sendQueueDepth: Int,
    val receiveQueueDepth: Int,
    val onMessage: ((ByteArray) -> Unit)?,
    val onMessageBufferDepth: Int,
    val disconnectOnSlowCallbackConsumer: Boolean,
    val handshake: HandshakeAuth,
)

internal fun freezeConfig(cfg: Config?): FrozenConfig {
    val c = cfg ?: Config.defaultConfig()
    return FrozenConfig(
        dialTimeout = if (c.dialTimeout.isZero || c.dialTimeout.isNegative) {
            Config.defaultConfig().dialTimeout
        } else {
            c.dialTimeout
        },
        handshakeTimeout = if (c.handshakeTimeout.isZero || c.handshakeTimeout.isNegative) {
            Config.defaultConfig().handshakeTimeout
        } else {
            c.handshakeTimeout
        },
        protocolVersion = if (c.protocolVersion == 0u.toUShort()) {
            Protocol.CURRENT_PROTOCOL_VERSION
        } else {
            c.protocolVersion
        },
        maxMessageBytes = if (c.maxMessageBytes <= 0) {
            Config.defaultConfig().maxMessageBytes
        } else {
            c.maxMessageBytes
        },
        sendQueueDepth = if (c.sendQueueDepth <= 0) {
            Config.defaultConfig().sendQueueDepth
        } else {
            c.sendQueueDepth
        },
        receiveQueueDepth = if (c.receiveQueueDepth <= 0) {
            Config.defaultConfig().receiveQueueDepth
        } else {
            c.receiveQueueDepth
        },
        onMessage = c.onMessage,
        onMessageBufferDepth = if (c.onMessage != null && c.onMessageBufferDepth <= 0) {
            Config.defaultConfig().onMessageBufferDepth
        } else {
            c.onMessageBufferDepth
        },
        disconnectOnSlowCallbackConsumer = c.disconnectOnSlowCallbackConsumer,
        handshake = c.handshake,
    )
}

internal fun handshakeOpts(f: FrozenConfig): com.hdmain.tcpduplex.crypto.HandshakeOpts? {
    val h = f.handshake
    val psk = h.preSharedKey
    if ((psk == null || psk.isEmpty()) && h.expectedPeerPubKeySha256 == null) {
        return null
    }
    return com.hdmain.tcpduplex.crypto.HandshakeOpts(
        preSharedKey = psk?.copyOf(),
        expectedPeerPubKeySha256 = h.expectedPeerPubKeySha256?.copyOf(),
    )
}
