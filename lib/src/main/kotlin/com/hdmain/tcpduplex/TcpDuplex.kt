package com.hdmain.tcpduplex

import com.hdmain.tcpduplex.crypto.clientHandshake
import com.hdmain.tcpduplex.crypto.serverHandshake
import com.hdmain.tcpduplex.protocol.Protocol
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

object TcpDuplex {
    /** Equivalent to [dial] with default config and no deadline. */
    fun dial(address: String): Conn = dial(address, null, null)

    /** Dials TCP, negotiates tcpduplex with protocolVersion from [cfg], and returns a running [Conn]. */
    fun dial(address: String, cfg: Config?, connectTimeoutMillis: Long? = null): Conn {
        val fc = freezeConfig(cfg)
        if (!Protocol.supportsVersion(fc.protocolVersion)) {
            throw Protocol.UnsupportedVersionException(fc.protocolVersion)
        }

        val socket = Socket()
        try {
            if (connectTimeoutMillis != null) {
                socket.connect(parseAddress(address), connectTimeoutMillis.toInt())
            } else {
                socket.connect(parseAddress(address), fc.dialTimeout.toMillis().toInt())
            }
            val session = clientHandshakeContext(socket, fc)
            return Conn(socket, session, fc)
        } catch (e: Exception) {
            socket.close()
            throw wrap("dial", e) ?: e
        }
    }

    /** Equivalent to [serveConn] with default config. */
    fun serveConn(socket: Socket): Conn = serveConn(socket, null)

    /** Completes the listener handshake and returns a running [Conn]. */
    fun serveConn(socket: Socket, cfg: Config?): Conn {
        val fc = freezeConfig(cfg)
        return try {
            val session = serverHandshakeContext(socket, fc)
            Conn(socket, session, fc)
        } catch (e: Exception) {
            socket.close()
            throw wrap("handshake", e) ?: e
        }
    }

    /** Opens a TCP listener. [cfg] may be null (defaults applied per connection). */
    fun listen(port: Int, cfg: Config? = null): Server = listen("0.0.0.0", port, cfg)

    fun listen(host: String, port: Int, cfg: Config? = null): Server {
        return try {
            val ss = ServerSocket()
            ss.bind(InetSocketAddress(host, port))
            Server(cfg, ss)
        } catch (e: Exception) {
            throw wrap("listen", e) ?: e
        }
    }

    private fun parseAddress(address: String): InetSocketAddress {
        val lastColon = address.lastIndexOf(':')
        if (lastColon <= 0) {
            throw IllegalArgumentException("invalid address: $address")
        }
        val host = address.substring(0, lastColon)
        val port = address.substring(lastColon + 1).toInt()
        return InetSocketAddress(host, port)
    }

    private fun clientHandshakeContext(socket: Socket, fc: FrozenConfig): com.hdmain.tcpduplex.crypto.Session {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        if (fc.handshakeTimeout.toMillis() > 0) {
            socket.soTimeout = fc.handshakeTimeout.toMillis().toInt()
        }
        return try {
            clientHandshake(input, output, fc.protocolVersion, handshakeOpts(fc))
        } finally {
            socket.soTimeout = 0
        }
    }

    private fun serverHandshakeContext(socket: Socket, fc: FrozenConfig): com.hdmain.tcpduplex.crypto.Session {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        if (fc.handshakeTimeout.toMillis() > 0) {
            socket.soTimeout = fc.handshakeTimeout.toMillis().toInt()
        }
        return try {
            serverHandshake(input, output, handshakeOpts(fc)).first
        } finally {
            socket.soTimeout = 0
        }
    }
}

/** Wraps a [ServerSocket] configured with shared tcpduplex defaults for accepted peers. */
class Server internal constructor(
    private val cfg: Config?,
    private val serverSocket: ServerSocket,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)

    fun addr(): InetSocketAddress = serverSocket.localSocketAddress as InetSocketAddress

    override fun close() {
        stopped.set(true)
        serverSocket.close()
    }

    /**
     * Accepts connections until [stopWhen] returns true or accept fails.
     * Each accepted socket is passed to [onConnect] after handshake.
     */
    fun serve(stopWhen: () -> Boolean, onConnect: (Conn) -> Unit) {
        while (!stopWhen() && !stopped.get()) {
            val raw = try {
                serverSocket.accept()
            } catch (_: Exception) {
                if (stopWhen() || stopped.get()) return
                continue
            }
            Thread({
                try {
                    val conn = TcpDuplex.serveConn(raw, cfg)
                    onConnect(conn)
                } catch (_: Exception) {
                    raw.close()
                }
            }, "tcpduplex-accept").apply { isDaemon = true }.start()
        }
    }
}
