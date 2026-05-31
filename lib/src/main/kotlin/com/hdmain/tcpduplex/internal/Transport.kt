package com.hdmain.tcpduplex.internal

import com.hdmain.tcpduplex.ConfigResolver
import com.hdmain.tcpduplex.TcpDuplexException
import com.hdmain.tcpduplex.internal.crypto.HandshakeEngine
import java.net.Socket

internal object Transport {

    fun performClientHandshake(
        socket: Socket,
        config: ConfigResolver.ResolvedConfig,
    ) = withSocketTimeout(socket, config.handshakeTimeout) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            HandshakeEngine.performClientHandshake(
                input = input,
                output = output,
                negotiatedVersion = config.protocolVersion,
                options = HandshakeEngine.optionsFrom(config.handshake),
            )
        } catch (e: TcpDuplexException) {
            throw e
        } catch (e: Exception) {
            throw TcpDuplexException.HandshakeFailed(e)
        }
    }

    fun performServerHandshake(
        socket: Socket,
        config: ConfigResolver.ResolvedConfig,
    ) = withSocketTimeout(socket, config.handshakeTimeout) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            HandshakeEngine.performServerHandshake(
                input = input,
                output = output,
                options = HandshakeEngine.optionsFrom(config.handshake),
            )
        } catch (e: TcpDuplexException) {
            throw e
        } catch (e: Exception) {
            throw TcpDuplexException.HandshakeFailed(e)
        }
    }

    private inline fun <T> withSocketTimeout(socket: Socket, timeout: java.time.Duration, block: () -> T): T {
        val previous = socket.soTimeout
        if (timeout.toMillis() > 0) {
            socket.soTimeout = timeout.toMillis().toInt().coerceAtLeast(1)
        }
        return try {
            block()
        } finally {
            socket.soTimeout = previous
        }
    }
}
