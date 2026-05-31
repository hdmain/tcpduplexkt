package com.hdmain.tcpduplex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TcpDuplexIntegrationTest {

    @Test
    fun sendReceiveRoundTrip() {
        ServerSocket(0).use { listener ->
            val serverResult = ArrayBlockingQueue<Result<TcpDuplexConnection>>(1)
            thread {
                val socket = listener.accept()
                runCatching { TcpDuplex.accept(socket) }
                    .fold(onSuccess = { serverResult.put(Result.success(it)) }, onFailure = { serverResult.put(Result.failure(it)) })
            }

            TcpDuplex.connect("127.0.0.1", listener.localPort).use { client ->
                val server = serverResult.poll(10, TimeUnit.SECONDS)!!.getOrThrow()
                server.use {
                    val message = "hello duplex".toByteArray()
                    client.send(message)
                    assertArrayEquals(message, server.receive())

                    val reply = "ack".toByteArray()
                    server.send(reply)
                    assertArrayEquals(reply, client.receive())
                }
                assertThrows<TcpDuplexException> { client.receive() }
            }
        }
    }

    @Test
    fun concurrentEcho() {
        ServerSocket(0).use { listener ->
            val done = CountDownLatch(1)
            val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

            thread {
                try {
                    TcpDuplex.accept(listener.accept()).use { server ->
                        repeat(50) {
                            val payload = server.receive()
                            server.send(payload)
                        }
                    }
                } catch (e: Throwable) {
                    error.set(e)
                } finally {
                    done.countDown()
                }
            }

            TcpDuplex.connect("127.0.0.1", listener.localPort).use { client ->
                repeat(50) { index ->
                    val payload = byteArrayOf(index.toByte())
                    client.send(payload)
                    assertArrayEquals(payload, client.receive())
                }
            }

            done.await(30, TimeUnit.SECONDS)
            error.get()?.let { throw it }
        }
    }

    @Test
    fun rejectsUnsupportedProtocolVersion() {
        val config = TcpDuplexConfig(protocolVersion = 42u)
        assertThrows<TcpDuplexException.UnsupportedProtocolVersion> {
            TcpDuplex.connect("127.0.0.1", 1, config)
        }
    }

    @Test
    fun preSharedKeyHandshake() {
        ServerSocket(0).use { listener ->
            val config = TcpDuplexConfig(handshake = HandshakeAuth(preSharedKey = "unit-test-psk".toByteArray()))
            thread {
                TcpDuplex.accept(listener.accept(), config).use { server ->
                    assertArrayEquals("ping".toByteArray(), server.receive())
                    server.send("pong".toByteArray())
                }
            }
            TcpDuplex.connect("127.0.0.1", listener.localPort, config).use { client ->
                client.send("ping".toByteArray())
                assertArrayEquals("pong".toByteArray(), client.receive())
            }
        }
    }

    @Test
    fun receiveFailsAfterPeerClose() {
        ServerSocket(0).use { listener ->
            val closed = CountDownLatch(1)
            thread {
                TcpDuplex.accept(listener.accept()).use { it.close() }
                closed.countDown()
            }
            TcpDuplex.connect("127.0.0.1", listener.localPort).use { client ->
                closed.await(10, TimeUnit.SECONDS)
                assertThrows<TcpDuplexException> { client.receive() }
            }
        }
    }
}
