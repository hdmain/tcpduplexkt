package com.hdmain.tcpduplex.internal

import com.hdmain.tcpduplex.ConfigResolver
import com.hdmain.tcpduplex.TcpDuplexConnection
import com.hdmain.tcpduplex.TcpDuplexException
import com.hdmain.tcpduplex.internal.crypto.AesGcmSession
import com.hdmain.tcpduplex.internal.protocol.MessageType
import com.hdmain.tcpduplex.internal.protocol.WireCodec
import java.io.EOFException
import java.net.Socket
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class ConnectionImpl(
    override val socket: Socket,
    private val session: AesGcmSession,
    private val config: ConfigResolver.ResolvedConfig,
) : TcpDuplexConnection {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    private val sendQueue = ArrayBlockingQueue<OutboundMessage>(config.sendQueueDepth)
    private val pendingReceive = ArrayDeque<ByteArray>()
    private val receiveMonitor = Object()
    private val callbackQueue: ArrayBlockingQueue<ByteArray>? =
        config.onMessage?.let { ArrayBlockingQueue(config.onMessageBufferDepth) }

    private val receiveError = AtomicReference<TcpDuplexException?>(null)
    private val receiveClosed = AtomicBoolean(false)
    private val droppedInbound = AtomicLong(0)
    private val stopRequested = AtomicBoolean(false)
    private val shutdownStarted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val writerFinished = CountDownLatch(1)
    private val readerFinished = CountDownLatch(1)
    private val callbackFinished = CountDownLatch(if (config.onMessage != null) 1 else 0)
    private val callbackStop = AtomicBoolean(false)

    init {
        if (config.onMessage != null) {
            daemonThread("tcpduplex-callback") { callbackLoop() }
        }
        daemonThread("tcpduplex-reader") { readerLoop() }
        daemonThread("tcpduplex-writer") { writerLoop() }
    }

    override fun send(message: ByteArray) = send(message, Duration.ZERO)

    override fun send(message: ByteArray, timeout: Duration) {
        requireOpen()
        if (message.size > config.maxMessageBytes) {
            throw TcpDuplexException.MessageTooLarge(config.maxMessageBytes)
        }
        enqueueOutbound(OutboundMessage(MessageType.TEXT, message.copyOf()), timeout, "send")
    }

    override fun receive(): ByteArray = receive(Duration.ZERO)

    override fun receive(timeout: Duration): ByteArray {
        if (config.onMessage != null) throw TcpDuplexException.ReceiveDisabled()
        return waitForInbound(timeout, "receive")
    }

    override fun close() {
        shutdownAndWait(waitTimeout = null)
    }

    override fun shutdown(timeout: Duration): Boolean = shutdownAndWait(timeout)

    override val droppedInboundCount: Long
        get() = droppedInbound.get()

    private fun shutdownAndWait(waitTimeout: Duration?): Boolean {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return true
        }
        closed.set(true)
        stopRequested.set(true)

        val writerOk = awaitLatch(writerFinished, waitTimeout)
        callbackStop.set(true)
        val callbackOk = if (config.onMessage != null) {
            awaitLatch(callbackFinished, remaining(waitTimeout, writerOk))
        } else {
            true
        }

        runCatching { socket.close() }
        val readerOk = awaitLatch(readerFinished, remaining(waitTimeout, writerOk && callbackOk))
        return writerOk && callbackOk && readerOk
    }

    private fun requireOpen() {
        if (closed.get() || stopRequested.get()) {
            throw receiveError.get() ?: TcpDuplexException.ConnectionClosed()
        }
    }

    private fun enqueueOutbound(message: OutboundMessage, timeout: Duration, operation: String) {
        val deadline = if (timeout.isZero || timeout.isNegative) null
        else System.nanoTime() + timeout.toNanos()

        while (true) {
            receiveError.get()?.let { throw it }
            if (stopRequested.get()) throw TcpDuplexException.ConnectionClosed()
            if (sendQueue.offer(message)) return
            if (deadline == null) {
                Thread.sleep(POLL_INTERVAL_MS)
            } else if (System.nanoTime() >= deadline) {
                throw TcpDuplexException.TimedOut(operation)
            } else {
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    private fun waitForInbound(timeout: Duration, operation: String): ByteArray {
        val deadline = if (timeout.isZero || timeout.isNegative) null
        else System.nanoTime() + timeout.toNanos()

        synchronized(receiveMonitor) {
            while (true) {
                receiveError.get()?.let { throw it }
                if (pendingReceive.isNotEmpty()) return pendingReceive.removeFirst()
                if (receiveClosed.get()) throw receiveError.get() ?: TcpDuplexException.ConnectionClosed()
                if (deadline == null) {
                    receiveMonitor.wait()
                } else {
                    val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (remainingMs <= 0) throw TcpDuplexException.TimedOut(operation)
                    receiveMonitor.wait(remainingMs)
                }
            }
        }
    }

    private fun failReceive(error: TcpDuplexException) {
        synchronized(receiveMonitor) {
            if (receiveClosed.get()) return
            receiveClosed.set(true)
            receiveError.set(error)
            receiveMonitor.notifyAll()
        }
    }

    private fun offerInbound(payload: ByteArray) {
        synchronized(receiveMonitor) {
            while (pendingReceive.size >= config.receiveQueueDepth) {
                if (stopRequested.get()) {
                    failReceive(TcpDuplexException.ConnectionClosed())
                    return
                }
                receiveMonitor.wait(POLL_INTERVAL_MS)
            }
            pendingReceive.addLast(payload)
            receiveMonitor.notifyAll()
        }
    }

    private fun callbackLoop() {
        val handler = config.onMessage ?: return
        try {
            while (!callbackStop.get()) {
                val payload = callbackQueue?.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS) ?: continue
                handler(payload)
            }
            while (true) {
                val payload = callbackQueue?.poll() ?: break
                handler(payload)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            callbackFinished.countDown()
        }
    }

    private fun readerLoop() {
        try {
            while (true) {
                val (type, sealed) = try {
                    WireCodec.readRecord(input)
                } catch (e: EOFException) {
                    failReceive(TcpDuplexException.ConnectionClosed(e))
                    return
                } catch (e: TcpDuplexException) {
                    failReceive(e)
                    return
                } catch (e: Exception) {
                    failReceive(TcpDuplexException.OperationFailed("read", e))
                    return
                }

                val plaintext = try {
                    session.open(sealed)
                } catch (e: TcpDuplexException) {
                    failReceive(e)
                    return
                }

                when (type) {
                    MessageType.TEXT -> handleText(plaintext)
                    MessageType.PING -> enqueueOutbound(
                        OutboundMessage(MessageType.PONG, plaintext.copyOf()),
                        Duration.ZERO,
                        "pong",
                    )
                    MessageType.PONG -> Unit
                    MessageType.CLOSE -> {
                        failReceive(TcpDuplexException.ConnectionClosed(EOFException("peer closed")))
                        return
                    }
                }
            }
        } finally {
            readerFinished.countDown()
        }
    }

    private fun handleText(plaintext: ByteArray) {
        if (plaintext.size > config.maxMessageBytes) {
            failReceive(TcpDuplexException.MessageTooLarge(config.maxMessageBytes))
            return
        }
        val handler = config.onMessage
        if (handler != null) {
            val payload = plaintext.copyOf()
            if (!callbackQueue!!.offer(payload)) {
                if (config.disconnectOnSlowCallbackConsumer) {
                    failReceive(TcpDuplexException.SlowConsumer())
                    return
                }
                droppedInbound.incrementAndGet()
            }
        } else {
            if (stopRequested.get()) {
                failReceive(TcpDuplexException.ConnectionClosed())
                return
            }
            offerInbound(plaintext.copyOf())
        }
    }

    private fun writerLoop() {
        try {
            while (!stopRequested.get()) {
                val outbound = sendQueue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (!writeOutbound(outbound)) return
            }
            while (true) {
                val outbound = sendQueue.poll() ?: break
                if (!writeOutbound(outbound)) return
            }
            writeOutbound(OutboundMessage(MessageType.CLOSE, ByteArray(0)))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            writerFinished.countDown()
        }
    }

    private fun writeOutbound(outbound: OutboundMessage): Boolean {
        return try {
            val sealed = session.seal(outbound.payload)
            WireCodec.writeRecord(output, outbound.type, sealed)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun awaitLatch(latch: CountDownLatch, timeout: Duration?): Boolean {
        return when {
            timeout == null -> {
                latch.await()
                true
            }
            timeout.isZero || timeout.isNegative -> latch.count == 0L
            else -> latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    private fun remaining(timeout: Duration?, previousStepOk: Boolean): Duration? {
        if (timeout == null) return null
        if (timeout.isZero || timeout.isNegative || !previousStepOk) return Duration.ZERO
        return timeout
    }

    private data class OutboundMessage(val type: MessageType, val payload: ByteArray)

    companion object {
        private const val POLL_INTERVAL_MS = 5L

        private fun daemonThread(name: String, block: () -> Unit): Thread =
            Thread(block, name).apply {
                isDaemon = true
                start()
            }
    }
}
