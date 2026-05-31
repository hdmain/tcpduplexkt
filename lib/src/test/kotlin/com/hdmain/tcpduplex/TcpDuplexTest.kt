package com.hdmain.tcpduplex

import com.hdmain.tcpduplex.protocol.Protocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TcpDuplexTest {
    @Test
    fun connSendReceive() {
        val ss = ServerSocket(0)
        ss.use { serverSocket ->
            val srvResult = ArrayBlockingQueue<Result<Conn>>(1)

            thread {
                val raw = serverSocket.accept()
                try {
                    srvResult.put(Result.success(TcpDuplex.serveConn(raw)))
                } catch (e: Exception) {
                    srvResult.put(Result.failure(e))
                }
            }

            val cli = TcpDuplex.dial("127.0.0.1:${serverSocket.localPort}")
            try {
                val res = srvResult.poll(10, TimeUnit.SECONDS)!!
                val srvConn = res.getOrThrow()
                try {
                    val msg = "hello duplex".toByteArray()
                    cli.send(msg)
                    val got = srvConn.receive()
                    assertArrayEquals(msg, got)

                    val reply = "ack".toByteArray()
                    srvConn.send(reply)
                    val got2 = cli.receive()
                    assertArrayEquals(reply, got2)

                    srvConn.close()
                    assertThrows<Exception> {
                        cli.receive()
                    }
                } finally {
                    runCatching { srvConn.close() }
                }
            } finally {
                cli.close()
            }
        }
    }

    @Test
    fun connConcurrentSendReceive() {
        val ss = ServerSocket(0)
        ss.use { serverSocket ->
            val errRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val done = CountDownLatch(1)

            thread {
                try {
                    val raw = serverSocket.accept()
                    TcpDuplex.serveConn(raw).use { srv ->
                        repeat(50) { i ->
                            val b = srv.receive()
                            srv.send(b)
                        }
                    }
                } catch (e: Exception) {
                    errRef.set(e)
                } finally {
                    done.countDown()
                }
            }

            TcpDuplex.dial("127.0.0.1:${serverSocket.localPort}").use { cli ->
                repeat(50) { i ->
                    val payload = byteArrayOf(i.toByte())
                    cli.send(payload)
                    val out = cli.receive()
                    assertEquals(1, out.size)
                    assertEquals(i.toByte(), out[0])
                }
            }

            done.await(30, TimeUnit.SECONDS)
            errRef.get()?.let { throw it }
        }
    }

    @Test
    fun dialUnsupportedProtocolVersion() {
        val cfg = Config.defaultConfig()
        cfg.protocolVersion = 42u
        assertThrows<Protocol.UnsupportedVersionException> {
            TcpDuplex.dial("127.0.0.1:1", cfg)
        }
    }

    @Test
    fun pskHandshake() {
        val ss = ServerSocket(0)
        ss.use { serverSocket ->
            val cfg = Config.defaultConfig()
            cfg.handshake.preSharedKey = "unit-test-psk".toByteArray()

            thread {
                val raw = serverSocket.accept()
                TcpDuplex.serveConn(raw, cfg).use { srv ->
                    val msg = srv.receive()
                    assertArrayEquals("ping".toByteArray(), msg)
                    srv.send("pong".toByteArray())
                }
            }

            TcpDuplex.dial("127.0.0.1:${serverSocket.localPort}", cfg).use { cli ->
                cli.send("ping".toByteArray())
                val out = cli.receive()
                assertArrayEquals("pong".toByteArray(), out)
            }
        }
    }

    @Test
    fun receiveAfterPeerClose() {
        val ss = ServerSocket(0)
        val done = CountDownLatch(1)
        thread {
            try {
                val raw = ss.accept()
                TcpDuplex.serveConn(raw).use { it.close() }
            } finally {
                done.countDown()
            }
        }

        TcpDuplex.dial("127.0.0.1:${ss.localPort}").use { cli ->
            done.await(10, TimeUnit.SECONDS)
            assertThrows<Exception> { cli.receive() }
        }
        ss.close()
    }
}
