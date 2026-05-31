package com.hdmain.tcpduplex.internal

import com.hdmain.tcpduplex.TcpDuplex
import com.hdmain.tcpduplex.TcpDuplexConfig
import com.hdmain.tcpduplex.TcpDuplexConnection
import com.hdmain.tcpduplex.TcpDuplexServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

internal class ServerImpl(
    private val config: TcpDuplexConfig,
    private val serverSocket: ServerSocket,
) : TcpDuplexServer {

    private val stopped = AtomicBoolean(false)

    override val address: InetSocketAddress
        get() = serverSocket.localSocketAddress as InetSocketAddress

    override fun serve(stopWhen: () -> Boolean, handler: (TcpDuplexConnection) -> Unit) {
        while (!stopWhen() && !stopped.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                if (stopWhen() || stopped.get()) return
                continue
            }
            Thread({
                try {
                    handler(TcpDuplex.accept(socket, config))
                } catch (_: Exception) {
                    runCatching { socket.close() }
                }
            }, "tcpduplex-accept-${socket.port}").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun close() {
        stopped.set(true)
        runCatching { serverSocket.close() }
    }
}
