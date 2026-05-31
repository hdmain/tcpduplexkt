package com.hdmain.tcpduplex

import com.hdmain.tcpduplex.crypto.HandshakeAuthenticationException
import com.hdmain.tcpduplex.crypto.PeerFingerprintMismatchException
import com.hdmain.tcpduplex.protocol.Protocol

class ClosedException : Exception("tcpduplex: connection closed")
class ReceiveDisabledException : Exception("tcpduplex: Receive unavailable while OnMessage is configured")
class MessageTooLargeException : Exception("tcpduplex: message exceeds MaxMessageBytes")
class InboundDroppedException : Exception("tcpduplex: inbound OnMessage buffer full")
class SlowConsumerException : Exception("tcpduplex: disconnected due to slow OnMessage consumer")

val ErrUnsupportedProtocol: Protocol.UnsupportedVersionException
    get() = Protocol.UnsupportedVersionException(0u)

val ErrHandshakeAuthentication: HandshakeAuthenticationException
    get() = HandshakeAuthenticationException()

val ErrPeerFingerprintMismatch: PeerFingerprintMismatchException
    get() = PeerFingerprintMismatchException()

class TcpDuplexException(val op: String, override val cause: Throwable) :
    Exception("tcpduplex $op: ${cause.message}", cause)

fun wrap(op: String, err: Throwable?): Throwable? {
    if (err == null) return null
    return TcpDuplexException(op, err)
}
