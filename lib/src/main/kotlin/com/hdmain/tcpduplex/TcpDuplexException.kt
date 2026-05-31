package com.hdmain.tcpduplex

/**
 * Typed errors surfaced by the public API. Use [TcpDuplexException.isCausedBy] for classification.
 */
sealed class TcpDuplexException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The session is closed or shutting down. */
    class ConnectionClosed(cause: Throwable? = null) :
        TcpDuplexException("tcpduplex: connection closed", cause)

    /** [TcpDuplexConnection.receive] was called while [TcpDuplexConfig.onMessage] is configured. */
    class ReceiveDisabled :
        TcpDuplexException("tcpduplex: receive unavailable while onMessage is configured")

    /** Plaintext payload exceeds [TcpDuplexConfig.maxMessageBytes]. */
    class MessageTooLarge(val maxBytes: Int) :
        TcpDuplexException("tcpduplex: message exceeds maxMessageBytes ($maxBytes)")

    /** Inbound callback buffer was full and the message was dropped. */
    class InboundDropped :
        TcpDuplexException("tcpduplex: inbound onMessage buffer full")

    /** Session torn down because the callback consumer could not keep up. */
    class SlowConsumer :
        TcpDuplexException("tcpduplex: disconnected due to slow onMessage consumer")

    /** Wire protocol revision is not supported. */
    class UnsupportedProtocolVersion(val version: UShort) :
        TcpDuplexException("tcpduplex: unsupported protocol version: $version")

    /** Handshake negotiation or optional authentication failed. */
    class HandshakeFailed(cause: Throwable) :
        TcpDuplexException("tcpduplex: handshake failed: ${cause.message}", cause)

    /** Peer public key fingerprint did not match the configured expectation. */
    class PeerFingerprintMismatch :
        TcpDuplexException("tcpduplex: peer public key fingerprint mismatch")

    /** Invalid handshake or record framing on the wire. */
    class ProtocolError(message: String, cause: Throwable? = null) :
        TcpDuplexException("tcpduplex: $message", cause)

    /** A blocking operation exceeded its deadline. */
    class TimedOut(val operation: String) :
        TcpDuplexException("tcpduplex: $operation timed out")

    /** Transport or I/O failure during an operation. */
    class OperationFailed(val operation: String, cause: Throwable) :
        TcpDuplexException("tcpduplex: $operation failed: ${cause.message}", cause)

    companion object {
        fun isCausedBy(error: Throwable, type: Class<out TcpDuplexException>): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (type.isInstance(current)) return true
                current = current.cause
            }
            return false
        }
    }
}

internal fun TcpDuplexException.Companion.wrap(operation: String, error: Throwable): TcpDuplexException =
    when (error) {
        is TcpDuplexException -> error
        else -> TcpDuplexException.OperationFailed(operation, error)
    }
