package com.hdmain.tcpduplex

import com.hdmain.tcpduplex.internal.ConnectionImpl
import com.hdmain.tcpduplex.internal.ServerImpl
import com.hdmain.tcpduplex.internal.Transport
import com.hdmain.tcpduplex.internal.protocol.ProtocolConstants
import com.hdmain.tcpduplex.internal.protocol.WireCodec
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration

/**
 * Entry point for establishing encrypted full-duplex tcpduplex sessions.
 *
 * Wire-compatible with the Go reference: https://github.com/hdmain/tcpduplex
 */
object TcpDuplex {

    /** Supported wire protocol revision negotiated during handshake. */
    const val CURRENT_PROTOCOL_VERSION: UShort = ProtocolConstants.CURRENT_VERSION

    /** Connects to [host]:[port] with [config]. */
    fun connect(
        host: String,
        port: Int,
        config: TcpDuplexConfig = TcpDuplexConfig.DEFAULT,
    ): TcpDuplexConnection = connect("$host:$port", config)

    /** Connects to [address] in `host:port` form with [config]. */
    fun connect(
        address: String,
        config: TcpDuplexConfig = TcpDuplexConfig.DEFAULT,
    ): TcpDuplexConnection {
        val resolved = ConfigResolver.resolve(config)
        if (!WireCodec.supportsVersion(resolved.protocolVersion)) {
            throw TcpDuplexException.UnsupportedProtocolVersion(resolved.protocolVersion)
        }

        val socket = Socket()
        try {
            socket.connect(
                AddressParser.parse(address),
                resolved.dialTimeout.toMillis().toInt().coerceAtLeast(1),
            )
            val session = Transport.performClientHandshake(socket, resolved)
            return ConnectionImpl(socket, session, resolved)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw TcpDuplexException.wrap("connect", e)
        }
    }

    /** Completes the server-side handshake on an already-accepted [socket]. */
    fun accept(
        socket: Socket,
        config: TcpDuplexConfig = TcpDuplexConfig.DEFAULT,
    ): TcpDuplexConnection {
        val resolved = ConfigResolver.resolve(config)
        return try {
            val session = Transport.performServerHandshake(socket, resolved)
            ConnectionImpl(socket, session, resolved)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw TcpDuplexException.wrap("accept", e)
        }
    }

    /** Binds a TCP listener on [host]:[port]. */
    fun listen(
        host: String = "0.0.0.0",
        port: Int,
        config: TcpDuplexConfig = TcpDuplexConfig.DEFAULT,
    ): TcpDuplexServer {
        return try {
            val serverSocket = ServerSocket()
            serverSocket.bind(InetSocketAddress(host, port))
            ServerImpl(config, serverSocket)
        } catch (e: Exception) {
            throw TcpDuplexException.wrap("listen", e)
        }
    }

    /** @see connect */
    @Deprecated("Use connect()", ReplaceWith("connect(address, config)"))
    fun dial(address: String, config: TcpDuplexConfig? = null): TcpDuplexConnection =
        connect(address, config ?: TcpDuplexConfig.DEFAULT)

    /** @see accept */
    @Deprecated("Use accept()", ReplaceWith("accept(socket, config)"))
    fun serveConn(socket: Socket, config: TcpDuplexConfig? = null): TcpDuplexConnection =
        accept(socket, config ?: TcpDuplexConfig.DEFAULT)
}

internal object AddressParser {
    fun parse(address: String): InetSocketAddress {
        val lastColon = address.lastIndexOf(':')
        require(lastColon > 0) { "invalid address (expected host:port): $address" }
        val host = address.substring(0, lastColon)
        val port = address.substring(lastColon + 1).toIntOrNull()
            ?: throw IllegalArgumentException("invalid port in address: $address")
        return InetSocketAddress(host, port)
    }
}

internal object ConfigResolver {
    data class ResolvedConfig(
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

    fun resolve(config: TcpDuplexConfig): ResolvedConfig {
        val defaults = TcpDuplexConfig.DEFAULT
        return ResolvedConfig(
            dialTimeout = config.dialTimeout.takeIf { !it.isZero && !it.isNegative } ?: defaults.dialTimeout,
            handshakeTimeout = config.handshakeTimeout.takeIf { !it.isZero && !it.isNegative }
                ?: defaults.handshakeTimeout,
            protocolVersion = config.protocolVersion.takeIf { it != 0u.toUShort() }
                ?: defaults.protocolVersion,
            maxMessageBytes = config.maxMessageBytes.takeIf { it > 0 } ?: defaults.maxMessageBytes,
            sendQueueDepth = config.sendQueueDepth.takeIf { it > 0 } ?: defaults.sendQueueDepth,
            receiveQueueDepth = config.receiveQueueDepth.takeIf { it > 0 } ?: defaults.receiveQueueDepth,
            onMessage = config.onMessage,
            onMessageBufferDepth = if (config.onMessage != null && config.onMessageBufferDepth <= 0) {
                defaults.onMessageBufferDepth
            } else {
                config.onMessageBufferDepth
            },
            disconnectOnSlowCallbackConsumer = config.disconnectOnSlowCallbackConsumer,
            handshake = config.handshake,
        )
    }
}
