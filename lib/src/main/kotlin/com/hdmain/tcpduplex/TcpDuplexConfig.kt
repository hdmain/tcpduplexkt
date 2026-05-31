package com.hdmain.tcpduplex

/**
 * Optional handshake authentication: pre-shared key mixing and peer public-key fingerprint pinning.
 *
 * @property preSharedKey Mixed into symmetric key derivation alongside ECDH when non-empty.
 * @property expectedPeerPublicKeySha256 When set, must equal SHA-256 of the peer's raw X25519 public key.
 */
data class HandshakeAuth(
    val preSharedKey: ByteArray? = null,
    val expectedPeerPublicKeySha256: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeAuth) return false
        return preSharedKey.contentEquals(other.preSharedKey) &&
            expectedPeerPublicKeySha256.contentEquals(other.expectedPeerPublicKeySha256)
    }

    override fun hashCode(): Int {
        var result = preSharedKey?.contentHashCode() ?: 0
        result = 31 * result + (expectedPeerPublicKeySha256?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Immutable runtime configuration for [TcpDuplex.connect], [TcpDuplex.accept], and [TcpDuplex.listen].
 */
data class TcpDuplexConfig(
    val dialTimeout: java.time.Duration = DEFAULT_DIAL_TIMEOUT,
    val handshakeTimeout: java.time.Duration = DEFAULT_HANDSHAKE_TIMEOUT,
    val protocolVersion: UShort = DEFAULT_PROTOCOL_VERSION,
    val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    val sendQueueDepth: Int = DEFAULT_SEND_QUEUE_DEPTH,
    val receiveQueueDepth: Int = DEFAULT_RECEIVE_QUEUE_DEPTH,
    val onMessage: ((ByteArray) -> Unit)? = null,
    val onMessageBufferDepth: Int = DEFAULT_ON_MESSAGE_BUFFER_DEPTH,
    val disconnectOnSlowCallbackConsumer: Boolean = false,
    val handshake: HandshakeAuth = HandshakeAuth(),
) {
    init {
        require(maxMessageBytes > 0) { "maxMessageBytes must be positive" }
        require(sendQueueDepth > 0) { "sendQueueDepth must be positive" }
        require(receiveQueueDepth > 0) { "receiveQueueDepth must be positive" }
        require(onMessageBufferDepth > 0) { "onMessageBufferDepth must be positive" }
    }

    /** Returns a copy with [onMessage] configured (disables [TcpDuplexConnection.receive]). */
    fun withOnMessage(
        bufferDepth: Int = onMessageBufferDepth,
        disconnectOnSlowConsumer: Boolean = disconnectOnSlowCallbackConsumer,
        handler: (ByteArray) -> Unit,
    ): TcpDuplexConfig = copy(
        onMessage = handler,
        onMessageBufferDepth = bufferDepth,
        disconnectOnSlowCallbackConsumer = disconnectOnSlowConsumer,
    )

    class Builder {
        private var dialTimeout: java.time.Duration = DEFAULT_DIAL_TIMEOUT
        private var handshakeTimeout: java.time.Duration = DEFAULT_HANDSHAKE_TIMEOUT
        private var protocolVersion: UShort = DEFAULT_PROTOCOL_VERSION
        private var maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES
        private var sendQueueDepth: Int = DEFAULT_SEND_QUEUE_DEPTH
        private var receiveQueueDepth: Int = DEFAULT_RECEIVE_QUEUE_DEPTH
        private var onMessage: ((ByteArray) -> Unit)? = null
        private var onMessageBufferDepth: Int = DEFAULT_ON_MESSAGE_BUFFER_DEPTH
        private var disconnectOnSlowCallbackConsumer: Boolean = false
        private var handshake: HandshakeAuth = HandshakeAuth()

        fun dialTimeout(value: java.time.Duration) = apply { dialTimeout = value }
        fun handshakeTimeout(value: java.time.Duration) = apply { handshakeTimeout = value }
        fun protocolVersion(value: UShort) = apply { protocolVersion = value }
        fun maxMessageBytes(value: Int) = apply { maxMessageBytes = value }
        fun sendQueueDepth(value: Int) = apply { sendQueueDepth = value }
        fun receiveQueueDepth(value: Int) = apply { receiveQueueDepth = value }
        fun onMessageBufferDepth(value: Int) = apply { onMessageBufferDepth = value }
        fun disconnectOnSlowCallbackConsumer(value: Boolean) = apply { disconnectOnSlowCallbackConsumer = value }
        fun onMessage(handler: (ByteArray) -> Unit) = apply { onMessage = handler }
        fun handshake(value: HandshakeAuth) = apply { handshake = value }
        fun preSharedKey(value: ByteArray) = apply { handshake = handshake.copy(preSharedKey = value.copyOf()) }
        fun expectedPeerFingerprint(value: ByteArray) =
            apply { handshake = handshake.copy(expectedPeerPublicKeySha256 = value.copyOf()) }

        fun build(): TcpDuplexConfig = TcpDuplexConfig(
            dialTimeout = dialTimeout,
            handshakeTimeout = handshakeTimeout,
            protocolVersion = protocolVersion,
            maxMessageBytes = maxMessageBytes,
            sendQueueDepth = sendQueueDepth,
            receiveQueueDepth = receiveQueueDepth,
            onMessage = onMessage,
            onMessageBufferDepth = onMessageBufferDepth,
            disconnectOnSlowCallbackConsumer = disconnectOnSlowCallbackConsumer,
            handshake = handshake,
        )
    }

    companion object {
        val DEFAULT_DIAL_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(30)
        val DEFAULT_HANDSHAKE_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(15)
        const val DEFAULT_PROTOCOL_VERSION: UShort = 1u
        const val DEFAULT_MAX_MESSAGE_BYTES: Int = 512 * 1024
        const val DEFAULT_SEND_QUEUE_DEPTH: Int = 256
        const val DEFAULT_RECEIVE_QUEUE_DEPTH: Int = 256
        const val DEFAULT_ON_MESSAGE_BUFFER_DEPTH: Int = 128

        val DEFAULT: TcpDuplexConfig = TcpDuplexConfig()

        fun builder(): Builder = Builder()
    }
}
