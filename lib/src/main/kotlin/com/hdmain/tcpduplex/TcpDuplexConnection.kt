package com.hdmain.tcpduplex

import java.net.Socket
import java.time.Duration

/**
 * Encrypted full-duplex session after the tcpduplex handshake completes.
 *
 * Thread-safe for concurrent [send] and [receive] from different threads.
 * [close] is idempotent; a graceful shutdown flushes outbound data and sends a close record.
 */
interface TcpDuplexConnection : AutoCloseable {

    /** Queues an application message (encrypted as MsgText). Blocks until queued or the session closes. */
    fun send(message: ByteArray)

    /**
     * Queues [message], waiting at most [timeout].
     *
     * @throws TcpDuplexException.TimedOut when the outbound queue stays full until the deadline.
     */
    fun send(message: ByteArray, timeout: Duration)

    /**
     * Blocks until the next inbound application message arrives.
     *
     * @throws TcpDuplexException.ReceiveDisabled when [TcpDuplexConfig.onMessage] is configured.
     */
    fun receive(): ByteArray

    /**
     * Waits up to [timeout] for the next inbound message.
     *
     * @throws TcpDuplexException.TimedOut when no message arrives in time.
     */
    fun receive(timeout: Duration): ByteArray

    /**
     * Gracefully shuts down the session.
     *
     * @param timeout Maximum time to wait for writer/reader/callback completion.
     *   [Duration.ZERO] returns immediately without waiting (socket may close before flush completes).
     *   Use [close] for an unlimited graceful shutdown.
     */
    fun shutdown(timeout: Duration = Duration.ZERO): Boolean

    /** Inbound messages dropped because [TcpDuplexConfig.onMessageBufferDepth] was exhausted. */
    val droppedInboundCount: Long

    /** Underlying TCP socket for advanced integration (TLS wrapping, metrics, etc.). */
    val socket: Socket

    override fun close()
}
