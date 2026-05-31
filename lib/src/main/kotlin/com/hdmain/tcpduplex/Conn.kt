package com.hdmain.tcpduplex

import com.hdmain.tcpduplex.crypto.Session
import com.hdmain.tcpduplex.protocol.Protocol
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private data class Outbound(val typ: UByte, val pt: ByteArray)

/**
 * Encrypted full-duplex tcpduplex session after handshake.
 * Writes are serialized through a dedicated writer thread; reads dispatch either to [receive]
 * or—when configured—to [Config.onMessage].
 */
class Conn internal constructor(
    private val socket: Socket,
    private val session: Session,
    private val cfg: FrozenConfig,
) : AutoCloseable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    private val sendQueue = ArrayBlockingQueue<Outbound>(cfg.sendQueueDepth)
    private val pendingRecv = ArrayDeque<ByteArray>()
    private val recvLock = Object()
    private val msgJobs: ArrayBlockingQueue<ByteArray>? =
        if (cfg.onMessage != null) ArrayBlockingQueue(cfg.onMessageBufferDepth) else null

    private val recvErr = AtomicReference<Throwable?>(null)
    private val recvClosed = AtomicBoolean(false)
    private val callbackDropped = AtomicLong(0)
    private val stopSend = AtomicBoolean(false)
    private val shutdownStarted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val writeDone = CountDownLatch(1)
    private val readDone = CountDownLatch(1)
    private val deliverDone = CountDownLatch(if (cfg.onMessage != null) 1 else 0)

    private val deliverStop = AtomicBoolean(false)

    init {
        if (cfg.onMessage != null) {
            Thread({ deliverLoop() }, "tcpduplex-deliver").apply { isDaemon = true }.start()
        }
        Thread({ readLoop() }, "tcpduplex-read").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "tcpduplex-write").apply { isDaemon = true }.start()
    }

    private fun deliverLoop() {
        try {
            val fn = cfg.onMessage ?: return
            while (!deliverStop.get()) {
                val payload = msgJobs?.poll(100, TimeUnit.MILLISECONDS) ?: continue
                fn(payload)
            }
            while (true) {
                val payload = msgJobs?.poll() ?: break
                fn(payload)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            deliverDone.countDown()
        }
    }

    /** Queues an application message (encrypted as MsgText). */
    fun send(payload: ByteArray) {
        send(null, payload)
    }

    /** Waits until [payload] is accepted into the outbound queue or the deadline elapses. */
    fun send(deadlineMillis: Long?, payload: ByteArray) {
        if (closed.get()) throw ClosedException()
        if (payload.size > cfg.maxMessageBytes) throw MessageTooLargeException()
        val body = payload.copyOf()
        val outbound = Outbound(Protocol.MSG_TEXT, body)
        if (deadlineMillis == null) {
            while (true) {
                if (stopSend.get()) throw ClosedException()
                if (sendQueue.offer(outbound)) return
                Thread.sleep(1)
            }
        } else {
            val deadline = System.currentTimeMillis() + deadlineMillis
            while (System.currentTimeMillis() < deadline) {
                if (stopSend.get()) throw ClosedException()
                if (sendQueue.offer(outbound)) return
                Thread.sleep(1)
            }
            throw java.util.concurrent.TimeoutException()
        }
    }

    /** Blocks until the next application message or an error (requires onMessage == null). */
    fun receive(): ByteArray = receive(null)

    /** Waits for the next MsgText or termination subject to an optional deadline. */
    fun receive(deadlineMillis: Long?): ByteArray {
        if (cfg.onMessage != null) throw ReceiveDisabledException()
        val deadlineAt = if (deadlineMillis != null) System.currentTimeMillis() + deadlineMillis else null
        synchronized(recvLock) {
            while (true) {
                recvErr.get()?.let { throw it }
                if (pendingRecv.isNotEmpty()) {
                    return pendingRecv.removeFirst()
                }
                if (recvClosed.get()) {
                    throw recvErr.get() ?: ClosedException()
                }
                if (deadlineAt == null) {
                    recvLock.wait()
                } else {
                    val remaining = deadlineAt - System.currentTimeMillis()
                    if (remaining <= 0) throw java.util.concurrent.TimeoutException()
                    recvLock.wait(remaining)
                }
            }
        }
    }

    /** Counts inbound messages dropped because onMessageBufferDepth was exhausted. */
    fun callbackDropped(): Long = callbackDropped.get()

    override fun close() {
        shutdown(null)
    }

    /** Tears down the session honoring an optional wait deadline. */
    fun shutdown(waitMillis: Long?) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            throw ClosedException()
        }
        closed.set(true)
        stopSend.set(true)

        awaitLatch(writeDone, waitMillis)
        deliverStop.set(true)
        if (cfg.onMessage != null) {
            awaitLatch(deliverDone, waitMillis)
        }

        val closeErr = runCatching { socket.close() }.exceptionOrNull()
        awaitLatch(readDone, waitMillis)
        if (closeErr != null) throw closeErr
    }

    private fun awaitLatch(latch: CountDownLatch, waitMillis: Long?) {
        if (waitMillis == null) {
            latch.await()
        } else if (!latch.await(waitMillis, TimeUnit.MILLISECONDS)) {
            throw java.util.concurrent.TimeoutException()
        }
    }

    private fun abortRecv(err: Throwable?) {
        synchronized(recvLock) {
            if (recvClosed.get()) return
            recvClosed.set(true)
            recvErr.set(err ?: EOFException())
            recvLock.notifyAll()
        }
    }

    private fun enqueueRecv(payload: ByteArray) {
        synchronized(recvLock) {
            while (pendingRecv.size >= cfg.receiveQueueDepth) {
                if (stopSend.get()) {
                    abortRecv(ClosedException())
                    return
                }
                recvLock.wait(10)
            }
            pendingRecv.addLast(payload)
            recvLock.notifyAll()
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                val (msgType, sealed) = try {
                    Protocol.readRecord(input)
                } catch (e: Exception) {
                    abortRecv(e)
                    return
                }
                val pt = try {
                    session.open(sealed)
                } catch (e: Exception) {
                    abortRecv(e)
                    return
                }
                when (msgType) {
                    Protocol.MSG_TEXT -> {
                        if (pt.size > cfg.maxMessageBytes) {
                            abortRecv(MessageTooLargeException())
                            return
                        }
                        if (cfg.onMessage != null) {
                            val payload = pt.copyOf()
                            if (!msgJobs!!.offer(payload)) {
                                if (cfg.disconnectOnSlowCallbackConsumer) {
                                    abortRecv(SlowConsumerException())
                                    return
                                }
                                callbackDropped.incrementAndGet()
                            }
                        } else {
                            if (stopSend.get()) {
                                abortRecv(ClosedException())
                                return
                            }
                            enqueueRecv(pt.copyOf())
                        }
                    }
                    Protocol.MSG_PING -> {
                        val reply = pt.copyOf()
                        while (true) {
                            if (stopSend.get()) {
                                abortRecv(ClosedException())
                                return
                            }
                            if (sendQueue.offer(Outbound(Protocol.MSG_PONG, reply))) break
                            Thread.sleep(1)
                        }
                    }
                    Protocol.MSG_PONG -> Unit
                    Protocol.MSG_CLOSE -> {
                        abortRecv(EOFException())
                        return
                    }
                    else -> {
                        abortRecv(Protocol.BadFrameException())
                        return
                    }
                }
            }
        } finally {
            readDone.countDown()
        }
    }

    private fun writeLoop() {
        try {
            fun writeOut(out: Outbound): Boolean {
                return try {
                    val sealed = session.seal(out.pt)
                    Protocol.writeRecord(output, out.typ, sealed)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            while (!stopSend.get()) {
                val out = sendQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!writeOut(out)) return
            }

            // flush remaining outbound messages
            flush@ while (true) {
                val out = sendQueue.poll() ?: break@flush
                if (!writeOut(out)) return
            }
            writeOut(Outbound(Protocol.MSG_CLOSE, ByteArray(0)))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            writeDone.countDown()
        }
    }

    /** Returns the TCP socket wrapped by this session. */
    fun underlying(): Socket = socket
}
