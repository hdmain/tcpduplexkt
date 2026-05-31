package com.hdmain.tcpduplex

import java.net.InetSocketAddress

/**
 * TCP listener that accepts tcpduplex sessions with shared [TcpDuplexConfig] defaults.
 */
interface TcpDuplexServer : AutoCloseable {

    val address: InetSocketAddress

    /**
     * Accepts connections until [stopWhen] returns true or the listener is [close]d.
     * Each accepted socket is handshaken and passed to [handler] on a background thread.
     */
    fun serve(stopWhen: () -> Boolean, handler: (TcpDuplexConnection) -> Unit)

    override fun close()
}
